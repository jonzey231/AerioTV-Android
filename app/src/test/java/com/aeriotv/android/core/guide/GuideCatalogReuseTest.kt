package com.aeriotv.android.core.guide

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item 6 (2026-10-27): a rebuild that changes nothing is not republished. */
class GuideCatalogReuseTest {
    private val h = 3_600_000L
    private fun ch(name: String, tvg: String) = M3UChannel(id = "m3u:$name", name = name, url = "http://p/$name", tvgID = tvg)
    private fun p(key: String, title: String, start: Long, end: Long) =
        EPGProgramme(channelId = key, title = title, description = "", startMillis = start, endMillis = end, category = "")

    private val espn = ch("ESPN", "espn.us")
    private val fox = ch("FOX", "fox.us")
    private val rows = listOf(p("espn.us", "A", 0, h), p("fox.us", "B", 0, h))

    @Test
    fun identicalRebuildReusesEveryIndexAndReportsSameContent() {
        val first = GuideCatalog.build(listOf(espn, fox), rows, 0, 2 * h)
        val second = GuideCatalog.build(listOf(espn, fox), rows.map { it.copy() }, 0, 2 * h, previous = first)
        assertSame(first.index(espn.guideChannelId()), second.index(espn.guideChannelId()))
        assertTrue(second.sameContentAs(first))
    }

    @Test
    fun changedProgramIsNewContent() {
        val first = GuideCatalog.build(listOf(espn, fox), rows, 0, 2 * h)
        val second = GuideCatalog.build(listOf(espn, fox), rows + p("fox.us", "C", h, 2 * h), 0, 2 * h, previous = first)
        assertFalse(second.sameContentAs(first))
        // The untouched channel still keeps its instance.
        assertSame(first.index(espn.guideChannelId()), second.index(espn.guideChannelId()))
    }

    @Test
    fun widerWindowOrNewChannelIsNewContent() {
        val first = GuideCatalog.build(listOf(espn, fox), rows, 0, 2 * h)
        assertFalse(GuideCatalog.build(listOf(espn, fox), rows, 0, 4 * h, previous = first).sameContentAs(first))
        val third = ch("CBS", "cbs.us")
        assertFalse(GuideCatalog.build(listOf(espn, fox, third), rows, 0, 2 * h, previous = first).sameContentAs(first))
    }
}
