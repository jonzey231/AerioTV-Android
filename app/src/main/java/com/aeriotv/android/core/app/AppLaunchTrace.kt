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
    @Volatile private var vodAtMs = 0L
    @Volatile private var vodDecodeMs = 0L
    @Volatile private var dvrAtMs = 0L
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

    /**
     * The saved Movies / TV Shows library is on screen. [decodeMs] is how long
     * the snapshot file took to read and decode, which is the part a slow box
     * pays: the Streamer spent 3.1 s on it (gtvlogs/session7.txt, restore at
     * 14:29:32.223 against a settle at 14:29:29.121).
     */
    fun noteVodRestored(decodeMs: Long) {
        if (vodAtMs == 0L) {
            vodAtMs = sinceStart()
            vodDecodeMs = decodeMs
        }
    }

    /** The saved DVR recordings are on screen. */
    fun noteDvrRestored() {
        if (dvrAtMs == 0L) dvrAtMs = sinceStart()
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
        val dvr = if (dvrAtMs == 0L) "n/a" else "+${dvrAtMs}ms"
        val vod = if (vodAtMs == 0L) "n/a" else "+${vodAtMs}ms (decode ${vodDecodeMs}ms)"
        Log.i(
            TAG,
            "[LAUNCH] channels $channels, guide programs $programs, dvr $dvr, " +
                "movies/tv $vod, settle +${settledAtMs}ms",
        )
    }
}
