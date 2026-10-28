package com.aeriotv.android.core.sync.dsync

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Read-side validation of a pulled `settings.v` (docs/DispatcharrSyncSchema.md,
 * settings values table): an unknown value, an out-of-range number, or a value
 * of the wrong type is ignored and the local setting stays unchanged. Pure, so
 * it is unit tested.
 */
object DsyncSettingsValidator {

    private val ENUMS: Map<String, Set<String>> = mapOf(
        "selectedTheme" to setOf("Aerio", "Midnight", "Sunset", "Forest", "Lavender", "Monochrome", "Light"),
        "appearanceMode" to setOf("dark", "light", "system"),
        "defaultTab" to setOf("", "LiveTV", "DVR", "Movies", "TVShows", "Settings"),
        "timeFormat" to setOf("system", "12", "24"),
        "guideGroupSelector" to setOf("pills", "sidebar"),
        "phoneGroupSelector" to setOf("pills", "sidebar"),
        "guideSidebarLayout" to setOf("overlay", "shift"),
        "guideProgramInfoButton" to setOf("off", "current", "all"),
        "guideLayout" to setOf("basic", "preview"),
        "brightnessEdge" to setOf("left", "right"),
        "dvrDefaultDestination" to setOf("server", "local"),
    )

    private val RANGES: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
        "textScale" to 0.85..1.50,
        "subtextScale" to 0.85..1.50,
        "textContrast" to 0.0..1.0,
        "displayScaleLiveTv" to 0.85..1.50,
        "displayScaleMovies" to 0.85..1.50,
        "displayScaleGuide" to 0.5..2.0,
    )

    /** Whole numbers: an allowed set or an inclusive range. */
    private val INTS: Map<String, (Int) -> Boolean> = mapOf(
        "skipBack" to { it in SKIP_CHOICES },
        "skipForward" to { it in SKIP_CHOICES },
        "channelLogoSize" to { it in 25..250 },
        "dvrPreRollMins" to { it in 0..240 },
        "dvrPostRollMins" to { it in 0..240 },
        "vodLibraryRefreshHours" to { it in 0..720 },
        "liveRewindDepthMinutes" to { it in 1..1440 },
        "liveRewindKeepCount" to { it in 1..5 },
        "liveRewindRetentionHours" to { it in 1..720 },
        "liveRewindBudgetGb" to { it in 1..500 },
        "multiview.logoSize" to { it in 5..25 },
    )

    /** Same list as core.ui.SkipIntervals.CHOICES (kept here so this stays pure). */
    val SKIP_CHOICES = listOf(5, 10, 15, 30, 60)

    val BOOLEANS: Set<String> = setOf(
        "trueBlack", "useCustomAccent", "programInfoButtonGuide", "programInfoButtonList", "showEpgBadgesTv",
        "showEpgBadgesMobile", "showRemoteHints", "guideTuneInMini", "playerInfoCard.showOnChannelChange",
        "liveTvEdgeSwipeOpensSidebar", "skipLoadingScreen", "appleTVChannelFlip", "enableCategoryColors",
        "tmdbPostersEnabled", "autoResumeLastChannel", "logoCornersList", "logoCornersGuide", "logoCornersGuideCells",
        "channelListShowLogos", "channelListShowNumbers", "channelListShowNames", "channelListShowProgramSubtitles",
        "skipWithoutControls", "autoRecoverFrozenStreams", "edgeBrightnessGesture", "edgeVolumeGesture",
        "liveRewindEnabled", "liveRewindKeepRecent", "multiview.tilePadding", "multiview.tileCornersRounded",
        "multiview.showLogos", "multiview.pickerShowSubtitles", "multiview.pickerShowDescriptions",
        "recentGroupVisible",
    ) + com.aeriotv.android.core.sync.INFO_CARD_ROWS.map { "playerInfoCard.$it" }

    /** Free strings with a max length (any value). */
    private val STRINGS: Map<String, Int> = mapOf(
        "defaultGroup" to 200, "lastGroup" to 200, "customCategories" to 4096, "hiddenEpgBadges" to 1024,
        "defaultLiveTvView" to 20, "moviesSortOrder" to 40, "seriesSortOrder" to 40, "dvrSortOrder" to 40,
        "multiview.audioFocusStyle" to 40, "multiview.logoPosition" to 40,
    )

    /** "RRGGBB" with an optional leading "#"; customAccentHex also takes "" (preset accent). */
    private val HEX = Regex("^#?[0-9A-Fa-f]{6}$")
    const val COLOR_PREFIX = "categoryColor."
    const val BUCKET_PREFIX = "categoryBucketEnabled."
    private val SUFFIX = Regex("^[A-Za-z0-9_]{1,40}$")

    /** Every schema name Android owns (prefix families excluded; see [isOwned]). */
    val NAMES: Set<String> = ENUMS.keys + RANGES.keys + INTS.keys + BOOLEANS + STRINGS.keys + "customAccentHex"

    fun isOwned(name: String): Boolean =
        name in NAMES ||
            (name.startsWith(COLOR_PREFIX) && SUFFIX.matches(name.removePrefix(COLOR_PREFIX))) ||
            (name.startsWith(BUCKET_PREFIX) && SUFFIX.matches(name.removePrefix(BUCKET_PREFIX)))

    /** True when [value] is acceptable for [name]. Unknown names are not accepted. */
    fun accepts(name: String, value: JsonPrimitive): Boolean {
        if (name == "customAccentHex") return value.isString && (value.content.isEmpty() || HEX.matches(value.content))
        if (name.startsWith(COLOR_PREFIX) && isOwned(name)) return value.isString && HEX.matches(value.content)
        if (name.startsWith(BUCKET_PREFIX) && isOwned(name)) return !value.isString && value.booleanOrNull != null
        STRINGS[name]?.let { max -> return value.isString && value.content.length <= max }
        INTS[name]?.let { ok ->
            if (value.isString) return false
            val i = value.content.toIntOrNull() ?: return false
            return ok(i)
        }
        ENUMS[name]?.let { allowed -> return value.isString && value.content in allowed }
        RANGES[name]?.let { r ->
            if (value.isString) return false
            val d = value.doubleOrNull ?: return false
            return !d.isNaN() && d in r
        }
        if (name in BOOLEANS) return !value.isString && value.booleanOrNull != null
        return false
    }

    data class Result(val accepted: Map<String, JsonPrimitive>, val ignored: List<String>)

    /** Splits [v] into the values to apply and "<key>=<value>" lines to log. */
    fun filter(v: Map<String, JsonPrimitive>): Result {
        val ok = LinkedHashMap<String, JsonPrimitive>()
        val bad = ArrayList<String>()
        v.forEach { (k, e) -> if (accepts(k, e)) ok[k] = e else bad += "$k=$e" }
        return Result(ok, bad)
    }
}
