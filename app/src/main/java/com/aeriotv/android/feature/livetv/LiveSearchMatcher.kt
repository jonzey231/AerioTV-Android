package com.aeriotv.android.feature.livetv

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.guideMatchKey

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

    /**
     * Lowercased titles that only repeat the channel's identity: the channel
     * name and its call sign (the tvg-id before the first dot, "ESPN2.us"
     * gives "espn2"). Guides fill unlisted hours with entries titled like
     * that, and a channel-name query would otherwise list them for every
     * hour of the week (tvOS review 2026-11-05, Apple channelTitleKeys).
     */
    fun channelTitleKeys(name: String, tvgId: String?): Set<String> {
        val out = HashSet<String>(2)
        name.trim().lowercase().takeIf { it.isNotEmpty() }?.let { out += it }
        tvgId?.trim()?.takeIf { it.isNotEmpty() }
            ?.substringBefore('.')?.lowercase()?.takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    /** True when [title] is only the channel's name or call sign. */
    fun isChannelTitle(title: String, keys: Set<String>): Boolean =
        title.trim().lowercase() in keys
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
    private class Entry(val text: String, val title: String, val programme: EPGProgramme)

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

    /**
     * Live TV search "Programs" section (Logan 2026-11-04, Apple
     * LiveProgramIndex.programHits): every program on a channel in
     * [channels] (playlist order) whose TITLE contains [query], from now
     * forward (not ended by [nowMillis]), by start time then channel
     * position, one row per channel and start, capped at [cap].
     */
    fun programHits(
        query: String,
        nowMillis: Long,
        channels: List<M3UChannel>,
        cap: Int = LiveProgramHit.CAP,
    ): LiveProgramHits {
        val q = query.trim().lowercase()
        if (q.isEmpty() || cap <= 0) return LiveProgramHits(emptyList(), false)
        // A guide key shared by several rows resolves to the first one.
        val chByKey = HashMap<String, Pair<Int, M3UChannel>>(channels.size * 2)
        channels.forEachIndexed { i, ch -> chByKey.putIfAbsent(ch.guideMatchKey, i to ch) }
        val found = ArrayList<Pair<Int, LiveProgramHit>>()
        for ((key, entries) in byKey) {
            val (order, ch) = chByKey[key] ?: continue
            val own = LiveSearchMatcher.channelTitleKeys(ch.name, ch.tvgID)
            var lastStart: Long? = null
            for (e in entries) {
                if (e.programme.endMillis <= nowMillis) continue
                if (!e.title.contains(q)) continue
                if (e.title.trim() in own) continue
                if (e.programme.startMillis == lastStart) continue
                lastStart = e.programme.startMillis
                found += order to LiveProgramHit(ch, e.programme)
            }
        }
        found.sortWith(compareBy<Pair<Int, LiveProgramHit>> { it.second.programme.startMillis }.thenBy { it.first })
        return LiveProgramHits(found.take(cap).map { it.second }, found.size > cap)
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
                        Entry(if (sub == null) p.title.lowercase() else p.title.lowercase() + "\n" + sub.lowercase(), p.title.lowercase(), p)
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

/** One row of the Live TV search "Programs" section. */
internal class LiveProgramHit(val channel: M3UChannel, val programme: EPGProgramme) {
    val key: String get() = "hit-${channel.url}|${programme.startMillis}"
    fun isAiring(nowMillis: Long): Boolean =
        programme.startMillis <= nowMillis && nowMillis < programme.endMillis

    companion object {
        /** Rows shown at most; the footer says so when more matched. */
        const val CAP = 200
    }
}

internal class LiveProgramHits(val hits: List<LiveProgramHit>, val truncated: Boolean)

/** Fixed copy for the Live TV search sections (identical on Apple). */
internal object LiveSearchCopy {
    const val CHANNELS_HEADER = "Channels"
    const val PROGRAMS_HEADER = "Programs"
    const val NO_PROGRAMS = "No programs match"
    const val CAP_FOOTER = "Showing the first 200 matches"
}

/** "Today 8:20 PM" today (or already airing), "Sat 9:00 PM" on any later day. */
internal fun programHitWhen(p: EPGProgramme, nowMillis: Long = System.currentTimeMillis()): String {
    val zone = java.time.ZoneId.systemDefault()
    val nowDay = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val atDay = java.time.Instant.ofEpochMilli(p.startMillis).atZone(zone).toLocalDate()
    val at = java.util.Date(p.startMillis)
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(at)
    if (p.startMillis <= nowMillis || atDay == nowDay) return "Today $time"
    return java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(at) + " " + time
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
