package com.aeriotv.android.feature.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A held Child's lists (Apple parity, 2026-11-11): the bare line on a manual
 * refresh, and at launch with nothing cached it stays with that line instead
 * of going to the add-playlist screen. Other failures keep today's copy.
 */
class HeldListCopyTest {

    @Test fun aHeldManualRefreshShowsTheBareLine() {
        assertEquals("Could not reach your Dispatcharr server", HeldListCopy.refreshFailed(held = true, reason = "Could not reach your Dispatcharr server"))
        assertEquals("Could not reach your Dispatcharr server", HeldListCopy.refreshFailed(held = true, reason = "anything"))
    }

    @Test fun otherRefreshFailuresKeepTheirPrefix() {
        assertEquals("Refresh failed: HTTP 500", HeldListCopy.refreshFailed(held = false, reason = "HTTP 500"))
    }

    @Test fun aHeldLaunchWithNothingCachedStays() {
        assertFalse(HeldListCopy.launchGoesToAdd(held = true))
        assertTrue(HeldListCopy.launchGoesToAdd(held = false))
        assertEquals(com.aeriotv.android.feature.settings.DISPATCHARR_UNREACHABLE, HeldListCopy.LINE)
    }
}
