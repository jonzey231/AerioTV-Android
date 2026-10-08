package com.aeriotv.android.core.data.vod

import android.database.Cursor
import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.aeriotv.android.core.data.db.AerioDatabase
import com.aeriotv.android.core.data.db.dao.VodArtRow
import com.aeriotv.android.core.data.db.entity.VodSweepLaneEntity
import com.aeriotv.android.core.data.db.entity.VodSweepStateEntity
import com.aeriotv.android.core.data.db.entity.VodTitleEntity
import com.aeriotv.android.core.network.DispatcharrVODMovie
import com.aeriotv.android.core.network.DispatcharrVODSeries
import com.aeriotv.android.feature.movies.MediaItem
import com.aeriotv.android.feature.movies.MediaSortOrder
import com.aeriotv.android.feature.movies.cleanArtTitle
import com.aeriotv.android.feature.movies.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The persisted Movies / TV Shows catalog (GH #109). Replaces the in-memory
 * lists and the 40k row cap: the sweep writes each page here as it arrives,
 * the grids read windows of rows, and filters and sorts run in SQL.
 *
 * Threading: every write and every query builder runs on [Dispatchers.IO]
 * (the Onn main-thread starvation rule, GH #84/#91). The one exception is
 * [VodWindowList.get] missing its window, which reads ~180 rows by primary
 * key on the calling thread; the list prefetches ahead of the scroll so that
 * path is rare (a fast rail jump, the first frame of a new list).
 */
@Singleton
class VodCatalogStore @Inject constructor(
    private val db: AerioDatabase,
) {
    private val dao = db.vodCatalogDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Sweeps of both kinds, the Xtream walk and the legacy import can all
    // write at once; one writer at a time keeps each page's transaction short.
    private val writeLock = Mutex()

    // ---- Writes

    /**
     * Write one page of movies for [generation]. Titles an older generation
     * wrote are refreshed; titles this generation already holds are left as
     * they are (first category wins the group stamp); new titles are inserted.
     * Returns how many rows were new or refreshed.
     */
    suspend fun writeMovies(playlistKey: String, generation: Long, movies: List<DispatcharrVODMovie>): Int =
        writeRows(playlistKey, KIND_MOVIE, generation, movies.map { movieRow(playlistKey, generation, it) })

    /** Series counterpart of [writeMovies]. */
    suspend fun writeSeries(playlistKey: String, generation: Long, series: List<DispatcharrVODSeries>): Int =
        writeRows(playlistKey, KIND_SERIES, generation, series.map { seriesRow(playlistKey, generation, it) })

    private suspend fun writeRows(playlistKey: String, kind: String, generation: Long, rows: List<VodTitleEntity>): Int {
        if (rows.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            writeLock.withLock {
                // Playlist delete crash guard: a sweep page that lands after
                // the delete never writes rows for the deleted playlist.
                com.aeriotv.android.core.data.repository.DeletedPlaylists
                    .ensureAlive(playlistIdOf(playlistKey), "VOD catalog")
                db.withTransaction {
                    var touched = 0
                    for (r in rows) {
                        val refreshed = dao.refreshOlder(
                            playlistKey, kind, r.itemKey, r.title, r.sortKey, r.bucket, r.year, r.rating,
                            r.ratingValue, r.posterUrl, r.category, r.tmdbId, r.normTitle, r.cleanTitle,
                            r.artKey, r.payload, generation,
                        )
                        if (refreshed > 0) touched++ else if (dao.insertIgnore(r) > 0) touched++
                    }
                    touched
                }
            }
        }
    }

    private fun movieRow(playlistKey: String, generation: Long, m: DispatcharrVODMovie): VodTitleEntity {
        val item = m.toMediaItem()
        return VodTitleEntity(
            playlistKey = playlistKey, kind = KIND_MOVIE, itemKey = m.uuid,
            title = item.title, sortKey = item.sortKey, bucket = item.bucket.toString(),
            year = m.year, rating = m.rating, ratingValue = item.ratingValue, posterUrl = m.posterUrl,
            category = m.categoryName, tmdbId = m.tmdbId?.takeIf { it.isNotBlank() },
            normTitle = normalizeVodTitle(m.displayName), cleanTitle = cleanArtTitle(m.displayName),
            artKey = item.artKey,
            // The raw custom_properties blob is only read to stamp the group,
            // which is already done; dropping it keeps rows small.
            payload = json.encodeToString(DispatcharrVODMovie.serializer(), m.copy(customPropertiesRaw = null)),
            generation = generation,
        )
    }

    private fun seriesRow(playlistKey: String, generation: Long, s: DispatcharrVODSeries): VodTitleEntity {
        val item = s.toMediaItem()
        return VodTitleEntity(
            playlistKey = playlistKey, kind = KIND_SERIES, itemKey = s.id.toString(),
            title = item.title, sortKey = item.sortKey, bucket = item.bucket.toString(),
            year = s.year, rating = s.rating, ratingValue = item.ratingValue, posterUrl = s.posterUrl,
            category = s.categoryName, tmdbId = s.tmdbId?.takeIf { it.isNotBlank() },
            normTitle = normalizeVodTitle(s.displayName), cleanTitle = cleanArtTitle(s.displayName),
            artKey = item.artKey,
            payload = json.encodeToString(DispatcharrVODSeries.serializer(), s.copy(customPropertiesRaw = null)),
            generation = generation,
        )
    }

    // ---- Sweep bookkeeping

    /** A sweep about to walk [lanes]: resumes the open generation, else opens a new one. */
    data class SweepPlan(val generation: Long, val resumed: Boolean, val lanes: List<VodSweepLaneEntity>)

    /**
     * Open (or resume) a sweep. An OPEN generation left by an interrupted or
     * failed run is resumed: lanes keep their saved page, lanes the server no
     * longer enables are dropped, new ones start at [firstQuery]. Otherwise
     * the generation advances and every lane starts from its first page.
     */
    suspend fun beginSweep(
        playlistKey: String,
        kind: String,
        lanes: List<String>,
        firstQuery: (String) -> String,
    ): SweepPlan = withContext(Dispatchers.IO) {
        writeLock.withLock {
            com.aeriotv.android.core.data.repository.DeletedPlaylists
                .ensureAlive(playlistIdOf(playlistKey), "VOD sweep start")
            db.withTransaction {
                val now = System.currentTimeMillis()
                val state = dao.state(playlistKey, kind)
                if (state != null && state.open) {
                    val saved = dao.lanes(playlistKey, kind).associateBy { it.lane }
                    val wanted = lanes.toSet()
                    saved.keys.filter { it !in wanted }.forEach { dao.deleteLane(playlistKey, kind, it) }
                    val out = lanes.map { name ->
                        saved[name]?.takeIf { it.generation == state.generation }
                            ?: VodSweepLaneEntity(playlistKey, kind, name, state.generation, firstQuery(name), 0, false)
                                .also { dao.upsertLane(it) }
                    }
                    SweepPlan(state.generation, resumed = true, lanes = out)
                } else {
                    val generation = (state?.generation ?: 0L) + 1L
                    dao.upsertState(
                        VodSweepStateEntity(playlistKey, kind, generation, open = true, startedAtMs = now,
                            completedAtMs = state?.completedAtMs ?: 0L),
                    )
                    dao.deleteLanes(playlistKey, kind)
                    val out = lanes.map { VodSweepLaneEntity(playlistKey, kind, it, generation, firstQuery(it), 0, false) }
                    out.forEach { dao.upsertLane(it) }
                    SweepPlan(generation, resumed = false, lanes = out)
                }
            }
        }
    }

    /** Save a lane's position after a page landed (or a page failed). */
    suspend fun saveLane(lane: VodSweepLaneEntity) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            com.aeriotv.android.core.data.repository.DeletedPlaylists
                .ensureAlive(playlistIdOf(lane.playlistKey), "VOD sweep lane")
            dao.upsertLane(lane)
        }
    }

    suspend fun state(playlistKey: String, kind: String): VodSweepStateEntity? =
        withContext(Dispatchers.IO) { dao.state(playlistKey, kind) }

    suspend fun lanes(playlistKey: String, kind: String): List<VodSweepLaneEntity> =
        withContext(Dispatchers.IO) { dao.lanes(playlistKey, kind) }

    /**
     * Close a sweep that walked every lane: delete titles older generations
     * wrote and nothing re-confirmed, except the groups in [keepCategories]
     * (lanes given up after repeated failures, whose titles could not be
     * re-confirmed and must not vanish because of it). Refuses to close a
     * generation that wrote nothing, so a transient all-empty answer never
     * wipes a good library. Returns the rows deleted, or -1 when refused.
     */
    suspend fun finishSweep(
        playlistKey: String,
        kind: String,
        generation: Long,
        keepCategories: List<String> = emptyList(),
    ): Int = withContext(Dispatchers.IO) {
        writeLock.withLock {
            db.withTransaction {
                if (dao.countGeneration(playlistKey, kind, generation) == 0) return@withTransaction -1
                val deleted = dao.deleteOlderGenerations(playlistKey, kind, generation, keepCategories.take(MAX_BIND))
                val prev = dao.state(playlistKey, kind)
                dao.upsertState(
                    VodSweepStateEntity(playlistKey, kind, generation, open = false,
                        startedAtMs = prev?.startedAtMs ?: 0L, completedAtMs = System.currentTimeMillis()),
                )
                dao.deleteLanes(playlistKey, kind)
                deleted
            }
        }.also { if (it >= 0) clearRowCache() }
    }

    /**
     * Close a sweep WITHOUT deleting anything: every lane was walked but the
     * generation wrote nothing ([finishSweep] refused), so the rows already
     * stored stay and the next sweep opens a fresh generation instead of
     * resuming an empty one forever. [completedAtMs] is left untouched.
     */
    suspend fun abandonSweep(playlistKey: String, kind: String) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            db.withTransaction {
                val prev = dao.state(playlistKey, kind) ?: return@withTransaction
                dao.upsertState(prev.copy(open = false))
                dao.deleteLanes(playlistKey, kind)
            }
        }
    }

    /**
     * Drop catalogs of playlists that no longer exist, and older identities
     * of [activeKey]'s playlist (a URL, account or key change re-sweeps into a
     * new identity and would otherwise leave the old rows behind forever).
     */
    suspend fun pruneIdentities(validPlaylistIds: Set<String>, activeKey: String?) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val keys = (dao.titlePlaylistKeys() + dao.knownPlaylistKeys()).toSet()
            val activeId = activeKey?.let(::playlistIdOf)
            for (k in keys) {
                val id = playlistIdOf(k)
                val stale = id == null || id !in validPlaylistIds || (activeId != null && id == activeId && k != activeKey)
                if (!stale) continue
                db.withTransaction {
                    dao.deleteTitlesFor(k); dao.deleteStateFor(k); dao.deleteLanesFor(k)
                }
                Log.i(TAG, "[VOD-DB] pruned catalog for stale identity of playlist ${id?.take(8)}")
            }
        }
    }

    /**
     * Drop every catalog row this playlist owns, across ALL of its
     * identities (a URL / account change mints a new key for the same id).
     *
     * Called from the playlist delete path and from "Refresh Everything"
     * (which wipes the ACTIVE playlist's catalog and rebuilds it from
     * scratch). A playlist SWITCH must never call this: the previous
     * playlist's catalog is what makes switching back instant.
     */
    suspend fun deleteForPlaylistId(playlistId: String) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val keys = (dao.titlePlaylistKeys() + dao.knownPlaylistKeys()).toSet()
                .filter { playlistIdOf(it) == playlistId }
            for (k in keys) {
                db.withTransaction {
                    dao.deleteTitlesFor(k); dao.deleteStateFor(k); dao.deleteLanesFor(k)
                }
            }
            if (keys.isNotEmpty()) {
                Log.i(TAG, "[VOD-DB] dropped ${keys.size} catalog identities for deleted playlist ${playlistId.take(8)}")
            }
        }
    }

    /** identity() is "v1|<playlistId>|<url>|..."; null for an unknown shape. */
    private fun playlistIdOf(key: String): String? = key.split('|').getOrNull(1)

    /**
     * One-time import of a pre-Room snapshot file so an upgrade opens with
     * the library it had. Written as generation 0 and closed with the
     * snapshot's completion stamp, so the launch cadence gate behaves exactly
     * as it did with the file; the first real sweep (generation 1) refreshes
     * every imported row and removes the ones the provider dropped.
     */
    suspend fun importLegacy(
        playlistKey: String,
        movies: List<DispatcharrVODMovie>,
        series: List<DispatcharrVODSeries>,
        moviesCompletedAtMs: Long,
        seriesCompletedAtMs: Long,
    ) {
        suspend fun closeImported(kind: String, completedAt: Long) = withContext(Dispatchers.IO) {
            writeLock.withLock {
                dao.upsertState(VodSweepStateEntity(playlistKey, kind, 0L, open = false, startedAtMs = 0L, completedAtMs = completedAt))
            }
        }
        if (movies.isNotEmpty() && count(playlistKey, KIND_MOVIE) == 0) {
            movies.chunked(IMPORT_CHUNK).forEach { writeMovies(playlistKey, 0L, it) }
            closeImported(KIND_MOVIE, moviesCompletedAtMs)
        }
        if (series.isNotEmpty() && count(playlistKey, KIND_SERIES) == 0) {
            series.chunked(IMPORT_CHUNK).forEach { writeSeries(playlistKey, 0L, it) }
            closeImported(KIND_SERIES, seriesCompletedAtMs)
        }
    }

    // ---- Reads

    suspend fun count(playlistKey: String, kind: String): Int =
        withContext(Dispatchers.IO) { dao.count(playlistKey, kind) }

    suspend fun distinctCategories(playlistKey: String, kind: String): List<String> =
        withContext(Dispatchers.IO) { dao.distinctCategories(playlistKey, kind) }

    suspend fun movie(playlistKey: String, uuid: String): DispatcharrVODMovie? = withContext(Dispatchers.IO) {
        dao.byItemKey(playlistKey, KIND_MOVIE, uuid)?.let(::decodeMovie)
    }

    suspend fun series(playlistKey: String, id: Int): DispatcharrVODSeries? = withContext(Dispatchers.IO) {
        dao.byItemKey(playlistKey, KIND_SERIES, id.toString())?.let(::decodeSeries)
    }

    suspend fun moviesByTmdbIds(playlistKey: String, ids: Collection<String>): List<DispatcharrVODMovie> =
        chunkedRows(ids) { dao.byTmdbIds(playlistKey, KIND_MOVIE, it) }.mapNotNull(::decodeMovie)

    suspend fun seriesByTmdbIds(playlistKey: String, ids: Collection<String>): List<DispatcharrVODSeries> =
        chunkedRows(ids) { dao.byTmdbIds(playlistKey, KIND_SERIES, it) }.mapNotNull(::decodeSeries)

    suspend fun moviesByNormTitlesWithoutTmdb(playlistKey: String, titles: Collection<String>): List<DispatcharrVODMovie> =
        chunkedRows(titles) { dao.byNormTitlesWithoutTmdb(playlistKey, KIND_MOVIE, it) }.mapNotNull(::decodeMovie)

    suspend fun seriesByNormTitlesWithoutTmdb(playlistKey: String, titles: Collection<String>): List<DispatcharrVODSeries> =
        chunkedRows(titles) { dao.byNormTitlesWithoutTmdb(playlistKey, KIND_SERIES, it) }.mapNotNull(::decodeSeries)

    suspend fun moviesByCleanTitles(playlistKey: String, titles: Collection<String>): List<DispatcharrVODMovie> =
        chunkedRows(titles) { dao.byCleanTitles(playlistKey, KIND_MOVIE, it) }.mapNotNull(::decodeMovie)

    suspend fun seriesByCleanTitles(playlistKey: String, titles: Collection<String>): List<DispatcharrVODSeries> =
        chunkedRows(titles) { dao.byCleanTitles(playlistKey, KIND_SERIES, it) }.mapNotNull(::decodeSeries)

    suspend fun searchMovies(playlistKey: String, query: String, limit: Int = SEARCH_LIMIT): List<DispatcharrVODMovie> =
        withContext(Dispatchers.IO) { dao.searchTitle(playlistKey, KIND_MOVIE, escapeLike(query), limit).mapNotNull(::decodeMovie) }

    suspend fun searchSeries(playlistKey: String, query: String, limit: Int = SEARCH_LIMIT): List<DispatcharrVODSeries> =
        withContext(Dispatchers.IO) { dao.searchTitle(playlistKey, KIND_SERIES, escapeLike(query), limit).mapNotNull(::decodeSeries) }

    suspend fun artPage(playlistKey: String, kind: String, afterId: Long, limit: Int): List<VodArtRow> =
        withContext(Dispatchers.IO) { dao.artPage(playlistKey, kind, afterId, limit) }

    private suspend fun chunkedRows(
        values: Collection<String>,
        query: suspend (List<String>) -> List<VodTitleEntity>,
    ): List<VodTitleEntity> = withContext(Dispatchers.IO) {
        val out = ArrayList<VodTitleEntity>()
        for (chunk in values.filter { it.isNotBlank() }.distinct().chunked(MAX_BIND)) out += query(chunk)
        out
    }

    private fun escapeLike(q: String) = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun decodeMovie(row: VodTitleEntity): DispatcharrVODMovie? =
        runCatching { json.decodeFromString(DispatcharrVODMovie.serializer(), row.payload) }.getOrNull()

    private fun decodeSeries(row: VodTitleEntity): DispatcharrVODSeries? =
        runCatching { json.decodeFromString(DispatcharrVODSeries.serializer(), row.payload) }.getOrNull()

    // ---- The grid's library

    /** What a tab's grid shows: the filtered + sorted ids and their rail buckets. */
    data class LibraryQuery(
        val playlistKey: String,
        val kind: String,
        val hiddenGroups: Set<String>,
        val hiddenTitleKeys: Set<String>,
        /** A group name, [onlyHiddenToken] for the Hidden category, or null for All. */
        val genre: String?,
        val onlyHiddenToken: String,
        val sort: MediaSortOrder,
    )

    /**
     * Run [q] and return the ids in display order plus each row's rail
     * bucket. ~9 bytes per title, so a 130k title library costs ~1.2 MB here
     * instead of the ~80 MB the mapped MediaItem list needed. Call off main.
     */
    suspend fun buildLibrary(q: LibraryQuery): VodWindowList = withContext(Dispatchers.IO) {
        val kindPrefix = q.kind + ":"
        val onlyHidden = q.genre == q.onlyHiddenToken
        val args = ArrayList<Any?>()
        val where = StringBuilder("playlistKey = ? AND kind = ?")
        args += q.playlistKey; args += q.kind
        // Hidden titles: bound in SQL while the set fits the parameter limit,
        // filtered in the cursor loop past it (item keys are then read too).
        val hiddenItemKeys = q.hiddenTitleKeys.asSequence()
            .filter { it.startsWith(kindPrefix) }.map { it.removePrefix(kindPrefix) }.toList()
        val bindHidden = hiddenItemKeys.size <= MAX_BIND
        if (onlyHidden) {
            if (hiddenItemKeys.isEmpty()) return@withContext VodWindowList.empty(this@VodCatalogStore)
            if (bindHidden) { where.append(" AND itemKey IN (").append(placeholders(hiddenItemKeys.size)).append(")"); args.addAll(hiddenItemKeys) }
        } else if (hiddenItemKeys.isNotEmpty() && bindHidden) {
            where.append(" AND itemKey NOT IN (").append(placeholders(hiddenItemKeys.size)).append(")"); args.addAll(hiddenItemKeys)
        }
        if (q.hiddenGroups.isNotEmpty()) {
            val groups = q.hiddenGroups.take(MAX_BIND)
            where.append(" AND (category IS NULL OR category NOT IN (").append(placeholders(groups.size)).append("))")
            args.addAll(groups)
        }
        if (!onlyHidden && q.genre != null) { where.append(" AND category = ?"); args += q.genre }
        val order = when (q.sort) {
            MediaSortOrder.TitleAZ -> "sortKey ASC, title ASC"
            MediaSortOrder.TitleZA -> "sortKey DESC, title ASC"
            MediaSortOrder.YearNewest -> "(year IS NULL) ASC, year DESC, sortKey ASC"
            MediaSortOrder.YearOldest -> "(year IS NULL) ASC, year ASC, sortKey ASC"
            MediaSortOrder.RatingHighest -> "COALESCE(ratingValue, -1.0) DESC, sortKey ASC"
        }
        val sql = "SELECT id, bucket" + (if (bindHidden) "" else ", itemKey") +
            " FROM vod_title WHERE $where ORDER BY $order"
        val hiddenSet = if (bindHidden) null else hiddenItemKeys.toHashSet()
        var ids = LongArray(4096)
        var buckets = CharArray(4096)
        var n = 0
        db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { c ->
            while (c.moveToNext()) {
                if (hiddenSet != null) {
                    val hidden = c.getString(2) in hiddenSet
                    if (hidden != onlyHidden) continue
                }
                if (n == ids.size) { ids = ids.copyOf(n * 2); buckets = buckets.copyOf(n * 2) }
                ids[n] = c.getLong(0)
                buckets[n] = c.getString(1)?.firstOrNull() ?: '#'
                n++
            }
        }
        val list = VodWindowList(this@VodCatalogStore, q.playlistKey, q.kind, ids.copyOf(n), buckets.copyOf(n))
        // Warm the first screens so the first frame never reads on main.
        loadWindow(list.ids, 0)
        list
    }

    private fun placeholders(n: Int) = List(n) { "?" }.joinToString(",")

    // Row cache shared by every window list, keyed by the stable row id, so a
    // rebuilt list (a new sort, a progressive sweep publish) reuses the rows
    // already read for the old one.
    private val rowCache = object : LinkedHashMap<Long, MediaItem>(ROW_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, MediaItem>?) = size > ROW_CACHE_SIZE
    }
    private val inFlight = HashSet<Int>()

    internal fun cached(id: Long): MediaItem? = synchronized(rowCache) { rowCache[id] }

    /** Forget every cached row (a finished sweep may have changed titles or art). */
    fun clearRowCache() = synchronized(rowCache) { rowCache.clear() }

    /** Read the window holding [index] synchronously into the row cache. */
    internal fun loadWindow(ids: LongArray, index: Int) {
        if (ids.isEmpty()) return
        val start = (index - WINDOW_BEHIND).coerceAtLeast(0)
        val end = (start + WINDOW_SIZE).coerceAtMost(ids.size)
        val want = (start until end).map { ids[it] }.filter { cached(it) == null }
        if (want.isEmpty()) return
        val sql = "SELECT id, itemKey, kind, title, year, rating, posterUrl, category FROM vod_title WHERE id IN (" +
            placeholders(want.size) + ")"
        runCatching {
            db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, want.toTypedArray())).use { c ->
                val rows = ArrayList<Pair<Long, MediaItem>>(want.size)
                while (c.moveToNext()) rows += c.getLong(0) to rowItem(c)
                synchronized(rowCache) { rows.forEach { (id, item) -> rowCache[id] = item } }
            }
        }.onFailure { Log.w(TAG, "[VOD-DB] window read failed: ${it.message}") }
    }

    /** Read the window after [index] in the background when it is not cached yet. */
    internal fun prefetch(ids: LongArray, index: Int) {
        val ahead = (index + WINDOW_SIZE - WINDOW_BEHIND).coerceAtMost(ids.size - 1)
        val behind = (index - WINDOW_BEHIND).coerceAtLeast(0)
        for (probe in intArrayOf(ahead, behind)) {
            if (probe < 0 || cached(ids[probe]) != null) continue
            val bucket = probe / WINDOW_SIZE
            val claimed = synchronized(inFlight) { inFlight.add(bucket) }
            if (!claimed) continue
            scope.launch {
                try { loadWindow(ids, probe) } finally { synchronized(inFlight) { inFlight.remove(bucket) } }
            }
        }
    }

    private fun rowItem(c: Cursor): MediaItem {
        val kind = c.getString(2)
        val itemKey = c.getString(1)
        return MediaItem(
            key = "$kind:$itemKey",
            title = c.getString(3),
            year = if (c.isNull(4)) null else c.getInt(4),
            rating = if (c.isNull(5)) null else c.getString(5),
            posterUrl = if (c.isNull(6)) null else c.getString(6),
            category = if (c.isNull(7)) null else c.getString(7),
            movieUuid = if (kind == KIND_MOVIE) itemKey else null,
            seriesId = if (kind == KIND_SERIES) itemKey.toIntOrNull() else null,
        )
    }

    /** Position of [itemKey] ("m:uuid" / "s:id") in [list], or -1. One indexed lookup. */
    internal fun indexOfKey(list: VodWindowList, key: String): Int {
        val itemKey = key.substringAfter(':', "")
        if (itemKey.isEmpty()) return -1
        val id = runCatching {
            db.openHelper.readableDatabase.query(
                SimpleSQLiteQuery(
                    "SELECT id FROM vod_title WHERE playlistKey = ? AND kind = ? AND itemKey = ? LIMIT 1",
                    arrayOf(list.playlistKey, list.kind, itemKey),
                ),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull() ?: return -1
        return list.ids.indexOf(id)
    }

    companion object {
        const val TAG = "VodCatalogStore"
        const val KIND_MOVIE = "m"
        const val KIND_SERIES = "s"
        /** SQLite's host-parameter limit on API 26 is 999. */
        const val MAX_BIND = 900
        const val WINDOW_SIZE = 180
        const val WINDOW_BEHIND = 60
        const val ROW_CACHE_SIZE = 2_400
        const val IMPORT_CHUNK = 500
        const val SEARCH_LIMIT = 500
    }
}

/**
 * Fold a display name for loose matching: trim, drop a trailing "(YYYY)"
 * year suffix, lowercase. A name that is ONLY "(2010)" keeps its original
 * text, mirroring TMDBService.splitTitleYear's empty-query guard. Shared by
 * the catalog's normTitle column and OnDemandViewModel's matchers.
 */
fun normalizeVodTitle(raw: String): String {
    val trimmed = raw.trim()
    val cleaned = knownForTrailingYear.find(trimmed)
        ?.let { trimmed.removeRange(it.range).trim() }
        ?.ifEmpty { trimmed }
        ?: trimmed
    return cleaned.lowercase()
}

private val knownForTrailingYear = Regex("""\(((?:19|20)\d{2})\)\s*$""")

/**
 * A tab's grid list backed by the catalog: only ids and rail buckets live in
 * memory, rows are read a window at a time into the store's shared cache.
 *
 * equals / hashCode / toString are IDENTITY based on purpose: the collection
 * defaults walk every element, which here would read the whole catalog the
 * moment a StateFlow or a remember key compared two libraries.
 */
class VodWindowList internal constructor(
    private val store: VodCatalogStore,
    internal val playlistKey: String,
    internal val kind: String,
    internal val ids: LongArray,
    private val buckets: CharArray,
) : AbstractList<MediaItem>() {

    override val size: Int get() = ids.size

    override fun get(index: Int): MediaItem {
        val id = ids[index]
        val hit = store.cached(id) ?: run {
            store.loadWindow(ids, index)
            store.cached(id)
        }
        store.prefetch(ids, index)
        // A row a finished sweep deleted after this list was built: a unique
        // blank placeholder until the rebuild that sweep triggers lands.
        return hit ?: MediaItem(key = "gone:$id", title = "", year = null, rating = null, posterUrl = null, category = null)
    }

    /** Rail letters present in this list. */
    val letters: Set<Char> by lazy { buckets.toHashSet() }

    /** First index whose rail bucket is [letter], or -1. No row reads. */
    fun indexOfLetter(letter: Char): Int = buckets.indexOf(letter)

    /** Index of the title with MediaItem key [key], or -1. */
    fun indexOfKey(key: Any): Int = (key as? String)?.let { store.indexOfKey(this, it) } ?: -1

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
    override fun toString(): String = "VodWindowList($kind, ${ids.size} titles)"

    companion object {
        internal fun empty(store: VodCatalogStore) = VodWindowList(store, "", "", LongArray(0), CharArray(0))
    }
}
