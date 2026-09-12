package com.aeriotv.android.core.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import android.media.MediaFormat
import android.net.Uri

/**
 * Always-on, release-safe playback tracer. The Android counterpart of the
 * Apple player's [TUNE] / STALL / feed-starvation log lines, so a Google TV
 * or Shield log can be read the same way an Apple TV log is: one tune costs
 * at most ~8 lines, steady playback one line per 15 s, a stall two lines.
 *
 * Deliberately separate from [AerioExoPlayerHolder]'s DiagnosticAnalyticsListener,
 * which stays BuildConfig.DEBUG-only and is a firehose. Everything here is
 * Log.i/Log.w under the single tag `AerioTrace`, cheap enough to ship:
 *   adb logcat -s AerioTrace
 *
 * This class NEVER changes playback behavior. It only observes.
 *
 * Threading: byte accounting arrives on ExoPlayer loader threads, everything
 * else on the main thread, so the feed window is guarded by [feedLock].
 */
@OptIn(UnstableApi::class)
class PlaybackTracer {

    // ---- current tune timeline (all SystemClock.elapsedRealtime stamps) ----
    @Volatile private var pressAtMs = 0L
    @Volatile private var channelName: String = "?"
    /** Kind of the current tune (live / catchup / vod / dvr), from [urlKind]. */
    @Volatile private var tuneKind: String = "live"
    private var playUrlAtMs = 0L
    private var openAtMs = 0L
    @Volatile private var firstByteAtMs = 0L
    /** When the media connection's HTTP response landed (0 until it does).
     *  Feeds the loading detail line, which must separate "still connecting"
     *  from "connected, no bytes yet" (Apple parity: TSHLSRemuxer
     *  ingestConnectedAt, commit 056feb2). */
    @Volatile private var connectedAtMs = 0L
    private var readyAtMs = 0L
    private var firstFrameAtMs = 0L
    private var formatLogged = false
    private var summaryLogged = false
    /** First frame arrived before STATE_READY; the summary is waiting for it. */
    @Volatile private var summaryPending = false
    @Volatile private var videoDecoderName: String? = null
    private var pendingFormat: Format? = null
    private val summaryHandler = Handler(Looper.getMainLooper())

    // ---- steady-state counters, reset per tune ----
    private var stallCount = 0
    private var stallStartedAtMs = 0L
    private var droppedTotal = 0
    private var droppedAtLastPerf = 0
    private var lastPerfAtMs = 0L
    @Volatile private var bitrateEstimateBps = 0L

    // ---- feed pacing window ----
    private val feedLock = Any()
    /** 1 s buckets of bytes read, covering [FEED_WINDOW_MS]. */
    private val feedBuckets = LongArray(FEED_WINDOW_BUCKETS)
    private var feedBucketIndex = 0
    private var feedBucketStartedAtMs = 0L
    private var lastByteAtMs = 0L
    /** (stamp, gapMs) pairs, pruned to the rolling window by [worstGapMs]. */
    private val feedGaps = ArrayDeque<Pair<Long, Long>>()
    /** Bytes delivered on this tune. */
    private var feedBytesTotal = 0L
    private var lastGapWarnAtMs = 0L

    // ---- media-time progress window ----
    // Logan 2026-09-11 (session11, ESPNU HD): comparing the trailing byte rate
    // against the tune's own byte rate said nothing about real time, because a
    // live picture's bitrate swings with its content, so two genuinely bursty
    // stalls were ignored as "feed below real time". The only honest real-time
    // test is MEDIA time: how many ms of media the feed buffered per ms of wall
    // clock. This ring holds (wallMs, bufferedPosition) samples over
    // [MEDIA_WINDOW_MS] and is cleared on every tune, because bufferedPosition
    // jumps when a stream is re-primed.
    private val mediaLock = Any()
    private val mediaSamples = ArrayDeque<Pair<Long, Long>>()

