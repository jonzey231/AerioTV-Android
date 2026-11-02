package com.aeriotv.android.core.sync.dsync

import com.aeriotv.android.core.sync.SyncScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings > Sync manual push and pull (section 10a, 2026-11-02). */
class DsyncManualTest {

    @Test
    fun allOnCoversEveryForcedKey() {
        assertEquals(DsyncCoexistence.FORCE_KEYS, DsyncManual.keys(emptyMap()))
    }

    @Test
    fun offItemsNeitherPushNorPull() {
        val keys = DsyncManual.keys(mapOf("favorites" to SyncScope.Off, "watchProgress" to SyncScope.Off, "hiddenTitles" to SyncScope.Off))
        assertFalse(DsyncKey.Favorites in keys)
        assertFalse(DsyncKey.Progress in keys)
        assertFalse(DsyncKey.HiddenTitles in keys)
        assertTrue(DsyncKey.Recents in keys)
        assertTrue(DsyncKey.Settings in keys)
    }

    @Test
    fun neverForcedKeysExcluded() {
        val keys = DsyncManual.keys(emptyMap())
        assertFalse(DsyncKey.Profiles in keys)
        assertFalse(DsyncKey.RemoteMap in keys)
        assertFalse(DsyncKey.RadioDefault in keys)
    }

    @Test
    fun rowsFollowProviderStates() {
        assertEquals(DsyncManual.Rows(true, true), DsyncManual.rows(true, true, true, true))
        assertEquals(DsyncManual.Rows(true, false), DsyncManual.rows(true, true, false, false))
        assertEquals(DsyncManual.Rows(true, false), DsyncManual.rows(true, true, true, false))
        assertEquals(DsyncManual.Rows(false, false), DsyncManual.rows(false, true, true, true))
        assertEquals(DsyncManual.Rows(false, false), DsyncManual.rows(true, false, true, true))
    }
}
