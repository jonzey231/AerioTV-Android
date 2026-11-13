package com.aeriotv.android.core.data

import com.aeriotv.android.core.data.DvrStorageCapRules as D
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings > DVR > Maximum in 0.25 GB steps: the same cases as Apple's dispatcharr-gate-tests (6660eef). */
class DvrStorageCapRulesTest {
    private val us = Locale.US
    private val de = Locale.GERMANY

    @Test fun labelHasNoTrailingZerosAndTheLocaleSeparator() {
        assertEquals("10 GB", D.label(10 * 1024, us))
        assertEquals("10.25 GB", D.label(10 * 1024 + 256, us))
        assertEquals("10.5 GB", D.label(10 * 1024 + 512, us))
        assertEquals("10.75 GB", D.label(10 * 1024 + 768, us))
        assertEquals("200 GB, no grouping", "200 GB", D.label(200 * 1024, us))
        assertEquals("German decimal comma", "10,25 GB", D.label(10 * 1024 + 256, de))
        assertEquals("whole GB in German", "1 GB", D.label(1024, de))
    }

    @Test fun theSliderSnapsToTheNearestQuarterGigabyteInRange() {
        assertEquals("10.1 snaps to 10", 10 * 1024, D.snappedMB(10.1))
        assertEquals("10.13 snaps to 10.25", 10 * 1024 + 256, D.snappedMB(10.13))
        assertEquals("below 1 GB clamps", 1024, D.snappedMB(0.2))
        assertEquals("above 200 GB clamps", 200 * 1024, D.snappedMB(250.0))
        assertEquals("NaN is the minimum", 1024, D.snappedMB(Double.NaN))
        // Whole-GB values saved before stay on a step (no migration).
        listOf(1, 10, 37, 200).forEach { gb -> assertEquals("$gb GB is on a step", 0, (gb * 1024) % D.STEP_MB) }
    }

    @Test fun tvStepsLandOnWholeMultiplesWithinRange() {
        assertEquals("press up: +0.25", 10 * 1024 + 256, D.stepped(10 * 1024, 256, up = true))
        assertEquals("press down: -0.25", 9 * 1024 + 768, D.stepped(10 * 1024, 256, up = false))
        assertEquals("1 GB step lands on whole GB", 11 * 1024, D.stepped(10 * 1024 + 256, 1024, up = true))
        assertEquals("1 GB step down lands on whole GB", 10 * 1024, D.stepped(10 * 1024 + 256, 1024, up = false))
        assertEquals("5 GB step lands on a multiple of 5", 15 * 1024, D.stepped(12 * 1024, 5 * 1024, up = true))
        assertEquals("floor at 1 GB", 1024, D.stepped(1024, 256, up = false))
        assertEquals("ceiling at 200 GB", 200 * 1024, D.stepped(200 * 1024, 5 * 1024, up = true))
    }

    @Test fun theHoldScheduleSpeedsUp() {
        assertTrue("press and 8 repeats: 0.25 GB", D.holdStepMB(0) == 256 && D.holdStepMB(8) == 256)
        assertTrue("next 12 repeats: 1 GB", D.holdStepMB(9) == 1024 && D.holdStepMB(20) == 1024)
        assertEquals("then 5 GB", 5 * 1024, D.holdStepMB(21))
        // A held Right from 10 GB reaches 100 GB in 37 adjustments: the press,
        // then 36 repeats (0.4 s, then one every 0.1 s), about 4 s.
        var mb = 10 * 1024
        var n = 0
        while (mb < 100 * 1024) { mb = D.stepped(mb, D.holdStepMB(n), up = true); n++ }
        assertEquals(100 * 1024, mb)
        assertEquals(37, n)
        assertEquals(3_900L, D.HOLD_DELAY_MS + (n - 2) * D.REPEAT_MS)
    }
}