    // ---- frame pacing (render cadence) ----
    // Logan 2026-09-11: a visible stutter on a 4K50 HEVC live channel with NO
    // [STALL], dropped +0, 5-8 s buffered and a 3.5 s worst feed gap. The feed
    // and the decoder were both fine, so the judder has to be in the RENDER
    // cadence (frames released late / unevenly) or the audio sink. This window
    // times every rendered frame so the log can say so.
    private val frameLock = Any()
    /** System.nanoTime of the previous rendered frame, 0 before the first. */
    private var lastFrameAtNs = 0L
    private var frameSecondStartedAtNs = 0L
    private var framesThisSecond = 0
    private var maxGapThisSecondUs = 0L
    private var bigGapsThisSecond = 0
    /** Rolling medians of the frame PTS delta, for content fps when the
     *  container signals none (live MPEG-TS usually does not). */
    private val ptsDeltasUs = ArrayDeque<Long>()
    private var lastPtsUs = -1L
    private var measuredFps = 0f
    /** Container-signaled frame rate, -1 when absent. */
    @Volatile private var formatFps = -1f
    /** Optional content-fps source (DisplayFrameRateMatcher), wired by the UI. */
    @Volatile var contentFpsProvider: (() -> Float)? = null
    // accumulators drained by the 15 s [PERF] line
    private var framesSincePerf = 0L
    private var frameSecondsSincePerf = 0
    private var maxGapSincePerfUs = 0L
    private var procOffsetSumUs = 0L
    private var procOffsetFrames = 0L
    @Volatile private var lastJudderLogAtMs = 0L

    private fun now() = SystemClock.elapsedRealtime()

    /** Delta from the press that started this tune (or from playUrl when the
     *  tune had no user press, e.g. a watchdog re-prime). */
    private fun sincePress(stamp: Long): Long = if (pressAtMs == 0L) 0L else stamp - pressAtMs

    // ---- tune lifecycle ----

    /**
     * Stamp the REAL key event that starts a live channel change (D-pad
     * Up/Down zap, number entry, channel-list or recents pick, the guide's
     * select press). Called before the tune reaches the holder so
     * press->firstFrame is measured end to end, exactly like tvOS.
     */
    fun markPress(name: String?) {
        pressAtMs = now()
        channelName = name?.takeIf { it.isNotBlank() } ?: "?"
        Log.i(TAG, "[TUNE] press ch=$channelName")
    }

    /** A new stream is being primed. [kind] is live / catchup / vod / dvr. */
    fun markTuneStart(name: String?, kind: String) {
        val n = now()
        // A press older than this was not what caused this prime (a watchdog
        // reload, a follow-poller re-prime); start the clock here instead.
        if (pressAtMs == 0L || n - pressAtMs > PRESS_MAX_AGE_MS) pressAtMs = n
        name?.takeIf { it.isNotBlank() }?.let { channelName = it }
        tuneKind = kind
        playUrlAtMs = n
        openAtMs = 0L
        firstByteAtMs = 0L
        connectedAtMs = 0L
        readyAtMs = 0L
        firstFrameAtMs = 0L
        formatLogged = false
        summaryLogged = false
        summaryPending = false
        videoDecoderName = null
        pendingFormat = null
        stallCount = 0
        stallStartedAtMs = 0L
        droppedTotal = 0
        droppedAtLastPerf = 0
        lastPerfAtMs = n
        synchronized(frameLock) {
            lastFrameAtNs = 0L
            frameSecondStartedAtNs = 0L
            framesThisSecond = 0
            maxGapThisSecondUs = 0L
            bigGapsThisSecond = 0
            ptsDeltasUs.clear()
            lastPtsUs = -1L
            measuredFps = 0f
            formatFps = -1f
            framesSincePerf = 0L
            frameSecondsSincePerf = 0
            maxGapSincePerfUs = 0L
            procOffsetSumUs = 0L
            procOffsetFrames = 0L
        }
        lastJudderLogAtMs = 0L
        synchronized(feedLock) {
            feedBuckets.fill(0L)
            feedBucketIndex = 0
            feedBucketStartedAtMs = n
            lastByteAtMs = n
            feedGaps.clear()
            lastGapWarnAtMs = 0L
            feedBytesTotal = 0L
        }
        synchronized(mediaLock) { mediaSamples.clear() }
        Log.i(TAG, "[TUNE] playUrl ch=$channelName +${sincePress(n)}ms url-kind=$kind")
    }

    private fun onOpen() {
        if (openAtMs != 0L) return
        openAtMs = now()
        Log.i(TAG, "[TUNE] open +${sincePress(openAtMs)}ms")
    }

    /**
     * The upstream's HTTP response has landed (the wrapped DataSource's open()
     * returned). Called from loader threads.
     */
    fun onOpened() {
        if (connectedAtMs == 0L) connectedAtMs = now()
    }

