package com.aeriotv.android.core.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Held-key re-check schedule: 1, 5, 15 min, then every 30 min; one probe per 60 s; never inside a 429 pause. */
class DispatcharrKeyRecheckScheduleTest {
    private var now = 10_000_000L
    private val min = 60_000L
    private val base = "https://tv.example.com"

    @Before fun setUp() {
        DispatcharrRequestGate.resetForTests()
        DispatcharrRequestGate.clock = { now }
    }

    @After fun tearDown() {
        DispatcharrRequestGate.resetForTests()
        DispatcharrRequestGate.clock = { System.nanoTime() / 1_000_000 }
    }

    private fun hold(key: String = "k", passwordToo: Boolean = false): DispatcharrRequestGate.Recheck {
        DispatcharrRequestGate.markKeyRejected("p1", key, "Home", passwordToo, baseUrl = base)
        return DispatcharrRequestGate.rechecks().single()
    }

    /** Advance to each minute mark and record when scheduled probes run. */
    private fun runScheduleFor(r: DispatcharrRequestGate.Recheck, minutes: Int): List<Long> {
        val start = now
        val fired = mutableListOf<Long>()
        for (m in 0..minutes) {
            now = start + m * min
            if (DispatcharrRequestGate.claimScheduled(r)) {
                fired += (now - start) / min
                DispatcharrRequestGate.finishProbe(r, accepted = false)
            }
        }
        return fired
    }

    @Test fun scheduleIsOneFiveFifteenThenEveryThirty() {
        val r = hold()
        assertEquals(listOf(1L, 5L, 15L, 45L, 75L, 105L), runScheduleFor(r, 110))
    }

    @Test fun scheduledDueTimes() {
        assertEquals(60_000L, DispatcharrRequestGate.scheduledDueAt(0, 0))
        assertEquals(300_000L, DispatcharrRequestGate.scheduledDueAt(0, 1))
        assertEquals(900_000L, DispatcharrRequestGate.scheduledDueAt(0, 2))
        assertEquals(2_700_000L, DispatcharrRequestGate.scheduledDueAt(0, 3))
        assertEquals(4_500_000L, DispatcharrRequestGate.scheduledDueAt(0, 4))
    }

    @Test fun onDemandLimitedToOnePerMinuteAndSharedWithSchedule() {
        val r = hold()
        // Foreground right after the rejection: allowed (no probe yet).
        assertTrue(DispatcharrRequestGate.claimOnDemand(r))
        DispatcharrRequestGate.finishProbe(r, false)
        // A user refresh 30 s later: refused.
        now += 30_000
        assertFalse(DispatcharrRequestGate.claimOnDemand(r))
        // The 1-minute scheduled probe is due at +60 s from the rejection, but
        // the on-demand one ran at +0, so it may run at +60 s: exactly the gap.
        now += 30_000
        assertTrue(DispatcharrRequestGate.claimScheduled(r))
        DispatcharrRequestGate.finishProbe(r, false)
        // And an on-demand right after is refused.
        now += 1_000
        assertFalse(DispatcharrRequestGate.claimOnDemand(r))
    }

    @Test fun onDemandPushesTheNextScheduledProbe() {
        val r = hold()
        now += 50_000
        assertTrue(DispatcharrRequestGate.claimOnDemand(r))
        DispatcharrRequestGate.finishProbe(r, false)
        // Scheduled slot at +60 s, but the last probe was at +50 s: waits to +110 s.
        now += 10_000
        assertFalse(DispatcharrRequestGate.claimScheduled(r))
        now += 50_000
        assertTrue(DispatcharrRequestGate.claimScheduled(r))
    }

    @Test fun neverInsideA429Pause() {
        val r = hold()
        DispatcharrRequestGate.pause(DispatcharrRequestGate.serverKey(base), 120)
        now += 60_000
        assertFalse(DispatcharrRequestGate.claimScheduled(r))
        assertFalse(DispatcharrRequestGate.claimOnDemand(r))
        assertEquals(now + 60_000, DispatcharrRequestGate.nextScheduledAt(r))
        now += 60_000
        assertTrue(DispatcharrRequestGate.claimScheduled(r))
    }

    @Test fun loginParkBlocksPasswordProbes() {
        val r = hold(passwordToo = true)
        now += 60_000
        assertFalse(DispatcharrRequestGate.claimScheduled(r, extraBlockedUntil = now + 20_000))
        now += 20_000
        assertTrue(DispatcharrRequestGate.claimScheduled(r, extraBlockedUntil = 0))
    }

    @Test fun oneProbeAtATime() {
        val r = hold()
        assertTrue(DispatcharrRequestGate.claimOnDemand(r))
        now += 120_000
        // Still in flight: no second probe.
        assertFalse(DispatcharrRequestGate.claimScheduled(r))
        DispatcharrRequestGate.finishProbe(r, false)
        assertTrue(DispatcharrRequestGate.claimScheduled(r))
    }

    @Test fun acceptedClearsHoldAndPrompt() {
        val r = hold()
        assertTrue(DispatcharrRequestGate.claimOnDemand(r))
        DispatcharrRequestGate.finishProbe(r, accepted = true)
        assertFalse(DispatcharrRequestGate.isKeyRejected("k"))
        assertNull(DispatcharrRequestGate.prompt.value)
        assertTrue(DispatcharrRequestGate.rechecks().isEmpty())
        assertNull(DispatcharrRequestGate.earliestScheduledAt())
    }

    @Test fun rejectedStaysHeldWithoutANewPrompt() {
        val r = hold()
        DispatcharrRequestGate.consumePrompt()
        now += 60_000
        assertTrue(DispatcharrRequestGate.claimScheduled(r))
        DispatcharrRequestGate.finishProbe(r, accepted = false)
        assertTrue(DispatcharrRequestGate.isKeyRejected("k"))
        assertNull(DispatcharrRequestGate.prompt.value)
        assertEquals(DispatcharrRequestGate.scheduledDueAt(r.rejectedAt, 1), DispatcharrRequestGate.nextScheduledAt(r))
    }

    @Test fun editOrProvenKeyDropsTheRecheck() {
        hold()
        DispatcharrRequestGate.clearPlaylist("p1")
        assertTrue(DispatcharrRequestGate.rechecks().isEmpty())
        hold()
        DispatcharrRequestGate.clearKey("k")
        assertTrue(DispatcharrRequestGate.rechecks().isEmpty())
    }

    @Test fun noBaseUrlNoRecheck() {
        DispatcharrRequestGate.markKeyRejected("p1", "k")
        assertTrue(DispatcharrRequestGate.rechecks().isEmpty())
    }

    @Test fun worstCaseRateIsFarUnderTheThrottle() {
        val r = hold()
        // Hammer on-demand every second for 10 minutes plus the schedule.
        var probes = 0
        val start = now
        for (s in 0..600) {
            now = start + s * 1_000L
            if (DispatcharrRequestGate.claimScheduled(r) || DispatcharrRequestGate.claimOnDemand(r)) {
                probes++
                DispatcharrRequestGate.finishProbe(r, false)
            }
        }
        // At most one per 60 s: 11 over 600 s, never more than 1 in any minute.
        assertTrue("probes=$probes", probes <= 11)
    }
}
