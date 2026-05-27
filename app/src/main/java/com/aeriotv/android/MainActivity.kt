package com.aeriotv.android

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aeriotv.android.core.pip.enterPip16x9
import com.aeriotv.android.core.playback.PlaybackService
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.aeriotv.android.core.pip.PipState
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.system.NotificationPermissionGate
import com.aeriotv.android.feature.splash.SplashGate
import com.aeriotv.android.ui.theme.AerioTVTheme
import com.aeriotv.android.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appPreferences: AppPreferences

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
                        AerioTVNavHost(
                            initialUrl = initialUrl,
                            initialEpgUrl = initialEpgUrl,
                            initialApiKey = initialApiKey,
                        )
                    }
                }
            }
        }
    }
}
