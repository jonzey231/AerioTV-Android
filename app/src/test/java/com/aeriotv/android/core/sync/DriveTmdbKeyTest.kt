package com.aeriotv.android.core.sync

import com.aeriotv.android.core.sync.dsync.DsyncPayloads
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TMDB API key in the Drive Credentials file (2026-11-13): one key per
 * device, newest edit wins by the Dispatcharr Sync rule, a clear syncs, an
 * apply keeps the source's time and is not an edit here. Made-up keys only.
 */
class DriveTmdbKeyTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val env = SyncEnvelope(lastModified = 5_000L, device = "remote", format = 2)

    private fun drive(key: String, atSec: Long) = DriveMerge.TmdbDrive(key, atSec * 1000)

    // ── push ──

    @Test fun aNewerLocalKeyGoesOut() {
        val out = DriveMerge.tmdbToWrite(drive("OLD", 100), "NEW", 200)
        assertEquals(drive("NEW", 200), out)
    }

    @Test fun anOlderLocalKeyLeavesDrivesCopy() {
        val remote = drive("NEWER", 300)
        assertEquals(remote, DriveMerge.tmdbToWrite(remote, "OLDER", 200))
    }

    @Test fun aNewerClearGoesOut() {
        assertEquals(drive("", 400), DriveMerge.tmdbToWrite(drive("KEY", 300), "", 400))
    }

    @Test fun anOlderClearDoesNotWipeDrivesKey() {
        val remote = drive("KEY", 500)
        assertEquals(remote, DriveMerge.tmdbToWrite(remote, "", 400))
    }

    @Test fun aKeyOfUnknownAgeBeatsAClearButNotAKey() {
        assertEquals("a key here beats Drive's clear", drive("MINE", 0), DriveMerge.tmdbToWrite(drive("", 300), "MINE", 0))
        val remote = drive("THEIRS", 300)
        assertEquals("Drive's key stands", remote, DriveMerge.tmdbToWrite(remote, "MINE", 0))
    }

    @Test fun noKeyAnywhereWritesNothing() {
        assertNull("no key and no edit here starts nothing", DriveMerge.tmdbToWrite(null, "", 0))
        assertEquals("a clear made here starts the field", drive("", 50), DriveMerge.tmdbToWrite(null, "", 50))
        assertEquals("a key here starts the field", drive("KEY", 0), DriveMerge.tmdbToWrite(null, " KEY ", 0))
    }

    @Test fun theSameKeyKeepsDrivesCopyUnchanged() {
        val remote = drive("KEY", 100)
        assertEquals(remote, DriveMerge.tmdbToWrite(remote, "KEY", 900))
    }

    // ── pull ──

    @Test fun aNewerDriveKeyApplies() {
        assertEquals(drive("NEW", 300), DriveMerge.tmdbToApply(drive("NEW", 300), "OLD", 200))
    }

    @Test fun aNewerDriveClearApplies() {
        assertEquals(drive("", 300), DriveMerge.tmdbToApply(drive("", 300), "KEY", 200))
    }

    @Test fun anOlderDriveCopyNeverApplies() {
        assertNull(DriveMerge.tmdbToApply(drive("OLD", 100), "NEW", 200))
        assertNull(DriveMerge.tmdbToApply(drive("", 100), "KEY", 200))
    }

    @Test fun aClearOfUnknownAgeNeverWipesAKey() {
        assertNull(DriveMerge.tmdbToApply(drive("", 0), "KEY", 0))
        assertNull(DriveMerge.tmdbToApply(drive("", 0), "KEY", 50))
    }

    @Test fun aDeviceWithNoKeyTakesDrivesKey() {
        assertEquals(drive("KEY", 0), DriveMerge.tmdbToApply(drive("KEY", 0), "", 0))
    }

    @Test fun noFieldOrTheSameKeyAppliesNothing() {
        assertNull(DriveMerge.tmdbToApply(null, "KEY", 100))
        assertNull(DriveMerge.tmdbToApply(drive("KEY", 900), " KEY ", 100))
    }

    @Test fun anApplyKeepsDrivesTimeAndIsNotAnEditHere() {
        val applied = DriveMerge.tmdbToApply(drive("NEW", 300), "OLD", 200)!!
        // The device records Drive's time (Unix seconds), not the moment of the pull.
        assertEquals(300L, applied.atMs / 1000)
        val before = DsyncPayloads.TmdbLocal(DsyncPayloads.tmdbDigest("OLD"), 200)
        val after = DsyncPayloads.TmdbLocal(DsyncPayloads.tmdbDigest(applied.key), applied.atMs / 1000, fromSync = true)
        assertFalse("Dispatcharr Sync does not send a Drive apply on as an edit", DsyncPayloads.tmdbIsEdit(before, after))
        val saved = DsyncPayloads.TmdbLocal(DsyncPayloads.tmdbDigest("TYPED"), 400, fromSync = false)
        assertTrue("a save after the apply is an edit", DsyncPayloads.tmdbIsEdit(after, saved))
    }

    @Test fun drivePullThenDispatcharrConvergeOnTheNewestEdit() {
        // Drive holds a key edited at 300; Dispatcharr holds a newer one at 400.
        var key = "OLD"; var at = 100L
        DriveMerge.tmdbToApply(drive("DRIVE", 300), key, at)?.let { key = it.key; at = it.atMs / 1000 }
        assertEquals("DRIVE", key)
        // The Dispatcharr Sync pull: its newer copy applies over the Drive one.
        assertFalse(DsyncPayloads.tmdbKeepsLocal(key, "DSYNC", at, 400))
        key = "DSYNC"; at = 400
        // The next Drive push sends the newest edit back to Drive, with its own time.
        assertEquals(drive("DSYNC", 400), DriveMerge.tmdbToWrite(drive("DRIVE", 300), key, at))
        // And a pull of that copy changes nothing: no ping-pong.
        assertNull(DriveMerge.tmdbToApply(drive("DSYNC", 400), key, at))
    }

    // ── the file ──

    @Test fun anOlderFileCarriesNoKey() {
        val old = """{"envelope":{"version":1,"lastModified":5000,"device":"old"},"entries":[{"playlistId":"p1","apiKey":"PL-KEY"}]}"""
        val f = json.decodeFromString<CredentialsSnapshot>(old)
        assertNull(f.tmdbKey)
        assertNull(DriveMerge.tmdbOf(f))
    }

    @Test fun aNewFileRoundTripsAndAnOlderReaderIgnoresTheKey() {
        val f = CredentialsSnapshot(env, listOf(CredentialsSnapshotEntry(playlistId = "p1", apiKey = "PL-KEY")), tmdbKey = "TMDB-KEY", tmdbUpdatedAt = 300_000L)
        val body = json.encodeToString(f)
        assertEquals(drive("TMDB-KEY", 300), DriveMerge.tmdbOf(json.decodeFromString<CredentialsSnapshot>(body)))
        // A reader built before the field (ignoreUnknownKeys, as every shipped build) still reads its entries.
        val older = json.decodeFromString<OlderCredentials>(body)
        assertEquals(listOf("p1"), older.entries.map { it.playlistId })
    }

    @Test fun aClearIsCarried() {
        val f = CredentialsSnapshot(env, emptyList(), tmdbKey = "", tmdbUpdatedAt = 400_000L)
        assertEquals(drive("", 400), DriveMerge.tmdbOf(json.decodeFromString<CredentialsSnapshot>(json.encodeToString(f))))
    }

    /** The Credentials file as builds before 2026-11-13 declare it. */
    @Serializable
    private data class OlderCredentials(val envelope: SyncEnvelope, val entries: List<CredentialsSnapshotEntry>)
}
