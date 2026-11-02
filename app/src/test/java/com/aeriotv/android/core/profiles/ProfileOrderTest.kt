package com.aeriotv.android.core.profiles

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileOrderTest {
    private val start = listOf("a", "b", "c", "d", "e")

    @Test fun fullOrderMatchesSingleMoves() {
        // "e" to the front by four Move Up steps, then "b" down two.
        var steps = start
        repeat(4) { steps = ProfileOrder.step(steps, "e", ProfileManager.Move.Earlier) }
        repeat(2) { steps = ProfileOrder.step(steps, "b", ProfileManager.Move.Later) }
        val pick = com.aeriotv.android.feature.player.PickUpReorder(start)
        pick.start(4); repeat(4) { pick.move(-1) }; pick.drop()
        pick.start(2); repeat(2) { pick.move(1) }
        val drop = pick.drop()!!
        assertEquals(steps, ProfileOrder.resolve(start, drop.order))
    }

    @Test fun unchangedOrderResolvesToCurrent() {
        assertEquals(start, ProfileOrder.resolve(start, start))
    }

    @Test fun rosterChangeMidLiftKeepsEveryProfile() {
        val current = listOf("a", "b", "c", "f")
        assertEquals(listOf("c", "a", "b", "f"), ProfileOrder.resolve(current, listOf("c", "a", "x", "b")))
    }

    @Test fun stepClampsAtBounds() {
        assertEquals(start, ProfileOrder.step(start, "a", ProfileManager.Move.Earlier))
        assertEquals(start, ProfileOrder.step(start, "e", ProfileManager.Move.Later))
    }
}
