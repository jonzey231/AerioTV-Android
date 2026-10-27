package com.aeriotv.android.core.sync.dsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTrimTest {
    @Test fun skipsAnUnchangedBucketPulledMomentsAgo() {
        assertTrue(shouldSkipBucketGet(lastPullAt = 1_000, now = 30_000, fetchedBytes = 512, listedBytes = 512))
    }

    @Test fun fetchesWhenTheSizeMoved() {
        assertFalse(shouldSkipBucketGet(1_000, 30_000, 512, 600))
    }

    @Test fun fetchesAfterTheWindow() {
        assertFalse(shouldSkipBucketGet(1_000, 1_000 + BUCKET_REFETCH_MIN_MS, 512, 512))
    }

    @Test fun fetchesWithoutAPriorPull() {
        assertFalse(shouldSkipBucketGet(0, 30_000, 512, 512))
        assertFalse(shouldSkipBucketGet(1_000, 30_000, null, 512))
    }

    @Test fun missMemoIsScopedToTheCatalogStamp() {
        val memo = MissMemo()
        memo.addMisses("k|1|100", listOf("i:7"))
        assertTrue(memo.isMiss("k|1|100", "i:7"))
        assertFalse(memo.isMiss("k|1|100", "i:8"))
        // A sweep page landed: new stamp, the miss is scanned again.
        assertFalse(memo.isMiss("k|1|180", "i:7"))
        memo.addMisses("k|1|180", listOf("i:9"))
        assertFalse(memo.isMiss("k|1|180", "i:7"))
    }
}
