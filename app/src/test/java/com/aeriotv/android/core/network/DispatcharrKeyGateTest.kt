package com.aeriotv.android.core.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The rejected-key gate: per playlist and key, prompt once, cleared by an edit or a proven key. */
class DispatcharrKeyGateTest {
    @Before fun setUp() = DispatcharrRequestGate.resetForTests()
    @After fun tearDown() = DispatcharrRequestGate.resetForTests()

    @Test fun rejectedKeyIsHeldAndPromptsOnce() {
        assertFalse(DispatcharrRequestGate.isKeyRejected("old-key"))
        assertTrue(DispatcharrRequestGate.markKeyRejected("p1", "old-key", "Home", passwordToo = false))
        assertTrue(DispatcharrRequestGate.isKeyRejected("old-key"))
        assertTrue(DispatcharrRequestGate.isPlaylistRejected("p1"))
        assertEquals(DispatcharrRequestGate.Prompt("p1", "Home", false), DispatcharrRequestGate.prompt.value)
        DispatcharrRequestGate.consumePrompt()
        // Every later call for the same rejection: no second prompt.
        assertFalse(DispatcharrRequestGate.markKeyRejected("p1", "old-key", "Home"))
        assertNull(DispatcharrRequestGate.prompt.value)
    }

    @Test fun otherKeysAreNotHeld() {
        DispatcharrRequestGate.markKeyRejected("p1", "old-key")
        assertFalse(DispatcharrRequestGate.isKeyRejected("new-key"))
        assertFalse(DispatcharrRequestGate.isKeyRejected(""))
        assertFalse(DispatcharrRequestGate.isPlaylistRejected("p2"))
    }

    @Test fun editClearsThePlaylistAndItsPrompt() {
        DispatcharrRequestGate.markKeyRejected("p1", "old-key")
        DispatcharrRequestGate.clearPlaylist("p1")
        assertFalse(DispatcharrRequestGate.isKeyRejected("old-key"))
        assertNull(DispatcharrRequestGate.prompt.value)
        // A new rejection after the edit prompts again.
        assertTrue(DispatcharrRequestGate.markKeyRejected("p1", "old-key"))
    }

    @Test fun clearingOnePlaylistKeepsAnotherWithTheSameKey() {
        DispatcharrRequestGate.markKeyRejected("p1", "shared")
        DispatcharrRequestGate.markKeyRejected("p2", "shared")
        DispatcharrRequestGate.clearPlaylist("p1")
        assertTrue(DispatcharrRequestGate.isKeyRejected("shared"))
        assertTrue(DispatcharrRequestGate.isPlaylistRejected("p2"))
    }

    @Test fun provenKeyClearsEverywhere() {
        DispatcharrRequestGate.markKeyRejected("p1", "k")
        DispatcharrRequestGate.markKeyRejected("p2", "k")
        DispatcharrRequestGate.clearKey("k")
        assertFalse(DispatcharrRequestGate.isKeyRejected("k"))
        assertNull(DispatcharrRequestGate.prompt.value)
    }

    @Test fun keyIsHashedAndTrimmed() {
        val h = DispatcharrRequestGate.keyHash("abc")
        assertEquals(24, h.length)
        assertFalse(h.contains("abc"))
        assertEquals(h, DispatcharrRequestGate.keyHash(" abc "))
    }

    @Test fun keyReadFromEveryHeaderShape() {
        assertEquals("k1", requestApiKey("k1", "ApiKey k1"))
        assertEquals("k2", requestApiKey(null, "ApiKey k2"))
        assertEquals("k3", requestApiKey(null, "Bearer k3"))
        assertNull(requestApiKey(null, "Basic abc"))
        assertNull(requestApiKey(null, null))
        assertNull(requestApiKey("", "ApiKey"))
    }

    @Test fun dispatcharrInvalidKeyBodyRecognized() {
        assertTrue(isDispatcharrInvalidKeyBody("{\"detail\":\"Invalid API key\"}"))
        // A deactivated account's key: final for every header shape, and counted by the server.
        assertTrue(isDispatcharrInvalidKeyBody("{\"detail\":\"User inactive\"}"))
        assertFalse(isDispatcharrInvalidKeyBody("{\"detail\":\"Authentication credentials were not provided.\"}"))
        assertFalse(isDispatcharrInvalidKeyBody(""))
    }

    @Test fun keyRejectedStopsLikeA401() {
        val e: Throwable = DispatcharrError.KeyRejected()
        assertTrue(e is DispatcharrError.Unauthorized)
        assertNull(e.rateLimitSeconds())
    }
}
