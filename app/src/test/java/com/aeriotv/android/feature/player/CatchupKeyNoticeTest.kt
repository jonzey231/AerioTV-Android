package com.aeriotv.android.feature.player

import com.aeriotv.android.core.network.DispatcharrRequestGate
import com.aeriotv.android.core.playback.StreamKeyRejectedException
import com.aeriotv.android.core.playback.StreamRouteHeldException
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Catch-up on a phone or tablet stops at once with the live player's "API
 * key no longer valid" notice when its API key is held (Apple stops a
 * catch-up with that alert); Android TV keeps its card.
 */
class CatchupKeyNoticeTest {
    private val headers = mapOf("X-API-Key" to "fake-key-for-tests")

    @Before fun setUp() {
        DispatcharrRequestGate.resetForTests()
        VodStreamRefusal.reset()
    }

    @After fun tearDown() {
        DispatcharrRequestGate.resetForTests()
        VodStreamRefusal.reset()
    }

    @Test fun aHeldKeyStopsCatchupOnPhonesAndTablets() {
        assertFalse(VodStreamRefusal.catchupKeyNotice(isCatchup = true, isTv = false, headers = headers))
        DispatcharrRequestGate.markKeyRejected("P1", "fake-key-for-tests")
        // The key the stream signs in with is held: a re-mint is stopped before it is sent.
        assertTrue(VodStreamRefusal.catchupKeyNotice(isCatchup = true, isTv = false, headers = headers))
        // Header names are case-insensitive.
        assertTrue(VodStreamRefusal.catchupKeyNotice(true, false, mapOf("x-api-key" to "fake-key-for-tests")))
        // Not catch-up, or Android TV: unchanged.
        assertFalse(VodStreamRefusal.catchupKeyNotice(isCatchup = false, isTv = false, headers = headers))
        assertFalse(VodStreamRefusal.catchupKeyNotice(isCatchup = true, isTv = true, headers = headers))
    }

    @Test fun aStreamRefusedWithTheHeldKeyStopsCatchup() {
        // The open was refused with the held key: the usual card line (null), no auto retry, and the notice.
        assertNull(VodStreamRefusal.onError(StreamKeyRejectedException(), "http://tv.example.test/proxy/catchup/x"))
        assertTrue(VodStreamRefusal.noAutoRetry)
        assertTrue(VodStreamRefusal.catchupKeyNotice(isCatchup = true, isTv = false, headers = emptyMap()))
        // A held Child's address is not a rejected key: its own line, not the key notice.
        VodStreamRefusal.reset()
        VodStreamRefusal.onError(StreamRouteHeldException(), "http://tv.example.test/proxy/catchup/x")
        assertFalse(VodStreamRefusal.catchupKeyNotice(isCatchup = true, isTv = false, headers = emptyMap()))
        // Any other error clears it.
        VodStreamRefusal.onError(java.io.IOException("boom"), null)
        assertFalse(VodStreamRefusal.catchupKeyNotice(isCatchup = true, isTv = false, headers = emptyMap()))
    }
}
