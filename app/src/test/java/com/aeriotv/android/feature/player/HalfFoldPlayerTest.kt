package com.aeriotv.android.feature.player

import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.ui.adaptive.FoldAxis
import com.aeriotv.android.ui.adaptive.FoldInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Half-folded (tabletop) player: when it applies, and the Guide panel's rows and wording. */
class HalfFoldPlayerTest {
    private fun fold(axis: FoldAxis, half: Boolean) =
        FoldInfo(axis = axis, isSeparating = half, isOccluding = false, start = 400.dp, end = 400.dp, halfOpened = half)

    @Test fun appliesOnlyHalfOpenedAcrossAHorizontalCrease() {
        assertTrue(halfFoldApplies(fold(FoldAxis.HORIZONTAL, half = true), isTv = false, inPip = false))
        assertFalse(halfFoldApplies(fold(FoldAxis.HORIZONTAL, half = false), isTv = false, inPip = false))
        assertFalse(halfFoldApplies(fold(FoldAxis.VERTICAL, half = true), isTv = false, inPip = false))
        assertFalse(halfFoldApplies(null, isTv = false, inPip = false))
    }

    @Test fun neverOnTvOrInPictureInPicture() {
        val f = fold(FoldAxis.HORIZONTAL, half = true)
        assertFalse(halfFoldApplies(f, isTv = true, inPip = false))
        assertFalse(halfFoldApplies(f, isTv = false, inPip = true))
    }

    private val chs = (1..5).map { M3UChannel(id = "c$it", name = "Ch $it", url = "u$it") }

    @Test fun guideStartsAtThePlayingChannelInTheLiveTvOrder() {
        val order = listOf("c3", "c1", "c5", "c2")
        assertEquals(listOf("c5", "c2", "c3", "c1"), halfFoldGuideRows(order, chs, "c5").map { it.id })
        assertEquals(listOf("c3", "c1", "c5", "c2"), halfFoldGuideRows(order, chs, "c3").map { it.id })
    }

    @Test fun aChannelOutsideTheOrderStillLeads() {
        val order = listOf("c3", "c1")
        assertEquals(listOf("c4", "c3", "c1"), halfFoldGuideRows(order, chs, "c4").map { it.id })
    }

    @Test fun noPublishedOrderUsesThePlaylistOrder() {
        assertEquals(listOf("c4", "c5", "c1", "c2", "c3"), halfFoldGuideRows(emptyList(), chs, "c4").map { it.id })
        // Ids no longer in the playlist are skipped.
        assertEquals(listOf("c2", "c1"), halfFoldGuideRows(listOf("gone", "c1", "c2"), chs, "c2").map { it.id })
    }

    private fun prog(start: Long, end: Long, title: String = "P") =
        EPGProgramme(channelId = "x", title = title, description = "", startMillis = start, endMillis = end, category = "")

    @Test fun timeLeftWording() {
        val min = 60_000L
        assertEquals("42m left", halfFoldTimeLeft(prog(0, 60 * min), 18 * min))
        assertEquals("2h left", halfFoldTimeLeft(prog(0, 180 * min), 60 * min))
        assertEquals("1h 5m left", halfFoldTimeLeft(prog(0, 125 * min), 60 * min))
        assertEquals("<1m left", halfFoldTimeLeft(prog(0, 60 * min), 60 * min - 30_000))
        assertEquals("<1m left", halfFoldTimeLeft(prog(0, 60 * min), 61 * min))
    }

    @Test fun nextLineWording() {
        assertEquals("Next: SportsCenter, 9:00 AM", halfFoldNextLine("SportsCenter", "9:00 AM"))
    }

    @Test fun nowAndNext() {
        val a = prog(0, 100, "A")
        val b = prog(100, 200, "B")
        val c = prog(200, 300, "C")
        assertEquals(a to b, halfFoldNowNext(listOf(c, a, b), 50))
        assertEquals(b to c, halfFoldNowNext(listOf(a, b, c), 100))
        val (now, next) = halfFoldNowNext(null, 50)
        assertNull(now)
        assertNull(next)
        // A gap: nothing airing, the next program still shows.
        assertEquals(null to c, halfFoldNowNext(listOf(a, c), 150))
    }
}
