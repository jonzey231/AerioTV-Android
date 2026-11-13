package com.aeriotv.android.feature.dvr

import android.content.Context
import android.util.Log
import com.aeriotv.android.core.data.LocalRecordingFiles
import com.aeriotv.android.core.data.db.dao.LocalRecordingDao

/**
 * An on-device recording still marked "recording" with no capture running
 * for it (Apple 2d82156 InterruptedCapture). The row is written when the
 * capture starts, so a process death mid-capture (a force stop, a crash, the
 * system reclaiming the app, an update) leaves it marked recording. It
 * becomes finished: interrupted, keeping what its file captured, with the
 * file's last write as its end; failed, with Apple's reason, when it
 * captured nothing (Logan 2026-11-13: a removed row "just" looks missing).
 */
internal object InterruptedCapture {
    const val RECORDING = "recording"

    /**
     * How many on-device recordings the playlist delete prompt names: every
     * row from the playlist ([statuses]), and the capture running now
     * ([capturingNow]) only when it has no row yet. Its row is written when
     * it starts (status [RECORDING]), so adding it again counted it twice
     * (2 for one recording on the emulator, 2026-11-13).
     */
    fun promptCount(statuses: List<String>, capturingNow: Boolean): Int =
        statuses.size + if (capturingNow && statuses.none { it == RECORDING }) 1 else 0
    const val INTERRUPTED = "interrupted"
    const val FAILED = "failed"

    // Apple's failureReason wording (RecordingCoordinator, InterruptedCapture).
    const val NOTHING_WRITTEN = "No data was written"
    const val FILE_NOT_FOUND = "The recording file was not found"
    const val NO_RECORDINGS_FOLDER = "Could not determine recordings directory"

    sealed interface Outcome {
        /** Keep the row, finished: [bytes] captured, ending at [endedAt]. */
        data class Interrupted(val bytes: Long, val endedAt: Long) : Outcome
        /** Keep the row as failed, with [reason]: nothing was captured. */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * [fileBytes] null: no file at the row's path. [lastWriteAt] is the
     * file's last modification, null when unknown ([now] stands in, as on
     * Apple). The end is never before [startedAt].
     */
    fun outcome(fileBytes: Long?, lastWriteAt: Long?, startedAt: Long, now: Long): Outcome = when {
        fileBytes == null -> Outcome.Failed(FILE_NOT_FOUND)
        fileBytes <= 0L -> Outcome.Failed(NOTHING_WRITTEN)
        else -> Outcome.Interrupted(fileBytes, maxOf(startedAt, lastWriteAt ?: now))
    }
}

/** Finishes on-device recording rows that no capture is behind ([InterruptedCapture]). */
internal object LocalRecordingRecovery {
    private const val TAG = "LocalRecordingService"

    /**
     * Every row still marked recording that started before [startedBefore]
     * (launch: rows from an earlier process; this process's own capture, if
     * one starts meanwhile, is never touched). Returns how many were finished.
     */
    suspend fun sweep(context: Context, dao: LocalRecordingDao, reason: String, startedBefore: Long): Int {
        val rows = runCatching { dao.recordingRows() }.getOrDefault(emptyList())
            .filter { it.startedAt < startedBefore }
        rows.forEach { finish(context, dao, it.id, reason) }
        return rows.size
    }

    /** Finish one row marked recording, unless the running capture is its own. */
    suspend fun finish(context: Context, dao: LocalRecordingDao, rowId: Long, reason: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { finishOnIo(context, dao, rowId, reason) }

    private suspend fun finishOnIo(context: Context, dao: LocalRecordingDao, rowId: Long, reason: String): Boolean {
        if (LocalRecordingService.isCapturingRow(rowId)) return false
        val row = runCatching { dao.byId(rowId) }.getOrNull() ?: return false
        if (row.status != InterruptedCapture.RECORDING) return false
        // Measured through the file itself (a MediaStore item left pending
        // measures the same), then published so a kept recording is visible
        // in Downloads like any finished one.
        val probe = LocalRecordingFiles.probe(context, row.filePath)
        when (val out = InterruptedCapture.outcome(probe.bytes, probe.lastWriteAt, row.startedAt, System.currentTimeMillis())) {
            is InterruptedCapture.Outcome.Interrupted -> {
                LocalRecordingFiles.publish(context, row.filePath)
                dao.update(row.copy(status = InterruptedCapture.INTERRUPTED, byteSize = out.bytes, endedAt = out.endedAt))
                Log.i(TAG, "[DVR] on-device recording ${row.id} had no capture running ($reason): now interrupted, kept ${out.bytes} bytes, ${(out.endedAt - row.startedAt) / 1000} s captured")
            }
            is InterruptedCapture.Outcome.Failed -> {
                // The row stays, failed, so the recording is not simply gone
                // from the list; an empty file (a MediaStore item left
                // pending) holds nothing to keep.
                if (probe.bytes != null) LocalRecordingFiles.delete(context, row.filePath)
                dao.update(row.copy(status = InterruptedCapture.FAILED, byteSize = 0L, failureReason = out.reason))
                Log.i(TAG, "[DVR] on-device recording ${row.id} had no capture running ($reason): now failed, ${out.reason}")
            }
        }
        return true
    }
}
