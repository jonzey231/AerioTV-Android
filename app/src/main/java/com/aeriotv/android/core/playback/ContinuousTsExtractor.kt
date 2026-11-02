package com.aeriotv.android.core.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import kotlin.math.abs

/**
 * Wraps the live raw-TS [Extractor] and rebases sample timestamps so the
 * player always sees ONE continuous timeline, even when Dispatcharr swaps the
 * channel's SOURCE stream underneath us (change_stream / server failover).
 *
 * Why this exists: on a switch we deliberately KEEP the HTTP connection
 * (AerioExoPlayerHolder.followStreamSwitch + SwitchSkipMediaSource) because a
 * second GET mints a new Dispatcharr client (stream_limit, 503 "stopping").
 * The backup provider's PTS, however, has nothing to do with the old one: it
 * can be +31 min, or reset to 0 (about -50 s). TsExtractor in MODE_SINGLE_PMT
 * only unwraps the 33-bit roll, so the new samples reached the renderers on a
 * foreign timeline: queued video was held as "too early" for up to 45 s
 * (render = 0.0 fps while the position advanced) or the whole timeline jumped,
 * with "Audio sink error: Unexpected audio track timestamp discontinuity".
 *
 * A hard re-tune is rejected (new GET, see above) and resetting the renderers
 * in place was tried 2026-09-15 and painted green/purple
 * (SwitchSkipMediaSource.kt:38-40). So the fix is arithmetic: keep the bytes
 * flowing and subtract a running offset from every sample time.
 *
 * Notes on the numbers:
 *  - Thresholds are Apple's, from TSHLSRemuxer.swift (switchPTSJumpForward
 *    5 s, switchPTSJumpBackward 2 s, sourceChangeReason): more than +5 s
 *    forward or more than -2 s backward relative to the same track's previous
 *    raw time is a new source, not B-frame reordering (a few frames) or
 *    ordinary upstream jitter.
 *  - The 33-bit PTS wrap NEVER reaches this class. TsExtractor runs every
 *    sample through TimestampAdjuster before TrackOutput.sampleMetadata, and
 *    the adjuster already unwraps the roll, so the times we see keep climbing
 *    across it and no jump is detected. (ContinuousTsExtractorTest proves a
 *    wrap-sized continuous sequence is passed through untouched.)
 *  - ONE offset is shared by all tracks, so audio and video stay locked to
 *    each other. The offset is latched by whichever track notices the jump
 *    FIRST, from that track's own continuity (nothing is ever emitted
 *    un-rebased, which is what would happen if we waited for video while
 *    audio was already crossing). Tracks that have not crossed yet keep using
 *    the PREVIOUS offset, so an old-timeline video sample trailing a
 *    new-timeline audio sample is still rebased correctly. When a later track
 *    crosses, its own candidate offset is compared with the latched one and
 *    accepted when it agrees within [CROSS_TRACK_TOLERANCE_US] (it does: both
 *    come from the same new source). Only a disagreement beyond that
 *    tolerance re-latches, and then only from VIDEO, which is the track the
 *    user actually sees.
 *  - Output is never allowed to go backwards across a rebase (clamped to
 *    last + 1 us). Away from a rebase the offset is a constant subtraction,
 *    so natural B-frame PTS reordering is preserved untouched.
 */
