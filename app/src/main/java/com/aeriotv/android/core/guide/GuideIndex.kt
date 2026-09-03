package com.aeriotv.android.core.guide

import com.aeriotv.android.core.data.EPGProgramme

/**
 * One channel's schedule, sorted by start, queried by time. This is what the
 * grid renderer and the now-playing rails read; nothing groups or sorts
 * programme lists on the main thread.
 *
 * Holes are synthesised on demand for a window ([withHoles]) and never
 * persisted: they exist so the focus model can land on them, and they carry
 * [EPGProgramme.isPlaceholder] so the renderer draws them as "No info".
 */
class GuideIndex private constructor(
    val channelId: GuideChannelId,
    /** Start-sorted, non-overlapping after [normalize]. */
    val cells: List<EPGProgramme>,
) {
    private val starts: LongArray = LongArray(cells.size) { cells[it].startMillis }

    /** Index of the cell containing [t], or -1. */
    fun indexAt(t: Long): Int {
        var lo = 0
        var hi = cells.size - 1
        var candidate = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= t) { candidate = mid; lo = mid + 1 } else hi = mid - 1
        }
        return if (candidate >= 0 && cells[candidate].endMillis > t) candidate else -1
    }

    fun cellAt(t: Long): EPGProgramme? = indexAt(t).takeIf { it >= 0 }?.let { cells[it] }

    /** Index of the first cell starting at or after [t], or cells.size. */
    fun firstIndexFrom(t: Long): Int {
        var lo = 0
        var hi = cells.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** The cells that intersect [fromMs, toMs), in order. */
    fun slice(fromMs: Long, toMs: Long): List<EPGProgramme> {
        if (cells.isEmpty() || toMs <= fromMs) return emptyList()
        var i = firstIndexFrom(fromMs)
        if (i > 0 && cells[i - 1].endMillis > fromMs) i--
        val out = ArrayList<EPGProgramme>()
        while (i < cells.size && cells[i].startMillis < toMs) { out.add(cells[i]); i++ }
        return out
    }

    /**
     * The same window with every hole of at least [minGapMs] filled by a
     * focusable placeholder cell. Leading and trailing holes are included so
     * a row with one programme at 6pm still has something to land on at 4pm.
     */
    fun withHoles(fromMs: Long, toMs: Long, minGapMs: Long = 5 * 60_000L, title: String = "No info"): List<EPGProgramme> {
        val real = slice(fromMs, toMs)
        val out = ArrayList<EPGProgramme>(real.size + 4)
        var cursor = fromMs
        fun hole(a: Long, b: Long) {
            if (b - a >= minGapMs) out.add(placeholder(channelId, title, a, b))
        }
        for (p in real) {
            if (p.startMillis > cursor) hole(cursor, p.startMillis)
            out.add(p)
            if (p.endMillis > cursor) cursor = p.endMillis
        }
        if (cursor < toMs) hole(cursor, toMs)
        return out
    }

    companion object {
        /**
         * Cells for a channel with NO guide data: the channel name stands in
         * for the programme. When the name carries an event time (Xtream event
         * feeds, see [EventTimeParser]) the row splits into a lead-in that says
         * when it starts, the event block itself, and the tail after it.
         */
        fun nameCells(
            channelId: GuideChannelId,
            name: String,
            windowStartMs: Long,
            windowEndMs: Long,
            nowMs: Long = System.currentTimeMillis(),
        ): List<EPGProgramme> {
            val start = EventTimeParser.parse(name, nowMs)
            if (start == null || start >= windowEndMs || start + EVENT_BLOCK_MS <= windowStartMs) {
                return listOf(placeholder(channelId, name, windowStartMs, windowEndMs))
            }
            val end = start + EVENT_BLOCK_MS
            val out = ArrayList<EPGProgramme>(3)
            val when_ = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault()).format(java.util.Date(start))
            if (start > windowStartMs) out += placeholder(channelId, name, windowStartMs, start).copy(description = "Starts $when_")
            out += placeholder(channelId, name, maxOf(start, windowStartMs), minOf(end, windowEndMs)).copy(description = "Event listing from the channel name; the provider has no guide data for it.")
            if (end < windowEndMs) out += placeholder(channelId, name, end, windowEndMs).copy(description = "Started $when_")
            return out
        }

        private const val EVENT_BLOCK_MS = 3 * 3_600_000L

        fun placeholder(channelId: GuideChannelId, title: String, startMs: Long, endMs: Long) = EPGProgramme(
            channelId = channelId.value,
            title = title,
            description = "",
            startMillis = startMs,
            endMillis = endMs,
            category = "",
            isPlaceholder = true,
        )

        /**
         * Build from resolved rows for one channel: sort by start, drop
         * zero-length rows, and resolve overlaps by keeping the earlier-
         * starting row and clipping the later one's start (a feed with a
         * one-minute overlap must not produce two cells under one time).
         */
        fun build(channelId: GuideChannelId, rows: List<EPGProgramme>): GuideIndex {
            val sorted = rows.asSequence()
                .filter { it.endMillis > it.startMillis }
                .sortedWith(compareBy({ it.startMillis }, { it.endMillis }))
                .toList()
            val out = ArrayList<EPGProgramme>(sorted.size)
            var lastEnd = Long.MIN_VALUE
            for (p in sorted) {
                if (p.startMillis >= lastEnd) {
                    out.add(p); lastEnd = p.endMillis
                } else if (p.endMillis > lastEnd) {
                    out.add(p.copy(startMillis = lastEnd)); lastEnd = p.endMillis
                } // else fully covered by the previous cell: dropped
            }
            return GuideIndex(channelId, out)
        }

        fun empty(channelId: GuideChannelId) = GuideIndex(channelId, emptyList())
    }
}
