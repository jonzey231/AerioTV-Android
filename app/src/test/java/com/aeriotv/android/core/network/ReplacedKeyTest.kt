package com.aeriotv.android.core.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * After a silent re-sign-in replaces a key, requests built with the old key
 * use the new one, and a 401 on the old key retries with the current key
 * instead of probing: no failed-key attempts are spent on a key that is gone.
 */
class ReplacedKeyTest {
    private var now = 1_000_000L

    @Before fun setUp() {
        DispatcharrRequestGate.resetForTests()
        DispatcharrRequestGate.clock = { now }
    }

    @After fun tearDown() {
        DispatcharrRequestGate.resetForTests()
        DispatcharrRequestGate.clock = { System.nanoTime() / 1_000_000 }
    }

    @Test fun replacedKeyMapsToTheNewOneForTenMinutes() {
        DispatcharrRequestGate.noteKeyReplaced("old", "new")
        assertEquals("new", DispatcharrRequestGate.replacementFor("old"))
        assertEquals("new", DispatcharrRequestGate.replacementFor(" old "))
        assertNull(DispatcharrRequestGate.replacementFor("new"))
        now += DispatcharrRequestGate.REPLACED_KEY_TTL_MS - 1
        assertEquals("new", DispatcharrRequestGate.replacementFor("old"))
        now += 1
        assertNull(DispatcharrRequestGate.replacementFor("old"))
    }

    @Test fun chainedReplacementsLandOnTheLatestKey() {
        DispatcharrRequestGate.noteKeyReplaced("a", "b")
        DispatcharrRequestGate.noteKeyReplaced("b", "c")
        assertEquals("c", DispatcharrRequestGate.replacementFor("a"))
        assertEquals("c", DispatcharrRequestGate.replacementFor("b"))
    }

    @Test fun sameOrBlankIsNotAReplacement() {
        DispatcharrRequestGate.noteKeyReplaced("k", "k")
        DispatcharrRequestGate.noteKeyReplaced("", "n")
        DispatcharrRequestGate.noteKeyReplaced("o", " ")
        assertNull(DispatcharrRequestGate.replacementFor("k"))
        assertNull(DispatcharrRequestGate.replacementFor("o"))
    }

    @Test fun headersCarryingTheOldKeyAreSwapped() {
        assertEquals("new" to "ApiKey new", swappedAuthHeaders("old", "ApiKey old", "old", "new"))
        assertEquals("new" to null, swappedAuthHeaders("old", null, "old", "new"))
        assertEquals(null to "Bearer new", swappedAuthHeaders(null, "Bearer old", "old", "new"))
        // A JWT or another key is left alone.
        assertEquals(null to null, swappedAuthHeaders("other", "Bearer eyJhbGciOi.x.y", "old", "new"))
        assertEquals(null to null, swappedAuthHeaders(null, "Basic old", "old", "new"))
    }

    @Test fun a401OnAReplacedKeyRetriesWithTheCurrentOne() {
        val none: (String) -> Boolean = { false }
        assertEquals("new", currentKeyAfterSwap("old", "new", none))
        assertEquals("new", currentKeyAfterSwap("old", " new ", none))
    }

    @Test fun a401OnTheCurrentKeyProbesAsUsual() {
        val none: (String) -> Boolean = { false }
        assertNull(currentKeyAfterSwap("key", "key", none))
        assertNull(currentKeyAfterSwap("key", null, none))
        assertNull(currentKeyAfterSwap("key", "", none))
        // The new key is itself held as rejected: no retry with it.
        assertNull(currentKeyAfterSwap("old", "new") { it == "new" })
    }
}
