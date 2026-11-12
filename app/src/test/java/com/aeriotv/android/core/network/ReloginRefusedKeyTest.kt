package com.aeriotv.android.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * A password re-login that signs in but hands back a key the server still
 * refuses (2026-11-12, security and lockout): the same key, or a new one.
 * The app holds that key at once and stops. Before, every caller replayed it
 * (about 15 refused requests a second), which trips Dispatcharr's
 * 10-per-minute failed-key lockout for the whole network.
 *
 * Drives [recoverFromUnauthorized] (the steps withApiKeyRetry takes after a
 * 401) with the real [DispatcharrRequestGate] and [DispatcharrLoginGate] and
 * a fake server that counts every request it refuses.
 */
class ReloginRefusedKeyTest {
    private var now = 1_000_000L

    /** The fake Dispatcharr: keys it refuses, and what it counted. */
    private val refusedKeys = mutableSetOf<String>()
    private var refused = 0
    private var logins = 0
    private var stored = "old-key"
    private var reloginKey = "old-key"
    /** When set, every request waits here first (concurrent callers all send before any 401 lands). */
    private var gate: CompletableDeferred<Unit>? = null

    @Before fun setUp() {
        DispatcharrRequestGate.resetForTests()
        DispatcharrLoginGate.resetForTests()
        DispatcharrLoginGate.clock = { now }
        DispatcharrLoginGate.logSink = { _, _ -> }
    }

    @After fun tearDown() {
        DispatcharrRequestGate.resetForTests()
        DispatcharrLoginGate.resetForTests()
        DispatcharrLoginGate.clock = { android.os.SystemClock.elapsedRealtime() }
        DispatcharrLoginGate.logSink = null
    }

    /** One API request with [key]: a held key never leaves the device (DispatcharrGatePlugin). */
    private suspend fun request(key: String): String {
        if (DispatcharrRequestGate.isKeyRejected(key)) throw DispatcharrError.KeyRejected()
        gate?.await()
        if (key in refusedKeys) {
            refused++
            throw DispatcharrError.Unauthorized("Dispatcharr rejected the api_key (HTTP 401)")
        }
        return "channels"
    }

    private suspend fun probe(key: String): DispatcharrClient.KeyProbe {
        if (DispatcharrRequestGate.isKeyRejected(key)) return DispatcharrClient.KeyProbe.Unknown
        if (key in refusedKeys) {
            refused++
            return DispatcharrClient.KeyProbe.Rejected
        }
        return DispatcharrClient.KeyProbe.Accepted(AUTH_MODE_BOTH)
    }

    private val ops = KeyRecoveryOps(
        block = { key -> request(key) },
        currentKeyIfReplaced = { null },
        probe = { key -> probe(key) },
        persistModeIfChanged = { null },
        relogin = {
            DispatcharrLoginGate.silentRelogin("P1") {
                logins++
                // The re-login saves a changed key on the row.
                if (reloginKey != stored) stored = reloginKey
                reloginKey
            }
        },
        loginRejected = { DispatcharrLoginGate.isRejected("P1") },
        accountHasNoKey = { DispatcharrLoginGate.accountHasNoKey("P1") },
        hold = { key, passwordToo, noApiKey -> DispatcharrRequestGate.markKeyRejected("P1", key, "Home", passwordToo, noApiKey = noApiKey) },
        invalidate = {},
        log = { _, _ -> },
    )

    /** One caller, as withApiKeyRetry runs it: the stored key, the held-key check, then the 401 steps. */
    private suspend fun call(): String {
        val key = stored
        if (DispatcharrRequestGate.isKeyRejected(key)) throw DispatcharrError.KeyRejected()
        return try {
            request(key)
        } catch (e: DispatcharrError.Unauthorized) {
            recoverFromUnauthorized("P1", key, hasPassword = true, e = e, ops = ops)
        }
    }

    private suspend fun callExpectingHold() {
        try {
            call()
            fail("the refused key must be held")
        } catch (e: DispatcharrError.KeyRejected) {
            // held: nothing more was sent
        }
    }

