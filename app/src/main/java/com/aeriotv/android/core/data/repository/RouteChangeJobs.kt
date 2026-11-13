package com.aeriotv.android.core.data.repository

import kotlinx.coroutines.CancellationException

/**
 * A long-running guide job's request would go to an address the playlist no
 * longer uses, or while its address is changing. A cancellation, so the job
 * simply ends.
 */
internal class RouteMoved(message: String) : CancellationException(message)

/**
 * The check every long-running per-playlist request makes right before it
 * goes out (Nothing Phone 2026-11-13: after Edit Playlist moved a playlist to
 * another server, the guide's background re-sweep kept paging the OLD
 * address, and since the key is read from the playlist at each request, it
 * sent the NEW server's key there, three 401s toward that server's
 * failed-key lockout).
 *
 * A job reads its address once, when it starts. Its request may go out only
 * while no address change is under way on the playlist and that address is
 * still one of the playlist's own (its URL or LAN URL): the key it carries is
 * the playlist's current one, so the address must be current too.
 */
internal class RouteGuard(
    /** True from the start of an address change on the playlist until it lands. */
    private val isChanging: (playlistId: String) -> Boolean,
    /** The playlist's URL and LAN URL now; null when the playlist is gone. */
    private val addresses: suspend (playlistId: String) -> Pair<String, String?>?,
) {
    /** Throws [RouteMoved] when [base] may not be used for [job] on [playlistId] now. */
    suspend fun ensure(playlistId: String, base: String, job: String) {
        if (isChanging(playlistId)) {
            throw RouteMoved("$job for ${playlistId.take(8)} stopped: its server address is changing")
        }
        val now = addresses(playlistId)
        if (now == null || !sameRoute(base, now.first, now.second)) {
            throw RouteMoved("$job for ${playlistId.take(8)} stopped: its server address changed")
        }
    }

    companion object {
        private fun norm(url: String?) = url?.trim()?.trimEnd('/')?.lowercase()?.takeIf { it.isNotEmpty() }

        /** [base] is the playlist's URL or its LAN URL. */
        fun sameRoute(base: String, url: String, lanUrl: String?): Boolean {
            val b = norm(base) ?: return false
            return b == norm(url) || b == norm(lanUrl)
        }
    }
}

/**
 * Long-running per-playlist jobs across a server address change (Apple
 * cdfab66 RouteChangeJobs): the running ones stop when the change starts,
 * one asked for during the change waits, and when the change lands (or its
 * save fails) the stopped and waiting ones start again at the current
 * address, unless one of the same kind is running by then. A job is a [kind]
 * with the action that starts it again.
 */
internal class RouteChangeJobs {
    private val pending = HashMap<String, LinkedHashMap<String, () -> Unit>>()

    /** A change on [playlistId] starts. [running]: its jobs running now, by kind. The kinds to stop. */
    @Synchronized
    fun onStart(playlistId: String, running: Map<String, () -> Unit>): Set<String> {
        if (running.isEmpty()) return emptySet()
        pending.getOrPut(playlistId) { LinkedHashMap() }.putAll(running)
        return running.keys
    }

    /** A job of [kind] is asked for. True (it waits for the landing) while [changing]. */
    @Synchronized
    fun hold(playlistId: String, changing: Boolean, kind: String, start: () -> Unit): Boolean {
        if (!changing) return false
        pending.getOrPut(playlistId) { LinkedHashMap() }[kind] = start
        return true
    }

    /** The change on [playlistId] landed. The jobs to start now; [running] kinds are skipped. */
    @Synchronized
    fun onLanded(playlistId: String, running: Set<String>): List<Pair<String, () -> Unit>> =
        pending.remove(playlistId).orEmpty().filterKeys { it !in running }.toList()

    /** Kinds waiting on [playlistId] (for logs and tests). */
    @Synchronized
    fun waiting(playlistId: String): Set<String> = pending[playlistId]?.keys?.toSet().orEmpty()

    companion object {
        const val SWEEP = "guide re-sweep"
        const val WALK = "guide window walk"
        const val LAYERING = "guide source layering"
    }
}
