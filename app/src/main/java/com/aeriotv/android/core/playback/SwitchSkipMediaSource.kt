package com.aeriotv.android.core.playback

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Live raw-TS source wrapper that can drop the OLD stream's buffered samples
 * after a Dispatcharr upstream switch, on the same connection.
 *
 * Why not a seek: on Media3 1.4.1 a progressive live period has an unseekable
 * SeekMap and DATA_TYPE_MEDIA_PROGRESSIVE_LIVE, so ProgressiveMediaPeriod.seekToUs
 * maps every seek to 0, discards all samples, cancels the loader and opens a new
 * GET. A second GET is exactly what makes Dispatcharr (stream_limit 1) terminate
 * our client and restart the channel on its default stream.
 *
 * Instead, once new data is queued past the boundary, the wrapped
 * SampleStreams use the sample queue's own keyframe skip (no loader
 * involvement): video lands on a keyframe inside the new data, audio on that
 * keyframe's time. Reads are NEVER withheld: until the jump (or if it cannot
 * happen) every read is a normal read and the old buffer plays out.
 *
 * (A hard switch that held reads and reset the renderers was tried and
 * abandoned 2026-09-15: MediaCodec is not reconfigured across the in-place
 * upstream change, so the picture came up green or purple.)
 *
 * All skip state lives on the playback thread (requests are posted to it).
 */
@UnstableApi
class SwitchSkipMediaSource(inner: MediaSource) : WrappingMediaSource(inner) {

    sealed class Result {
        /** Old samples dropped; playback continues at [boundaryOffsetMs] past the boundary. */
        data class Jumped(val boundaryOffsetMs: Long, val droppedMs: Long) : Result()
        /** The playhead was already at or past the boundary; nothing to drop. */
        object AlreadyPast : Result()
        /** Skip gave up; the old buffer plays out as before. */
        data class Abandoned(val reason: String) : Result()
    }

    /** Outcome of a [requestRejoin]. */
    sealed class RejoinResult {
        /** Replayed samples dropped; [aheadAfterMs] left buffered past the landing keyframe. */
        data class Skipped(val droppedMs: Long, val aheadAfterMs: Long) : RejoinResult()
        /** The initial burst left no more than the keep-ahead; nothing dropped. */
        data class NotNeeded(val aheadMs: Long) : RejoinResult()
        data class Abandoned(val reason: String) : RejoinResult()
    }

    @Volatile private var period: SwitchSkipMediaPeriod? = null

    /** A rejoin requested before the period existed (a fresh re-prime). */
    @Volatile private var pendingRejoin: Pair<Long, (RejoinResult) -> Unit>? = null

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod {
        val p = SwitchSkipMediaPeriod(super.createPeriod(id, allocator, startPositionUs))
        period = p
        pendingRejoin?.let { (keepUs, sink) ->
            pendingRejoin = null
            p.armRejoin(keepUs, sink)
        }
        return p
    }

    /**
     * Rejoin near live after a re-prime on the same URL. Dispatcharr serves
     * its channel buffer from its start on every new connection, so a rebuild
     * replays seconds the user already saw (Streamer 2026-11-01 19:35:49:
     * buffered ahead 7.9 s at READY, 14.3 s one second later). Once that
     * initial burst has landed, video skips to the newest keyframe that still
     * leaves [keepAheadMs] buffered, and audio follows to the same time.
     * Only call this on a source that has not been prepared yet.
     */
    fun requestRejoin(
        resultHandler: Handler,
        keepAheadMs: Long,
        onResult: (RejoinResult) -> Unit,
    ) {
        pendingRejoin = (keepAheadMs * 1000L) to { r: RejoinResult -> resultHandler.post { onResult(r) } }
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val p = mediaPeriod as SwitchSkipMediaPeriod
        if (period === p) period = null
        p.abandon("period released")
        p.abandonRejoin("period released")
        super.releasePeriod(p.inner)
    }

    /**
     * Arm a skip on the current period. [playbackHandler] runs on the player's
     * playback looper; [onResult] is invoked on [resultHandler]. Arms once new
     * media reaches [minNewDataMs] past the boundary; gives up after [timeoutMs].
     * Returns false when there is no period to skip on.
     */
    fun requestSkip(
        playbackHandler: Handler,
        resultHandler: Handler,
        minNewDataMs: Long,
        timeoutMs: Long,
        onResult: (Result) -> Unit,
    ): Boolean {
        val p = period ?: return false
        playbackHandler.post {
            p.arm(minNewDataMs * 1000L, timeoutMs) { r -> resultHandler.post { onResult(r) } }
        }
        return true
    }
}

