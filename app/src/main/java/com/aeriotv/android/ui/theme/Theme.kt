package com.aeriotv.android.ui.theme

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalAppTheme = staticCompositionLocalOf { AppTheme.Aerio }

@Composable
fun AerioTVTheme(
    appTheme: AppTheme = AppTheme.Aerio,
    customAccent: Color? = null,
    // Orthogonal appearance axis. Defaults to Dark so any caller that has not
    // yet threaded the preference (and every existing install) is unchanged.
    appearanceMode: AppearanceMode = AppearanceMode.Dark,
    content: @Composable () -> Unit,
) {
    // Resolve the mode axis to a concrete light/dark decision. Only System
    // consults the OS setting; Dark/Light are explicit overrides. The DEFAULT
    // (AppearanceMode.Dark) resolves to isDark = true, so a fresh install with
    // no stored preference is byte-for-byte the original dark theme.
    val isDark = when (appearanceMode) {
        AppearanceMode.Dark -> true
        AppearanceMode.Light -> false
        AppearanceMode.System -> isSystemInDarkTheme()
    }

    // iOS ThemeManager.useCustomAccent parity: when the user enables a custom
    // accent in Appearance, replace the preset's primary with their hex. Falls
    // back to the preset's own accent when [customAccent] is null. In light mode
    // the preset uses its darkened light accent (the bright dark-mode accents
    // wash out on white); a user-chosen custom accent still wins in both modes.
    val baseAccent = if (isDark) appTheme.accentPrimary else appTheme.lightAccentPrimary
    val effectivePrimary = customAccent ?: baseAccent

    // Surface + ink selection per mode. The dark branch keeps the ORIGINAL
    // values verbatim; the light branch reads the theme's light rendition.
    val appBackground = if (isDark) appTheme.appBackground else appTheme.lightAppBackground
    val cardBackground = if (isDark) appTheme.cardBackground else appTheme.lightCardBackground
    val textPrimary = if (isDark) TextPrimary else TextPrimaryLight

    // Android TV needs a 10-foot type scale + slightly brighter dim text. iOS
    // keeps a separate ~1.5x tvOS Typography and pushes secondary text opacity
    // to 0.75 (vs 0.65 on phone) for legibility at distance (Colors.swift).
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    // Mirrors iOS Colors.swift textSecondary = accent.opacity(0.65 phone / 0.75
    // tvOS). Every secondary copy (subtitles, card descriptions, info-banner
    // body, hint text) renders accent-tinted instead of plain white, which is
    // what gives the iOS Welcome / Add Playlist / Configure screens their soft
    // branded feel. Mapped through Material3 onSurfaceVariant so every call site
    // (`MaterialTheme.colorScheme.onSurfaceVariant`) picks it up.
    //
    // On a WHITE surface an accent-at-0.65 composited over white turns into a
    // washed pastel, so in light mode we both pull the tint toward the dark ink
    // AND raise the alpha to earn real contrast (the accent is already the
    // darkened light-mode accent here).
    val textSecondary = if (isDark) {
        effectivePrimary.copy(alpha = if (isTv) 0.75f else 0.65f)
    } else {
        lerp(effectivePrimary, textPrimary, 0.20f).copy(alpha = 0.90f)
    }
    // Visual-parity polish: iOS has a SECOND, dimmer rung under textSecondary.
    // Colors.swift textTertiary = accent.opacity(0.45 tvOS / 0.28 phone), used
    // for channel numbers, time ranges, and hints so they recede behind the
    // title/description hierarchy. Carried on Material3's otherwise-unused
    // `tertiary` slot; reach it via `MaterialTheme.colorScheme.tertiary`.
    val textTertiary = if (isDark) {
        effectivePrimary.copy(alpha = if (isTv) 0.45f else 0.28f)
    } else {
        lerp(effectivePrimary, textPrimary, 0.15f).copy(alpha = 0.65f)
    }

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = effectivePrimary,
            onPrimary = appBackground,
            secondary = appTheme.accentSecondary,
            onSecondary = textPrimary,
            tertiary = textTertiary,
            onTertiary = appBackground,
            background = appBackground,
            onBackground = textPrimary,
            surface = cardBackground,
            onSurface = textPrimary,
            surfaceVariant = cardBackground,
            onSurfaceVariant = textSecondary,
            error = StatusLive,
            onError = TextPrimary,
        )
    } else {
        lightColorScheme(
            primary = effectivePrimary,
            // On a mid-tone/dark light-accent fill, near-white reads best.
            onPrimary = Color.White,
            secondary = appTheme.accentSecondary,
            onSecondary = Color.White,
            tertiary = textTertiary,
            onTertiary = Color.White,
            background = appBackground,
            onBackground = textPrimary,
            surface = cardBackground,
            onSurface = textPrimary,
            surfaceVariant = cardBackground,
            onSurfaceVariant = textSecondary,
            error = StatusLive,
            onError = Color.White,
        )
    }

    // Match the system-bar icon contrast to the resolved mode so the status /
    // navigation bar glyphs stay legible: dark icons over a light background,
    // light icons over a dark one. Player chrome hides the bars entirely, so
    // this never fights the always-dark video scrims.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(LocalAppTheme provides appTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = aerioTypography(isTv),
            content = content,
        )
    }
}
