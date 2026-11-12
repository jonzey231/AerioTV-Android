package com.aeriotv.android.core.profiles

import com.aeriotv.android.core.network.DispatcharrRequestGate
import com.aeriotv.android.core.setup.FixLogin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Choosing a profile whose key is held as rejected (2026-11-12, Roku spec
 * 8.4 step 4): the sign-in form opens after its PIN and nothing goes out
 * with the held key. A non-default profile signs in only as its own user;
 * the default profile signs the playlist in as its known user; a pass clears
 * only that profile's hold; Cancel changes nothing.
 */
class RejectedKeySwitchTest {
    private val kid = DispatcharrProfile(id = "kid", playlistId = "P1", displayName = "Kid", username = "kid", apiKey = "kid-key", userLevel = 1, role = ProfileRole.Child)
    private val default = DispatcharrProfile(id = "def", playlistId = "P1", displayName = "Parent", username = "parent", isDefault = true)
    private val isRejected: (String) -> Boolean = DispatcharrRequestGate::isKeyRejected

    @Before fun setUp() = DispatcharrRequestGate.resetForTests()
    @After fun tearDown() = DispatcharrRequestGate.resetForTests()

    @Test fun aNonDefaultProfilesHeldKeyOpensItsOwnFormAndAPassGoesOn() {
        DispatcharrRequestGate.markKeyRejected("P1", "kid-key", "Home", profileId = "kid")
        // A Child target gets the form too (an adult, or an adult's PIN, chose it).
        assertEquals(RejectedKeySwitch.Form.Profile, RejectedKeySwitch.formFor(kid, "own-key", isRejected))
        assertTrue(RejectedKeySwitch.profilePasses(kid, " KID "))
        // The pass clears only its own hold, then the switch finds nothing held.
        DispatcharrRequestGate.markKeyRejected("P1", "own-key", "Home")
        DispatcharrRequestGate.clearProfile("P1", "kid")
        assertEquals(RejectedKeySwitch.Form.None, RejectedKeySwitch.formFor(kid, "own-key", isRejected))
        assertTrue(DispatcharrRequestGate.isKeyRejected("own-key"))
    }

    @Test fun anotherUserIsRefusedAndTheHoldStays() {
        DispatcharrRequestGate.markKeyRejected("P1", "kid-key", "Home", profileId = "kid")
        assertFalse(RejectedKeySwitch.profilePasses(kid, "parent"))
        assertTrue(DispatcharrRequestGate.isKeyRejected("kid-key"))
        assertEquals(RejectedKeySwitch.Form.Profile, RejectedKeySwitch.formFor(kid, "own-key", isRejected))
    }

    @Test fun theDefaultSignsThePlaylistInAsItsKnownUser() {
        DispatcharrRequestGate.markKeyRejected("P1", "own-key", "Home")
        assertEquals(RejectedKeySwitch.Form.PlaylistOwn, RejectedKeySwitch.formFor(default, "own-key", isRejected))
        // Item 11's chain: the recorded identity first, its id compared too.
        val known = FixLogin.knownAccount(ProfileHostHold.Identity(7, "Parent"), "parent", "parent")
        assertTrue(FixLogin.sameAccount(known.username, known.userId, "PARENT", 7, childInvolved = false))
        assertFalse(FixLogin.sameAccount(known.username, known.userId, "parent", 8, childInvolved = false))
        assertFalse(FixLogin.sameAccount(known.username, known.userId, "kid", 7, childInvolved = false))
        // A pass lifts the playlist's own hold; the switch then goes on.
        FixLogin.liftHold("P1", "own-key", "own-new")
        assertEquals(RejectedKeySwitch.Form.None, RejectedKeySwitch.formFor(default, "own-new", isRejected))
    }

    @Test fun cancelChangesNothing() {
        DispatcharrRequestGate.markKeyRejected("P1", "kid-key", "Home", profileId = "kid")
        DispatcharrRequestGate.markKeyRejected("P1", "own-key", "Home")
        // Cancel calls nothing: every hold stays, and choosing again opens the form again.
        assertTrue(DispatcharrRequestGate.isKeyRejected("kid-key"))
        assertTrue(DispatcharrRequestGate.isKeyRejected("own-key"))
        assertEquals(RejectedKeySwitch.Form.Profile, RejectedKeySwitch.formFor(kid, "own-key", isRejected))
        assertEquals(RejectedKeySwitch.Form.PlaylistOwn, RejectedKeySwitch.formFor(default, "own-key", isRejected))
    }

    @Test fun aKeyNotHeldSwitchesAsUsual() {
        assertEquals(RejectedKeySwitch.Form.None, RejectedKeySwitch.formFor(kid, "own-key", isRejected))
        assertEquals(RejectedKeySwitch.Form.None, RejectedKeySwitch.formFor(default, null, isRejected))
        assertEquals(RejectedKeySwitch.Form.None, RejectedKeySwitch.formFor(kid.copy(apiKey = null), "own-key", isRejected))
    }
}
