package com.aeriotv.android.core.app

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Holds launch and switch work for two reasons.
 *
 * 1. "picker": the TV launch picker or the launch PIN gate covers the app.
 *    The heavy NETWORK work (now-airing category enrichment, the VOD sweeps
 *    and refills, the Dispatcharr Sync launch pull, the EPG network fetch,
 *    the Dispatcharr warmup login and capability probe, the settle cycle)
 *    waits in [await]. Cached paints keep running under a bare picker.
 *    Measured on the Streamer 2026-10-26: with all of it running under the
 *    picker the app sat at 244% CPU and PIN entry lagged.
 *
 * 2. "pin pad": a PIN pad is on screen (the launch gate, the picker's PIN
 *    prompt, any profile PIN dialog). EVERYTHING pauses, the cached restores
 *    and paints too ([awaitPinPad]): channels, the cached EPG, the guide
 *    install, the VOD and DVR restores. Streamer 2026-10-27 (13:37:24 to
 *    13:37:32): the cached paints under the pad skipped 33 frames, a 773 ms
 *    Davey, and a GC every second while the PIN was typed. The work resumes
 *    the moment the pad closes (ok, cancel, or back).
 *
 * ProfileManager arms the picker reason synchronously from
 * Application.onCreate (restoreSync) so no ViewModel can get past [await]
 * before the hold is armed. With no picker, no gate, and no pad nothing is
 * held and both awaits return at once.
 */
object LaunchWorkHold {
    private const val TAG = "AerioProfiles"

    private var pickerHeld = false
    private var pinPads = 0

    private val _held = MutableStateFlow(false)
    /** True while either reason holds: network and settle work waits. */
    val held: StateFlow<Boolean> = _held.asStateFlow()

    private val _pickerHold = MutableStateFlow(false)
    /** True only for the picker reason (launch picker or launch PIN gate), never for an in-app PIN pad. */
    val pickerHold: StateFlow<Boolean> = _pickerHold.asStateFlow()

    private val _pinPadOpen = MutableStateFlow(false)
    /** True while a PIN pad is on screen: cached work waits too. */
    val pinPadOpen: StateFlow<Boolean> = _pinPadOpen.asStateFlow()

    /** True once the launch picker or PIN gate held launch work this
     *  process. The work it released runs at once (the user already waited
     *  behind the picker), never behind the guide-paint wait. */
    @Volatile var heldThisLaunch: Boolean = false
        private set

    /** The "picker" reason (launch picker or launch PIN gate). */
    @Synchronized
    fun set(hold: Boolean) {
        if (pickerHeld == hold) return
        pickerHeld = hold
        if (hold) heldThisLaunch = true
        Log.i(TAG, if (hold) "[PROFILE] launch work deferred until the picker closes" else "[PROFILE] launch work resumed")
        publish()
    }

    /** A PIN pad opened. Balanced by [pinPadClosed]; pads may stack. */
    @Synchronized
    fun pinPadOpened() {
        pinPads++
        if (pinPads == 1) {
            Log.i(TAG, "[PROFILE] launch work paused: pin pad")
            publish()
        }
    }

    /** A PIN pad closed (ok, cancel, back, or left composition). */
    @Synchronized
    fun pinPadClosed() {
        if (pinPads == 0) return
        pinPads--
        if (pinPads == 0) {
            Log.i(TAG, "[PROFILE] launch work resumed: pin pad closed")
            publish()
        }
    }

    private fun publish() {
        val wasHeld = _held.value
        val nowHeld = pickerHeld || pinPads > 0
        // Stamp the release BEFORE the flows flip. Waiters resume on other
        // threads the instant _held drops; reading resumedAtMs before this
        // write was the 2026-10-27 14:38:02 bug: they saw 0 ("never held"),
        // returned at once, and every held job resumed in the same 20 ms.
        if (wasHeld && !nowHeld) {
            synchronized(stagesLogged) { stagesLogged.clear() }
            resumedAtMs = SystemClock.uptimeMillis()
        }
        _pinPadOpen.value = pinPads > 0
        _pickerHold.value = pickerHeld
        _held.value = nowHeld
    }

    /** Uptime of the last held -> released flip (0 = never held). */
    @Volatile private var resumedAtMs = 0L

    /** Uptime of the last D-pad / key event (root key interceptor). */
    @Volatile var lastKeyAtMs = 0L
        private set

    private val stagesLogged = HashSet<Int>()

    /** Called for every key event so staged resumes can wait for input to go quiet. */
    fun noteKey() {
        lastKeyAtMs = SystemClock.uptimeMillis()
    }

    /** True inside the staged-resume window that follows a release. */
    fun inResumeWindow(): Boolean {
        val r = resumedAtMs
        return r != 0L && SystemClock.uptimeMillis() - r <= STAGE_WINDOW_MS
    }

