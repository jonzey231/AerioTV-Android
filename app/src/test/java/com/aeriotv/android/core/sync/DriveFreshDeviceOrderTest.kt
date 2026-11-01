package com.aeriotv.android.core.sync

import com.aeriotv.android.core.sync.dsync.DsyncCoexistence
import com.aeriotv.android.core.sync.dsync.DsyncKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field run 2026-11-01 (docs/DispatcharrSyncSchema.md 10a): after a
 * Dispatcharr choice, the Drive follow labeled a recents push "Favorites",
 * and a fresh device pushed its empty progress over a populated backup.
 */
class DriveFreshDeviceOrderTest {

    @Test fun everyKeyWithACategoryMapsToItsDriveFile() {
        DsyncKey.entries.filter { it.category != null }.forEach { k ->
            assertNotNull(k.suffix, DsyncCoexistence.driveFileFor(k))
        }
        DsyncCoexistence.FORCE_KEYS.forEach { k ->
            assertEquals(k.suffix, SetupSourceChoice.driveFile(k.suffix), DsyncCoexistence.driveFileFor(k))
        }
        assertEquals(SyncCategory.Preferences, DsyncCoexistence.driveFileFor(DsyncKey.Recents))
        assertEquals(SyncCategory.Preferences, DsyncCoexistence.driveFileFor(DsyncKey.Collections))
        assertEquals(SyncCategory.Favorites, DsyncCoexistence.driveFileFor(DsyncKey.Favorites))
        assertEquals(SyncCategory.Watchlist, DsyncCoexistence.driveFileFor(DsyncKey.HiddenTitles))
        assertNull(DsyncCoexistence.driveFileFor(DsyncKey.Profiles))
    }

    @Test fun everySharedNameHasExactlyOneDriveFile() {
        val files = SetupSourceChoice.SHARED.associateWith { SetupSourceChoice.driveFile(it) }
        files.forEach { (n, f) -> assertNotNull(n, f) }
        assertEquals(SetupSourceChoice.SHARED.toSet(), DsyncCoexistence.SHARED_CATEGORIES.flatMap { SetupSourceChoice.namesInFile(it) }.toSet())
        assertEquals(listOf("collections", "recents", "hiddenGroups", "groupOrder", "settings"), SetupSourceChoice.namesInFile(SyncCategory.Preferences))
    }

    @Test fun freshDeviceWaitsForThePullBeforeItsFirstPush() {
        val names = SetupSourceChoice.namesInFile(SyncCategory.WatchProgress)
        // No complete Dispatcharr pull yet: wait.
        assertNotNull(SetupSourceChoice.freshDeviceHold(names, null, mapOf("progress" to 1), mapOf("progress" to 0)))
        // Pulled but not applied (catalog not ready): wait.
        assertEquals("progress not pulled yet", SetupSourceChoice.freshDeviceHold(names, setOf("favorites"), mapOf("progress" to 1), mapOf("progress" to 0)))
        // Applied but this device still has none of the server rows: an empty
        // fresh device never overwrites the populated backup.
        assertEquals("progress empty here, 1 on Dispatcharr", SetupSourceChoice.freshDeviceHold(names, setOf("progress"), mapOf("progress" to 1), mapOf("progress" to 0)))
        // Applied and seeded: push.
        assertNull(SetupSourceChoice.freshDeviceHold(names, setOf("progress"), mapOf("progress" to 1), mapOf("progress" to 1)))
        // Known empty on the server: an empty push is the true copy.
        assertNull(SetupSourceChoice.freshDeviceHold(names, setOf("progress"), mapOf("progress" to 0), mapOf("progress" to 0)))
        assertNull(SetupSourceChoice.freshDeviceHold(names, setOf("progress"), emptyMap(), mapOf("progress" to 0)))
        // Item off on this device (in neither map): nothing to wait for.
        assertNull(SetupSourceChoice.freshDeviceHold(names, null, emptyMap(), emptyMap()))
    }

    @Test fun preferencesFileWaitsForEveryCategoryItCarries() {
        val names = SetupSourceChoice.namesInFile(SyncCategory.Preferences)
        val server = mapOf("recents" to 9, "collections" to 0, "hiddenGroups" to 0, "groupOrder" to 4)
        val local = mapOf("recents" to 9, "collections" to 0, "hiddenGroups" to 0, "groupOrder" to 0)
        assertEquals("groupOrder empty here, 4 on Dispatcharr", SetupSourceChoice.freshDeviceHold(names, server.keys, server, local))
        assertNull(SetupSourceChoice.freshDeviceHold(names, server.keys, server, local + ("groupOrder" to 4)))
    }

    @Test fun pushLineNamesTheFileAndTheReason() {
        assertEquals("[DRIVE] push WatchProgress rows=1 (converge)",
            SetupSourceChoice.drivePushLine("WatchProgress", 1, mapOf("progress" to 1), "converge", emptyList(), true))
        val line = SetupSourceChoice.drivePushLine("Preferences", 20, mapOf("recents" to 9, "groupOrder" to 4), null, listOf("recents local", "recents pull"), true)
        assertEquals("[DRIVE] push Preferences rows=20 recents=9 groupOrder=4 (follow recents local, recents pull)", line)
        assertTrue(SetupSourceChoice.drivePushLine("Favorites", 0, emptyMap(), "replace", emptyList(), false).endsWith("(replace) failed"))
    }
}
