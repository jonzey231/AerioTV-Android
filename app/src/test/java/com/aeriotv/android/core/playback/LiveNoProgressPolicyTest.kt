package com.aeriotv.android.core.playback

import com.aeriotv.android.core.playback.LiveNoProgressPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveNoProgressPolicyTest {
    private val budget = 50_000L
    private val mb = 1024L * 1024L

    private fun decide(elapsed: Long, bytes: Long, sinceLast: Long, progress: Boolean = false) =
        LiveNoProgressPolicy.decide(elapsed, budget, bytes, sinceLast, progress)

    @Test fun bytesFlowingNoProgressRebuilds() {
        // The 18:20 Streamer case: 47 MB delivered, last byte just now.
        assertEquals(Verdict.REBUILD, decide(elapsed = 20_000, bytes = 47 * mb, sinceLast = 200))
        assertEquals(Verdict.REBUILD, decide(elapsed = 50_871, bytes = 47 * mb, sinceLast = 200))
    }

    @Test fun bytesFlowingInsideBudgetWaits() {
        assertEquals(Verdict.WAIT, decide(elapsed = 5_000, bytes = 4 * mb, sinceLast = 100))
    }

    @Test fun noBytesWalksAtSilenceBudget() {
        assertEquals(Verdict.WALK, decide(elapsed = 50_000, bytes = 0, sinceLast = 50_000))
        assertEquals(Verdict.WAIT, decide(elapsed = 20_000, bytes = 0, sinceLast = 20_000))
    }

    @Test fun bytesThatStoppedCountAsSilence() {
        assertEquals(Verdict.WALK, decide(elapsed = 60_000, bytes = 2 * mb, sinceLast = 40_000))
    }

    @Test fun tooFewBytesCountAsSilence() {
        assertEquals(Verdict.WALK, decide(elapsed = 50_000, bytes = 1_000, sinceLast = 100))
    }

    @Test fun decodedProgressAlwaysWaits() {
        assertEquals(Verdict.WAIT, decide(elapsed = 90_000, bytes = 0, sinceLast = 90_000, progress = true))
        assertEquals(Verdict.WAIT, decide(elapsed = 90_000, bytes = 9 * mb, sinceLast = 10, progress = true))
    }
}
