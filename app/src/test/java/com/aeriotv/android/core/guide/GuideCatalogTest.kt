package com.aeriotv.android.core.guide

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCatalogTest {
    private val h = 3_600_000L
    private fun ch(name: String, tvg: String, url: String = "http://p/$name") =
        M3UChannel(id = "m3u:$name", name = name, url = url, tvgID = tvg)
    private fun p(key: String, title: String, start: Long, end: Long) =
        EPGProgramme(channelId = key, title = title, description = "", startMillis = start, endMillis = end, category = "")

    @Test
    fun buildsIndicesAndKeepsTheMapContractForOldConsumers() {
        val espn = ch("ESPN", "espn.us"); val fox = ch("FOX", "fox.us"); val quiet = ch("Quiet", "quiet.us")
        val cat = GuideCatalog.build(
            listOf(espn, fox, quiet),
            listOf(p("ESPN.us", "SportsCenter", 6 * h, 7 * h), p("fox.us", "News", 0, h)),
            windowStartMs = 0, windowEndMs = 8 * h,
        )
        // Old consumers: epgByChannel[channel.guideMatchKey]
        val espnList = cat[espn.guideChannelId().value]
        assertNotNull(espnList)
        assertEquals(listOf("No info", "SportsCenter", "No info"), espnList!!.map { it.title })
        assertEquals(listOf("News", "No info"), cat[fox.guideChannelId().value]!!.map { it.title })
        // A channel with nothing gets the channel-name placeholder.
        val q = cat[quiet.guideChannelId().value]!!.single()
        assertTrue(q.isPlaceholder); assertEquals("Quiet", q.title)
        // Direct index access for the new renderer.
        assertEquals("SportsCenter", cat.index(espn.guideChannelId())!!.cellAt(6 * h + 1)?.title)
        assertEquals(3, cat.size); assertTrue(cat.containsKey(fox.guideChannelId().value))
    }

    @Test
    fun rowsAlreadyKeyedByCanonicalIdAreTakenDirectly() {
        val espn = ch("ESPN", "espn.us")
        val cat = GuideCatalog.build(listOf(espn), listOf(p(espn.guideChannelId().value, "Direct", 0, h)), 0, 2 * h)
        assertEquals("Direct", cat.index(espn.guideChannelId())!!.cellAt(1)?.title)
    }

    @Test
    fun unchangedChannelsKeepTheirPreviousListInstance() {
        val espn = ch("ESPN", "espn.us"); val fox = ch("FOX", "fox.us")
        val rows = listOf(p("espn.us", "A", 0, h), p("fox.us", "B", 0, h))
        val first = GuideCatalog.build(listOf(espn, fox), rows, 0, 2 * h)
        val second = GuideCatalog.build(listOf(espn, fox), rows + p("fox.us", "C", h, 2 * h), 0, 2 * h, previous = first)
        assertSame(first[espn.guideChannelId().value], second[espn.guideChannelId().value])
        assertTrue(first[fox.guideChannelId().value] !== second[fox.guideChannelId().value])
    }

    @Test
    fun duplicatesFromTwoSourcesCollapse() {
        val espn = ch("ESPN", "espn.us")
        val cat = GuideCatalog.build(
            listOf(espn),
            listOf(p("espn.us", "Game", 0, 2 * h), p("espn.us", "Game", 30_000, 2 * h)),
            0, 2 * h,
        )
        assertEquals(1, cat.index(espn.guideChannelId())!!.cells.size)
    }
}
