package com.aeriotv.android.core.profiles

import kotlinx.serialization.Serializable

/**
 * App-defined profile avatar (Profiles 2c, 2026-10-26): one built-in icon
 * (or the initial) on one palette color, stored per profile and synced in
 * the Dispatcharr Sync `profiles` row (schema 4.12, `avatar`). Dispatcharr's
 * own `avatar_config` is not used: it has no defined shape, the web UI never
 * reads it, and a non-admin cannot write its own (PATCH users/me/ drops it).
 *
 * Pure (no Android types): the ids are the wire values shared with the
 * Apple app, the UI maps them to vectors and colors.
 */
@Serializable
data class AvatarSpec(
    /** One of [AvatarCatalog.ICONS]; "initial" draws the display name's first letter. */
    val icon: String = AvatarCatalog.INITIAL,
    /** One of [AvatarCatalog.COLORS]; "" = the username's default color. */
    val color: String = "",
)

object AvatarCatalog {
    const val INITIAL = "initial"

    /** 12 built-in icons, in picker order: people, animals, objects. */
    val ICONS: List<String> = listOf(
        "person", "face", "child",
        "paw", "cat", "fish",
        "star", "rocket", "music", "soccer", "gamepad", "movie",
    )

    /** Picker order: the initial first, then the 12 icons. */
    val PICKER_ICONS: List<String> = listOf(INITIAL) + ICONS

    /** 8 colors from the theme palette, in picker order (ARGB in the UI). */
    val COLORS: List<String> = listOf("teal", "blue", "orange", "ember", "green", "purple", "slate", "sea")

    /** Stable default color: sum of the lowercased username's chars, mod 8. */
    fun defaultColorIndex(username: String): Int =
        username.trim().lowercase().sumOf { it.code }.mod(COLORS.size)

    fun defaultColor(username: String): String = COLORS[defaultColorIndex(username)]

    /** The color id to draw for [spec] on [username]'s profile. */
    fun colorFor(spec: AvatarSpec?, username: String): String =
        spec?.color?.takeIf { it in COLORS } ?: defaultColor(username)

    /** The icon id to draw ("initial" when unset or unknown). */
    fun iconFor(spec: AvatarSpec?): String = spec?.icon?.takeIf { it in ICONS } ?: INITIAL

    /**
     * Letter and icon color on each avatar color (owner pick 2026-11-11, the
     * same as Apple): #0B1220 on the four lighter colors, white on the rest,
     * in light and dark mode and on every form factor. A fixed set, not a
     * luminance rule: no color here measures above 0.45, so the old rule drew
     * white on all eight.
     */
    val DARK_INK_COLORS: Set<String> = setOf("teal", "orange", "green", "slate")
    const val DARK_INK: Long = 0xFF0B1220
    const val LIGHT_INK: Long = 0xFFFFFFFF

    /** ARGB ink for the avatar color id [colorId]. */
    fun inkArgb(colorId: String): Long = if (colorId in DARK_INK_COLORS) DARK_INK else LIGHT_INK

    /**
     * The letter an initial avatar draws, the same on every platform: the
     * trimmed display name, else the trimmed username, else "?"; its first
     * character (a whole grapheme, so an accent stays with its letter),
     * uppercased.
     */
    fun initial(name: String, username: String): String {
        val source = name.trim().ifEmpty { username.trim() }
        if (source.isEmpty()) return "?"
        val chars = java.text.BreakIterator.getCharacterInstance()
        chars.setText(source)
        val end = chars.next().takeIf { it != java.text.BreakIterator.DONE } ?: source.length
        return source.substring(0, end).uppercase()
    }

    /** Normalize a pulled or picked spec: unknown ids fall back; all default = null. */
    fun normalize(spec: AvatarSpec?): AvatarSpec? {
        if (spec == null) return null
        val icon = spec.icon.takeIf { it in ICONS } ?: INITIAL
        val color = spec.color.takeIf { it in COLORS } ?: ""
        return if (icon == INITIAL && color.isEmpty()) null else AvatarSpec(icon, color)
    }
}
