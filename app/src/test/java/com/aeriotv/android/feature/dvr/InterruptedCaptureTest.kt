package com.aeriotv.android.feature.dvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Apple 2d82156 InterruptedCapture parity: a row left recording with no capture behind it. */
class InterruptedCaptureTest {
    private val start = 1_000_000L
    private val now = start + 3_600_000L

    @Test fun aCaptureWithDataKeepsItsSizeAndRealLength() {
        val out = InterruptedCapture.outcome(fileBytes = 19_430_928L, lastWriteAt = start + 21_000L, startedAt = start, now = now)
        assertEquals(InterruptedCapture.Outcome.Interrupted(19_430_928L, start + 21_000L), out)
    }

    @Test fun aMissingLastWriteFallsBackToNow() {
        val out = InterruptedCapture.outcome(fileBytes = 5L, lastWriteAt = null, startedAt = start, now = now)
        assertEquals(InterruptedCapture.Outcome.Interrupted(5L, now), out)
    }

    @Test fun theLengthIsNeverNegative() {
        val out = InterruptedCapture.outcome(fileBytes = 5L, lastWriteAt = start - 60_000L, startedAt = start, now = now)
        assertEquals(InterruptedCapture.Outcome.Interrupted(5L, start), out)
    }

    @Test fun anEmptyFileAndAMissingFileAreFailedWithTheirReasons() {
        assertEquals(
            InterruptedCapture.Outcome.Failed("No data was written"),
            InterruptedCapture.outcome(fileBytes = 0L, lastWriteAt = start + 5_000L, startedAt = start, now = now),
        )
        assertEquals(
            InterruptedCapture.Outcome.Failed("The recording file was not found"),
            InterruptedCapture.outcome(fileBytes = null, lastWriteAt = null, startedAt = start, now = now),
        )
    }

    @Test fun noOutcomeLeavesTheRowRecording() {
        for (bytes in listOf<Long?>(null, 0L, 1L, 10_000_000L)) {
            val out = InterruptedCapture.outcome(bytes, start + 1_000L, start, now)
            assertTrue(out is InterruptedCapture.Outcome.Interrupted || out is InterruptedCapture.Outcome.Failed)
            if (out is InterruptedCapture.Outcome.Interrupted) assertTrue(out.bytes > 0L && out.endedAt >= start)
        }
    }

    @Test fun aFailedRowKeepsAppleWordingAndItsColumnMigrates() {
        assertEquals("failed", InterruptedCapture.FAILED)
        assertEquals("Could not determine recordings directory", InterruptedCapture.NO_RECORDINGS_FOLDER)
        assertEquals(
            listOf("ALTER TABLE `local_recording` ADD COLUMN `failureReason` TEXT"),
            com.aeriotv.android.core.di.DatabaseModule.MIGRATION_40_41_SQL,
        )
    }
}
