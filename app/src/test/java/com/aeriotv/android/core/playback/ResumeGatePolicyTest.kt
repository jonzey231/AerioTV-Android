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

    @Test fun noArmInsideSwitchWindow() {
        // 01:24:18 on the Streamer: armed inside the window with rate n/a.
        assertFalse(ResumeGatePolicy.shouldArm(37, 5_414, null, repeat = false, inSwitchWindow = true))
        assertTrue(ResumeGatePolicy.shouldArm(37, 5_414, null, repeat = false, inSwitchWindow = false))
    }

    @Test fun noEarlyReleaseInsideSwitchWindow() {
        // Released at 4737 >= 4640 after 12.9 s, then the next switch dropped it.
        assertNull(ResumeGatePolicy.releaseOnTick(4_737, 4_640, null, 12_950, 25_000, inSwitchWindow = true))
        assertNull(ResumeGatePolicy.releaseOnTick(9_000, 5_483, 0.5, 4_000, 25_000, inSwitchWindow = true))
        assertEquals(
            Release.TARGET_REACHED,
            ResumeGatePolicy.releaseOnTick(5_483, 5_483, null, 14_000, 25_000, inSwitchWindow = false),
        )
    }

    @Test fun timeoutStillBoundsAnExtendedHold() {
        assertEquals(
            Release.TIMEOUT,
            ResumeGatePolicy.releaseOnTick(1_000, 5_483, null, 25_000, 25_000, inSwitchWindow = true),
        )
    }

    @Test fun targetNeverShrinksDuringAHold() {
        assertEquals(5_414, ResumeGatePolicy.effectiveTarget(4_640, 5_414))
        assertEquals(5_483, ResumeGatePolicy.effectiveTarget(5_483, 5_414))
        // 2015 ms against 5483 must not count as reached.
        assertNull(
            ResumeGatePolicy.releaseOnTick(
                2_015, ResumeGatePolicy.effectiveTarget(2_000, 5_483), null, 3_000, 25_000,
            ),
        )
    }

    @Test fun deferredHoldArmsOnceWhenWindowCloses() {
        assertFalse(ResumeGatePolicy.shouldArmAfterSwitch(pending = true, inSwitchWindow = true, gateActive = false))
        assertTrue(ResumeGatePolicy.shouldArmAfterSwitch(pending = true, inSwitchWindow = false, gateActive = false))
        assertFalse(ResumeGatePolicy.shouldArmAfterSwitch(pending = false, inSwitchWindow = false, gateActive = false))
        assertFalse(ResumeGatePolicy.shouldArmAfterSwitch(pending = true, inSwitchWindow = false, gateActive = true))
    }

    @Test fun windowHeldOpenByAnUnrenderedSource() {
        val w = ResumeGatePolicy.SwitchWindow()
        assertTrue(w.noteSwitch(1_000, boundaryMs = 50_000))
        // Old buffer playing out below the boundary is not the first frame.
        assertFalse(w.noteTick(3_000, positionMs = 49_000, advanced = true))
        // At the boundary but not advancing: not rendered.
        assertFalse(w.noteTick(4_000, positionMs = 50_000, advanced = false))
        assertTrue(w.isOpen(20_000))
    }

    @Test fun firstFrameThenSettleClosesTheWindow() {
        val w = ResumeGatePolicy.SwitchWindow()
        w.noteSwitch(1_000, boundaryMs = 50_000)
        assertTrue(w.noteTick(4_000, positionMs = 50_200, advanced = true))
        assertFalse(w.noteTick(4_500, positionMs = 50_700, advanced = true))
        assertTrue(w.isOpen(8_999))
        assertFalse(w.isOpen(9_000))
        assertTrue(ResumeGatePolicy.shouldArmAfterSwitch(pending = true, inSwitchWindow = w.isOpen(9_000), gateActive = false))
    }

    @Test fun laterSwitchReArmsTheWindow() {
        val w = ResumeGatePolicy.SwitchWindow()
        w.noteSwitch(1_000, boundaryMs = 50_000)
        w.noteTick(4_000, positionMs = 50_200, advanced = true)
        // Re-armed before it settled: stays open, first frame cleared, boundary kept.
        assertFalse(w.noteSwitch(6_000, boundaryMs = 53_000))
        assertNull(w.firstFrameAtMs)
        assertEquals(50_000L, w.boundaryMs)
        assertTrue(w.isOpen(12_000))
        assertTrue(w.noteTick(13_000, positionMs = 51_000, advanced = true))
        assertFalse(w.isOpen(18_000))
    }

    @Test fun capClosesAWindowWhoseSourceNeverRenders() {
        val w = ResumeGatePolicy.SwitchWindow()
        w.noteSwitch(1_000, boundaryMs = 50_000)
        assertTrue(w.isOpen(30_999))
        assertFalse(w.isOpen(31_000))
        assertFalse(w.noteTick(31_000, positionMs = 51_000, advanced = true))
        // A closed window reopens on a new switch with a fresh boundary.
        assertTrue(w.noteSwitch(40_000, boundaryMs = 60_000))
        assertEquals(60_000L, w.boundaryMs)
    }

    @Test fun staleSwitchIsIgnored() {
        val w = ResumeGatePolicy.SwitchWindow()
        w.noteSwitch(10_000, boundaryMs = 50_000)
        w.noteTick(12_000, positionMs = 50_100, advanced = true)
        assertFalse(w.noteSwitch(9_000, boundaryMs = 70_000))
        assertFalse(w.noteSwitch(10_000, boundaryMs = 70_000))
        assertEquals(12_000L, w.firstFrameAtMs)
        assertEquals(50_000L, w.boundaryMs)
        assertFalse(w.isOpen(17_000))
    }
}
