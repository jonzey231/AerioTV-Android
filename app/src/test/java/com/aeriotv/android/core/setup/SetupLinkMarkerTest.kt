package com.aeriotv.android.core.setup

import com.aeriotv.android.core.setup.SetupLinkMarker.Mark
import com.aeriotv.android.core.setup.SetupLinkMarker.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An old setup link is never opened again by accident; a rescan of a live
 * code always opens, cold start or not (Z Fold5, 2026-11-11).
 */
class SetupLinkMarkerTest {
    private val pk = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private fun link(code: String, ip: String = "192.0.2.1") =
        "aeriotv://setup?v=1&ip=$ip&port=9&code=$code&pk=$pk&name=TV"

    private val t0 = 1_800_000_000_000L
    private val minute = 60_000L

    @Test fun idFollowsCodeAndDeviceKeyNotAddress() {
        val a = SetupLinkMarker.linkId(link("123456"))
        assertEquals(a, SetupLinkMarker.linkId(link("123456", ip = "192.0.2.9")))
        assertNotEquals(a, SetupLinkMarker.linkId(link("654321")))
        assertNull(SetupLinkMarker.linkId("aeriotv://setup?v=1&code=12"))
    }

    @Test fun unknownLinkOpens() {
        assertFalse(SetupLinkMarker.shouldIgnore("a", emptyList(), t0))
        assertFalse(SetupLinkMarker.shouldIgnore("a", listOf(Mark("b", State.Sent, t0)), t0))
    }

    @Test fun sentIsIgnoredAtOnce() {
        assertEquals("already sent", SetupLinkMarker.ignoreReason("a", listOf(Mark("a", State.Sent, t0)), t0 + 1))
    }

    @Test fun rescanWithinTheSessionOpensWhateverHappenedToTheSheet() {
        // The Fold5 case: the sheet opened, then the app was swiped away (the mark may
        // still read Open) or the sheet was closed (Cancelled); the rescan cold-starts.
        for (state in listOf(State.Open, State.Cancelled)) {
            val marks = listOf(Mark("a", state, t0))
            assertNull(SetupLinkMarker.ignoreReason("a", marks, t0 + 30_000))
            assertNull(SetupLinkMarker.ignoreReason("a", marks, t0 + 10 * minute))
        }
    }

    @Test fun olderThanTheSessionIsIgnored() {
        for (state in State.entries) {
            val marks = listOf(Mark("a", state, t0))
            assertTrue(SetupLinkMarker.shouldIgnore("a", marks, t0 + 10 * minute + 1))
        }
        assertEquals("first seen over 10 minutes ago",
            SetupLinkMarker.ignoreReason("a", listOf(Mark("a", State.Cancelled, t0)), t0 + 11 * minute))
        // A mark stored before first-seen times existed counts as old.
        assertTrue(SetupLinkMarker.shouldIgnore("a", listOf(Mark("a", State.Cancelled)), t0))
    }

    @Test fun reopeningKeepsTheFirstSeenTime() {
        // Opened, closed, and reopened by rescans: the 10 minutes run from the first scan.
        var marks = SetupLinkMarker.updated(emptyList(), "a", State.Open, t0)
        marks = SetupLinkMarker.updated(marks, "a", State.Cancelled, t0 + 2 * minute)
        marks = SetupLinkMarker.updated(marks, "a", State.Open, t0 + 9 * minute)
        assertEquals(Mark("a", State.Open, t0), marks.single())
        assertFalse(SetupLinkMarker.shouldIgnore("a", marks, t0 + 9 * minute))
        assertTrue(SetupLinkMarker.shouldIgnore("a", marks, t0 + 11 * minute))
    }

    @Test fun replacedLinkStaysKnown() {
        // Link a opened, then a second scan (b) replaced it: a is cancelled, b open.
        var marks = SetupLinkMarker.updated(emptyList(), "a", State.Open, t0)
        marks = SetupLinkMarker.updated(marks, "a", State.Cancelled, t0 + minute)
        marks = SetupLinkMarker.updated(marks, "b", State.Open, t0 + minute)
        assertEquals(listOf(Mark("b", State.Open, t0 + minute), Mark("a", State.Cancelled, t0)), marks)
        // The task's old launch intent (a) replayed after the session ended is ignored.
        assertTrue(SetupLinkMarker.shouldIgnore("a", marks, t0 + 20 * minute))
    }

    @Test fun updateMovesToFrontAndCaps() {
        var marks = emptyList<Mark>()
        for (i in 1..(SetupLinkMarker.MAX_MARKS + 3)) marks = SetupLinkMarker.updated(marks, "id$i", State.Cancelled, t0 + i)
        assertEquals(SetupLinkMarker.MAX_MARKS, marks.size)
        assertEquals("id${SetupLinkMarker.MAX_MARKS + 3}", marks.first().id)
        marks = SetupLinkMarker.updated(marks, "id5", State.Sent, t0 + 100)
        assertEquals(Mark("id5", State.Sent, t0 + 5), marks.first())
        assertEquals(1, marks.count { it.id == "id5" })
    }

    @Test fun storageRoundTripAndOldFormat() {
        val marks = listOf(Mark("a", State.Open, t0), Mark("b", State.Sent, t0 + 1))
        assertEquals(marks, SetupLinkMarker.encode(marks).split(',').mapNotNull(SetupLinkMarker::parseMark))
        assertEquals(Mark("c", State.Cancelled, 0L), SetupLinkMarker.parseMark("c:Cancelled"))
        assertNull(SetupLinkMarker.parseMark("c:Bogus:1"))
        assertNull(SetupLinkMarker.parseMark(":Open:1"))
    }
}
