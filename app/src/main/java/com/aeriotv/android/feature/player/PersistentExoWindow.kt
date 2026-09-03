package com.aeriotv.android.feature.player

import android.util.Log
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aeriotv.android.core.playback.AerioExoPlayerHolder
import com.aeriotv.android.feature.settings.SettingsViewModel

/**
 * The single, activity-lifetime Media3 [PlayerView]. Direct counterpart of
 * [PersistentMpvWindow] for the Media3 migration.
 *
 * Mounted ONCE inside MainActivity's root Box (as a sibling above NavHost,
 * via the same Phase 165 -> 167 z-order rules), it never changes parents.
 * Only the modifier driving its size + position changes in response to
 * [ExoWindowState] flips from PlayerScreen / TvMiniPlayerOverlay.
 *
 * Visibility modes mirror [PersistentMpvWindow] one-for-one:
 *   - Hidden: AndroidView mounted inside a zero-size Box.
 *   - Fullscreen: fillMaxSize within the outer Box. Default z-order, so
 *     PlayerScreen chrome drawn ON TOP via NavHost (Phase 167's trick).
 *   - Mini: 210x118 dp at top-right with 24dp end / 12dp top inset.
 *     zIndex(1f) so it floats above whatever NavHost route is showing
 *     underneath (Phase 175's fix).
 *
 * Surface mode: PlayerView with surface_type=surface_view. Media3's
 * MediaCodecVideoRenderer writes directly into the SurfaceView's surface
 * via MediaCodec.configure(..., surface) -- this is the path that fixes
 * the QTI HEVC-in-MPEG-TS bug. No GLES blit, no FFmpeg hevc_mediacodec
 * wrapper.
 */
