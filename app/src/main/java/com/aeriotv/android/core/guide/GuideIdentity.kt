package com.aeriotv.android.core.guide

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import java.security.MessageDigest

/**
 * Guide identity, rebuilt (feature/guide-rebuild, 2026-09-01).
 *
 * Mirrors the Apple GuideStore model: every channel has ONE canonical id that
 * is never a tvg-id, a channel number or an integer server id, and programmes
 * are attached to channels ONCE, at ingest, through explicit match maps.
 * Nothing downstream bridges, guesses or re-derives.
 *
 * Why this exists: the previous design folded tvg-id, channel number,
 * Dispatcharr id and uuid into one first-wins map. Two servers' numeric
 * spaces collided the day a second server's channels were imported (KNBC's
 * declared tvg-id "19" vs ESPN HD's Dispatcharr id 19) and the guide painted
 * KNBC's programmes on ESPN. Here a number can only ever mean a tvg-id.
 */
@JvmInline
value class GuideChannelId(val value: String) {
    override fun toString(): String = value
}

/**
 * Canonical id for a channel row.
 *
 *  - Dispatcharr sources: the server's channel uuid ("disp:<uuid>"), which
 *    is what [M3UChannel.id] already carries for them. Stable across
 *    renumbering and across playlists that import the same server.
 *  - Everything else (XC, plain M3U, file imports): a playlist-scoped id
 *    derived from the stream url, so two playlists (or two rows) sharing a
 *    tvg-id can never share a bucket. The url is hashed: XC urls carry
 *    credentials and must not leak into keys, logs or caches.
 */
fun M3UChannel.guideChannelId(): GuideChannelId = when {
    id.startsWith("disp:") -> GuideChannelId(id)
    else -> GuideChannelId("m3u:" + sha1Hex(url.trim()))
}

private fun sha1Hex(s: String): String {
    val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(40)
    for (b in d) sb.append(String.format("%02x", b))
    return sb.toString()
}

/**
 * Explicit match maps, built once per fetch. Apple builds three
 * (tvgIDToChannelIDs, intIDToChannelID, uuidToChannelID); the integer-id map
 * is deliberately absent here, because integer ids are exactly what collided.
 *
 * [tvgIdToChannels] is ONE-TO-MANY: a tvg-id shared by several channels
 * attaches its programmes to every one of them, as Apple does.
 *
 * Keys are trimmed and lower-cased. What each channel contributes:
 *  - Dispatcharr rows: their bound guide key, i.e. the tvg-id of the EPG
 *    data row the server assigned to the channel (already resolved into
 *    [M3UChannel.tvgID] by dispatcharrGuideKey), or the channel uuid when the
 *    server assigned its "dummy" EPG. Never the channel number, never the
 *    integer id.
 *  - Other rows: their declared tvg-id (the "tvg-id" attribute). Nothing
 *    else. A blank tvg-id contributes nothing and the row simply has no
 *    programmes until a feed keys them some other way.
 */
class GuideMatchMaps private constructor(
    val tvgIdToChannels: Map<String, Set<GuideChannelId>>,
    val uuidToChannel: Map<String, GuideChannelId>,
) {
    /** All canonical ids a raw feed key resolves to; empty when unknown. */
    fun resolve(rawKey: String): Set<GuideChannelId> {
        val key = normalize(rawKey)
        if (key.isEmpty()) return emptySet()
        tvgIdToChannels[key]?.let { return it }
        uuidToChannel[key]?.let { return setOf(it) }
        return emptySet()
    }

    val isEmpty: Boolean get() = tvgIdToChannels.isEmpty() && uuidToChannel.isEmpty()

    companion object {
        fun normalize(raw: String): String = raw.trim().lowercase()

        fun build(channels: List<M3UChannel>): GuideMatchMaps {
            val byTvg = HashMap<String, MutableSet<GuideChannelId>>(channels.size * 2)
            val byUuid = HashMap<String, GuideChannelId>()
            for (ch in channels) {
                val id = ch.guideChannelId()
                val declared = normalize(ch.tvgID.ifBlank { ch.rawAttributes["tvg-id"].orEmpty() })
                if (declared.isNotEmpty()) byTvg.getOrPut(declared) { linkedSetOf() }.add(id)
                if (ch.id.startsWith("disp:")) {
                    // Dummy-EPG channels are keyed by their uuid in the grid.
                    val uuid = normalize(ch.id.removePrefix("disp:"))
                    if (uuid.isNotEmpty()) byUuid.putIfAbsent(uuid, id)
                }
            }
            return GuideMatchMaps(byTvg, byUuid)
        }
    }
}

