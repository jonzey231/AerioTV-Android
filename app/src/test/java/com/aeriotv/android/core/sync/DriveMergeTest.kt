package com.aeriotv.android.core.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Logan's rule (2026-11-10): pre-migration data never overwrites
 * post-migration data. Every Drive push merges per item onto the Drive copy,
 * newest change time wins, a tie keeps Drive, deletions travel as tombstones.
 */
class DriveMergeTest {
    private val env = SyncEnvelope(lastModified = 5_000L, device = "remote", format = 2)

    private fun wp(id: String, at: Long, pos: Long = 10L, vod: Int? = null) =
        WatchProgressSnapshotEntry(videoId = id, title = id, positionMs = pos, durationMs = 100L, updatedAt = at, dispatcharrVodId = vod)

    // ── generic ──

    @Test fun newestWinsAndATieKeepsTheRemote() {
        val out = DriveMerge.byStamp(listOf("a" to 5L, "b" to 5L), listOf("a" to 6L, "b" to 5L, "c" to 1L), { it.first }, { it.second })
        assertEquals(listOf("a" to 6L, "b" to 5L, "c" to 1L), out)
    }

    // ── the 14:05 emulator: a long-offline device with nothing new ──

    @Test fun aStaleDeviceWithNothingNewChangesNothing() {
        val remote = listOf(wp("m1", 9_000L, pos = 90L), wp("m2", 8_000L))
        val staleLocal = listOf(wp("m1", 1_000L, pos = 10L), wp("m2", 1_000L))
        assertEquals(remote, DriveMerge.mergeProgress(remote, staleLocal))
    }

    @Test fun aStaleDeviceOnlyAddsWhatItChangedAfterwards() {
        val remote = listOf(wp("m1", 9_000L, pos = 90L))
        val local = listOf(wp("m1", 1_000L), wp("m3", 9_500L))
        val out = DriveMerge.mergeProgress(remote, local)
        assertEquals(listOf(wp("m1", 9_000L, pos = 90L), wp("m3", 9_500L)), out)
    }

    // ── watch progress tombstones ──

    @Test fun aRemovalNewerThanTheRowWins() {
        val local = DriveMerge.progressLocal(emptyList(), mapOf("m1" to 9_500L))
        val out = DriveMerge.mergeProgress(listOf(wp("m1", 9_000L)), local)
        assertEquals(9_500L, out.single().removedAt)
    }

    @Test fun aRowWatchedAgainAfterTheRemovalWins() {
        val remote = listOf(DriveMerge.progressTombstone("m1", 9_000L))
        val out = DriveMerge.mergeProgress(remote, listOf(wp("m1", 9_100L)))
        assertNull(out.single().removedAt)
        assertEquals(9_100L, out.single().updatedAt)
    }

    @Test fun aLocalWinKeepsTheRemoteCanonicalId() {
        val out = DriveMerge.mergeProgress(listOf(wp("m1", 1_000L, vod = 77)), listOf(wp("m1", 2_000L)))
        assertEquals(77, out.single().dispatcharrVodId)
    }

    @Test fun liveViewsDropTombstones() {
        val s = WatchProgressSnapshot(env, listOf(wp("m1", 1L), DriveMerge.progressTombstone("m2", 2L)))
        assertEquals(listOf("m1"), s.live().entries.map { it.videoId })
    }

    // ── favorites ──

    private fun fav(id: String, order: Long, added: Long, updated: Long? = null) =
        FavoriteSnapshotEntry(channelId = id, channelName = id, displayOrder = order, addedAt = added, updatedAt = updated)

    @Test fun aLocalReorderWinsOverAnOlderRemoteOrder() {
        val local = DriveMerge.favoritesLocal(listOf(fav("c1", 2L, 100L)), touched = mapOf("c1" to 900L), removed = emptyMap())
        val out = DriveMerge.mergeFavorites(listOf(fav("c1", 1L, 100L, updated = 500L)), local)
        assertEquals(2L, out.single().displayOrder)
        assertEquals(900L, out.single().updatedAt)
    }

