package com.aeriotv.android.feature.livetv.grid

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.core.guide.GuideCatalog
import com.aeriotv.android.core.guide.GuideChannelId
import com.aeriotv.android.core.guide.GuideIndex

/**
 * The rows of the guide grid for one displayed channel list: each row is a
 * channel plus its cells over the grid window, holes included, computed once
 * per row on first use and kept for the life of this object (a new channel
 * list or a new catalog makes a new [GuideGridRows]).
 *
 * Pure Kotlin so the focus model can be unit-tested without Compose.
 */
class GuideGridRows(
    val channels: List<M3UChannel>,
    private val catalog: GuideCatalog?,
    val windowStartMs: Long,
    val windowEndMs: Long,
    private val minGapMs: Long = 5 * 60_000L,
) {
    val size: Int get() = channels.size
    val isEmpty: Boolean get() = channels.isEmpty()

    private val cellCache = arrayOfNulls<List<EPGProgramme>>(channels.size)

    fun channel(row: Int): M3UChannel = channels[row]

    /** Start-sorted, gap-free cells covering [windowStartMs, windowEndMs). */
    fun cells(row: Int): List<EPGProgramme> {
        cellCache[row]?.let { return it }
        val ch = channels[row]
        val id = GuideChannelId(ch.guideMatchKey)
        val index = catalog?.index(id)
        val cells = if (index == null || index.cells.isEmpty()) {
            listOf(GuideIndex.placeholder(id, ch.name, windowStartMs, windowEndMs))
        } else {
            index.withHoles(windowStartMs, windowEndMs, minGapMs)
        }
        cellCache[row] = cells
        return cells
    }

    /** Index into [cells] of the cell containing [t], or -1. */
    fun cellIndexAt(row: Int, t: Long): Int {
        val cells = cells(row)
        var lo = 0
        var hi = cells.size - 1
        var candidate = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cells[mid].startMillis <= t) { candidate = mid; lo = mid + 1 } else hi = mid - 1
        }
        return if (candidate >= 0 && cells[candidate].endMillis > t) candidate else -1
    }

    fun cellAt(row: Int, t: Long): EPGProgramme? = cellIndexAt(row, t).takeIf { it >= 0 }?.let { cells(row)[it] }

    /** The cell containing [t], else the nearest cell by start (never null for a non-empty row). */
    fun nearestCell(row: Int, t: Long): EPGProgramme? {
        cellAt(row, t)?.let { return it }
        val cells = cells(row)
        if (cells.isEmpty()) return null
        return if (t < cells.first().startMillis) cells.first() else cells.last()
    }

    fun indexOfChannel(channelId: String): Int = channels.indexOfFirst { it.id == channelId }

    companion object {
        val EMPTY = GuideGridRows(emptyList(), null, 0L, 0L)
    }
}
