package com.aeriotv.android.core.playback

import com.aeriotv.android.core.playback.ResumeGatePolicy.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeGatePolicyTest {
    @Test fun armsOnEmptyBufferWithHealthyFeed() {
        assertTrue(ResumeGatePolicy.shouldArm(0, 7_223, 1.0, repeat = false))
        assertTrue(ResumeGatePolicy.shouldArm(0, 7_223, null, repeat = false))
    }

    @Test fun noArmWhenBufferAlreadyAtTarget() {
        assertFalse(ResumeGatePolicy.shouldArm(8_000, 7_223, 1.0, repeat = false))
        assertFalse(ResumeGatePolicy.shouldArm(0, 0, 1.0, repeat = false))
    }

    @Test fun repeatStallOnSlowFeedDoesNotArm() {
        // 01:14:57 on the Streamer: repeat stall at 0.68x held 15.5 s.
        assertFalse(ResumeGatePolicy.shouldArm(1_376, 10_268, 0.68, repeat = true))
        assertFalse(ResumeGatePolicy.shouldArm(1_376, 7_000, 0.77, repeat = false))
        assertTrue(ResumeGatePolicy.shouldArm(1_376, 10_268, 0.95, repeat = true))
    }

    @Test fun releasesTheMomentTargetIsReached() {
        assertEquals(Release.TARGET_REACHED, ResumeGatePolicy.releaseOnTick(7_223, 7_223, 0.5, 100, 25_000))
        assertNull(ResumeGatePolicy.releaseOnTick(7_222, 7_223, 1.0, 100, 25_000))
    }

    @Test fun releasesAStarvedHoldAfterSettle() {
        assertNull(ResumeGatePolicy.releaseOnTick(3_000, 7_223, 0.6, 2_999, 25_000))
        assertEquals(Release.FEED_STARVED, ResumeGatePolicy.releaseOnTick(3_000, 7_223, 0.6, 3_000, 25_000))
        assertNull(ResumeGatePolicy.releaseOnTick(3_000, 7_223, null, 10_000, 25_000))
    }

    @Test fun hardTimeout() {
        assertEquals(Release.TIMEOUT, ResumeGatePolicy.releaseOnTick(3_000, 7_223, 1.0, 25_000, 25_000))
    }

    @Test fun wantsToPlayDuringHolds() {
        assertTrue(ResumeGatePolicy.wantsToPlay(false, gateHolding = true, audioOnlyHolding = false))
        assertTrue(ResumeGatePolicy.wantsToPlay(false, gateHolding = false, audioOnlyHolding = true))
        assertFalse(ResumeGatePolicy.wantsToPlay(false, gateHolding = false, audioOnlyHolding = false))
    }
}
