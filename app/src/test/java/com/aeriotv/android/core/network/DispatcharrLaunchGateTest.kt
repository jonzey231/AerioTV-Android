package com.aeriotv.android.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Launch order (2026-11-10): the first Dispatcharr requests wait for the
 * Drive credentials pull, bounded, and a newly held key tells Drive Sync to
 * look for the synced key.
 */
class DispatcharrLaunchGateTest {
    private var now = 1_000L

    @Before fun setUp() {
        DispatcharrLaunchGate.resetForTests()
        DispatcharrRequestGate.resetForTests()
    }

    @After fun tearDown() {
        DispatcharrLaunchGate.resetForTests()
        DispatcharrRequestGate.resetForTests()
        DispatcharrRequestGate.onKeyHeld = null
    }

    @Test fun notArmedNeverWaits() = runBlocking {
        assertFalse(DispatcharrLaunchGate.waiting())
        val t0 = System.nanoTime()
        DispatcharrLaunchGate.await()
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 200)
    }

    @Test fun requestsWaitForThePullThenGoAtOnce() = runBlocking {
        DispatcharrLaunchGate.arm()
        assertTrue(DispatcharrLaunchGate.waiting())
        val t0 = System.nanoTime()
        val waiter = async { DispatcharrLaunchGate.await() }
        delay(150)
        assertTrue(waiter.isActive)
        DispatcharrLaunchGate.open()
        waiter.await()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("waited $ms ms", ms in 100..1_500)
        assertFalse(DispatcharrLaunchGate.waiting())
    }

    @Test fun theWaitIsBoundedEvenIfThePullNeverEnds() {
        DispatcharrLaunchGate.clock = { now }
        DispatcharrLaunchGate.arm()
        assertTrue(DispatcharrLaunchGate.waiting())
        now += DispatcharrLaunchGate.MAX_WAIT_MS
        assertFalse(DispatcharrLaunchGate.waiting())
        // Arming again later does not restart the wait once it was armed.
        DispatcharrLaunchGate.arm()
        assertFalse(DispatcharrLaunchGate.waiting())
    }

    @Test fun aNewlyHeldKeyTellsDriveSyncOnce() {
        val told = mutableListOf<String>()
        DispatcharrRequestGate.onKeyHeld = { told += it }
        DispatcharrRequestGate.markKeyRejected("p1", "DEAD", "Home")
        DispatcharrRequestGate.markKeyRejected("p1", "DEAD", "Home")
        assertEquals(listOf("p1"), told)
        // The adoption path: hold lifted, prompt cleared, the old key routed to the new one.
        DispatcharrRequestGate.clearPlaylist("p1")
        DispatcharrRequestGate.noteKeyReplaced("DEAD", "GOOD")
        assertFalse(DispatcharrRequestGate.isKeyRejected("DEAD"))
        assertEquals(null, DispatcharrRequestGate.prompt.value)
        assertEquals("GOOD", DispatcharrRequestGate.replacementFor("DEAD"))
    }
}
