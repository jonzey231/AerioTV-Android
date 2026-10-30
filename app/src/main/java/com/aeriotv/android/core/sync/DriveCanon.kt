package com.aeriotv.android.core.sync

import com.aeriotv.android.core.data.ChannelCollection
import com.aeriotv.android.core.sync.dsync.CollectionRow
import com.aeriotv.android.core.sync.dsync.DsyncLimits
import com.aeriotv.android.core.sync.dsync.DsyncPayloads
import com.aeriotv.android.core.sync.dsync.ProgressRow
import com.aeriotv.android.core.sync.dsync.RecentRow
import com.aeriotv.android.core.sync.dsync.ReminderRow
import com.aeriotv.android.core.sync.dsync.VodMarkRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical row identities shared by Google Drive snapshots and Dispatcharr
 * Sync (docs/DispatcharrSyncSchema.md section 10a, Rule 4). The canonical id
 * of each shared category is the one Dispatcharr Sync puts on the wire:
 * Dispatcharr channel ids (favorites, recents, collection members), "m"/"e"/"s"
 * plus the Dispatcharr VOD id (progress, watchlist, hidden titles), channel
 * name plus start second (reminders), group names (hidden groups, group
 * order), and schema setting names (settings).
 *
 * Pure: the id lookups arrive as maps ([Ids]), so the enrichment (the
 * one-time Drive id migration and every push) and both sides' fingerprints
 * are unit tested (DriveCanonTest).
 */
object DriveCanon {

    /** Local id to Dispatcharr id. A missing key is an id that did not resolve. */
    data class Ids(
        val channels: Map<String, Int> = emptyMap(),
        val movies: Map<String, Int> = emptyMap(),
        val episodes: Map<String, Int> = emptyMap(),
    ) {
        fun channel(local: String): Int? = channels[local]
    }

    // ---- canonical item strings (both sides build exactly these) ----

    fun channelItem(id: Int) = "$id"
    fun vodItem(type: String, id: Int) = "$type|$id"
    fun reminderItem(channelName: String, startS: Long) = "$channelName|$startS"
    fun collectionItem(id: String, name: String, placement: String, members: List<Int>) =
        "$id|$name|$placement|" + members.distinct().sorted().joinToString(",")
    fun groupItem(kind: String, name: String) = "$kind|$name"
    fun settingItem(lane: String, name: String, value: String) = "$lane|$name|$value"

    private fun fp(items: Collection<String>) = SetupSourceChoice.fingerprint(items)

    // ---- Dispatcharr side (wire rows) ----

    fun dsyncFavorites(ids: List<Int>) = fp(ids.distinct().map(::channelItem))
    fun dsyncRecents(rows: List<RecentRow>) = fp(rows.map { channelItem(it.channelId) }.distinct())
    fun dsyncProgress(rows: List<ProgressRow>) = fp(rows.map { vodItem(it.type, it.vodId) }.distinct())
    fun dsyncReminders(rows: List<ReminderRow>) = fp(rows.map { reminderItem(it.channelName, it.start) })
    fun dsyncMarks(rows: List<VodMarkRow>) = fp(rows.map { vodItem(it.type, it.vodId) }.distinct())
    fun dsyncCollections(rows: List<CollectionRow>) = fp(rows.map { collectionItem(it.id, it.name, it.placement, it.members) })
    fun dsyncHiddenGroups(g: DsyncPayloads.HiddenGroups) = fp(hiddenGroupItems(g.live, g.movies, g.series))
    fun dsyncGroupOrder(g: DsyncPayloads.GroupOrder) = fp(groupOrderItems(g.mode, g.order))
    fun dsyncSettings(lanes: Map<String, Map<String, JsonPrimitive>>): Pair<Int, String> {
        val items = lanes.flatMap { (lane, m) -> m.filterValues { it.content.isNotBlank() }.map { (k, v) -> settingItem(lane, k, v.content) } }
        return items.size to fp(items)
    }

    private fun hiddenGroupItems(live: List<String>, movies: List<String>, series: List<String>) =
        (live.map { groupItem("live", it) } + movies.map { groupItem("movies", it) } + series.map { groupItem("series", it) }).distinct()

