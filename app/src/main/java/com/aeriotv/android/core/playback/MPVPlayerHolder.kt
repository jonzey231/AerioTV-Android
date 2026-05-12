package com.aeriotv.android.core.playback

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import com.aeriotv.android.feature.player.MPVPlayerView
import `is`.xyz.mpv.Utils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hoists the active [MPVPlayerView] out of any single composable lifecycle so
 * the underlying libmpv handle survives when PlayerScreen exits (system back
 * → mini-player). Without this, every nav transition tears MPV down + does a
 * second multi-second native re-init, and audio cuts.
 *
 * Pattern:
 *   - PlayerScreen calls [acquireOrCreate] inside its AndroidView factory.
 *     The first call constructs MPV; subsequent calls (after a back-out and
 *     resume) detach the same view from its prior parent and hand it back.
 *   - AndroidView's onRelease calls [detach] instead of destroy, leaving MPV
 *     alive in the holder while the surface is unparented.
 *   - When the user dismisses via the explicit X button, the caller invokes
 *     [destroy] to tear MPV down for real.
 *
 * Threading: all entry points expect the main thread (MPVPlayerView attaches
 * to the view hierarchy synchronously).
 */
@Singleton
class MPVPlayerHolder @Inject constructor() {

    var view: MPVPlayerView? = null
        private set

    /** The most-recent channel id played through the held MPV instance, so a
     * resuming PlayerScreen knows whether to skip the playFile re-init. */
    var currentChannelId: String? = null

    /**
     * Return the active MPV view, creating it once on first call. The view is
     * detached from any prior parent before return so the caller can attach
     * it to a fresh ViewGroup (AndroidView's host frame).
     */
    fun acquireOrCreate(
        context: Context,
        caFilePath: String,
        cachingMs: Int,
        isLive: Boolean,
        httpHeaders: Map<String, String>,
        configDir: String,
        cacheDir: String,
    ): MPVPlayerView {
        val existing = view
        if (existing != null) {
            // The hosting AndroidView frame from the prior composition still
            // holds this view as a child; detach so the new frame can adopt.
            (existing.parent as? ViewGroup)?.removeView(existing)
            // The new chrome may have a different buffer/headers config — push
            // those onto the live instance without re-initialising.
            existing.cachingMs = cachingMs
            existing.httpHeaders = httpHeaders
            // Re-enable video output (back-out via mini-player set vid=no).
            runCatching { existing.mpv.setPropertyString("vid", "auto") }
            return existing
        }
        Log.i(TAG, "Creating fresh MPVPlayerView in holder")
        Utils.copyAssets(context)
        val fresh = MPVPlayerView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            this.isLive = isLive
            this.caFilePath = caFilePath
            this.httpHeaders = httpHeaders
            this.cachingMs = cachingMs
        }
        fresh.initialize(configDir, cacheDir)
        view = fresh
        return fresh
    }

    /** Composable-unmount hook. Does NOT destroy MPV; PlaybackService is
     * responsible for keeping the process alive while audio continues. */
    fun detach() {
        val v = view ?: return
        (v.parent as? ViewGroup)?.removeView(v)
    }

    /** Toggle MPV video output. Used when entering / exiting the mini-player
     * so GPU isn't running for an unparented surface. Audio is unaffected. */
    fun setVideoEnabled(enabled: Boolean) {
        val v = view ?: return
        runCatching { v.mpv.setPropertyString("vid", if (enabled) "auto" else "no") }
            .onFailure { Log.w(TAG, "setVideoEnabled $enabled failed", it) }
    }

    /** Pause / resume playback without tearing anything down. */
    fun setPaused(paused: Boolean) {
        val v = view ?: return
        runCatching { v.mpv.setPropertyString("pause", if (paused) "yes" else "no") }
            .onFailure { Log.w(TAG, "setPaused $paused failed", it) }
    }

    fun isPaused(): Boolean = view?.mpv?.getPropertyString("pause") == "yes"

    /** Fully release the MPV core. Invoked when the user explicitly closes
     * the player or session is dismissed via the mini-player X button. */
    fun destroy() {
        val v = view ?: return
        Log.i(TAG, "Destroying held MPVPlayerView")
        (v.parent as? ViewGroup)?.removeView(v)
        v.destroy()
        view = null
        currentChannelId = null
    }

    companion object { private const val TAG = "MPVPlayerHolder" }
}
