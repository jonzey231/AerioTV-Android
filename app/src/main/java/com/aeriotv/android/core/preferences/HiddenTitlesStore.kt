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

private val Context.hiddenTitlesDataStore: DataStore<Preferences> by preferencesDataStore(name = "aerio_hidden_titles")

/**
 * Titles the user long-pressed and hid. A hidden movie or series is gone
 * from every on-demand list, shelf and search result until it is unhidden
 * from the "Hidden" category. Scoped per playlist exactly like
 * [WatchlistStore] (the server host, not the local playlist row id) and kept
 * as one JSON blob in its own DataStore like the other VOD stores.
 *
 * Sync: rides the Watchlist category through DriveSync with the same shape
 * as [WatchlistStore]. An unhide leaves a tombstone instead of deleting the
 * row, and a merge is last-writer-wins per (key, playlistId), so hiding on
 * one device and unhiding on another resolves the same way everywhere.
 */
@Singleton
class HiddenTitlesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Serializable
    data class Entry(
        /** "m:<movie uuid>" or "s:<series id>", the MediaItem key. */
        val key: String,
        val playlistId: String? = null,
        val hiddenAt: Long = System.currentTimeMillis(),
        /** Tombstone for sync: set instead of deleting so the unhide travels. */
        val unhiddenAt: Long? = null,
    ) {
        val isLive: Boolean get() = unhiddenAt == null
    }

    private val store get() = context.hiddenTitlesDataStore

    companion object {
        private val KEY = stringPreferencesKey("entries")

        /** Profiles (2026-10-16): the bare "entries" key IS the default
         *  profile (existing values stay put); another profile reads and
         *  writes "entries@prof:<id>". Drive sync runs under
         *  ProfileScope.defaultOnly(), so it keeps the bare key. */
        private fun skey() = stringPreferencesKey(
            com.aeriotv.android.core.profiles.ProfileScope.keyName("entries"),
        )

        private fun Entry.sameIdentity(o: Entry) = key == o.key && playlistId == o.playlistId

        private fun Entry.stamp(): Long = maxOf(hiddenAt, unhiddenAt ?: 0L)

        /**
         * Pure last-writer-wins merge, per (key, playlistId): the row with the
         * later hiddenAt / unhiddenAt wins, unknown rows are inserted. Kept
         * separate from the DataStore so it is unit testable.
         */
        fun mergeEntries(local: List<Entry>, remote: List<Entry>): List<Entry> {
            if (remote.isEmpty()) return local
            val merged = local.toMutableList()
            remote.forEach { r ->
                val i = merged.indexOfFirst { it.sameIdentity(r) }
                if (i < 0) { merged += r; return@forEach }
                if (r.stamp() > merged[i].stamp()) merged[i] = r
            }
            return merged
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The hidden keys for a playlist; rows with no scope match every playlist. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(playlistId: String?): Flow<Set<String>> = com.aeriotv.android.core.profiles.ProfileScope.token.flatMapLatest { store.data }.map { prefs ->
        decode(prefs[skey()])
            .filter { it.isLive }
            .filter { playlistId == null || it.playlistId == null || it.playlistId == playlistId }
            .mapTo(HashSet()) { it.key }
    }

    /** Every row including tombstones, for the sync snapshot. */
    suspend fun allOnce(): List<Entry> = decode(store.data.first()[skey()])

    suspend fun hide(key: String, playlistId: String?) {
        // Resolved here: DataStore runs the edit block on its own thread,
        // outside the caller's ProfileScope context (Drive sync).
        val k = skey()
        store.edit { prefs ->
            val now = System.currentTimeMillis()
            val current = decode(prefs[k])
            val existing = current.firstOrNull { it.key == key && it.playlistId == playlistId }
            if (existing != null && existing.isLive) return@edit
            prefs[k] = json.encodeToString(
                if (existing == null) {
                    current + Entry(key = key, playlistId = playlistId, hiddenAt = now)
                } else {
                    current.map {
                        if (it.key == key && it.playlistId == playlistId) {
                            it.copy(hiddenAt = now, unhiddenAt = null)
                        } else {
                            it
                        }
                    }
                },
            )
        }
    }

    suspend fun unhide(key: String, playlistId: String?) {
        // Resolved here: DataStore runs the edit block on its own thread,
        // outside the caller's ProfileScope context (Drive sync).
        val k = skey()
        store.edit { prefs ->
            val now = System.currentTimeMillis()
            val current = decode(prefs[k])
            var changed = false
            val next = current.map {
                if (it.key == key && (it.playlistId == playlistId || it.playlistId == null) && it.isLive) {
                    changed = true
                    it.copy(unhiddenAt = now)
                } else {
                    it
                }
            }
            if (!changed) return@edit
            prefs[k] = json.encodeToString(next)
        }
    }

    /**
     * Merge a remote snapshot: per (key, playlistId) the row with the later
     * hiddenAt / unhiddenAt wins, so a hide on one device and a later unhide
     * on another resolve the same way everywhere.
     */
    suspend fun mergeRemote(remote: List<Entry>) {
        // Resolved here: DataStore runs the edit block on its own thread,
        // outside the caller's ProfileScope context (Drive sync).
        val k = skey()
        if (remote.isEmpty()) return
        store.edit { prefs ->
            prefs[k] = json.encodeToString(mergeEntries(decode(prefs[k]), remote))
        }
    }

    /** Dispatcharr Sync pull: the hidden rows of [playlistId] become exactly [hidden] (key to hiddenAt). */
    suspend fun replaceScope(playlistId: String?, hidden: Map<String, Long>) {
        val k = skey()
        store.edit { prefs ->
            val now = System.currentTimeMillis()
            val current = decode(prefs[k]).toMutableList()
            for (i in current.indices) {
                val c = current[i]
                if (c.playlistId == playlistId && c.isLive && c.key !in hidden) current[i] = c.copy(unhiddenAt = now)
            }
            hidden.forEach { (key, at) ->
                val i = current.indexOfFirst { it.key == key && it.playlistId == playlistId }
                if (i < 0) current += Entry(key = key, playlistId = playlistId, hiddenAt = at)
                else if (!current[i].isLive) current[i] = current[i].copy(hiddenAt = at, unhiddenAt = null)
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