    @Test fun anOlderFileUsesTheAddTimeNotThePushTime() {
        // Written before change times: the order stamp is addedAt, so the
        // file's (push-time) envelope never makes its order look newer.
        val remote = listOf(fav("c1", 1L, 100L))
        val local = DriveMerge.favoritesLocal(listOf(fav("c1", 2L, 100L)), touched = emptyMap(), removed = emptyMap())
        assertEquals(remote, DriveMerge.mergeFavorites(remote, local))
    }

    @Test fun aRemovedFavoriteStaysRemoved() {
        val local = DriveMerge.favoritesLocal(emptyList(), emptyMap(), removed = mapOf("c1" to 700L))
        val out = DriveMerge.mergeFavorites(listOf(fav("c1", 1L, 100L)), local)
        assertEquals(700L, out.single().removedAt)
    }

    // ── reminders ──

    private fun rem(key: String, end: Long, updated: Long? = null, removed: Long? = null) =
        ReminderSnapshotEntry(reminderKey = key, channelName = "", programTitle = "", startMillis = end - 10, endMillis = end, updatedAt = updated, removedAt = removed)

    @Test fun aCancelledReminderStaysCancelledAndSpentOnesDrop() {
        val now = 10_000_000_000L
        val local = DriveMerge.remindersLocal(emptyList(), emptyMap(), removed = mapOf("r1" to now - 1_000L))
        val remote = listOf(rem("r1", now + 3_600_000L, updated = now - 5_000L), rem("old", now - 2 * 86_400_000L))
        val out = DriveMerge.mergeReminders(remote, local, now)
        assertEquals(listOf("r1"), out.map { it.reminderKey })
        assertEquals(now - 1_000L, out.single().removedAt)
    }

    // ── watchlist ──

    @Test fun watchlistMergesByItsOwnStamps() {
        val r = WatchlistSnapshot(env, listOf(WatchlistSnapshotEntry(vodId = "1", title = "A", addedAt = 100L, removedAt = 900L)))
        val l = WatchlistSnapshot(env, listOf(WatchlistSnapshotEntry(vodId = "1", title = "A", addedAt = 500L)))
        val (entries, _) = DriveMerge.mergeWatchlist(r, l)
        assertEquals(900L, entries.single().removedAt)
    }

    // ── credentials ──

    private fun cred(pid: String, user: String? = null, pass: String? = null, key: String? = null, at: Long? = null) =
        CredentialsSnapshotEntry(playlistId = pid, username = user, password = pass, apiKey = key, updatedAt = at)

    @Test fun aRejectedKeyIsNeverPushed() {
        val out = DriveMerge.mergeCredentials(listOf(cred("p", key = "good", at = 1L)), listOf(cred("p", key = "dead", at = 9L))) { it == "dead" }
        assertEquals("good", out.single().apiKey)
    }

    @Test fun aPlaylistKeepsTheEarliestCreationTimeEitherSideSaw() {
        fun pl(id: String, at: Long?, created: Long?) = PlaylistSnapshotEntry(id = id, name = "n", sourceType = "M3uUrl", urlString = "u", updatedAt = at, createdAt = created)
        // Remote wins on change time; the local copy saw the playlist earlier.
        val out = DriveMerge.mergePlaylists(listOf(pl("p", 9L, 500L)), listOf(pl("p", 1L, 100L)))
        assertEquals(100L, out.single().createdAt)
        assertEquals(9L, out.single().updatedAt)
        // An older file without creation times keeps the one the other side has.
        assertEquals(300L, DriveMerge.mergePlaylists(listOf(pl("p", 9L, null)), listOf(pl("p", 1L, 300L))).single().createdAt)
        assertNull(DriveMerge.mergePlaylists(listOf(pl("p", 9L, null)), emptyList()).single().createdAt)
    }

    @Test fun aRejectedKeyHereGivesWayToTheSyncedKeyWhateverTheTimes() {
        // Neither side has a change time (older builds); this device's key was rejected.
        assertEquals(Triple(null, null, "GOOD"), DriveMerge.credentialsToApply(null, null, "DEAD", localTime = 0L, remote = cred("p", key = "GOOD", at = null)) { it == "DEAD" })
        // Even when this device's change is newer.
        assertEquals("GOOD", DriveMerge.credentialsToApply(null, null, "DEAD", localTime = 9L, remote = cred("p", key = "GOOD", at = 1L)) { it == "DEAD" }!!.third)
        // Never for a synced key that is itself rejected.
        assertNull(DriveMerge.credentialsToApply(null, null, "DEAD", localTime = 0L, remote = cred("p", key = "DEAD2", at = null)) { it.startsWith("DEAD") })
        // A key that was not rejected here keeps the time rule.
        assertNull(DriveMerge.credentialsToApply(null, null, "MINE", localTime = 0L, remote = cred("p", key = "OTHER", at = null)) { false })
    }