    /** True for [BACKGROUND_BOUND_MS] after a release: background I/O stays
     *  serial and low priority (see [BackgroundSweep.serial]). */
    fun inPostReleaseBound(): Boolean {
        val r = resumedAtMs
        return r != 0L && SystemClock.uptimeMillis() - r <= BACKGROUND_BOUND_MS
    }

    /**
     * The ONE resume coordinator. Every held launch job comes through here:
     * it waits while held (silently), then, inside [STAGE_WINDOW_MS] of the
     * release, until no key arrived for [idleMs] (measured from the release
     * or the last key, whichever is later), capped at [STAGE_MAX_EXTRA_MS]
     * past the idle target. The stage line is logged before any of its
     * jobs' "launch work resumed: <job>" lines. Stages: 1 cached restores
     * and settle (no wait), 2 EPG fetch (1.5 s quiet), 3 warmup / probe /
     * sync / channel refresh (3 s quiet), 4 VOD art pass, hero art, and
     * sweeps (5 s quiet). Without a hold this launch it returns at once.
     */
    suspend fun awaitStage(stage: Int, name: String, what: String, idleMs: Long) {
        if (!_held.value && !inResumeWindow()) return
        var deferredLogged = false
        while (true) {
            if (_held.value) {
                if (!deferredLogged) {
                    Log.i(TAG, "[PROFILE] launch work deferred: $what")
                    deferredLogged = true
                }
                _held.first { !it }
            }
            val resumed = resumedAtMs
            if (resumed == 0L || SystemClock.uptimeMillis() - resumed > STAGE_WINDOW_MS) break
            val deadline = resumed + idleMs + STAGE_MAX_EXTRA_MS
            val now = SystemClock.uptimeMillis()
            val quietSince = maxOf(resumed, lastKeyAtMs)
            val wait = minOf(quietSince + idleMs, deadline) - now
            if (wait <= 0) break
            delay(wait)
        }
        val first = synchronized(stagesLogged) { stagesLogged.add(stage) }
        if (first) Log.i(TAG, "[PROFILE] resume stage $stage: $name")
        if (deferredLogged) Log.i(TAG, "[PROFILE] launch work resumed: $what")
    }

    /**
     * Per-iteration checkpoint for long launch loops (TMDB details and art
     * passes, hero backdrops): suspends while a PIN pad is open or the
     * picker holds, then re-enters the job's stage, so a pad that opens
     * mid-loop stops it at the next item.
     */
    suspend fun checkpoint(stage: Int, name: String, what: String, idleMs: Long) {
        if (!_held.value && !inResumeWindow()) return
        awaitStage(stage, name, what, idleMs)
    }

    const val STAGE_CACHED = 1
    const val STAGE_EPG = 2
    const val STAGE_NETWORK = 3
    const val STAGE_VOD = 4
    const val IDLE_EPG_MS = 1_500L
    const val IDLE_NETWORK_MS = 3_000L
    const val IDLE_VOD_MS = 5_000L
    private const val STAGE_WINDOW_MS = 30_000L
    private const val STAGE_MAX_EXTRA_MS = 12_000L
    private const val BACKGROUND_BOUND_MS = 30_000L
    const val STAGE_CACHED_NAME = "cached restores and settle"
    const val STAGE_EPG_NAME = "EPG fetch, compare, prune, and backfill"
    const val STAGE_NETWORK_NAME = "warmup, probe, sync, and channel refresh"
    const val STAGE_VOD_NAME = "VOD art pass, hero art, series episodes, TMDB details, and sweeps"

    /** Stage 4 shorthand for the VOD / TMDB launch jobs. */
    suspend fun awaitVod(what: String) = awaitStage(STAGE_VOD, STAGE_VOD_NAME, what, IDLE_VOD_MS)
    suspend fun checkpointVod(what: String) = checkpoint(STAGE_VOD, STAGE_VOD_NAME, what, IDLE_VOD_MS)

    /** Network / settle work: returns at once when not held; otherwise
     *  suspends until neither the picker nor a PIN pad holds it. */
    suspend fun await(what: String) {
        if (!_held.value) return
        // Everything held resumes through the coordinator (stage 1 default).
        awaitStage(STAGE_CACHED, STAGE_CACHED_NAME, what, 0L)
    }

    /** Cached restores and paints: suspend only while a PIN pad is open
     *  (a bare picker lets them run). Called at stage boundaries, so a
     *  pad that opens mid-restore stops it at the next one. */
    suspend fun awaitPinPad(what: String) {
        if (!_pinPadOpen.value) return
        Log.i(TAG, "[PROFILE] launch work paused under pin pad: $what")
        _pinPadOpen.first { !it }
        Log.i(TAG, "[PROFILE] launch work resumed after pin pad: $what")
    }
}
