package com.aeriotv.android.core.guide

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideIdentityTest {
    private fun disp(name: String, uuid: String, boundKey: String, dispId: Int, number: String) = M3UChannel(
        id = "disp:$uuid", name = name, url = "http://srv/proxy/ts/stream/$uuid",
        tvgID = boundKey, channelNumber = number, dispatcharrChannelId = dispId,
    )
    private fun m3u(name: String, tvgId: String, url: String) = M3UChannel(
        id = "m3u:${tvgId.ifBlank { url }}", name = name, url = url, tvgID = tvgId,
    )
    private fun prog(key: String, title: String, start: Long = 0L, end: Long = 1_800_000L) = EPGProgramme(
        channelId = key, title = title, description = "", startMillis = start, endMillis = end, category = "",
    )

    @Test
    fun numericTvgIdNeverReachesAChannelThroughItsIntegerIdOrNumber() {
        // The 2026-09-01 field case: ESPN HD (Dispatcharr id 19, number 1,
        // bound to "espn.us") next to imported KNBC declaring tvg-id "19".
        val espn = disp("ESPN HD", "aaaa", boundKey = "espn.us", dispId = 19, number = "1")
        val knbc = m3u("KNBC", tvgId = "19", url = "http://xc/live/u/p/4790.ts")
        val maps = GuideMatchMaps.build(listOf(espn, knbc))
        val result = GuideIngest.attach(
            listOf(prog("19", "The Kelly Clarkson Show"), prog("ESPN.us", "SportsCenter"), prog("1", "Number One")),
            maps, GuideSource.GRID,
        )
        val byChannel = result.resolved.groupBy({ it.channelId }, { it.programme.title })
        assertEquals(listOf("The Kelly Clarkson Show"), byChannel[knbc.guideChannelId()])
        assertEquals(listOf("SportsCenter"), byChannel[espn.guideChannelId()])
        // "1" is nobody's tvg-id: dropped and counted, never matched by channel number.
        assertEquals(1, result.unresolvedCount)
        assertEquals(listOf("1"), result.unresolvedKeys)
    }

    @Test
    fun sharedTvgIdAttachesToEveryDeclaringChannel() {
        val a = m3u("ESPN East", "espn.us", "http://p/1.ts")
        val b = m3u("ESPN West", "espn.us", "http://p/2.ts")
        assertNotEquals(a.guideChannelId(), b.guideChannelId())
        val maps = GuideMatchMaps.build(listOf(a, b))
        val r = GuideIngest.attach(listOf(prog("espn.us", "SportsCenter")), maps, GuideSource.PLAYLIST_XMLTV)
        assertEquals(2, r.attachedCount)
        assertEquals(setOf(a.guideChannelId(), b.guideChannelId()), r.resolved.map { it.channelId }.toSet())
        assertTrue(r.resolved.all { it.programme.channelId == it.channelId.value })
    }

    @Test
    fun dummyEpgChannelsResolveByUuid() {
        val ch = disp("Local Cam", "bbbb-cccc", boundKey = "bbbb-cccc", dispId = 7, number = "900")
        val maps = GuideMatchMaps.build(listOf(ch))
        val r = GuideIngest.attach(listOf(prog("BBBB-CCCC", "Local Cam")), maps, GuideSource.GRID)
        assertEquals(1, r.attachedCount)
        assertEquals(ch.guideChannelId(), r.resolved.single().channelId)
    }

    @Test
    fun blankTvgIdContributesNothing() {
        val ch = m3u("No Guide", "", "http://p/3.ts")
        val maps = GuideMatchMaps.build(listOf(ch))
        assertTrue(maps.resolve("").isEmpty())
        val r = GuideIngest.attach(listOf(prog("", "Ghost")), maps, GuideSource.GRID)
        assertEquals(0, r.attachedCount)
        assertEquals(1, r.unresolvedCount)
    }

    @Test
    fun canonicalIdsNeverLeakCredentialsAndStayStable() {
        val ch = m3u("XC", "x", "http://host/live/user/secret/1.ts")
        val id = ch.guideChannelId().value
        assertTrue(id.startsWith("m3u:"))
        assertTrue(!id.contains("secret"))
        assertEquals(id, ch.copy(name = "renamed").guideChannelId().value)
    }

    @Test
    fun spanOwnershipRules() {
        val now = 1_000_000L
        // Grid owns now..end, never the past.
        assertEquals(now until 2_000_000L, SpanOwnership.replaceWindow(GuideSource.GRID, now, 0L, 2_000_000L, null))
        // Upstream may only fill before the grid's earliest row.
        assertEquals(0L until 500_000L, SpanOwnership.replaceWindow(GuideSource.UPSTREAM, now, 0L, 2_000_000L, 500_000L))
        assertNull(SpanOwnership.replaceWindow(GuideSource.UPSTREAM, now, 0L, 2_000_000L, null))
        // A user's own feed owns what it covers.
        assertEquals(0L until 2_000_000L, SpanOwnership.replaceWindow(GuideSource.USER_XMLTV, now, 0L, 2_000_000L, 500_000L))
    }

    @Test
    fun indexFindsCellsAndSynthesisesHoles() {
        val id = GuideChannelId("disp:x")
        val h = 3_600_000L
        val idx = GuideIndex.build(id, listOf(
            prog("disp:x", "Late", start = 6 * h, end = 7 * h),
            prog("disp:x", "Early", start = 2 * h, end = 4 * h),
            prog("disp:x", "Overlap", start = 3 * h + 1, end = 5 * h), // clipped to start at 4h
        ))
        assertEquals("Early", idx.cellAt(3 * h)?.title)
        assertEquals("Overlap", idx.cellAt(4 * h + 1)?.title)
        assertNull(idx.cellAt(5 * h + 1))
        val window = idx.withHoles(0L, 8 * h)
        assertEquals(listOf("No info", "Early", "Overlap", "No info", "Late", "No info"), window.map { it.title })
        assertTrue(window.filter { it.isPlaceholder }.all { it.channelId == id.value })
        // The hole between 5h and 6h is exactly the focusable gap the D-pad needs.
        val gap = window[3]
        assertEquals(5 * h, gap.startMillis); assertEquals(6 * h, gap.endMillis)
    }
}
