package com.aeriotv.android.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * A password re-login that signs in but whose key the server still refuses
 * (2026-11-12, security and lockout; Apple 44c544c). Before, that key was
 * never held: every caller got it back, replayed it, and about 15 refused
 * requests went out in a second, tripping Dispatcharr's 10-per-minute
 * failed-key lockout for the whole network.
 *
 * Now the re-login checks the key it read with exactly one request before
 * anything stores or sends it; a refused one ends the re-login with no key,
 * parks the identity for the minute, and the old key is held.
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
    private var probesOfOld = 0
    private var checks = 0
    private var logins = 0
    private var stored = "old-key"
    /** The key the caller in recovery used. */
    private var used = "old-key"
    /** The key the account reports on users/me after a login. */
    private var minted = "minted-key"
    private val probeFlight = SingleFlight<String, DispatcharrClient.KeyProbe>()

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
        if (key in refusedKeys) {
            refused++
            throw DispatcharrError.Unauthorized("Dispatcharr rejected the api_key (HTTP 401)")
        }
        return "channels"
    }

    /** The 401 probe of a key, shared by concurrent callers (DispatcharrClient.probeApiKey). */
    private suspend fun probe(key: String): DispatcharrClient.KeyProbe = probeFlight.run(key) {
        yield() // concurrent 401s join the probe in flight
        if (DispatcharrRequestGate.isKeyRejected(key)) return@run DispatcharrClient.KeyProbe.Unknown
        if (key == "old-key") probesOfOld++
        if (key in refusedKeys) {
            refused++
            DispatcharrClient.KeyProbe.Rejected
        } else {
            DispatcharrClient.KeyProbe.Accepted(AUTH_MODE_BOTH)
        }
    }

    /** The silent re-login as rebootstrapOnce runs it: login, read the key, ONE check, then store. */
    private suspend fun relogin(refusedKey: String? = null): String? = DispatcharrLoginGate.silentRelogin("P1", refusedKey) {
        logins++
        val key = minted
        checks++
        val proof = if (key in refusedKeys) {
            refused++
            DispatcharrClient.KeyProbe.Rejected
        } else {
            DispatcharrClient.KeyProbe.Accepted(AUTH_MODE_BOTH)
        }
        reloginOutcome(key, proof).also {
            if (proof is DispatcharrClient.KeyProbe.Accepted) DispatcharrLoginGate.noteKeyVerified("P1", it)
            if (it != stored) stored = it
        }
    }

    private val ops = KeyRecoveryOps(
        block = { key -> request(key) },
        currentKeyIfReplaced = { currentKeyAfterSwap(used, stored, DispatcharrRequestGate::isKeyRejected) },
        probe = { key -> probe(key) },
        persistModeIfChanged = { null },
        relogin = { relogin(refusedKey = used) },
        loginRejected = { DispatcharrLoginGate.isRejected("P1") },
        accountHasNoKey = { DispatcharrLoginGate.accountHasNoKey("P1") },
        keyRefused = { DispatcharrLoginGate.foundRefusedKey("P1") },
        noteKeyRefused = { DispatcharrLoginGate.noteKeyRefused("P1") },
        keyVerified = { key -> DispatcharrLoginGate.keyVerified("P1", key) },
        hold = { key, passwordToo, noApiKey -> DispatcharrRequestGate.markKeyRejected("P1", key, "Home", passwordToo, noApiKey = noApiKey) },
        invalidate = {},
        log = { _, _ -> },
    )

    /** A caller whose request with [key] was just answered 401. */
    private suspend fun after401(key: String): Result<String> = runCatching {
        used = key
        recoverFromUnauthorized("P1", key, hasPassword = true, e = DispatcharrError.Unauthorized("HTTP 401"), ops = ops)
    }

    /** A caller as withApiKeyRetry runs it: the stored key, the held-key check, the request, then the 401 steps. */
    private suspend fun call(): Result<String> = runCatching {
        val key = stored
        used = key
        if (DispatcharrRequestGate.isKeyRejected(key)) throw DispatcharrError.KeyRejected()
        try {
            request(key)
        } catch (e: DispatcharrError.Unauthorized) {
            recoverFromUnauthorized("P1", key, hasPassword = true, e = e, ops = ops)
        }
    }

    @Test fun theOutcomeRule() {
        // One check of the minted key decides: refused ends the re-login.
        try {
            reloginOutcome("k", DispatcharrClient.KeyProbe.Rejected)
            fail("a refused minted key ends the re-login")
        } catch (e: ReloginKeyRefused) {
            // key refused
        }
        assertEquals("k", reloginOutcome("k", DispatcharrClient.KeyProbe.Accepted(AUTH_MODE_BOTH)))
        // No answer (network, 5xx) is not a refusal: the key is used.
        assertEquals("k", reloginOutcome("k", DispatcharrClient.KeyProbe.Unknown))
        // The hold it leads to names the key (the password signed in).
        assertEquals(ReloginHold.KeyRefused, reloginHold(keyRejected = true, loginRejected = false, accountHasNoKey = false, keyRefused = true))
        assertEquals(ReloginHold.None, reloginHold(keyRejected = false, loginRejected = false, accountHasNoKey = false, keyRefused = true))
    }

    @Test fun aRefusedCheckParksTheIdentityAndNobodyGetsTheKey() = runBlocking {
        refusedKeys += "minted-key"
        assertNull(relogin())
        assertEquals(1, logins)
        assertTrue("the refused key is recorded (the old key is held)", DispatcharrLoginGate.foundRefusedKey("P1"))
        assertEquals("the minted key was not stored", "old-key", stored)
        // No second re-login inside the minute, and no key for anyone.
        now += 30_000
        assertNull(relogin())
        now += 29_000
        assertNull(relogin())
        assertEquals(1, logins)
        // At most one re-login per slot per minute: the next one runs after it.
        now += 2_000
        refusedKeys.clear()
        assertEquals("minted-key", relogin())
        assertEquals(2, logins)
    }

    @Test fun aSharedKeyRefusedLaterGetsOneFreshReloginThenParks() = runBlocking {
        // The re-login's key passed its check and is shared inside the minute.
        assertEquals("minted-key", relogin())
        assertEquals("minted-key", DispatcharrLoginGate.silentRelogin("P1") { "never" })
        // Then the server refuses it (rotated): one fresh re-login, whose key
        // (the same one, still refused) fails its check and parks the slot.
        refusedKeys += "minted-key"
        assertTrue(after401("minted-key").exceptionOrNull() is DispatcharrError.KeyRejected)
        assertEquals(2, logins)
        assertTrue(DispatcharrLoginGate.foundRefusedKey("P1"))
        assertNull(DispatcharrLoginGate.silentRelogin("P1") { "never" })
        assertTrue("held: nothing sends it", DispatcharrRequestGate.isKeyRejected("minted-key"))
        // The user's own sign-in clears it.
        DispatcharrLoginGate.reset("P1")
        assertFalse(DispatcharrLoginGate.foundRefusedKey("P1"))
    }

    @Test fun aKeyThatWorkedThenRotatedGetsAFreshReloginNotAHold() = runBlocking {
        // Measured on the Streamer: K1 refused, the re-login mints K2, which passes its check.
        stored = "K1"; refusedKeys += "K1"; minted = "K2"
        assertEquals("channels", call().getOrThrow())
        assertEquals("K2", stored)
        // 49 s later the server rotates K2: a 401 on K2, and the account now reports K3.
        now += 49_000
        refusedKeys += "K2"; minted = "K3"
        assertEquals("channels", call().getOrThrow())
        // A fresh re-login minted K3 (checked once): no prompt, no hold.
        assertEquals(2, logins)
        assertEquals("K3", stored)
        assertFalse(DispatcharrRequestGate.isKeyRejected("K2"))
        assertFalse(DispatcharrRequestGate.isKeyRejected("K3"))
        assertNull(DispatcharrRequestGate.prompt.value)
        assertFalse(DispatcharrLoginGate.foundRefusedKey("P1"))
    }

    @Test fun rotationsWhileAReloginIsInFlightShareIt() = runBlocking {
        // The first 401 on K2 starts a re-login; two more 401s on K2 arrive while it runs.
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val first = async { DispatcharrLoginGate.silentRelogin("P1", refusedKey = "K2") { attempts++; release.await(); "K3" } }
        yield()
        val others = List(2) { async { DispatcharrLoginGate.silentRelogin("P1", refusedKey = "K2") { attempts++; "K4" } } }
        yield()
        release.complete(Unit)
        assertEquals("K3", first.await())
        // They share the one re-login's key (different from the one they had refused).
        assertEquals(listOf("K3", "K3"), others.map { it.await() })
        assertEquals(1, attempts)
    }

    @Test fun theShareRule() {
        // A different key, or no refused key: share.
        assertEquals(DispatcharrLoginGate.Share.Share, DispatcharrLoginGate.shareRule("K3", lastKeyVerified = true, refusedKey = "K2"))
        assertEquals(DispatcharrLoginGate.Share.Share, DispatcharrLoginGate.shareRule("K3", lastKeyVerified = false, refusedKey = null))
        // The refused key itself: it passed its check, so it was rotated since (fresh); it never succeeded (park).
        assertEquals(DispatcharrLoginGate.Share.Fresh, DispatcharrLoginGate.shareRule("K2", lastKeyVerified = true, refusedKey = "K2"))
        assertEquals(DispatcharrLoginGate.Share.Park, DispatcharrLoginGate.shareRule("K2", lastKeyVerified = false, refusedKey = "K2"))
    }

    @Test fun aBurstOfFifteen401sSendsTwoRefusedRequestsInTotal() = runBlocking {
        // The old key and the key the re-login issues are both refused.
        refusedKeys += listOf("old-key", "minted-key")
        val burst = List(15) { async { after401("old-key") } }.awaitAll()
        assertTrue(burst.all { it.exceptionOrNull() is DispatcharrError.KeyRejected })
        // One probe of the old key (shared per key) and one check of the minted key.
        assertEquals("refused requests", 2, refused)
        assertEquals(1, probesOfOld)
        assertEquals(1, checks)
        assertEquals("one re-login for the whole burst", 1, logins)
        assertTrue("the old key is held: no background load sends it", DispatcharrRequestGate.isKeyRejected("old-key"))
        assertFalse("the refused minted key was never stored or handed out", DispatcharrRequestGate.isKeyRejected("minted-key"))
        assertEquals("old-key", stored)
        // The prompt names the key: the password signed in.
        assertFalse(DispatcharrRequestGate.prompt.value!!.passwordToo)
        // Every later caller, and the next minute's, sends nothing.
        repeat(15) { assertTrue(call().exceptionOrNull() is DispatcharrError.KeyRejected) }
        now += DispatcharrLoginGate.ATTEMPT_WINDOW_MS + 1
        repeat(15) { assertTrue(call().exceptionOrNull() is DispatcharrError.KeyRejected) }
        assertEquals(2, refused)
        assertEquals(1, logins)
    }

    @Test fun aReloginThatIssuesTheRefusedKeyAgainSendsTwoRefusedRequests() = runBlocking {
        refusedKeys += "old-key"
        minted = "old-key"
        val burst = List(15) { async { after401("old-key") } }.awaitAll()
        assertTrue(burst.all { it.exceptionOrNull() is DispatcharrError.KeyRejected })
        // The probe of the old key and the check of the same key the account reports.
        assertEquals(2, refused)
        assertEquals(1, logins)
        assertTrue(DispatcharrRequestGate.isKeyRejected("old-key"))
    }

    @Test fun aRotatedKeyStillRecovers() = runBlocking {
        refusedKeys += "old-key"
        assertEquals("channels", after401("old-key").getOrThrow())
        // The probe of the old key only; the minted key's check passed, then it was used.
        assertEquals(1, refused)
        assertEquals(1, checks)
        assertEquals("minted-key", stored)
        assertFalse(DispatcharrRequestGate.isKeyRejected("minted-key"))
    }

    @Test fun aKeyTheServerAcceptsIsNeverHeld() = runBlocking {
        // An endpoint-specific 401 for a key that works: the probe accepts it,
        // the re-login hands the same key back, and the call is replayed once.
        stored = "good-key"
        minted = "good-key"
        var calls = 0
        val endpoint = KeyRecoveryOps(
            block = { _: String -> calls++; throw DispatcharrError.Unauthorized("HTTP 403") },
            currentKeyIfReplaced = { null },
            probe = { key -> probe(key) },
            persistModeIfChanged = { null },
            relogin = { relogin() },
            loginRejected = { false },
            accountHasNoKey = { false },
            keyRefused = { DispatcharrLoginGate.foundRefusedKey("P1") },
            noteKeyRefused = { DispatcharrLoginGate.noteKeyRefused("P1") },
            hold = { key, passwordToo, noApiKey -> DispatcharrRequestGate.markKeyRejected("P1", key, "Home", passwordToo, noApiKey = noApiKey) },
            invalidate = {},
            log = { _, _ -> },
        )
        val r = runCatching { recoverFromUnauthorized("P1", "good-key", hasPassword = true, e = DispatcharrError.Unauthorized("HTTP 403"), ops = endpoint) }
        val err = r.exceptionOrNull()
        assertTrue(err is DispatcharrError.Unauthorized && err !is DispatcharrError.KeyRejected)
        assertEquals(1, calls)
        assertFalse(DispatcharrRequestGate.isKeyRejected("good-key"))
        assertFalse(DispatcharrLoginGate.foundRefusedKey("P1"))
    }
}