    /** One poll of the byte source behind a loading spinner. */
    data class LoadingSnapshot(
        val connected: Boolean,
        /** elapsedRealtime stamp of the response, 0 when not connected. */
        val connectedAtMs: Long,
        val bytes: Long,
    )

    /** Thread-safe snapshot for the loading detail line. */
    fun loadingSnapshot(): LoadingSnapshot {
        val bytes = synchronized(feedLock) { feedBytesTotal }
        val at = connectedAtMs
        return LoadingSnapshot(connected = at != 0L || bytes > 0L, connectedAtMs = at, bytes = bytes)
    }

    /** Fired once per tune, on a loader thread, when the FIRST byte arrives.
     *  [LiveStreamFailover] cancels its first-byte deadline against it. */
    @Volatile var onFirstByte: (() -> Unit)? = null

    /** Byte accounting. Called from loader threads (DataSource wrapper) and
     *  from onLoadCompleted for chunk-based sources. */
    fun onBytes(count: Long) {
        if (count <= 0L) return
        val n = now()
        if (firstByteAtMs == 0L) {
            firstByteAtMs = n
            if (connectedAtMs == 0L) connectedAtMs = n
            Log.i(TAG, "[TUNE] firstByte +${sincePress(n)}ms")
            onFirstByte?.invoke()
        }
        synchronized(feedLock) {
            advanceFeedBuckets(n)
            feedBuckets[feedBucketIndex] += count
            feedBytesTotal += count
            val gap = n - lastByteAtMs
            if (gap >= FEED_GAP_RECORD_MS) {
                feedGaps.addLast(n to gap)
                if (n - lastGapWarnAtMs >= FEED_GAP_WARN_COOLDOWN_MS) {
                    lastGapWarnAtMs = n
                    Log.w(TAG, "[FEED] gap ${gap}ms without bytes")
                }
            }
            lastByteAtMs = n
        }
    }

    /** Must be called holding [feedLock]. */
    private fun advanceFeedBuckets(nowMs: Long) {
        val elapsed = nowMs - feedBucketStartedAtMs
        if (elapsed < FEED_BUCKET_MS) return
        val steps = (elapsed / FEED_BUCKET_MS).toInt()
        repeat(steps.coerceAtMost(FEED_WINDOW_BUCKETS)) {
            feedBucketIndex = (feedBucketIndex + 1) % FEED_WINDOW_BUCKETS
            feedBuckets[feedBucketIndex] = 0L
        }
        feedBucketStartedAtMs += steps * FEED_BUCKET_MS
    }

    private fun onFormat(format: Format) {
        if (format.frameRate > 1f) formatFps = format.frameRate
        if (formatLogged) return
        pendingFormat = format
        // The decoder name matters: session2.txt 19:56:48 is a MediaTek AVC
        // decoder being reclaimed while the HEVC one is created, and the log
        // could not say which codec the tune ended up on. The format usually
        // lands first, so hold the line until the decoder is named (or until
        // the first frame / ready forces it out).
        if (videoDecoderName != null) emitFormat()
    }

    private fun emitFormat() {
        val format = pendingFormat ?: return
        if (formatLogged) return
        formatLogged = true
        pendingFormat = null
        val fps = if (format.frameRate > 0f) "${format.frameRate}" else "?"
        Log.i(
            TAG,
            "[TUNE] format ${format.width}x${format.height} $fps ${format.sampleMimeType} " +
                "dec=${videoDecoderName ?: "?"}",
        )
    }

    private fun onReady() {
        if (readyAtMs != 0L) return
        readyAtMs = now()
        emitFormat()
        Log.i(TAG, "[TUNE] ready +${sincePress(readyAtMs)}ms")
        // STATE_READY can land AFTER onRenderedFirstFrame; the summary waits up
        // to [SUMMARY_DEFER_MS] for it so it stops printing ready=n/a
        // (session2.txt: both tunes reported ready=n/a).
        if (summaryPending) emitSummary()
    }

    /** One summary line per tune, same shape as the tvOS one. */
    fun onFirstFrame() {
        if (summaryLogged || summaryPending) return
        firstFrameAtMs = now()
        if (readyAtMs == 0L) {
            summaryPending = true
            val tune = playUrlAtMs
            summaryHandler.postDelayed(
                {
                    // Still nothing from STATE_READY: print as before.
                    if (summaryPending && playUrlAtMs == tune) emitSummary()
                },
                SUMMARY_DEFER_MS,
            )
            return
        }
        emitSummary()
    }