    private fun groupOrderItems(mode: String, order: List<String>) =
        order.distinct().mapIndexed { i, n -> "$i|$n" } + "mode|$mode"

    fun groupOrderRows(mode: String, order: List<String>) = if (order.isNotEmpty()) order.distinct().size else if (mode != "default") 1 else 0

    // ---- Drive side: enrichment ----

    /** Fills [FavoriteSnapshotEntry.dispatcharrChannelId] where null and resolvable. Returns the snapshot and rows filled. */
    fun enrichFavorites(s: FavoritesSnapshot, ids: Ids): Pair<FavoritesSnapshot, Int> {
        var n = 0
        val e = s.entries.map { r ->
            if (r.dispatcharrChannelId != null) r else ids.channel(r.channelId)?.let { n++; r.copy(dispatcharrChannelId = it) } ?: r
        }
        return s.copy(entries = e) to n
    }

    fun enrichProgress(s: WatchProgressSnapshot, ids: Ids): Pair<WatchProgressSnapshot, Int> {
        var n = 0
        val e = s.entries.map { r ->
            if (r.dispatcharrVodId != null) r else progressId(r, ids)?.let { n++; r.copy(dispatcharrVodId = it) } ?: r
        }
        return s.copy(entries = e) to n
    }

    /** Returns the snapshot, watchlist rows filled, and hidden rows filled. */
    fun enrichWatchlist(s: WatchlistSnapshot, ids: Ids): Triple<WatchlistSnapshot, Int, Int> {
        var w = 0
        var h = 0
        val e = s.entries.map { r ->
            if (r.dispatcharrVodId != null) r else markId(r.vodType, r.vodId, ids)?.let { w++; r.copy(dispatcharrVodId = it) } ?: r
        }
        val hid = s.hidden.map { r ->
            if (r.dispatcharrVodId != null) r else markId(r.vodType, r.vodId, ids)?.let { h++; r.copy(dispatcharrVodId = it) } ?: r
        }
        return Triple(s.copy(entries = e, hidden = hid), w, h)
    }

    /** Fills [PreferencesSnapshot.dispatcharrChannelIds] for the recents and collection channels. Returns rows (channels) added. */
    fun enrichPreferences(s: PreferencesSnapshot, ids: Ids): Pair<PreferencesSnapshot, Int> {
        val have = s.dispatcharrChannelIds.orEmpty()
        val add = referencedChannels(s).filter { it !in have }.mapNotNull { l -> ids.channel(l)?.let { l to it } }.toMap()
        if (add.isEmpty()) return s to 0
        return s.copy(dispatcharrChannelIds = have + add) to add.size
    }

    /** Local channel ids recents.v1 and channelCollections.v1 reference. */
    fun referencedChannels(s: PreferencesSnapshot): Set<String> =
        (recents(s) + collections(s).flatMap { it.memberIds }).toSet()

    /** Local movie and episode ids a progress snapshot needs translated (movie uuids, episode uuid to series id). */
    fun progressNeeds(s: WatchProgressSnapshot): Pair<Set<String>, Map<String, Int?>> {
        val rows = progressCandidates(s).filter { it.dispatcharrVodId == null }
        return rows.filter { it.vodType == "movie" }.map { it.videoId }.toSet() to
            rows.filter { it.vodType == "episode" }.associate { it.videoId to it.seriesId?.toIntOrNull() }
    }

    fun watchlistMovieNeeds(s: WatchlistSnapshot): Set<String> =
        (s.entries.filter { it.dispatcharrVodId == null && it.vodType != "series" }.map { it.vodId } +
            s.hidden.filter { it.dispatcharrVodId == null && it.vodType != "series" }.map { it.vodId }).toSet()

    private fun progressId(r: WatchProgressSnapshotEntry, ids: Ids): Int? = when {
        r.videoId.contains("://") -> null
        r.vodType == "movie" -> ids.movies[r.videoId]
        r.vodType == "episode" -> ids.episodes[r.videoId]
        else -> null
    }

