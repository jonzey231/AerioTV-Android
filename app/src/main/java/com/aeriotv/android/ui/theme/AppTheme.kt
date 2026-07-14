package com.aeriotv.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand theme presets mirroring iOS AerioTV's AppTheme enum.
 * User-selectable from Settings. Default is [Aerio] (cyan on navy).
 *
 * Each theme carries BOTH a dark and a light rendition. The dark rendition
 * ([appBackground]/[cardBackground]/[accentPrimary]) is the original palette
 * and is what every existing install sees. The light rendition
 * ([lightAppBackground]/[lightCardBackground]/[lightAccentPrimary]) is a
 * hand-authored counterpart used only when the orthogonal [AppearanceMode]
 * resolves to Light. Theme = hue/identity; mode = surface luminance.
 *
 * [lightAccentPrimary] exists because several presets' dark accents are far
 * too pale to read as text/tint on a white surface (Aerio's pale cyan,
 * Monochrome's near-white grey, Forest's bright green). The light rendition
 * uses a darkened accent that keeps the hue but earns real contrast on white.
 */
enum class AppTheme(
    val displayName: String,
    val accentPrimary: Color,
    val accentSecondary: Color,
    val appBackground: Color,
    val cardBackground: Color,
    // Light rendition (used only when AppearanceMode resolves to Light).
    val lightAppBackground: Color,
    val lightCardBackground: Color,
    val lightAccentPrimary: Color,
) {
    Aerio(
        displayName = "AerioTV",
        accentPrimary = Color(0xFF1AC4D8),
        accentSecondary = Color(0xFF1A8FA8),
        appBackground = Color(0xFF0A1628),
        cardBackground = Color(0xFF0D1E35),
        lightAppBackground = Color(0xFFF2F7FA),
        lightCardBackground = Color(0xFFFFFFFF),
        // Bright pale cyan is invisible as text on white -> darkened teal.
        lightAccentPrimary = Color(0xFF0E8A9C),
    ),
    Midnight(
        displayName = "Midnight",
        accentPrimary = Color(0xFF60A5FA),
        accentSecondary = Color(0xFF3B82F6),
        appBackground = Color(0xFF0A0F1A),
        cardBackground = Color(0xFF111827),
        lightAppBackground = Color(0xFFF4F6FB),
        lightCardBackground = Color(0xFFFFFFFF),
        lightAccentPrimary = Color(0xFF2563EB),
    ),
    Sunset(
        displayName = "Sunset",
        accentPrimary = Color(0xFFFB923C),
        accentSecondary = Color(0xFFF97316),
        appBackground = Color(0xFF0F0A07),
        cardBackground = Color(0xFF1A1108),
        lightAppBackground = Color(0xFFFCF7F2),
        lightCardBackground = Color(0xFFFFFFFF),
        lightAccentPrimary = Color(0xFFE0670C),
    ),
    Forest(
        displayName = "Forest",
        accentPrimary = Color(0xFF4ADE80),
        accentSecondary = Color(0xFF22C55E),
        appBackground = Color(0xFF080F0A),
        cardBackground = Color(0xFF0E1A10),
        lightAppBackground = Color(0xFFF3F8F4),
        lightCardBackground = Color(0xFFFFFFFF),
        lightAccentPrimary = Color(0xFF1B9E4B),
    ),
    Lavender(
        displayName = "Lavender",
        accentPrimary = Color(0xFFA78BFA),
        accentSecondary = Color(0xFF8B5CF6),
        appBackground = Color(0xFF0C0A12),
        cardBackground = Color(0xFF130F1E),
        lightAppBackground = Color(0xFFF7F5FC),
        lightCardBackground = Color(0xFFFFFFFF),
        lightAccentPrimary = Color(0xFF7C3AED),
    ),
    Monochrome(
        displayName = "Monochrome",
        accentPrimary = Color(0xFFE2E8F0),
        accentSecondary = Color(0xFF94A3B8),
        appBackground = Color(0xFF0A0A0A),
        cardBackground = Color(0xFF111111),
        lightAppBackground = Color(0xFFF5F5F5),
        lightCardBackground = Color(0xFFFFFFFF),
        // Near-white pale grey is invisible on white -> dark slate ink.
        lightAccentPrimary = Color(0xFF334155),
    ),

    /**
     * Standalone neutral "Light" theme. A low-chroma teal-grey accent that
     * reads on white. Orthogonal to [AppearanceMode]: picking this theme does
     * NOT flip the mode. It ships a dark rendition too so it stays consistent
     * when the user's mode is Dark/System-dark.
     */
    Light(
        displayName = "Light",
        // Dark rendition: a lighter teal reads on the near-black ground.
        accentPrimary = Color(0xFF4FB3C2),
        accentSecondary = Color(0xFF3C8A96),
        appBackground = Color(0xFF0E1416),
        cardBackground = Color(0xFF161D20),
        lightAppBackground = Color(0xFFF6F8FA),
        lightCardBackground = Color(0xFFFFFFFF),
        lightAccentPrimary = Color(0xFF2B7A86),
    ),
}

/**
 * Appearance mode axis, ORTHOGONAL to [AppTheme]. Theme picks the hue; mode
 * picks the surface luminance. Default is [Dark] so every existing install
 * (no stored value) is visually unchanged on upgrade. Persisted + Drive-synced
 * as the raw [wire] string so the choice follows the user across all devices.
 */
enum class AppearanceMode(val wire: String) {
    Dark("dark"),
    Light("light"),
    System("system"),
    ;

    companion object {
        /** Resolve a stored wire value; unknown/absent -> [Dark] (the default). */
        fun fromWire(raw: String?): AppearanceMode =
            entries.firstOrNull { it.wire == raw } ?: Dark
    }
}
