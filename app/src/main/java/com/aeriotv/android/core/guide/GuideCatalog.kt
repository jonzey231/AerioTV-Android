package com.aeriotv.android.core.guide

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel

/**
 * The whole playlist's guide, built ONCE from cached rows: one [GuideIndex]
 * per canonical channel id. Exposed as an immutable `Map<String, List>` so
 * every existing consumer that does `epgByChannel[channel.guideMatchKey]`
 * keeps working unchanged (phase 2 of the rebuild: new data layer behind the
 * old surface). The new grid renderer reads [index] directly.
 *
 * Replaces the four incremental map-surgery paths in the ViewModel (cached
 * paint, fresh fetch, layering landed, history merge) with a single
 * deterministic build: rows -> attach -> dedup -> index -> placeholders.
 * Anything that changes the cache writes to Room and rebuilds this.
 */
class GuideCatalog private constructor(
    private val indices: Map<String, GuideIndex>,
    private val lists: Map<String, List<EPGProgramme>>,
    /** The placeholder window this catalog was built for (0 = unknown). */
    private val windowStartMs: Long = 0L,
    private val windowEndMs: Long = 0L,
    private val minGapMs: Long = 5 * 60_000L,
) : Map<String, List<EPGProgramme>> by lists {

    fun index(channelId: GuideChannelId): GuideIndex? = indices[channelId.value]
    fun index(channelKey: String): GuideIndex? = indices[channelKey]

    /**
     * True when [other] would render exactly the same guide: the same
     * channel keys, every index and cell list the SAME instance (build reuses
     * them from `previous` when equal). A rebuild that produced this is not
     * published, so the guide does not reinstall its rows for nothing
     * (2026-10-27: up to six installs per cold launch).
     */
    fun sameContentAs(other: GuideCatalog): Boolean {
        if (other === this) return true
        if (indices.size != other.indices.size || lists.size != other.lists.size) return false
        for ((k, v) in indices) if (other.indices[k] !== v) return false
        for ((k, v) in lists) if (other.lists[k] !== v) return false
        return true
    }

    /**
     * Paint-only category update (now-airing enrichment): a copy whose cells
     * matching [byProgramId] (Dispatcharr program id) or [byAiring]
     * ("start|end|title") carry the new category. Channels with no change
     * keep their list and index instances, so only the touched rows
     * recompose. No Room read, no rebuild. Returns this when nothing changed.
     */
    fun withCategories(byProgramId: Map<Int, String>, byAiring: Map<String, String>): GuideCatalog {
        if (byProgramId.isEmpty() && byAiring.isEmpty()) return this
        fun patch(cells: List<EPGProgramme>): List<EPGProgramme>? {
            var out: ArrayList<EPGProgramme>? = null
            for (i in cells.indices) {
                val p = cells[i]
                if (p.isPlaceholder) continue
                val cat = p.dispatcharrProgramId?.let { byProgramId[it] }
                    ?: byAiring["${p.startMillis}|${p.endMillis}|${p.title}"]
                    ?: continue
                if (cat == p.category) continue
                if (out == null) out = ArrayList(cells)
                out[i] = p.copy(category = cat)
            }
            return out
        }
        var changed = false
        val newLists = HashMap<String, List<EPGProgramme>>(lists.size * 2)
        val newIndices = HashMap<String, GuideIndex>(indices.size * 2)
        for ((k, list) in lists) newLists[k] = patch(list)?.also { changed = true } ?: list
        for ((k, idx) in indices) {
            val cells = patch(idx.cells)
            newIndices[k] = if (cells == null) idx else { changed = true; GuideIndex.build(idx.channelId, cells) }
        }
        return if (changed) GuideCatalog(newIndices, newLists, windowStartMs, windowEndMs, minGapMs) else this
    }

    /**
     * In-place delta (EPG fetch with a handful of new rows): a copy where
     * each channel in [added] drops its real cells that overlap an added row
     * or whose "channel|start" is in [removed], takes the added rows, and is
     * re-indexed; every other channel keeps its instances, so only the
     * touched rows recompose. No Room read, no full rebuild. Returns null
     * when the delta cannot be applied in place (a row keyed outside the
     * catalog, or an unknown window): the caller rebuilds instead.
     */
    fun withDelta(added: List<EPGProgramme>, removed: Set<String>): GuideCatalog? {
        if (windowEndMs <= windowStartMs) return null
        if (added.isEmpty() && removed.isEmpty()) return this
        val addedByKey = added.groupBy { it.channelId }
        val removedKeys = removed.mapTo(HashSet()) { it.substringBefore('|') }
        val touched = addedByKey.keys + removedKeys
        if (touched.any { it !in indices }) return null
        val newIndices = HashMap(indices)
        val newLists = HashMap(lists)
        for (key in touched) {
            val idx = indices[key] ?: return null
            val adds = addedByKey[key].orEmpty()
            val kept = idx.cells.filter { c ->
                !c.isPlaceholder &&
                    "${c.channelId}|${c.startMillis}" !in removed &&
                    adds.none { a -> a.startMillis < c.endMillis && c.startMillis < a.endMillis }
            }
            val merged = GuideMerge.dedup((kept + adds).sortedWith(compareBy({ it.startMillis }, { it.endMillis })))
            val index = GuideIndex.build(idx.channelId, merged)
            newIndices[key] = index
            newLists[key] = if (merged.isEmpty()) lists[key].orEmpty() else index.withHoles(windowStartMs, windowEndMs, minGapMs)
        }
        return GuideCatalog(newIndices, newLists, windowStartMs, windowEndMs, minGapMs)
    }

    companion object {
        val EMPTY = GuideCatalog(emptyMap(), emptyMap())

        /**
         * Build for [channels] from cached [rows] (any source, any key shape:
         * rows are attached through the match maps here, one-to-many).
         *
         * [windowStartMs]..[windowEndMs] bounds the placeholder synthesis:
         * a channel with no programmes gets one channel-name placeholder
         * spanning the window (Dispatcharr "dummy EPG" parity); a channel
         * with holes of [minGapMs] or more gets focusable "No info" cells.
         *
         * [previous] lets unchanged channels keep their previous List
         * INSTANCE, so Compose rows keyed on list identity do not recompose
         * when a refresh landed the same programmes again.
         */
        fun build(
            channels: List<M3UChannel>,
            rows: List<EPGProgramme>,
            windowStartMs: Long,
            windowEndMs: Long,
            minGapMs: Long = 5 * 60_000L,
            previous: GuideCatalog? = null,
        ): GuideCatalog {
            if (channels.isEmpty()) return EMPTY
            val maps = GuideMatchMaps.build(channels)
            // Rows are already stored under canonical ids once written by the
            // new pipeline; legacy rows (raw feed keys) still resolve through
            // the maps. Attach is idempotent for canonical keys because a
            // canonical id never equals a tvg-id/number/uuid key.
            val byChannel = HashMap<String, ArrayList<EPGProgramme>>(channels.size * 2)
            for (p in rows) {
                val direct = p.channelId
                if (direct.startsWith("disp:") || direct.startsWith("m3u:")) {
                    byChannel.getOrPut(direct) { ArrayList() }.add(p)
                    continue
                }
                for (id in maps.resolve(direct, p.source)) {
                    byChannel.getOrPut(id.value) { ArrayList() }.add(p.copy(channelId = id.value))
                }
            }
            val indices = HashMap<String, GuideIndex>(channels.size * 2)
            val lists = HashMap<String, List<EPGProgramme>>(channels.size * 2)
            for (ch in channels) {
                val id = ch.guideChannelId()
                val raw = byChannel[id.value]
                val cells: List<EPGProgramme>
                val index: GuideIndex
                if (raw.isNullOrEmpty()) {
                    index = GuideIndex.empty(id)
                    cells = GuideIndex.nameCells(id, ch.name, windowStartMs, windowEndMs)
                } else {
                    val deduped = GuideMerge.dedup(raw.sortedWith(compareBy({ it.startMillis }, { it.endMillis })))
                    index = GuideIndex.build(id, deduped)
                    cells = index.withHoles(windowStartMs, windowEndMs, minGapMs)
                }
                val prev = previous?.lists?.get(id.value)
                lists[id.value] = if (prev != null && prev == cells) prev else cells
                // Same for the index the grid reads: equal cells keep the
                // previous instance, so [sameContentAs] is a reference check.
                val prevIndex = previous?.indices?.get(id.value)
                indices[id.value] = if (prevIndex != null && prevIndex.cells == index.cells) prevIndex else index
            }
            return GuideCatalog(indices, lists, windowStartMs, windowEndMs, minGapMs)
        }
    }
}
