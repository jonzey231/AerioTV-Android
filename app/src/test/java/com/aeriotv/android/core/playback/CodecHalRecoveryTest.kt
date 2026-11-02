package com.aeriotv.android.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecHalRecoveryTest {
    private var now = 0L
    private val r = CodecHalRecovery { now }

    @Test fun videoStaysVideo() {
        val d = r.decide(radioVideoOff = false, radioStartInFlight = false) as CodecHalRecovery.Decision.Rebuild
        assertEquals(CodecHalRecovery.Mode.VIDEO, d.mode)
        assertEquals(400L, d.settleMs)
    }

    @Test fun radioStaysRadio() {
        val d = r.decide(radioVideoOff = true, radioStartInFlight = false) as CodecHalRecovery.Decision.Rebuild
        assertEquals(CodecHalRecovery.Mode.RADIO, d.mode)
    }

    @Test fun inFlightRadioStartCompletesAsRadio() {
        val d = r.decide(radioVideoOff = false, radioStartInFlight = true) as CodecHalRecovery.Decision.Rebuild
        assertEquals(CodecHalRecovery.Mode.RADIO, d.mode)
    }

    @Test fun secondRebuildSettlesLongerThenCaps() {
        r.decide(false, false)
        now += 2_000
        val second = r.decide(false, false) as CodecHalRecovery.Decision.Rebuild
        assertEquals(1_000L, second.settleMs)
        assertEquals(2, second.attempt)
        now += 2_000
        assertTrue(r.decide(false, false) is CodecHalRecovery.Decision.CapReached)
    }

    @Test fun capResetsAfterWindow() {
        r.decide(false, false); r.decide(false, false)
        now += 30_001
        val d = r.decide(false, false) as CodecHalRecovery.Decision.Rebuild
        assertEquals(400L, d.settleMs)
    }
}