@UnstableApi
internal class SwitchSkipMediaPeriod(val inner: MediaPeriod) : MediaPeriod, MediaPeriod.Callback {

    private companion object {
        const val TAG = "AerioExoPlayer"
        /** How far into the new data the video keyframe skip may look. */
        const val LOOKAHEAD_US = 2_000_000L
        /** Non-keyframe video samples dropped in one read call before the
         *  renderer is handed back control (it retries immediately). */
        const val MAX_DROPS_PER_READ = 64
    }

    private var callback: MediaPeriod.Callback? = null
    private var wrappers: Array<SkipStream?> = emptyArray()
    private val scratch = DecoderInputBuffer.newNoDataInstance()

    // ---- skip state (playback thread) ----
    private var armed = false
    private var active = false
    private var boundaryUs = C.TIME_UNSET
    private var minNewDataUs = 0L
    private var armedAtMs = 0L
    private var activatedAtMs = 0L
    private var timeoutMs = 0L
    private var targetUs = C.TIME_UNSET
    private var firstDroppedUs = C.TIME_UNSET
    /** Highest buffered position seen while armed; its growth IS "new bytes". */
    private var lastBufferedUs = C.TIME_UNSET
    private var firstNewDataAtMs = 0L
    /** While true, no video sample at or past the boundary reaches the decoder
     *  until an IDR (H.264) / IRAP (HEVC) keyframe does. */
    private var awaitVideoKeyframe = false
    private var keyframeLogged = false
    private var resultSink: ((SwitchSkipMediaSource.Result) -> Unit)? = null

    // ---- rejoin-near-live state (playback thread) ----
    private var rejoinSink: ((SwitchSkipMediaSource.RejoinResult) -> Unit)? = null
    private var rejoinKeepUs = 0L
    private var rejoinArmedAtMs = 0L
    private var rejoinFirstDataAtMs = 0L
    private var rejoinLastBufferedUs = C.TIME_UNSET
    private var rejoinLastGrowAtMs = 0L
    private var rejoinVideoTargetUs = C.TIME_UNSET
    private var rejoinFirstUs = C.TIME_UNSET
    private var rejoinVideoDone = false

    fun armRejoin(keepUs: Long, sink: (SwitchSkipMediaSource.RejoinResult) -> Unit) {
        rejoinSink = sink
        rejoinKeepUs = keepUs
        rejoinArmedAtMs = SystemClock.elapsedRealtime()
        rejoinFirstDataAtMs = 0L
        rejoinLastBufferedUs = C.TIME_UNSET
        rejoinLastGrowAtMs = 0L
        rejoinVideoTargetUs = C.TIME_UNSET
        rejoinFirstUs = C.TIME_UNSET
        rejoinVideoDone = false
    }

    fun abandonRejoin(reason: String) {
        if (rejoinSink == null) return
        finishRejoin(SwitchSkipMediaSource.RejoinResult.Abandoned(reason))
    }

    private fun finishRejoin(r: SwitchSkipMediaSource.RejoinResult) {
        val sink = rejoinSink ?: return
        rejoinSink = null
        sink(r)
    }

