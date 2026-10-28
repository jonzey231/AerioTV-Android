package com.aeriotv.android.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lane rules for per-item sync scopes (docs/DispatcharrSyncSchema.md 4.9). */
class SyncScopeLanesTest {

    @Test fun eachDeviceUsesOnlyItsOwnClass() {
        assertEquals("v", SyncScope.All.lane(isTv = true))
        assertEquals("tv", SyncScope.Tv.lane(isTv = true))
        assertEquals("mobile", SyncScope.Mobile.lane(isTv = false))
        assertNull(SyncScope.Off.lane(isTv = true))
        // The other class's scope (for example after a restore onto a phone) reads as All devices.
        assertEquals("v", SyncScope.Tv.lane(isTv = false))
        assertEquals("v", SyncScope.Mobile.lane(isTv = true))
        assertEquals(listOf(SyncScope.All, SyncScope.Tv, SyncScope.Off), SyncScope.choices(isTv = true))
        assertEquals(listOf(SyncScope.All, SyncScope.Mobile, SyncScope.Off), SyncScope.choices(isTv = false))
    }

    @Test fun pushSplitsByScopeAndOffWritesNothing() {
        val scopes = mapOf("textScale" to SyncScope.Tv, "selectedTheme" to SyncScope.All, "timeFormat" to SyncScope.Off)
        val values = mapOf("textScale" to 1.2, "selectedTheme" to "Midnight", "timeFormat" to "24")
        val tv = laneValues(values, { scopes.getValue(it) }, isTv = true)
        assertEquals(mapOf("v" to mapOf("selectedTheme" to "Midnight"), "tv" to mapOf("textScale" to 1.2)), tv)
        // On a phone "TVs only" is All devices.
        val phone = laneValues(values, { scopes.getValue(it) }, isTv = false)
        assertEquals(mapOf("v" to mapOf("textScale" to 1.2, "selectedTheme" to "Midnight")), phone)
    }

    @Test fun pullReadsOneLaneWithoutFallback() {
        val lanes = mapOf(
            "v" to mapOf("textScale" to 1.0, "selectedTheme" to "Aerio", "timeFormat" to "12"),
            "tv" to mapOf("selectedTheme" to "Midnight"),
            "mobile" to mapOf("textScale" to 1.3),
        )
        val scopes = mapOf("textScale" to SyncScope.Tv, "selectedTheme" to SyncScope.Tv, "timeFormat" to SyncScope.Off)
        // TV: TVs only reads `tv` and ignores `v`; no tv value for textScale means nothing.
        assertEquals(mapOf("selectedTheme" to "Midnight"), pickFromLanes(lanes, emptyList(), { scopes.getValue(it) }, isTv = true))
        // Phone: the TV scope reads as All devices, so `v`; the mobile lane is never consulted for an All item.
        assertEquals(mapOf("textScale" to 1.0, "selectedTheme" to "Aerio"), pickFromLanes(lanes, emptyList(), { scopes.getValue(it) }, isTv = false))
    }

    @Test fun catalogIdsAreUniqueAndNamesBelongToOneItem() {
        val ids = SyncItemCatalog.allItems.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val names = SyncItemCatalog.allItems.flatMap { it.names }
        assertEquals(names.size, names.toSet().size)
        assertEquals("categoryColors", SyncItemCatalog.itemForName("categoryColor.sports")?.id)
        assertEquals("groupOrder", SyncItemCatalog.itemForName("defaultGroup")?.id)
    }
}