    @Test fun aReloginThatHandsBackTheRefusedKeySendsAtMostTwoRefusedRequests() = runBlocking {
        refusedKeys += "old-key"
        reloginKey = "old-key"
        // The channel refresh, the guide, On Demand, the DVR poll: one after another.
        repeat(6) { callExpectingHold() }
        // The first call and one probe of the key the re-login handed back. Nothing after the hold.
        assertTrue("refused requests: $refused", refused <= 2)
        assertEquals(2, refused)
        assertEquals("one re-login, shared within the minute", 1, logins)
        assertTrue(DispatcharrRequestGate.isKeyRejected("old-key"))
        // The prompt names the key (the password signed in), not the sign-in.
        assertFalse(DispatcharrRequestGate.prompt.value!!.passwordToo)
    }

    @Test fun aReloginThatHandsBackANewRefusedKeySendsAtMostTwoRefusedRequests() = runBlocking {
        refusedKeys += listOf("old-key", "new-key")
        reloginKey = "new-key"
        repeat(6) { callExpectingHold() }
        // The first call with the old key, and one probe of the new one: never a replay.
        assertEquals(2, refused)
        assertEquals(1, logins)
        assertTrue(DispatcharrRequestGate.isKeyRejected("new-key"))
        assertEquals("new-key", stored)
    }

    @Test fun aMinuteLaterTheHeldKeyStillSendsNothing() = runBlocking {
        refusedKeys += "old-key"
        callExpectingHold()
        now += DispatcharrLoginGate.ATTEMPT_WINDOW_MS + 1
        repeat(6) { callExpectingHold() }
        // The hold, not the re-login window, stops every later call.
        assertEquals(2, refused)
        assertEquals(1, logins)
    }

    @Test fun callersAlreadyInFlightCostOneEachThenNothing() = runBlocking {
        refusedKeys += "old-key"
        val inFlight = CompletableDeferred<Unit>()
        gate = inFlight
        val callers = List(6) { async { runCatching { call() } } }
        yield()
        inFlight.complete(Unit)
        val results = callers.awaitAll()
        gate = null
        assertTrue(results.all { it.exceptionOrNull() is DispatcharrError.KeyRejected })
        // Six requests were already on the wire before the first 401 came back,
        // then one probe; one re-login for all of them.
        assertEquals(7, refused)
        assertEquals(1, logins)
        // The next wave sends nothing.
        repeat(6) { callExpectingHold() }
        assertEquals(7, refused)
    }

    @Test fun aRotatedKeyStillRecoversWithOneRefusedRequest() = runBlocking {
        refusedKeys += "old-key"
        reloginKey = "new-key"
        assertEquals("channels", call())
        // The stale key's call only; the new key is checked once (accepted) and used.
        assertEquals(1, refused)
        assertEquals(1, logins)
        assertFalse(DispatcharrRequestGate.isKeyRejected("new-key"))
        assertEquals("channels", call())
        assertEquals(1, refused)
    }

    @Test fun aKeyTheServerAcceptsIsNeverHeld() = runBlocking {
        // An endpoint-specific 401 for a key that works: the re-login hands it back,
        // the probe accepts it, and the call is replayed once; nothing is held.
        var calls = 0
        val endpoint = KeyRecoveryOps(
            block = { _: String -> calls++; throw DispatcharrError.Unauthorized("HTTP 403") },
            currentKeyIfReplaced = { null },
            probe = { key -> probe(key) },
            persistModeIfChanged = { null },
            relogin = { DispatcharrLoginGate.silentRelogin("P1") { logins++; "good-key" } },
            loginRejected = { false },
            accountHasNoKey = { false },
            hold = { key, passwordToo, noApiKey -> DispatcharrRequestGate.markKeyRejected("P1", key, "Home", passwordToo, noApiKey = noApiKey) },
            invalidate = {},
            log = { _, _ -> },
        )
        try {
            recoverFromUnauthorized("P1", "good-key", hasPassword = true, e = DispatcharrError.Unauthorized("HTTP 403"), ops = endpoint)
            fail("the endpoint's own 401 stands")
        } catch (e: DispatcharrError.Unauthorized) {
            assertFalse(e is DispatcharrError.KeyRejected)
        }
        assertEquals(1, calls)
        assertFalse(DispatcharrRequestGate.isKeyRejected("good-key"))
    }
}
