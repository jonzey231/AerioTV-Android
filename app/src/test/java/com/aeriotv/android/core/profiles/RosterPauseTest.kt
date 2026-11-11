package com.aeriotv.android.core.profiles

import com.aeriotv.android.core.sync.dsync.DsyncKey
import com.aeriotv.android.core.sync.dsync.DsyncV2
import com.aeriotv.android.core.sync.dsync.ProfileRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dispatcharr Sync's `profiles` category pauses for a playlist while any
 * profile on it is held after a server change (item 4, B13, 2026-11-11).
 */
class RosterPauseTest {

    private class MemoryBacking(var value: String? = null) : HostHoldBook.Backing {
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }

    private val admin = ProfileHostHold.Identity(7, "admin")
    private val home = ProfileHostHold.routes("https://tv.example.test", null)
    private val kidRow = ProfileRow(
        12, "kid", "Sam", "numbers", "a".repeat(64), "b".repeat(32),
        role = "child", updatedAt = 1_794_000_000L, avatar = AvatarSpec("rocket", "orange"), order = 2,
    )

    private fun category(data: JsonObject) = JsonObject(mapOf("updatedAt" to JsonPrimitive("2026-11-11T18:00:00Z"), "deviceId" to JsonPrimitive("d"), "data" to data))

    private fun bucket(vararg cats: Pair<String, JsonObject>) = JsonObject(
        mapOf(
            "schemaVersion" to JsonPrimitive(DsyncV2.SCHEMA),
            "deviceId" to JsonPrimitive("server-side"),
            "platform" to JsonPrimitive("apple"),
            "appVersion" to JsonPrimitive("1.8.45"),
            "updatedAt" to JsonPrimitive("2026-11-11T18:00:00Z"),
            DsyncV2.CATEGORIES to JsonObject(mapOf(*cats)),
        ),
    )

    // ---- Only `profiles` pauses ----

    @Test fun profilesPausesInBothDirectionsWhileHeld() {
        assertFalse(RosterPause.syncs(DsyncKey.Profiles, enabled = true, paused = true))
        assertTrue(RosterPause.syncs(DsyncKey.Profiles, enabled = true, paused = false))
        assertFalse(RosterPause.syncs(DsyncKey.Profiles, enabled = false, paused = false))
    }

    @Test fun otherCategoriesStillSyncWhilePaused() {
        for (key in DsyncKey.entries.filter { it != DsyncKey.Profiles }) {
            assertTrue(key.suffix, RosterPause.syncs(key, enabled = true, paused = true))
        }
    }

    // ---- Push: the server's value, never local rows ----

    @Test fun aPutWhilePausedCarriesTheServersProfilesExactly() {
        val theirs = category(JsonObject(mapOf("rows" to JsonArray(listOf(JsonPrimitive("server-row"))))))
        val server = bucket("profiles" to theirs)
        // A stale copy of the playlist's previous server, with this device's roster in it.
        val mine = category(JsonObject(mapOf("rows" to JsonArray(listOf(JsonPrimitive("local-row-with-pin-hash"))))))
        val favorites = category(JsonObject(mapOf("ids" to JsonArray(emptyList()))))
        val value = DsyncV2.encodeBucket(bucket("profiles" to mine), "this-device", "0.5.14", 1_794_000_000L, mapOf(DsyncKey.Favorites to favorites))
        val out = RosterPause.withServerProfiles(value, server)
        val cats = out[DsyncV2.CATEGORIES] as JsonObject
        assertEquals(theirs, cats["profiles"])
        assertEquals(favorites, cats["favorites"])
    }

    @Test fun aPutWhilePausedLeavesProfilesOutWhenTheServerHasNone() {
        val stale = category(JsonObject(mapOf("rows" to JsonArray(listOf(JsonPrimitive("old-server-row"))))))
        val value = DsyncV2.encodeBucket(bucket("profiles" to stale), "this-device", "0.5.14", 1_794_000_000L, emptyMap())
        assertNull((RosterPause.withServerProfiles(value, null)[DsyncV2.CATEGORIES] as JsonObject)["profiles"])
        assertNull((RosterPause.withServerProfiles(value, bucket())[DsyncV2.CATEGORIES] as JsonObject)["profiles"])
    }

