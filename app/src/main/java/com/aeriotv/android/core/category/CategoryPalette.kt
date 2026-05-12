package com.aeriotv.android.core.category

import androidx.compose.ui.graphics.Color

/**
 * Immutable snapshot of the user's category-palette configuration. Built by
 * [com.aeriotv.android.core.preferences.AppPreferences] from DataStore and
 * collected as Compose state at the screen level. Each render computes a
 * Color via [tintFor], so any DataStore write triggers a recomposition.
 *
 * Mirrors iOS CategoryColor (Aerio/Shared/CategoryColor.swift). The static
 * UserDefaults reads on iOS are reactive because SwiftUI rebinds @AppStorage
 * keys; on Android we collect the snapshot once and re-resolve inline.
 */
data class CategoryPaletteState(
    val masterEnabled: Boolean,
    /** suffix → 6-char uppercase hex override (without leading '#'). */
    val overrides: Map<String, String>,
    /** suffix → user-toggled enable. Missing keys fall back to default-bucket membership. */
    val enabledFlags: Map<String, Boolean>,
    val custom: List<CustomCategoryEntry>,
) {

    fun hexFor(bucket: ProgramCategory): String =
        overrides[bucket.storageSuffix]?.takeIf { it.isNotBlank() } ?: bucket.defaultHex

    fun isBucketEnabled(bucket: ProgramCategory): Boolean =
        enabledFlags[bucket.storageSuffix] ?: ProgramCategory.defaultBuckets.contains(bucket)

    /**
     * Resolves a free-form XMLTV `<category>` string to a category-color.
     * Custom entries win over built-in buckets so a user override takes
     * priority. Returns null when nothing matches or master toggle is off.
     */
    fun resolveBaseColor(rawCategory: String?): Color? {
        if (!masterEnabled) return null
        if (rawCategory.isNullOrBlank()) return null

        // Custom entries first — substring match on lowercased haystack.
        val haystack = rawCategory.lowercase()
        custom.firstOrNull { entry ->
            val needle = entry.match.lowercase().trim()
            needle.isNotEmpty() && needle in haystack
        }?.let { return parseHex(it.hex) }

        // Built-in bucket resolution. Tokenize by separators then probe in priority order.
        val tokens = haystack
            .split(',', '/', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        for (token in tokens) {
            for (bucket in ProgramCategory.priorityOrder) {
                if (!isBucketEnabled(bucket)) continue
                if (bucket.aliases.any { alias -> alias in token }) {
                    return parseHex(hexFor(bucket))
                }
            }
        }
        return null
    }

    /**
     * Background tint for a programme cell or channel row. Opacity tiers mirror
     * iOS iOS-side weights (live=0.45, default=0.28). Focused isn't currently
     * piped through on Android — pre-press touch ripple covers that signal —
     * but the parameter is kept for parity with iOS callers if they port back.
     */
    fun tintFor(rawCategory: String?, isLive: Boolean, isFocused: Boolean = false): Color? {
        val base = resolveBaseColor(rawCategory) ?: return null
        val alpha = when {
            isFocused -> 0.55f
            isLive -> 0.45f
            else -> 0.28f
        }
        return base.copy(alpha = alpha)
    }

    companion object {
        const val MASTER_ENABLED_KEY: String = "enableCategoryColors"
        const val CUSTOM_KEY: String = "customCategoryColors.v1"

        val Default: CategoryPaletteState = CategoryPaletteState(
            masterEnabled = true,
            overrides = emptyMap(),
            enabledFlags = emptyMap(),
            custom = emptyList(),
        )
    }
}

/**
 * Parse a 6-char hex (with or without leading '#') into a Compose [Color].
 * Returns Color.Transparent on malformed input rather than throwing — the
 * Settings UI bounds-checks input but a corrupt persisted value shouldn't
 * crash the whole render.
 */
internal fun parseHex(raw: String): Color {
    val stripped = raw.trim().removePrefix("#")
    if (stripped.length != 6) return Color.Transparent
    val asLong = stripped.toLongOrNull(16) ?: return Color.Transparent
    val r = ((asLong shr 16) and 0xFF).toInt()
    val g = ((asLong shr 8) and 0xFF).toInt()
    val b = (asLong and 0xFF).toInt()
    return Color(red = r, green = g, blue = b)
}
