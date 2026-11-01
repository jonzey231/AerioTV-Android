package com.aeriotv.android.core.sync

import com.aeriotv.android.core.sync.dsync.DsyncCoexistence
import com.aeriotv.android.core.sync.dsync.DsyncKey
import com.aeriotv.android.core.sync.dsync.DsyncPayloads
import com.aeriotv.android.core.sync.dsync.DsyncReadiness
import com.aeriotv.android.core.sync.dsync.ProgressRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section 10a, field run 2026-11-01: Drive progress=8 vs Dispatcharr progress=1, 7 rows with no canonical id. */
class CanonicalIdSymmetryTest {
    private val env = SyncEnvelope(lastModified = 1_000, device = "t")
    private val now = 2_000_000_000_000L

    private fun prog(id: String, type: String = "movie", server: Int? = null) =
        WatchProgressSnapshotEntry(id, "T $id", null, 10, 100, now - 1_000, type, if (type == "episode") "5" else null, dispatcharrVodId = server)

    @Test fun canonicalSnapshotForcePushesProgressWithoutTheCatalog() {
        val s = WatchProgressSnapshot(env, listOf(prog("m1", server = 11), prog("e1", "episode", server = 22), prog("e2", "episode", server = 23)))
        val (movies, episodes) = DriveCanon.progressCanonical(s)
        assertEquals(mapOf("m1" to 11), movies)
        assertEquals(mapOf("e1" to 22, "e2" to 23), episodes)
        // Recorded into the id map, every row translates: the push goes out with the catalog not built.
        val unresolved = s.entries.count { (if (it.vodType == "movie") movies[it.videoId] else episodes[it.videoId]) == null }
        assertEquals(0, unresolved)
        assertEquals(DsyncReadiness.Decision.Push, DsyncReadiness.decide(DsyncKey.Progress, cacheReady = false, unresolvedRows = unresolved))
    }

    private fun eightVsOne(): Pair<SetupSourceChoice.Summary, SetupSourceChoice.Summary> {
        val rows = listOf(prog("m1", server = 11)) + (2..8).map { prog("m$it") }
        val drive = SetupSourceChoice.Summary(mapOf("progress" to DriveCanon.progress(WatchProgressSnapshot(env, rows), DriveCanon.Ids(), now)))
        val wire = DsyncPayloads.encodeProgress(null, listOf(ProgressRow(11, "m", 10, 100, 1, false, null, 0, 0, "T m1")), now / 1000)
        val dsync = SetupSourceChoice.Summary(
            mapOf("progress" to SetupSourceChoice.Category(DsyncCoexistence.rowCount(DsyncKey.Progress, wire), 1_000, com.aeriotv.android.core.sync.dsync.DsyncCoexistence.fingerprint(DsyncKey.Progress, wire))),
        )
        return drive to dsync
    }

    @Test fun untranslatableRowsAreExcludedFromBothSummaries() {
        val (drive, dsync) = eightVsOne()
        assertEquals(1, drive.rows("progress"))
        assertEquals(1, dsync.rows("progress"))
        assertEquals(drive.categories["progress"]!!.fingerprint, dsync.categories["progress"]!!.fingerprint)
        assertEquals(SetupSourceChoice.Decision.UseDispatcharr, SetupSourceChoice.decide(drive, dsync))
    }

    @Test fun convergeAgreesWithTheProbe() {
        val (drive, dsync) = eightVsOne()
        assertTrue(SetupSourceChoice.convergePlan(drive, dsync, emptySet(), emptyMap()).isEmpty())
        assertTrue(SetupSourceChoice.convergeSkips(drive, dsync, emptyMap(), emptyMap()).isEmpty())
        // A real difference: the probe asks, and converge either pushes or names a reason.
        val other = SetupSourceChoice.Summary(mapOf("progress" to SetupSourceChoice.Category(2, 1_000, "x")))
        assertTrue(SetupSourceChoice.decide(other, dsync) is SetupSourceChoice.Decision.Ask)
        assertEquals(listOf("progress"), SetupSourceChoice.convergePlan(other, dsync, emptySet(), emptyMap()))
        assertEquals(listOf("progress" to "deferred"), SetupSourceChoice.convergeSkips(other, dsync, mapOf("progress" to "deferred"), emptyMap()))
        val mark = mapOf("progress" to SetupSourceChoice.convergeMark(dsync.categories["progress"]))
        assertTrue(SetupSourceChoice.convergePlan(other, dsync, emptySet(), mark).isEmpty())
        assertEquals(listOf("progress" to "untranslatable"), SetupSourceChoice.convergeSkips(other, dsync, emptyMap(), mark))
    }
}
