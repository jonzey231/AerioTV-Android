package com.aeriotv.android.core.sync.dsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-10-18 two-device findings: switch ordering, offline dirty retry, per-row profiles merge. */
class DsyncGateTest {

    // 1) Ordering: a write queued by the Admin session never goes out under Test1,
    //    and Test1's session writes nothing before its first pull.
    @Test fun queuedWriteBoundToItsSession() {
        val admin = ""
        val test1 = "prof-test1"
        // Admin's debounced favorites write, flushed while Admin is still active: allowed.
        assertTrue(DsyncGate.mayWrite(admin, currentToken = admin, firstPullDone = true, keyRead = true))
        // The same write firing after the token moved to Test1: dropped.
        assertFalse(DsyncGate.mayWrite(admin, currentToken = test1, firstPullDone = true, keyRead = true))
        // Test1's new session before its first pull completed: nothing goes out.
        assertFalse(DsyncGate.mayWrite(test1, currentToken = test1, firstPullDone = false, keyRead = true))
        // Key not read in this session: nothing goes out.
        assertFalse(DsyncGate.mayWrite(test1, currentToken = test1, firstPullDone = true, keyRead = false))
        assertTrue(DsyncGate.mayWrite(test1, currentToken = test1, firstPullDone = true, keyRead = true))
    }

    // 2) Offline change: a newer server copy never overwrites a dirty key blindly.
    @Test fun dirtyKeyMergesInsteadOfBeingOverwritten() {
        assertEquals(DsyncGate.DirtyPull.TakeRemote, DsyncGate.onNewerPull(DsyncKey.Favorites, dirty = false, dirtyAt = 0, remoteAt = 200))
        DsyncGate.ROW_MERGE_KEYS.forEach {
            assertEquals(DsyncGate.DirtyPull.Merge, DsyncGate.onNewerPull(it, dirty = true, dirtyAt = 100, remoteAt = 200))
        }
        // Whole-value keys: the newer side wins.
        assertEquals(DsyncGate.DirtyPull.KeepLocal, DsyncGate.onNewerPull(DsyncKey.Settings, dirty = true, dirtyAt = 300, remoteAt = 200))
        assertEquals(DsyncGate.DirtyPull.TakeRemote, DsyncGate.onNewerPull(DsyncKey.Settings, dirty = true, dirtyAt = 100, remoteAt = 200))
        // Phone favorited 1042 offline; Streamer wrote [77, 903] meanwhile. Union keeps both.
        assertEquals(listOf(77, 1042, 903), DsyncGate.favoritesUnion(listOf(77, 1042), listOf(77, 903)))
        val local = listOf(CollectionRow("a", "Sports", "b", listOf(1, 2)))
        val remote = listOf(CollectionRow("a", "Sports", "b", listOf(2, 3)), CollectionRow("b", "News", "e", listOf(9)))
        val merged = DsyncGate.collectionsUnion(local, remote)
        assertEquals(listOf("a", "b"), merged.map { it.id })
        assertEquals(listOf(1, 2, 3), merged[0].members)
    }

    // 3) Profiles merge per row: an older server row never wipes a newer local PIN.
    @Test fun profilesMergePerRow() {
        val older = ProfileRow(7, "test1", "Test1", "none", "", "", "user", updatedAt = 1_000)
        val newer = older.copy(pinType = "numbers", pinHash = "a".repeat(64), pinSalt = "b".repeat(32), updatedAt = 3_000)
        // Phone set the PIN at 2000; Streamer's row is from 1000: keep local.
        assertFalse(DsyncGate.profileRowWins(localUpdatedAt = 2_000, remote = older))
        // A row from an older writer without updatedAt (0) never replaces a stamped local row.
        assertFalse(DsyncGate.profileRowWins(localUpdatedAt = 2_000, remote = older.copy(updatedAt = 0)))
        assertTrue(DsyncGate.profileRowWins(localUpdatedAt = 2_000, remote = newer))
        // updatedAt round-trips at position 7.
        val d = DsyncPayloads.encodeProfiles(null, listOf(newer))
        assertEquals(listOf(newer.copy(avatar = com.aeriotv.android.core.profiles.AvatarSpec())), DsyncPayloads.decodeProfiles(d))
    }
}

class DsyncReconnectMergeTest {
    // B5: phone adds ESPNU (1042) offline; Streamer adds ESPNews (903) meanwhile.
    // On reconnect the pre-put read merges rows, so both survive on both devices.
    @Test fun offlineLocalAddPlusRemoteAddIsUnionAfterReconnect() {
        val before = listOf(77)
        val phoneLocal = before + 1042
        val serverAfterStreamer = before + 903
        val merged = DsyncGate.favoritesUnion(phoneLocal, serverAfterStreamer)
        assertEquals(setOf(77, 1042, 903), merged.toSet())
        // The PUT body built from the merged state keeps every id.
        val body = DsyncPayloads.encodeFavorites(null, merged)
        assertEquals(merged, DsyncPayloads.decodeFavorites(body))
        // Reminders and the dirty decision follow the same rule.
        val a = ReminderRow(1, 100, 200, "A", "C")
        val b = ReminderRow(2, 100, 200, "B", "D")
        assertEquals(2, DsyncGate.remindersUnion(listOf(a), listOf(a, b)).size)
        assertEquals(DsyncGate.DirtyPull.Merge, DsyncGate.onNewerPull(DsyncKey.Reminders, true, 50, 100))
    }
}
