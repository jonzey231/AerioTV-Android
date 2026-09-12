package com.aeriotv.android.core.app

import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * One-line launch budget, in the shape of the player's [TUNE] line.
 *
 * Logan 2026-09-12, after two rounds of guessing which launch stage was
 * costing the Streamer its twenty seconds: every stage now stamps itself
 * against process start and the last one to arrive prints
 *
 *   [LAUNCH] channels +Nms, guide programs +Nms (rows R), settle +Nms
 *
 * so a captured log answers "what took so long" without anyone correlating
 * timestamps by eye. Process-relative, not activity-relative: a warm relaunch
 * that reuses the process reports small numbers, which is the honest answer.
 * Sticky for the process; a foreground return does not reprint it.
 */
object AppLaunchTrace {
    private const val TAG = "AerioTrace"

    /** Elapsed-realtime at process start, from the kernel's own view. */
    private val processStartMs: Long = Process.getStartElapsedRealtime()

    @Volatile private var channelsAtMs = 0L
    @Volatile private var programsAtMs = 0L
    @Volatile private var programRows = 0
    @Volatile private var settledAtMs = 0L
    @Volatile private var printed = false

    private fun sinceStart(): Long = SystemClock.elapsedRealtime() - processStartMs

    /** The channel list is on screen. */
    fun noteChannels() {
        if (channelsAtMs == 0L) channelsAtMs = sinceStart()
    }

    /** The cached guide programmes are installed in the catalog. */
    fun noteGuidePrograms(rows: Int) {
        if (programsAtMs == 0L) {
            programsAtMs = sinceStart()
            programRows = rows
        }
        maybePrint()
    }

    /** [AppSettleGate] has declared the launch quiet. */
    fun noteSettled() {
        if (settledAtMs == 0L) settledAtMs = sinceStart()
        maybePrint()
    }

    /**
     * Prints once both the guide programmes and the settle signal have landed.
     * A launch that never opens the guide still settles (the gate caps its
     * wait), and prints with the programme stage marked "n/a".
     */
    private fun maybePrint() {
        if (printed) return
        if (settledAtMs == 0L) return
        printed = true
        val programs =
            if (programsAtMs == 0L) "n/a" else "+${programsAtMs}ms (rows $programRows)"
        val channels = if (channelsAtMs == 0L) "n/a" else "+${channelsAtMs}ms"
        Log.i(TAG, "[LAUNCH] channels $channels, guide programs $programs, settle +${settledAtMs}ms")
    }
}
