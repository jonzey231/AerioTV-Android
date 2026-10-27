package com.aeriotv.android.feature.player

import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.PlayerView
import com.aeriotv.android.core.playback.PlaybackTracer
import java.util.WeakHashMap

/**
 * Attaches [DisplayFrameRateMatcher] to the VOD player (movies, series, DVR
 * and catch-up through VODPlayerScreen). The live path does this in
 * PersistentExoWindow; VOD built its own ExoPlayer and never attached, so a
 * 25 / 50 Hz movie with no container fps got neither the Surface.setFrameRate
 * hint nor the Match Frame Rate mode pin and played 50-on-60 (Streamer,
 * 2026-10-27). Kept out of VODPlayerScreen because that composable sits at
 * the ART verifier's register limit.
 */
internal object VodFrameRateMatch {
    private const val TAG = "AerioFpsMatch"

    private class Attachment(
        val player: ExoPlayer,
        val handle: Any?,
        val surfaceView: SurfaceView,
        val prevPlayer: ExoPlayer?,
        val prevHandle: Any?,
    )

    private val attachments = WeakHashMap<PlayerView, Attachment>()

    /** Same semantics as the live window: TV requests the rate (the mode pin
     *  in MainActivity stays gated by Match Frame Rate), phones and tablets
     *  measure only for the format badge. */
    fun attach(view: PlayerView, player: ExoPlayer, tracer: PlaybackTracer) {
        detach(view, tracer)
        // Media3 must not cast its own container-fps vote over the matcher's.
        player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        val sv = view.videoSurfaceView as? SurfaceView ?: return
        val prevPlayer = DisplayFrameRateMatcher.attachedPlayer
        val prevHandle = DisplayFrameRateMatcher.latestHandle
        val handle = DisplayFrameRateMatcher.attach(player, sv, matchRate = isTv(view), filmRate = true)
        attachments[view] = Attachment(player, handle, sv, prevPlayer, prevHandle)
        tracer.contentFpsProvider = { DisplayFrameRateMatcher.contentFps.value }
        runCatching {
            player.setVideoFrameMetadataListener(
                tracer.frameMetadataListener(handle as? VideoFrameMetadataListener),
            )
        }
        Log.i(TAG, "[AerioFpsMatch] attached to VOD player")
    }

    /** Called from the view's onRelease (route dispose, Cast handoff close,
     *  and the phone-mini handoff), before the player is released or retained. */
    private fun isTv(view: PlayerView): Boolean =
        (view.context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

    fun detach(view: PlayerView, tracer: PlaybackTracer) {
        val a = attachments.remove(view) ?: return
        // Skip the Surface reset and listener clear when a newer attachment
        // (the live window, or the mini's) already owns the matcher.
        if (a.handle != null && a.handle === DisplayFrameRateMatcher.latestHandle) {
            DisplayFrameRateMatcher.detach(a.player, a.handle, a.surfaceView)
            // The live window stays attached to its own player underneath.
            DisplayFrameRateMatcher.restoreOwner(a.prevPlayer, a.prevHandle)
            DisplayFrameRateMatcher.onVodDetached?.invoke()
        }
        runCatching { a.player.setVideoFrameMetadataListener(tracer.frameMetadataListener(null)) }
        tracer.contentFpsProvider = null
    }
}
