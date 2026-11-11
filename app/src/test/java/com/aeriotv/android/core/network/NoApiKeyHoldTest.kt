package com.aeriotv.android.core.network

import com.aeriotv.android.feature.settings.KeyRejectedCopy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A password playlist whose account's API key was removed on the server
 * (2026-11-11, Apple parity): the re-login signs in but users/me has no key.
 * The key is held (no more requests with it), the prompt says the account has
 * no API key, and the re-check is a re-login, never a key request.
 */
class NoApiKeyHoldTest {
    @Before fun setUp() = DispatcharrRequestGate.resetForTests()
    @After fun tearDown() = DispatcharrRequestGate.resetForTests()

    @Test fun reloginOutcomeDecidesTheHold() {
        // Signed in, no API key: hold whatever the probe said.
        assertEquals(ReloginHold.NoApiKey, reloginHold(keyRejected = true, loginRejected = false, accountHasNoKey = true))
        assertEquals(ReloginHold.NoApiKey, reloginHold(keyRejected = false, loginRejected = false, accountHasNoKey = true))
        // Password refused too: hold only when the probe also rejected the key (as before).
        assertEquals(ReloginHold.PasswordRefused, reloginHold(keyRejected = true, loginRejected = true, accountHasNoKey = false))
        assertEquals(ReloginHold.None, reloginHold(keyRejected = false, loginRejected = true, accountHasNoKey = false))
        // A network failure: not held.
        assertEquals(ReloginHold.None, reloginHold(keyRejected = true, loginRejected = false, accountHasNoKey = false))
    }

    @Test fun noKeyHoldStopsTheKeyAndPromptsOnce() {
        assertTrue(DispatcharrRequestGate.markKeyRejected("p1", "dead-key", "Home", passwordToo = true, baseUrl = "http://server:9191", noApiKey = true))
        assertTrue("no more requests with the dead key", DispatcharrRequestGate.isKeyRejected("dead-key"))
        val p = DispatcharrRequestGate.prompt.value!!
        assertTrue(p.noApiKey)
        assertEquals(
            "This account has no API key yet. Ask your Dispatcharr administrator to turn on API access for it.",
            KeyRejectedCopy.body(p.playlistName, p.passwordToo, p.noApiKey),
        )
        // The form's no-key line is the same string.
        assertEquals(KeyRejectedCopy.NO_API_KEY, KeyRejectedCopy.body("Home", passwordToo = true, noApiKey = true))
        // Every later 401 for the same key: no second prompt.
        DispatcharrRequestGate.consumePrompt()
        assertFalse(DispatcharrRequestGate.markKeyRejected("p1", "dead-key", "Home", passwordToo = true, noApiKey = true))
        // The automatic re-check is a re-login (passwordToo), not a key request.
        assertTrue(DispatcharrRequestGate.rechecks().single().passwordToo)
    }

    @Test fun otherPromptsKeepTheirBody() {
        DispatcharrRequestGate.markKeyRejected("p2", "k", "Home", passwordToo = false)
        val p = DispatcharrRequestGate.prompt.value!!
        assertFalse(p.noApiKey)
        assertTrue(KeyRejectedCopy.body(p.playlistName, p.passwordToo, p.noApiKey).startsWith("Dispatcharr no longer accepts the API key for Home."))
    }
}
