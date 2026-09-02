package com.aeriotv.android.core.guide

import com.aeriotv.android.core.data.EPGProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideMergeTest {
    private fun p(title: String, start: Long, end: Long, desc: String = "", cat: String = "", isNew: Boolean = false) =
        EPGProgramme(channelId = "c", title = title, description = desc, startMillis = start, endMillis = end, category = cat, isNew = isNew)

    @Test fun sameTitleWithin60sIsDuplicate() {
        assertTrue(GuideMerge.isDuplicate(p("News", 0, 1_800_000), p("News", 59_000, 1_800_000)))
        // 61 s apart AND mostly non-overlapping (overlap 35 % of the incoming): kept.
        assertFalse(GuideMerge.isDuplicate(p("News", 0, 1_800_000), p("News", 61_000, 5_000_000)))
        // Different titles, same slot: the overlap rule still catches it.
        assertTrue(GuideMerge.isDuplicate(p("News", 0, 1_800_000), p("Noticias", 0, 1_800_000)))
    }

    @Test fun overlapIsMeasuredAgainstTheIncomingDuration() {
        val long = p("Movie", 0, 7_200_000)
        val short = p("Short", 3_600_000, 3_900_000) // fully inside the movie
        assertTrue(GuideMerge.isDuplicate(long, short))     // short incoming inside long existing: duplicate
        assertFalse(GuideMerge.isDuplicate(short, long))    // long incoming over short existing: kept
    }

    @Test fun existingKeepsIdentityAndTimesLongerDescriptionWins() {
        val a = p("A", 0, 1_000, desc = "short", cat = "Sports")
        val b = p("B", 10, 1_000, desc = "a much longer description", cat = "", isNew = true)
        val m = GuideMerge.merge(a, b)
        assertEquals("A", m.title); assertEquals(0L, m.startMillis)
        assertEquals("a much longer description", m.description)
        assertEquals("Sports", m.category); assertTrue(m.isNew)
    }

    @Test fun dedupCollapsesRunsKeepingTheEarlierRow() {
        val out = GuideMerge.dedup(listOf(p("X", 0, 1_800_000), p("X", 30_000, 1_800_000), p("Y", 1_800_000, 3_600_000)))
        assertEquals(listOf("X", "Y"), out.map { it.title })
        assertEquals(0L, out[0].startMillis)
    }
}
