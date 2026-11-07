package com.aeriotv.android.feature.livetv.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RailBandSlotsTest {
    private val c = 100f
    private val w = 10f

    @Test fun favoriteAndCatchupPackWithNoGap() {
        val s = railBandSlotLefts(c, w, favorite = true, radio = false, catchup = true)
        assertEquals(100f, s.clock); assertNull(s.radio); assertEquals(90f, s.star)
    }
    @Test fun allThree() {
        val s = railBandSlotLefts(c, w, favorite = true, radio = true, catchup = true)
        assertEquals(100f, s.clock); assertEquals(90f, s.radio); assertEquals(80f, s.star)
    }
    @Test fun radioTakesCornerWithoutClock() {
        val s = railBandSlotLefts(c, w, favorite = true, radio = true, catchup = false)
        assertNull(s.clock); assertEquals(100f, s.radio); assertEquals(90f, s.star)
    }
    @Test fun starAloneTakesCorner() {
        val s = railBandSlotLefts(c, w, favorite = true, radio = false, catchup = false)
        assertEquals(100f, s.star); assertNull(s.radio); assertNull(s.clock)
    }
    @Test fun clampsAtZero() {
        val s = railBandSlotLefts(5f, w, favorite = true, radio = true, catchup = true)
        assertEquals(0f, s.radio); assertEquals(0f, s.star)
    }
}
