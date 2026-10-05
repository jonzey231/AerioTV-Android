package com.aeriotv.android.feature.player

import android.os.Handler
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Per-player content frame rate measured from the video decoder's output
 * counters, independent of the Match Frame Rate path and of the single
 * VideoFrameMetadataListener slot. Live MPEG-TS almost never carries a
 * container frameRate (Format.frameRate == NO_VALUE), so the chrome's format
 * badge and Stream Info need a measured rate on every platform and for every
 * player (main, VOD, Multiview tiles), not only when [DisplayFrameRateMatcher]
 * happens to own the listener slot.
 *
 * Method: once a second, on the player's application looper, read
 * rendered + skipped + dropped output buffers and the playback position. The
 * rate is frames over CONTENT time elapsed since a baseline (at least 2 s), so
 * stalls do not dilute it and precision improves as the window grows (capped
 * at 30 s, then the baseline slides). Seeks, flips and renderer re-enables
 * reset the baseline. The result is snapped to the standard broadcast rates
 * within 0.3 fps. One sampler per player instance; it stops by itself once the
 * player is garbage collected. 0 means "not measured yet".
 */
@OptIn(UnstableApi::class)
object ContentFpsMeter {
    private val flows = WeakHashMap<ExoPlayer, MutableStateFlow<Float>>()

    private val STANDARD_RATES = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)

    /** Nearest standard rate within 0.3 fps, otherwise the raw value. */
    fun snap(fps: Float): Float {
        if (fps <= 0f || fps.isNaN()) return 0f
        var best = fps
        var bestDelta = 0.3f
        for (r in STANDARD_RATES) {
            val d = abs(fps - r)
            if (d <= bestDelta) { best = r; bestDelta = d }
        }
        return best
    }

    /** Measured fps for [player], starting its sampler on first use. */
    fun flowFor(player: ExoPlayer): StateFlow<Float> = synchronized(flows) {
        flows.getOrPut(player) {
            MutableStateFlow(0f).also { start(player, it) }
        }
    }

    fun current(player: ExoPlayer): Float = flowFor(player).value

    private const val SAMPLE_MS = 1_000L
    private const val MIN_WINDOW_MS = 2_000L
    private const val MAX_WINDOW_MS = 30_000L

    private fun start(player: ExoPlayer, out: MutableStateFlow<Float>) {
        val ref = WeakReference(player)
        val handler = Handler(player.applicationLooper)
        val sampler = object : Runnable {
            var baseCounters: DecoderCounters? = null
            var baseFrames = 0L
            var basePosMs = 0L
            var baseWallMs = 0L
            var lastPosMs = 0L
            var lastWallMs = 0L

            fun rebase(c: DecoderCounters?, frames: Long, pos: Long, wall: Long) {
                baseCounters = c; baseFrames = frames; basePosMs = pos; baseWallMs = wall
                lastPosMs = pos; lastWallMs = wall
            }

            override fun run() {
                val p = ref.get() ?: return
                runCatching { sample(p) }
                handler.postDelayed(this, SAMPLE_MS)
            }

            fun sample(p: ExoPlayer) {
                val c = p.videoDecoderCounters
                val wall = SystemClock.elapsedRealtime()
                if (c == null) {
                    // Video disabled (stop / channel flip): drop the old rate.
                    baseCounters = null
                    out.value = 0f
                    return
                }
                c.ensureUpdated()
                val frames = (c.renderedOutputBufferCount + c.skippedOutputBufferCount +
                    c.droppedBufferCount).toLong()
                val pos = p.currentPosition
                if (c !== baseCounters) {
                    // New renderer enable = new stream: clear and start over.
                    out.value = 0f
                    rebase(c, frames, pos, wall)
                    return
                }
                if (!p.isPlaying || frames < baseFrames) {
                    rebase(c, frames, pos, wall)
                    return
                }
                // A seek / live-edge jump moves content time unlike wall time.
                val dPosStep = pos - lastPosMs
                val dWallStep = wall - lastWallMs
                lastPosMs = pos
                lastWallMs = wall
                if (abs(dPosStep - dWallStep) > 750L) {
                    rebase(c, frames, pos, wall)
                    return
                }
                val dPos = pos - basePosMs
                if (dPos < MIN_WINDOW_MS) return
                val fps = (frames - baseFrames) * 1000f / dPos
                if (fps < 5f || fps > 130f) {
                    rebase(c, frames, pos, wall)
                    return
                }
                val snapped = snap(fps)
                if (abs(snapped - out.value) > 0.005f) out.value = snapped
                if (dPos > MAX_WINDOW_MS) rebase(c, frames, pos, wall)
            }
        }
        handler.post(sampler)
    }
}
