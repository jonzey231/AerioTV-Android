package com.aeriotv.android.core.profiles

import com.aeriotv.android.core.sync.dsync.ProfileRow
import com.aeriotv.android.core.sync.dsync.ProfileTombstone
import com.aeriotv.android.core.sync.dsync.ProfileTombstones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Undoing a roster add writes no tombstone (item 5, A1, 2026-11-11), on every
 * undo path: the onboarding Remove and Cancel on the "PIN Required" step after
 * an add while a Child exists. Both run ProfileManager.deleteLocal with
 * [ProfileRoster.undoWritesTombstone]. A plain add keeps today's tombstone.
 */
class RosterAddUndoTest {

    private val kidRow = ProfileRow(12, "kid", "Sam", "numbers", "a".repeat(64), "b".repeat(32), "child", 1_794_000_000L)
    private val now = 1_794_100_000L

    @Test fun undoingARosterAddOnAnyPathWritesNoTombstone() {
        // Onboarding Remove and the PIN-step Cancel take the same rule.
        for (path in listOf("onboarding-remove", "pin-required-cancel")) {
            assertFalse(path, ProfileRoster.undoWritesTombstone(rosterAdd = true))
        }
    }

    @Test fun afterARosterUndoTheRowIsOfferedAgainAndNothingRemovesIt() {
        val rows = listOf(kidRow)
        // While added: not offered.
        assertTrue(ProfileRoster.suggestions(rows, listOf("admin", "kid")).isEmpty())
        // Undone on this device only: offered again under "From your other devices".
        assertEquals(listOf(kidRow), ProfileRoster.suggestions(rows, listOf("admin")))
        // No tombstone went out, so no device drops the row.
        val tombs = emptyList<ProfileTombstone>()
        assertFalse(ProfileTombstones.kills(tombs, "kid", kidRow.updatedAt))
        assertEquals(listOf(kidRow), ProfileTombstones.resolve(rows, tombs).rows)
    }

    @Test fun undoingAPlainAddBehavesAsBeforeOnEveryPath() {
        for (path in listOf("onboarding-remove", "pin-required-cancel")) {
            assertTrue(path, ProfileRoster.undoWritesTombstone(rosterAdd = false))
        }
        // The tombstone it writes removes the row this device pushed for that user.
        val pushed = ProfileRow(40, "newcomer", "Newcomer", "none", "", "", "user", now - 60)
        val tombs = listOf(ProfileTombstone("newcomer", now))
        assertTrue(ProfileTombstones.kills(tombs, "newcomer", pushed.updatedAt))
        assertTrue(ProfileTombstones.resolve(listOf(pushed), tombs).rows.isEmpty())
    }

    @Test fun aManualAddThatFinishedAsARosterAddUndoesLikeOne() {
        val row = ProfileRoster.rowForManualAdd(listOf(kidRow), listOf("admin"), "Kid")
        assertEquals(kidRow, row)
        assertFalse(ProfileRoster.undoWritesTombstone(rosterAdd = row != null))
    }
}
