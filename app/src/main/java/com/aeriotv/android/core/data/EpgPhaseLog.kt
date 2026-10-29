package com.aeriotv.android.core.data

/**
 * One "[EPG] phase=..." line per guide data phase on the AerioTV tag, so a
 * device log proves which phase put rows on screen and whether those rows
 * carried descriptions. Counts only: no titles or descriptions are logged.
 */
object EpgPhaseLog {
    fun phase(name: String, programmes: Collection<EPGProgramme>, extra: String = "") {
        var empty = 0
        for (p in programmes) if (!p.isPlaceholder && p.description.isBlank()) empty++
        android.util.Log.i(
            "AerioTV",
            "[EPG] phase=$name programmes=${programmes.size} emptyDesc=$empty" +
                if (extra.isEmpty()) "" else " $extra",
        )
    }

    /** The Live TV list (and guide) republished from a new EPG snapshot. */
    fun republish(reason: String, catalog: Map<String, List<EPGProgramme>>, quick: Boolean) {
        var total = 0
        var empty = 0
        for (cells in catalog.values) for (p in cells) {
            if (p.isPlaceholder) continue
            total++
            if (p.description.isBlank()) empty++
        }
        android.util.Log.i(
            "AerioTV",
            "[EPG] list republish reason=$reason channels=${catalog.size} programmes=$total " +
                "emptyDesc=$empty quick=$quick",
        )
    }
}
