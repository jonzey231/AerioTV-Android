package com.aeriotv.android.core.sync.dsync

import com.aeriotv.android.core.sync.DriveCanon
import com.aeriotv.android.core.sync.PreferencesSnapshot
import com.aeriotv.android.core.sync.SyncEnvelope
import com.aeriotv.android.core.sync.WatchProgressSnapshot
import com.aeriotv.android.core.sync.WatchProgressSnapshotEntry
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 10a rules from the 2026-11-01 phone run: one Drive WatchProgress
 * snapshot builder (held and Dispatcharr rows always ride), pull before
 * replace on a Dispatcharr choice, and an atomic per-category Drive commit.
 */
class DriveSnapshotRulesTest {
    private val nowS = 2_000_000_000L
    private val env = SyncEnvelope(lastModified = 1_000, device = "t")
    private val heldRow = ProgressRow(11, "m", 10, 100, nowS - 600, false, null, 0, 0, "Held m11")
    private val serverPlayed = ProgressRow(22, "m", 60, 5400, nowS - 30, false, null, 0, 0, "Played m22")
    private fun local(id: Int? = null) = WatchProgressSnapshotEntry(
        videoId = "uuid-22", title = "Played m22", positionMs = 60_000, durationMs = 5_400_000,
        updatedAt = (nowS - 30) * 1000, vodType = "movie", dispatcharrVodId = id,
    )
    private fun rows(s: WatchProgressSnapshot) = DriveCanon.progress(s, DriveCanon.Ids(), nowS * 1000).rows
    private val uuidOf = { t: String, id: Int -> if (t == "m" && id == 22) "uuid-22" else null }