    private fun emitSummary() {
        if (summaryLogged) return
        emitFormat()
        summaryLogged = true
        summaryPending = false
        val total = sincePress(firstFrameAtMs)
        fun d(stamp: Long): String = if (stamp == 0L) "n/a" else "${sincePress(stamp)}"
        Log.i(
            TAG,
            "[TUNE] press->firstFrame ${total}ms ch=$channelName | " +
                "playUrl=${d(playUrlAtMs)} open=${d(openAtMs)} firstByte=${d(firstByteAtMs)} " +
                "ready=${d(readyAtMs)} frame=${d(firstFrameAtMs)}",
        )
    }

    // ---- stalls ----

    private fun onBuffering(player: Player?) {
        // Only a rebuffer counts: everything before the first frame is the
        // cold start, already covered by the [TUNE] lines.
        if (firstFrameAtMs == 0L || stallStartedAtMs != 0L) return
        val n = now()
        stallStartedAtMs = n
        stallCount += 1
        val pos = player?.currentPosition ?: 0L
        val buffered = player?.let { (it.bufferedPosition - it.currentPosition).coerceAtLeast(0L) } ?: 0L
        Log.w(
            TAG,
            "[STALL] at +${n - firstFrameAtMs}ms pos=${pos}ms buffered=${buffered}ms " +
                "liveOffset=${liveOffsetMs(player)}ms ch=$channelName",
        )
        // Hand the feed shape to the holder's start-buffer learner. It only
        // ever changes the NEXT tune; this playback is untouched.
        onStall?.invoke(stallSnapshot())
    }

    /**
     * Feed shape at the moment of a [STALL], for the learned live start-buffer
     * ("hold-back") learner in [AerioExoPlayerHolder]. Reading it here keeps the
     * tracer observational: it reports, the holder decides.
     */
    data class FeedStallSnapshot(
        val channelName: String,
        val isLive: Boolean,
        /** Longest stretch without bytes inside the 30 s window. */
        val worstGapMs: Long,
        /** Buffered MEDIA time gained per unit of wall clock over the trailing
         *  [MEDIA_WINDOW_MS], clamped to 0..2, or null until the ring covers at
         *  least [MEDIA_WINDOW_MIN_MS]. At or above 1.0 the feed is keeping up
         *  with real time; well below it the feed is starved upstream, which a
         *  deeper start buffer cannot fix. */
        val feedMediaRatio: Double?,
    )

    /** Set by the holder; invoked on the main thread once per [STALL]. */
    @Volatile var onStall: ((FeedStallSnapshot) -> Unit)? = null

    /** Average kbps over the trailing 30 s feed window. */
    fun windowKbps(): Long = feedSnapshot(now()).first

    /** Longest stretch without bytes inside the trailing 30 s window. */
    fun worstGapMs(): Long = feedSnapshot(now()).second

    /**
     * Buffered media time gained per unit of wall clock over the trailing
     * [MEDIA_WINDOW_MS]. Null until the ring spans [MEDIA_WINDOW_MIN_MS], so an
     * early stall is never judged on a half-filled window.
     */
    private fun feedMediaRatio(): Double? = synchronized(mediaLock) {
        val oldest = mediaSamples.firstOrNull() ?: return null
        val newest = mediaSamples.last()
        val wallDelta = newest.first - oldest.first
        if (wallDelta < MEDIA_WINDOW_MIN_MS) return null
        val mediaDelta = newest.second - oldest.second
        (mediaDelta.toDouble() / wallDelta.toDouble()).coerceIn(0.0, 2.0)
    }

    private fun stallSnapshot(): FeedStallSnapshot {
        val (_, worst) = feedSnapshot(now())
        return FeedStallSnapshot(
            channelName = channelName,
            isLive = tuneKind == "live",
            worstGapMs = worst,
            feedMediaRatio = feedMediaRatio(),
        )
    }

    private fun onReadyAfterStall() {
        val started = stallStartedAtMs
        if (started == 0L) return
        stallStartedAtMs = 0L
        Log.i(TAG, "[STALL] recovered after ${now() - started}ms")
    }

    private fun liveOffsetMs(player: Player?): Long {
        val off = player?.currentLiveOffset ?: C.TIME_UNSET
        return if (off == C.TIME_UNSET) -1L else off
    }

    // ---- periodic heartbeat ----

