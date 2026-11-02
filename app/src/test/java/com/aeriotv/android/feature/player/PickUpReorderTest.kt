package com.aeriotv.android.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickUpReorderTest {

    @Test fun startLiftsInRangeOnly() {
        val r = PickUpReorder(listOf(1, 2, 3))
        assertFalse(r.start(3))
        assertFalse(r.start(-1))
        assertTrue(r.start(1))
        assertEquals(1, r.liftedIndex)
        assertEquals(2, r.liftedItem)
        assertFalse("second start while lifted", r.start(0))
    }

    @Test fun moveUpAndDownRespectsBounds() {
        val r = PickUpReorder(listOf(1, 2, 3))
        r.start(0)
        assertFalse(r.move(-1))
        assertTrue(r.move(1))
        assertEquals(listOf(2, 1, 3), r.order)
        assertTrue(r.move(1))
        assertEquals(listOf(2, 3, 1), r.order)
        assertFalse(r.move(1))
        assertTrue(r.move(-1))
        assertEquals(listOf(2, 1, 3), r.order)
        assertEquals(1, r.liftedIndex)
    }

    @Test fun moveWithoutLiftDoesNothing() {
        val r = PickUpReorder(listOf(1, 2))
        assertFalse(r.move(1))
        assertEquals(listOf(1, 2), r.order)
        assertNull(r.drop())
        assertEquals(-1, r.cancel())
    }

    @Test fun dropComputesFullOrder() {
        val r = PickUpReorder(listOf(10, 20, 30, 40))
        r.start(3)
        r.move(-1); r.move(-1)
        val d = r.drop()!!
        assertEquals(40, d.item)
        assertEquals(3, d.from)
        assertEquals(1, d.to)
        assertTrue(d.changed)
        assertEquals(listOf(10, 40, 20, 30), d.order)
        assertFalse(r.isLifted)
        assertEquals(listOf(10, 40, 20, 30), r.order)
    }

    @Test fun dropInPlaceIsUnchanged() {
        val r = PickUpReorder(listOf(1, 2, 3))
        r.start(1)
        r.move(1); r.move(-1)
        val d = r.drop()!!
        assertFalse(d.changed)
        assertEquals(listOf(1, 2, 3), d.order)
    }

    @Test fun cancelRestoresOriginal() {
        val r = PickUpReorder(listOf(1, 2, 3))
        r.start(2)
        r.move(-1); r.move(-1)
        assertEquals(listOf(3, 1, 2), r.order)
        assertEquals(2, r.cancel())
        assertEquals(listOf(1, 2, 3), r.order)
        assertFalse(r.isLifted)
    }

    @Test fun resetIgnoredWhileLifted() {
        val r = PickUpReorder(listOf(1, 2, 3))
        r.start(0); r.move(1)
        r.reset(listOf(9))
        assertEquals(listOf(2, 1, 3), r.order)
        r.cancel()
        r.reset(listOf(3, 2, 1))
        assertEquals(listOf(3, 2, 1), r.order)
    }
}
