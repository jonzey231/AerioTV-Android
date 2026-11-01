package com.aeriotv.android.core.sync.dsync

import com.aeriotv.android.core.sync.DriveCanon
import com.aeriotv.android.core.sync.SetupSourceChoice
import com.aeriotv.android.core.sync.SyncEnvelope
import com.aeriotv.android.core.sync.WatchProgressSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 10a, field run 2026-11-01 (phone): Dispatcharr progress=1 against
 * Drive progress=0 on every probe, because the pull could not map the one
 * server row to a local uuid and dropped it, so the Drive push carried 0.
 */
class HeldProgressTest {
    private val nowS = 2_000_000_000L
    private val env = SyncEnvelope(lastModified = 1_000, device = "t")
    private val row = ProgressRow(11, "m", 10, 100, nowS - 60, false, null, 0, 0, "T m11")
    private val wire = DsyncPayloads.encodeProgress(null, listOf(row), nowS)

    private fun dsync(keep: (String, Int) -> Boolean) =
        SetupSourceChoice.Summary(mapOf("progress" to DsyncHeld.vodCategory(DsyncKey.Progress, wire, 1_000, keep).first))

    private fun drive(s: WatchProgressSnapshot) =
        SetupSourceChoice.Summary(mapOf("progress" to DriveCanon.progress(s, DriveCanon.Ids(), nowS * 1000)))

    @Test fun heldServerRowGivesEqualSummariesAndDispatcharrDecision() {
        // The catalog cannot resolve id 11; the pull holds it by canonical id.
        val held = listOf(row)
        val keep = { t: String, id: Int -> DsyncHeld.representable(t, id, emptySet(), emptySet(), held.map(DsyncHeld::item).toSet(), emptySet()) }
        // After the Dispatcharr choice the Drive WatchProgress push carries the held row.
        val pushed = DsyncHeld.withHeld(WatchProgressSnapshot(env, emptyList()), held)
        assertEquals(1, pushed.entries.size)
        assertEquals(11, pushed.entries.single().dispatcharrVodId)
        assertTrue(DsyncHeld.isPlaceholder(pushed.entries.single().videoId))
        val d = drive(pushed)
        val x = dsync(keep)
        assertEquals(1, d.rows("progress"))
        assertEquals(1, x.rows("progress"))
        assertEquals(d.categories["progress"]!!.fingerprint, x.categories["progress"]!!.fingerprint)
        assertEquals(SetupSourceChoice.Decision.UseDispatcharr, SetupSourceChoice.decide(d, x))
        assertTrue(SetupSourceChoice.convergePlan(d, x, emptySet(), emptyMap()).isEmpty())
        // The placeholder round-trips back to the held row on another device.
        assertEquals(row.copy(tail = Tail()), DsyncHeld.fromEntry(pushed.entries.single())!!.copy(tail = Tail()))
    }

    @Test fun unrepresentableRowIsExcludedSoNothingDiffers() {
        val keep = { t: String, id: Int -> DsyncHeld.representable(t, id, emptySet(), emptySet(), emptySet(), emptySet()) }
        val (c, gone) = DsyncHeld.vodCategory(DsyncKey.Progress, wire, 1_000, keep)
        assertEquals(0, c.rows)
        assertEquals(1, gone)
        val d = drive(WatchProgressSnapshot(env, emptyList()))
        val x = SetupSourceChoice.Summary(mapOf("progress" to c))
        assertTrue(SetupSourceChoice.decide(d, x) !is SetupSourceChoice.Decision.Ask)
        assertTrue(SetupSourceChoice.convergeSkips(d, x, emptyMap(), emptyMap()).isEmpty())
        assertEquals("[SYNC] probe excludes progress rows=1 (no local match)", DsyncHeld.excludeLine("progress", gone))
    }

    @Test fun driveCarriedOrResolvedRowsStayInTheDispatcharrSummary() {
        assertTrue(DsyncHeld.representable("m", 11, setOf(11), emptySet(), emptySet(), emptySet()))
        assertTrue(DsyncHeld.representable("m", 11, emptySet(), emptySet(), emptySet(), setOf("m|11")))
        assertTrue(DsyncHeld.representable("s", 5, emptySet(), emptySet(), emptySet(), emptySet()))
        assertTrue(!DsyncHeld.representable("e", 7, emptySet(), emptySet(), emptySet(), emptySet()))
        val marks = DsyncPayloads.encodeVodMarks(null, listOf(VodMarkRow(3, "s", 1), VodMarkRow(9, "m", 1)), 100)
        val (c, gone) = DsyncHeld.vodCategory(DsyncKey.Watchlist, marks, 1_000) { t, id -> DsyncHeld.representable(t, id, emptySet(), emptySet(), emptySet(), emptySet()) }
        assertEquals(1, c.rows)
        assertEquals(1, gone)
    }

    @Test fun resolvedLocalRowWinsOverThePlaceholder() {
        val real = com.aeriotv.android.core.sync.WatchProgressSnapshotEntry("uuid-1", "T", null, 1, 2, 3, "movie", dispatcharrVodId = 11)
        val s = DsyncHeld.withHeld(WatchProgressSnapshot(env, listOf(real)), listOf(row))
        assertEquals(listOf("uuid-1"), s.entries.map { it.videoId })
        assertEquals(Pair(emptyMap<String, Int>(), emptyMap<String, Int>()), DriveCanon.progressCanonical(DsyncHeld.withHeld(WatchProgressSnapshot(env, emptyList()), listOf(row))))
    }
}