    /**
     * Drive from the holder's existing 1 s watchdog poll. Emits one [PERF] +
     * one [FEED] line per [PERF_INTERVAL_MS] while the player is actually
     * playing; silent otherwise.
     */
    fun tick(player: Player?) {
        val p = player ?: return
        if (!p.isPlaying) return
        val n = now()
        // Media-time progress ring (see [mediaSamples]). Sampled on every tick,
        // not only on the [PERF] cadence, so the window is dense.
        val bufferedPosition = p.bufferedPosition
        if (bufferedPosition != C.TIME_UNSET) {
            synchronized(mediaLock) {
                mediaSamples.addLast(n to bufferedPosition)
                while (mediaSamples.size > 1 &&
                    n - mediaSamples.first().first > MEDIA_WINDOW_MS
                ) {
                    mediaSamples.removeFirst()
                }
            }
        }
        if (n - lastPerfAtMs < PERF_INTERVAL_MS) return
        lastPerfAtMs = n
        val droppedDelta = droppedTotal - droppedAtLastPerf
        droppedAtLastPerf = droppedTotal
        val buffered = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
        val render = drainRenderStats()
        Log.i(
            TAG,
            "[PERF] ch=$channelName stalls=$stallCount dropped=+$droppedDelta($droppedTotal) " +
                "buffered=${buffered}ms liveOffset=${liveOffsetMs(p)}ms " +
                "bw=${bitrateEstimateBps / 1000}kbps pos=${p.currentPosition}ms " +
                "render=${"%.1f".format(render.fps)}fps maxGap=${render.maxGapMs}ms " +
                "procOffset=${render.procOffsetUs}us",
        )
        val (kbps, worstGap) = feedSnapshot(n)
        Log.i(TAG, "[FEED] ${kbps}kbps avg over 30s, worst gap ${worstGap}ms without bytes")
    }

