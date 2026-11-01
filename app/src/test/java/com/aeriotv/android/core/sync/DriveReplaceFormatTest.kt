package com.aeriotv.android.core.sync

import com.aeriotv.android.core.sync.dsync.DsyncCoexistence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rule 4 replace on the Google Drive choice and the format 2 writer (docs/DispatcharrSyncSchema.md 10a). */
class DriveReplaceFormatTest {
    private val now = 2_000_000_000_000L
    private val day = 86_400_000L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val env = DriveCanon.writerEnvelope(now, "t", null)

    private fun prog(id: String, type: String = "movie", at: Long = now - 1_000, finished: Boolean = false) =
        WatchProgressSnapshotEntry(id, "T", null, 10, 100, at, type, isFinished = finished)

    @Test fun everySharedCategoryReplacesOnTheForcedApply() {
        DsyncCoexistence.SHARED_CATEGORIES.forEach { assertTrue(it.name, DsyncCoexistence.replaceOnForcedApply(it)) }
        assertTrue(!DsyncCoexistence.replaceOnForcedApply(SyncCategory.Playlists))
        assertTrue(!DsyncCoexistence.replaceOnForcedApply(SyncCategory.Credentials))
    }

    @Test fun progressRemovesLocalOnlyRowsAndKeepsFilteredOnes() {
        val backup = WatchProgressSnapshot(env, listOf(prog("a")))
        val local = listOf(
            prog("a"), prog("localOnly"),
            prog("http://host/x.ts"), // URL-keyed: never synced
            prog("dvr-1", type = "recording"), // not a movie or episode row
            prog("oldDone", at = now - 40 * day, finished = true), // finished over 30 days ago
        )
        assertEquals(listOf("localOnly"), DriveCanon.progressReplaceDeletes(local, backup, now))
        // Beyond the 300 newest: not counted, so kept.
        val many = (0 until 305).map { prog("p$it", at = now - it * 1_000L) }
        val gone = DriveCanon.progressReplaceDeletes(many, WatchProgressSnapshot(env, emptyList()), now)
        assertEquals(300, gone.size)
        assertTrue("p304" !in gone)
    }

    @Test fun remindersRemoveFutureLocalOnlyAndKeepPast() {
        val b = RemindersSnapshot(env, listOf(ReminderSnapshotEntry("k1", "A", "x", now + 10_000, now + 20_000)))
        val local = listOf(
            ReminderSnapshotEntry("k1", "A", "x", now + 10_000, now + 20_000),
            ReminderSnapshotEntry("other-key", "A", "x", now + 10_000, now + 20_000), // same channel and start
            ReminderSnapshotEntry("k2", "B", "y", now + 10_000, now + 20_000),
            ReminderSnapshotEntry("past", "C", "z", now - 20_000, now - 10_000),
        )
        assertEquals(listOf("k2"), DriveCanon.reminderReplaceDeletes(local, b, now))
    }

    @Test fun favoritesRemoveLocalOnly() {
        val b = FavoritesSnapshot(env, listOf(FavoriteSnapshotEntry("1", "One", 0, 0)))
        assertEquals(listOf("2"), DriveCanon.favoriteReplaceDeletes(listOf("1", "2"), b))
        assertEquals(listOf("1", "2"), DriveCanon.favoriteReplaceDeletes(listOf("1", "2"), FavoritesSnapshot(env, emptyList())))
    }

    private data class M(val key: String, val scope: String?, val removed: Long? = null)

    @Test fun marksTombstoneInScopeLocalOnlyAndKeepOtherScopes() {
        val local = listOf(M("m:a", "h"), M("m:local", "h"), M("m:other", "elsewhere"), M("s:1", null), M("m:dead", "h", 5))
        val remote = listOf(M("m:a", "h", 9), M("m:new", "h"))
        val out = DriveCanon.replaceMarks(
            local, remote, { it.key to it.scope }, { it.removed == null },
            { it.scope == null || it.scope == "h" }, { it.copy(removed = 1) },
        )
        assertEquals(
            listOf(M("m:a", "h", 9), M("m:local", "h", 1), M("m:other", "elsewhere"), M("s:1", null, 1), M("m:dead", "h", 5), M("m:new", "h")),
            out,
        )
    }

    private fun fmt(s: String) = json.parseToJsonElement(s).jsonObject["envelope"]!!.jsonObject["format"]?.jsonPrimitive?.int

    @Test fun everySharedCategoryWriteCarriesFormat2() {
        val bodies = mapOf(
            SyncCategory.Favorites to json.encodeToString(FavoritesSnapshot.serializer(), FavoritesSnapshot(env, emptyList())),
            SyncCategory.WatchProgress to json.encodeToString(WatchProgressSnapshot.serializer(), WatchProgressSnapshot(env, emptyList())),
            SyncCategory.Reminders to json.encodeToString(RemindersSnapshot.serializer(), RemindersSnapshot(env, emptyList())),
            SyncCategory.Watchlist to json.encodeToString(WatchlistSnapshot.serializer(), WatchlistSnapshot(env, emptyList())),
            SyncCategory.Preferences to json.encodeToString(PreferencesSnapshot.serializer(), PreferencesSnapshot(envelope = env, keys = emptyMap())),
        )
        assertEquals(DsyncCoexistence.SHARED_CATEGORIES, bodies.keys)
        bodies.forEach { (c, b) -> assertEquals(c.name, DriveCanon.FORMAT, fmt(b)) }
    }

    @Test fun formatStampKeepsTheBodyAndCatchesUndecodableFiles() {
        val old = """{"envelope":{"lastModified":7,"device":"d"},"entries":[{"unknown":1}],"extra":"x"}"""
        val stamped = DriveCanon.stampFormat(old)!!
        assertEquals(2, fmt(stamped))
        val o = json.parseToJsonElement(stamped).jsonObject
        assertEquals("x", o["extra"]!!.jsonPrimitive.content)
        assertEquals(7, o["envelope"]!!.jsonObject["lastModified"]!!.jsonPrimitive.int)
        assertNull(DriveCanon.stampFormat("""{"entries":[]}"""))
        assertEquals(listOf("Reminders"), DriveCanon.oldFormatNames(mapOf("Favorites" to 2, "Reminders" to null)))
    }
}