    @Test fun aDeletedPlaylistsSignInLeavesDrive() {
        val merged = DriveMerge.mergeCredentials(listOf(cred("gone", key = "k1", at = 1L), cred("kept", key = "k2", at = 1L)), emptyList()) { false }
        assertEquals(listOf("kept"), DriveMerge.dropDeletedPlaylists(merged) { it == "gone" }.map { it.playlistId })
    }

    @Test fun aKeyKnownRejectedHereLeavesDriveAndTheAccountNameTravels() {
        // Drive holds a key this device knows is rejected: the merge drops it whoever wrote it.
        val out = DriveMerge.mergeCredentials(listOf(cred("p", key = "dead", at = 9L)), listOf(cred("p", key = null, at = 1L))) { it == "dead" }
        assertNull(out.single().apiKey)
        // The account name fills in from the side that knows it.
        val acct = DriveMerge.mergeCredentials(
            listOf(cred("p", key = "k", at = 9L)),
            listOf(cred("p", key = "k", at = 1L).copy(account = "logan")),
        ) { false }
        assertEquals("logan", acct.single().account)
    }

    @Test fun aSettingStampedWhenItChangedKeepsThatTimeAtPush() {
        val local = DriveMerge.PrefState(mapOf("theme" to "dark"), emptyMap(), emptyMap(), emptyMap())
        // Changed at 7_000 (stamped then), baseline recorded at 5_000, push at 9_000.
        val t = DriveMerge.localPrefTimes(local, baseline = mapOf("v:theme" to "light"), previous = mapOf("v:theme" to 7_000L), nowMs = 9_000L, baselineAt = 5_000L)
        assertEquals(7_000L, t["v:theme"])
        // A change the stamp missed (older than the baseline) falls back to the push time.
        val f = DriveMerge.localPrefTimes(local, baseline = mapOf("v:theme" to "light"), previous = mapOf("v:theme" to 3_000L), nowMs = 9_000L, baselineAt = 5_000L)
        assertEquals(9_000L, f["v:theme"])
    }

    @Test fun anEmptyPasswordNeverOverwritesASavedOne() {
        val out = DriveMerge.mergeCredentials(listOf(cred("p", "u", "secret", at = 1L)), listOf(cred("p", "u", null, "k", at = 9L))) { false }
        assertEquals("secret", out.single().password)
        assertEquals("k", out.single().apiKey)
    }

    @Test fun credentialsApplyOnlyWhenNewerAndNeverBlankOrRejected() {
        // Older remote: nothing changes.
        assertNull(DriveMerge.credentialsToApply("u", "p", "k", localTime = 5L, remote = cred("x", "u2", "p2", "k2", at = 4L)) { false })
        // Newer remote with an empty password: the password stays.
        val n = DriveMerge.credentialsToApply("u", "p", "k", localTime = 5L, remote = cred("x", "u", "", "k2", at = 6L)) { false }!!
        assertEquals(Triple("u", "p", "k2"), n)
        // Newer remote with a rejected key: the key stays.
        assertNull(DriveMerge.credentialsToApply("u", "p", "k", localTime = 5L, remote = cred("x", "u", "p", "dead", at = 6L)) { it == "dead" })
        // An empty field here is filled even from an older file (fresh device).
        assertEquals(Triple("u", "p", "k"), DriveMerge.credentialsToApply(null, null, null, localTime = 0L, remote = cred("x", "u", "p", "k", at = null)) { false })
    }

    // ── preferences ──

    @Test fun firstRunStampsNothingSoStaleValuesNeverWin() {
        val local = DriveMerge.PrefState(mapOf("theme" to "dark"), emptyMap(), emptyMap(), emptyMap())
        assertTrue(DriveMerge.localPrefTimes(local, baseline = null, previous = emptyMap(), nowMs = 9_999L).isEmpty())
        val merged = DriveMerge.mergePrefs(
            DriveMerge.PrefState(mapOf("theme" to "light"), emptyMap(), emptyMap(), mapOf("v:theme" to 5_000L)), local,
        )
        assertEquals("light", merged.v["theme"])
    }

