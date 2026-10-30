package com.aeriotv.android.feature.onboarding

import com.aeriotv.android.core.sync.SyncProviderId
import com.aeriotv.android.feature.onboarding.WelcomeSyncChain.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomeSyncChainTest {
    private fun plan(drive: Boolean, d: Boolean, restoredD: Boolean = false, available: Boolean = true) =
        WelcomeSyncChain.plan(drive, d, restoredD, available)

    @Test fun nothingSelectedRunsNothing() = assertEquals(emptyList<Step>(), plan(false, false))

    @Test fun driveOnlyNoDispatcharrRestore() =
        assertEquals(listOf(Step.DriveRestore, Step.Main), plan(true, false))

    @Test fun driveOnlyRestoreBringsDispatcharr() =
        assertEquals(listOf(Step.DriveRestore, Step.DispatcharrSync, Step.AddProfiles, Step.Main), plan(true, false, restoredD = true))

    @Test fun dispatcharrOnlyOffersDrive() = assertEquals(
        listOf(Step.ConnectServer, Step.DispatcharrSync, Step.DriveOffer, Step.AddProfiles, Step.Main),
        plan(false, true),
    )

    @Test fun dispatcharrOnlyNoClientIdSkipsDriveOffer() = assertEquals(
        listOf(Step.ConnectServer, Step.DispatcharrSync, Step.AddProfiles, Step.Main),
        plan(false, true, available = false),
    )

    @Test fun bothWithoutDispatcharrRestoreConnects() = assertEquals(
        listOf(Step.DriveRestore, Step.ConnectServer, Step.DispatcharrSync, Step.AddProfiles, Step.Main),
        plan(true, true),
    )

    @Test fun bothWithDispatcharrRestoreSkipsConnect() = assertEquals(
        listOf(Step.DriveRestore, Step.DispatcharrSync, Step.AddProfiles, Step.Main),
        plan(true, true, restoredD = true),
    )

    @Test fun connectAfterRestore() {
        assertTrue(WelcomeSyncChain.connectAfterRestore(dispatcharr = true, restoredIsDispatcharr = false))
        assertFalse(WelcomeSyncChain.connectAfterRestore(dispatcharr = true, restoredIsDispatcharr = true))
        assertFalse(WelcomeSyncChain.connectAfterRestore(dispatcharr = false, restoredIsDispatcharr = false))
    }

    @Test fun driveOfferCondition() {
        assertTrue(WelcomeSyncChain.showDriveOffer(driveOn = false, driveAvailable = true))
        assertFalse(WelcomeSyncChain.showDriveOffer(driveOn = true, driveAvailable = true))
        assertFalse(WelcomeSyncChain.showDriveOffer(driveOn = false, driveAvailable = false))
    }

    @Test fun answeredProvidersLeaveTheOffer() {
        WelcomeSyncChain.resetForTest()
        WelcomeSyncChain.markAnswered(SyncProviderId.GoogleDrive)
        assertEquals(listOf(SyncProviderId.Dispatcharr),
            WelcomeSyncChain.filterOffers(listOf(SyncProviderId.Dispatcharr, SyncProviderId.GoogleDrive)))
        WelcomeSyncChain.resetForTest()
    }

    @Test fun oldBackupWithDispatcharrRestoreOffersUpdateFirst() = assertEquals(
        listOf(Step.DriveRestore, Step.BackupUpdate, Step.DispatcharrSync, Step.AddProfiles, Step.Main),
        WelcomeSyncChain.plan(drive = true, dispatcharr = false, restoredIsDispatcharr = true, driveAvailable = true, backupNeedsUpdate = true),
    )

    @Test fun bothSelectedOldBackupOffersUpdateBeforeSync() = assertEquals(
        listOf(Step.DriveRestore, Step.BackupUpdate, Step.DispatcharrSync, Step.AddProfiles, Step.Main),
        WelcomeSyncChain.plan(drive = true, dispatcharr = true, restoredIsDispatcharr = true, driveAvailable = true, backupNeedsUpdate = true),
    )

    @Test fun updateOfferedForEveryPlaylistTypeAfterADriveRestore() {
        assertTrue(WelcomeSyncChain.showBackupUpdate(drive = true, backupNeedsUpdate = true))
        assertFalse(WelcomeSyncChain.showBackupUpdate(drive = false, backupNeedsUpdate = true))
        assertFalse(WelcomeSyncChain.showBackupUpdate(drive = true, backupNeedsUpdate = false))
        assertEquals(
            listOf(Step.DriveRestore, Step.BackupUpdate, Step.Main),
            WelcomeSyncChain.plan(true, false, false, true, backupNeedsUpdate = true),
        )
    }
}
