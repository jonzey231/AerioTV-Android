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
 * fix).
 *
 * Dispatcharr's MPEG-TS feed for UK UHD channels (e.g. Sky Sports Main Event
 * UHD) runs at 50fps but does NOT signal its frame rate in the container
 * (Format.frameRate == -1). With nothing to match, the panel stays pinned at
 * 60Hz and 50-on-60 pulldown produces constant judder.
 *
 * We measure the real frame rate from rendered-frame presentation timestamps
 * and request a matching display refresh rate via [Surface.setFrameRate] with
 * [Surface.CHANGE_FRAME_RATE_ALWAYS].
 *
 * ALWAYS here does NOT mean "force a mode switch": the framework arbitrates
 * against the USER'S system setting (Display & Sound -> Match content frame
 * rate). Seamless-only users get exactly the old behavior (seamless when the
 * panel can, no-op when it can't); users who chose "Always" get the real
 * non-seamless 60->50 switch they explicitly opted into - the short black
 * resync TiviMate/Plex/Emby do. The previous ONLY_IF_SEAMLESS request capped
 * everyone at seamless and made the OS-level "Always" opt-in dead weight:
 * 60->50 is a different mode group on virtually every panel, so European
 * 50fps streams never matched (field report alturismo 2026-07-18, Google TV
 * Streamer + Shield, system setting Always, TV pinned at 60Hz).
 *
 * Why this and NOT the old preferredDisplayModeId pin (GOTCHA 23): the pin
 * forced a raw HDMI re-handshake that destroyed the persistent SurfaceView
 * mid-stream with no rebind -> dead surface, black picture, audio continuing.
 * A setFrameRate-driven switch is framework-managed: the system coordinates
 * the mode change and the SurfaceView receives normal destroyed/created
 * callbacks, which PlayerView handles by rebinding the video surface.
 *
 * Lifetime: attached once by [PersistentExoWindow] to the activity-lifetime
 * PlayerView's SurfaceView. The listener only does work while frames are
 * actually being rendered, so it is inert when nothing is playing.
 */
@OptIn(UnstableApi::class)
object DisplayFrameRateMatcher {
    private const val TAG = "AerioFpsMatch"

    /**
     * Start measuring [player]'s frame rate and request a refresh-rate match on
     * [surfaceView]'s Surface (framework arbitrates via the user's OS Match
     * content frame rate setting). Returns an opaque handle to pass back to
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
                // Map measured CONTENT fps to the rate we actually request.
                // Only the 50 / 59.94 / 60 class is ever requested: 25 / 29.97
                // / 30 content is requested at DOUBLE rate, which the panel
                // shows with a clean 2:2 cadence - same judder-free result as
                // a native low-rate mode, on a mode the UI can also live on.
                //
                // NEVER request a sub-mode rate directly. Requesting 25 while
                // in the 1080p50 mode put kirkwood's SurfaceFlinger into a
                // 25Hz per-uid frameRateOverride with broken present pacing:
                // 38-40 skipped UI frames at a time and repeated visible video
                // dropouts on the TV (Logan's Hisense, 2026-07-18). The
                // doubling also makes a 25-vs-50 measurement flap harmless -
                // both map to a 50 request, so no repeated mode switches.
                //
                // 24-class film cadence (23.976 / 24) is deliberately NOT
                // requested: it divides neither 50 nor 60, so it keeps the
                // status-quo 3:2 behavior until handled on purpose.
                val target = when {
                    fps in 45f..65f -> fps
                    fps in 24.5f..32.5f -> fps * 2f
                    else -> 0f
                }
                if (target > 0f && abs(target - lastAppliedFps) > 1.0f) {
                    lastAppliedFps = target
                    val surface = surfaceView.holder.surface
                    if (surface != null && surface.isValid) {
                        runCatching {
                            surface.setFrameRate(
                                target,
                                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                                Surface.CHANGE_FRAME_RATE_ALWAYS,
                            )
                        }.onSuccess {
                            Log.i(TAG, "setFrameRate(${"%.2f".format(target)}) for content ${"%.2f".format(fps)}fps strategy=ALWAYS")
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
