package com.aeriotv.android.core.data.repository

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * Playlist delete crash guard (Apple d051505 parity, TestFlight 1.8.41
 * "Deleting playlist crashed app"). A delete marks the id here FIRST, before
 * any cache purge or the row delete, so a channel, EPG or VOD task that was
 * already running for that playlist can never write rows for it after its next
 * suspension (orphan rows, or a foreign-key failure on the cascaded tables).
 *
 * Writers call [ensureAlive] right before they touch the database; it throws
 * [CancellationException], which ends the calling coroutine quietly instead of
 * crashing it. Ids are never removed: playlist ids are random UUIDs, so a
 * deleted id is never reused.
 */
object DeletedPlaylists {
    private const val TAG = "PlaylistDelete"
    private val ids = ConcurrentHashMap.newKeySet<String>()

    fun mark(playlistId: String) {
        ids.add(playlistId)
    }

    fun isDeleted(playlistId: String?): Boolean = playlistId != null && playlistId in ids

    /** Throws [CancellationException] when [playlistId] was deleted. */
    fun ensureAlive(playlistId: String?, task: String) {
        if (isDeleted(playlistId)) {
            Log.i(TAG, "[PLAYLIST] delete: dropped late $task write for deleted playlist ${playlistId?.take(8)}")
            throw CancellationException("playlist $playlistId was deleted")
        }
    }
}
