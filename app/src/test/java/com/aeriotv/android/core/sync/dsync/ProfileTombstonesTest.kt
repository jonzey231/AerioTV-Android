package com.aeriotv.android.core.sync.dsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schema 4.12 removal tombstones: a stale third device must not resurrect a removed profile. */
class ProfileTombstonesTest {

    private fun row(id: Int, u: String, at: Long) = ProfileRow(id, u, u, "none", "", "", "user", at)

    @Test fun removalThenStaleDeviceReAddIsDropped() {
        // Streamer removed Test2 at T; the stale phone still has the 2026-10-18 row.
        val removedAt = 1_793_000_000L
        val tombs = listOf(ProfileTombstone("test2", removedAt, 8))
        val server = listOf(row(1, "admin", 1_792_000_000L))
        val staleRow = row(8, "Test2", 1_792_347_494L)
        val r = ProfileTombstones.resolve(server + staleRow, tombs)
        assertEquals(listOf("admin"), r.rows.map { it.username })
        assertEquals(listOf("Test2"), r.dropped.map { it.username })
        assertEquals(tombs, r.removed)
        assertTrue(ProfileTombstones.kills(tombs, "TEST2", staleRow.updatedAt))
        // And the encoded payload keeps the tombstone for every other device.
        val data = DsyncPayloads.encodeProfiles(null, r.rows, r.removed)
        assertEquals(tombs, DsyncPayloads.decodeProfileTombstones(data))
        assertEquals(1, DsyncPayloads.decodeProfiles(data)!!.size)
    }

    @Test fun staleLocalRowWithoutTombstoneIsNotReSent() {
        val server = listOf(row(1, "admin", 1_793_100_000L))
        val local = row(7, "Test1", 1_792_346_722L)
        assertTrue(ProfileTombstones.staleLocal(local, server, 1_793_100_000L, local.updatedAt, lastPushedAt = 1_792_346_722L))
        // An unsent local change (newer than the last push) is a real add.
        val fresh = row(9, "Test3", 1_793_200_000L)
        assertFalse(ProfileTombstones.staleLocal(fresh, server, 1_793_100_000L, fresh.updatedAt, lastPushedAt = 1_793_000_000L))
        // A row the server already carries is merged per row, never "stale".
        assertFalse(ProfileTombstones.staleLocal(row(1, "admin", 1L), server, 1_793_100_000L, 1L, 1L))
    }

    @Test fun editAfterRemovalReAddsAndClearsTombstone() {
        val tombs = listOf(ProfileTombstone("test2", 1_793_000_000L))
        val r = ProfileTombstones.resolve(listOf(row(8, "Test2", 1_793_000_500L)), tombs)
        assertEquals(listOf("Test2"), r.rows.map { it.username })
        assertTrue(r.removed.isEmpty())
        // Same second as the removal: the tombstone still wins.
        assertTrue(ProfileTombstones.kills(tombs, "test2", 1_793_000_000L))
    }

    @Test fun tombstonesExpireAfterNinetyDays() {
        val at = 1_793_000_000L
        val t = listOf(ProfileTombstone("test1", at), ProfileTombstone("test2", at + 86_400L))
        val now = at + ProfileTombstones.WINDOW_S + 1
        assertEquals(listOf("test2"), ProfileTombstones.expire(t, now).map { it.username })
        assertEquals(2, ProfileTombstones.expire(t, at + ProfileTombstones.WINDOW_S).size)
    }

    @Test fun unionKeepsNewestPerUsername() {
        val u = ProfileTombstones.union(
            listOf(ProfileTombstone("test1", 10L, 7)),
            listOf(ProfileTombstone("Test1", 20L), ProfileTombstone("test2", 5L)),
        ).associateBy { it.username }
        assertEquals(20L, u["test1"]!!.removedAt)
        assertEquals(7, u["test1"]!!.userId)
        assertEquals(5L, u["test2"]!!.removedAt)
    }

    @Test fun emptyRemovedListDropsTheMember() {
        val base = DsyncPayloads.encodeProfiles(null, emptyList(), listOf(ProfileTombstone("a", 1L)))
        assertFalse(DsyncPayloads.encodeProfiles(base, emptyList(), emptyList()).containsKey("removed"))
    }
}