    /**
     * One rejoin step for [w]. Waits until the initial burst has landed (the
     * buffered position stopped growing for [LiveRejoin.BURST_QUIET_MS], or
     * [LiveRejoin.BURST_MAX_WAIT_MS] after the first sample), then uses the
     * queue's own keyframe skip. Returns a format result the renderer must get
     * now, or null to continue with a normal read.
     */
    private fun rejoinStep(w: SkipStream, formatHolder: FormatHolder): Int? {
        val now = SystemClock.elapsedRealtime()
        if (now - rejoinArmedAtMs > LiveRejoin.TIMEOUT_MS) {
            finishRejoin(SwitchSkipMediaSource.RejoinResult.Abandoned("no burst in ${LiveRejoin.TIMEOUT_MS}ms"))
            return null
        }
        val buffered = inner.bufferedPositionUs
        if (buffered == C.TIME_UNSET || buffered == C.TIME_END_OF_SOURCE) return null
        if (rejoinLastBufferedUs == C.TIME_UNSET || buffered > rejoinLastBufferedUs) {
            if (rejoinFirstDataAtMs == 0L) rejoinFirstDataAtMs = now
            rejoinLastBufferedUs = buffered
            rejoinLastGrowAtMs = now
        }
        if (rejoinFirstDataAtMs == 0L) return null
        val isVideo = w.trackType == C.TRACK_TYPE_VIDEO
        val video = hasVideo()
        val fmt = intArrayOf(C.RESULT_NOTHING_READ)
        if (isVideo || !video) {
            if (rejoinVideoDone) return null
            if (!LiveRejoin.burstLanded(now, rejoinFirstDataAtMs, rejoinLastGrowAtMs)) return null
            val first = peekTimeUs(w, formatHolder, fmt)
            if (fmt[0] == C.RESULT_FORMAT_READ) return fmt[0]
            if (first == C.TIME_UNSET) return null
            val target = LiveRejoin.skipTargetUs(first, rejoinLastBufferedUs, rejoinKeepUs)
            if (target == C.TIME_UNSET || w.inner.skipData(target) == 0) {
                rejoinVideoDone = true
                finishRejoin(SwitchSkipMediaSource.RejoinResult.NotNeeded((rejoinLastBufferedUs - first) / 1000L))
                return null
            }
            val landed = peekTimeUs(w, formatHolder, fmt)
            rejoinFirstUs = first
            rejoinVideoTargetUs = if (landed == C.TIME_UNSET) target else landed
            rejoinVideoDone = true
            if (!video) finishRejoinSkipped()
            return if (fmt[0] == C.RESULT_FORMAT_READ) fmt[0] else null
        }
        // Audio follows the video landing time.
        if (rejoinVideoTargetUs == C.TIME_UNSET) return null
        val first = peekTimeUs(w, formatHolder, fmt)
        if (fmt[0] == C.RESULT_FORMAT_READ) return fmt[0]
        if (first != C.TIME_UNSET && first < rejoinVideoTargetUs) w.inner.skipData(rejoinVideoTargetUs)
        finishRejoinSkipped()
        return null
    }

    private fun finishRejoinSkipped() {
        finishRejoin(
            SwitchSkipMediaSource.RejoinResult.Skipped(
                droppedMs = (rejoinVideoTargetUs - rejoinFirstUs) / 1000L,
                aheadAfterMs = (rejoinLastBufferedUs - rejoinVideoTargetUs) / 1000L,
            ),
        )
    }

    fun arm(minNewDataUs: Long, timeoutMs: Long, sink: (SwitchSkipMediaSource.Result) -> Unit) {
        abandon("superseded by a new switch")
        val buffered = inner.bufferedPositionUs
        if (buffered == C.TIME_UNSET || buffered == C.TIME_END_OF_SOURCE) {
            sink(SwitchSkipMediaSource.Result.Abandoned("no buffered position"))
            return
        }
        this.boundaryUs = buffered
        this.minNewDataUs = minNewDataUs
        this.timeoutMs = timeoutMs
        armedAtMs = SystemClock.elapsedRealtime()
        targetUs = C.TIME_UNSET
        firstDroppedUs = C.TIME_UNSET
        lastBufferedUs = buffered
        firstNewDataAtMs = 0L
        awaitVideoKeyframe = true
        keyframeLogged = false
        wrappers.forEach { it?.done = false }
        resultSink = sink
        armed = true
        active = false
    }

    /** True while a skip is armed (waiting for new data or retrying). */
    val isArmed: Boolean get() = armed

    fun abandon(reason: String) {
        if (!armed) return
        finish(SwitchSkipMediaSource.Result.Abandoned(reason))
    }

    private fun finish(result: SwitchSkipMediaSource.Result) {
        armed = false
        active = false
        awaitVideoKeyframe = false
        val sink = resultSink
        resultSink = null
        sink?.invoke(result)
    }

    private fun hasVideo(): Boolean = wrappers.any { it?.trackType == C.TRACK_TYPE_VIDEO }

