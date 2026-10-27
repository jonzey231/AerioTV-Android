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
 * resync other players do. The previous ONLY_IF_SEAMLESS request capped
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

    /** Latest requested rate class (50 / 59.94 / 60), 0 until measured. */
    @Volatile var lastRequestedRate: Float = 0f
        private set

    /**
     * The MEASURED content frame rate (the raw median, NOT the doubled request
     * class), 0 when nothing has been measured for the current stream. Live
     * MPEG-TS very often carries no frameRate in the container
     * (Format.frameRate == -1), so this is the fallback the Stream Info panel
     * and the chrome's format badge use rather than printing nothing (Logan
     * 2026-09-11). Cleared on a stream discontinuity / channel flip, so a new
     * channel never shows the previous one's rate.
     */
    val contentFps = kotlinx.coroutines.flow.MutableStateFlow(0f)

    /** The handle the most recent [attach] returned. A surface-epoch swap
     *  builds the NEW view (and attaches) before the OLD view's onRelease
     *  runs; the old release must not detach / re-register over the newer
     *  attachment on the same player (Logan 2026-10-04: that left the phone
     *  and tablet badge at "720p" with no fps for the whole session). */
    @Volatile var latestHandle: Any? = null
        private set

    /** The player [contentFps] belongs to, so another player (VOD, a
     *  Multiview tile) never borrows the main player's rate. */
    @Volatile var attachedPlayer: ExoPlayer? = null
        private set

    /** Fired (on the metadata thread) whenever the requested rate class
     *  changes. MainActivity uses it to pick a display MODE at that rate when
     *  the seamless request cannot be honoured (2160p50 on a 60 Hz mode). */
    @Volatile var onRateRequested: ((Float) -> Unit)? = null

    /** Fired on the main thread when the VOD player's attachment is torn
     *  down, so MainActivity can drop a mode pin the VOD title requested
     *  when no live playback needs it. */
    @Volatile var onVodDetached: (() -> Unit)? = null

    /**
     * Start measuring [player]'s frame rate and request a refresh-rate match on
     * [surfaceView]'s Surface (framework arbitrates via the user's OS Match
     * content frame rate setting). Returns an opaque handle to pass back to
     * [detach]; null on API < S (the 3-arg setFrameRate strategy is API 31+).
     */
    fun attach(
        player: ExoPlayer,
        surfaceView: SurfaceView,
        /** False on phones / tablets: measure [contentFps] only (the format
         *  badge and Stream Info need it) and never request a display rate. */
        matchRate: Boolean = true,
        /** VOD only: also request the 24 film class (23.976 / 24). Live keeps
         *  the 50 / 60 classes only. */
        filmRate: Boolean = false,
    ): Any? {
        // Android#134: measure on every API level. Below S (Shield on
        // Android 11) there is no Surface.setFrameRate, but the measured rate
        // still drives MainActivity's Match Frame Rate mode pick through
        // [onRateRequested].
        val deltasUs = ArrayDeque<Long>()
        var lastPtsUs = -1L
        var lastAppliedFps = 0f
        var lastLoggedClass = Float.NaN
        val listener = VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            if (lastPtsUs >= 0L) {
                val d = presentationTimeUs - lastPtsUs
                if (d < 0L || d > 1_000_000L) {
                    deltasUs.clear() // stream discontinuity / channel flip
                    contentFps.value = 0f
                    lastLoggedClass = Float.NaN
                } else if (d in 4_000L..210_000L) {
                    deltasUs.addLast(d)
                    if (deltasUs.size > 60) deltasUs.removeFirst()
                }
            }
            lastPtsUs = presentationTimeUs
            if (deltasUs.size >= 30) {
                // Trimmed MEAN, not the median: some feeds carry PTS rounded
                // to whole milliseconds (16 / 17 ms alternating at 59.94), and
                // a median lands on 17 ms = 58.82 fps (measured on the Streamer
                // 2026-10-05). The mean of the middle 80% recovers 16.68 ms.
                val sorted = deltasUs.sorted()
                val trim = sorted.size / 10
                val mid = sorted.subList(trim, sorted.size - trim)
                val fps = (1_000_000.0 / mid.average()).toFloat()
                if (abs(fps - contentFps.value) > 0.2f) contentFps.value = fps
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
                //
                // VOD (filmRate) requests the 24 class directly: a film title
                // on a 60 Hz panel otherwise plays with 3:2 pulldown judder
                // (Streamer 2026-10-27). 24 is a real panel mode, not a
                // sub-mode override, so the 25 lesson above does not apply.
                val target = when {
                    fps in 45f..65f -> fps
                    fps in 24.5f..32.5f -> fps * 2f
                    filmRate && fps in 23.5f..24.5f -> filmTarget(sorted, surfaceView)
                    else -> 0f
                }
                val cls = if (target > 0f) target else Math.round(fps).toFloat()
                if (lastLoggedClass.isNaN() || abs(cls - lastLoggedClass) > 1.0f) {
                    lastLoggedClass = cls
                    // Always PTS-derived: this runs on the playback thread,
                    // where reading player.videoFormat throws a wrong-thread
                    // runtime check.
                    Log.i(TAG, "[AerioFpsMatch] measured ${"%.3f".format(fps)} (n=${deltasUs.size}, source=pts)")
                }
                if (matchRate && target > 0f && abs(target - lastAppliedFps) > 1.0f) {
                    lastAppliedFps = target
                    lastRequestedRate = target
                    onRateRequested?.invoke(target)
                    val surface = surfaceView.holder.surface
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        surface != null && surface.isValid
                    ) {
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
        latestHandle = listener
        attachedPlayer = player
        return listener
    }

    /** 23.976 when the median frame interval says below 24.0 fps and the
     *  display lists a 23.976-class mode, else 24. */
    private fun filmTarget(sorted: List<Long>, surfaceView: SurfaceView): Float {
        val median = 1_000_000.0 / sorted[sorted.size / 2]
        if (median >= 24.0) return 24f
        val modes = runCatching { surfaceView.display?.supportedModes }.getOrNull() ?: return 24f
        return if (modes.any { abs(it.refreshRate - 23.976f) < 0.01f }) 23.976f else 24f
    }

    /** Hand ownership back to an earlier attachment (the live window) after
     *  a temporary one (VOD) detaches, so [attachedPlayer] / [latestHandle]
     *  never keep pointing at a released player. */
    fun restoreOwner(player: ExoPlayer?, handle: Any?) {
        attachedPlayer = player
        latestHandle = handle
    }

    /** Stop measuring and release our frame-rate preference (so the guide /
     *  launcher revert to the panel default). */
    fun detach(player: ExoPlayer?, handle: Any?, surfaceView: SurfaceView?) {
        contentFps.value = 0f
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