    private fun src(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
    private val driveSrc by lazy { src("src/main/java/com/aeriotv/android/core/sync/DriveSyncManager.kt") }

    @Test fun heldPlusOneLocalRowGiveTheSameSnapshotFromEveryEntryPoint() {
        val entryPoints = listOf("replace", "converge", "follow local", "follow pull", "periodic", "Push to Drive")
        val snaps = entryPoints.map { DsyncHeld.driveSnapshot(WatchProgressSnapshot(env, listOf(local(22))), listOf(heldRow), uuidOf) }
        snaps.forEach { assertEquals(snaps.first(), it) }
        val s = snaps.first()
        assertEquals(2, rows(s))
        // The resolved local row wins; only the unresolved held row is a placeholder.
        assertEquals(1, s.entries.count { DsyncHeld.isPlaceholder(it.videoId) })
        assertEquals("uuid-22", s.entries.first().videoId)
        // Rebuilding from a snapshot that already carries placeholders is stable.
        assertEquals(s, DsyncHeld.driveSnapshot(s, listOf(heldRow), uuidOf))
    }

    @Test fun sourceHasOneBuilderUsedByEveryPush() {
        // Only driveProgressSnapshot reads the local table for Drive; both payload sites call it.
        assertEquals(1, Regex("""buildWatchProgressSnapshot\(\)""").findAll(driveSrc).count { true } - 1) // declaration + one call
        assertEquals(2, Regex("""driveProgressSnapshot\((label|null)""").findAll(driveSrc).count())
        assertFalse(driveSrc.contains("withHeld("))
    }

    @Test fun playlistWritesNeverReplaceTheRow() {
        // REPLACE deletes the parent row first, and ON DELETE CASCADE then removed every
        // watch progress, reminder, and local recording row of that playlist.
        val dao = src("src/main/java/com/aeriotv/android/core/data/db/dao/PlaylistDao.kt")
        assertFalse(dao.contains("OnConflictStrategy.REPLACE"))
        assertTrue(Regex("""@androidx\.room\.Upsert\s+suspend fun upsert\(playlist: PlaylistEntity\)""").containsMatchIn(dao))
    }

    @Test fun failedIdLookupNoLongerEmptiesTheSnapshot() {
        // Item 4: the local row existed but its canonical lookup returned nothing in that run.
        val s = DsyncHeld.driveSnapshot(WatchProgressSnapshot(env, listOf(local(null))), listOf(serverPlayed, heldRow), uuidOf)
        assertEquals(2, rows(s))
        assertEquals(22, s.entries.single { it.videoId == "uuid-22" }.dispatcharrVodId)
        // No saved reverse id at all: the server row rides as a placeholder, still counted once.
        val t = DsyncHeld.driveSnapshot(WatchProgressSnapshot(env, listOf(local(null))), listOf(serverPlayed, heldRow))
        assertEquals(2, rows(t))
    }

    @Test fun choicePullAppliesANotNewerServerRecentsCopyBeforeTheDrivePush() {
        // Server copy (14) newer in content than local (13); this device already consumed the stamp.
        assertTrue(DsyncCoexistence.choiceApplies(remoteWins = false, forceApply = true, key = DsyncKey.Recents, dirty = false))
        assertFalse(DsyncCoexistence.choiceApplies(false, false, DsyncKey.Recents, false)) // ordinary pull: not newer stays
        assertFalse(DsyncCoexistence.choiceApplies(false, true, DsyncKey.Recents, true)) // unsent local change is newer
        assertFalse(DsyncCoexistence.choiceApplies(false, true, DsyncKey.Profiles, false))
        val local = (1..13).map { RecentRow(100 + it, nowS - 100 - it) }
        val server = local + RecentRow(200, nowS - 5)
        val applied = DsyncMerge.recents(local, server)
        val ids = applied.associate { "ch-${it.channelId}" to it.channelId }
        val prefs = PreferencesSnapshot(env, mapOf(DriveCanon.K_RECENTS to applied.joinToString("\n") { "ch-${it.channelId}" }), dispatcharrChannelIds = ids)
        assertEquals(14, DriveCanon.recentsCategory(prefs, DriveCanon.Ids()).rows)
        // Order in code: the choice pull runs, and only a successful one replaces Drive.
        val coord = src("src/main/java/com/aeriotv/android/core/sync/SetupSourceCoordinator.kt")
        val body = coord.substringAfter("private suspend fun pullThenReplace()").substringBefore("private val replaceLock")
        assertTrue(body.indexOf("pullForChoice()") in 0 until body.indexOf("replaceBackupNow()"))
        assertTrue(body.contains("[DRIVE] replace deferred: Dispatcharr pull failed"))
    }

    @Test fun recentThisDeviceCannotMapIsExcludedSoTheProbesAgree() {
        // Server recents 14, one channel absent from this device's list: both summaries count 13.
        val local = (1..13).map { RecentRow(100 + it, nowS - 100 - it) }
        val wire = DsyncPayloads.encodeRecents(null, local + RecentRow(999, nowS - 5))
        val mine = local.map { it.channelId }.toSet()
        val keep = { t: String, id: Int -> DsyncHeld.representable(t, id, emptySet(), emptySet(), emptySet(), emptySet(), mine) }
        val (c, gone) = DsyncHeld.recentsCategory(wire, 1_000, keep)
        assertEquals(13, c.rows)
        assertEquals(1, gone)
        val ids = local.associate { "ch-${it.channelId}" to it.channelId }
        val prefs = PreferencesSnapshot(env, mapOf(DriveCanon.K_RECENTS to local.joinToString("\n") { "ch-${it.channelId}" }), dispatcharrChannelIds = ids)
        assertEquals(DriveCanon.recentsCategory(prefs, DriveCanon.Ids()).fingerprint, c.fingerprint)
        // A Drive copy that carries the channel keeps it; an unloaded channel list excludes nothing.
        assertTrue(DsyncHeld.representable("c", 999, emptySet(), emptySet(), emptySet(), setOf(DsyncHeld.channelItem(999)), mine))
        assertTrue(DsyncHeld.representable("c", 999, emptySet(), emptySet(), emptySet(), emptySet(), emptySet()))
    }

    @Test fun uploadAndBookkeepingCannotBeSplitByCancellation() {
        // The only category push upload lives in commitCategory, and commitCategory runs under NonCancellable
        // (the id migration's single-call rewrites carry no bookkeeping).
        assertEquals(1, Regex("""driveClient\.upload\(token, category\.fileName""").findAll(driveSrc).count())
        val commit = driveSrc.substringAfter("private suspend fun commitCategory(")
        assertTrue(commit.substringBefore("\n    }\n").contains("driveClient.upload("))
        assertEquals(1, Regex("""commitCategory\(token,""").findAll(driveSrc).count())
        assertTrue(Regex("""withContext\(kotlinx\.coroutines\.NonCancellable\) \{\s*commitCategory\(""").containsMatchIn(driveSrc))
        // And the mechanism: a cancel during the commit lets both halves finish.
        var uploaded = false
        var committed = false
        runBlocking {
            val job = launch {
                withContext(NonCancellable) {
                    delay(30); uploaded = true
                    delay(30); committed = true
                }
            }
            delay(10)
            job.cancel()
            job.join()
        }
        assertTrue(uploaded && committed)
    }
}