@OptIn(UnstableApi::class)
@Composable
fun BoxScope.PersistentExoWindow(
    holder: AerioExoPlayerHolder,
    state: ExoWindowState,
) {
    val mode by state.mode.collectAsStateWithLifecycle()
    // Recomposes the AndroidView update block whenever the holder swaps the
    // underlying ExoPlayer instance; see the rebind comment below.
    val boundPlayer by holder.playerInstance.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settingsVm: SettingsViewModel = hiltViewModel()
    val aspectMode by settingsVm.playerAspectMode.collectAsStateWithLifecycle(initialValue = "fit")

    // See PersistentMpvWindow for the long form of the z-index rationale.
    // tl;dr: NavHost paints over PersistentExoWindow by declaration order;
    // fullscreen wants that (chrome over video), mini doesn't (mini needs
    // to float above the Guide's opaque background).
    val containerModifier = when (mode) {
        ExoWindowState.Mode.Hidden -> Modifier.size(0.dp)
        ExoWindowState.Mode.Fullscreen -> Modifier
            .fillMaxSize()
            .background(Color.Black)
        ExoWindowState.Mode.Mini -> Modifier
            .zIndex(1f)
            .align(Alignment.TopEnd)
            .padding(end = 24.dp, top = 12.dp)
            // 16:9. Grown from 210x118 to fill the reserved band below it; width
            // is capped here because the top-right corner butts up against the
            // centered top-nav ("Settings") -- wider would overlap it. The
            // MainScaffold mini spacer is trimmed to match so the group pills
            // sit just under this.
            .size(width = 240.dp, height = 135.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
    }

    // Re-anchor the SurfaceView after the activity window is recreated while
    // this window is Hidden. Repro (user log + emulator, the recurring
    // "audio plays, screen stays black" report): minimize playback to the
    // audio chip (Hidden -> the SurfaceView is 0-size and has NO surface),
    // background the app (Android destroys the activity window), return, and
    // start a channel. requestFullscreen resizes the old SurfaceView, which
    // then creates its surface against the DEAD window's layer tree:
    // SurfaceFlinger shows the layer in the Offscreen Hierarchy, consuming
    // 1920x1080 buffers nobody composites. onRenderedFirstFrame fires (so the
    // no-video-frame watchdog rightly stays quiet) but the screen is black
    // until a force-stop. A view that holds a LIVE surface across the window
    // teardown re-anchors correctly through the normal surfaceDestroyed /
    // surfaceCreated cycle, so only the Hidden case needs this; recreating the
    // PlayerView there is free (no surface, no codec attachment to disturb).
    val lifecycleOwner = LocalLifecycleOwner.current
    var surfaceEpoch by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START &&
                state.mode.value == ExoWindowState.Mode.Hidden
            ) {
                surfaceEpoch++
                Log.i(TAG, "window restarted while Hidden; recreating PlayerView (epoch=$surfaceEpoch)")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Shield freeze after a display mode change (Frankie B., 2026-09-02):
    // "the first channel after a UHD <-> HD change freezes; the next channel
    // is fine". His logs show every resolution switch rendering frames (the
    // wedge watchdog never fires) yet the picture stays on the last frame:
    // the HDMI re-handshake reallocates the SurfaceView's buffers under the
    // running decoder ("freeAllBuffers: 1 buffers were freed while being
    // dequeued!") and the decoder keeps rendering into a queue nobody
    // composites any more. A channel change fixes it only because it
    // re-attaches the surface, so do that directly: recreate the PlayerView
    // after the display's RESOLUTION changes.
    //
    // Timing matters (Streamer, 2026-09-02): a surface created while the
    // HDMI transition is still in progress lands on a layer the compositor
    // cannot present, and the decoder renders nothing into it (black screen
    // with audio until the next surface change). So the recreate waits
    // until 1.5s after the LAST display event, and the player's own
    // onRenderedFirstFrame verifies it: no frame within 4s means the surface
    // was still too early, and it is recreated once more.
    //
    // Refresh-rate-only mode changes (the seamless frame-rate matcher's own
    // switches, which can also arrive with an unchanged mode id) are logged
    // and ignored; only a size change acts.
    val displayContext = LocalContext.current
    val recreateGate = remember { SurfaceRecreateGate() }
    // Streamer (MediaTek) at 2160p: a recreated SurfaceView was never
    // presented by the compositor (no frames reached the screen) until the
    // layer's GEOMETRY changed - Back to the mini player did that by
    // accident. So each recreate is followed by a one-frame 1px nudge of
    // the container, which makes SurfaceFlinger re-validate the layer.
    var geometryNudge by remember { mutableStateOf(false) }
    DisposableEffect(boundPlayer) {
        val p = boundPlayer
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() { recreateGate.frameSinceRecreate = true }
        }
        p?.addListener(listener)
        onDispose { p?.removeListener(listener) }
    }
    DisposableEffect(displayContext) {
        val dmgr = displayContext.getSystemService(android.content.Context.DISPLAY_SERVICE)
            as android.hardware.display.DisplayManager
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val startMode = dmgr.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.mode
        var lastW = startMode?.physicalWidth ?: 0
        var lastH = startMode?.physicalHeight ?: 0
        var pending: Runnable? = null
        var audioReinit: Runnable? = null
        var verify: Runnable? = null
        fun playing(): Boolean {
            if (state.mode.value == ExoWindowState.Mode.Hidden) return false
            val p = boundPlayer ?: return false
            return p.playbackState != androidx.media3.common.Player.STATE_IDLE &&
                p.playbackState != androidx.media3.common.Player.STATE_ENDED
        }
        fun recreate(reason: String, retriesLeft: Int) {
            if (!playing()) return
            Log.i(TAG, "recreating PlayerView surface ($reason)")
            recreateGate.frameSinceRecreate = false
            surfaceEpoch++
            mainHandler.postDelayed({
                geometryNudge = true
                mainHandler.postDelayed({ geometryNudge = false }, 120L)
            }, 400L)
            verify?.let { mainHandler.removeCallbacks(it) }
            verify = Runnable {
                if (!recreateGate.frameSinceRecreate && playing() && retriesLeft > 0) {
                    recreate("no frame rendered 4s after the previous recreate", retriesLeft - 1)
                } else if (!recreateGate.frameSinceRecreate && playing()) {
                    Log.w(TAG, "surface recreate: still no rendered frame after retry; leaving it to the watchdog")
                }
            }.also { mainHandler.postDelayed(it, 4000L) }
        }
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != android.view.Display.DEFAULT_DISPLAY) return
                val mode = dmgr.getDisplay(displayId)?.mode ?: return
                val sizeChanged = mode.physicalWidth != lastW || mode.physicalHeight != lastH
                val desc = "${mode.physicalWidth}x${mode.physicalHeight}@${mode.refreshRate} (mode ${mode.modeId})"
                lastW = mode.physicalWidth; lastH = mode.physicalHeight
                val appSwitch = DisplayModeSwitchSignal.pending
                if (!sizeChanged && !appSwitch) {
                    Log.i(TAG, "display changed, same size: $desc; no surface action")
                    return
                }
                if (!playing()) return
                Log.i(TAG, "display mode changed during playback: $desc (sizeChanged=$sizeChanged appSwitch=$appSwitch); surface recreate armed")
                pending?.let { mainHandler.removeCallbacks(it) }
                pending = Runnable { recreate("display settled at $desc", retriesLeft = 1) }
                    .also { mainHandler.postDelayed(it, 1500L) }
                // NVIDIA Shield (Frankie B. log, 2026-09-03): the HDMI mode
                // switch kills the AudioTrack; about a second later the audio
                // renderer hits "AudioTrack write failed: -6" and Media3 tears
                // the sink down and re-buffers, which the user sees as a hang.
                // Hand the player a fresh audio session id shortly after the
                // switch: Media3 flushes the sink and opens a new track on
                // the re-negotiated output before the dead one is written to.
                audioReinit?.let { mainHandler.removeCallbacks(it) }
                audioReinit = Runnable {
                    val p = holder.player ?: return@Runnable
                    val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                    val id = am?.generateAudioSessionId() ?: return@Runnable
                    runCatching { p.audioSessionId = id }
                        .onSuccess { Log.i(TAG, "audio sink re-init after display mode change (session $id)") }
                        .onFailure { Log.w(TAG, "audio sink re-init failed", it) }
                }.also { mainHandler.postDelayed(it, 400L) }
            }
        }
        dmgr.registerDisplayListener(listener, mainHandler)
        onDispose {
            dmgr.unregisterDisplayListener(listener)
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    Box(modifier = containerModifier.padding(end = if (geometryNudge) 1.dp else 0.dp)) {
        key(surfaceEpoch) {
        // Survives recompositions within this epoch; disposed (and detached via
        // onRelease) when surfaceEpoch flips and the SurfaceView is recreated.
        val fpsMatch = remember { FrameRateMatchAttachment() }
        AndroidView(
            factory = { ctx ->
                Log.i(TAG, "PersistentExoWindow factory: building PlayerView + binding player")
                val player = holder.acquireOrCreate(ctx)
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // We render our own chrome via Compose (PlayerChromeOverlay).
                    // The built-in PlayerView controls would compete with it.
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    // Letterbox (FIT) matches the iOS canon and our libmpv
                    // setup -- broadcast streams have intrinsic aspect ratios
                    // we don't want to crop. ZOOM is a Settings-level toggle
                    // we'll port from MPV later.
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    // Critical on Android TV: PlayerView is focusable by
                    // default. With our Compose chrome layered above it via
                    // NavHost, a focused PlayerView swallows BACK before
                    // PlayerScreen's BackHandler ever sees the event -- the
                    // user ends up at the launcher on first BACK rather than
                    // surfacing chrome (the tvOS-style 3-press flow). Lock
                    // focus traversal out of this View; the chrome controls
                    // are the only focusable surface in the activity.
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    // Surface mode is set via the bundled layout
                    // exo_player_view.xml's surface_type=surface_view default.
                    setPlayer(player)
                    // Seamless content frame-rate matching (UHD judder fix).
                    // Requests a refresh-rate match on THIS SurfaceView's Surface
                    // via Surface.setFrameRate(CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
                    // -- it never pins the display mode, so it cannot black out
                    // the video like the old preferredDisplayModeId path
                    // (GOTCHA 23). TV-only + self-gating (acts only while frames
                    // flow); API 31+ inside the matcher.
                    val tvUiMode = ctx.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_TYPE_MASK
                    if (tvUiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
                        (videoSurfaceView as? android.view.SurfaceView)?.let { sv ->
                            // Pin the surface BUFFER to the display size so the
                            // Fullscreen <-> Mini layout RESIZE does not recreate
                            // the underlying Surface. A SurfaceView whose bounds
                            // change otherwise destroys + recreates its Surface
                            // (new "surface generation"), which forces the video
                            // MediaCodec to be torn down and re-initialised against
                            // the new surface -> a multi-second freeze + re-buffer
                            // when entering the mini-player (NOT a network re-fetch;
                            // the stream keeps playing, only the decoder resets).
                            // With a fixed buffer size, SurfaceFlinger's hardware
                            // scaler maps the constant-size buffer into whatever
                            // display rect the view occupies (fullscreen or the
                            // 210x118 mini), so the Surface -- and the codec bound
                            // to it -- survive the transition untouched.
                            val dm = ctx.resources.displayMetrics
                            sv.holder.setFixedSize(dm.widthPixels, dm.heightPixels)
                            // Retain the handle + player + SurfaceView so the
                            // matcher can be re-attached after a holder rebuild
                            // (update block) and detached on teardown (onRelease).
                            fpsMatch.handle = DisplayFrameRateMatcher.attach(player, sv)
                            fpsMatch.player = player
                            fpsMatch.surfaceView = sv
                        }
                    }
                }
            },
            update = { view ->
                // iOS Issue #26: live aspect-ratio toggle (Fit / Zoom / Fill).
                view.resizeMode = when (aspectMode) {
                    "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                // Rebind when the holder recreated the player (post-X-close
                // re-create, media-service acquire, passthrough rebuild).
                // The factory's one-time setPlayer covered only the original
                // instance; without this, a recreated player renders into a
                // placeholder surface: audio with a black screen until the
                // app process restarts (GitHub report).
                val current = boundPlayer
                if (current != null && view.player !== current) {
                    Log.i(TAG, "PersistentExoWindow: rebinding PlayerView to recreated player")
                    view.player = current
                }
                // Re-attach the seamless frame-rate matcher to the rebuilt
                // player. The factory attached only the original instance; a
                // holder rebuild (black-screen recreate / passthrough rebuild)
                // otherwise leaves the matcher bound to a destroyed player and
                // UHD judder returns for the rest of the session. TV-only:
                // surfaceView is null off-TV, so this is a no-op there. The
                // player-identity guard keeps it from firing on every recompose.
                val matchSv = fpsMatch.surfaceView
                if (matchSv != null && current != null && current !== fpsMatch.player) {
                    DisplayFrameRateMatcher.detach(fpsMatch.player, fpsMatch.handle, matchSv)
                    fpsMatch.handle = DisplayFrameRateMatcher.attach(current, matchSv)
                    fpsMatch.player = current
                }
            },
            modifier = Modifier.fillMaxSize(),
            // onRelease only runs on a surfaceEpoch swap (the view otherwise
            // outlives every composition). Detach the player from the dying
            // view; Media3 ignores the clear when the player's active surface
            // already belongs to the replacement view, so ordering is safe.
            onRelease = { view ->
                // Detach the frame-rate matcher (clear the listener + reset the
                // Surface's frame-rate preference to the panel default) before
                // the SurfaceView is destroyed on this epoch swap.
                fpsMatch.surfaceView?.let { sv ->
                    DisplayFrameRateMatcher.detach(fpsMatch.player, fpsMatch.handle, sv)
                }
                fpsMatch.player = null
                fpsMatch.handle = null
                fpsMatch.surfaceView = null
                view.player = null
            },
        )
        }
    }
}

private const val TAG = "PersistentExoWindow"

/** Whether the player has rendered a frame since the last surface recreate. */
private class SurfaceRecreateGate {
    @Volatile var frameSinceRecreate = true
}

/**
 * Retains the [DisplayFrameRateMatcher] attachment so it can be re-attached
 * after a holder player rebuild and detached when the SurfaceView is torn down.
 * The factory previously discarded the attach() handle and detach() was never
 * called, so seamless frame-rate matching was lost for the rest of the session
 * after any black-screen / passthrough rebuild, and the Surface's frame-rate
 * preference was never reset on an epoch swap. TV-only: [surfaceView] is set
 * only inside the TV gate, so the re-attach / detach paths are no-ops elsewhere.
 */
private class FrameRateMatchAttachment {
    var player: androidx.media3.exoplayer.ExoPlayer? = null
    var handle: Any? = null
    var surfaceView: android.view.SurfaceView? = null
}
