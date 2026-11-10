package com.aeriotv.android.core.setup

import com.aeriotv.android.core.network.DispatcharrClient.KeyCheck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The sender checks the key it is about to hand over. */
class SetupKeyPreflightTest {
    private fun run(vararg answers: KeyCheck): Triple<SetupKeyVerdict, Int, List<Pair<Long, Boolean>>> = runBlocking {
        var i = 0
        val shown = mutableListOf<Pair<Long, Boolean>>()
        val v = preflightSetupKey(
            check = { answers[minOf(i++, answers.size - 1)] },
            onCountdown = { left, limited -> shown += left to limited },
            tick = {},
        )
        Triple(v, i, shown)
    }

    @Test fun acceptedSends() {
        val (v, calls, shown) = run(KeyCheck.Accepted)
        assertEquals(SetupKeyVerdict.Send, v)
        assertEquals(1, calls)
        assertEquals(emptyList<Pair<Long, Boolean>>(), shown)
    }

    @Test fun rejectedNeverSends() {
        val (v, calls, _) = run(KeyCheck.Rejected)
        assertEquals(SetupKeyVerdict.Rejected, v)
        assertEquals(1, calls)
    }

    @Test fun rateLimitedCountsDownThenRetries() {
        val (v, calls, shown) = run(KeyCheck.Unknown(2), KeyCheck.Rejected)
        assertEquals(SetupKeyVerdict.Rejected, v)
        assertEquals(2, calls)
        assertEquals(listOf(2L to true, 1L to true), shown)
    }

    @Test fun stillUnknownAfterTwoRetriesSends() {
        val (v, calls, _) = run(KeyCheck.Unknown(1), KeyCheck.Unknown(null), KeyCheck.Unknown(1))
        assertEquals(SetupKeyVerdict.Send, v)
        assertEquals(3, calls)
    }

    @Test fun networkErrorWaitsThreeSeconds() {
        val (_, _, shown) = run(KeyCheck.Unknown(null), KeyCheck.Accepted)
        assertEquals(listOf(3L to false, 2L to false, 1L to false), shown)
    }
}
