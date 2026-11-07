package com.aeriotv.android.core.data.repository

import com.aeriotv.android.core.data.db.entity.EpgProgrammeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgDayDiffTest {
    private val h = 3_600_000L
    private val day = 1_000L * 86_400_000L
    private val now = day // removals never reach before this

    private fun row(id: Long, ch: String, startH: Int, endH: Int, title: String, desc: String = "") =
        EpgProgrammeEntity(
            id = id, playlistId = "p", channelId = ch, title = title, description = desc,
            startMillis = day + startH * h, endMillis = day + endH * h, category = "",
            dispatcharrProgramId = null, fetchedAt = 0L,
        )

    private fun fresh(r: EpgProgrammeEntity) = r.copy(id = 0, fetchedAt = 99L)

    @Test fun unchangedDayWritesNothing() {
        val cached = listOf(row(1, "a", 0, 2, "News"), row(2, "a", 2, 4, "Game"), row(3, "b", 0, 6, "Movie"))
        val d = EpgDayDiff.compute(cached.map(::fresh), cached, null, now)
        assertEquals(0, d.writes)
        assertEquals(3, d.unchanged)
    }

    @Test fun additionIsTheOnlyWrite() {
        val cached = listOf(row(1, "a", 0, 2, "News"))
        val fetched = cached.map(::fresh) + row(0, "a", 2, 4, "Late Show")
        val d = EpgDayDiff.compute(fetched, cached, null, now)
        assertEquals(1, d.added); assertEquals(0, d.changed); assertEquals(0, d.removed)
        assertEquals(listOf("Late Show"), d.upserts.map { it.title })
    }

    @Test fun seriesEndingEarlyRemovesTheDroppedGameAndChangesTheSlot() {
        // Game 7 at 20:00 is cancelled; the slot becomes a replay and the
        // 23:00 postgame show is dropped.
        val cached = listOf(
            row(1, "s", 18, 20, "Pregame"),
            row(2, "s", 20, 23, "Game 7"),
            row(3, "s", 23, 24, "Postgame"),
        )
        val fetched = listOf(fresh(cached[0]), row(0, "s", 20, 24, "Classic Replay"))
        val d = EpgDayDiff.compute(fetched, cached, null, now)
        assertEquals(1, d.unchanged)
        assertEquals(1, d.changed)
        assertEquals(0, d.added)
        // Postgame starts inside the fetched span (18..24) and is gone.
        assertEquals(listOf(3L), d.deleteIds)
        assertEquals(2, d.writes)
    }

    @Test fun endTimeMoveIsAChange() {
        val cached = listOf(row(1, "a", 0, 3, "Game"))
        val d = EpgDayDiff.compute(listOf(row(0, "a", 0, 2, "Game")), cached, null, now)
        assertEquals(1, d.changed); assertEquals(1, d.writes)
    }

    @Test fun descriptionChangeIsAChange() {
        val cached = listOf(row(1, "a", 0, 2, "News", "old"))
        val d = EpgDayDiff.compute(listOf(row(0, "a", 0, 2, "News", "new")), cached, null, now)
        assertEquals(1, d.changed)
    }

    @Test fun outOfScopeChannelsAreNeverWritten() {
        val cached = listOf(row(1, "a", 0, 2, "Old"), row(2, "b", 0, 2, "Old"))
        val fetched = listOf(row(0, "a", 0, 2, "New"), row(0, "b", 0, 2, "New"), row(0, "b", 2, 4, "Extra"))
        val d = EpgDayDiff.compute(fetched, cached, { it == "a" }, now)
        assertEquals(1, d.changed); assertEquals(0, d.added); assertEquals(1, d.writes)
        assertTrue(d.upserts.all { it.channelId == "a" })
    }

    @Test fun channelMissingFromFetchIsLeftAlone() {
        val cached = listOf(row(1, "a", 0, 2, "News"), row(2, "b", 0, 2, "Movie"))
        val d = EpgDayDiff.compute(listOf(fresh(cached[0])), cached, null, now)
        assertEquals(0, d.writes)
    }

    @Test fun alreadyAiredRowsAreNotRemoved() {
        val cached = listOf(row(1, "a", 0, 2, "Aired"), row(2, "a", 4, 6, "Later"))
        val fetched = listOf(row(0, "a", 4, 6, "Later").copy(startMillis = day + 4 * h), row(0, "a", 0, 1, "Short"))
        // now is 3h into the day: the aired row at 0h stays even though the fetch changed that span.
        val d = EpgDayDiff.compute(fetched, cached, null, day + 3 * h)
        assertEquals(0, d.removed)
    }

    @Test fun enrichedCategoryIsNotAChange() {
        val cached = listOf(row(1, "a", 0, 2, "News").copy(category = "News,Talk"))
        val d = EpgDayDiff.compute(listOf(row(0, "a", 0, 2, "News")), cached, null, now)
        assertEquals(0, d.writes)
    }

    @Test fun sourceRefreshWithNewProgramIdsIsNotARewrite() {
        val cached = listOf(row(1, "a", 0, 2, "News").copy(dispatcharrProgramId = 10))
        val fetched = listOf(row(0, "a", 0, 2, "News").copy(dispatcharrProgramId = 77))
        val d = EpgDayDiff.compute(fetched, cached, null, now)
        assertEquals(0, d.writes)
        assertEquals(1, d.unchanged)
        assertEquals(listOf(1L to 77), d.programIdUpdates)
    }
}
