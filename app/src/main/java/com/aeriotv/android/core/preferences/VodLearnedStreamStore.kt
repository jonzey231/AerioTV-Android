package com.aeriotv.android.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aeriotv.android.core.data.VodLearnedStream
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Own DataStore file, deliberately NOT the shared `aerio_prefs` one. This is
 * written DURING playback (each time the format changes), and a Preferences
 * DataStore rewrites its whole file per edit -- folding these rows into the
 * settings blob would rewrite every app preference, encrypted credentials
 * included, on each measurement.
 */
private val Context.vodLearnedStreamDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "aerio_vod_learned_streams",
)

/**
 * iOS `VODVersionMeasurementStore` parity: per-copy playback measurements that
 * survive a relaunch, keyed by the Version picker's option id -- the
 * Dispatcharr provider RELATION pk, which is globally unique.
 *
 * MOVIES ONLY. An episode option pins an m3u ACCOUNT rather than a specific
 * file, so an episode's id says nothing about what played and must never be
 * written here (nor read: the two id spaces overlap numerically).
 *
 * A small, disposable cache. Losing it only means the picker falls back to
 * whatever the server reported, so the whole map rides as one JSON blob and a
 * corrupt blob decodes to "nothing learned yet" rather than crashing.
 */
@Singleton
class VodLearnedStreamStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store get() = context.vodLearnedStreamDataStore

    // Tolerant on purpose: a blob written by a FUTURE schema (an added field)
    // still decodes on a downgraded build instead of reading as empty.
    private val json = Json { ignoreUnknownKeys = true }

    /** What this device measured for one provider copy, or null if it has
     *  never played here. */
    suspend fun lookup(relationId: Int): VodLearnedStream? =
        decode(store.data.first()[KEY_ENTRIES])[relationId]?.stream

    /**
     * Batch [lookup] for a whole picker's worth of copies. One decode instead
     * of one per row; relation ids with nothing learned are simply absent.
     */
    suspend fun lookupAll(relationIds: Collection<Int>): Map<Int, VodLearnedStream> {
        if (relationIds.isEmpty()) return emptyMap()
        val wanted = relationIds.toSet()
        return decode(store.data.first()[KEY_ENTRIES])
            .filterKeys { it in wanted }
            .mapValues { (_, entry) -> entry.stream }
    }

    /**
     * Remember [stream] for one provider copy. Skips an empty measurement
     * (nothing to show) and skips the write entirely when the stored value
     * already matches, because playback calls this repeatedly. Over
     * [MAX_ENTRIES] the least recently updated rows are evicted.
     */
    suspend fun record(relationId: Int, stream: VodLearnedStream) {
        if (stream.isEmpty) return
        store.edit { prefs ->
            val current = decode(prefs[KEY_ENTRIES])
            if (current[relationId]?.stream == stream) return@edit
            val updated = current.toMutableMap()
            updated[relationId] = VodLearnedStreamEntry(stream, System.currentTimeMillis())
            val capped: Map<Int, VodLearnedStreamEntry> = if (updated.size <= MAX_ENTRIES) {
                updated
            } else {
                updated.entries
                    .sortedByDescending { it.value.updatedAt }
                    .take(MAX_ENTRIES)
                    .associate { it.key to it.value }
            }
            prefs[KEY_ENTRIES] = json.encodeToString(capped)
        }
    }

    private fun decode(raw: String?): Map<Int, VodLearnedStreamEntry> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<Int, VodLearnedStreamEntry>>(raw)
        }.getOrDefault(emptyMap())
    }

    companion object {
        private val KEY_ENTRIES = stringPreferencesKey("vod_version_measurements_v1")

        /** Room for a heavy browser's history without unbounded growth; the
         *  blob is read and rewritten whole on every change. */
        private const val MAX_ENTRIES = 400
    }
}

/** One persisted row. [updatedAt] exists solely to order the eviction. */
@Serializable
internal data class VodLearnedStreamEntry(
    val stream: VodLearnedStream,
    val updatedAt: Long = 0L,
)
