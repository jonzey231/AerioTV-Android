package com.aeriotv.android.feature.ondemand

/**
 * On Demand catalog sweeps across a server address change (stuck save
 * 2026-11-12: a sweep went on paging the OLD server for minutes after Edit
 * Playlist moved the playlist to another address).
 *
 * A sweep reads the playlist row once, when it starts, and walks that
 * address to the end. So while a change is in progress on the active
 * playlist, no sweep walks it: the running ones stop when the change
 * starts, and one asked for during the change (a profile switch the change
 * caused, a pull to refresh) waits. When the change lands, the stopped and
 * waiting sweeps start at the current address, unless one is running by
 * then.
 */
internal class RouteStoppedSweeps {
    data class Sweeps(val movies: Boolean, val series: Boolean) {
        val any: Boolean get() = movies || series
        operator fun plus(o: Sweeps) = Sweeps(movies || o.movies, series || o.series)
    }

    private val stopped = HashMap<String, Sweeps>()

    /** A change on [playlistId] starts: the running sweeps to stop now. */
    fun onStart(playlistId: String, activeId: String?, running: Sweeps): Sweeps {
        if (playlistId != activeId || !running.any) return NONE
        stopped[playlistId] = (stopped[playlistId] ?: NONE) + running
        return running
    }

    /**
     * A sweep of [movies] or series is about to start on [activeId]. True
     * when its address is [changing]: it waits for the landing instead.
     */
    fun holdStart(activeId: String?, changing: Boolean, movies: Boolean): Boolean {
        if (activeId == null || !changing) return false
        stopped[activeId] = (stopped[activeId] ?: NONE) + Sweeps(movies, !movies)
        return true
    }

    /** The change on [playlistId] landed (or its save failed): the sweeps to start now. */
    fun onLanded(playlistId: String, activeId: String?, running: Sweeps): Sweeps {
        val s = stopped.remove(playlistId) ?: return NONE
        if (playlistId != activeId) return NONE
        return Sweeps(s.movies && !running.movies, s.series && !running.series)
    }

    private companion object {
        val NONE = Sweeps(false, false)
    }
}
