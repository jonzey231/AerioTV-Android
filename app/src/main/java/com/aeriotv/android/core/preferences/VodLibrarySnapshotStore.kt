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
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun identity(p: PlaylistEntity): String =
        "v1|${p.id}|${p.urlString}|${p.username ?: ""}|${p.apiKey?.hashCode() ?: 0}"

    private fun file(identity: String): File =
        File(context.cacheDir, "vod-library-${identity.hashCode().toUInt()}.json")

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
            ?.takeIf { it.identity == identity && (it.movies.isNotEmpty() || it.series.isNotEmpty()) }
    }

    /** Streamed for the same reason as [load]: encodeToString built the whole
     *  30 MB document in memory before a byte reached the disk. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    suspend fun save(snapshot: Snapshot) = withContext(Dispatchers.IO) {
        if (snapshot.movies.isEmpty() && snapshot.series.isEmpty()) return@withContext
        val f = file(snapshot.identity)
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().buffered(STREAM_BUFFER_BYTES).use { json.encodeToStream(snapshot, it) }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            Log.i(TAG, "[VOD-CACHE] saved ${snapshot.movies.size} movies, ${snapshot.series.size} series (${f.length() / 1024} KB)")
        }.onFailure { Log.w(TAG, "snapshot save failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "VodLibrarySnapshot"
        /** Big enough that a multi-megabyte document is not read a page at a
         *  time, small enough to stay out of the large-object space. */
        const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}
