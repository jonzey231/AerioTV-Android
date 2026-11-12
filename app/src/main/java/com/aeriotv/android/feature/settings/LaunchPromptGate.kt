package com.aeriotv.android.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Launch-time prompts never show over the launch PIN gate or the TV launch
 * picker (2026-11-12, security, the same rule on Apple): the gate always
 * shows and is never lost, nothing reaches an action of theirs (Edit
 * Playlist, Sign In, Update from Phone, an offer's Turn On, an update's
 * download) before the gate passes, and once it does they show with their
 * normal buttons. Pure, for the tests.
 */
object LaunchPromptGate {
    /** A pending launch-time prompt shows only once neither the gate nor the picker is up. */
    fun promptShows(pending: Boolean, gateUp: Boolean, pickerUp: Boolean): Boolean = pending && !gateUp && !pickerUp

    /**
     * The held-profile sign-in form waits for the launch PIN gate. The
     * picker is different: a profile picked there opens its form above the
     * picker (its PIN, if any, already passed), which is the pick itself.
     */
    fun heldFormShows(requested: Boolean, gateUp: Boolean): Boolean = requested && !gateUp
}

/** True while the launch PIN gate or the TV launch picker covers the app (prompts wait). */
@Composable
fun launchLocked(): Boolean {
    val held by com.aeriotv.android.core.app.LaunchWorkHold.pickerHold.collectAsStateWithLifecycle()
    return held
}
