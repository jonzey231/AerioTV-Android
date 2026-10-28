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
    )

    private val RANGES: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
        "textScale" to 0.85..1.50,
        "subtextScale" to 0.85..1.50,
        "textContrast" to 0.0..1.0,
    )

    val BOOLEANS: Set<String> = setOf(
        "trueBlack", "programInfoButtonGuide", "programInfoButtonList", "showEpgBadgesTv",
        "showRemoteHints", "guideTuneInMini", "playerInfoCard.showOnChannelChange",
        "liveTvEdgeSwipeOpensSidebar", "skipLoadingScreen", "appleTVChannelFlip",
    )

    /** Free strings with a max length (any value). */
    private val STRINGS: Map<String, Int> = mapOf("defaultGroup" to 200)

    /** True when [value] is acceptable for [name]. Unknown names are not accepted. */
    fun accepts(name: String, value: JsonPrimitive): Boolean {
        STRINGS[name]?.let { max -> return value.isString && value.content.length <= max }
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