    @Test fun aLocalChangeSinceTheLastSyncIsStampedAndWins() {
        val local = DriveMerge.PrefState(mapOf("theme" to "dark", "font" to "s"), emptyMap(), emptyMap(), emptyMap())
        val times = DriveMerge.localPrefTimes(local, baseline = mapOf("v:theme" to "light", "v:font" to "s"), previous = emptyMap(), nowMs = 9_000L)
        assertEquals(mapOf("v:theme" to 9_000L), times)
        val remote = DriveMerge.PrefState(mapOf("theme" to "light", "font" to "m"), emptyMap(), emptyMap(), mapOf("v:theme" to 5_000L, "v:font" to 6_000L))
        val merged = DriveMerge.mergePrefs(remote, local.copy(times = times))
        assertEquals("dark", merged.v["theme"])
        assertEquals("m", merged.v["font"])
    }

    @Test fun anOlderFilesPushTimeNeverOverwritesASetting() {
        // Untimed keys (a file an older build pushed) are of unknown age: the
        // envelope is a push time, so they never outrank this device's value.
        assertEquals(0L, DriveMerge.remotePrefTime(emptyMap(), "v", "theme"))
        assertEquals(0L, DriveMerge.remotePrefTime(mapOf("v:other" to 1L), "v", "theme"))
        assertFalse(DriveMerge.prefApplies(0L, null))
        // ...except on a device that never pulled (restore): every key applies.
        assertTrue(DriveMerge.prefApplies(0L, null, freshDevice = true))
        assertTrue(DriveMerge.prefApplies(7_000L, null))
        assertFalse(DriveMerge.prefApplies(7_000L, 7_000L))
        assertFalse(DriveMerge.prefApplies(6_000L, 7_000L))
        // Push: an untimed Drive value and an unstamped local value tie, so Drive keeps its value
        // (this device keeps its own) and both sides wait for a real change.
        val merged = DriveMerge.mergePrefs(
            DriveMerge.PrefState(mapOf("theme" to "stale"), emptyMap(), emptyMap(), emptyMap()),
            DriveMerge.PrefState(mapOf("theme" to "mine", "font" to "s"), emptyMap(), emptyMap(), emptyMap()),
        )
        assertEquals("stale", merged.v["theme"]); assertEquals("s", merged.v["font"])
        assertEquals(mapOf("v:theme" to 0L, "v:font" to 0L), merged.times)
        // The baseline after that push is this device's own values, so the next
        // round sees no local change (Drive keeping its value is not one).
        val mine = DriveMerge.PrefState(mapOf("theme" to "mine", "font" to "s"), emptyMap(), emptyMap(), emptyMap())
        assertTrue(DriveMerge.localPrefTimes(mine, baseline = mapOf("v:theme" to "mine", "v:font" to "s"), previous = emptyMap(), nowMs = 9L).isEmpty())
        // Measured on the Fold 2026-11-10: a merged-value baseline read "theme" as changed here.
        assertEquals(mapOf("v:theme" to 9L), DriveMerge.localPrefTimes(mine, baseline = mapOf("v:theme" to "stale", "v:font" to "s"), previous = emptyMap(), nowMs = 9L))
    }

    // ── envelope ──

    @Test fun theFileTimeIsTheNewestItemNeverThePushTime() {
        assertEquals(9_500L, DriveMerge.mergedTime(5_000L, listOf(1_000L, 9_500L)))
        assertEquals(5_000L, DriveMerge.mergedTime(5_000L, listOf(1_000L)))
        assertEquals(0L, DriveMerge.mergedTime(null, emptyList()))
    }

    @Test fun preMigrationRowsAreNeverStampedCurrent() {
        assertNull(DriveMerge.mergedFormat(remoteExists = true, remoteFormat = null, current = 2))
        assertEquals(1, DriveMerge.mergedFormat(remoteExists = true, remoteFormat = 1, current = 2))
        assertEquals(2, DriveMerge.mergedFormat(remoteExists = true, remoteFormat = 2, current = 2))
        assertEquals(2, DriveMerge.mergedFormat(remoteExists = false, remoteFormat = null, current = 2))
    }
}

