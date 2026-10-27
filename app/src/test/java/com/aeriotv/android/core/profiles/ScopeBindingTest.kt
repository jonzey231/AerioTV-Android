package com.aeriotv.android.core.profiles

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Item 1 (2026-10-27): a refresh writes under the profile it started as. */
class ScopeBindingTest {
    private fun overlay(pid: String, id: String) = ProfileScope.Overlay(
        playlistId = pid, profileId = id, apiKey = "k2", username = "Kid", password = "p",
        userLevel = 1, dvrAccess = "none", catchupEnabled = true, vodMoviesEnabled = true,
        vodSeriesEnabled = true, accountProfileIds = "", isStaff = false, isSuperuser = false,
        customProperties = "", capabilitiesFetchedAt = 0L, capabilitiesSchema = 0, capabilitiesStale = false,
    )

    @After fun reset() = ProfileScope.activate(null)

    @Test fun keyIsCapturedAtStartAndASwitchDropsTheWrite() {
        ProfileScope.activate(null)
        val b = ScopeBinding.capture("P1")
        assertEquals("P1", b.cacheKey)
        assertTrue(b.isDefaultProfile)
        assertTrue(b.isCurrent())
        // Picker closes onto another profile while the refresh is in flight.
        ProfileScope.activate(overlay("P1", "KID"))
        assertEquals("P1", b.cacheKey)
        assertFalse(b.isCurrent())
        try {
            b.ensureCurrent("channel refresh")
            fail("expected ScopeMovedException")
        } catch (_: ScopeMovedException) {
        }
    }

    @Test fun nonDefaultProfileNeverOwnsTheRowFields() {
        ProfileScope.activate(overlay("P1", "KID"))
        val b = ScopeBinding.capture("P1")
        assertEquals("P1@KID", b.cacheKey)
        assertFalse(b.isDefaultProfile)
        ProfileScope.activate(null)
        assertFalse(b.isCurrent())
    }
}
