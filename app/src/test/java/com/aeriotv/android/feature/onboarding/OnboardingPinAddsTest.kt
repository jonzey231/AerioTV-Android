package com.aeriotv.android.feature.onboarding

import com.aeriotv.android.core.profiles.RoleRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 14 (Apple parity, 2026-11-11): onboarding asks for the PIN right after
 * each manual add and each "Add selected" batch; Cancel there removes only
 * that add or batch, and Continue's walk passes no adds.
 */
class OnboardingPinAddsTest {

    private val noPin: (String) -> Boolean = { false }

    @Test
    fun eachManualAddAsksRightAwayWithOnlyItself() {
        assertEquals(listOf("adult1"), OnboardingPinAdds.afterManualAdd("adult1", needsPin = true))
        // No Child yet, a Child added, or a PIN already set: no PIN step.
        assertNull(OnboardingPinAdds.afterManualAdd("kid", needsPin = false))
        // Cancel on that step removes only that add, not an earlier one still pending.
        val pending = setOf("adult0", "adult1")
        assertEquals(listOf("adult1"), RoleRules.pinStepCancelDiscards(listOf("adult1"), pending, noPin))
    }

    @Test
    fun cancelAfterABatchRemovesOnlyThatBatch() {
        val adds = OnboardingPinAdds()
        adds.startBatch()
        adds.batchAdded("a")
        adds.batchAdded("b")
        assertEquals(setOf("a", "b"), adds.batchAdds().toSet())
        // A second batch (after a partial failure) is its own action.
        adds.startBatch()
        adds.batchAdded("c")
        assertEquals(listOf("c"), adds.batchAdds())
        val pending = setOf("a", "b", "c", "handoff")
        assertEquals(listOf("c"), RoleRules.pinStepCancelDiscards(adds.batchAdds(), pending, noPin))
    }

    @Test
    fun continueAsksForNoAdds() {
        val adds = OnboardingPinAdds()
        adds.startBatch()
        adds.batchAdded("a")
        val walk = adds.continueAdds()
        assertTrue(walk.isEmpty())
        // Cancel on Continue's walk removes nothing, whatever is still pending.
        assertTrue(RoleRules.pinStepCancelDiscards(walk, setOf("a", "handoff"), noPin).isEmpty())
        assertTrue(adds.batchAdds().isEmpty())
    }
}
