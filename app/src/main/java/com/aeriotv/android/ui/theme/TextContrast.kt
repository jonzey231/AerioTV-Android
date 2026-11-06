package com.aeriotv.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Text Contrast (Appearance > Text Contrast, [AppPreferences.textContrast]).
 *
 * 0f = today's look, 1f = dimmed and accent-tinted TEXT becomes plain white
 * (dark mode) or plain black (light mode). Applied centrally in AerioTVTheme:
 *  - colorScheme.onSurfaceVariant (textSecondary) and colorScheme.tertiary
 *    (textTertiary) are blended, so every secondary text call site follows.
 *  - [textAccent] is the accent for TEXT. colorScheme.primary itself is left
 *    alone because it also paints fills, rings, progress bars and badges.
 *  - [forText] adjusts one-off dimmed text colors (onBackground.copy(alpha)).
 * Non-text uses of the blended tokens (outlines, dots, dividers) read
 * [LocalBaseTextColors] to keep the original look.
 */
val LocalTextContrast = compositionLocalOf { 0f }

/** Resolved dark/light decision of the active AerioTVTheme. */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/** The theme's secondary / tertiary / accent colors BEFORE Text Contrast. */
data class BaseTextColors(
    val secondary: Color,
    val tertiary: Color,
    val accent: Color,
)

val LocalBaseTextColors = staticCompositionLocalOf {
    BaseTextColors(Color.Unspecified, Color.Unspecified, Color.Unspecified)
}

/** Accent-colored text, blended by Text Contrast. */
internal val LocalTextAccent = staticCompositionLocalOf { Color.Unspecified }

/** Theme-tinted text (section headers), blended by Text Contrast. */
internal val LocalTextThemeTint = staticCompositionLocalOf { Color.Unspecified }

/** Blend [color] toward plain white / black ink by [amount] (alpha toward 1). */
fun contrastBlend(color: Color, amount: Float, isDark: Boolean): Color {
    if (amount <= 0f || color == Color.Unspecified) return color
    val a = amount.coerceIn(0f, 1f)
    val ink = if (isDark) Color.White else Color.Black
    return lerp(color.copy(alpha = 1f), ink, a)
        .copy(alpha = color.alpha + (1f - color.alpha) * a)
}

/**
 * Theme accent for TEXT (values, links, accent-tinted titles). Equals
 * colorScheme.primary at Text Contrast 0.
 */
val ColorScheme.textAccent: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalTextAccent.current.takeIf { it != Color.Unspecified } ?: primary

/**
 * Theme tint for TEXT (section headers): the theme's own accent, never the
 * custom accent override on the Custom theme (Apple ThemeManager.themeTint).
 */
val ColorScheme.textThemeTint: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalTextThemeTint.current.takeIf { it != Color.Unspecified } ?: textAccent

/** One-off dimmed text color (e.g. onBackground.copy(alpha = 0.7f)) with Text Contrast applied. */
@Composable
@ReadOnlyComposable
fun Color.forText(): Color = contrastBlend(this, LocalTextContrast.current, LocalIsDarkTheme.current)

/**
 * colorScheme.onSurfaceVariant WITHOUT Text Contrast, for non-text uses
 * (outlines, dots, dividers) that must keep the theme's look.
 */
val ColorScheme.decorSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalBaseTextColors.current.secondary.takeIf { it != Color.Unspecified } ?: onSurfaceVariant

/** colorScheme.tertiary WITHOUT Text Contrast, for non-text uses. */
val ColorScheme.decorTertiary: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalBaseTextColors.current.tertiary.takeIf { it != Color.Unspecified } ?: tertiary
