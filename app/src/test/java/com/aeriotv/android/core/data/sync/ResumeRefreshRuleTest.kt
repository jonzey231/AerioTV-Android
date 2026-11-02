package com.aeriotv.android.core.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GH #147: resume refresh is due once the last refresh is older than the interval. */
class ResumeRefreshRuleTest {
    @Test fun intervalModeUsesTheInterval() = assertEquals(180, ResumeRefreshRule.intervalMins("interval", 180))
    @Test fun timeModeIsDaily() = assertEquals(1440, ResumeRefreshRule.intervalMins("time", 180))
    @Test fun dueOnlyPastTheInterval() {
        assertFalse(ResumeRefreshRule.isDue(60 * 60_000L, 60))
        assertTrue(ResumeRefreshRule.isDue(60 * 60_000L + 1, 60))
        assertTrue(ResumeRefreshRule.isDue(10L * 60 * 60_000L, 180))
    }
    @Test fun ageFormat() {
        assertEquals("45m", ResumeRefreshRule.formatAge(45 * 60_000L))
        assertEquals("3h12m", ResumeRefreshRule.formatAge((3 * 60 + 12) * 60_000L))
    }
}
