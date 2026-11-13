package com.aeriotv.android.core.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The prompt's own sign-in must be able to check the key it was just issued
 * and lift the hold (2026-11-12, measured on a phone, a blocker): a re-login
 * against another server had recorded the real key as replaced by that
 * server's key, which the re-login guard then held (key refused). Signing in
 * again with the right password got the real key back, and its check was
 * swapped to the held key and refused locally, every time.
 */
class SignInAfterKeyRefusedTest {
    private var now = 1_000_000L

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

    @Test fun aFreshKeyIsCheckedAsItIsAndTheHoldLifts() {
        // The re-login on the other server replaced the real key with that server's key...
        DispatcharrRequestGate.noteKeyReplaced("real-key", "other-server-key")
        // ...and back on the real server the guard held it (the key-refused path).
        DispatcharrLoginGate.noteKeyRefused("P1")
        DispatcharrRequestGate.markKeyRejected("P1", "other-server-key", "Home", passwordToo = false)
        assertTrue(DispatcharrLoginGate.foundRefusedKey("P1"))

        // The sign-in form's check of the real key goes out as the real key.
        assertNull(keySwap("real-key", bypassKeyGate = true))
        // Even a request without the bypass is never swapped into a held key.
        assertNull(keySwap("real-key", bypassKeyGate = false))
        assertFalse("the real key itself is not held", DispatcharrRequestGate.isKeyRejected("real-key"))

        // Signed in (writeSignIn and the login gate reset): the hold lifts, and
        // the real key is never swapped for another one again.
        DispatcharrRequestGate.clearPlaylist("P1")
        DispatcharrRequestGate.clearKey("real-key")
        DispatcharrLoginGate.reset("P1")
        assertFalse(DispatcharrLoginGate.foundRefusedKey("P1"))
        assertNull(DispatcharrRequestGate.replacementFor("real-key"))
    }

    @Test fun aReplacedKeyStillGoesOutAsTheCurrentOne() {
        // A request built before a re-sign-in replaced the key goes out with the new key.
        DispatcharrRequestGate.noteKeyReplaced("old-key", "new-key")
        assertEquals("new-key", keySwap("old-key", bypassKeyGate = false))
        // An explicit check of the old key is sent as the old key.
        assertNull(keySwap("old-key", bypassKeyGate = true))
    }
}
