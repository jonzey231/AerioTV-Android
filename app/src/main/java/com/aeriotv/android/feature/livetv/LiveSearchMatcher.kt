package com.aeriotv.android.feature.livetv

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel

/**
 * Live TV per-tab search rules (Logan 2026-11-01), phones and tablets:
 * case-insensitive substring on the channel name, the channel number, and
 * the title or sub-title of a program on that channel that is airing now or
 * starts later inside the loaded guide window. Descriptions never match, so
 * results stay tight and the scan stays cheap.
 */
internal object LiveSearchMatcher {
    fun channelMatches(ch: M3UChannel, query: String): Boolean =
        ch.name.contains(query, ignoreCase = true) ||
            (ch.channelNumber?.contains(query, ignoreCase = true) == true)
}

/**
 * In-memory search index over the loaded EPG map: per guide key, the
 * programs in start order with their title and sub-title lowercased once.
 * Built off the main thread and memoized by map identity, so typing reuses
 * it until the guide map itself changes.
 */
internal class LiveProgramIndex private constructor(
    private val byKey: Map<String, List<Entry>>,
) {
    private class Entry(val text: String, val programme: EPGProgramme)

    /** The earliest program on [key] that has not ended by [nowMillis] and
     *  whose title or sub-title contains [query]; null when none. */
    fun firstMatch(key: String, query: String, nowMillis: Long): EPGProgramme? {
        val entries = byKey[key] ?: return null
        val q = query.lowercase()
        for (e in entries) {
            if (e.programme.endMillis <= nowMillis) continue
            if (e.text.contains(q)) return e.programme
        }
        return null
    }

    companion object {
        fun build(epg: Map<String, List<EPGProgramme>>): LiveProgramIndex {
            val out = HashMap<String, List<Entry>>(epg.size * 2)
            for ((key, list) in epg) {
                val entries = list.asSequence()
                    .filter { !it.isPlaceholder && it.title.isNotBlank() }
                    .sortedBy { it.startMillis }
                    .map { p ->
                        val sub = p.subTitle?.takeIf { it.isNotBlank() }
                        Entry(if (sub == null) p.title.lowercase() else p.title.lowercase() + "\n" + sub.lowercase(), p)
                    }
                    .toList()
                if (entries.isNotEmpty()) out[key] = entries
            }
            return LiveProgramIndex(out)
        }

        /** Memoized by map identity (see [GuideMemo]). */
        fun of(epg: Map<String, List<EPGProgramme>>): LiveProgramIndex =
            GuideMemo.get("liveProgramIndex", GuideMemo.Ref(epg)) { build(epg) }
    }
}

/** Debounce before a Live TV search pass (spec: at most 150 ms). */
internal const val LIVE_SEARCH_DEBOUNCE_MS = 120L

/**
 * The "why it matched" line for a program-only match: the title (plus the
 * sub-title when present) and when it airs. "On now" for the airing program,
 * "Today 8:20 PM" / "Tomorrow 7:00 AM" / "Sat 9:00 PM" for upcoming ones.
 */
internal fun searchMatchLine(p: EPGProgramme, nowMillis: Long = System.currentTimeMillis()): String {
    val sub = p.subTitle?.takeIf { it.isNotBlank() && !it.equals(p.title, ignoreCase = true) }
    val title = if (sub == null) p.title else "${p.title}: $sub"
    return "$title · ${searchMatchWhen(p, nowMillis)}"
}

internal fun searchMatchWhen(p: EPGProgramme, nowMillis: Long): String {
    if (p.startMillis <= nowMillis) return "On now"
    val zone = java.time.ZoneId.systemDefault()
    val nowDay = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toEpochDay()
    val atDay = java.time.Instant.ofEpochMilli(p.startMillis).atZone(zone).toLocalDate().toEpochDay()
    val at = java.util.Date(p.startMillis)
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(at)
    val day = when (atDay - nowDay) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(at)
    }
    return "$day $time"
}

/** Program-only search matches for the guide rail, keyed by channel url. */
internal val LocalGuideSearchMatches =
    androidx.compose.runtime.staticCompositionLocalOf<Map<String, EPGProgramme>> { emptyMap() }
