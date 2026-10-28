package com.aeriotv.android.core.sync.dsync

import com.aeriotv.android.core.data.db.entity.FavoriteChannelEntity
import com.aeriotv.android.core.profiles.ProfileScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-10-28: a favorites pull after a roster add + switch never reached the new profile. */
class DsyncFavoritesTest {

    private val toLocal = mapOf(155 to "disp:a", 165 to "disp:b", 166 to "disp:c")
    private val toInt = toLocal.entries.associate { it.value to it.key }
    private val nameOf = mapOf(155 to "A", 165 to "B", 166 to "C")

    private fun fav(id: String, scope: String, order: Long = 0) =
        FavoriteChannelEntity(channelId = id, channelName = id, displayOrder = order, addedAt = 1L, profileScope = scope)

    @Test fun writesTheSessionScopeNotTheGlobalOne() {
        // The global scope is still the previous profile ("" = default) while
        // the session belongs to Jordan: every row must carry Jordan's scope.
        ProfileScope.activate(null)
        val jordan = "6ee31559-jordan"
        val p = DsyncFavorites.plan(listOf(155, 165, 999), toLocal, toInt, nameOf, emptyList(), jordan, 10L)
        assertEquals(listOf("disp:a", "disp:b"), p.wanted)
        assertEquals(2, p.upserts.size)
        assertTrue(p.upserts.all { it.profileScope == jordan })
        assertEquals(listOf(0L, 1L), p.upserts.map { it.displayOrder })
        assertEquals("B", p.upserts[1].channelName)
    }

    @Test fun deletesOnlyThisScopesDroppedRows() {
        val jordan = "jordan"
        val local = listOf(
            fav("disp:c", jordan), // dropped by the server list: delete
            fav("disp:a", jordan, 5), // kept, addedAt carried
            fav("m3u:other", jordan), // not in the snapshot: kept
            fav("disp:b", ""), // another profile's row: never touched
        )
        val p = DsyncFavorites.plan(listOf(155), toLocal, toInt, nameOf, local, jordan, 10L)
        assertEquals(listOf("disp:c"), p.deletes)
        assertEquals(listOf("disp:a"), p.upserts.map { it.channelId })
        assertEquals(1L, p.upserts[0].addedAt)
    }

    @Test fun logLineNamesScopeAndPlaylist() {
        assertEquals(
            "[DSYNC] favorites in: 11 ids, 11 mapped, 11 written, scope=6ee31559, playlist=3982c47c",
            DsyncFavorites.logLine("in", 11, 11, 11, "6ee31559-aaaa", "3982c47c-8f68"),
        )
        assertTrue(DsyncFavorites.logLine("out", 0, 0, 0, "", "p").contains("scope=default"))
    }
}
