package com.aeriotv.android.core.sync.dsync

import android.content.Context
import com.aeriotv.android.core.sync.DriveCanon
import com.aeriotv.android.core.sync.SetupSourceChoice
import com.aeriotv.android.core.sync.WatchProgressSnapshot
import com.aeriotv.android.core.sync.WatchProgressSnapshotEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Held server rows (docs/DispatcharrSyncSchema.md section 10a, 2026-11-01).
 *
 * A Dispatcharr Sync progress row this device cannot map to a local movie or
 * episode uuid (the movie catalog sweep has not reached it, or the episode is
 * not in the saved episode map) is held by its canonical id instead of being
 * dropped. The Drive snapshot carries it under a placeholder video id
 * ("dsync:<type>:<id>") with its dispatcharrVodId, so both summaries count
 * the same canonical row, and a later pull or catalog page resolves it into a
 * real local row. A row neither side can represent here is excluded from the
 * Dispatcharr summary on this device, so the setup probe and converge
 * compare only rows this device can represent on both providers.
 *
 * Pure parts are here; [DsyncHeldStore] keeps the rows per playlist.
 */
object DsyncHeld {
    const val PREFIX = "dsync:"

    fun placeholder(type: String, id: Int) = "$PREFIX$type:$id"

    fun isPlaceholder(videoId: String) = videoId.startsWith(PREFIX)

    /** Canonical item ("m|11") of a held row. */
    fun item(r: ProgressRow) = DriveCanon.vodItem(r.type, r.vodId)

    fun toEntry(r: ProgressRow) = WatchProgressSnapshotEntry(
        videoId = placeholder(r.type, r.vodId),
        title = r.title,
        posterUrl = null,
        positionMs = r.position * 1000,
        durationMs = r.duration * 1000,
        updatedAt = r.updatedAt * 1000,
        vodType = if (r.type == "e") "episode" else "movie",
        seriesId = r.seriesId?.toString(),
        seasonNumber = r.season,
        episodeNumber = r.episode,
        isFinished = r.finished,
        dispatcharrVodId = r.vodId,
    )

    /** A Drive placeholder entry back to a held row; null for real local rows or rows without an id. */
    fun fromEntry(e: WatchProgressSnapshotEntry): ProgressRow? {
        if (!isPlaceholder(e.videoId)) return null
        val id = e.dispatcharrVodId ?: return null
        val ep = e.vodType == "episode"
        return ProgressRow(
            vodId = id, type = if (ep) "e" else "m",
            position = e.positionMs / 1000, duration = e.durationMs / 1000, updatedAt = e.updatedAt / 1000,
            finished = e.isFinished, seriesId = if (ep) e.seriesId?.toIntOrNull() else null,
            season = e.seasonNumber, episode = e.episodeNumber, title = e.title,
        )
    }

    /**
     * The Drive snapshot plus the held rows whose canonical item no entry
     * carries yet (a resolved local row wins). Placeholder rows already in
     * [s] are replaced by the current held set.
     */
    fun withHeld(s: WatchProgressSnapshot, held: List<ProgressRow>): WatchProgressSnapshot = driveSnapshot(s, held)

    /**
     * THE Drive WatchProgress snapshot rule (section 10a, single builder,
     * 2026-11-01): local rows plus every Dispatcharr row this device holds or
     * last read from the server ([extra]), so the Drive copy always carries
     * what the Dispatcharr copy carries here. Every push entry point (replace,
     * converge, follow local, follow pull, periodic worker, Push to Drive)
     * goes through DriveSyncManager.driveProgressSnapshot, which calls this.
     *
     * 1. A local row without a canonical id takes it from an [extra] row whose
     *    saved reverse id ([uuidOf]) is that row's uuid (a lookup that failed
     *    in this run never drops the row from the canonical set).
     * 2. A resolved local row wins over the placeholder for the same item.
     * 3. Any other [extra] row rides as a placeholder ("dsync:<type>:<id>").
     * Placeholder rows already in [s] are replaced by the current set.
     */
    fun driveSnapshot(
        s: WatchProgressSnapshot,
        extra: List<ProgressRow>,
        uuidOf: (String, Int) -> String? = { _, _ -> null },
    ): WatchProgressSnapshot {
        val rows = extra.groupBy { item(it) }.map { (_, v) -> v.maxBy { it.updatedAt } }
        val byUuid = HashMap<String, ProgressRow>()
        rows.forEach { r -> uuidOf(r.type, r.vodId)?.takeIf { it.isNotBlank() }?.let { byUuid[it] = r } }
        val real = s.entries.filterNot { isPlaceholder(it.videoId) }.map { e ->
            if (e.dispatcharrVodId != null || (e.vodType != "movie" && e.vodType != "episode")) e
            else byUuid[e.videoId]?.takeIf { (it.type == "e") == (e.vodType == "episode") }?.let { e.copy(dispatcharrVodId = it.vodId) } ?: e
        }
        val have = real.mapNotNull { e ->
            e.dispatcharrVodId?.let { DriveCanon.vodItem(if (e.vodType == "episode") "e" else "m", it) }
        }.toSet()
        val add = rows.filter { item(it) !in have }.map(::toEntry)
        return s.copy(entries = real + add)
    }

