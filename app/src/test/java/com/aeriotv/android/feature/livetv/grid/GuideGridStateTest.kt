package com.aeriotv.android.feature.livetv.grid

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.guide.GuideCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideGridStateTest {
    private val h = 3_600_000L
    private val m = 60_000L
    private fun ch(name: String) = M3UChannel(id = "m3u:$name", name = name, url = "http://p/$name", tvgID = "$name.tv")
    private fun p(ch: M3UChannel, title: String, start: Long, end: Long) =
        EPGProgramme(channelId = "${ch.name}.tv", title = title, description = "", startMillis = start, endMillis = end, category = "")

    private val espn = ch("ESPN"); private val fox = ch("FOX"); private val quiet = ch("Quiet")
    private val window = 0L to 12 * h

    private fun rows(vararg channels: M3UChannel, programmes: List<EPGProgramme>): GuideGridRows {
        val cat = GuideCatalog.build(channels.toList(), programmes, window.first, window.second)
        return GuideGridRows(channels.toList(), cat, window.first, window.second)
    }

    private fun state(now: Long = 6 * h) = GuideGridState(initialViewportStartMs = now - 15 * m).also { it.viewportDurationMs = 3 * h }

    @Test
    fun firstInstallLandsOnRowZeroAtTheAnchor() {
        val s = state()
        s.installRows(rows(espn, fox, programmes = listOf(p(espn, "A", 5 * h, 7 * h), p(fox, "B", 0, 12 * h))))
        assertEquals(0, s.focusRow)
        assertEquals("A", s.focusedCell()!!.title)
        assertEquals(6 * h, s.anchorMs)
    }

    @Test
    fun verticalMoveLandsOnTheCellContainingTheAnchorAndNeverMovesTheTimeline() {
        val s = state()
        s.installRows(rows(espn, fox, programmes = listOf(p(espn, "A", 5 * h, 7 * h), p(fox, "Early", 0, 6 * h - 1), p(fox, "Late", 6 * h - 1, 12 * h))))
        val before = s.viewportStartMs
        assertTrue(s.moveRows(1))
        assertEquals(1, s.focusRow)
        assertEquals("Late", s.focusedCell()!!.title)
        assertEquals(before, s.viewportStartMs)
    }

    @Test
    fun upAtTheTopEscapesInsteadOfMoving() {
        val s = state()
        s.installRows(rows(espn, programmes = listOf(p(espn, "A", 0, 12 * h))))
        assertFalse(s.moveRows(-1))
        assertEquals(0, s.focusRow)
    }

    @Test
    fun holesAreLandableCells() {
        val s = state()
        s.installRows(rows(espn, fox, programmes = listOf(p(espn, "A", 0, 4 * h), p(fox, "B", 0, 12 * h))))
        assertTrue(s.focusedCell()!!.isPlaceholder)
        assertEquals("No info", s.focusedCell()!!.title)
        assertTrue(s.moveRows(1)); assertEquals("B", s.focusedCell()!!.title)
        assertTrue(s.moveRows(-1)); assertEquals("No info", s.focusedCell()!!.title)
    }

    @Test
    fun emptyChannelGetsItsNamePlaceholder() {
        val s = state()
        s.installRows(rows(quiet, programmes = emptyList()))
        assertEquals("Quiet", s.focusedCell()!!.title)
        assertTrue(s.focusedCell()!!.isPlaceholder)
    }

    @Test
    fun horizontalPanMovesHalfAnHourAndRetargetsOnTheSameRow() {
        val s = state()
        s.installRows(rows(espn, programmes = listOf(p(espn, "A", 0, 6 * h + 20 * m), p(espn, "B", 6 * h + 20 * m, 12 * h))))
        assertEquals("A", s.focusedCell()!!.title)
        assertTrue(s.pan(+1))
        assertEquals(6 * h + 15 * m, s.viewportStartMs)
        assertEquals("B", s.focusedCell()!!.title)
        assertTrue(s.pan(-1))
        assertEquals("A", s.focusedCell()!!.title)
    }

    @Test
    fun panStopsAtTheWindowEdges() {
        val s = state()
        s.installRows(rows(espn, programmes = listOf(p(espn, "A", 0, 12 * h))))
        var n = 0
        while (s.pan(+1)) n++
        assertEquals(9 * h, s.viewportStartMs) // window end (12h) minus viewport (3h)
        assertTrue(n > 0)
        assertFalse(s.pan(+1))
    }

    @Test
    fun focusFollowsTheChannelAcrossARowListSwap() {
        val s = state()
        s.installRows(rows(espn, fox, quiet, programmes = listOf(p(espn, "A", 0, 12 * h), p(fox, "B", 0, 12 * h))))
        s.moveRows(2)
        assertEquals("Quiet", s.rows.channel(s.focusRow).name)
        // Sort flips the order: Quiet is now row 0.
        s.installRows(rows(quiet, fox, espn, programmes = listOf(p(espn, "A", 0, 12 * h), p(fox, "B", 0, 12 * h))))
        assertEquals(0, s.focusRow)
        assertEquals("Quiet", s.focusChannelId?.let { id -> s.rows.channels.first { it.id == id }.name })
        // Group change drops the channel: focus clamps to a valid row, never -1.
        s.installRows(rows(espn, programmes = listOf(p(espn, "A", 0, 12 * h))))
        assertEquals(0, s.focusRow)
        assertEquals("A", s.focusedCell()!!.title)
    }

    @Test
    fun backLadderRestoresNowThenTopThenNothing() {
        val now = 6 * h
        val s = state(now)
        s.installRows(rows(espn, fox, programmes = listOf(p(espn, "A", 0, 12 * h), p(fox, "B", 0, 12 * h))))
        s.moveRows(1)
        repeat(3) { s.pan(+1) } // 90 minutes away from now: beyond the 30-minute slop
        assertTrue(s.isAwayFromNow(now))
        assertEquals(GuideGridState.BackStep.RESTORED_NOW_AND_TOP, s.back(now))
        assertEquals(0, s.focusRow)
        assertEquals(now - 15 * m, s.viewportStartMs)
        s.moveRows(1)
        assertEquals(GuideGridState.BackStep.TOP, s.back(now))
        assertEquals(GuideGridState.BackStep.NONE, s.back(now))
    }

    @Test
    fun aSmallPanStaysWithinTheSlop() {
        val now = 6 * h
        val s = state(now)
        s.installRows(rows(espn, programmes = listOf(p(espn, "A", 0, 12 * h))))
        s.pan(+1)
        assertFalse(s.isAwayFromNow(now))
    }
}
