package com.aeriotv.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand theme presets mirroring iOS AerioTV's AppTheme enum.
 * User-selectable from Settings. Default is [Aerio] (cyan on navy).
 */
enum class AppTheme(
    val displayName: String,
    val accentPrimary: Color,
    val accentSecondary: Color,
    val appBackground: Color,
    val cardBackground: Color,
) {
    Aerio(
        displayName = "AerioTV",
        accentPrimary = Color(0xFF1AC4D8),
        accentSecondary = Color(0xFF1A8FA8),
        appBackground = Color(0xFF0A1628),
        cardBackground = Color(0xFF0D1E35),
    ),
    Midnight(
        displayName = "Midnight",
        accentPrimary = Color(0xFF60A5FA),
        accentSecondary = Color(0xFF3B82F6),
        appBackground = Color(0xFF0A0F1A),
        cardBackground = Color(0xFF111827),
    ),
    Sunset(
        displayName = "Sunset",
        accentPrimary = Color(0xFFFB923C),
        accentSecondary = Color(0xFFF97316),
        appBackground = Color(0xFF0F0A07),
        cardBackground = Color(0xFF1A1108),
    ),
    Forest(
        displayName = "Forest",
        accentPrimary = Color(0xFF4ADE80),
        accentSecondary = Color(0xFF22C55E),
        appBackground = Color(0xFF080F0A),
        cardBackground = Color(0xFF0E1A10),
    ),
    Lavender(
        displayName = "Lavender",
        accentPrimary = Color(0xFFA78BFA),
        accentSecondary = Color(0xFF8B5CF6),
        appBackground = Color(0xFF0C0A12),
        cardBackground = Color(0xFF130F1E),
    ),
    Monochrome(
        displayName = "Monochrome",
        accentPrimary = Color(0xFFE2E8F0),
        accentSecondary = Color(0xFF94A3B8),
        appBackground = Color(0xFF0A0A0A),
        cardBackground = Color(0xFF111111),
    ),
}