    /**
     * Setup-choice ordering (section 10a, field run 2026-11-01: the first
     * replace snapshot read local=0 canonical=3 placeholders=3): the replace
     * push builds the WatchProgress snapshot only once the pulled rows are
     * local, so it waits while rows are held and the movie catalog that
     * resolves them is not built yet. True: build the snapshot now.
     */
    fun progressSettled(heldRows: Int, catalogReady: Boolean): Boolean = heldRows == 0 || catalogReady

    /** Counts only, for the snapshot line. */
    fun snapshotLine(why: String, local: Int, canonical: Int, extra: Int, placeholders: Int) =
        "[DRIVE] progress snapshot local=$local canonical=$canonical dsync=$extra placeholders=$placeholders ($why)"

    /** Held rows merged per canonical item, newest updatedAt wins. */
    fun merge(a: List<ProgressRow>, b: List<ProgressRow>): List<ProgressRow> =
        (a + b).groupBy { item(it) }.map { (_, v) -> v.maxBy { it.updatedAt } }

    /** The VOD keys the representable filter applies to. */
    val VOD_KEYS = setOf(DsyncKey.Progress, DsyncKey.Watchlist, DsyncKey.HiddenTitles)

    /**
     * One VOD category of the Dispatcharr copy restricted to the rows [keep]
     * accepts (type, id). The same row definition as DsyncCoexistence.rowCount
     * and fingerprint. Returns the category and the excluded row count.
     */
    fun vodCategory(key: DsyncKey, data: JsonObject, updatedAtMs: Long, keep: (String, Int) -> Boolean): Pair<SetupSourceChoice.Category, Int> =
        when (key) {
            DsyncKey.Progress -> {
                val all = DsyncPayloads.decodeProgress(data).orEmpty().distinctBy { it.type to it.vodId }
                val rows = all.filter { keep(it.type, it.vodId) }
                SetupSourceChoice.Category(rows.size, updatedAtMs, DriveCanon.dsyncProgress(rows)) to (all.size - rows.size)
            }
            else -> {
                val all = DsyncPayloads.decodeVodMarks(data).orEmpty().distinctBy { it.type to it.vodId }
                val rows = all.filter { keep(it.type, it.vodId) }
                SetupSourceChoice.Category(rows.size, updatedAtMs, DriveCanon.dsyncMarks(rows)) to (all.size - rows.size)
            }
        }

    /**
     * Whether this device can represent a Dispatcharr row: series ids are
     * local integers; a movie or episode resolves to a local uuid, is held,
     * or is carried with a canonical id by the Drive copy.
     */
    fun representable(
        type: String, id: Int,
        movies: Set<Int>, episodes: Set<Int>, held: Set<String>, drive: Set<String>,
        channels: Set<Int>? = null,
    ): Boolean = when {
        type == "s" -> true
        // Recent channels (2026-11-01, recents 14 vs 13): a channel this
        // device's list maps, or one the Drive copy carries. An unloaded
        // channel list (null or empty) excludes nothing.
        type == CHANNEL -> channels.isNullOrEmpty() || id in channels || channelItem(id) in drive
        type == "m" && id in movies -> true
        type == "e" && id in episodes -> true
        else -> DriveCanon.vodItem(type, id).let { it in held || it in drive }
    }

    const val CHANNEL = "c"

    /** Canonical channel item for the representable sets ("c|<id>"). */
    fun channelItem(id: Int) = DriveCanon.vodItem(CHANNEL, id)

    /** The recents category of the Dispatcharr copy restricted to channels [keep] accepts; returns the excluded count. */
    fun recentsCategory(data: JsonObject, updatedAtMs: Long, keep: (String, Int) -> Boolean): Pair<SetupSourceChoice.Category, Int> {
        val all = DsyncPayloads.decodeRecents(data).orEmpty().distinctBy { it.channelId }
        val rows = all.filter { keep(CHANNEL, it.channelId) }
        return SetupSourceChoice.Category(rows.size, updatedAtMs, DriveCanon.dsyncRecents(rows)) to (all.size - rows.size)
    }

    fun excludeLine(name: String, rows: Int) = "[SYNC] probe excludes $name rows=$rows (no local match)"
}

/** Held progress rows per Dispatcharr playlist (base user), plus the canonical VOD items the last Drive read carried. */
@Singleton
class DsyncHeldStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("aerio_dsync_held", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** Canonical VOD items ("m|11", "e|22", "s|3") the last Drive read carried with an id, by playlist. */
    private val driveItems = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    private fun key(playlistId: String) = "progress|$playlistId"

    /** True once a pull wrote the held set for [playlistId] (empty included). */
    fun has(playlistId: String): Boolean = prefs.contains(key(playlistId))

    fun progress(playlistId: String): List<ProgressRow> = runCatching {
        val raw = prefs.getString(key(playlistId), null) ?: return emptyList()
        (json.parseToJsonElement(raw) as? JsonObject)?.let { DsyncPayloads.decodeProgress(it) }.orEmpty()
    }.getOrDefault(emptyList())

    fun setProgress(playlistId: String, rows: List<ProgressRow>) {
        val nowS = System.currentTimeMillis() / 1000
        prefs.edit().putString(key(playlistId), DsyncPayloads.encodeProgress(null, rows, nowS).toString()).apply()
    }

    fun items(playlistId: String): Set<String> = progress(playlistId).map(DsyncHeld::item).toSet()

    fun noteDriveItems(playlistId: String, items: Set<String>) { driveItems[playlistId] = items }

    fun driveItems(playlistId: String): Set<String> = driveItems[playlistId].orEmpty()
}
