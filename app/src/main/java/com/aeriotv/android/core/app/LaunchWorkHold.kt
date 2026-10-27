package com.aeriotv.android.core.app

import android.util.Log
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
        _pinPadOpen.value = pinPads > 0
        _held.value = pickerHeld || pinPads > 0
    }

    /** Network / settle work: returns at once when not held; otherwise
     *  suspends until neither the picker nor a PIN pad holds it. */
    suspend fun await(what: String) {
        if (!_held.value) return
        Log.i(TAG, "[PROFILE] launch work deferred: $what")
        _held.first { !it }
        Log.i(TAG, "[PROFILE] launch work resumed: $what")
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