/** The device-local store of change times and tombstones. */
class SyncStampsTest {
    private val mem = HashMap<String, String>()
    private var now = 1_000_000_000_000L

    @Before fun setUp() {
        SyncStamps.install(object : SyncStamps.Store {
            override fun get(name: String): String? = mem[name]
            override fun put(name: String, value: String) { mem[name] = value }
        })
        SyncStamps.now = { now }
        SyncStamps.recordable = { true }
    }

    @After fun tearDown() {
        SyncStamps.now = { System.currentTimeMillis() }
    }

    @Test fun aSettingIsStampedTheMomentItChanges() {
        // First write: values recorded, nothing stamped (their age is unknown).
        assertTrue(SyncStamps.notePrefValues(mapOf("v:a" to "1", "v:b" to "x")).isEmpty())
        now += 100
        assertEquals(listOf("v:a"), SyncStamps.notePrefValues(mapOf("v:a" to "2", "v:b" to "x")))
        assertEquals(now, SyncStamps.touchedAt(SyncStamps.Cat.Preferences, "v:a"))
        assertNull(SyncStamps.touchedAt(SyncStamps.Cat.Preferences, "v:b"))
        // A sync apply records values without stamping; the next write compares against them.
        SyncStamps.setPrefLastSeen(mapOf("v:a" to "2", "v:b" to "y"))
        now += 100
        assertTrue(SyncStamps.notePrefValues(mapOf("v:a" to "2", "v:b" to "y")).isEmpty())
        assertNull(SyncStamps.touchedAt(SyncStamps.Cat.Preferences, "v:b"))
    }

    @Test fun mergeBookkeepingPersists() {
        SyncStamps.learnAccount("p1", " logan ")
        assertEquals("logan", SyncStamps.learnedAccount("p1"))
        SyncStamps.supersede("abc")
        assertTrue(SyncStamps.isSuperseded("abc"))
        assertFalse(SyncStamps.isSuperseded("def"))
        SyncStamps.setPrefBaseline(mapOf("v:a" to "1"))
        assertEquals(now, SyncStamps.prefBaselineAt())
        // A pull records what it applied without moving the baseline's time.
        val pushedAt = now
        now += 500
        SyncStamps.setPrefBaseline(mapOf("v:a" to "2"), stampTime = false)
        assertEquals(pushedAt, SyncStamps.prefBaselineAt())
    }

    @Test fun removeThenTouchClearsTheTombstone() {
        SyncStamps.removed(SyncStamps.Cat.Favorites, "c1")
        assertEquals(now, SyncStamps.removedAt(SyncStamps.Cat.Favorites, "c1"))
        now += 10
        SyncStamps.touched(SyncStamps.Cat.Favorites, "c1")
        assertNull(SyncStamps.removedAt(SyncStamps.Cat.Favorites, "c1"))
        assertEquals(now, SyncStamps.touchedAt(SyncStamps.Cat.Favorites, "c1"))
    }

    @Test fun otherProfilesAreNotRecorded() {
        SyncStamps.recordable = { false }
        SyncStamps.removed(SyncStamps.Cat.WatchProgress, "m1")
        assertNull(SyncStamps.removedAt(SyncStamps.Cat.WatchProgress, "m1"))
    }

    @Test fun tombstonesExpire() {
        SyncStamps.removed(SyncStamps.Cat.Reminders, "old", at = now - SyncStamps.TOMBSTONE_TTL_MS - 1)
        SyncStamps.removed(SyncStamps.Cat.Reminders, "new")
        assertEquals(setOf("new"), SyncStamps.removedAll(SyncStamps.Cat.Reminders).keys)
    }

    @Test fun survivesARestart() {
        SyncStamps.touched(SyncStamps.Cat.Credentials, "p1")
        SyncStamps.install(object : SyncStamps.Store {
            override fun get(name: String): String? = mem[name]
            override fun put(name: String, value: String) { mem[name] = value }
        })
        assertEquals(now, SyncStamps.touchedAt(SyncStamps.Cat.Credentials, "p1"))
    }
}