    private fun markId(vodType: String, vodId: String, ids: Ids): Int? =
        if (vodType == "series") vodId.toIntOrNull() else ids.movies[vodId]

    // ---- Drive side: summary categories (what Dispatcharr Sync would carry) ----

    private fun progressCandidates(s: WatchProgressSnapshot) =
        s.entries.filter { !it.videoId.contains("://") && (it.vodType == "movie" || it.vodType == "episode") }

    fun favorites(s: FavoritesSnapshot, ids: Ids): SetupSourceChoice.Category {
        val c = s.entries.map { it.dispatcharrChannelId ?: ids.channel(it.channelId) }
        val f = if (c.any { it == null }) null else fp(c.filterNotNull().distinct().map(::channelItem))
        return SetupSourceChoice.Category(s.entries.size, s.envelope.lastModified, f)
    }

    /** Mirrors the Dispatcharr cap: finished rows older than 30 days drop, then the 300 newest. */
    fun progress(s: WatchProgressSnapshot, ids: Ids, nowMs: Long): SetupSourceChoice.Category {
        val nowS = nowMs / 1000
        val rows = progressCandidates(s)
            .filterNot { it.isFinished && nowS - it.updatedAt / 1000 > DsyncLimits.FINISHED_MAX_AGE_S }
            .sortedByDescending { it.updatedAt }.take(DsyncLimits.PROGRESS)
        val items = rows.map { r -> (r.dispatcharrVodId ?: progressId(r, ids))?.let { vodItem(if (r.vodType == "episode") "e" else "m", it) } }
        val f = if (items.any { it == null }) null else fp(items.filterNotNull().distinct())
        return SetupSourceChoice.Category(rows.size, s.envelope.lastModified, f)
    }

    /** Future reminders only, as Dispatcharr Sync carries. */
    fun reminders(s: RemindersSnapshot, nowMs: Long): SetupSourceChoice.Category {
        val rows = s.entries.filter { it.endMillis >= nowMs }.distinctBy { it.channelName to it.startMillis / 1000 }
        return SetupSourceChoice.Category(rows.size, s.envelope.lastModified, fp(rows.map { reminderItem(it.channelName, it.startMillis / 1000) }))
    }

    /** Live rows of this playlist's scope ([host], or unscoped). */
    fun watchlist(s: WatchlistSnapshot, ids: Ids, host: String?): SetupSourceChoice.Category {
        val rows = s.entries.filter { it.removedAt == null && (it.serverId == null || it.serverId == host) }
        val items = rows.map { r -> (r.dispatcharrVodId ?: markId(r.vodType, r.vodId, ids))?.let { vodItem(if (r.vodType == "series") "s" else "m", it) } }
        return SetupSourceChoice.Category(rows.size, s.envelope.lastModified, if (items.any { it == null }) null else fp(items.filterNotNull().distinct()))
    }

    fun hiddenTitles(s: WatchlistSnapshot, ids: Ids, host: String?): SetupSourceChoice.Category {
        val rows = s.hidden.filter { it.unhiddenAt == null && (it.serverId == null || it.serverId == host) }
        val items = rows.map { r -> (r.dispatcharrVodId ?: markId(r.vodType, r.vodId, ids))?.let { vodItem(if (r.vodType == "series") "s" else "m", it) } }
        return SetupSourceChoice.Category(rows.size, s.envelope.lastModified, if (items.any { it == null }) null else fp(items.filterNotNull().distinct()))
    }

    private fun lines(v: String?): List<String> = v.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun recents(s: PreferencesSnapshot): List<String> = lines(s.keys[K_RECENTS]).take(DsyncLimits.RECENTS)

    private val collectionsJson = Json { ignoreUnknownKeys = true }

    fun collections(s: PreferencesSnapshot): List<ChannelCollection> =
        s.keys[K_COLLECTIONS]?.takeIf { it.isNotBlank() }?.let {
            runCatching { collectionsJson.decodeFromString<List<ChannelCollection>>(it) }.getOrNull()
        }.orEmpty()

