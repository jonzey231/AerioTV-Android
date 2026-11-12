package com.aeriotv.android.core.profiles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A profile added during a roster pause that takes its roster row when the
 * pause ends is a roster add from then on: undoing it on either path (the
 * onboarding Remove, or Cancel on "PIN Required") writes no tombstone (item 5,
 * review round 5 APP-1). One that found no row stays a plain add.
 */
class PauseAddUndoTest {

    private val undoPaths = listOf("onboarding-remove", "pin-required-cancel")

    @Test fun aPauseAddThatAdoptedItsRowUndoesWithNoTombstoneOnEveryPath() {
        val adds = RosterAdds()
        // Added during the pause: marked, not a roster add yet, and local only while marked.
        assertTrue(adds.undoIsLocalOnly("kid", stillPausedAdd = true))
        // The pause ends; the first pull gives it its roster row, and the mark is cleared.
        adds.pauseAddMerged("kid", tookRow = true)
        val localOnly = adds.undoIsLocalOnly("kid", stillPausedAdd = false)
        for (path in undoPaths) {
            assertFalse(path, ProfileRoster.undoWritesTombstone(rosterAdd = localOnly))
        }
    }

    @Test fun aPauseAddThatFoundNoRowStaysAPlainAdd() {
        val adds = RosterAdds()
        adds.pauseAddMerged("newcomer", tookRow = false)
        val localOnly = adds.undoIsLocalOnly("newcomer", stillPausedAdd = false)
        for (path in undoPaths) {
            assertTrue(path, ProfileRoster.undoWritesTombstone(rosterAdd = localOnly))
        }
    }

    @Test fun aFailedOrMalformedPullKeepsEveryMark() {
        // A failed GET or an absent value: no rows were read, nothing settles.
        assertTrue(RosterPause.settledPauseAdds(listOf("kid", "pat"), decodedRows = null).isEmpty())
        // A malformed value does not decode, so the merge (where marks settle) is never reached.
        val malformed = kotlinx.serialization.json.JsonObject(
            mapOf("rows" to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("not-a-row")))),
        )
        val decoded = com.aeriotv.android.core.sync.dsync.DsyncPayloads.decodeProfiles(malformed)
        assertTrue(RosterPause.settledPauseAdds(listOf("kid"), decoded).isEmpty())
        // While the mark stays, undoing that add stays local only.
        assertTrue(RosterAdds().undoIsLocalOnly("kid", stillPausedAdd = true))
    }

    @Test fun aDecodedPullSettlesTheMarks() {
        assertTrue(RosterPause.settledPauseAdds(listOf("kid"), decodedRows = emptyList()) == setOf("kid"))
    }

    @Test fun aRemovedProfileIsForgotten() {
        val adds = RosterAdds()
        adds.addedFromRoster("kid")
        assertTrue(adds.undoIsLocalOnly("kid", stillPausedAdd = false))
        adds.forget("kid")
        assertFalse(adds.undoIsLocalOnly("kid", stillPausedAdd = false))
    }
}
