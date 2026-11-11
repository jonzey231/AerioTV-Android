package com.aeriotv.android.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * One login per launch (2026-11-11): the startup login and a 401 re-login
 * are the same [DispatcharrLoginGate.silentRelogin] attempt, so a re-login
 * while the startup login runs, or within a minute after it, shares its
 * result instead of signing in again.
 */
class StartupLoginSharingTest {
    private var now = 1_000_000L
    private var logins = 0

    @Before fun setUp() {
        DispatcharrLoginGate.resetForTests()
        DispatcharrLoginGate.clock = { now }
        DispatcharrLoginGate.logSink = { _, _ -> }
    }

    @After fun tearDown() {
        DispatcharrLoginGate.resetForTests()
        DispatcharrLoginGate.clock = { android.os.SystemClock.elapsedRealtime() }
        DispatcharrLoginGate.logSink = null
    }

    @Test fun reloginDuringTheStartupLoginWaitsAndShares() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val startup = async { DispatcharrLoginGate.silentRelogin("p1") { logins++; release.await(); "new-key" } }
        yield()
        val relogin = async { DispatcharrLoginGate.silentRelogin("p1") { logins++; "second-key" } }
        yield()
        release.complete(Unit)
        assertEquals("new-key", startup.await())
        assertEquals("the re-login shares the startup login's key", "new-key", relogin.await())
        assertEquals("one launch, one login", 1, logins)
    }

    @Test fun reloginJustAfterTheStartupLoginShares() = runBlocking {
        assertEquals("k1", DispatcharrLoginGate.silentRelogin("p1") { logins++; "k1" })
        now += 30_000
        assertEquals("k1", DispatcharrLoginGate.silentRelogin("p1") { logins++; "k2" })
        assertEquals(1, logins)
        // A minute later a new rejection signs in again (key rotation recovery).
        now += DispatcharrLoginGate.ATTEMPT_WINDOW_MS
        assertEquals("k2", DispatcharrLoginGate.silentRelogin("p1") { logins++; "k2" })
        assertEquals(2, logins)
    }

    @Test fun startupLoginWithNoKeyStillHolds() = runBlocking {
        assertNull(DispatcharrLoginGate.silentRelogin("p1") { logins++; throw DispatcharrError.NoApiKey("none") })
        assertTrue(DispatcharrLoginGate.accountHasNoKey("p1"))
        now += 5_000
        // The 401 re-login shares the result and still sees "no API key" (the broker holds the key).
        assertNull(DispatcharrLoginGate.silentRelogin("p1") { logins++; "never" })
        assertTrue(DispatcharrLoginGate.accountHasNoKey("p1"))
        assertEquals(1, logins)
        assertEquals(ReloginHold.NoApiKey, reloginHold(keyRejected = true, loginRejected = false, accountHasNoKey = DispatcharrLoginGate.accountHasNoKey("p1")))
    }

    @Test fun refusedStartupLoginParksTheReloginToo() = runBlocking {
        assertNull(DispatcharrLoginGate.silentRelogin("p1") { logins++; throw DispatcharrError.InvalidCredentials("401") })
        assertTrue(DispatcharrLoginGate.isRejected("p1"))
        now += 10_000
        assertNull(DispatcharrLoginGate.silentRelogin("p1") { logins++; "never" })
        assertEquals(1, logins)
    }

    @Test fun otherPlaylistsAreSeparate() = runBlocking {
        DispatcharrLoginGate.silentRelogin("p1") { logins++; "a" }
        DispatcharrLoginGate.silentRelogin("p2") { logins++; "b" }
        assertEquals(2, logins)
        assertFalse(DispatcharrLoginGate.accountHasNoKey("p2"))
    }

    @Test fun unchangedKeyWritesNothing() {
        assertFalse(reloginChangesKey("k1", "k1"))
        assertFalse(reloginChangesKey(" k1 ", "k1"))
        assertTrue(reloginChangesKey("k1", "k2"))
        assertTrue(reloginChangesKey(null, "k2"))
        assertTrue(reloginChangesKey("", "k2"))
    }
}