    private fun channelOf(s: PreferencesSnapshot, ids: Ids, local: String): Int? =
        s.dispatcharrChannelIds?.get(local) ?: ids.channel(local)

    fun recentsCategory(s: PreferencesSnapshot, ids: Ids): SetupSourceChoice.Category {
        val r = recents(s)
        val c = r.map { channelOf(s, ids, it) }
        return SetupSourceChoice.Category(r.size, s.envelope.lastModified, if (c.any { it == null }) null else fp(c.filterNotNull().distinct().map(::channelItem)))
    }

    /** A member that does not resolve leaves the fingerprint null (it may be on the server under its id). */
    fun collectionsCategory(s: PreferencesSnapshot, ids: Ids): SetupSourceChoice.Category {
        val cs = collections(s)
        var unsure = false
        val items = cs.map { c ->
            val m = c.memberIds.map { channelOf(s, ids, it) }
            if (m.any { it == null }) unsure = true
            collectionItem(c.id, c.name, if (c.placement == ChannelCollection.PLACEMENT_BEGINNING) "b" else "e", m.filterNotNull())
        }
        return SetupSourceChoice.Category(cs.size, s.envelope.lastModified, if (unsure) null else fp(items))
    }

    fun hiddenGroupsCategory(s: PreferencesSnapshot): SetupSourceChoice.Category {
        val items = hiddenGroupItems(lines(s.keys[K_HIDDEN_LIVE]), lines(s.keys[K_HIDDEN_MOVIES]), lines(s.keys[K_HIDDEN_SERIES]))
        return SetupSourceChoice.Category(items.size, s.envelope.lastModified, fp(items))
    }

    fun groupOrderCategory(s: PreferencesSnapshot): SetupSourceChoice.Category {
        val mode = s.keys[K_SORT_MODE]?.lowercase()?.takeIf { it in DsyncPayloads.GROUP_MODES } ?: "default"
        val order = lines(s.keys[K_ORDER])
        return SetupSourceChoice.Category(groupOrderRows(mode, order), s.envelope.lastModified, fp(groupOrderItems(mode, order)))
    }

    /**
     * [lanes] are the Drive lanes already translated to schema names
     * (AppPreferences.driveLanesAsDsync). Blank values are left out on both sides.
     */
    fun settingsCategory(lanes: Map<String, Map<String, String>>, updatedAt: Long): SetupSourceChoice.Category {
        val items = lanes.flatMap { (lane, m) -> m.filterValues { it.isNotBlank() }.map { (k, v) -> settingItem(lane, k, v) } }
        return SetupSourceChoice.Category(items.size, updatedAt, fp(items))
    }

    const val K_RECENTS = "recents.v1"
    const val K_COLLECTIONS = "channelCollections.v1"
    const val K_HIDDEN_LIVE = "hiddenGroups.v1"
    const val K_HIDDEN_MOVIES = "hiddenMovieGroups.v1"
    const val K_HIDDEN_SERIES = "hiddenSeriesGroups.v1"
    const val K_ORDER = "groupOrder.v1"
    const val K_SORT_MODE = "groupSortMode.v1"

    /**
     * Categories the accepted backup update still has to rewrite: enabled,
     * among [all], and without a done flag. Empty when not accepted, so a
     * retry never runs without "Update backup".
     */
    fun <C> pendingCategories(accepted: Boolean, all: List<C>, enabled: Set<C>, done: (C) -> Boolean): List<C> =
        if (!accepted) emptyList() else all.filter { it in enabled && !done(it) }

    /** The backup format this build writes on every Drive file (SyncEnvelope.format). */
    const val FORMAT = 2

    /** A file is old format when its envelope format is missing or below [FORMAT]. */
    fun isOldFormat(format: Int?): Boolean = format == null || format < FORMAT

    /** Old format backup: any shared file present (non-null entry) is old format. Absent files do not count. */
    fun backupOutdated(fileFormats: List<Int?>): Boolean = fileFormats.any(::isOldFormat)

    /** The single migration log line: counts only, never ids. */
    fun migrationLine(category: String, rows: Int) = "[DRIVE] id migration: $category $rows rows enriched"
}
