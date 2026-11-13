package com.aeriotv.android.core.profiles

import java.util.Collections
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing Phone 2026-11-13: jwilliams4362 was active (its Default Group is
 * Favorites) when a server address change held its sign-in and moved the
 * playlist to the default profile, jonzey231 (which pins All). No landing ran,
 * so Live TV opened on Favorites under jonzey231. Every change of the active
 * profile reaches ProfileScope.changes with its reason, and every change whose
 * own path does not land the guide asks for a landing.
 */
class ProfileLandingTest {
    private fun overlay(pid: String, id: String) = ProfileScope.Overlay(
        playlistId = pid, profileId = id, apiKey = "k2", username = "jwilliams4362", password = "p",
        userLevel = 1, dvrAccess = "none", catchupEnabled = true, vodMoviesEnabled = true,
        vodSeriesEnabled = true, accountProfileIds = "", isStaff = false, isSuperuser = false,
        customProperties = "", capabilitiesFetchedAt = 0L, capabilitiesSchema = 0, capabilitiesStale = false,
    )

    @After fun reset() {
        ProfileScope.sealed = emptySet()
        ProfileScope.held = emptySet()
        ProfileScope.activate(null)
    }

    @Test fun theHoldMovingTheProfileToTheDefaultAsksForALanding() = runBlocking {
        val seen = Collections.synchronizedList(ArrayList<ProfileScope.Change>())
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { ProfileScope.changes.collect { seen += it } }

        // 08:47:43: the switch to jwilliams4362 (its reload lands the guide).
        ProfileScope.activate(overlay("10a8b936", "jw"), ProfileScope.Why.SWITCH)
        yield()
        // 08:49:23: the address change holds jwilliams4362's sign-in; the
        // held overlay is dropped and the default profile is active.
        ProfileScope.held = setOf("jw")
        yield()
        collector.cancel()

        assertEquals(listOf("jw", ""), seen.map { it.token })
        assertFalse("the switch lands on its own path", ProfileLanding.needsLanding(seen[0].reason))
        assertEquals(ProfileScope.Why.HELD, seen[1].reason)
        assertTrue("the hold's move to the default profile lands the guide", ProfileLanding.needsLanding(seen[1].reason))
        assertEquals("", ProfileScope.current())
    }

    @Test fun onlyAChangeOfTheActiveProfileIsReported() = runBlocking {
        val seen = Collections.synchronizedList(ArrayList<ProfileScope.Change>())
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { ProfileScope.changes.collect { seen += it } }
        ProfileScope.activate(overlay("10a8b936", "jw"), ProfileScope.Why.SWITCH)
        // A refresh of the same profile's overlay is not a change.
        ProfileScope.activate(overlay("10a8b936", "jw"))
        yield()
        collector.cancel()
        assertEquals(1, seen.size)
    }

    @Test fun pathsThatLandOnTheirOwnAreNotLandedTwice() {
        assertFalse(ProfileLanding.needsLanding(ProfileScope.Why.SWITCH))
        assertFalse(ProfileLanding.needsLanding(ProfileScope.Why.PLAYLIST))
        assertFalse(ProfileLanding.needsLanding(ProfileScope.Why.LAUNCH))
        assertTrue(ProfileLanding.needsLanding(ProfileScope.Why.HELD))
        assertTrue("an unnamed path lands too", ProfileLanding.needsLanding(ProfileScope.Why.UNSPECIFIED))
    }
}
