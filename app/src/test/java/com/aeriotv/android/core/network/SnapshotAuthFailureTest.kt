package com.aeriotv.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * The permissions check (fetchAccountSnapshot) treats most failures as "no
 * answer", but never a rejected key: that has to reach the key broker so the
 * hold starts and the DVR tab's 30 s refresh stops spending failed-key attempts.
 */
class SnapshotAuthFailureTest {
    private fun rethrown(t: Throwable): Throwable? = try {
        Result.failure<String?>(t).orNullKeepingAuthFailures()
        null
    } catch (e: Throwable) {
        e
    }

    @Test fun answerPassesThrough() {
        assertEquals("me", Result.success<String?>("me").orNullKeepingAuthFailures())
        assertNull(Result.success<String?>(null).orNullKeepingAuthFailures())
    }

    @Test fun otherFailuresAreNoAnswer() {
        assertNull(rethrown(DispatcharrError.Transport("HTTP 500 from /api/accounts/users/me/")))
        assertNull(rethrown(DispatcharrError.RateLimited(30)))
        assertNull(rethrown(java.io.IOException("reset")))
    }

    @Test fun rejectedKeyReachesTheBroker() {
        val e = DispatcharrError.Unauthorized("HTTP 401")
        if (rethrown(e) !== e) fail("a 401 must be rethrown, not folded into null")
        val held = DispatcharrError.KeyRejected()
        if (rethrown(held) !== held) fail("a held key must be rethrown, not folded into null")
    }

    @Test fun cancellationIsRethrown() {
        val c = kotlinx.coroutines.CancellationException("left")
        if (rethrown(c) !== c) fail("a cancellation must be rethrown")
    }
}
