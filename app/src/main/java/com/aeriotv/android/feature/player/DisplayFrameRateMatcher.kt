package com.aeriotv.android.feature.player

import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlin.math.abs

/**
 * tvOS-style content frame-rate matching for live TV (the UHD "super stuttery"
 * fix) -- SEAMLESS edition.
 *
 * Dispatcharr's MPEG-TS feed for UK UHD channels (e.g. Sky Sports Main Event
 * UHD) runs at 50fps but does NOT signal its frame rate in the container
 * (Format.frameRate == -1). With nothing to match, the panel stays pinned at
 * 60Hz and 50-on-60 pulldown produces constant judder.
 *
 * We measure the real frame rate from rendered-frame presentation timestamps
 * and request a matching display refresh rate via [Surface.setFrameRate] with
 * [Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS].
 *
 * Why this and NOT the old preferredDisplayModeId pin (GOTCHA 23): pinning
 * window.preferredDisplayModeId on a TV box is a NON-seamless HDMI re-handshake
 * that destroys the persistent SurfaceView mid-stream with no rebind -> the
 * decoder writes into a dead surface and the picture goes BLACK while audio
 * keeps playing. The seamless path can NEVER do that: if the panel can change
 * refresh rate WITHOUT a mode switch it does so smoothly; if it can't, the
 * request is a no-op (judder remains, but video keeps playing). So it is safe
 * to leave on for TV.
 *
 * Lifetime: attached once by [PersistentExoWindow] to the activity-lifetime
 * PlayerView's SurfaceView. The listener only does work while frames are
 * actually being rendered, so it is inert when nothing is playing.
 */
@OptIn(UnstableApi::class)
object DisplayFrameRateMatcher {
    private const val TAG = "AerioFpsMatch"

    /**
     * Start measuring [player]'s frame rate and request a SEAMLESS refresh-rate
     * match on [surfaceView]'s Surface. Returns an opaque handle to pass back to
     * [detach]; null on API < S (the 3-arg setFrameRate strategy is API 31+).
     */
    fun attach(player: ExoPlayer, surfaceView: SurfaceView): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val deltasUs = ArrayDeque<Long>()
        var lastPtsUs = -1L
        var lastAppliedFps = 0f
        val listener = VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            if (lastPtsUs >= 0L) {
                val d = presentationTimeUs - lastPtsUs
                if (d < 0L || d > 1_000_000L) {
                    deltasUs.clear() // stream discontinuity / channel flip
                } else if (d in 4_000L..210_000L) {
                    deltasUs.addLast(d)
                    if (deltasUs.size > 60) deltasUs.removeFirst()
                }
            }
            lastPtsUs = presentationTimeUs
            if (deltasUs.size >= 30) {
                val sorted = deltasUs.sorted()
                val fps = (1_000_000.0 / sorted[sorted.size / 2]).toFloat()
                // Re-request only on a meaningful cadence change (a channel flip
                // to different-fps content), not every frame.
                if (fps in 20f..130f && abs(fps - lastAppliedFps) > 1.0f) {
                    lastAppliedFps = fps
                    val surface = surfaceView.holder.surface
                    if (surface != null && surface.isValid) {
                        runCatching {
                            surface.setFrameRate(
                                fps,
                                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                            )
                        }.onSuccess {
                            Log.i(TAG, "seamless setFrameRate(${"%.2f".format(fps)})")
                        }
                    }
                }
            }
        }
        player.setVideoFrameMetadataListener(listener)
        return listener
    }

    /** Stop measuring and release our frame-rate preference (so the guide /
     *  launcher revert to the panel default). */
    fun detach(player: ExoPlayer?, handle: Any?, surfaceView: SurfaceView?) {
        (handle as? VideoFrameMetadataListener)?.let { l ->
            runCatching { player?.clearVideoFrameMetadataListener(l) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val surface = surfaceView?.holder?.surface
            if (surface != null && surface.isValid) {
                runCatching {
                    surface.setFrameRate(
                        0f,
                        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                    )
                }
            }
        }
    }
}
