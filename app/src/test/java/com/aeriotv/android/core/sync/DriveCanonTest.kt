package com.aeriotv.android.core.sync

import com.aeriotv.android.core.sync.dsync.DsyncCoexistence
import com.aeriotv.android.core.sync.dsync.DsyncKey
import com.aeriotv.android.core.sync.dsync.DsyncPayloads
import com.aeriotv.android.core.sync.dsync.ProgressRow
import com.aeriotv.android.core.sync.dsync.RecentRow
import com.aeriotv.android.core.sync.dsync.VodMarkRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveCanonTest {
    private val env = SyncEnvelope(lastModified = 1_000, device = "t")
    private val now = 2_000_000_000_000L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun fav(id: String, server: Int? = null) = FavoriteSnapshotEntry(id, "Ch $id", 0, 0, server)
    private fun prog(id: String, type: String = "movie", server: Int? = null, sid: String? = null) =
        WatchProgressSnapshotEntry(id, "T $id", null, 10, 100, now - 1_000, type, sid, dispatcharrVodId = server)

    @Test fun olderFilesDecodeWithoutNewFields() {
        val f = json.decodeFromString<FavoritesSnapshot>(
            """{"envelope":{"lastModified":1,"device":"d"},"entries":[{"channelId":"a","channelName":"A","displayOrder":0,"addedAt":0}]}""",
        )
        assertNull(f.entries[0].dispatcharrChannelId)
        val p = json.decodeFromString<PreferencesSnapshot>("""{"envelope":{"lastModified":1,"device":"d"},"keys":{}}""")
        assertNull(p.dispatcharrChannelIds)
        val w = json.decodeFromString<WatchlistSnapshot>(
            """{"envelope":{"lastModified":1,"device":"d"},"entries":[{"vodId":"u","title":"x","addedAt":1}],"hidden":[{"vodId":"9","vodType":"series","hiddenAt":1}]}""",
        )
        assertNull(w.entries[0].dispatcharrVodId)
        assertNull(w.hidden[0].dispatcharrVodId)
    }

    @Test fun enrichFavoritesFillsResolvableOnly() {
        val s = FavoritesSnapshot(env, listOf(fav("a"), fav("b"), fav("c", server = 7)))
        val (out, n) = DriveCanon.enrichFavorites(s, DriveCanon.Ids(channels = mapOf("a" to 1, "c" to 99)))
        assertEquals(1, n)
        assertEquals(listOf(1, null, 7), out.entries.map { it.dispatcharrChannelId })
        assertEquals(env, out.envelope)
    }

    @Test fun enrichIsIdempotent() {
        val s = FavoritesSnapshot(env, listOf(fav("a")))
        val ids = DriveCanon.Ids(channels = mapOf("a" to 1))
        val once = DriveCanon.enrichFavorites(s, ids).first
        assertEquals(0, DriveCanon.enrichFavorites(once, ids).second)
    }

    @Test fun enrichProgressMoviesAndEpisodesSkipsRecordings() {
        val s = WatchProgressSnapshot(env, listOf(prog("m1"), prog("e1", "episode", sid = "5"), prog("dvr-3", "recording")))
        val (out, n) = DriveCanon.enrichProgress(s, DriveCanon.Ids(movies = mapOf("m1" to 11), episodes = mapOf("e1" to 22)))
        assertEquals(2, n)
        assertEquals(listOf(11, 22, null), out.entries.map { it.dispatcharrVodId })
        val (movies, eps) = DriveCanon.progressNeeds(s)
        assertEquals(setOf("m1"), movies)
        assertEquals(mapOf("e1" to 5), eps)
    }

    @Test fun enrichWatchlistSeriesFromIdMoviesFromLookup() {
        val s = WatchlistSnapshot(
            env,
            listOf(WatchlistSnapshotEntry("u1", "movie", "A", addedAt = 1), WatchlistSnapshotEntry("42", "series", "B", addedAt = 1)),
            listOf(HiddenTitleSnapshotEntry("u2", "movie", hiddenAt = 1)),
        )
        val (out, w, h) = DriveCanon.enrichWatchlist(s, DriveCanon.Ids(movies = mapOf("u1" to 3)))
        assertEquals(2, w)
        assertEquals(0, h)
        assertEquals(listOf(3, 42), out.entries.map { it.dispatcharrVodId })
        assertEquals(setOf("u1", "u2"), DriveCanon.watchlistMovieNeeds(s))
    }

    @Test fun enrichPreferencesMapsRecentsAndCollectionMembers() {
        val p = PreferencesSnapshot(
            env,
            mapOf(
                DriveCanon.K_RECENTS to "a\nb",
                DriveCanon.K_COLLECTIONS to """[{"id":"c1","name":"News","memberIds":["b","z"],"placement":"end"}]""",
            ),
        )
        assertEquals(setOf("a", "b", "z"), DriveCanon.referencedChannels(p))
        val (out, n) = DriveCanon.enrichPreferences(p, DriveCanon.Ids(channels = mapOf("a" to 1, "b" to 2)))
        assertEquals(2, n)
        assertEquals(mapOf("a" to 1, "b" to 2), out.dispatcharrChannelIds)
        // z did not resolve: collections stay unsure, recents are provable.
        assertNull(DriveCanon.collectionsCategory(out, DriveCanon.Ids()).fingerprint)
        assertEquals(
            DriveCanon.dsyncRecents(listOf(RecentRow(2, 5), RecentRow(1, 9))),
            DriveCanon.recentsCategory(out, DriveCanon.Ids()).fingerprint,
        )
    }

    @Test fun favoritesMatchDispatcharrWireThroughMigratedOrResolvedIds() {
        val wire = JsonObject(mapOf("ids" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(1)))))
        val dsyncFp = DsyncCoexistence.fingerprint(DsyncKey.Favorites, wire)
        val migrated = FavoritesSnapshot(env, listOf(fav("a", 1), fav("b", 2)))
        assertEquals(dsyncFp, DriveCanon.favorites(migrated, DriveCanon.Ids()).fingerprint)
        val legacy = FavoritesSnapshot(env, listOf(fav("a"), fav("b")))
        assertEquals(dsyncFp, DriveCanon.favorites(legacy, DriveCanon.Ids(channels = mapOf("a" to 1, "b" to 2))).fingerprint)
        // An unresolved row keeps the prompt.
        assertNull(DriveCanon.favorites(legacy, DriveCanon.Ids(channels = mapOf("a" to 1))).fingerprint)
    }

    @Test fun progressMatchesWireAndAppliesTheCap() {
        val old = WatchProgressSnapshotEntry("m9", "Old", null, 1, 1, now - 40L * 86_400_000, "movie", isFinished = true, dispatcharrVodId = 9)
        val s = WatchProgressSnapshot(env, listOf(prog("m1", server = 11), prog("e1", "episode", server = 22), old))
        val c = DriveCanon.progress(s, DriveCanon.Ids(), now)
        assertEquals(2, c.rows)
        val rows = listOf(
            ProgressRow(11, "m", 0, 0, 0, false, null, 0, 0, "x"),
            ProgressRow(22, "e", 0, 0, 0, false, 5, 1, 1, "y"),
        )
        assertEquals(DriveCanon.dsyncProgress(rows), c.fingerprint)
    }

    @Test fun watchlistMatchesWireInScopeOnly() {
        val s = WatchlistSnapshot(
            env,
            listOf(
                WatchlistSnapshotEntry("u1", "movie", "A", serverId = "h", addedAt = 1, dispatcharrVodId = 3),
                WatchlistSnapshotEntry("42", "series", "B", serverId = null, addedAt = 1),
                WatchlistSnapshotEntry("u9", "movie", "C", serverId = "other", addedAt = 1),
                WatchlistSnapshotEntry("u8", "movie", "D", serverId = "h", addedAt = 1, removedAt = 2),
            ),
        )
        val c = DriveCanon.watchlist(s, DriveCanon.Ids(), "h")
        assertEquals(2, c.rows)
        assertEquals(DriveCanon.dsyncMarks(listOf(VodMarkRow(42, "s", 1), VodMarkRow(3, "m", 1))), c.fingerprint)
    }

    @Test fun groupsAndOrderMatchWire() {
        val p = PreferencesSnapshot(
            env,
            mapOf(DriveCanon.K_HIDDEN_LIVE to "Sports\nNews", DriveCanon.K_ORDER to "A\nB", DriveCanon.K_SORT_MODE to "Manual"),
        )
        val hg = DriveCanon.hiddenGroupsCategory(p)
        assertEquals(2, hg.rows)
        assertEquals(DriveCanon.dsyncHiddenGroups(DsyncPayloads.HiddenGroups(listOf("News", "Sports"), emptyList(), emptyList())), hg.fingerprint)
        val go = DriveCanon.groupOrderCategory(p)
        assertEquals(2, go.rows)
        assertEquals(DriveCanon.dsyncGroupOrder(DsyncPayloads.GroupOrder("manual", listOf("A", "B"))), go.fingerprint)
        assertTrue(go.fingerprint != DriveCanon.dsyncGroupOrder(DsyncPayloads.GroupOrder("manual", listOf("B", "A"))))
    }

    @Test fun settingsMatchWireIgnoringBlanks() {
        val drive = DriveCanon.settingsCategory(mapOf("v" to mapOf("selectedTheme" to "Ocean", "defaultGroup" to "")), 5)
        val (rows, f) = DriveCanon.dsyncSettings(mapOf("v" to mapOf("selectedTheme" to JsonPrimitive("Ocean"), "defaultGroup" to JsonPrimitive(""))))
        assertEquals(1, drive.rows)
        assertEquals(rows, drive.rows)
        assertEquals(f, drive.fingerprint)
    }

    @Test fun remindersFutureOnly() {
        val r = RemindersSnapshot(
            env,
            listOf(ReminderSnapshotEntry("k1", "ESPN", "G", now + 1_000, now + 5_000), ReminderSnapshotEntry("k2", "CNN", "N", now - 9_000, now - 5_000)),
        )
        val c = DriveCanon.reminders(r, now)
        assertEquals(1, c.rows)
        assertNotNull(c.fingerprint)
    }

    @Test fun migrationLineCarriesCountsOnly() {
        assertEquals("[DRIVE] id migration: favorites 3 rows enriched", DriveCanon.migrationLine("favorites", 3))
    }

    @Test fun detectsOldFormatBackup() {
        assertTrue(DriveCanon.backupOutdated(listOf(2, null)))
        assertTrue(DriveCanon.backupOutdated(listOf(1)))
        assertTrue(!DriveCanon.backupOutdated(listOf(2, 2)))
        assertTrue(!DriveCanon.backupOutdated(emptyList()))
        assertTrue(!DriveCanon.backupOutdated(listOf(3)))
    }

    @Test fun envelopeFormatDecodesAndDefaults() {
        val old = json.decodeFromString<FavoritesSnapshot>("""{"envelope":{"lastModified":1,"device":"d"},"entries":[]}""")
        assertNull(old.envelope.format)
        assertEquals(1, old.envelope.version)
        val v2 = json.decodeFromString<FavoritesSnapshot>(json.encodeToString(old.copy(envelope = old.envelope.copy(format = DriveCanon.FORMAT))))
        assertEquals(2, v2.envelope.format)
    }

    @Test fun pendingCategoriesAfterReadiness() {
        val all = listOf("favorites", "progress", "watchlist")
        val done = mutableSetOf("favorites")
        assertEquals(emptyList<String>(), DriveCanon.pendingCategories(false, all, all.toSet()) { it in done })
        assertEquals(listOf("progress", "watchlist"), DriveCanon.pendingCategories(true, all, all.toSet()) { it in done })
        assertEquals(listOf("progress"), DriveCanon.pendingCategories(true, all, setOf("favorites", "progress")) { it in done })
        done += listOf("progress", "watchlist")
        assertEquals(emptyList<String>(), DriveCanon.pendingCategories(true, all, all.toSet()) { it in done })
    }
}
