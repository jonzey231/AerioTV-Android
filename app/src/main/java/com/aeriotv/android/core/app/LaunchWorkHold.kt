package com.aeriotv.android.core.app

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Holds the heavy launch work (now-airing category enrichment, the VOD
 * catalog restore and refill, the Dispatcharr Sync launch pull, the EPG
 * network fetch, the settle cycle) while the TV launch picker or the launch
 * PIN gate covers the app. Measured on the Streamer 2026-10-26: with all of
 * it running under the picker the app sat at 244% CPU and PIN entry lagged.
 *
 * ProfileManager sets it synchronously from Application.onCreate (restoreSync)
 * so no ViewModel can get past [await] before the hold is armed. With no
 * picker and no gate it is never held and [await] returns at once, so a
 * launch without a picker behaves exactly as before.
 */
object LaunchWorkHold {
    private const val TAG = "AerioProfiles"
    private val _held = MutableStateFlow(false)
    val held: StateFlow<Boolean> = _held.asStateFlow()

    fun set(hold: Boolean) {
        if (_held.value == hold) return
        _held.value = hold
        Log.i(TAG, if (hold) "[PROFILE] launch work deferred until the picker closes" else "[PROFILE] launch work resumed")
    }

    /** Returns at once when not held; otherwise suspends until released. */
    suspend fun await(what: String) {
        if (!_held.value) return
        Log.i(TAG, "[PROFILE] launch work deferred: $what")
        _held.first { !it }
        Log.i(TAG, "[PROFILE] launch work resumed: $what")
    }
}
