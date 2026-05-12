package com.aeriotv.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalAppTheme = staticCompositionLocalOf { AppTheme.Aerio }

@Composable
fun AerioTVTheme(
    appTheme: AppTheme = AppTheme.Aerio,
    customAccent: Color? = null,
    content: @Composable () -> Unit,
) {
    // iOS ThemeManager.useCustomAccent parity: when the user enables a custom
    // accent in Appearance, replace the preset's primary with their hex. Falls
    // back to the preset's own accent when [customAccent] is null.
    val effectivePrimary = customAccent ?: appTheme.accentPrimary
    val colorScheme = darkColorScheme(
        primary = effectivePrimary,
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
            content = content,
        )
    }
}
