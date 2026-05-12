package com.aeriotv.android

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
