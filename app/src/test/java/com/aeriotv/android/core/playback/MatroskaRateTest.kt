package com.aeriotv.android.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class MatroskaRateTest {
    @Test fun videoDefaultDurationIsTheDeclaredRate() {
        // DefaultDuration 41666667 ns = 24/1 ("#Xmas (2022)", device pass 2026-11-14).
        assertEquals(24f, MatroskaRate.fps(1, 41_666_667), 0.001f)
        assertEquals(23.976f, MatroskaRate.fps(1, 41_708_333), 0.001f)
    }

    @Test fun audioOrMissingDurationDeclaresNothing() {
        assertEquals(0f, MatroskaRate.fps(2, 21_333_333), 0f)
        assertEquals(0f, MatroskaRate.fps(1, 0), 0f)
    }
}
