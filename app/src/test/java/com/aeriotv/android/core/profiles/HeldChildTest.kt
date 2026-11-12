package com.aeriotv.android.core.profiles

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Child stays where it is on a server address change (item 8, security,
 * 2026-11-11): no switch to the default profile at the change or after a
 * relaunch, held so nothing it holds is sent and its requests go nowhere,
 * lifted by a passing same-server check, and left only through a PIN.
 */
class HeldChildTest {

    private class MemoryBacking(var value: String? = null) : HostHoldBook.Backing {
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }

    private val newRoutes = ProfileHostHold.routes("https://other.example.test", null)

    private fun childOverlay() = ProfileScope.Overlay(
        playlistId = "P1", profileId = "kid", apiKey = "kid-key", username = "kid", password = "kid-pass",
        userLevel = 1, dvrAccess = "", catchupEnabled = true, vodMoviesEnabled = true, vodSeriesEnabled = true,
        accountProfileIds = "", isStaff = false, isSuperuser = false, customProperties = "",
        capabilitiesFetchedAt = 0L, capabilitiesSchema = 0, capabilitiesStale = false,
    )

    /** What ProfileManager does for a held active Child: seal (with the playlist's routes now), then hold. */
    private fun holdChildInPlace() {
        ProfileScope.sealed = ProfileScope.sealed + "kid"
        ProfileScope.sealedRoutes = newRoutes
        ProfileScope.held = ProfileScope.held + "kid"
    }

    @After fun reset() {
        ProfileScope.sealed = emptySet()
        ProfileScope.sealedRoutes = emptySet()
        ProfileScope.held = emptySet()
        ProfileScope.activate(null)
        ProfileScope.onOverlayChanged = null
    }

    @Test fun onlyAChildStaysActiveWhenHeld() {
        assertTrue(ProfileHostHold.staysActiveWhenHeld(ProfileRole.Child))
        assertFalse(ProfileHostHold.staysActiveWhenHeld(ProfileRole.User))
        assertFalse(ProfileHostHold.staysActiveWhenHeld(ProfileRole.Admin))
    }

    @Test fun aCloudPulledChangeKeepsTheChildActiveAndSendsNothing() {
        ProfileScope.activate(childOverlay())
        holdChildInPlace()
        // Still the Child: its scope and data, no switch to the default.
        assertEquals("kid", ProfileScope.token.value)
        val o = ProfileScope.overlayFor("P1")!!
        assertEquals("kid", o.profileId)
        // Nothing it holds goes out, and the default's sign-in is not used instead.
        assertNull(o.apiKey)
        assertNull(o.password)
        // Its requests to the playlist's addresses go nowhere.
        assertTrue(ProfileScope.isSealedRoute("https://other.example.test/api/channels/channels/"))
        assertFalse(ProfileScope.isSealedRoute("https://elsewhere.example.test/api/core/version/"))
    }

    @Test fun aWriteWhileHeldNeverWipesTheChildsStoredSignIn() {
        var persisted: ProfileScope.Overlay? = null
        ProfileScope.onOverlayChanged = { persisted = it }
        ProfileScope.activate(childOverlay())
        holdChildInPlace()
        // A write of the row as read (no sign-in) diverts into the overlay.
        ProfileScope.updateOverlay { it.copy(apiKey = null, password = null, capabilitiesStale = true) }
        assertEquals("kid-key", persisted?.apiKey)
        assertEquals("kid-pass", persisted?.password)
        assertTrue(persisted!!.capabilitiesStale)
    }

    @Test fun theSameServerLiftsTheHoldAndTheChildLoadsAgain() {
        ProfileScope.activate(childOverlay())
        holdChildInPlace()
        // A passing check lifts the hold: the held set and the seal clear.
        ProfileScope.sealed = emptySet()
        ProfileScope.sealedRoutes = emptySet()
        ProfileScope.held = emptySet()
        assertEquals("kid-key", ProfileScope.overlayFor("P1")?.apiKey)
        assertFalse(ProfileScope.isSealedRoute("https://other.example.test/api/channels/channels/"))
        assertEquals("kid", ProfileScope.token.value)
    }

    @Test fun aDifferentServerLeavesItHeldAndLeavingStillNeedsAPin() {
        val book = HostHoldBook(MemoryBacking())
        book.record("P1", ProfileHostHold.Identity(7, "admin"))
        book.tag("kid", ProfileHostHold.Identity(7, "admin"))
        book.hold("P1", setOf("kid"), ProfileHostHold.routes("https://tv.example.test", null), currentRoutes = newRoutes)
        val o = book.onAnswer("P1", ProfileHostHold.routeKey("https://other.example.test"), newRoutes, ProfileHostHold.Answer.Me(ProfileHostHold.Identity(99, "mallory")))
        assertEquals(HostHoldBook.Outcome.Failed("different-user"), o)
        assertTrue(book.liftMatching("P1", newRoutes).isEmpty())
        assertTrue(book.isHeld("kid"))
        // A Child never sees the switcher: the way out is the PIN-gated one, as before.
        assertFalse(RoleRules.canSeeSwitcher(ProfileRole.Child))
    }

    @Test fun aRelaunchKeepsTheHeldChildActiveWithNoResetAndNoPicker() {
        // Before the relaunch: the hold and its routes are stored.
        val backing = MemoryBacking()
        HostHoldBook(backing).hold("P1", setOf("kid"), ProfileHostHold.routes("https://tv.example.test", null), expected = null, currentRoutes = newRoutes)
        // After it: restoreSync's order. The held set first, then the Child sealed, then activated.
        val book = HostHoldBook(backing)
        assertEquals(newRoutes, book.hold("P1")?.current)
        ProfileScope.held = book.heldProfileIds()
        assertTrue(ProfileHostHold.staysActiveWhenHeld(ProfileRole.Child))
        ProfileScope.sealed = setOf("kid")
        ProfileScope.sealedRoutes = book.hold("P1")!!.current
        ProfileScope.activate(childOverlay())
        // Still the Child, still held: no reset to the default, nothing sent.
        assertEquals("kid", ProfileScope.token.value)
        assertNull(ProfileScope.overlayFor("P1")?.apiKey)
        assertTrue(ProfileScope.isSealedRoute("https://other.example.test/api/epg/grid/"))
        // A Child restored at launch goes straight in: no launch picker.
        assertFalse(RoleRules.showLaunchPicker(isTv = true, isDispatcharr = true, toggleOn = true, profileCount = 3, lastRole = ProfileRole.Child))
    }

    @Test fun aHeldProfileThatIsNotAChildIsNeverInstalled() {
        ProfileScope.held = setOf("kid")
        ProfileScope.activate(childOverlay())
        assertNull(ProfileScope.overlayFor("P1"))
        assertEquals("", ProfileScope.token.value)
    }
}
