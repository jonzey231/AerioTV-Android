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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun identity(p: PlaylistEntity): String =
        "v1|${p.id}|${p.urlString}|${p.username ?: ""}|${p.apiKey?.hashCode() ?: 0}"

    private fun file(identity: String): File =
        File(context.cacheDir, "vod-library-${identity.hashCode().toUInt()}.json")

    suspend fun load(identity: String): Snapshot? = withContext(Dispatchers.IO) {
        val f = file(identity)
        if (!f.isFile) return@withContext null
        runCatching { json.decodeFromString<Snapshot>(f.readText()) }
            .onFailure { Log.w(TAG, "snapshot unreadable: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.identity == identity && (it.movies.isNotEmpty() || it.series.isNotEmpty()) }
    }

    suspend fun save(snapshot: Snapshot) = withContext(Dispatchers.IO) {
        if (snapshot.movies.isEmpty() && snapshot.series.isEmpty()) return@withContext
        val f = file(snapshot.identity)
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(snapshot))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            Log.i(TAG, "[VOD-CACHE] saved ${snapshot.movies.size} movies, ${snapshot.series.size} series (${f.length() / 1024} KB)")
        }.onFailure { Log.w(TAG, "snapshot save failed: ${it.message}") }
    }

    private companion object { const val TAG = "VodLibrarySnapshot" }
}
