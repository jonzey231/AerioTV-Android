package com.aeriotv.android.core.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * A playlist deleted and then created again with the SAME id (2026-11-12,
 * measured on a phone: a setup from another device re-created the deleted
 * playlist with the sender's id, and every write for it was dropped until a
 * restart). The new playlist's writes go through; a late write from the
 * deleted playlist's task stays dropped.
 */
class DeletedPlaylistsReuseTest {
    @Before fun setUp() {
        DeletedPlaylists.resetForTests()
        DeletedPlaylists.logSink = {}
    }

    @After fun tearDown() {
        DeletedPlaylists.resetForTests()
        DeletedPlaylists.logSink = null
    }

    private fun dropped(block: () -> Unit): Boolean = try { block(); false } catch (e: CancellationException) { true }

    @Test fun reAddedPlaylistWritesWhileTheOldTasksStayBlocked() = runBlocking {
        // A refresh (or guide load) of X starts, then X is deleted.
        val oldTask = DeletedPlaylists.instance("X")
        DeletedPlaylists.mark("X")
        assertTrue("deleted: nothing writes", dropped { DeletedPlaylists.check("X", "channel snapshot", null) })
        assertTrue(dropped { DeletedPlaylists.check("X", "channel snapshot", oldTask) })
        // A setup creates X again with the same id (the playlist DAO's upsert).
        DeletedPlaylists.reborn("X")
        assertFalse(DeletedPlaylists.isDeleted("X"))
        // The new X's own task writes.
        val newTask = DeletedPlaylists.instance("X")
        assertFalse(dropped { DeletedPlaylists.check("X", "channel snapshot", newTask) })
        // A late write from the deleted X's task is still dropped.
        assertTrue(dropped { DeletedPlaylists.check("X", "EPG cache", oldTask) })
        // The coroutine element a background task was launched with carries its instance.
        try {
            withContext(DeletedPlaylists.Instance("X", oldTask)) { DeletedPlaylists.ensureAlive("X", "EPG sweep") }
            fail("the deleted X's background task must not write")
        } catch (e: CancellationException) {
            // dropped
        }
        withContext(DeletedPlaylists.started("X")) { DeletedPlaylists.ensureAlive("X", "EPG sweep") }
    }

    @Test fun deletedAgainAndAddedAgainKeepsEveryOlderInstanceOut() {
        val first = DeletedPlaylists.instance("X")
        DeletedPlaylists.mark("X"); DeletedPlaylists.reborn("X")
        val second = DeletedPlaylists.instance("X")
        DeletedPlaylists.mark("X")
        assertTrue(dropped { DeletedPlaylists.check("X", "VOD catalog", second) })
        DeletedPlaylists.reborn("X")
        assertTrue(dropped { DeletedPlaylists.check("X", "VOD catalog", first) })
        assertTrue(dropped { DeletedPlaylists.check("X", "VOD catalog", second) })
        assertFalse(dropped { DeletedPlaylists.check("X", "VOD catalog", DeletedPlaylists.instance("X")) })
    }

    @Test fun anIdNeverDeletedIsUntouched() {
        DeletedPlaylists.reborn("Y")
        assertFalse(DeletedPlaylists.isDeleted("Y"))
        assertFalse(dropped { DeletedPlaylists.check("Y", "EPG cache", 0L) })
        assertFalse(dropped { DeletedPlaylists.check("Y", "EPG cache", null) })
    }
}
