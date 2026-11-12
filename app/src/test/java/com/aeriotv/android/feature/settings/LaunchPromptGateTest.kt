package com.aeriotv.android.feature.settings

import com.aeriotv.android.feature.devicesetup.setupSendAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Launch-time prompts wait for the launch PIN gate and the launch picker
 * (2026-11-12, security): none shows over them, none of their actions is
 * reachable before the gate passes, and they then show with their normal
 * buttons.
 */
class LaunchPromptGateTest {

    @Test fun aRejectedKeyAtLaunchWaitsForThePinGate() {
        // The key was refused at launch while the PIN gate is up: nothing shows.
        assertFalse(LaunchPromptGate.promptShows(pending = true, gateUp = true, pickerUp = false))
        // TV: the same under the launch picker.
        assertFalse(LaunchPromptGate.promptShows(pending = true, gateUp = false, pickerUp = true))
        // The gate passed: the prompt shows, with its normal buttons (Sign In
        // is offered again because the app is no longer locked).
        assertTrue(LaunchPromptGate.promptShows(pending = true, gateUp = false, pickerUp = false))
        val canSignIn = setupSendAllowed(locked = false, canSetUpTv = true)
        assertEquals(listOf("Sign In", "Edit Playlist", "Not Now"), KeyRejectedCopy.buttons(isTv = false, canSignIn = canSignIn).map(KeyRejectedCopy::label))
        // Nothing pending, nothing shown.
        assertFalse(LaunchPromptGate.promptShows(pending = false, gateUp = false, pickerUp = false))
    }

    @Test fun theHeldSignInFormWaitsForTheGateButNotForThePickersOwnPick() {
        assertFalse(LaunchPromptGate.heldFormShows(requested = true, gateUp = true))
        assertTrue(LaunchPromptGate.heldFormShows(requested = true, gateUp = false))
        assertFalse(LaunchPromptGate.heldFormShows(requested = false, gateUp = false))
    }
}
