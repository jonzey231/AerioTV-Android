package com.aeriotv.android.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateClassTest {
    private fun target(fps: Float, film: Boolean = true, has23976: Boolean = true) =
        FrameRateClass.target(fps, film) { has23976 }

    @Test fun noDeclaredRateMeansMeasure() {
        assertNull(FrameRateClass.declared(-1f))
        assertNull(FrameRateClass.declared(0f))
        assertNull(FrameRateClass.declared(Float.NaN))
        assertEquals(24f, FrameRateClass.declared(24f))
    }

    @Test fun declared24RequestsTwentyFourEvenWithA23976Mode() {
        assertEquals(24f, target(24f), 0f)
    }

    @Test fun declared23976RequestsTheFilmModeWhenThePanelHasIt() {
        assertEquals(23.976f, target(24000f / 1001f), 0f)
        assertEquals(24f, target(24000f / 1001f, has23976 = false), 0f)
    }

    @Test fun liveNeverRequestsTheFilmClass() {
        assertEquals(0f, target(24f, film = false), 0f)
    }

    @Test fun lowRatesDoubleAndHighRatesPassThrough() {
        assertEquals(50f, target(25f), 0f)
        assertEquals(59.94f, target(29.97f), 0.001f)
        assertEquals(59.94f, target(59.94f), 0f)
    }
}
