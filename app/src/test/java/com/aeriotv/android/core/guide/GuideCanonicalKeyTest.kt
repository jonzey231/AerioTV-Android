package com.aeriotv.android.core.guide

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rows the new pipeline already wrote under canonical ids must attach without going through tvg-id/uuid/number. */
class GuideCanonicalKeyTest {
    private val espn = M3UChannel(id = "disp:277edaa2-a406-4dc6-a56e-6ffd23861292", name = "ESPN", url = "http://p/espn", tvgID = "espn.us")
    private fun row(key: String) = EPGProgramme(channelId = key, title = "T", description = "", startMillis = 0, endMillis = 1, category = "")

    @Test
    fun canonicalKeyResolvesToItself() {
        val maps = GuideMatchMaps.build(listOf(espn))
        assertEquals(setOf(espn.guideChannelId()), maps.resolve(espn.guideChannelId().value, GuideSource.GRID))
        assertEquals(setOf(espn.guideChannelId()), maps.resolve(espn.guideChannelId().value, GuideSource.UPSTREAM))
    }

    @Test
    fun canonicalKeyOfAnUnloadedChannelIsUnresolved() {
        val maps = GuideMatchMaps.build(listOf(espn))
        assertTrue(maps.resolve("disp:00000000-0000-0000-0000-000000000000").isEmpty())
        assertTrue(maps.resolve("m3u:deadbeef").isEmpty())
    }

    @Test
    fun attachCountsCanonicalRowsAsResolved() {
        val maps = GuideMatchMaps.build(listOf(espn))
        val r = GuideIngest.attach(listOf(row(espn.guideChannelId().value), row("espn.us"), row("nope")), maps, GuideSource.GRID)
        assertEquals(1, r.unresolved)
        assertEquals(2, r.programmes.size)
    }
}
