package com.aeriotv.android.core.network

import com.aeriotv.android.core.profiles.ProfileScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A 401 for an active non-default profile (2026-11-12, security): its silent
 * re-login uses only its own stored password, and never shares the
 * playlist's (default's) fresh key, which would replay its requests as the
 * default user. Rows are cached and re-logins kept per identity.
 */
class ReloginIdentityTest {
    private var now = 1_000_000L

    private fun kid() = ProfileScope.Overlay(
        playlistId = "P1", profileId = "kid", apiKey = "kid-key", username = "kid", password = "kid-pass",
        userLevel = 1, dvrAccess = "", catchupEnabled = true, vodMoviesEnabled = true, vodSeriesEnabled = true,
        accountProfileIds = "", isStaff = false, isSuperuser = false, customProperties = "",
        capabilitiesFetchedAt = 0L, capabilitiesSchema = 0, capabilitiesStale = false,
    )

    @Before fun setUp() {
        DispatcharrLoginGate.resetForTests()
        DispatcharrLoginGate.clock = { now }
        DispatcharrLoginGate.logSink = { _, _ -> }
    }

    @After fun tearDown() {
        DispatcharrLoginGate.resetForTests()
        DispatcharrLoginGate.clock = { android.os.SystemClock.elapsedRealtime() }
        DispatcharrLoginGate.logSink = null
        ProfileScope.activate(null)
    }

    @Test fun aProfilesReloginNeverSharesThePlaylistsKey() = runBlocking {
        // The startup login signed in as the playlist's own user.
        assertEquals("parent-key", DispatcharrLoginGate.silentRelogin(DispatcharrLoginGate.slotFor("P1", null)) { "parent-key" })
        // Seconds later the Child's key is refused: it signs in as itself.
        var kidLogins = 0
        val kid = DispatcharrLoginGate.silentRelogin(DispatcharrLoginGate.slotFor("P1", "kid")) { kidLogins++; "kid-new-key" }
        assertEquals("kid-new-key", kid)
        assertEquals(1, kidLogins)
        // The same identity still shares within the window, so the login throttle holds.
        assertEquals("kid-new-key", DispatcharrLoginGate.silentRelogin(DispatcharrLoginGate.slotFor("P1", "kid")) { "third" })
        assertEquals("parent-key", DispatcharrLoginGate.silentRelogin(DispatcharrLoginGate.slotFor("P1", null)) { "fourth" })
    }

    @Test fun aProfilesRefusedLoginParksOnlyItself() = runBlocking {
        val kidSlot = DispatcharrLoginGate.slotFor("P1", "kid")
        assertNull(DispatcharrLoginGate.silentRelogin(kidSlot) { throw DispatcharrError.InvalidCredentials("no") })
        assertEquals(true, DispatcharrLoginGate.isRejected(kidSlot))
        assertEquals(false, DispatcharrLoginGate.isRejected(DispatcharrLoginGate.slotFor("P1", null)))
        // Parked: no fallback to anyone else's sign-in.
        assertNull(DispatcharrLoginGate.silentRelogin(kidSlot) { "never" })
    }

    @Test fun theIdentityFollowsTheRowThatWasRead() = runBlocking {
        assertEquals("P1", DispatcharrLoginGate.slotFor("P1", null))
        assertEquals("P1:kid", DispatcharrLoginGate.slotFor("P1", "kid"))
        ProfileScope.activate(kid())
        // A row read now carries the Child's sign-in.
        assertEquals("kid", ProfileScope.readScope("P1"))
        assertEquals("kid", ProfileScope.signInIdentity("P1", "KID"))
        // The playlist's own row (another user) is the playlist's identity.
        assertNull(ProfileScope.signInIdentity("P1", "parent"))
        assertEquals("", ProfileScope.readScope("P2"))
        // A default-only read is the playlist's own, even with the Child installed.
        withContext(ProfileScope.defaultOnly()) {
            assertEquals("", ProfileScope.readScope("P1"))
            assertNull(ProfileScope.signInIdentity("P1", "kid"))
        }
    }
}
