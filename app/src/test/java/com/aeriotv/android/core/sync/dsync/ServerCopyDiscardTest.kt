package com.aeriotv.android.core.sync.dsync

import com.aeriotv.android.core.profiles.ProfileHostHold
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A server address change drops Dispatcharr Sync's saved copy of the old
 * server's record for every profile of the playlist (item 6, 2026-11-11), so
 * the first PUT at the new address carries nothing taken from it.
 */
class ServerCopyDiscardTest {

    private fun category(tag: String) = JsonObject(
        mapOf("updatedAt" to JsonPrimitive("2026-11-11T18:00:00Z"), "deviceId" to JsonPrimitive("d"), "data" to JsonObject(mapOf("rows" to JsonArray(listOf(JsonPrimitive(tag)))))),
    )

    private fun bucket(vararg cats: Pair<String, JsonObject>) = JsonObject(
        mapOf(
            "schemaVersion" to JsonPrimitive(DsyncV2.SCHEMA),
            "deviceId" to JsonPrimitive("other"),
            "platform" to JsonPrimitive("apple"),
            "appVersion" to JsonPrimitive("1.8.45"),
            "updatedAt" to JsonPrimitive("2026-11-11T18:00:00Z"),
            DsyncV2.CATEGORIES to JsonObject(mapOf(*cats)),
        ),
    )

    private val saved = listOf(
        // P1's server copy, two profiles.
        "braw|P1|default|Main", "raw|P1|default|favorites", "data|P1|default|favorites", "at|P1|default|favorites", "dev|P1|default|favorites",
        "braw|P1|kid-id|Main", "raw|P1|kid-id|recents",
        // P1's local marks and local data stamps: kept.
        "dirty|P1|default|favorites", "dirtyAt|P1|default|favorites", "fpdone|P1|default", "last|P1|default", "rstamp|P1|default", "rhead|P1|default",
        "prow|P1|kid", "pdirty|P1",
        // Another playlist: kept.
        "braw|P2|default|Main", "raw|P2|default|favorites",
    )

    @Test fun aRouteChangeDropsTheServerCopyForEveryProfileOfThatPlaylistOnly() {
        val doomed = DsyncServerCopy.keys(saved, "P1").toSet()
        assertEquals(
            setOf(
                "braw|P1|default|Main", "raw|P1|default|favorites", "data|P1|default|favorites", "at|P1|default|favorites", "dev|P1|default|favorites",
                "braw|P1|kid-id|Main", "raw|P1|kid-id|recents",
            ),
            doomed,
        )
        assertTrue(doomed.none { it.startsWith("dirty") || it.startsWith("fpdone") || it.startsWith("prow") || "|P2|" in it })
    }

    @Test fun afterTheDiscardAPutToAnEmptyServerCarriesNothingFromTheOldCopy() {
        // Before: the old server's copy would have carried its recents and settings along.
        val old = bucket("recents" to category("old-server-recents"), "settings" to category("old-server-settings"))
        val withStale = DsyncV2.encodeBucket(old, "this-device", "0.5.14", 1_794_000_000L, mapOf(DsyncKey.Favorites to category("mine")))
        assertEquals(setOf("recents", "settings", "favorites"), (withStale[DsyncV2.CATEGORIES] as JsonObject).keys)
        // After: the copy is gone and the server answered 404, so only this device's own category goes.
        val fresh = DsyncV2.encodeBucket(null, "this-device", "0.5.14", 1_794_000_000L, mapOf(DsyncKey.Favorites to category("mine")))
        assertEquals(setOf("favorites"), (fresh[DsyncV2.CATEGORIES] as JsonObject).keys)
        // And with no saved category envelope, the category carries only this device's data.
        val sub = DsyncV2.encodeCategory(null, "this-device", 1_794_000_000L, JsonObject(emptyMap()))
        assertEquals(setOf("updatedAt", "deviceId", "data"), sub.keys)
    }

    @Test fun aPathOnlyChangeKeepsTheCopy() {
        // Not a route change, so nothing is dropped.
        assertFalse(ProfileHostHold.routeChanged("https://tv.example.test/a", "https://tv.example.test/b", null, null))
        assertTrue(ProfileHostHold.routeChanged("https://tv.example.test", "https://other.example.test", null, null))
    }

    @Test fun aReadAtTheNewAddressRebuildsTheCopy() {
        // The read saves what the new server has, and the PUT starts from it.
        val theirs = bucket("recents" to category("new-server-recents"))
        val put = DsyncV2.encodeBucket(theirs, "this-device", "0.5.14", 1_794_000_000L, mapOf(DsyncKey.Favorites to category("mine")))
        val cats = put[DsyncV2.CATEGORIES] as JsonObject
        assertEquals(category("new-server-recents"), cats["recents"])
        assertEquals(category("mine"), cats["favorites"])
    }
}
