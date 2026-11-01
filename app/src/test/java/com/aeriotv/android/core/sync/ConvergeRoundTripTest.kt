package com.aeriotv.android.core.sync

import com.aeriotv.android.core.data.ChannelCollection
import com.aeriotv.android.core.sync.dsync.CollectionRow
import com.aeriotv.android.core.sync.dsync.DsyncCoexistence
import com.aeriotv.android.core.sync.dsync.DsyncKey
import com.aeriotv.android.core.sync.dsync.DsyncPayloads
import com.aeriotv.android.core.sync.dsync.DsyncV2
import com.aeriotv.android.core.sync.dsync.ProgressRow
import com.aeriotv.android.core.sync.dsync.RecentRow
import com.aeriotv.android.core.sync.dsync.ReminderRow
import com.aeriotv.android.core.sync.dsync.VodMarkRow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule 5 (docs/DispatcharrSyncSchema.md 10a): an equal data set fingerprints
 * equal after a round trip through each provider's real encoder (Drive
 * snapshot JSON, Dispatcharr bucket), for every shared category including
 * empty ones and the settings lanes; and the converge plan pushes once, then
 * never again for the same Dispatcharr state.
 */
class ConvergeRoundTripTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }
    private val now = 2_000_000_000_000L
    private val nowS = now / 1000
    private val env = DriveCanon.writerEnvelope(now - 5_000, "t", null)

    private inline fun <reified T> rt(v: T): T = json.decodeFromString(json.encodeToString(v))

    /** Dispatcharr side: encode a bucket, decode it, summarize (the converge input). */
    private fun dsyncSummary(data: Map<DsyncKey, JsonObject>, scopes: Map<String, SyncScope> = emptyMap()): SetupSourceChoice.Summary {
        val cats = data.mapValues { (_, d) -> DsyncV2.encodeCategory(null, "dev", nowS - 5, d) }
        val raw = DsyncV2.encodeBucket(null, "dev", "1", nowS - 5, cats)
        val back = json.parseToJsonElement(raw.toString()) as JsonObject
        val bucket = (DsyncV2.decode(back) as DsyncV2.Decode.Ok).bucket
        return DsyncCoexistence.summarize(bucket, scopes)
    }

    // Local channel ids "a".."c" map to Dispatcharr 1..3; movie uuids to 11..; series id is its own id.
    private val ids = DriveCanon.Ids(channels = mapOf("a" to 1, "b" to 2, "c" to 3), movies = mapOf("u1" to 11, "u2" to 12), episodes = mapOf("e1" to 21))

    /**
     * [strays] adds local rows with no Dispatcharr id (a channel of another
     * playlist, "z"): they can never live on Dispatcharr, so neither side
     * counts them (canonical-id rows only, section 10a).
     */
    private fun driveSummary(full: Boolean, scopes: Map<String, SyncScope> = emptyMap(), driveLanes: Map<String, Map<String, JsonPrimitive>>, strays: Boolean = false): SetupSourceChoice.Summary {
        val fav = rt(DriveCanon.enrichFavorites(FavoritesSnapshot(env, if (full) listOf(
            FavoriteSnapshotEntry("a", "A", 0, 0), FavoriteSnapshotEntry("b", "B", 1, 0),
        ) + (if (strays) listOf(FavoriteSnapshotEntry("z", "Z", 2, 0)) else emptyList()) else emptyList()), ids).first)
        val prog = rt(DriveCanon.enrichProgress(WatchProgressSnapshot(env, if (full) listOf(
            WatchProgressSnapshotEntry("u1", "M", null, 10, 100, now - 1_000, "movie"),
            WatchProgressSnapshotEntry("e1", "E", null, 10, 100, now - 2_000, "episode", "7"),
        ) else emptyList()), ids).first)
        val rem = rt(RemindersSnapshot(env, if (full) listOf(ReminderSnapshotEntry("k", "News", "T", now + 60_000, now + 120_000)) else emptyList()))
        val wl = rt(DriveCanon.enrichWatchlist(WatchlistSnapshot(env,
            if (full) listOf(WatchlistSnapshotEntry("u1", "movie", "A", addedAt = 1), WatchlistSnapshotEntry("42", "series", "S", addedAt = 1)) else emptyList(),
            if (full) listOf(HiddenTitleSnapshotEntry("u2", "movie", hiddenAt = 1)) else emptyList(),
        ), ids).first)
        val keys = if (full) mapOf(
            DriveCanon.K_RECENTS to if (strays) "a\nz\nc\na" else "a\nc",
            DriveCanon.K_COLLECTIONS to json.encodeToString(listOf(ChannelCollection("c1", "Sports", listOf("a", "b"), ChannelCollection.PLACEMENT_BEGINNING))),
            DriveCanon.K_HIDDEN_LIVE to "Adult", DriveCanon.K_HIDDEN_MOVIES to "", DriveCanon.K_HIDDEN_SERIES to "Kids",
            DriveCanon.K_ORDER to "News\nSports", DriveCanon.K_SORT_MODE to "Manual",
        ) else emptyMap()
        val prefs = rt(DriveCanon.enrichPreferences(PreferencesSnapshot(env, keys), ids).first)
        val out = linkedMapOf(
            "favorites" to DriveCanon.favorites(fav, DriveCanon.Ids()),
            "progress" to DriveCanon.progress(prog, DriveCanon.Ids(), now),
            "reminders" to DriveCanon.reminders(rem, now),
            "watchlist" to DriveCanon.watchlist(wl, DriveCanon.Ids(), null),
            "hiddenTitles" to DriveCanon.hiddenTitles(wl, DriveCanon.Ids(), null),
            "recents" to DriveCanon.recentsCategory(prefs, DriveCanon.Ids()),
            "collections" to DriveCanon.collectionsCategory(prefs, DriveCanon.Ids()),
            "hiddenGroups" to DriveCanon.hiddenGroupsCategory(prefs),
            "groupOrder" to DriveCanon.groupOrderCategory(prefs),
        )
        val (n, f) = DriveCanon.dsyncSettings(DriveCanon.lanesOn(driveLanes) { SetupSourceChoice.nameOff(scopes, it) })
        out["settings"] = SetupSourceChoice.Category(n, env.lastModified, f)
        return SetupSourceChoice.forDevice(SetupSourceChoice.Summary(out)) { SetupSourceChoice.itemOff(scopes, it) }
    }

    private fun dsyncData(full: Boolean, lanes: Map<String, Map<String, JsonPrimitive>>): Map<DsyncKey, JsonObject> = mapOf(
        DsyncKey.Favorites to DsyncPayloads.encodeFavorites(null, if (full) listOf(2, 1) else emptyList()),
        DsyncKey.Progress to DsyncPayloads.encodeProgress(null, if (full) listOf(
            ProgressRow(11, "m", 10, 100, nowS - 1, false, null, 0, 0, "M"),
            ProgressRow(21, "e", 10, 100, nowS - 2, false, 7, 1, 1, "E"),
        ) else emptyList(), nowS),
        DsyncKey.Reminders to DsyncPayloads.encodeReminders(null, if (full) listOf(ReminderRow(5, nowS + 60, nowS + 120, "T", "News")) else emptyList(), nowS),
        DsyncKey.Watchlist to DsyncPayloads.encodeVodMarks(null, if (full) listOf(VodMarkRow(42, "s", 1), VodMarkRow(11, "m", 1)) else emptyList(), 500),
        DsyncKey.HiddenTitles to DsyncPayloads.encodeVodMarks(null, if (full) listOf(VodMarkRow(12, "m", 1)) else emptyList(), 500),
        DsyncKey.Recents to DsyncPayloads.encodeRecents(null, if (full) listOf(RecentRow(1, 1), RecentRow(3, 2)) else emptyList()),
        DsyncKey.Collections to DsyncPayloads.encodeCollections(null, if (full) listOf(CollectionRow("c1", "Sports", "b", listOf(2, 1))) else emptyList()),
        DsyncKey.HiddenGroups to DsyncPayloads.encodeHiddenGroups(null, if (full) DsyncPayloads.HiddenGroups(listOf("Adult"), emptyList(), listOf("Kids")) else DsyncPayloads.HiddenGroups(emptyList(), emptyList(), emptyList())),
        DsyncKey.GroupOrder to DsyncPayloads.encodeGroupOrder(null, if (full) DsyncPayloads.GroupOrder("manual", listOf("News", "Sports")) else DsyncPayloads.GroupOrder("default", emptyList())),
        DsyncKey.Settings to DsyncPayloads.encodeSettingsLanes(null, lanes),
    )

    // Drive writes strings and the registry types them (AppPreferences.driveLanesAsWire): "1.0" becomes 1.0, "#ff00aa" stays a string.
    private val driveLanes = mapOf(
        "v" to mapOf("trueBlack" to JsonPrimitive(true), "textScale" to JsonPrimitive(1.0), "customAccentHex" to JsonPrimitive("#ff00aa"), "defaultTab" to JsonPrimitive("guide")),
        "mobile" to mapOf("skipBack" to JsonPrimitive(10)),
    )
    // Another device (or Apple) sends 1 for 1.0 and uppercase hex without "#".
    private val wireLanes = mapOf(
        "v" to mapOf("trueBlack" to JsonPrimitive(true), "textScale" to JsonPrimitive(1), "customAccentHex" to JsonPrimitive("FF00AA"), "defaultTab" to JsonPrimitive("guide")),
        "mobile" to mapOf("skipBack" to JsonPrimitive(10.0)),
    )

    @Test fun equalDataFingerprintsEqualAfterBothEncoders() {
        val d = driveSummary(true, driveLanes = driveLanes)
        val x = dsyncSummary(dsyncData(true, wireLanes))
        SetupSourceChoice.SHARED.forEach { n ->
            assertTrue("$n drive=${d.categories[n]} dsync=${x.categories[n]}", SetupSourceChoice.categoryEqual(d.categories[n], x.categories[n]))
            assertTrue("$n has rows", (d.categories[n]?.rows ?: 0) > 0)
        }
        assertTrue(SetupSourceChoice.equal(d, x))
        assertEquals(SetupSourceChoice.Decision.UseDispatcharr, SetupSourceChoice.decide(d, x))
    }

    /**
     * One row definition per category (field run 2026-11-01: a forced recents
     * push logged rows=1 while the probe read 9): the Dispatcharr push
     * rowCount, the Dispatcharr probe summary, and the Drive summary (which
     * the Drive push line and the converge fingerprint use) agree for all 10
     * shared categories, with stray local rows that have no Dispatcharr id.
     */
    @Test fun pushRowCountEqualsProbeCountForEveryCategory() {
        val data = dsyncData(true, wireLanes)
        val probe = dsyncSummary(data)
        val drive = driveSummary(true, driveLanes = driveLanes, strays = true)
        assertEquals(SetupSourceChoice.SHARED.toSet(), DsyncCoexistence.FORCE_KEYS.map { it.suffix }.toSet())
        DsyncCoexistence.FORCE_KEYS.forEach { key ->
            val n = key.suffix
            val push = DsyncCoexistence.rowCount(key, data.getValue(key))
            assertEquals("$n push vs probe", push, probe.rows(n))
            assertEquals("$n push vs drive", push, drive.rows(n))
            assertTrue("$n has rows", push > 0)
            assertTrue("$n equal", SetupSourceChoice.categoryEqual(drive.categories[n], probe.categories[n]))
        }
        // The 50 cap and dedupe are the same on both sides.
        val many = (1..60).map { RecentRow(it, it.toLong()) } + RecentRow(60, 1)
        val enc = DsyncPayloads.encodeRecents(null, many)
        val lines = (1..60).joinToString("\n") { "c$it" }
        val dr = DriveCanon.recentsCategory(PreferencesSnapshot(env, mapOf(DriveCanon.K_RECENTS to lines)), DriveCanon.Ids(channels = (1..60).associate { "c$it" to it }))
        assertEquals(50, DsyncCoexistence.rowCount(DsyncKey.Recents, enc))
        assertEquals(50, dr.rows)
    }

    @Test fun emptyCategoriesEqualOnBothSides() {
        val d = driveSummary(false, driveLanes = emptyMap())
        val x = dsyncSummary(dsyncData(false, emptyMap()))
        SetupSourceChoice.SHARED.forEach { n -> assertTrue(n, SetupSourceChoice.categoryEqual(d.categories[n], x.categories[n])) }
        assertTrue(SetupSourceChoice.convergePlan(d, x, emptySet(), emptyMap()).isEmpty())
    }

    @Test fun categoryOffOnThisDeviceIsNeitherComparedNorConverged() {
        val scopes = mapOf("favorites" to SyncScope.Off, "trueBlack" to SyncScope.Off)
        val d = driveSummary(false, scopes, driveLanes = mapOf("v" to mapOf("trueBlack" to JsonPrimitive(false))))
        val x = dsyncSummary(dsyncData(true, wireLanes).filterKeys { it == DsyncKey.Favorites || it == DsyncKey.Settings }.let {
            it + (DsyncKey.Settings to DsyncPayloads.encodeSettingsLanes(null, mapOf("v" to mapOf("trueBlack" to JsonPrimitive(true)))))
        }, scopes)
        assertTrue("favorites" !in d.categories && "favorites" !in x.categories)
        assertTrue(SetupSourceChoice.categoryEqual(d.categories["settings"], x.categories["settings"]))
        assertTrue(SetupSourceChoice.convergePlan(d, x, emptySet(), emptyMap()).isEmpty())
    }

    @Test fun convergeDiffersPushesOnceThenEqualAndNoSecondPush() {
        val x = dsyncSummary(dsyncData(true, wireLanes))
        var drive = driveSummary(false, driveLanes = emptyMap())
        val pushed = mutableMapOf<String, String>()
        var pushes = 0
        repeat(3) {
            val plan = SetupSourceChoice.convergePlan(drive, x, emptySet(), pushed)
            if (plan.isNotEmpty()) {
                pushes++
                // The Drive push writes the Dispatcharr-derived local copy, read back by the next cycle.
                drive = driveSummary(true, driveLanes = driveLanes)
                plan.forEach { pushed[it] = SetupSourceChoice.convergeMark(x.categories[it]) }
            }
        }
        assertEquals(1, pushes)
        assertTrue(SetupSourceChoice.equal(drive, x))
    }

    @Test fun convergeNeverPingPongsWhenOnePushCannotEqualize() {
        val x = dsyncSummary(dsyncData(true, wireLanes))
        val stuck = driveSummary(false, driveLanes = emptyMap())
        val first = SetupSourceChoice.convergePlan(stuck, x, emptySet(), emptyMap())
        assertEquals(SetupSourceChoice.SHARED.toSet(), first.toSet())
        val marks = first.associateWith { SetupSourceChoice.convergeMark(x.categories[it]) }
        assertTrue(SetupSourceChoice.convergePlan(stuck, x, emptySet(), marks).isEmpty())
        // A new Dispatcharr state is converged again.
        val x2 = dsyncSummary(dsyncData(true, wireLanes) + (DsyncKey.Favorites to DsyncPayloads.encodeFavorites(null, listOf(3))))
        assertEquals(listOf("favorites"), SetupSourceChoice.convergePlan(stuck, x2, emptySet(), marks))
    }

    @Test fun pendingLocalChangeIsSkipped() {
        val x = dsyncSummary(dsyncData(true, wireLanes))
        val d = driveSummary(false, driveLanes = emptyMap())
        assertTrue("favorites" !in SetupSourceChoice.convergePlan(d, x, setOf("favorites"), emptyMap()))
    }

    @Test fun canonValueSpellings() {
        assertEquals("1", DriveCanon.canonValue("1.0"))
        assertEquals("1.5", DriveCanon.canonValue("1.5"))
        assertEquals("true", DriveCanon.canonValue("True"))
        assertEquals("FF00AA", DriveCanon.canonValue("#ff00aa"))
        assertEquals("guide", DriveCanon.canonValue("guide"))
    }
}