    private fun feedSnapshot(nowMs: Long): Pair<Long, Long> = synchronized(feedLock) {
        advanceFeedBuckets(nowMs)
        val bytes = feedBuckets.sum()
        val kbps = bytes * 8L / 1000L / (FEED_WINDOW_MS / 1000L)
        while (feedGaps.isNotEmpty() && nowMs - feedGaps.first().first > FEED_WINDOW_MS) {
            feedGaps.removeFirst()
        }
        val liveGap = nowMs - lastByteAtMs
        val worst = (feedGaps.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(liveGap)
        kbps to worst
    }


    // ---- frame pacing ----

    /** Content frame interval in ms: container fps, else the measured median,
     *  else the 50 Hz assumption (the channel Logan saw juddering). */
    private fun contentFps(): Float {
        val signaled = formatFps
        if (signaled > 1f) return signaled
        val provided = contentFpsProvider?.invoke() ?: 0f
        if (provided > 1f) return provided
        if (measuredFps > 1f) return measuredFps
        return 50f
    }

    /**
     * Chain the tracer's frame timer in front of [downstream] (the seamless
     * frame-rate matcher's listener, when there is one). ExoPlayer has a
     * SINGLE video-frame-metadata slot, so whoever registers last must carry
     * the other. Register the result with setVideoFrameMetadataListener.
     */
    fun frameMetadataListener(downstream: VideoFrameMetadataListener? = null): VideoFrameMetadataListener =
        object : VideoFrameMetadataListener {
            override fun onVideoFrameAboutToBeRendered(
                presentationTimeUs: Long,
                releaseTimeNs: Long,
                format: Format,
                mediaFormat: MediaFormat?,
            ) {
                onFrameRendered(presentationTimeUs)
                downstream?.onVideoFrameAboutToBeRendered(
                    presentationTimeUs,
                    releaseTimeNs,
                    format,
                    mediaFormat,
                )
            }
        }

    /** Called once per rendered frame, on the video renderer thread. Must stay
     *  allocation-free and lock-cheap: it runs 50-60 times a second. */
    private fun onFrameRendered(presentationTimeUs: Long) {
        val nowNs = System.nanoTime()
        var judderGapUs = 0L
        var judderGaps = 0
        var judderFps = 0
        var thresholdMs = 0L
        synchronized(frameLock) {
            // Measured content fps (median PTS delta), used only when the
            // container signals no frame rate.
            if (lastPtsUs >= 0L) {
                val d = presentationTimeUs - lastPtsUs
                if (d < 0L || d > 1_000_000L) {
                    ptsDeltasUs.clear()
                    measuredFps = 0f
                } else if (d in 4_000L..210_000L) {
                    ptsDeltasUs.addLast(d)
                    if (ptsDeltasUs.size > 60) ptsDeltasUs.removeFirst()
                    if (ptsDeltasUs.size >= 30) {
                        val sorted = ptsDeltasUs.sorted()
                        measuredFps = (1_000_000.0 / sorted[sorted.size / 2]).toFloat()
                    }
                }
            }
            lastPtsUs = presentationTimeUs

            val fps = contentFps()
            val frameIntervalUs = (1_000_000f / fps).toLong().coerceAtLeast(1L)
            val gapThresholdUs = (frameIntervalUs * FRAME_GAP_FACTOR).toLong()
            if (lastFrameAtNs != 0L) {
                val gapUs = (nowNs - lastFrameAtNs) / 1_000L
                if (gapUs > maxGapThisSecondUs) maxGapThisSecondUs = gapUs
                if (gapUs > gapThresholdUs) bigGapsThisSecond += 1
            }
            lastFrameAtNs = nowNs
            framesThisSecond += 1
            if (frameSecondStartedAtNs == 0L) frameSecondStartedAtNs = nowNs

            if (nowNs - frameSecondStartedAtNs >= 1_000_000_000L) {
                framesSincePerf += framesThisSecond
                frameSecondsSincePerf += 1
                if (maxGapThisSecondUs > maxGapSincePerfUs) maxGapSincePerfUs = maxGapThisSecondUs
                if (bigGapsThisSecond > 0) {
                    judderGapUs = maxGapThisSecondUs
                    judderGaps = bigGapsThisSecond
                    judderFps = framesThisSecond
                    thresholdMs = gapThresholdUs / 1_000L
                }
                frameSecondStartedAtNs = nowNs
                framesThisSecond = 0
                maxGapThisSecondUs = 0L
                bigGapsThisSecond = 0
            }
        }
        if (judderGaps > 0) {
            val n = now()
            // Startup is NOT judder: the first second after the first rendered
            // frame legitimately contains one huge gap (decoder priming, the
            // surface's first buffers) and fired "[JUDDER] max frame gap
            // 2071ms fps=2/s pos=1933ms" on literally every tune. Drop the
            // frame-gap verdict until the pipeline has been rendering for
            // JUDDER_FIRST_FRAME_GRACE_MS. Audio-underrun judder lines stay
            // unconditional; they are never a startup artifact.
            val ff = firstFrameAtMs
            if (ff == 0L || n - ff < JUDDER_FIRST_FRAME_GRACE_MS) {
                judderGaps = 0
                return
            }
            if (n - lastJudderLogAtMs < JUDDER_LOG_COOLDOWN_MS) return
            lastJudderLogAtMs = n
            val gapMs = judderGapUs / 1_000L
            // currentPosition must be read on the player's app thread.
            summaryHandler.post {
                val pos = tracedPlayer?.currentPosition ?: 0L
                Log.w(
                    TAG,
                    "[JUDDER] max frame gap ${gapMs}ms ($judderGaps gaps > ${thresholdMs}ms) " +
                        "fps=$judderFps/s pos=${pos}ms",
                )
            }
        }
    }

    /** Drain the render accumulators for the 15 s [PERF] line. */
    private data class RenderSnapshot(val fps: Float, val maxGapMs: Long, val procOffsetUs: Long)

    private fun drainRenderStats(): RenderSnapshot = synchronized(frameLock) {
        val seconds = frameSecondsSincePerf
        val fps = if (seconds > 0) framesSincePerf.toFloat() / seconds else 0f
        val maxGapMs = maxGapSincePerfUs / 1_000L
        val offset = if (procOffsetFrames > 0L) procOffsetSumUs / procOffsetFrames else 0L
        framesSincePerf = 0L
        frameSecondsSincePerf = 0
        maxGapSincePerfUs = 0L
        procOffsetSumUs = 0L
        procOffsetFrames = 0L
        RenderSnapshot(fps, maxGapMs, offset)
    }

    // ---- recovery visibility ----

    /** Mirror an existing watchdog / heal action into the same tag so a
     *  reload is visible next to the [TUNE] and [STALL] lines. */
    fun recover(what: String) {
        Log.w(TAG, "[RECOVER] $what ch=$channelName")
    }

    // ---- wiring ----

    /** Wrap a live [DataSource.Factory] so the progressive TS path (whose
     *  single load never "completes") still reports byte flow. */
    fun wrapDataSourceFactory(upstream: DataSource.Factory): DataSource.Factory =
        DataSource.Factory { TracingDataSource(upstream.createDataSource(), this) }

    /** Always-on analytics listener; attach to the foreground player only. */
    val analyticsListener: AnalyticsListener = object : AnalyticsListener {
        override fun onLoadStarted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            onOpen()
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            onBytes(loadEventInfo.bytesLoaded)
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            videoDecoderName = decoderName
            emitFormat()
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            onFormat(format)
        }

        override fun onPlaybackStateChanged(
            eventTime: AnalyticsListener.EventTime,
            state: Int,
        ) {
            when (state) {
                Player.STATE_READY -> { onReady(); onReadyAfterStall() }
                Player.STATE_BUFFERING -> onBuffering(tracedPlayer)
                else -> Unit
            }
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            droppedTotal += droppedFrames
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            // An audio-sink underrun is heard as a hitch even when video never
            // stalls and no frames are dropped.
            Log.w(
                TAG,
                "[JUDDER] audio underrun buffer=${bufferSizeMs}ms sinceFeed=${elapsedSinceLastFeedMs}ms " +
                    "bytes=$bufferSize ch=$channelName",
            )
        }

        override fun onVideoFrameProcessingOffset(
            eventTime: AnalyticsListener.EventTime,
            totalProcessingOffsetUs: Long,
            frameCount: Int,
        ) {
            if (frameCount <= 0) return
            synchronized(frameLock) {
                procOffsetSumUs += totalProcessingOffsetUs
                procOffsetFrames += frameCount.toLong()
            }
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            bitrateEstimateBps = bitrateEstimate
        }
    }

