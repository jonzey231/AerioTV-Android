package com.aeriotv.android.core.preferences

import com.aeriotv.android.core.sync.SyncCategory
import com.aeriotv.android.core.sync.dsync.DsyncCoexistence
import com.aeriotv.android.feature.settings.syncProviderLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Old single "Sync with" choice -> independent Drive and Dispatcharr switches. */
class SyncSwitchMigrationTest {
    @Test fun legacyDispatcharrMigratesToOn() {
        assertTrue(SyncSwitchMigration.dispatcharrOn(null, "dispatcharr"))
    }

    @Test fun legacyDriveOffOrUnsetMigratesToOff() {
        assertFalse(SyncSwitchMigration.dispatcharrOn(null, "drive"))
        assertFalse(SyncSwitchMigration.dispatcharrOn(null, "off"))
        assertFalse(SyncSwitchMigration.dispatcharrOn(null, null))
    }

    @Test fun storedBooleanWinsOverLegacy() {
        assertFalse(SyncSwitchMigration.dispatcharrOn(false, "dispatcharr"))
        assertTrue(SyncSwitchMigration.dispatcharrOn(true, "drive"))
    }

    @Test fun ownsCategoryFollowsTheDispatcharrSwitchWithDriveOnToo() {
        // Drive on or off does not enter the rule; only the Dispatcharr switch does.
        val dsyncOn = SyncSwitchMigration.dispatcharrOn(null, "dispatcharr")
        assertTrue(DsyncCoexistence.owns(SyncCategory.Favorites, true, dsyncOn, true))
        val dsyncOff = SyncSwitchMigration.dispatcharrOn(null, "drive")
        assertFalse(DsyncCoexistence.owns(SyncCategory.Favorites, true, dsyncOff, true))
    }

    @Test fun rootLabelCoversBoth() {
        assertEquals("Google Drive and Dispatcharr", syncProviderLabel(true, true))
        assertEquals("Google Drive", syncProviderLabel(true, false))
        assertEquals("Dispatcharr", syncProviderLabel(false, true))
        assertEquals("Off", syncProviderLabel(false, false))
    }
}
