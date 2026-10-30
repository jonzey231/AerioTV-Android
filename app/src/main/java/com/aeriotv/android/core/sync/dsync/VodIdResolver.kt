package com.aeriotv.android.core.sync.dsync

import android.content.Context
import android.util.Log
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.vod.VodCatalogStore
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.preferences.VodLibrarySnapshotStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Episode uuid/id pairs seen by the client (the series episode list), handed
 * to [VodIdResolver]. A plain hook so DispatcharrClient needs no new deps.
 */
object VodIdSink {
    @Volatile var onEpisodes: ((List<Pair<String, Int>>) -> Unit)? = null
}

/**
 * Dispatcharr Sync rows carry the server's INTEGER primary keys for VOD
 * (schema 4.3, 4.5, 4.6); local rows are keyed by the movie or episode uuid.
 * Movies resolve through the VOD catalog (its payload holds `id`); episodes
 * through a small saved map filled from every series episode list the app
 * loads, fetching one series' list on demand when a pair is unknown.
 * Series ids are already integers locally.
 */
@Singleton
class VodIdResolver @Inject constructor(
    @ApplicationContext context: Context,
    private val catalog: VodCatalogStore,
    private val snapshots: VodLibrarySnapshotStore,
    private val client: DispatcharrClient,
) {
    private val prefs = context.getSharedPreferences("aerio_vod_ids", Context.MODE_PRIVATE)
    private val fetchedSeries = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /**
     * Movie ids (and uuids) the catalog did not hold, remembered for this
     * process per catalog state (review 2026-10-27): every pull re-scanned
     * the whole catalog for rows of movies this catalog never had. The
     * stamp is identity + generation + row count, so any sweep page that
     * lands invalidates the memo and the next lookup scans again.
     */
    private val missMemo = MissMemo()

    private suspend fun catalogStamp(key: String): String {
        val gen = catalog.state(key, VodCatalogStore.KIND_MOVIE)?.generation ?: -1L
        return "$key|$gen|${catalog.count(key, VodCatalogStore.KIND_MOVIE)}"
    }

    init {
        VodIdSink.onEpisodes = { pairs -> record("e", pairs) }
    }

    private fun record(kind: String, pairs: List<Pair<String, Int>>) {
        if (pairs.isEmpty()) return
        val e = prefs.edit()
        pairs.forEach { (uuid, id) ->
            if (uuid.isBlank()) return@forEach
            e.putInt("$kind:$uuid", id).putString("${kind.uppercase()}:$id", uuid)
        }
        e.apply()
    }

    fun catalogKey(row: PlaylistEntity): String = snapshots.identity(row)

    /**
     * True once [row]'s movie catalog has completed a sweep (or VOD is off for
     * the playlist, so no catalog will come). Until then a movie uuid that is
     * not yet translated may simply not be loaded yet (DsyncReadiness).
     */
    suspend fun moviesReady(row: PlaylistEntity): Boolean {
        if (!row.vodEnabled) return true
        catalog.migrateIdentitiesToV2()
        val st = catalog.state(catalogKey(row), VodCatalogStore.KIND_MOVIE) ?: return false
        return st.completedAtMs > 0L
    }

    /** Movie uuid -> Dispatcharr movie id. */
    suspend fun movieId(row: PlaylistEntity, uuid: String): Int? {
        if (prefs.contains("m:$uuid")) return prefs.getInt("m:$uuid", 0)
        catalog.migrateIdentitiesToV2()
        val key = catalogKey(row)
        val stamp = catalogStamp(key)
        if (missMemo.isMiss(stamp, "u:$uuid")) return null
        val id = catalog.movie(key, uuid)?.id
        if (id == null) { missMemo.addMisses(stamp, listOf("u:$uuid")); return null }
        record("m", listOf(uuid to id))
        return id
    }

    /** Dispatcharr movie ids -> local movie uuids (catalog scan only for unknown ids). */
    suspend fun movieUuids(row: PlaylistEntity, ids: Set<Int>): Map<Int, String> {
        val out = HashMap<Int, String>()
        val missing = HashSet<Int>()
        ids.forEach { id -> prefs.getString("M:$id", null)?.let { out[id] = it } ?: missing.add(id) }
        if (missing.isNotEmpty()) {
            catalog.migrateIdentitiesToV2()
            val key = catalogKey(row)
            val stamp = catalogStamp(key)
            val toScan = missing.filterTo(HashSet()) { !missMemo.isMiss(stamp, "i:$it") }
            if (toScan.isNotEmpty()) {
                val found = catalog.movieUuidsByIds(key, toScan)
                record("m", found.map { (id, uuid) -> uuid to id })
                out += found
                missMemo.addMisses(stamp, (toScan - found.keys).map { "i:$it" })
            }
        }
        return out
    }

    /** Episode uuid -> id; loads [seriesId]'s episode list once when unknown. */
    suspend fun episodeId(base: String, apiKey: String, uuid: String, seriesId: Int?): Int? {
        if (prefs.contains("e:$uuid")) return prefs.getInt("e:$uuid", 0)
        if (seriesId == null || !loadSeries(base, apiKey, seriesId)) return null
        return if (prefs.contains("e:$uuid")) prefs.getInt("e:$uuid", 0) else null
    }

    /** Episode id -> local uuid; loads [seriesId]'s episode list once when unknown. */
    suspend fun episodeUuid(base: String, apiKey: String, id: Int, seriesId: Int?): String? {
        prefs.getString("E:$id", null)?.let { return it }
        if (seriesId == null || !loadSeries(base, apiKey, seriesId)) return null
        return prefs.getString("E:$id", null)
    }

    private suspend fun loadSeries(base: String, apiKey: String, seriesId: Int): Boolean {
        if (!fetchedSeries.add(seriesId)) return false
        return runCatching {
            val page = client.getSeriesEpisodesFirstPage(base, apiKey, seriesId)
            record("e", page.results.map { it.uuid to it.id })
            true
        }.onFailure { Log.w("AerioDsync", "[DSYNC] episode ids for series $seriesId: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
    }
}

/** Lookup misses valid for one catalog stamp; a new stamp starts empty. */
internal class MissMemo {
    private var stamp: String? = null
    private val misses = HashSet<String>()

    @Synchronized fun isMiss(stamp: String, key: String): Boolean = this.stamp == stamp && key in misses

    @Synchronized fun addMisses(stamp: String, keys: Collection<String>) {
        if (this.stamp != stamp) { this.stamp = stamp; misses.clear() }
        misses += keys
    }
}