    /** Set by the holder so stall lines can read position / live offset.
     *  AnalyticsListener.EventTime carries a Timeline, not the player. */
    @Volatile var tracedPlayer: Player? = null

    /**
     * Pass-through [DataSource] that only counts bytes. Mirrors the shape of
     * the Live Rewind TeeDataSource so the live path keeps ONE wrapper style.
     */
    private class TracingDataSource(
        private val upstream: DataSource,
        private val tracer: PlaybackTracer,
    ) : DataSource {
        override fun open(dataSpec: DataSpec): Long {
            val length = upstream.open(dataSpec)
            // open() returns once the response headers are in: "connected".
            tracer.onOpened()
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = upstream.read(buffer, offset, length)
            if (n > 0) tracer.onBytes(n.toLong())
            return n
        }

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun getUri(): Uri? = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() = upstream.close()
    }

    companion object {
        private const val TAG = "AerioTrace"
        private const val PRESS_MAX_AGE_MS = 15_000L
        private const val SUMMARY_DEFER_MS = 2_000L
        private const val PERF_INTERVAL_MS = 15_000L
        private const val FEED_WINDOW_MS = 30_000L
        /** Span of the media-time progress ring. */
        private const val MEDIA_WINDOW_MS = 30_000L
        /** The ring has to cover this much wall clock before its ratio means
         *  anything. */
        private const val MEDIA_WINDOW_MIN_MS = 20_000L
        private const val FEED_BUCKET_MS = 1_000L
        private const val FEED_WINDOW_BUCKETS = (FEED_WINDOW_MS / FEED_BUCKET_MS).toInt()
        private const val FEED_GAP_RECORD_MS = 2_000L
        private const val FEED_GAP_WARN_COOLDOWN_MS = 10_000L
        /** A rendered-frame gap this many times the content frame interval
         *  counts as a visible hitch (50 fps -> 50ms interval -> 125ms). */
        private const val FRAME_GAP_FACTOR = 2.5f
        private const val JUDDER_LOG_COOLDOWN_MS = 2_000L
        /** Frame-gap judder is not evaluated until the pipeline has been
         *  rendering this long past the tune's first frame. */
        private const val JUDDER_FIRST_FRAME_GRACE_MS = 2_000L

        /** Classify a play URL for the [TUNE] playUrl line. Never logs the
         *  URL itself (it can embed credentials). */
        fun urlKind(url: String): String {
            val u = url.lowercase()
            return when {
                "/movie/" in u || "/vod/" in u || "/series/" in u -> "vod"
                "timeshift" in u || "catchup" in u || "streaming/timeshift" in u -> "catchup"
                "/dvr/" in u || "recording" in u -> "dvr"
                else -> "live"
            }
        }
    }
}
