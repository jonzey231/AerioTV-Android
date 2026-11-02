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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

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
    fun settingItem(lane: String, name: String, value: String) = "$lane|$name|${canonValue(value)}"

    private val HEX = Regex("^#?[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")

    /**
     * One spelling per value on both sides (Rule 5): booleans lowercase,
     * numbers without a trailing ".0" (Drive writes "1.0", another device may
     * send 1), and color hex uppercase without "#". Anything else as written.
     */
    fun canonValue(v: String): String {
        val t = v.trim()
        t.lowercase().toBooleanStrictOrNull()?.let { return it.toString() }
        if (HEX.matches(t) && (t.startsWith("#") || t.any { it.isLetter() })) return t.removePrefix("#").uppercase()
        t.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }?.let { d ->
            return if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
        }
        return t
    }

    /** Settings lanes without the names this device has Off in Sync Categories (Rule 5, item toggles). */
    fun <V> lanesOn(lanes: Map<String, Map<String, V>>, off: (String) -> Boolean): Map<String, Map<String, V>> =
        lanes.mapValues { (_, m) -> m.filterKeys { !off(it) } }

    private fun fp(items: Collection<String>) = SetupSourceChoice.fingerprint(items)

    // ---- Dispatcharr side (wire rows) ----

    fun dsyncFavorites(ids: List<Int>) = fp(ids.distinct().map(::channelItem))
    fun dsyncRecents(rows: List<RecentRow>) = fp(rows.map { channelItem(it.channelId) }.distinct())
    fun dsyncProgress(rows: List<ProgressRow>) = fp(rows.map { vodItem(it.type, it.vodId) }.distinct())
    fun dsyncReminders(rows: List<ReminderRow>) = fp(rows.map { reminderItem(it.channelName, it.start) })
    fun dsyncMarks(rows: List<VodMarkRow>) = fp(rows.map { vodItem(it.type, it.vodId) }.distinct())
    fun dsyncCollections(rows: List<CollectionRow>) = fp(rows.map { collectionItem(it.id, it.name, it.placement, it.members) })
    fun dsyncHiddenGroups(g: DsyncPayloads.HiddenGroups) = fp(hiddenGroupItems(g.live, g.movies, g.series))
    fun dsyncGroupOrder(g: DsyncPayloads.GroupOrder) = fp(groupOrderItems(groupMode(g.mode), g.order))

    /** The group sort mode as both sides compare it: a known mode, lowercase, else "default" (Drive reads it the same way). */
    fun groupMode(m: String?): String = m?.lowercase()?.takeIf { it in DsyncPayloads.GROUP_MODES } ?: "default"
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
        val rows = progressCandidates(s).filter { it.dispatcharrVodId == null && !com.aeriotv.android.core.sync.dsync.DsyncHeld.isPlaceholder(it.videoId) }
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

    /** Movie and episode uuid to canonical id, from the rows that carry one (format 2). */
    fun progressCanonical(s: WatchProgressSnapshot): Pair<Map<String, Int>, Map<String, Int>> {
        // Held placeholders (no local uuid) never enter the uuid map.
        val rows = progressCandidates(s).filter { it.dispatcharrVodId != null && !com.aeriotv.android.core.sync.dsync.DsyncHeld.isPlaceholder(it.videoId) }
        return rows.filter { it.vodType == "movie" }.associate { it.videoId to it.dispatcharrVodId!! } to
            rows.filter { it.vodType == "episode" }.associate { it.videoId to it.dispatcharrVodId!! }
    }

    /** Movie uuid to canonical id for the watchlist and hidden rows that carry one. */
    fun watchlistCanonical(s: WatchlistSnapshot): Map<String, Int> =
        (s.entries.filter { it.vodType != "series" && it.dispatcharrVodId != null }.map { it.vodId to it.dispatcharrVodId!! } +
            s.hidden.filter { it.vodType != "series" && it.dispatcharrVodId != null }.map { it.vodId to it.dispatcharrVodId!! }).toMap()

    // ---- Drive side: summary categories (what Dispatcharr Sync would carry) ----

    private fun progressCandidates(s: WatchProgressSnapshot) =
        s.entries.filter { !it.videoId.contains("://") && (it.vodType == "movie" || it.vodType == "episode") }

    /**
     * Canonical VOD items a Drive copy carries with an id (progress, watchlist,
     * hidden titles), for the Dispatcharr side's representable filter (10a).
     */
    fun carriedItems(p: WatchProgressSnapshot?, w: WatchlistSnapshot?, ids: Ids = Ids()): Set<String> {
        val out = HashSet<String>()
        p?.let { s -> progressCandidates(s).forEach { r -> (r.dispatcharrVodId ?: progressId(r, ids))?.let { out += vodItem(if (r.vodType == "episode") "e" else "m", it) } } }
        w?.let { s ->
            (s.entries.map { it.vodType to (it.dispatcharrVodId ?: markId(it.vodType, it.vodId, ids)) } +
                s.hidden.map { it.vodType to (it.dispatcharrVodId ?: markId(it.vodType, it.vodId, ids)) })
                .forEach { (t, id) -> if (id != null) out += vodItem(if (t == "series") "s" else "m", id) }
        }
        return out
    }

    /** Canonical-id rows only (distinct Dispatcharr channel ids), as Dispatcharr Sync carries and counts them. */
    fun favorites(s: FavoritesSnapshot, ids: Ids): SetupSourceChoice.Category {
        val c = s.entries.mapNotNull { it.dispatcharrChannelId ?: ids.channel(it.channelId) }.distinct()
        return SetupSourceChoice.Category(c.size, s.envelope.lastModified, fp(c.map(::channelItem)))
    }

    /** Mirrors the Dispatcharr cap: finished rows older than 30 days drop, then the 300 newest. */
    fun progress(s: WatchProgressSnapshot, ids: Ids, nowMs: Long): SetupSourceChoice.Category {
        val nowS = nowMs / 1000
        val rows = progressCandidates(s)
            .filterNot { it.isFinished && nowS - it.updatedAt / 1000 > DsyncLimits.FINISHED_MAX_AGE_S }
            .sortedByDescending { it.updatedAt }.take(DsyncLimits.PROGRESS)
        // Canonical-id rows only (section 10a): a row with no Dispatcharr id can
        // never live on Dispatcharr, so it is neither counted nor compared.
        val items = rows.mapNotNull { r -> (r.dispatcharrVodId ?: progressId(r, ids))?.let { vodItem(if (r.vodType == "episode") "e" else "m", it) } }.distinct()
        return SetupSourceChoice.Category(items.size, s.envelope.lastModified, fp(items))
    }

    /** Future reminders only, as Dispatcharr Sync carries. */
    fun reminders(s: RemindersSnapshot, nowMs: Long): SetupSourceChoice.Category {
        val rows = s.entries.filter { it.endMillis >= nowMs }.distinctBy { it.channelName to it.startMillis / 1000 }
        return SetupSourceChoice.Category(rows.size, s.envelope.lastModified, fp(rows.map { reminderItem(it.channelName, it.startMillis / 1000) }))
    }

    /** Live rows of this playlist's scope ([host], or unscoped). */
    fun watchlist(s: WatchlistSnapshot, ids: Ids, host: String?): SetupSourceChoice.Category {
        val rows = s.entries.filter { it.removedAt == null && (it.serverId == null || it.serverId == host) }
        val items = rows.mapNotNull { r -> (r.dispatcharrVodId ?: markId(r.vodType, r.vodId, ids))?.let { vodItem(if (r.vodType == "series") "s" else "m", it) } }.distinct()
        return SetupSourceChoice.Category(items.size, s.envelope.lastModified, fp(items))
    }

    fun hiddenTitles(s: WatchlistSnapshot, ids: Ids, host: String?): SetupSourceChoice.Category {
        val rows = s.hidden.filter { it.unhiddenAt == null && (it.serverId == null || it.serverId == host) }
        val items = rows.mapNotNull { r -> (r.dispatcharrVodId ?: markId(r.vodType, r.vodId, ids))?.let { vodItem(if (r.vodType == "series") "s" else "m", it) } }.distinct()
        return SetupSourceChoice.Category(items.size, s.envelope.lastModified, fp(items))
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

    /**
     * Canonical-id rows only, the row definition Dispatcharr Sync carries
     * (section 10a): a recent channel with no Dispatcharr id (another
     * playlist, or not in the channel list) can never live on Dispatcharr,
     * so it is neither counted nor compared. Distinct ids, then the 50 cap,
     * the same as the Dispatcharr build (DsyncPayloads.capRecents) and its
     * rowCount (field run 2026-11-01: Drive counted 9 raw lines while the
     * Dispatcharr build carried fewer).
     */
    /** Canonical channel items ("c|<id>") the recents of a Drive copy carry, for the representable filter. */
    fun recentChannelItems(s: PreferencesSnapshot, ids: Ids): Set<String> =
        lines(s.keys[K_RECENTS]).mapNotNull { channelOf(s, ids, it) }.map { vodItem("c", it) }.toSet()

    fun recentsCategory(s: PreferencesSnapshot, ids: Ids): SetupSourceChoice.Category {
        val c = lines(s.keys[K_RECENTS]).mapNotNull { channelOf(s, ids, it) }.distinct().take(DsyncLimits.RECENTS)
        return SetupSourceChoice.Category(c.size, s.envelope.lastModified, fp(c.map(::channelItem)))
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
        val mode = groupMode(s.keys[K_SORT_MODE])
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

    /** The envelope every Drive write carries: always [FORMAT]. */
    fun writerEnvelope(nowMs: Long, device: String, providers: SyncProvidersRecord?) =
        SyncEnvelope(lastModified = nowMs, device = device, syncProviders = providers, format = FORMAT)

    /** A file is old format when its envelope format is missing or below [FORMAT]. */
    fun isOldFormat(format: Int?): Boolean = format == null || format < FORMAT

    /** Old format backup: any shared file present (non-null entry) is old format. Absent files do not count. */
    fun backupOutdated(fileFormats: List<Int?>): Boolean = fileFormats.any(::isOldFormat)

    /**
     * The format stamp alone: [body] with envelope.format set to [FORMAT] and
     * every other field kept as written (unknown fields included). Used when
     * the id fill has to wait (channel list, movie catalog) or the body does
     * not decode as this build's snapshot, so no old-format file survives an
     * accepted backup update. Null when [body] has no envelope object.
     */
    fun stampFormat(body: String): String? = runCatching {
        val o = Json.parseToJsonElement(body).jsonObject
        val env = o["envelope"]?.jsonObject ?: return null
        JsonObject(o + ("envelope" to JsonObject(env + ("format" to JsonPrimitive(FORMAT))))).toString()
    }.getOrNull()

    /** envelope.format of a Drive file body; null when absent or the body has no readable envelope. */
    fun envelopeFormat(body: String): Int? = runCatching {
        Json.parseToJsonElement(body).jsonObject["envelope"]?.jsonObject?.get("format")?.jsonPrimitive?.intOrNull
    }.getOrNull()

    /** Names of the files (category to envelope format) that are old format, for the counts-only offer log. */
    fun oldFormatNames(formats: Map<String, Int?>): List<String> = formats.filterValues(::isOldFormat).keys.toList()

    // ---- Rule 4 replace (the user chose Google Drive) ----
    //
    // The device starts from the Drive copy: a local row the backup does not
    // carry is removed. Only rows inside the comparison the setup probe makes
    // are candidates (same filters as [progress], [reminders], [watchlist],
    // [hiddenTitles]); a row those filters exclude is counted by neither side
    // and is kept (docs/DispatcharrSyncSchema.md 10a).

    /** Local progress rows the probe counts: movie and episode ids, finished rows within 30 days, the 300 newest. */
    fun progressCounted(local: List<WatchProgressSnapshotEntry>, nowMs: Long): List<WatchProgressSnapshotEntry> {
        val nowS = nowMs / 1000
        return local.filter { !it.videoId.contains("://") && (it.vodType == "movie" || it.vodType == "episode") }
            .filterNot { it.isFinished && nowS - it.updatedAt / 1000 > DsyncLimits.FINISHED_MAX_AGE_S }
            .sortedByDescending { it.updatedAt }.take(DsyncLimits.PROGRESS)
    }

    /** Progress video ids to delete: counted locally and absent from the backup. */
    fun progressReplaceDeletes(local: List<WatchProgressSnapshotEntry>, backup: WatchProgressSnapshot, nowMs: Long): List<String> {
        val keep = backup.entries.map { it.videoId }.toSet()
        return progressCounted(local, nowMs).map { it.videoId }.filter { it !in keep }.distinct()
    }

    /** Future reminders only (past ones are not compared). Matched by key, or by channel name and start second. */
    fun reminderReplaceDeletes(local: List<ReminderSnapshotEntry>, backup: RemindersSnapshot, nowMs: Long): List<String> {
        val keys = backup.entries.map { it.reminderKey }.toSet()
        val ids = backup.entries.map { reminderItem(it.channelName, it.startMillis / 1000) }.toSet()
        return local.filter { it.endMillis >= nowMs }
            .filter { it.reminderKey !in keys && reminderItem(it.channelName, it.startMillis / 1000) !in ids }
            .map { it.reminderKey }
    }

    /** Favorites carry no scope: every local favorite the backup lacks goes. */
    fun favoriteReplaceDeletes(local: List<String>, backup: FavoritesSnapshot): List<String> {
        val keep = backup.entries.map { it.channelId }.toSet()
        return local.filter { it !in keep }.distinct()
    }

    /**
     * Watchlist and hidden titles (tombstoned stores): a row whose identity
     * the backup carries becomes the backup row (tombstone included); a live
     * local row in scope ([inScope]) the backup lacks is tombstoned via
     * [tombstone]; every other row is kept; backup rows new here are added.
     */
    fun <E> replaceMarks(
        local: List<E>,
        remote: List<E>,
        identity: (E) -> Pair<String, String?>,
        live: (E) -> Boolean,
        inScope: (E) -> Boolean,
        tombstone: (E) -> E,
    ): List<E> {
        val byId = remote.associateBy(identity)
        val seen = HashSet<Pair<String, String?>>()
        val out = local.map { l ->
            val id = identity(l)
            val r = byId[id]
            when {
                r != null -> { seen += id; r }
                live(l) && inScope(l) -> tombstone(l)
                else -> l
            }
        }
        return out + remote.filter { identity(it) !in seen && seen.add(identity(it)) }
    }

    /** Live rows in [inScope], for the counts line. */
    fun <E> liveInScope(rows: List<E>, live: (E) -> Boolean, inScope: (E) -> Boolean) = rows.count { live(it) && inScope(it) }

    /** The counts-only replace line. */
    fun replaceLine(category: String, local: Int, backup: Int, removed: Int) =
        "[DRIVE] replace $category local=$local backup=$backup removed=$removed"

    /** The single migration log line: counts only, never ids. */
    fun migrationLine(category: String, rows: Int) = "[DRIVE] id migration: $category $rows rows enriched"
}
