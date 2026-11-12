package com.aeriotv.android.core.network

import com.aeriotv.android.core.setup.FixLogin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Rejected-key holds are per identity (2026-11-12, security): repairing the
 * playlist's own sign-in (Sign In, Edit Playlist, Fix Login, a Drive pull)
 * never releases a non-default profile's held key unless that profile's own
 * key is the one repaired, so its still-refused key adds no failed-key
 * attempts.
 */
class PerIdentityHoldTest {
    private val base = "https://dispatcharr.example.test"

    @Before fun setUp() = DispatcharrRequestGate.resetForTests()
    @After fun tearDown() = DispatcharrRequestGate.resetForTests()

    private fun holdBoth() {
        DispatcharrRequestGate.markKeyRejected("P1", "own-old", "Home", baseUrl = base)
        DispatcharrRequestGate.markKeyRejected("P1", "kid-key", "Home", baseUrl = base, profileId = "kid")
    }

    @Test fun repairingThePlaylistKeepsTheProfilesKeyHeld() {
        holdBoth()
        assertTrue(DispatcharrRequestGate.isKeyRejected("kid-key"))
        // Sign In / Fix Login on the playlist's own sign-in.
        FixLogin.liftHold("P1", "own-old", "own-new")
        assertFalse(DispatcharrRequestGate.isKeyRejected("own-old"))
        assertTrue(DispatcharrRequestGate.isKeyRejected("kid-key"))
        // Its re-check stays too (the key is still refused, nothing new to try).
        assertTrue(DispatcharrRequestGate.rechecks().any { it.key == "kid-key" })
        assertFalse(DispatcharrRequestGate.rechecks().any { it.key == "own-old" })
        // The playlist still counts as having a held key.
        assertTrue(DispatcharrRequestGate.isPlaylistRejected("P1"))
        // An Edit Playlist save or a Drive pull clears the playlist the same way.
        DispatcharrRequestGate.clearPlaylist("P1")
        assertTrue(DispatcharrRequestGate.isKeyRejected("kid-key"))
        assertNull(DispatcharrRequestGate.prompt.value)
    }

    @Test fun theProfilesOwnRepairReleasesIt() {
        holdBoth()
        DispatcharrRequestGate.clearProfile("P1", "kid")
        assertFalse(DispatcharrRequestGate.isKeyRejected("kid-key"))
        assertTrue(DispatcharrRequestGate.isKeyRejected("own-old"))
        assertFalse(DispatcharrRequestGate.rechecks().any { it.key == "kid-key" })
    }

    @Test fun theSameKeyRepairedReleasesEveryHolder() {
        // The profile signs in as the playlist's own account: one key, repaired once.
        DispatcharrRequestGate.markKeyRejected("P1", "shared", "Home", profileId = "kid")
        DispatcharrRequestGate.clearKey("shared")
        assertFalse(DispatcharrRequestGate.isKeyRejected("shared"))
        assertFalse(DispatcharrRequestGate.isPlaylistRejected("P1"))
    }

    @Test fun holdersAreTheIdentity() {
        assertEquals("P1", DispatcharrRequestGate.holder("P1"))
        assertEquals("P1", DispatcharrRequestGate.holder("P1", ""))
        assertEquals("P1@kid", DispatcharrRequestGate.holder("P1", "kid"))
        // Each identity's first rejection is its own transition.
        assertTrue(DispatcharrRequestGate.markKeyRejected("P1", "k", profileId = "kid"))
        assertTrue(DispatcharrRequestGate.markKeyRejected("P1", "k"))
        assertFalse(DispatcharrRequestGate.markKeyRejected("P1", "k", profileId = "kid"))
    }
}