/** Which source a programme came from; decides span ownership at merge. */
enum class GuideSource {
    /** Dispatcharr /api/epg/grid/: owns now and future for its channels. */
    GRID,
    /** The user's own XMLTV url on a playlist: owns its channels' full window. */
    USER_XMLTV,
    /** Upstream feeds discovered on the server: additive history only. */
    UPSTREAM,
    /** XMLTV of an XC / M3U playlist: owns its channels' full window. */
    PLAYLIST_XMLTV,
}

/** A programme attached to exactly one canonical channel. */
data class ResolvedProgramme(
    val channelId: GuideChannelId,
    val programme: EPGProgramme,
    val source: GuideSource,
)

/** Outcome of an ingest pass, with the counts the log needs. */
data class IngestResult(
    val resolved: List<ResolvedProgramme>,
    val inputCount: Int,
    val unresolvedCount: Int,
    val unresolvedKeys: List<String>,
) {
    val attachedCount: Int get() = resolved.size
}

object GuideIngest {
    /**
     * Attach feed programmes to channels through [maps]. One programme can
     * land on several channels (shared tvg-id); a programme whose key matches
     * nothing is counted, sampled for the log, and dropped. There is no
     * fallback matching of any kind.
     */
    fun attach(
        programmes: List<EPGProgramme>,
        maps: GuideMatchMaps,
        source: GuideSource,
        unresolvedSample: Int = 8,
    ): IngestResult {
        if (maps.isEmpty) {
            return IngestResult(emptyList(), programmes.size, programmes.size, emptyList())
        }
        val out = ArrayList<ResolvedProgramme>(programmes.size)
        var unresolved = 0
        val sample = LinkedHashSet<String>()
        for (p in programmes) {
            val targets = maps.resolve(p.channelId)
            if (targets.isEmpty()) {
                unresolved++
                if (sample.size < unresolvedSample) sample.add(GuideMatchMaps.normalize(p.channelId))
                continue
            }
            for (id in targets) {
                out.add(ResolvedProgramme(id, if (p.channelId == id.value) p else p.copy(channelId = id.value), source))
            }
        }
        return IngestResult(out, programmes.size, unresolved, sample.toList())
    }
}

/**
 * Span ownership: the rule that replaces the old authoritative/insert-only
 * flag. A source may replace cached rows only inside the span it owns for a
 * channel; outside that span it is additive. Reads the same on both
 * platforms.
 */
object SpanOwnership {
    /**
     * Returns the [startMs, endMs) window this [source] may replace for a
     * channel, given the earliest row the grid holds for that channel
     * ([gridEarliestMs], null when the grid has none) and the incoming rows'
     * own extent. Null means "additive only, replace nothing".
     */
    fun replaceWindow(
        source: GuideSource,
        nowMs: Long,
        incomingStartMs: Long,
        incomingEndMs: Long,
        gridEarliestMs: Long?,
    ): LongRange? = when (source) {
        // Grid: authoritative from now forward, never rewrites history.
        GuideSource.GRID -> {
            val from = maxOf(nowMs, incomingStartMs)
            if (incomingEndMs > from) from until incomingEndMs else null
        }
        // The user's own feed and a playlist's own XMLTV own everything they cover.
        GuideSource.USER_XMLTV, GuideSource.PLAYLIST_XMLTV ->
            if (incomingEndMs > incomingStartMs) incomingStartMs until incomingEndMs else null
        // Upstream layering may only fill history before the grid's first row.
        GuideSource.UPSTREAM -> {
            val cutoff = gridEarliestMs ?: return null
            val end = minOf(incomingEndMs, cutoff)
            if (end > incomingStartMs) incomingStartMs until end else null
        }
    }
}
