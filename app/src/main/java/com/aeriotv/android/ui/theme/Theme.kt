package com.aeriotv.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalAppTheme = staticCompositionLocalOf { AppTheme.Aerio }

@Composable
fun AerioTVTheme(
    appTheme: AppTheme = AppTheme.Aerio,
    content: @Composable () -> Unit
) {
    val colorScheme = darkColorScheme(
        primary = appTheme.accentPrimary,
        onPrimary = appTheme.appBackground,
        secondary = appTheme.accentSecondary,
        onSecondary = TextPrimary,
        background = appTheme.appBackground,
        onBackground = TextPrimary,
        surface = appTheme.cardBackground,
        onSurface = TextPrimary,
        surfaceVariant = appTheme.cardBackground,
        onSurfaceVariant = TextPrimary,
        error = StatusLive,
        onError = TextPrimary,
    )

    CompositionLocalProvider(LocalAppTheme provides appTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
