package com.aeriotv.android.core.sync.dsync

import org.junit.Assert.assertEquals
import org.junit.Test

class DsyncMissingRecordTest {
    @Test fun firstReadDoneAndMissingPrompts() =
        assertEquals(DsyncMissingRecord.Action.Prompt, DsyncMissingRecord.decide(firstReadDone = true, recordMissing = true))

    @Test fun firstReadNotDoneSeedsAsToday() =
        assertEquals(DsyncMissingRecord.Action.Seed, DsyncMissingRecord.decide(firstReadDone = false, recordMissing = true))

    @Test fun recordPresentIsNormal() {
        assertEquals(DsyncMissingRecord.Action.Normal, DsyncMissingRecord.decide(firstReadDone = true, recordMissing = false))
        assertEquals(DsyncMissingRecord.Action.Normal, DsyncMissingRecord.decide(firstReadDone = false, recordMissing = false))
    }
}
