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
    /** Fingerprint of the channel list this catalog was built for. */
    val identityHash: String,
) : Map<String, List<EPGProgramme>> by lists {

    fun index(channelId: GuideChannelId): GuideIndex? = indices[channelId.value]
    fun index(channelKey: String): GuideIndex? = indices[channelKey]

    companion object {
        val EMPTY = GuideCatalog(emptyMap(), emptyMap(), "")

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
                    cells = listOf(
                        GuideIndex.placeholder(id, ch.name, windowStartMs, windowEndMs),
                    )
                } else {
                    val deduped = GuideMerge.dedup(raw.sortedWith(compareBy({ it.startMillis }, { it.endMillis })))
                    index = GuideIndex.build(id, deduped)
                    cells = index.withHoles(windowStartMs, windowEndMs, minGapMs)
                }
                val prev = previous?.lists?.get(id.value)
                lists[id.value] = if (prev != null && prev == cells) prev else cells
                indices[id.value] = index
            }
            return GuideCatalog(indices, lists, GuideIdentityHash.of(channels))
        }
    }
}
