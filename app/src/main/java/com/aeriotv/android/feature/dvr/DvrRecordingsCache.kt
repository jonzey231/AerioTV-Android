package com.aeriotv.android.feature.dvr

import android.content.Context
import android.util.Log
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
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
 * Last known SERVER recordings list per playlist, as JSON in cacheDir. Same
 * rules as the EPG and VOD caches (Logan 2026-09-12): the DVR tab opens on the
 * cached list instantly and the first network refresh is a quiet background
 * one after the app has settled.
 *
 * Local (on-device) recordings are NOT cached here: they come from Room, which
 * is already instant and authoritative.
 */
@Singleton
class DvrRecordingsCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Serializable
    data class Snapshot(
        val identity: String,
        val savedAtMs: Long,
        val recordings: List<DvrViewModel.Recording> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Keyed by the playlist identity, so another source never serves a stale list. */
    fun identity(p: PlaylistEntity): String =
        "v1|${p.id}|${p.urlString}|${p.username ?: ""}|${p.apiKey?.hashCode() ?: 0}"

    private fun file(identity: String): File =
        File(context.cacheDir, "dvr-recordings-${identity.hashCode().toUInt()}.json")

    suspend fun load(identity: String): Snapshot? = withContext(Dispatchers.IO) {
        val f = file(identity)
        if (!f.isFile) return@withContext null
        runCatching { json.decodeFromString<Snapshot>(f.readText()) }
            .onFailure { Log.w(TAG, "cache unreadable: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.identity == identity }
    }

    suspend fun save(identity: String, recordings: List<DvrViewModel.Recording>) =
        withContext(Dispatchers.IO) {
            val f = file(identity)
            runCatching {
                val snapshot = Snapshot(
                    identity = identity,
                    savedAtMs = System.currentTimeMillis(),
                    // Server rows only; see the class note.
                    recordings = recordings.filter { it.source == DvrViewModel.Source.Server },
                )
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(json.encodeToString(snapshot))
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }.onFailure { Log.w(TAG, "cache save failed: ${it.message}") }
        }

    private companion object { const val TAG = "DvrRecordingsCache" }
}
