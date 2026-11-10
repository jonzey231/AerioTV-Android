package com.aeriotv.android.feature.devicesetup

import com.aeriotv.android.core.setup.SetupTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The setup link and send gate: only a profile that may set up a device, never while locked. */
class SetupLinkRulesTest {
    private fun action(isTv: Boolean = false, locked: Boolean = false, starting: Boolean = false, can: Boolean = true) =
        setupLinkAction(isTv = isTv, locked = locked, starting = starting, canSetUpTv = can)

    @Test fun adminOrDefaultProfileOpens() {
        assertEquals(SetupLinkAction.Open, action())
    }

    @Test fun profileThatMayNotSetUpIsDroppedSilently() {
        assertEquals(SetupLinkAction.Drop, action(can = false))
    }

    @Test fun noActivePlaylistIsDropped() {
        // No playlist: the caller passes canSetUpTv = false, as Settings hides the row.
        assertEquals(SetupLinkAction.Drop, action(can = false, starting = false))
    }

    @Test fun lockedHoldsWhateverTheProfile() {
        // The profile behind the PIN screen is not decided yet: hold, never open.
        assertEquals(SetupLinkAction.Hold, action(locked = true, can = true))
        assertEquals(SetupLinkAction.Hold, action(locked = true, can = false))
    }

    @Test fun coldStartHoldsUntilThePlaylistIsKnown() {
        assertEquals(SetupLinkAction.Hold, action(starting = true))
    }

    @Test fun afterUnlockTheUnlockingProfileDecides() {
        assertEquals(SetupLinkAction.Hold, action(locked = true, can = false))
        assertEquals(SetupLinkAction.Drop, action(locked = false, can = false))
        assertEquals(SetupLinkAction.Open, action(locked = false, can = true))
    }

    @Test fun tvNeverOpensTheSender() {
        assertEquals(SetupLinkAction.Drop, action(isTv = true))
        assertEquals(SetupLinkAction.Drop, action(isTv = true, locked = true))
    }

    @Test fun sendNeedsAnUnlockedAllowedProfile() {
        assertTrue(setupSendAllowed(locked = false, canSetUpTv = true))
        assertFalse(setupSendAllowed(locked = false, canSetUpTv = false))
        assertFalse(setupSendAllowed(locked = true, canSetUpTv = true))
        assertFalse(setupSendAllowed(locked = true, canSetUpTv = false))
    }

    @Test fun newerLinkReplacesUnlessASendIsUnderWay() {
        val t = SetupTarget("TV", "192.0.2.1", 9, ByteArray(32), code = "123456")
        val steps = listOf(
            SetUpTvViewModel.Step.Pick,
            SetUpTvViewModel.Step.Scope(t),
            SetUpTvViewModel.Step.Code(t),
            SetUpTvViewModel.Step.Choose(t, "123456"),
            SetUpTvViewModel.Step.Credential(t, "p", "user"),
            SetUpTvViewModel.Step.KeyRejected("pl", SetUpTvViewModel.Step.Pick),
            SetUpTvViewModel.Step.Error("x", SetUpTvViewModel.Step.Pick),
            SetUpTvViewModel.Step.Sent,
        )
        steps.forEach { assertTrue("$it", setupLinkMayReplace(it)) }
        assertFalse(setupLinkMayReplace(SetUpTvViewModel.Step.Sending))
        assertFalse(setupLinkMayReplace(SetUpTvViewModel.Step.Checking("Checking the playlist sign-in")))
    }
}
