package com.aeriotv.android.core.app

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.Executors

/**
 * One shared, deliberately slow lane for the quiet background refreshes (VOD
 * library sweep, DVR recordings, EPG sweep). Single threaded so two sweeps can
 * never run at once, and parked at THREAD_PRIORITY_LOWEST so the scheduler
 * hands the CPU to the guide, the decoder and input first on weak boxes (the
 * Onn starvation class of bug, GH #84/#91).
 */
object BackgroundSweep {
    val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { body ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
                body.run()
            }, "aerio-bg-sweep").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    /**
     * Foreground counterpart: a normal-priority single thread. A sweep the
     * user is waiting on still maps pages and builds rows off the main
     * thread (review 2026-10-27), and a single thread keeps the same
     * one-at-a-time confinement the sweep state relies on in the background.
     */
    val foregroundDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { body ->
            Thread(body, "aerio-fg-sweep").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    /**
     * Post-PIN I/O bound (Streamer 2026-10-27 14:39: Room's four
     * arch_disk_io threads at 20 + 16 + 14 + 11 % on a four-core box while
     * the EPG compare, prune, backfill, EPG sweep, and VOD sweep ran at
     * once). Each of those units runs here: one at a time (a shared lock),
     * on a THREAD_PRIORITY_BACKGROUND thread, with a yield after each, so
     * input and the guide keep the cores.
     */
    private val serialLock = Mutex()

    val serialDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { body ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                body.run()
            }, "aerio-bg-serial").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    suspend fun <T> serial(block: suspend () -> T): T =
        serialLock.withLock {
            val out = withContext(serialDispatcher) { block() }
            yield()
            out
        }
}
