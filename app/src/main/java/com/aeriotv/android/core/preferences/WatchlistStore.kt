package com.aeriotv.android.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
    )

    private val store get() = context.watchlistDataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun observe(playlistId: String?): Flow<List<Entry>> = store.data.map { prefs ->
        decode(prefs[KEY]).filter { playlistId == null || it.playlistId == null || it.playlistId == playlistId }
            .sortedByDescending { it.addedAt }
    }

    suspend fun toggle(entry: Entry) {
        store.edit { prefs ->
            val current = decode(prefs[KEY])
            val next = if (current.any { it.key == entry.key && it.playlistId == entry.playlistId }) {
                current.filterNot { it.key == entry.key && it.playlistId == entry.playlistId }
            } else {
                current + entry
            }
            prefs[KEY] = json.encodeToString(next)
        }
    }

    suspend fun remove(key: String, playlistId: String?) {
        store.edit { prefs ->
            val current = decode(prefs[KEY])
            prefs[KEY] = json.encodeToString(current.filterNot { it.key == key && it.playlistId == playlistId })
        }
    }

    private fun decode(raw: String?): List<Entry> =
        raw?.let { runCatching { json.decodeFromString<List<Entry>>(it) }.getOrNull() } ?: emptyList()

    private companion object { val KEY = stringPreferencesKey("entries") }
}
