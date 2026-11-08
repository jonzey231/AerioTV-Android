package com.aeriotv.android.core.timeshift

import com.aeriotv.android.core.timeshift.RewindStoragePolicy.GB
import com.aeriotv.android.core.timeshift.RewindStoragePolicy.Segment
import com.aeriotv.android.core.timeshift.RewindStoragePolicy.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewindStoragePolicyTest {
    private val hugeFree = 2_000L * GB

    @Test fun capUsesDepthAllowanceWhenSmall() {
        // 15 min x 5 x 1.5 MB/s = 6.75e9 bytes
        assertEquals(6_750_000_000L, RewindStoragePolicy.capBytes(15 * 60_000L, false, 0, hugeFree))
    }

    @Test fun capClampsToCeilingOnLargeDisk() {
        // The 43 GB case: 60 min depth on a big disk must not be unbounded.
        assertEquals(16L * GB, RewindStoragePolicy.capBytes(60 * 60_000L, false, 0, hugeFree))
        assertEquals(8L * GB, RewindStoragePolicy.capBytes(60 * 60_000L, true, 0, hugeFree))
    }

    @Test fun capKeepsFreeSpaceFloor() {
        assertEquals(1L * GB, RewindStoragePolicy.capBytes(180 * 60_000L, false, 0, 3L * GB))
        assertEquals(0L, RewindStoragePolicy.capBytes(180 * 60_000L, false, 0, 1L * GB))
        assertEquals(4L * GB, RewindStoragePolicy.capBytes(180 * 60_000L, false, 3L * GB, 3L * GB))
    }

    private fun sess(name: String, start: Long, active: Boolean, owned: Boolean, n: Int) =
        Session(name, start, active, owned, (0 until n).map { Segment("seg_${start + it}.ts", start + it, 10) })

    @Test fun noTrimUnderCap() {
        assertTrue(RewindStoragePolicy.selectTrim(listOf(sess("a", 1, true, true, 3)), 30).isEmpty())
    }

    @Test fun trimsOldestNonActiveFirstThenActive() {
        val active = sess("sess_100", 100, true, true, 3)
        val oldRetained = sess("sess_10", 10, false, true, 3)
        val orphan = sess("sess_50", 50, false, false, 2)
        val trims = RewindStoragePolicy.selectTrim(listOf(active, orphan, oldRetained), 20)
        // total 80, cap 20: retained loses 2 (keeps its open segment), orphan loses 2, active loses 2.
        assertEquals(
            listOf("sess_10", "sess_10", "sess_50", "sess_50", "sess_100", "sess_100"),
            trims.map { it.session },
        )
        assertEquals("seg_10.ts", trims.first().segment)
        assertEquals("seg_101.ts", trims.last().segment)
    }

    @Test fun ownedSessionsKeepNewestSegment() {
        val trims = RewindStoragePolicy.selectTrim(listOf(sess("a", 1, true, true, 4)), 0)
        assertEquals(3, trims.size)
        assertFalse(trims.any { it.segment == "seg_4.ts" })
    }

    @Test fun orphansAreEveryUnownedDirRegardlessOfAge() {
        assertEquals(
            listOf("avp_x", "sess_1", "sess_3"),
            RewindStoragePolicy.selectOrphans(listOf("sess_3", "sess_2", "sess_1", "avp_x"), setOf("sess_2")),
        )
    }

    @Test fun orphanTempOnlyBeforeProcessStart() {
        assertTrue(RewindStoragePolicy.isOrphanTemp("aerio_dl123.xml", 999, 1000))
        assertFalse(RewindStoragePolicy.isOrphanTemp("aerio_dl123.xml", 1000, 1000))
        assertFalse(RewindStoragePolicy.isOrphanTemp("image_cache", 1, 1000))
    }
}
