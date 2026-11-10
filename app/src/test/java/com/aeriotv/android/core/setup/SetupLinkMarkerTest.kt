package com.aeriotv.android.core.setup

import com.aeriotv.android.core.setup.SetupLinkMarker.Mark
import com.aeriotv.android.core.setup.SetupLinkMarker.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same setup link is never opened twice by accident; a fresh scan after Cancel still works. */
class SetupLinkMarkerTest {
    private val pk = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private fun link(code: String, ip: String = "192.0.2.1") =
        "aeriotv://setup?v=1&ip=$ip&port=9&code=$code&pk=$pk&name=TV"

    @Test fun idFollowsCodeAndDeviceKeyNotAddress() {
        val a = SetupLinkMarker.linkId(link("123456"))
        assertEquals(a, SetupLinkMarker.linkId(link("123456", ip = "192.0.2.9")))
        assertNotEquals(a, SetupLinkMarker.linkId(link("654321")))
        assertNull(SetupLinkMarker.linkId("aeriotv://setup?v=1&code=12"))
    }

    @Test fun unknownLinkOpens() {
        assertFalse(SetupLinkMarker.shouldIgnore("a", emptyList(), coldLaunch = true))
        assertFalse(SetupLinkMarker.shouldIgnore("a", listOf(Mark("b", State.Sent)), coldLaunch = false))
    }

    @Test fun openOrSentIsIgnoredEverywhere() {
        for (cold in listOf(true, false)) {
            assertTrue(SetupLinkMarker.shouldIgnore("a", listOf(Mark("a", State.Open)), cold))
            assertTrue(SetupLinkMarker.shouldIgnore("a", listOf(Mark("a", State.Sent)), cold))
        }
    }

    @Test fun afterCancelAFreshScanOpensButAColdReplayDoesNot() {
        val marks = listOf(Mark("a", State.Cancelled))
        assertFalse(SetupLinkMarker.shouldIgnore("a", marks, coldLaunch = false))
        assertTrue(SetupLinkMarker.shouldIgnore("a", marks, coldLaunch = true))
    }

    @Test fun replacedLinkStaysKnown() {
        // Link a opened, then a second scan (b) replaced it: a is cancelled, b open.
        var marks = SetupLinkMarker.updated(emptyList(), "a", State.Open)
        marks = SetupLinkMarker.updated(marks, "a", State.Cancelled)
        marks = SetupLinkMarker.updated(marks, "b", State.Open)
        assertEquals(listOf(Mark("b", State.Open), Mark("a", State.Cancelled)), marks)
        // The task's old launch intent (a) replayed on a cold start is still ignored.
        assertTrue(SetupLinkMarker.shouldIgnore("a", marks, coldLaunch = true))
    }

    @Test fun updateMovesToFrontAndCaps() {
        var marks = emptyList<Mark>()
        for (i in 1..(SetupLinkMarker.MAX_MARKS + 3)) marks = SetupLinkMarker.updated(marks, "id$i", State.Cancelled)
        assertEquals(SetupLinkMarker.MAX_MARKS, marks.size)
        assertEquals("id${SetupLinkMarker.MAX_MARKS + 3}", marks.first().id)
        marks = SetupLinkMarker.updated(marks, "id5", State.Sent)
        assertEquals(Mark("id5", State.Sent), marks.first())
        assertEquals(1, marks.count { it.id == "id5" })
    }
}
