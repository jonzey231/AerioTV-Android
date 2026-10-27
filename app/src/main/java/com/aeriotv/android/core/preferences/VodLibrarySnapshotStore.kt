package com.aeriotv.android.core.preferences

import android.content.Context
import android.util.Log
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.network.DispatcharrVODMovie
import com.aeriotv.android.core.network.DispatcharrVODSeries
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the last finished Movies / TV Shows sweep per playlist so the
 * tabs open populated on the next launch, and so a launch can skip the
 * provider re-sweep while the snapshot is fresh (Settings > App Behaviors >
 * Refresh Movies and TV Shows). Apple parity: VODLibraryCache. Keyed by the
 * playlist identity (id, URL, account), so another source never serves a
 * stale library.
 *
 * Since GH #109 the titles themselves live in the Room catalog
 * (VodCatalogStore) and this file carries only the metadata below: group
 * names and the change-probe baseline. [Snapshot.movies] / [Snapshot.series]
 * are read once from a pre-Room file for the one-time import and are never
 * written again, which also retires the 30 MB decode at launch.
 */
@Singleton
class VodLibrarySnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Serializable
    data class Snapshot(
        val identity: String,
        val savedAtMs: Long,
        val movies: List<DispatcharrVODMovie> = emptyList(),
        val series: List<DispatcharrVODSeries> = emptyList(),
        val movieGroupNames: List<String> = emptyList(),
        val seriesGroupNames: List<String> = emptyList(),
        // When each kind's sweep last ran to completion. 0 = never (or the
        // sweep was killed mid-walk: an app update, a force stop), so the
        // launch gate re-sweeps that kind even though the file is fresh.
        // Pre-field snapshots decode as 0 and re-sweep once.
        val moviesCompletedAtMs: Long = 0L,
        val seriesCompletedAtMs: Long = 0L,
        // Cheap Dispatcharr change-probe baseline recorded alongside the
        // library: the server's item count and newest-item stamp at the time
        // this snapshot was written (see DispatcharrClient.getVODMoviesChangeProbe).
        // The background sweep gate compares a fresh probe against these and
        // sweeps early when either moved, even though the cadence window is
        // still open. 0 / blank = no baseline yet (the first probe records one).
        val moviesProbeCount: Int = 0,
        val moviesProbeNewest: String = "",
        val seriesProbeCount: Int = 0,
        val seriesProbeNewest: String = "",
        // Whether the per-user Dispatcharr capability for each half was DENIED
        // when this file was written (Capability.CanViewVod / CanViewSeries).
        // A denied half is not a library the cadence gate may call "fresh": its
        // completion stamp is forced to 0 on write, and a launch that finds the
        // capability restored treats the half as stale and re-sweeps it even if
        // every other signal says the cache is current. Without this, blocking
        // a half once and unblocking it left the tab hidden forever (phone
        // 2026-09-15: "movies=fresh ... gate=skipped" with an emptied library).
        val moviesDenied: Boolean = false,
        val seriesDenied: Boolean = false,
        // Bumped when a new field changes how the launch gate must read this
        // file. A snapshot older than [SCHEMA] predates the denied markers, so
        // it may have been written while a half was blocked and cannot be
        // trusted as "fresh"; the launch still serves its rows (the tab opens
        // populated) but re-sweeps both halves once to rebuild them honestly.
        val schema: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * v2 (review 2026-10-27): playlist + URL + username, no API key hash, so
     * a key rotation keeps the catalog. VodCatalogStore.migrateIdentitiesToV2
     * re-keys the Room rows; [load] carries a v1 file forward.
     */
    fun identity(p: PlaylistEntity): String =
        "v2|${p.id}|${p.urlString}|${p.username ?: ""}"

    /** The pre-2026-10-27 identity, read once to migrate its file. */
    fun legacyIdentity(p: PlaylistEntity): String =
        "v1|${p.id}|${p.urlString}|${p.username ?: ""}|${p.apiKey?.hashCode() ?: 0}"

    // filesDir, not cacheDir (review 2026-10-27): the denied flags and schema
    // in here arm the written-under-denial latch, and a cache trim that
    // removed the file forced a full sweep of both halves over an intact
    // Room catalog.
    private fun file(identity: String): File =
        File(context.filesDir, "vod-library-${identity.hashCode().toUInt()}.json")

    private fun legacyCacheFile(identity: String): File =
        File(context.cacheDir, "vod-library-${identity.hashCode().toUInt()}.json")

    /**
     * Move an older file (cacheDir, or the v1 identity) to [identity]'s
     * filesDir home, once. No-op when the current file already exists.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    suspend fun migrateFrom(identity: String, legacyIdentity: String?) = withContext(Dispatchers.IO) {
        if (file(identity).isFile) return@withContext
        val candidates = listOfNotNull(
            identity to legacyCacheFile(identity),
            legacyIdentity?.let { it to file(it) },
            legacyIdentity?.let { it to legacyCacheFile(it) },
        )
        for ((oldId, f) in candidates) {
            if (!f.isFile) continue
            val snap = runCatching {
                f.inputStream().buffered(STREAM_BUFFER_BYTES).use { json.decodeFromStream<Snapshot>(it) }
            }.getOrNull()?.takeIf { it.identity == oldId } ?: continue
            save(snap.copy(identity = identity))
            runCatching { f.delete() }
            Log.i(TAG, "[VOD-CACHE] snapshot metadata moved to filesDir under the v2 identity")
            return@withContext
        }
    }

    /**
     * STREAMED, not [File.readText]. Measured on the Google TV Streamer
     * 2026-09-12 (gtvlogs/session6.txt): this library is ~30 MB on disk, and
     * reading it whole allocated a 30 MB byte array plus a ~60 MB String
     * before the parser had decoded a single entry -- the GC at 14:19:32.219
     * freed "7(120MB) LOS objects" and the storm that followed starved the
     * guide's EPG load for twenty seconds. decodeFromStream parses out of a
     * buffered reader, so only the decoded objects are ever held.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    suspend fun load(identity: String): Snapshot? = withContext(Dispatchers.IO) {
        val f = file(identity)
        if (!f.isFile) return@withContext null
        runCatching {
            f.inputStream().buffered(STREAM_BUFFER_BYTES).use { json.decodeFromStream<Snapshot>(it) }
        }
            .onFailure { Log.w(TAG, "snapshot unreadable: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.identity == identity }
    }

    /** Streamed for the same reason as [load]: encodeToString built the whole
     *  30 MB document in memory before a byte reached the disk. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    suspend fun save(snapshot: Snapshot) = withContext(Dispatchers.IO) {
        val f = file(snapshot.identity)
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().buffered(STREAM_BUFFER_BYTES).use { json.encodeToStream(snapshot, it) }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            Log.i(TAG, "[VOD-CACHE] saved metadata: ${snapshot.movieGroupNames.size} movie groups, ${snapshot.seriesGroupNames.size} series groups, legacy rows ${snapshot.movies.size}/${snapshot.series.size} (${f.length() / 1024} KB)")
        }.onFailure { Log.w(TAG, "snapshot save failed: ${it.message}") }
    }

    /**
     * Delete the snapshot file for [identity]. Called ONLY when the playlist
     * that owns it is deleted; a playlist switch leaves every other
     * playlist's snapshot exactly where it is so switching back is instant.
     */
    suspend fun delete(identity: String) = withContext(Dispatchers.IO) {
        runCatching { file(identity).delete(); legacyCacheFile(identity).delete() }
            .onFailure { Log.w(TAG, "snapshot delete failed: ${it.message}") }
        Unit
    }

    companion object {
        /** Current snapshot schema; see [Snapshot.schema]. */
        const val SCHEMA = 1

        const val TAG = "VodLibrarySnapshot"
        /** Big enough that a multi-megabyte document is not read a page at a
         *  time, small enough to stay out of the large-object space. */
        const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}
