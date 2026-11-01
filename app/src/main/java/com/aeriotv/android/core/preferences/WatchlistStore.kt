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
import kotlinx.coroutines.flow.flatMapLatest
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

    companion object {
        /**
         * Cross-device scope for a playlist: the server host, not the local
         * playlist row id (each device assigns its own ids, so a synced entry
         * would never match). Mirrors the iOS serverID role. Null when the URL
         * has no host, which matches every playlist.
         */
        fun scopeFor(p: com.aeriotv.android.core.data.db.entity.PlaylistEntity): String? =
            runCatching { java.net.URI(p.urlString.trim()).host?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }

        private val KEY = stringPreferencesKey("entries")

        /** Profiles (2026-10-16): the bare "entries" key IS the default
         *  profile (existing values stay put); another profile reads and
         *  writes "entries@prof:<id>". Drive sync runs under
         *  ProfileScope.defaultOnly(), so it keeps the bare key. */
        private fun skey() = stringPreferencesKey(
            com.aeriotv.android.core.profiles.ProfileScope.keyName("entries"),
        )
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(playlistId: String?): Flow<List<Entry>> = com.aeriotv.android.core.profiles.ProfileScope.token.flatMapLatest { store.data }.map { prefs ->
        decode(prefs[skey()]).filter { it.isLive }
            .filter { playlistId == null || it.playlistId == null || it.playlistId == playlistId }
            .sortedByDescending { it.addedAt }
    }

    /** Every row including tombstones, for the sync snapshot. */
    suspend fun allOnce(): List<Entry> = decode(store.data.first()[skey()])

    private fun Entry.sameIdentity(o: Entry) = key == o.key && playlistId == o.playlistId

    suspend fun toggle(entry: Entry) {
        // Resolved here: DataStore runs the edit block on its own thread,
        // outside the caller's ProfileScope context (Drive sync).
        val k = skey()
        store.edit { prefs ->
            val current = decode(prefs[k])
            val existing = current.firstOrNull { it.sameIdentity(entry) }
            val next = when {
                existing == null -> current + entry
                existing.isLive -> current.map { if (it.sameIdentity(entry)) it.copy(removedAt = System.currentTimeMillis()) else it }
                else -> current.map { if (it.sameIdentity(entry)) entry.copy(removedAt = null) else it }
            }
            prefs[k] = json.encodeToString(next)
        }
    }

    suspend fun remove(key: String, playlistId: String?) {
        // Resolved here: DataStore runs the edit block on its own thread,
        // outside the caller's ProfileScope context (Drive sync).
        val k = skey()
        store.edit { prefs ->
            val now = System.currentTimeMillis()
            val current = decode(prefs[k])
            prefs[k] = json.encodeToString(
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
        // Resolved here: DataStore runs the edit block on its own thread,
        // outside the caller's ProfileScope context (Drive sync).
        val k = skey()
        if (remote.isEmpty()) return
        store.edit { prefs ->
            val current = decode(prefs[k]).toMutableList()
            remote.forEach { r ->
                val i = current.indexOfFirst { it.sameIdentity(r) }
                if (i < 0) { current += r; return@forEach }
                val l = current[i]
                if (r.stamp() > l.stamp()) current[i] = r
            }
            prefs[k] = json.encodeToString(current)
        }
    }

    private fun Entry.stamp(): Long = maxOf(addedAt, removedAt ?: 0L)

    /**
     * Dispatcharr Sync pull (full replace for one playlist scope): rows of
     * [playlistId] become exactly [live]; a live row absent from it gets a
     * removedAt tombstone, a missing or removed one is added or revived.
     * Title and art stay from the local row when one exists. Other scopes
     * are untouched.
     */
    suspend fun replaceScope(playlistId: String?, live: List<Entry>) {
        val k = skey()
        store.edit { prefs ->
            val now = System.currentTimeMillis()
            val current = decode(prefs[k]).toMutableList()
            val wanted = live.associateBy { it.key }
            for (i in current.indices) {
                val c = current[i]
                if (c.playlistId != playlistId) continue
                if (c.isLive && c.key !in wanted) current[i] = c.copy(removedAt = now)
            }
            wanted.values.forEach { w ->
                val i = current.indexOfFirst { it.key == w.key && it.playlistId == playlistId }
                if (i < 0) current += w.copy(playlistId = playlistId, removedAt = null)
                else if (!current[i].isLive) current[i] = current[i].copy(addedAt = w.addedAt, removedAt = null)
            }
            prefs[k] = json.encodeToString(current)
        }
    }


    /** Rule 4 replace (DriveCanon.replaceMarks): every row, tombstones included, becomes [transform] of the current rows. */
    suspend fun rewrite(transform: (List<Entry>) -> List<Entry>) {
        val k = skey()
        store.edit { prefs -> prefs[k] = json.encodeToString(transform(decode(prefs[k]))) }
    }

    /** Remove Profile cleanup. */
    suspend fun deleteScope(profileId: String) {
        store.edit { it.remove(stringPreferencesKey(com.aeriotv.android.core.profiles.ProfileScope.keyName("entries", profileId))) }
    }

    private fun decode(raw: String?): List<Entry> =
        raw?.let { runCatching { json.decodeFromString<List<Entry>>(it) }.getOrNull() } ?: emptyList()

}
