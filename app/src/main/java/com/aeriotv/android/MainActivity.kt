package com.aeriotv.android

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aeriotv.android.core.pip.enterPip16x9
import com.aeriotv.android.core.playback.AerioMediaPlaybackService
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import androidx.compose.ui.Modifier
import com.aeriotv.android.core.cast.AerioCastReceiverController
import com.aeriotv.android.core.pip.PipState
import com.aeriotv.android.core.playback.AerioExoPlayerHolder
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.system.NotificationPermissionGate
import com.aeriotv.android.feature.miniplayer.MiniPlayerSession
import com.aeriotv.android.feature.player.ExoWindowState
import com.aeriotv.android.feature.player.PersistentExoWindow
import com.aeriotv.android.feature.splash.SplashGate
import com.aeriotv.android.ui.theme.AerioTVTheme
import com.aeriotv.android.ui.theme.AppTheme
import com.aeriotv.android.ui.theme.AppearanceMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var miniPlayerSession: MiniPlayerSession
    @Inject lateinit var exoHolder: AerioExoPlayerHolder
    @Inject lateinit var exoWindowState: ExoWindowState
    @Inject lateinit var castReceiver: AerioCastReceiverController
    @Inject lateinit var castSender: com.aeriotv.android.core.cast.AerioCastSender
    @Inject lateinit var companionHost: com.aeriotv.android.core.cast.companion.CompanionHostController

    /**
     * Most recent deep-link target the activity has received from a
     * `aeriotv://channel/<id>` or `aeriotv://vod/<uuid>` Intent. Read by
     * the Compose tree via [DeepLinkTargetHolder] / a CompositionLocal
     * provider so NavHost can pop straight onto the target route once
     * the active playlist is ready. Drained (set null) after consumption
     * so a second tap on the same notification re-fires correctly.
     */
    private val deepLinkTarget = androidx.compose.runtime.mutableStateOf<DeepLinkTarget?>(null)

    /**
     * Flipped true by [onKeyLongPress] when BACK is HELD on Android TV, to
     * surface the "Would you like to close AerioTV?" confirm (issue #16: the
     * 0.3.0 short-Back rework removed the old exit popup). Read by the Compose
     * tree in [onCreate]; Cancel/dismiss sets it back to false, Close calls finish().
     */
    private val showExitConfirm = androidx.compose.runtime.mutableStateOf(false)

    /** Deadline (uptimeMillis) until which a held D-pad Right is swallowed after
     *  it closed the corner mini-player, so the still-held Right can't scroll the
     *  guide once the mini is gone ("hold position until released"). 0 = not
     *  pinning; cleared on Right release, and self-expires after RIGHT_HOLD_PIN_MS
     *  as a backstop if the release event is missed. */
    private var rightHoldPinUntil = 0L

    /**
     * Audit task #22 mini-player resume. The Google TV Streamer remote has
     * no dedicated play/pause key, so we repurpose a double-press of D-pad
     * Select (KEYCODE_DPAD_CENTER, also KEYCODE_ENTER on some remotes) as
     * the "bring me back to fullscreen" affordance while the mini-player is
     * Active. We don't consume the FIRST press - it still acts as a normal
     * Compose click on whatever's focused - and only consume the SECOND
     * press when it lands inside the double-press window AND the mini-player
     * is showing. That keeps single-press OK working in all other contexts.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The mini-player no longer hijacks OK. Previously, while the mini was
        // Active, EVERY D-pad Select was consumed (double-press = resume), which
        // trapped the user: a single OK on a guide cell did nothing, so they
        // couldn't start a different channel without restarting the app
        // (Coolwolf report). OK now always reaches Compose, so selecting any
        // channel in the guide plays it fullscreen and supersedes the mini.
        // Resume = just select the channel that's playing in the corner.
        //
        // tvOS parity: Play/Pause on the mini EXPANDS to fullscreen (tvOS
        // NowPlayingManager). Intercept it BEFORE the MediaSession would pause
        // playback, but only while the mini is Active on TV. Single BACK also
        // resumes (handled by the mini overlay's BackHandler).
        if (event.action == KeyEvent.ACTION_DOWN && isTelevisionDevice() &&
            (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) &&
            miniPlayerSession.state.value is MiniPlayerSession.State.Active
        ) {
            miniPlayerSession.requestResume()
            return true
        }
        // Hold D-pad RIGHT while the corner mini-player is Active to close it:
        // stop playback and drop back to a clean guide (tvOS parity; the mini
        // otherwise had no close affordance -- Freyguy report). dispatchKeyEvent
        // sees the key BEFORE Compose focus consumes Right. "Hold position until
        // released" is honored (parity with the guide's hold-Left pin): a single
        // tap (repeatCount 0) still navigates the guide, but the moment Right is
        // HELD (repeatCount >= 1) with the mini up we swallow every repeat so
        // guide focus doesn't scroll during the hold, fire the close at the
        // threshold (isLongPress || repeatCount past MINI_CLOSE_HOLD_REPEAT,
        // ~0.5s), and keep swallowing afterwards (rightHoldPinUntil) until the key
        // is RELEASED, so the still-held Right can't fly focus across the guide
        // once the mini is gone. The pin self-expires after RIGHT_HOLD_PIN_MS as a
        // backstop for a missed release. Same teardown as the X-close / PiP dismiss.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_UP) {
            rightHoldPinUntil = 0L
        }
        if (event.action == KeyEvent.ACTION_DOWN && isTelevisionDevice() &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        ) {
            if (android.os.SystemClock.uptimeMillis() < rightHoldPinUntil) {
                return true   // pinned after close: hold position until release
            }
            if (miniPlayerSession.state.value is MiniPlayerSession.State.Active) {
                if (event.isLongPress || event.repeatCount >= MINI_CLOSE_HOLD_REPEAT) {
                    runCatching { miniPlayerSession.dismiss() }
                    runCatching { exoWindowState.hide() }
                    runCatching { exoHolder.stop() }
                    AerioMediaPlaybackService.stop(this)
                    rightHoldPinUntil = android.os.SystemClock.uptimeMillis() + RIGHT_HOLD_PIN_MS
                    return true
                }
                if (event.repeatCount >= 1) {
                    return true   // held below threshold: hold position, don't scroll
                }
                // repeatCount 0 (a tap): fall through so a short Right still navigates.
            }
        }
        // Live channel surf: D-pad UP/DOWN flips prev/next channel while the
        // FULLSCREEN live player is frontmost, even when its controls overlay is
        // visible. Routing here (before Compose focus) is what makes UP/DOWN win
        // over the chrome pill row + the Options DropdownMenu popup, which would
        // otherwise consume the keys. Gated on exoWindowState.mode == Fullscreen
        // (only the live PlayerScreen sets that; VOD owns its own per-screen
        // player and never touches exoWindowState), so this never fires for VOD
        // or on the guide. Auto-repeat delivers repeated ACTION_DOWNs; the hook's
        // own debounce paces them so a held key surfs one channel at a time. If
        // the hook is null / declines (menu open, setting off, single channel) we
        // fall through so nothing else breaks.
        if (event.action == KeyEvent.ACTION_DOWN && isTelevisionDevice() &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) &&
            exoWindowState.mode.value == ExoWindowState.Mode.Fullscreen
        ) {
            val delta = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1
            if (exoWindowState.onLiveChannelFlip?.invoke(delta) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Android TV BACK handling. A SHORT back is routed through the
     * OnBackPressedDispatcher (onKeyUp) so every existing BackHandler still
     * fires (mini resume/dismiss, the guide's double-Back-to-top ladder, nav-up).
     * A LONG back (onKeyLongPress) surfaces the "close AerioTV?" confirm (issue
     * #16). onKeyDown must startTracking() for the long-press to fire at all.
     * Predictive back is off, so this legacy key path is authoritative.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isTelevisionDevice()) {
            // Arm long-press tracking; the action is decided in onKeyLongPress
            // (resume) or onKeyUp (normal short back).
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        // Issue #16: holding BACK on Android TV surfaces the "close AerioTV?"
        // confirm. The 0.3.0 short-Back rework (double-Back -> top channel, then
        // finish() with no prompt) left exit undiscoverable and easy to trigger
        // by accident; a deliberate hold restores a confirmable exit. Consuming
        // the long-press cancels the follow-up onKeyUp (event.isCanceled), so the
        // short-Back paths (mini resume, guide back-to-top, nav-up) are untouched.
        if (keyCode == KeyEvent.KEYCODE_BACK && isTelevisionDevice()) {
            showExitConfirm.value = true
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isTelevisionDevice()) {
            // A handled long-press cancels this up -> ignore it. Otherwise it's
            // a short press: run the normal back through the dispatcher so the
            // existing BackHandlers / nav still work.
            if (event.isCanceled) return true
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.inPictureInPicture.value = isInPictureInPictureMode
        // Closing the PiP window with its X must STOP playback. Android only
        // tells us "PiP ended"; the X-dismiss and the expand-back-to-app land
        // in the same callback. They differ by lifecycle state: on expand the
        // activity is on its way to RESUMED (>= STARTED here); on X-dismiss it
        // was already stopped, so we sit at CREATED. Without this the player
        // singleton kept decoding and audio played on in the background until
        // a force-stop (tester report on 0.2.4/0.2.5). Same teardown order as
        // the player's proven X-close path.
        if (!isInPictureInPictureMode &&
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            runCatching { miniPlayerSession.dismiss() }
            runCatching { exoWindowState.hide() }
            runCatching { exoHolder.stop() }
            // VOD/DVR own a screen-local ExoPlayer the live holder above can't
            // reach; the mounted player screen registers a stop hook so closing
            // its PiP with the X stops it too instead of playing on at the
            // launcher (#120).
            runCatching { PipState.onPipDismissed?.invoke() }
            AerioMediaPlaybackService.stop(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-pin on resume so a fold/unfold display switch (the cover and inner
        // panels expose different display-mode ids) keeps the highest rate.
        requestHighestRefreshRate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Cast Connect (GH #33): a Cast LAUNCH arriving while the app is already
        // up lands here (singleTop). Let MediaManager consume it first; if it was
        // a cast load the receiver's load callback fires and drives playback via
        // the loadRequests collector, so we skip deep-link parsing for it.
        if (castReceiver.handleIntent(intent)) return
        // singleTop means a second LAUNCH/aeriotv:// intent arrives here
        // instead of recreating the activity. Capture the URI for the
        // Compose tree.
        captureDeepLinkFrom(intent)
    }

    /**
     * Pull a deep-link target out of [intent.data] when the scheme is
     * `aeriotv`. Supported hosts: `channel`, `vod`, `guide`. Path is the id /
     * uuid / guideMatchKey. Anything else is ignored.
     */
    private fun captureDeepLinkFrom(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.scheme.equals("aeriotv", ignoreCase = true)) return
        val host = data.host?.lowercase() ?: return
        val path = data.pathSegments?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        val target = when (host) {
            "channel" -> DeepLinkTarget.Channel(path)
            "vod" -> DeepLinkTarget.Vod(path)
            "guide" -> {
                val start = data.getQueryParameter("start")?.toLongOrNull()
                    ?: return // no start time => cannot anchor; ignore
                DeepLinkTarget.GuideProgram(path, start)
            }
            else -> null
        } ?: return
        deepLinkTarget.value = target
    }

    /**
     * TV leave-app teardown. onUserLeaveHint does NOT reliably fire on every
     * Android TV launcher's HOME press (verified on the Google TV Streamer:
     * the app backgrounded but onUserLeaveHint never ran, so playback kept
     * going). onStop ALWAYS fires when the activity goes invisible (HOME,
     * overview, app switch, screen off), so the TV stop lives here. Guarded by
     * !isChangingConfigurations so a config-change recreation (which also calls
     * onStop) never kills audio, and by isTelevisionDevice so phones keep their
     * background-audio / PiP behavior. Back-to-mini does NOT stop the activity,
     * so the mini-player keeps playing -- only a real leave triggers this.
     */
    override fun onStop() {
        if (isTelevisionDevice() && !isChangingConfigurations) {
            android.util.Log.i("AerioLeave", "onStop: TV leave -> stopping playback")
            runCatching { miniPlayerSession.dismiss() }
            runCatching { exoWindowState.hide() }
            runCatching { exoHolder.stop() }
            AerioMediaPlaybackService.stop(this)
        }
        super.onStop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        when {
            // Android TV / leanback: there is no PiP and no music-app
            // background-audio expectation, so leaving the app (HOME / overview)
            // must STOP playback. Without this the live holder + foreground
            // media service keep decoding and audio plays on at the launcher
            // (jonzee222 report): syncAutoEnterPip no-ops with no PiP feature,
            // the API<31 branch never runs on a modern TV, so the old when{}
            // matched nothing and nothing tore down. onUserLeaveHint fires only
            // on a genuine user leave -- never on config-change/fold recreation
            // -- so this cannot kill audio on a recreation. stop() (not
            // destroy()) so a quick relaunch reuses the holder. Same teardown
            // order as the X-close path. Must be FIRST so it short-circuits the
            // audio-only + API<31 video branches on TV.
            isTelevisionDevice() -> {
                runCatching { miniPlayerSession.dismiss() }
                runCatching { exoWindowState.hide() }
                runCatching { exoHolder.stop() }
                AerioMediaPlaybackService.stop(this)
            }
            // Audio-only: never enter PiP. Keep a foreground media notification
            // alive so audio continues with status-bar + lock-screen controls.
            PipState.audioPlaybackActive.value -> {
                // MediaSession picks up title / subtitle / artwork from
                // MediaItem.mediaMetadata automatically; no extras needed.
                AerioMediaPlaybackService.startBackground(this)
            }
            // Video on API < 31 has no setAutoEnterEnabled, so trigger PiP here.
            // API 31+ auto-enters via the params synced in syncAutoEnterPip.
            PipState.videoPlaybackActive.value &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> enterPip16x9()
        }
    }

    override fun onDestroy() {
        // Explicit app exit (Back -> Exit dialog -> finish) must stop playback.
        // The ExoPlayer holder + media session are process-scoped singletons, so
        // without this they keep decoding audio after the activity is gone (the
        // "audio still plays after Exit" report). Gated on isFinishing so a
        // config-change recreation doesn't kill playback; HOME / leave keeps
        // playing via onUserLeaveHint, which does NOT finish the activity.
        if (isFinishing) {
            runCatching { miniPlayerSession.dismiss() }
            runCatching { exoWindowState.hide() }
            runCatching { exoHolder.destroy() }
            AerioMediaPlaybackService.stop(this)
        }
        super.onDestroy()
    }

    /**
     * Mirror [PipState.videoPlaybackActive] into the window's PiP params so the
     * system auto-enters Picture-in-Picture on leave (API 31+). No-op on older
     * versions (handled by onUserLeaveHint) and on devices without PiP.
     */
    private fun syncAutoEnterPip(videoActive: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        // Modern Google TV / Android TV DOES support PiP (the Google TV
        // Streamer auto-entered PiP on HOME), which kept audio + video playing
        // at the launcher -- the "audio keeps playing after leaving" report.
        // tvOS parity is: leaving the app STOPS playback (done in onStop). So
        // never auto-enter PiP on a TV; phones keep auto-PiP.
        val enable = videoActive && !isTelevisionDevice()
        runCatching {
            setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(enable)
                    .build(),
            )
        }
    }

    /**
     * Opt the window into the display's highest-refresh-rate mode at the current
     * resolution (e.g. 120Hz on the Z Fold panels) so the UI renders at the full
     * panel rate instead of being held at 60Hz. Samsung One UI in particular runs
     * apps that don't request a mode at 60Hz, and Android's frame-rate "category"
     * keeps non-voting surfaces low; pinning preferredDisplayModeId is the
     * documented opt-in. Filters to the current resolution so we never switch the
     * panel's pixel size, only its refresh rate. No-op when one mode exists.
     */
    /**
     * True for Android TV / leanback set-top boxes (Mecool, Shield, Google TV
     * Streamer, etc). FEATURE_LEANBACK is the canonical TV signal; the uiMode
     * check is a belt-and-braces fallback for boxes that under-report it.
     */
    private fun isTelevisionDevice(): Boolean {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return mode == Configuration.UI_MODE_TYPE_TELEVISION
    }

    private fun requestHighestRefreshRate() {
        // User report (v0.1.6, Mecool KM2 Plus / Amlogic S905X4): "the screen
        // goes black when opening the app." Pinning preferredDisplayModeId
        // forces an HDMI display-mode switch, and TV boxes (Amlogic especially)
        // do a full black-screen re-handshake on ANY mode change. This routine
        // exists for Samsung Z Fold panels, where 60->120Hz switching is
        // seamless and worthwhile; on a TV the panel is already at its native
        // rate, so the only effect is a black flash on every resume for zero
        // gain. Skip it on TV/leanback devices; keep it for phones + foldables.
        if (isTelevisionDevice()) return
        val disp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION") windowManager.defaultDisplay
        } ?: return
        val current = disp.mode ?: return
        val best = disp.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .maxByOrNull { it.refreshRate } ?: return
        if (window.attributes.preferredDisplayModeId != best.modeId) {
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = best.modeId
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // TV soft-input mode history (GH #1 and two user reports):
        //  - RESIZE originally fed a per-frame recompose + bring-into-view
        //    loop (the onboarding jiggle) -> switched to PAN.
        //  - PAN slid the whole window up and exposed black behind the
        //    keyboard -> switched to ADJUST_NOTHING.
        //  - ADJUST_NOTHING left the keyboard COVERING lower form fields
        //    (no insets, so nothing scrolls them clear).
        // RESIZE is correct again now that both root causes are fixed at the
        // source: TvImeNoJitterBringIntoViewSpec deadbands the 1px scroll
        // oscillation, and the keyboard-on-OK gate means the IME only opens
        // on a deliberate click, so the focused field is scrolled above the
        // keyboard by ordinary inset handling and is never covered.
        if (isTelevisionDevice()) {
            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        // Keep the window's PiP params in sync with player video state so the
        // system auto-enters Picture-in-Picture when the user leaves the app while
        // video is playing (API 31+). Audio-only is excluded -- onUserLeaveHint
        // surfaces a background media notification for that case instead.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PipState.videoPlaybackActive.collect { syncAutoEnterPip(it) }
            }
        }
        // Debug-only auto-load hook so dev iteration on emulators doesn't have to fight
        // Gboard's stylus tutorial when typing test URLs. Hard-gated behind BuildConfig.DEBUG
        // so release builds NEVER accept a URL via intent extra. Production deep-link
        // handling will introduce its own intent-filter when needed, not this path.
        val initialUrl = if (BuildConfig.DEBUG) intent?.getStringExtra("url") else null
        val initialEpgUrl = if (BuildConfig.DEBUG) intent?.getStringExtra("epg") else null
        val initialApiKey = if (BuildConfig.DEBUG) intent?.getStringExtra("apikey") else null
        // Cast Connect (GH #33) SENDER: warm CastContext so the phone/tablet can
        // discover cast devices and show the Cast button. No-op on a Cast-disabled
        // build (no App ID) or a device without Google Play services.
        runCatching { castSender.warm(this) }
        // Cast Connect (GH #33): observe validated cast loads and route each into
        // the SAME deep-link path a channel/vod tap uses, so the fullscreen player
        // mounts the persistent surface and plays the raw TS with video enabled.
        // Starting the media service guarantees the MediaSession exists so the
        // sender gets play/pause + now-playing status back. No-op off Android TV.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                castReceiver.loadRequests.collect { req ->
                    deepLinkTarget.value = when (req.kind) {
                        AerioCastReceiverController.Kind.LIVE ->
                            DeepLinkTarget.Channel(req.mediaId)
                        AerioCastReceiverController.Kind.VOD ->
                            DeepLinkTarget.Vod(req.mediaId)
                    }
                    runCatching { AerioMediaPlaybackService.startBackground(this@MainActivity) }
                }
            }
        }
        // GH #33 companion VOD/DVR: a paired phone asked this TV to play a movie /
        // episode / recording. Route through the same deep-link navigation the
        // cast loads use; the VodPlay/RecordingPlay targets AUTOPLAY (straight to
        // the VOD player, not the detail screen).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                companionHost.playRequests.collect { req ->
                    deepLinkTarget.value = when (req) {
                        is com.aeriotv.android.core.cast.companion.CompanionHostController.PlayRequest.Vod ->
                            DeepLinkTarget.VodPlay(req.videoId, req.isEpisode)
                        is com.aeriotv.android.core.cast.companion.CompanionHostController.PlayRequest.Recording ->
                            DeepLinkTarget.RecordingPlay(req.url, req.title)
                    }
                }
            }
        }
        // Cast Connect: the initial LAUNCH that started the activity as a receiver
        // arrives as the launch intent. Hand it to MediaManager before deep-link
        // parsing; if consumed, the load callback drives navigation above.
        val castLaunch = castReceiver.handleIntent(intent)
        // Audit task #47: parse the launching intent's data URI for a
        // aeriotv:// deep link. The Compose tree consumes deepLinkTarget
        // via a top-level effect, navigates, then clears it.
        if (!castLaunch) captureDeepLinkFrom(intent)
        setContent {
            val theme by appPreferences.selectedTheme.collectAsState(initial = AppTheme.Aerio)
            // DEFAULT MUST be Dark: the initial (pre-first-emission) value AND
            // the persisted-absence value both resolve to Dark, so an existing
            // install sees zero visual change on upgrade.
            val appearanceMode by appPreferences.appearanceMode.collectAsState(initial = AppearanceMode.Dark)
            val useCustomAccent by appPreferences.useCustomAccent.collectAsState(initial = false)
            val customAccentHex by appPreferences.customAccentHex.collectAsState(initial = "")
            val customAccent = if (useCustomAccent && customAccentHex.length == 6) {
                runCatching {
                    val n = customAccentHex.toLong(16)
                    androidx.compose.ui.graphics.Color(
                        red = ((n shr 16) and 0xFF).toInt(),
                        green = ((n shr 8) and 0xFF).toInt(),
                        blue = (n and 0xFF).toInt(),
                    )
                }.getOrNull()
            } else null
            AerioTVTheme(
                appTheme = theme,
                customAccent = customAccent,
                appearanceMode = appearanceMode,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NotificationPermissionGate()
                    SplashGate {
                        // Phase 165/167: PersistentMpvWindow lives as a
                        // SIBLING of NavHost inside an outer Box. The
                        // video SurfaceView is mounted ONCE at this scope
                        // and never changes parents -- only its modifier
                        // (Hidden / Fullscreen / Mini) flips.
                        //
                        // ORDER MATTERS: PersistentMpvWindow is declared
                        // FIRST so it draws at the BOTTOM of the Box's
                        // z-stack. NavHost (containing PlayerScreen's
                        // chrome overlay) is declared SECOND so its
                        // children draw ON TOP, occluding the
                        // PersistentMpvWindow's black backing wherever
                        // chrome controls are visible. Without this
                        // ordering, PersistentMpvWindow's
                        // fillMaxSize+black background paints over the
                        // chrome and the user only sees the SurfaceView
                        // punch-through (video) -- chrome IS in state
                        // but never reaches the pixels.
                        Box(modifier = Modifier.fillMaxSize()) {
                            // PersistentExoWindow is declared FIRST so it
                            // sits at the bottom of the z-stack. Fullscreen
                            // mode = NavHost (containing PlayerScreen
                            // chrome) paints OVER the video; Mini mode
                            // lifts via zIndex(1f) (Phase 175 fix). Live
                            // TV mounts here; VOD owns its own per-screen
                            // PlayerView; multiview owns per-tile
                            // PlayerViews.
                            PersistentExoWindow(
                                holder = exoHolder,
                                state = exoWindowState,
                            )
                            AerioTVNavHost(
                                initialUrl = initialUrl,
                                initialEpgUrl = initialEpgUrl,
                                initialApiKey = initialApiKey,
                                deepLinkTarget = deepLinkTarget.value,
                                onDeepLinkConsumed = { deepLinkTarget.value = null },
                            )
                            // Issue #16: long-press BACK on Android TV opens this
                            // confirm (onKeyLongPress flips showExitConfirm). Rendered
                            // as a sibling of the NavHost so it overlays every screen.
                            if (showExitConfirm.value) {
                                val exitGuard = rememberTvMenuGuard()
                                TvActionMenuDialog(
                                    title = "Would you like to close AerioTV?",
                                    guard = exitGuard,
                                    onDismiss = { showExitConfirm.value = false },
                                    actions = listOf(
                                        TvMenuAction(
                                            label = "Cancel",
                                            icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                            onClick = {},
                                        ),
                                        TvMenuAction(
                                            label = "Close AerioTV",
                                            icon = Icons.Outlined.Close,
                                            destructive = true,
                                            onClick = { finish() },
                                        ),
                                    ),
                                )
                            }
                            // GH #33 companion remote: while a phone is pairing, show
                            // its 6-digit code over everything on the TV. Clears itself
                            // when the phone pairs (the host nulls the code). Never
                            // non-null on phones (the host advertises on TV only).
                            val companionCode by companionHost.pairingCode.collectAsState()
                            companionCode?.let {
                                com.aeriotv.android.feature.cast.companion.CompanionPairingOverlay(it)
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** D-pad Right auto-repeat count that counts as a deliberate "hold" to
         *  close the corner mini-player. Mirrors the guide's
         *  HOLD_LEFT_ALL_PILL_REPEAT (Android starts auto-repeating ~400ms after
         *  the press, so 4 repeats is ~0.5s -- a hold, not a tap). */
        const val MINI_CLOSE_HOLD_REPEAT = 4

        /** How long (ms) a held D-pad Right stays pinned after it closed the mini
         *  player, so the still-held Right holds guide focus in place until the
         *  key is released. Backstop only -- the release event normally clears the
         *  pin first; this bounds a missed release. Mirrors the guide hold-Left
         *  pin's 2.5s safety timeout. */
        const val RIGHT_HOLD_PIN_MS = 2_500L
    }
}
