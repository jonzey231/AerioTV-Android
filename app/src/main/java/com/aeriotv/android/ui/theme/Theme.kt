package com.aeriotv.android.ui.theme

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration

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
    // Android TV needs a 10-foot type scale + slightly brighter dim text. iOS
    // keeps a separate ~1.5x tvOS Typography and pushes secondary text opacity
    // to 0.75 (vs 0.65 on phone) for legibility at distance (Colors.swift).
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    // Mirrors iOS Colors.swift textSecondary = accent.opacity(0.65 phone / 0.75
    // tvOS). Every secondary copy (subtitles, card descriptions, info-banner
    // body, hint text) renders cyan-tinted instead of plain white, which is what
    // gives the iOS Welcome / Add Playlist / Configure screens their soft
    // branded feel. Mapped through Material3 onSurfaceVariant so every call site
    // (`MaterialTheme.colorScheme.onSurfaceVariant`) picks it up.
    val textSecondary = effectivePrimary.copy(alpha = if (isTv) 0.75f else 0.65f)
    // Visual-parity polish: iOS has a SECOND, dimmer rung under textSecondary.
    // Colors.swift textTertiary = accent.opacity(0.45 tvOS / 0.28 phone), used
    // for channel numbers, time ranges, and hints so they recede behind the
    // title/description hierarchy. Android previously rendered all of those on
    // the single textSecondary rung, which is a big part of why the Apple apps
    // read "cleaner". Carried on Material3's otherwise-unused `tertiary` slot
    // (same pattern as textSecondary riding onSurfaceVariant above); reach it
    // via `MaterialTheme.colorScheme.tertiary`.
    val textTertiary = effectivePrimary.copy(alpha = if (isTv) 0.45f else 0.28f)
    val colorScheme = darkColorScheme(
        primary = effectivePrimary,
        onPrimary = appTheme.appBackground,
        secondary = appTheme.accentSecondary,
        onSecondary = TextPrimary,
        tertiary = textTertiary,
        onTertiary = appTheme.appBackground,
        background = appTheme.appBackground,
        onBackground = TextPrimary,
        surface = appTheme.cardBackground,
        onSurface = TextPrimary,
        surfaceVariant = appTheme.cardBackground,
        onSurfaceVariant = textSecondary,
        error = StatusLive,
        onError = TextPrimary,
    )

    CompositionLocalProvider(LocalAppTheme provides appTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = aerioTypography(isTv),
            content = content,
        )
    }
}
