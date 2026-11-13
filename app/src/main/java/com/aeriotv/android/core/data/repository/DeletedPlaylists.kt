package com.aeriotv.android.core.data.repository

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext

/**
 * Playlist delete crash guard (Apple d051505 parity, TestFlight 1.8.41
 * "Deleting playlist crashed app"). A delete marks the id here FIRST, before
 * any cache purge or the row delete, so a channel, EPG or VOD task that was
 * already running for that playlist can never write rows for it after its next
 * suspension (orphan rows, or a foreign-key failure on the cascaded tables).
 *
 * Writers call [ensureAlive] right before they touch the database; it throws
 * [CancellationException], which ends the calling coroutine quietly instead of
 * crashing it.
 *
 * Ids ARE reused (2026-11-12, measured on a phone): a setup from another
 * device re-creates a playlist with the sender's id, and Drive restore,
 * Dispatcharr Sync, and imports can too. The old rule ("a deleted id is never
 * reused") left the re-added playlist with every channel, guide, and VOD
 * write dropped until the app restarted. So each id has an INSTANCE number:
 * a delete records the instance it removed, and a row created again with
 * that id ([reborn], from the playlist DAO's upsert) starts the next one.
 * Writes are dropped while the id is deleted, and also when the task that
 * makes them started under an earlier instance ([startedInstance], or the
 * [Instance] coroutine element its launch carried): a late write from the
 * deleted playlist's task never lands in the new one, and the new one's
 * writes go through.
 */
object DeletedPlaylists {
    private const val TAG = "PlaylistDelete"

    /** Log sink (tests replace it; android.util.Log is not available on the JVM). */
    @Volatile internal var logSink: ((String) -> Unit)? = null
    private fun log(line: String) = logSink?.invoke(line) ?: run { Log.i(TAG, line); Unit }

    /** Deleted ids, with the instance each delete removed. */
    private val deleted = ConcurrentHashMap<String, Long>()

    /** The live instance of each id created again after a delete (absent = 0). */
    private val live = ConcurrentHashMap<String, Long>()

    /** [playlistId]'s current instance: what a task captures when it starts. */
    fun instance(playlistId: String?): Long = playlistId?.let { live[it] } ?: 0L

    fun mark(playlistId: String) {
        deleted[playlistId] = instance(playlistId)
    }

    /**
     * A playlist row with [playlistId] was written as a new row (the DAO's
     * upsert): if the id was deleted, this is a new instance of it. A no-op
     * for an id that was never deleted.
     */
    fun reborn(playlistId: String) {
        val gone = deleted.remove(playlistId) ?: return
        live[playlistId] = gone + 1
        log("[PLAYLIST] ${playlistId.take(8)} added again after a delete; tasks of the deleted one stay blocked")
    }

    fun isDeleted(playlistId: String?): Boolean = playlistId != null && deleted.containsKey(playlistId)

    /**
     * Throws [CancellationException] when [playlistId] is deleted, or when
     * the task writing started under an earlier instance of it
     * ([startedInstance], else the calling coroutine's [Instance] element).
     */
    suspend fun ensureAlive(playlistId: String?, task: String, startedInstance: Long? = null) {
        val started = startedInstance ?: currentCoroutineContext()[Instance]?.takeIf { it.playlistId == playlistId }?.instance
        check(playlistId, task, started)
    }

    /** The rule behind [ensureAlive], for callers outside a coroutine and for the tests. */
    fun check(playlistId: String?, task: String, startedInstance: Long?) {
        if (playlistId == null) return
        val stale = startedInstance != null && startedInstance != instance(playlistId)
        if (isDeleted(playlistId) || stale) {
            log("[PLAYLIST] delete: dropped late $task write for deleted playlist ${playlistId.take(8)}")
            throw CancellationException("playlist $playlistId was deleted")
        }
    }

    /** The instance a background task for [playlistId] started under: add it to the task's launch context. */
    fun started(playlistId: String): Instance = Instance(playlistId, instance(playlistId))

    class Instance(val playlistId: String, val instance: Long) : AbstractCoroutineContextElement(Instance) {
        companion object Key : CoroutineContext.Key<Instance>
    }

    /** Test hook. */
    internal fun resetForTests() {
        deleted.clear()
        live.clear()
    }
}