    /**
     * Arm -> active once the first post-switch data is loaded, bounded only by
     * the switch window ([timeoutMs], 30 s).
     *
     * AMD 2026-09-16: the old rule gave up 5 s after activation. With an 8 to
     * 10 s first byte the new stream had not produced a usable keyframe yet, so
     * the skip fell back to "play it out" and the mid-GOP burst that followed
     * reached the decoder uncut (audio fine, picture frozen). The pending skip
     * now stays armed while nothing has arrived, and the first new bytes after
     * a dry spell are the boundary candidate.
     */
    private fun maybeActivate(): Boolean {
        if (!armed) return false
        val now = SystemClock.elapsedRealtime()
        if (now - armedAtMs > timeoutMs) {
            abandon(
                if (active) "no keyframe past the boundary in ${timeoutMs}ms"
                else "no new data in ${timeoutMs}ms",
            )
            return false
        }
        val buffered = inner.bufferedPositionUs
        if (buffered != C.TIME_UNSET && buffered != C.TIME_END_OF_SOURCE) {
            if (buffered > lastBufferedUs) lastBufferedUs = buffered
            if (firstNewDataAtMs == 0L && buffered > boundaryUs) {
                firstNewDataAtMs = now
                Log.i(
                    TAG,
                    "[SWITCH] first new bytes ${(buffered - boundaryUs) / 1000}ms past the " +
                        "boundary after ${now - armedAtMs}ms",
                )
            }
            if (!active && buffered >= boundaryUs + minNewDataUs) {
                active = true
                activatedAtMs = now
            }
        }
        return active
    }

    private fun read(
        w: SkipStream,
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: Int,
    ): Int {
        if (rejoinSink != null &&
            (w.trackType == C.TRACK_TYPE_VIDEO || w.trackType == C.TRACK_TYPE_AUDIO) &&
            (readFlags and SampleStream.FLAG_PEEK) == 0
        ) {
            val formatResult = rejoinStep(w, formatHolder)
            if (formatResult != null) return formatResult
        }
        if (maybeActivate() && !w.done &&
            (w.trackType == C.TRACK_TYPE_VIDEO || w.trackType == C.TRACK_TYPE_AUDIO) &&
            (readFlags and SampleStream.FLAG_PEEK) == 0
        ) {
            val formatResult = trySkip(w, formatHolder)
            if (formatResult != null) return formatResult
        }
        if (awaitVideoKeyframe && w.trackType == C.TRACK_TYPE_VIDEO &&
            (readFlags and SampleStream.FLAG_PEEK) == 0
        ) {
            return readVideoFromKeyframe(w, formatHolder, buffer, readFlags)
        }
        // Otherwise a normal read: the skip never withholds samples, so the old
        // buffer keeps playing until (and unless) the jump happens.
        return w.inner.readData(formatHolder, buffer, readFlags)
    }

    /** Peek the next queued sample's time without consuming it. Returns
     *  C.TIME_UNSET when nothing is queued. A pending format is handed back via
     *  [formatOut] (the queue marks it delivered, so the renderer must get it). */
    private fun peekTimeUs(w: SkipStream, formatHolder: FormatHolder, formatOut: IntArray): Long {
        scratch.clear()
        val r = w.inner.readData(
            formatHolder, scratch, SampleStream.FLAG_PEEK or SampleStream.FLAG_OMIT_SAMPLE_DATA,
        )
        if (r == C.RESULT_FORMAT_READ) { formatOut[0] = r; return C.TIME_UNSET }
        if (r != C.RESULT_BUFFER_READ || scratch.isEndOfStream) return C.TIME_UNSET
        return scratch.timeUs
    }

    /**
     * The new stream must start the video decoder on a keyframe.
     *
     * The queue's keyframe skip alone is not enough: when the old buffer has
     * already run dry (AMD 2026-09-16, slow provider) the playhead is past the
     * boundary before any new byte lands, so there is nothing left to skip, and
     * Dispatcharr hands over mid-GOP. An Amlogic decoder fed non-IDR/non-IRAP
     * slices with no reference frames simply outputs nothing while audio keeps
     * playing: the frozen picture.
     *
     * So every video sample at or past the boundary is dropped until the first
     * keyframe (ExoPlayer's H.264 / H.265 sample readers flag IDR and IRAP
     * access units). The decoder therefore never sees data that depends on
     * references it does not have, which is what a flush would have been for,
     * without the renderer reset that painted green/purple in 2026-09-15. A
     * real container change (PID / codec) still arrives as a format read, which
     * MediaCodecRenderer handles as a format change on its own.
     *
     * Old-buffer samples (before the boundary) pass through untouched.
     */
    private fun readVideoFromKeyframe(
        w: SkipStream,
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: Int,
    ): Int {
        var dropped = 0
        while (true) {
            val r = w.inner.readData(formatHolder, buffer, readFlags)
            if (r != C.RESULT_BUFFER_READ || buffer.isEndOfStream) return r
            if (buffer.timeUs < boundaryUs) return r
            if (buffer.isKeyFrame) {
                onVideoKeyframe(buffer.timeUs, dropped)
                return r
            }
            buffer.clear()
            if (++dropped >= MAX_DROPS_PER_READ) return C.RESULT_NOTHING_READ
        }
    }

