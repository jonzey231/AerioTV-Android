package com.aeriotv.android.feature.dvr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Apple DVRFormat.duration parity, byte for byte. */
class DvrFormatTest {
    private fun min(m: Long, s: Long = 0) = (m * 60 + s) * 1000

    @Test fun underAMinuteIsOneMinute() {
        assertEquals("1 min", DvrFormat.duration(0L))
        assertEquals("1 min", DvrFormat.duration(min(0, 37)))
        assertEquals("1 min", DvrFormat.duration(-5_000L))
    }

    @Test fun minutesRoundDown() {
        assertEquals("1 min", DvrFormat.duration(min(1, 59)))
        assertEquals("45 min", DvrFormat.duration(min(45)))
        assertEquals("59 min", DvrFormat.duration(min(59, 59)))
    }

    @Test fun wholeHoursDropTheMinutes() {
        assertEquals("1 h", DvrFormat.duration(min(60)))
        assertEquals("2 h", DvrFormat.duration(min(120, 30)))
    }

    @Test fun hoursAndMinutes() {
        assertEquals("2 h 5 min", DvrFormat.duration(min(125)))
        assertEquals("3 h 35 min", DvrFormat.duration(min(215)))
    }
}
