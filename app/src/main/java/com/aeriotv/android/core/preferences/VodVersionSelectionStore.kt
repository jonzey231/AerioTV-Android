package com.aeriotv.android.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Own DataStore file for the same reason [VodLearnedStreamStore] has one: this
 * is written while BROWSING (every version pick, in the detail sheet and in the
 * player), and a Preferences DataStore rewrites its whole file per edit --
 * folding these rows into the settings blob would rewrite every app preference,
 * encrypted credentials included, on each pick.
 */
private val Context.vodVersionSelectionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "aerio_vod_version_selections",
)

/**
 * iOS `VODVersionSelectionStore` parity: which provider copy the user pinned
 * for a title, so reopening it (or relaunching) does not silently fall back to
 * Auto.
 *
 * Keyed by playlist + item type + item id, so the same movie on two servers
 * keeps separate choices and the numerically overlapping movie / series id
 * spaces cannot collide. The VALUE is the provider RELATION id, which the
 * caller validates against the freshly loaded provider list: a copy the
 * provider dropped falls back to Auto rather than pinning a dead id.
 *
 * A small, disposable cache. Losing it only means a title opens on Auto, so the
 * whole map rides as one JSON blob and a corrupt blob decodes to "nothing
 * pinned" rather than crashing.
 */
@Singleton
class VodVersionSelectionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store get() = context.vodVersionSelectionDataStore

    // Tolerant on purpose: a blob written by a FUTURE schema (an added field)
    // still decodes on a downgraded build instead of reading as empty.
    private val json = Json { ignoreUnknownKeys = true }

    /** The relation id pinned for [key], or null when that title is on Auto. */
    suspend fun selection(key: String): Int? =
        decode(store.data.first()[KEY_ENTRIES])[key]?.relationId

    /**
     * Pin [relationId] for [key]; null CLEARS the row (the user chose Auto),
     * matching the in-memory model where Auto is the ABSENCE of a selection.
     * Skips the write when nothing would change. Over [MAX_ENTRIES] the least
     * recently updated rows are evicted.
     */
    suspend fun setSelection(key: String, relationId: Int?) {
        store.edit { prefs ->
            val current = decode(prefs[KEY_ENTRIES])
            if (current[key]?.relationId == relationId) return@edit
            if (relationId == null && !current.containsKey(key)) return@edit
            val updated = current.toMutableMap()
            if (relationId == null) {
                updated.remove(key)
            } else {
                updated[key] = VodVersionSelectionEntry(relationId, System.currentTimeMillis())
            }
            val capped: Map<String, VodVersionSelectionEntry> = if (updated.size <= MAX_ENTRIES) {
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

    private fun decode(raw: String?): Map<String, VodVersionSelectionEntry> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, VodVersionSelectionEntry>>(raw)
        }.getOrDefault(emptyMap())
    }

    companion object {
        private val KEY_ENTRIES = stringPreferencesKey("vod_version_selections_v1")

        /** Room for a heavy browser's history without unbounded growth; the
         *  blob is read and rewritten whole on every change. */
        private const val MAX_ENTRIES = 500

        /**
         * iOS storage-key parity ("<serverUUID>|<movie|series>|<itemId>"): the
         * active playlist id is the server identity here, so a title pinned on
         * one source never leaks its choice onto another.
         */
        fun storageKey(playlistId: String, itemType: VodVersionItemType, itemId: Int): String =
            "$playlistId|${itemType.key}|$itemId"
    }
}

/** Which half of the library a pinned choice belongs to. Part of the storage
 *  key because movie and series ids are separate int spaces that overlap. */
enum class VodVersionItemType(val key: String) {
    MOVIE("movie"),
    SERIES("series"),
}

/** One persisted row. [updatedAt] exists solely to order the eviction. */
@Serializable
internal data class VodVersionSelectionEntry(
    val relationId: Int,
    val updatedAt: Long = 0L,
)
