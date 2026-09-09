package com.aeriotv.android.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.watchlistDataStore: DataStore<Preferences> by preferencesDataStore(name = "aerio_watchlist")

/**
 * Watchlist (Apple parity: WatchlistManager / WatchlistEntry). Titles the
 * user bookmarked from Movies or TV Shows, per playlist, newest first. Kept
 * as one JSON blob in its own DataStore like the other VOD stores.
 */
@Singleton
class WatchlistStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Serializable
    data class Entry(
        /** "m:<movie uuid>" or "s:<series id>". */
        val key: String,
        val title: String,
        val posterUrl: String? = null,
        val year: Int? = null,
        val rating: String? = null,
        val isMovie: Boolean = true,
        val playlistId: String? = null,
        val addedAt: Long = System.currentTimeMillis(),
        /** Tombstone for sync: set instead of deleting so the removal travels. */
        val removedAt: Long? = null,
    ) {
        val isLive: Boolean get() = removedAt == null
    }

    private val store get() = context.watchlistDataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun observe(playlistId: String?): Flow<List<Entry>> = store.data.map { prefs ->
        decode(prefs[KEY]).filter { it.isLive }
            .filter { playlistId == null || it.playlistId == null || it.playlistId == playlistId }
            .sortedByDescending { it.addedAt }
    }

    /** Every row including tombstones, for the sync snapshot. */
    suspend fun allOnce(): List<Entry> = decode(store.data.first()[KEY])

    private fun Entry.sameIdentity(o: Entry) = key == o.key && playlistId == o.playlistId

    suspend fun toggle(entry: Entry) {
        store.edit { prefs ->
            val current = decode(prefs[KEY])
            val existing = current.firstOrNull { it.sameIdentity(entry) }
            val next = when {
                existing == null -> current + entry
                existing.isLive -> current.map { if (it.sameIdentity(entry)) it.copy(removedAt = System.currentTimeMillis()) else it }
                else -> current.map { if (it.sameIdentity(entry)) entry.copy(removedAt = null) else it }
            }
            prefs[KEY] = json.encodeToString(next)
        }
    }

    suspend fun remove(key: String, playlistId: String?) {
        store.edit { prefs ->
            val now = System.currentTimeMillis()
            val current = decode(prefs[KEY])
            prefs[KEY] = json.encodeToString(
                current.map { if (it.key == key && it.playlistId == playlistId && it.isLive) it.copy(removedAt = now) else it },
            )
        }
    }

    /**
     * Merge a remote snapshot: per identity the row with the later
     * addedAt / removedAt wins, so an add on one device and a later removal
     * on another resolve the same way everywhere. Unknown rows are inserted.
     */
    suspend fun mergeRemote(remote: List<Entry>) {
        if (remote.isEmpty()) return
        store.edit { prefs ->
            val current = decode(prefs[KEY]).toMutableList()
            remote.forEach { r ->
                val i = current.indexOfFirst { it.sameIdentity(r) }
                if (i < 0) { current += r; return@forEach }
                val l = current[i]
                if (r.stamp() > l.stamp()) current[i] = r
            }
            prefs[KEY] = json.encodeToString(current)
        }
    }

    private fun Entry.stamp(): Long = maxOf(addedAt, removedAt ?: 0L)

    private fun decode(raw: String?): List<Entry> =
        raw?.let { runCatching { json.decodeFromString<List<Entry>>(it) }.getOrNull() } ?: emptyList()

    private companion object { val KEY = stringPreferencesKey("entries") }
}