    /** First keyframe of the new stream reached the decoder: the gate is done. */
    private fun onVideoKeyframe(timeUs: Long, dropped: Int) {
        awaitVideoKeyframe = false
        if (!keyframeLogged) {
            keyframeLogged = true
            Log.i(
                TAG,
                "[SWITCH] keyframe found at +${(timeUs - boundaryUs) / 1000}ms past the boundary " +
                    "(dropped $dropped partial-GOP video samples)",
            )
        }
        if (armed) {
            if (targetUs == C.TIME_UNSET) targetUs = timeUs
            wrappers.forEach { if (it?.trackType == C.TRACK_TYPE_VIDEO) it.done = true }
            maybeFinish()
        }
    }

    /**
     * One NON-BLOCKING skip attempt for [w], using the queue's own keyframe
     * skip (SampleStream.skipData lands on the last keyframe at or before the
     * target, and never past what is queued). Returns a format result that must
     * be handed to the renderer now, or null to continue with a normal read.
     * Video goes first: it lands on a keyframe inside the new data, and audio
     * follows to that time. Audio never waits for video; it just reads normally
     * until the video target exists. A video attempt that lands short of the
     * boundary is retried on later reads (a later keyframe in range can only be
     * new data) until [ACTIVE_TIMEOUT_MS].
     */
    private fun trySkip(w: SkipStream, formatHolder: FormatHolder): Int? {
        val fmt = intArrayOf(C.RESULT_NOTHING_READ)
        val first = peekTimeUs(w, formatHolder, fmt)
        if (fmt[0] == C.RESULT_FORMAT_READ) return fmt[0]
        if (first == C.TIME_UNSET) return null
        val isVideo = w.trackType == C.TRACK_TYPE_VIDEO
        val video = hasVideo()
        if (!isVideo && video && targetUs == C.TIME_UNSET) return null
        if (first >= boundaryUs && (isVideo || !video)) {
            // Already playing past the boundary: nothing (more) to drop. Video
            // still waits for the keyframe gate to pass a real IDR / IRAP.
            if (isVideo && awaitVideoKeyframe) return null
            w.done = true
            if (targetUs == C.TIME_UNSET) targetUs = first
            maybeFinish()
            return null
        }
        val skipTo = if (isVideo || !video) boundaryUs + LOOKAHEAD_US else targetUs
        if (first >= skipTo) {
            w.done = true
            maybeFinish()
            return null
        }
        if (w.inner.skipData(skipTo) == 0) return null // nothing new in range yet; retry later
        val landed = peekTimeUs(w, formatHolder, fmt)
        if (firstDroppedUs == C.TIME_UNSET && (isVideo || !video)) firstDroppedUs = first
        if (fmt[0] == C.RESULT_FORMAT_READ) {
            // Position already moved; deliver the new format, finish next read.
            return fmt[0]
        }
        if (isVideo || !video) {
            if (landed == C.TIME_UNSET || landed < boundaryUs) {
                // Only an old keyframe so far; retry on later reads.
                return null
            }
            targetUs = landed
        }
        // The video gate stays closed until a keyframe is actually delivered.
        if (isVideo && awaitVideoKeyframe) return null
        w.done = true
        maybeFinish()
        return null
    }

    private fun maybeFinish() {
        if (!armed) return
        val av = wrappers.filterNotNull()
            .filter { it.trackType == C.TRACK_TYPE_VIDEO || it.trackType == C.TRACK_TYPE_AUDIO }
        if (av.isEmpty() || av.any { !it.done }) return
        val result = if (firstDroppedUs == C.TIME_UNSET || targetUs == C.TIME_UNSET) {
            SwitchSkipMediaSource.Result.AlreadyPast
        } else {
            SwitchSkipMediaSource.Result.Jumped(
                boundaryOffsetMs = (targetUs - boundaryUs) / 1000L,
                droppedMs = (targetUs - firstDroppedUs) / 1000L,
            )
        }
        finish(result)
    }