@UnstableApi
class ContinuousTsExtractor(
    private val inner: Extractor,
    /** Shared with the innermost [SpliceResetTsExtractor]; null in tests and
     *  for sources without one. */
    private val splice: SpliceControl? = null,
    nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : Extractor by inner {

    companion object {
        private const val TAG = "AerioExoPlayer"

        /** Raw forward jump that means "new source" (Apple: switchPTSJumpForward 5 s). */
        const val JUMP_FORWARD_US = 5_000_000L

        /** Raw backward jump that means "new source" (Apple: switchPTSJumpBackward 2 s). */
        const val JUMP_BACKWARD_US = -2_000_000L

        /** How far two tracks' independently computed offsets may differ and
         *  still be treated as the same switch. */
        const val CROSS_TRACK_TOLERANCE_US = 500_000L

        /** Fallbacks until a track has shown its own sample spacing. */
        private const val DEFAULT_VIDEO_GAP_US = 33_367L // ~29.97 fps
        private const val DEFAULT_AUDIO_GAP_US = 21_333L // 1024 samples @ 48 kHz
        private const val DEFAULT_OTHER_GAP_US = 40_000L
        private const val MIN_GAP_US = 1_000L
        private const val MAX_GAP_US = 200_000L

        /** A second rebase this soon after the previous one is not a clean
         *  switch (Streamer 2026-11-01: rebases every few seconds, +/-1536 s). */
        const val REBASE_REPEAT_MS = 10_000L

        /** Tracks whose last output sits further than this from the splice
         *  anchor are treated as damaged and re-anchored. */
        const val SPLICE_TRACK_SPREAD_US = 2_000_000L
    }

    private val state = Rebaser(log, if (splice == null) ({ 0L }) else nowMs) { reason -> splice?.request(reason) }

    init {
        splice?.onReset = { state.armSplice() }
    }

    override fun init(output: ExtractorOutput) {
        inner.init(RebasingExtractorOutput(output, state))
    }

    override fun seek(position: Long, timeUs: Long) {
        // A real seek on this path is a genuine re-open: a hard re-tune, or a
        // Live Rewind buffer (re-)entry, which relies on a fresh TsExtractor
        // (AerioExoPlayerHolder ~1410-1421, the recorded-splice-gap reopen).
        // Both start a brand new timeline, so the running offset must go.
        state.reset()
        inner.seek(position, timeUs)
    }

    /** Test seam: the per-track/offset state, after wiring. */
    internal fun rebaserForTest(): Rebaser = state

    // ---- rebasing ----

    internal class TrackState(val trackType: Int) {
        var lastRawUs: Long = C.TIME_UNSET
        var lastOutUs: Long = C.TIME_UNSET
        var maxOutUs: Long = Long.MIN_VALUE
        var gapUs: Long = when (trackType) {
            C.TRACK_TYPE_VIDEO -> DEFAULT_VIDEO_GAP_US
            C.TRACK_TYPE_AUDIO -> DEFAULT_AUDIO_GAP_US
            else -> DEFAULT_OTHER_GAP_US
        }
        var generation: Int = 0
        var clampArmed: Boolean = false

        val label: String = when (trackType) {
            C.TRACK_TYPE_VIDEO -> "video"
            C.TRACK_TYPE_AUDIO -> "audio"
            else -> "track"
        }
    }

    internal class Rebaser(
        private val log: (String) -> Unit,
        private val nowMs: () -> Long = { 0L },
        private val onSuspect: (String) -> Unit = {},
    ) {
        private var lastRebaseAtMs = Long.MIN_VALUE
        private var spliceArmed = false
        private var spliceAnchorUs: Long? = null
        /** After a splice, the first VIDEO sample may still move the shared
         *  base forward (never back), once, so the picture never overlaps its
         *  own old output when audio crossed first. */
        private var spliceVideoPending = false
        private val tracks = ArrayList<TrackState>()
        /** Cumulative offset per generation; generation 0 is the original timeline. */
        private val offsets = HashMap<Int, Long>().apply { put(0, 0L) }
        private var generation = 0

        /** Total rebase currently applied to the newest timeline, for tests/logs. */
        val currentOffsetUs: Long get() = offsets[generation] ?: 0L

        fun newTrack(trackType: Int): TrackState {
            val t = TrackState(trackType)
            // A track created after a switch joins the current timeline.
            t.generation = generation
            tracks.add(t)
            return t
        }

        fun reset() {
            tracks.forEach {
                it.lastRawUs = C.TIME_UNSET
                it.lastOutUs = C.TIME_UNSET
                it.maxOutUs = Long.MIN_VALUE
                it.generation = 0
                it.clampArmed = false
            }
            offsets.clear()
            offsets[0] = 0L
            generation = 0
            spliceArmed = false
            spliceAnchorUs = null
            lastRebaseAtMs = Long.MIN_VALUE
        }

        /**
         * The inner extractor was replaced after a server-side switch: every
         * track restarts on ONE fresh TimestampAdjuster base. The first sample
         * after this, from whichever track, sets a single offset for ALL
         * tracks (never per track), anchored just after the newest output of
         * the tracks that agree with video.
         */
        fun armSplice() {
            val video = tracks.filter { it.trackType == C.TRACK_TYPE_VIDEO && it.maxOutUs != Long.MIN_VALUE }
                .maxOfOrNull { it.maxOutUs }
            val ref = video ?: tracks.filter { it.maxOutUs != Long.MIN_VALUE }.minOfOrNull { it.maxOutUs }
            spliceAnchorUs = ref?.let { r ->
                tracks.filter { it.maxOutUs != Long.MIN_VALUE && abs(it.maxOutUs - r) <= SPLICE_TRACK_SPREAD_US }
                    .maxOfOrNull { it.maxOutUs } ?: r
            }
            spliceArmed = true
            tracks.forEach { it.lastRawUs = C.TIME_UNSET }
        }

        private fun applySplice(rawUs: Long) {
            spliceArmed = false
            val anchor = spliceAnchorUs
            val target = if (anchor != null) anchor + DEFAULT_VIDEO_GAP_US else rawUs
            generation += 1
            offsets[generation] = rawUs - target
            tracks.forEach {
                it.generation = generation
                it.clampArmed = false
                if (anchor != null && it.maxOutUs != Long.MIN_VALUE &&
                    abs(it.maxOutUs - anchor) > SPLICE_TRACK_SPREAD_US
                ) {
                    it.maxOutUs = anchor
                }
            }
            lastRebaseAtMs = Long.MIN_VALUE
            spliceVideoPending = tracks.any { it.trackType == C.TRACK_TYPE_VIDEO && it.maxOutUs != Long.MIN_VALUE }
            pruneOffsets()
            log("[SWITCH] splice: one shared base for all tracks, rebased PTS by ${(target - rawUs) / 1000}ms")
        }

        /** Maps one raw sample time to the continuous timeline. */
        fun map(t: TrackState, rawUs: Long): Long {
            if (rawUs == C.TIME_UNSET) return rawUs
            if (spliceArmed) applySplice(rawUs)
            if (spliceVideoPending && t.trackType == C.TRACK_TYPE_VIDEO) {
                spliceVideoPending = false
                val off = offsets[generation] ?: 0L
                if (t.maxOutUs != Long.MIN_VALUE && rawUs - off <= t.maxOutUs) {
                    offsets[generation] = rawUs - (t.maxOutUs + t.gapUs)
                    log("[SWITCH] splice: shared base moved ${(off - (offsets[generation] ?: 0L)) / 1000}ms forward for video continuity")
                }
            }
            if (t.lastRawUs != C.TIME_UNSET) {
                val delta = rawUs - t.lastRawUs
                if (delta > JUMP_FORWARD_US || delta < JUMP_BACKWARD_US) {
                    onJump(t, rawUs, delta)
                } else if (delta > 0) {
                    t.gapUs = delta.coerceIn(MIN_GAP_US, MAX_GAP_US)
                }
            }
            t.lastRawUs = rawUs
            var out = rawUs - (offsets[t.generation] ?: 0L)
            if (t.clampArmed) {
                if (t.maxOutUs != Long.MIN_VALUE && out <= t.maxOutUs) {
                    out = t.maxOutUs + 1
                } else {
                    t.clampArmed = false
                }
            }
            t.lastOutUs = out
            if (out > t.maxOutUs) t.maxOutUs = out
            return out
        }

        private fun onJump(t: TrackState, rawUs: Long, deltaUs: Long) {
            // Where this track WANTS to land so its own output stays continuous.
            val desired =
                if (t.maxOutUs != Long.MIN_VALUE) t.maxOutUs + t.gapUs
                else rawUs - (offsets[t.generation] ?: 0L)
            val candidate = rawUs - desired
            if (t.generation == generation) {
                // First track across the boundary: it latches the shared offset,
                // so nothing is ever emitted on the foreign timeline.
                generation += 1
                offsets[generation] = candidate
                t.generation = generation
                log(
                    "[SWITCH] rebased PTS by ${-candidate / 1000}ms " +
                        "(${t.label} jump ${deltaUs / 1000}ms)",
                )
                val n = nowMs()
                if (lastRebaseAtMs != Long.MIN_VALUE && n - lastRebaseAtMs < REBASE_REPEAT_MS) onSuspect("pts")
                lastRebaseAtMs = n
            } else {
                // A later track crossing the same boundary: agree with the
                // latched offset unless it is plainly a different timeline.
                t.generation = generation
                val current = offsets[generation] ?: 0L
                if (abs(candidate - current) > CROSS_TRACK_TOLERANCE_US) {
                    if (t.trackType == C.TRACK_TYPE_VIDEO) {
                        offsets[generation] = candidate
                        log(
                            "[SWITCH] rebased PTS by ${-candidate / 1000}ms " +
                                "(${t.label} jump ${deltaUs / 1000}ms, re-latched from video)",
                        )
                        onSuspect("pts")
                    } else {
                        onSuspect("pts")
                        log(
                            "[SWITCH] ${t.label} jump ${deltaUs / 1000}ms disagrees with the " +
                                "latched rebase by ${(candidate - current) / 1000}ms; keeping it",
                        )
                    }
                }
            }
            t.clampArmed = true
            pruneOffsets()
        }

        private fun pruneOffsets() {
            val oldest = tracks.minOfOrNull { it.generation } ?: return
            offsets.keys.filter { it < oldest }.forEach { offsets.remove(it) }
        }
    }

    private class RebasingExtractorOutput(
        private val inner: ExtractorOutput,
        private val state: Rebaser,
    ) : ExtractorOutput {
        private val wrappers = HashMap<Int, TrackOutput>()

        override fun track(id: Int, type: Int): TrackOutput {
            wrappers[id]?.let { return it }
            val w = RebasingTrackOutput(inner.track(id, type), state.newTrack(type), state)
            wrappers[id] = w
            return w
        }

        override fun endTracks() = inner.endTracks()

        override fun seekMap(seekMap: SeekMap) = inner.seekMap(seekMap)
    }

    private class RebasingTrackOutput(
        private val inner: TrackOutput,
        private val track: TrackState,
        private val state: Rebaser,
    ) : TrackOutput by inner {

        override fun format(format: Format) = inner.format(format)

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            inner.sampleMetadata(state.map(track, timeUs), flags, size, offset, cryptoData)
        }
    }
}
