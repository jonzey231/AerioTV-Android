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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aeriotv.android.core.pip.enterPip16x9
import com.aeriotv.android.core.playback.PlaybackService
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.aeriotv.android.core.pip.PipState
import com.aeriotv.android.core.playback.AerioExoPlayerHolder
import com.aeriotv.android.core.playback.MPVPlayerHolder
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.system.NotificationPermissionGate
import com.aeriotv.android.feature.miniplayer.MiniPlayerSession
import com.aeriotv.android.feature.player.ExoWindowState
import com.aeriotv.android.feature.player.MpvWindowState
import com.aeriotv.android.feature.player.PersistentExoWindow
import com.aeriotv.android.feature.player.PersistentMpvWindow
import com.aeriotv.android.feature.splash.SplashGate
import com.aeriotv.android.ui.theme.AerioTVTheme
import com.aeriotv.android.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var miniPlayerSession: MiniPlayerSession
    @Inject lateinit var mpvHolder: MPVPlayerHolder
    @Inject lateinit var mpvWindowState: MpvWindowState
    @Inject lateinit var exoHolder: AerioExoPlayerHolder
    @Inject lateinit var exoWindowState: ExoWindowState

    /**
     * Most recent deep-link target the activity has received from a
     * `aeriotv://channel/<id>` or `aeriotv://vod/<uuid>` Intent. Read by
     * the Compose tree via [DeepLinkTargetHolder] / a CompositionLocal
     * provider so NavHost can pop straight onto the target route once
     * the active playlist is ready. Drained (set null) after consumption
     * so a second tap on the same notification re-fires correctly.
     */
    private val deepLinkTarget = androidx.compose.runtime.mutableStateOf<DeepLinkTarget?>(null)

    /** Wall-clock timestamp of the last DPAD_CENTER press (uptimeMillis).
     *  Reset to 0 after a successful double-press detection so a fast triple
     *  doesn't trigger twice. */
    private var lastSelectPressMs = 0L

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
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            val now = SystemClock.uptimeMillis()
            val sinceLast = now - lastSelectPressMs
            lastSelectPressMs = now
            val miniActive = miniPlayerSession.state.value is MiniPlayerSession.State.Active
            Log.i(
                "MiniPlayerResume",
                "OK pressed: sinceLast=${sinceLast}ms miniActive=$miniActive " +
                    "threshold=${DOUBLE_PRESS_THRESHOLD_MS}ms",
            )
            // Audit task #22 + Phase 162 crash fix: while the mini is Active,
            // consume EVERY OK press, not just the second one. Otherwise the
            // first press falls through to Compose and clicks whatever's
            // focused in the guide (a programme cell, group chip, etc.),
            // navigating to PLAYER -- and then the second press fires the
            // resume which navigates to PLAYER *again*. Two NavController
            // navigations within ~350ms of each other against the same
            // singleTop route was producing the crash. Single OK while
            // mini-Active is now a deliberate no-op; user knows from the hint
            // chip that double-press is the resume gesture.
            if (miniActive) {
                if (sinceLast in 1L..DOUBLE_PRESS_THRESHOLD_MS) {
                    Log.i("MiniPlayerResume", "Double-press detected -> requestResume()")
                    miniPlayerSession.requestResume()
                    lastSelectPressMs = 0L
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.inPictureInPicture.value = isInPictureInPictureMode
    }

    override fun onResume() {
        super.onResume()
        // Re-pin on resume so a fold/unfold display switch (the cover and inner
        // panels expose different display-mode ids) keeps the highest rate.
        requestHighestRefreshRate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop means a second LAUNCH/aeriotv:// intent arrives here
        // instead of recreating the activity. Capture the URI for the
        // Compose tree.
        captureDeepLinkFrom(intent)
    }

    /**
     * Pull a deep-link target out of [intent.data] when the scheme is
     * `aeriotv`. Supported hosts: `channel`, `vod`. Path is the id /
     * uuid. Anything else is ignored.
     */
    private fun captureDeepLinkFrom(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.scheme.equals("aeriotv", ignoreCase = true)) return
        val host = data.host?.lowercase() ?: return
        val path = data.pathSegments?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        val target = when (host) {
            "channel" -> DeepLinkTarget.Channel(path)
            "vod" -> DeepLinkTarget.Vod(path)
            else -> null
        } ?: return
        deepLinkTarget.value = target
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        when {
            // Audio-only: never enter PiP. Keep a foreground media notification
            // alive so audio continues with status-bar + lock-screen controls.
            PipState.audioPlaybackActive.value -> {
                PlaybackService.startBackground(
                    this,
                    PipState.nowPlayingTitle,
                    PipState.nowPlayingSubtitle,
                    PipState.nowPlayingLogo,
                )
            }
            // Video on API < 31 has no setAutoEnterEnabled, so trigger PiP here.
            // API 31+ auto-enters via the params synced in syncAutoEnterPip.
            PipState.videoPlaybackActive.value &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> enterPip16x9()
        }
    }

    /**
     * Mirror [PipState.videoPlaybackActive] into the window's PiP params so the
     * system auto-enters Picture-in-Picture on leave (API 31+). No-op on older
     * versions (handled by onUserLeaveHint) and on devices without PiP.
     */
    private fun syncAutoEnterPip(videoActive: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(videoActive)
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
    private fun requestHighestRefreshRate() {
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
        // Audit task #47: parse the launching intent's data URI for a
        // aeriotv:// deep link. The Compose tree consumes deepLinkTarget
        // via a top-level effect, navigates, then clears it.
        captureDeepLinkFrom(intent)
        setContent {
            val theme by appPreferences.selectedTheme.collectAsState(initial = AppTheme.Aerio)
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
            AerioTVTheme(appTheme = theme, customAccent = customAccent) {
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
                            // sits at the bottom of the z-stack, same
                            // logic as PersistentMpvWindow re: NavHost
                            // chrome painting over it in Fullscreen mode
                            // and its own zIndex(1f) lifting it above
                            // the NavHost in Mini mode (Phase 175).
                            //
                            // Both player views live here side by side
                            // during the migration (task #61). Live TV
                            // routes through PersistentExoWindow, VOD +
                            // multiview still route through Persistent
                            // MpvWindow. Each side starts in Hidden mode
                            // so the inactive player consumes no layout
                            // space. Once tasks #62 / #63 land the
                            // MPV pair is removed entirely (task #67).
                            PersistentExoWindow(
                                holder = exoHolder,
                                state = exoWindowState,
                            )
                            PersistentMpvWindow(
                                mpvHolder = mpvHolder,
                                state = mpvWindowState,
                            )
                            AerioTVNavHost(
                                initialUrl = initialUrl,
                                initialEpgUrl = initialEpgUrl,
                                initialApiKey = initialApiKey,
                                deepLinkTarget = deepLinkTarget.value,
                                onDeepLinkConsumed = { deepLinkTarget.value = null },
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** Max gap between two D-pad Select presses to count as a double-press
         *  for the mini-player resume action. 350ms is a comfortable but not
         *  sluggish window that matches typical "double-tap" expectations on
         *  TV remotes. */
        const val DOUBLE_PRESS_THRESHOLD_MS = 350L
    }
}
