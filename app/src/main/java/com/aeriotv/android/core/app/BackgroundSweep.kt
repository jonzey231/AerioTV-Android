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
}
