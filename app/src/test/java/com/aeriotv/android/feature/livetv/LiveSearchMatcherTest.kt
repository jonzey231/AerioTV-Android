package com.aeriotv.android.feature.livetv

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.playlist.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSearchMatcherTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun ch(name: String, num: String) = M3UChannel(
        id = "id-$name", name = name, url = "http://x/$name", channelNumber = num, groupTitle = "G",
    )

    private fun prog(ch: M3UChannel, title: String, start: Long, end: Long, desc: String = "", sub: String? = null) =
        EPGProgramme(
            channelId = ch.guideMatchKey, title = title, description = desc,
            startMillis = start, endMillis = end, category = "", subTitle = sub,
        )

    private val alpha = ch("Alpha News", "101")
    private val bravo = ch("Bravo", "202")
    private val charlie = ch("Charlie", "303")
    private val delta = ch("Delta", "404")
    private val echo = ch("Echo", "505")
    private val channels = listOf(alpha, bravo, charlie, delta, echo)

    private val epg = mapOf(
        // Upcoming match, later start than delta's.
        bravo.guideMatchKey to listOf(prog(bravo, "Evening Roundup", now + 3 * hour, now + 4 * hour)),
        // Airing now match.
        charlie.guideMatchKey to listOf(prog(charlie, "Morning Roundup", now - hour, now + hour)),
        // Upcoming match through the sub-title, earlier start than bravo's.
        delta.guideMatchKey to listOf(
            prog(delta, "Already Over Roundup", now - 3 * hour, now - 2 * hour),
            prog(delta, "Weekly Show", now + hour, now + 2 * hour, sub = "The Roundup Special"),
        ),
        // Description only: must not match.
        echo.guideMatchKey to listOf(prog(echo, "Cooking", now - hour, now + hour, desc = "a roundup of recipes")),
    )

    private fun run(q: String, map: Map<String, List<EPGProgramme>>? = epg) = computeLiveSearch(
        channels, PlaylistViewModel.ALL_GROUPS, q, SortMode.ByNumber,
        listOf("G"), GroupSortMode.Default, emptySet(), emptySet(), emptyList(), emptyList(),
        map, now,
    )

    @Test fun nameMatch() {
        val r = run("alpha")
        assertEquals(listOf(alpha), r.channels)
        assertTrue(r.programMatches.isEmpty())
    }

    @Test fun numberMatch() {
        assertEquals(listOf(bravo), run("202").channels)
    }

    @Test fun programNowAndUpcomingAndOrdering() {
        val r = run("ROUNDUP")
        // Airing now (charlie), then upcoming by start (delta at +1h, bravo at +3h).
        assertEquals(listOf(charlie, delta, bravo), r.channels)
        assertEquals("Morning Roundup", r.programMatches[charlie.url]?.title)
        assertEquals("Weekly Show", r.programMatches[delta.url]?.title)
        assertNull(r.programMatches[echo.url])
    }

    @Test fun nameMatchesComeBeforeProgramMatches() {
        val r = run("o") // names with "o" plus program-only matches
        val names = r.channels.filter { it.url !in r.programMatches }
        assertEquals(r.channels.take(names.size), names)
    }

    @Test fun noDescriptionMatch() {
        assertTrue(run("recipes").channels.isEmpty())
    }

    @Test fun endedProgramDoesNotMatch() {
        assertTrue(run("already over").channels.isEmpty())
    }

    @Test fun nullEpgIsChannelOnly() {
        assertTrue(run("roundup", map = null).channels.isEmpty())
    }

    @Test
    fun programHitsCoverTheWholeGuideByStartWithCap() {
        val epg = mapOf(
            alpha.guideMatchKey to listOf(
                prog(alpha, "Late Match", now - 3 * hour, now - hour),
                prog(alpha, "Match Night", now - hour / 2, now + hour),
                prog(alpha, "Recap", now + 2 * hour, now + 3 * hour, sub = "Match"),
                prog(alpha, "Match Night", now + 26 * hour, now + 27 * hour),
            ),
            bravo.guideMatchKey to listOf(
                prog(bravo, "Big Match", now + hour, now + 2 * hour),
                prog(bravo, "Big Match", now + hour, now + 2 * hour),
            ),
            charlie.guideMatchKey to listOf(prog(charlie, "Match Hidden", now + hour, now + 2 * hour)),
        )
        val r = LiveProgramIndex.build(epg).programHits(" MATCH ", now, listOf(alpha, bravo))
        assertEquals(listOf(alpha, bravo, alpha), r.hits.map { it.channel })
        assertEquals(false, r.truncated)
        assertTrue(r.hits[0].isAiring(now))
        val many = LiveProgramIndex.build(
            mapOf(alpha.guideMatchKey to (0 until 250).map { prog(alpha, "Show", now + it * hour, now + it * hour + hour / 2) }),
        ).programHits("show", now, listOf(alpha))
        assertEquals(200, many.hits.size)
        assertTrue(many.truncated)
    }

    @Test
    fun channelNameFillerIsNotAProgram() {
        val keys = LiveSearchMatcher.channelTitleKeys("ESPN2", "ESPN2HD.us")
        assertEquals(setOf("espn2", "espn2hd"), keys)
        assertEquals(emptySet<String>(), LiveSearchMatcher.channelTitleKeys(" ", null))
        assertTrue(LiveSearchMatcher.isChannelTitle(" espn2 ", keys))
        assertEquals(false, LiveSearchMatcher.isChannelTitle("ESPN2 College Football", keys))
        val x = M3UChannel(id = "x", name = "ESPN2", url = "http://x/x", channelNumber = "1", groupTitle = "G", tvgID = "ESPN2HD.us")
        val y = ch("Other", "2")
        val epg = mapOf(
            x.guideMatchKey to listOf(
                prog(x, "ESPN2", now, now + hour),
                prog(x, "SportsCenter on ESPN2", now + hour, now + 2 * hour),
                prog(x, "espn2hd", now + 2 * hour, now + 3 * hour),
            ),
            y.guideMatchKey to listOf(prog(y, "ESPN2", now, now + hour)),
        )
        val r = LiveProgramIndex.build(epg).programHits("espn2", now, listOf(x, y))
        assertEquals(listOf("ESPN2", "SportsCenter on ESPN2"), r.hits.map { it.programme.title })
        assertEquals(listOf(y, x), r.hits.map { it.channel })
    }
}