    inner class SkipStream(val inner: SampleStream, val trackType: Int) : SampleStream {
        var done = false
        override fun isReady(): Boolean = inner.isReady
        override fun maybeThrowError() = inner.maybeThrowError()
        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int =
            read(this, formatHolder, buffer, readFlags)
        override fun skipData(positionUs: Long): Int = inner.skipData(positionUs)
    }

    // ---- MediaPeriod ----

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        inner.prepare(this, positionUs)
    }

    override fun maybeThrowPrepareError() = inner.maybeThrowPrepareError()

    override fun getTrackGroups(): TrackGroupArray = inner.trackGroups

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> =
        inner.getStreamKeys(trackSelections)

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        val old = arrayOfNulls<SkipStream>(streams.size)
        val innerStreams = arrayOfNulls<SampleStream>(streams.size)
        for (i in streams.indices) {
            val s = streams[i]
            if (s is SkipStream) { old[i] = s; innerStreams[i] = s.inner } else innerStreams[i] = s
        }
        val pos = inner.selectTracks(selections, mayRetainStreamFlags, innerStreams, streamResetFlags, positionUs)
        val next = arrayOfNulls<SkipStream>(streams.size)
        for (i in streams.indices) {
            val s = innerStreams[i]
            next[i] = when {
                s == null -> null
                old[i]?.inner === s -> old[i]
                else -> {
                    val mime = selections[i]?.selectedFormat?.sampleMimeType
                    SkipStream(s, MimeTypes.getTrackType(mime))
                }
            }
            streams[i] = next[i]
        }
        wrappers = next
        abandon("tracks reselected")
        return pos
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) =
        inner.discardBuffer(positionUs, toKeyframe)

    override fun readDiscontinuity(): Long = inner.readDiscontinuity()

    override fun seekToUs(positionUs: Long): Long {
        abandon("seek")
        return inner.seekToUs(positionUs)
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        inner.getAdjustedSeekPositionUs(positionUs, seekParameters)

    override fun getBufferedPositionUs(): Long = inner.bufferedPositionUs

    override fun getNextLoadPositionUs(): Long = inner.nextLoadPositionUs

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = inner.continueLoading(loadingInfo)

    override fun isLoading(): Boolean = inner.isLoading

    override fun reevaluateBuffer(positionUs: Long) = inner.reevaluateBuffer(positionUs)

    // ---- MediaPeriod.Callback ----

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        callback?.onPrepared(this)
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        callback?.onContinueLoadingRequested(this)
    }
}

/**
 * Rejoin-near-live arithmetic for [SwitchSkipMediaPeriod.armRejoin]. Pure, so
 * the unit tests can drive it.
 */
object LiveRejoin {
    /** Buffer kept ahead of the landing keyframe. */
    const val KEEP_AHEAD_MS = 2_000L
    /** Smaller skips are not worth a jump. */
    const val MIN_SKIP_US = 500_000L
    /** The burst has landed once the buffered position is still this long. */
    const val BURST_QUIET_MS = 300L
    /** ...or this long after the first sample, whichever is first. */
    const val BURST_MAX_WAIT_MS = 2_500L
    /** Give up when no sample arrives this long after the re-prime. */
    const val TIMEOUT_MS = 15_000L

    fun burstLanded(nowMs: Long, firstDataAtMs: Long, lastGrowAtMs: Long): Boolean =
        firstDataAtMs != 0L &&
            (nowMs - lastGrowAtMs >= BURST_QUIET_MS || nowMs - firstDataAtMs >= BURST_MAX_WAIT_MS)

    /** Skip target for the video keyframe search (the queue lands on the
     *  newest keyframe at or before it), or [C.TIME_UNSET] when the buffer
     *  past [firstUs] is within [keepUs] + [MIN_SKIP_US]. */
    fun skipTargetUs(firstUs: Long, bufferedUs: Long, keepUs: Long): Long {
        val target = bufferedUs - keepUs
        return if (target - firstUs < MIN_SKIP_US) C.TIME_UNSET else target
    }
}
