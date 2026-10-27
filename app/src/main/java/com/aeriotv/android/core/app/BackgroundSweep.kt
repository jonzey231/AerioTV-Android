package com.aeriotv.android.core.app

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
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
}