    /**
     * The pending (dirty) mark on `profiles` is cleared only by a PUT of
     * `profiles` itself, and that key is never written while paused: a PUT of
     * another category leaves it set, so the edits go out after the pause.
     */
    @Test fun aPutDuringThePauseLeavesTheRosterPending() {
        assertFalse(RosterPause.syncs(DsyncKey.Profiles, enabled = true, paused = true))
        assertTrue(RosterPause.syncs(DsyncKey.Favorites, enabled = true, paused = true))
        assertTrue(RosterPause.syncs(DsyncKey.Profiles, enabled = true, paused = false))
    }

    // ---- Pull: no rows, tombstones, or suggestions while paused ----

    @Test fun aPulledTombstoneOrRowCannotTouchAHeldProfile() {
        // The category is neither pulled nor merged, so its rows and its `removed` list never apply.
        assertFalse(RosterPause.syncs(DsyncKey.Profiles, enabled = true, paused = true))
        assertTrue(RosterPause.visibleRows(listOf(kidRow), paused = true).isEmpty())
        assertTrue(ProfileRoster.suggestions(RosterPause.visibleRows(listOf(kidRow), paused = true), listOf("admin")).isEmpty())
    }

    @Test fun afterTheHoldClearsTheRosterMergesAsToday() {
        assertEquals(listOf(kidRow), RosterPause.visibleRows(listOf(kidRow), paused = false))
        // Today's rule: a newer row applies, an older one does not.
        assertTrue(RosterPause.rowApplies(addedDuringPause = false, localAt = kidRow.updatedAt - 1, row = kidRow))
        assertFalse(RosterPause.rowApplies(addedDuringPause = false, localAt = kidRow.updatedAt + 1, row = kidRow))
    }

    // ---- Removal and adds during the pause ----

    @Test fun removingAHeldProfileWritesNoTombstone() {
        assertFalse(RosterPause.writesTombstone(requested = true, held = true))
        assertTrue(RosterPause.writesTombstone(requested = true, held = false))
        assertFalse(RosterPause.writesTombstone(requested = false, held = false))
    }

    @Test fun aRosterUserAddedDuringThePauseTakesItsRowAfterThePauseEnds() {
        val book = HostHoldBook(MemoryBacking())
        book.record("P1", admin)
        book.hold("P1", setOf("B"), home)
        assertTrue(book.isPaused("P1"))
        // During the pause a manual add sees no roster rows: a plain add, stamped now, and marked.
        assertNull(ProfileRoster.rowForManualAdd(RosterPause.visibleRows(listOf(kidRow), book.isPaused("P1")), listOf("admin"), "kid"))
        book.markPausedAdd("K")
        val addedAt = kidRow.updatedAt + 3_600
        // The pause ends.
        book.liftProfile("B")
        assertFalse(book.isPaused("P1"))
        // The next pull: its row applies although the local stamp is newer, the item 1 way.
        assertTrue(book.isPausedAdd("K"))
        assertTrue(RosterPause.rowApplies(addedDuringPause = true, localAt = addedAt, row = kidRow))
        val local = DispatcharrProfile(id = "K", playlistId = "P1", displayName = "kid", username = "kid", userLevel = 1, apiKey = "typed-key")
        val after = ProfileRoster.applyRow(local, kidRow)
        assertEquals(ProfileRole.Child, after.effectiveRole())
        assertEquals("Sam", after.displayName)
        assertEquals("typed-key", after.apiKey)
        // Then the mark goes, and the stamp adopted is the row's.
        book.clearPausedAdd("K")
        assertFalse(book.isPausedAdd("K"))
    }

    @Test fun thePausedAddMarkSurvivesARelaunch() {
        val backing = MemoryBacking()
        HostHoldBook(backing).markPausedAdd("K")
        assertTrue(HostHoldBook(backing).isPausedAdd("K"))
    }
}
