package com.aeriotv.android.core.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cancel on "PIN Required" removes only the profiles added by the action that
 * opened the step (item 7, 2026-11-11). A profile from a setup handoff that is
 * still waiting for its PIN stays exactly as it was: it is not removed, so no
 * tombstone is written for it.
 */
class PinStepCancelTest {

    @Test fun withAHandoffProfilePendingCancelRemovesOnlyTheNewAdd() {
        val pending = setOf("handoff", "new")
        val discard = RoleRules.pinStepCancelDiscards(addedInAction = listOf("new"), pending = pending, hasPin = { false })
        assertEquals(listOf("new"), discard)
    }

    @Test fun thePendingHandoffProfileIsUntouchedSoNoTombstoneIsWrittenForIt() {
        val discard = RoleRules.pinStepCancelDiscards(listOf("new"), setOf("handoff", "new")) { false }
        // Only removed profiles get a removal hook; the handoff profile is not among them.
        assertTrue("handoff" !in discard)
    }

    @Test fun anAddThatAlreadySetItsPinOrIsNoLongerPendingStays() {
        assertEquals(emptyList<String>(), RoleRules.pinStepCancelDiscards(listOf("new"), setOf("new")) { it == "new" })
        assertEquals(emptyList<String>(), RoleRules.pinStepCancelDiscards(listOf("gone"), setOf("handoff")) { false })
    }

    @Test fun aMultiAddCancelsEveryAddOfThatActionOnce() {
        val discard = RoleRules.pinStepCancelDiscards(listOf("a", "b", "a"), setOf("a", "b", "handoff")) { false }
        assertEquals(listOf("a", "b"), discard)
    }

    @Test fun theRemovedAddsFollowTheExistingUndoRules() {
        // A roster add (or an add during a pause) leaves no tombstone; a plain add keeps its own.
        assertEquals(false, ProfileRoster.undoWritesTombstone(rosterAdd = true))
        assertEquals(true, ProfileRoster.undoWritesTombstone(rosterAdd = false))
    }
}
