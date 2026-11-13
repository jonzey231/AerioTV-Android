package com.aeriotv.android.core.data.repository

import com.aeriotv.android.core.data.db.entity.ReminderEntity
import com.aeriotv.android.core.network.DispatcharrRequestGate
import com.aeriotv.android.feature.dvr.DvrSavedOnDevice
import com.aeriotv.android.feature.dvr.DvrViewModel
import com.aeriotv.android.feature.dvr.LocalRecordingService
import com.aeriotv.android.feature.reminders.ReminderVisibility
import com.aeriotv.android.feature.settings.PlaylistDeleteCopy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Playlist delete cutoff (Logan 2026-11-12: "If the playlist is deleted then
 * the app should no longer have access to anything within that playlist.
 * Immediately." and "A playlist delete must NEVER send anything to the
 * server to cancel, stop, or delete a Dispatcharr (server-side) recording or
 * recording rule."), the recordings prompt, and the reminders rule.
 */
class PlaylistDeleteCutoffTest {

    @Before fun setUp() {
        DispatcharrRequestGate.resetForTests()
        DeletedPlaylists.resetForTests()
    }

    @After fun tearDown() {
        DispatcharrRequestGate.resetForTests()
        DeletedPlaylists.resetForTests()
    }

    // ---- the prompt ----

    @Test
    fun aRunningCaptureIsCountedOnceInThePrompt() {
        val c = com.aeriotv.android.feature.dvr.InterruptedCapture
        // Its row is written when it starts: one recording, not two.
        assertEquals(1, c.promptCount(listOf(c.RECORDING), capturingNow = true))
        assertEquals(2, c.promptCount(listOf("completed", c.RECORDING), capturingNow = true))
        // A capture whose start row failed to write still counts.
        assertEquals(2, c.promptCount(listOf("completed"), capturingNow = true))
        assertEquals(1, c.promptCount(listOf("completed"), capturingNow = false))
        assertEquals(0, c.promptCount(emptyList(), capturingNow = false))
    }

    @Test
    fun thePromptNamesThePlaylistAndCountsItsRecordings() {
        assertEquals("Delete Living Room?", PlaylistDeleteCopy.title("Living Room"))
        assertEquals(
            "This removes the playlist and its sign-in from this device. 1 recording saved on this device came from this playlist.",
            PlaylistDeleteCopy.body(1),
        )
        assertEquals(
            "This removes the playlist and its sign-in from this device. 3 recordings saved on this device came from this playlist.",
            PlaylistDeleteCopy.body(3),
        )
        assertEquals("Delete, Keep Recordings", PlaylistDeleteCopy.KEEP)
        assertEquals("Delete Playlist and Recordings", PlaylistDeleteCopy.DELETE_ALL)
        assertEquals("Cancel", PlaylistDeleteCopy.CANCEL)
    }

    // ---- nothing goes out with its sign-ins ----

    @Test
    fun aDeletedPlaylistsKeyIsRefusedEverywhereWithNoPrompt() {
        DispatcharrRequestGate.markDeleted("KEY-A")
        assertTrue(DispatcharrRequestGate.isKeyDeleted("KEY-A"))
        // Every gate that checks a held key (API calls, streams, logos) refuses it.
        assertTrue(DispatcharrRequestGate.isKeyRejected("KEY-A"))
        assertNull(DispatcharrRequestGate.prompt.value)
        assertFalse(DispatcharrRequestGate.isKeyRejected("KEY-B"))
    }

    @Test
    fun theSameKeySavedAgainIsUsableAgain() {
        DispatcharrRequestGate.markDeleted("KEY-A")
        DispatcharrRequestGate.forgetDeleted("KEY-A")
        assertFalse(DispatcharrRequestGate.isKeyRejected("KEY-A"))
        DispatcharrRequestGate.markDeleted("KEY-A")
        // A key that just authenticated (an explicit save's check) is good again.
        DispatcharrRequestGate.clearKey("KEY-A")
        assertFalse(DispatcharrRequestGate.isKeyDeleted("KEY-A"))
    }

    // ---- an on-device recording running at the delete ----

    @Test
    fun aRecordingFinishingAfterItsPlaylistWasDeletedIsKeptWithoutAPlaylist() {
        assertNull(LocalRecordingService.deletedOutcome("P1"))
        DeletedPlaylists.mark("P1")
        assertEquals(true, LocalRecordingService.deletedOutcome("P1"))
        assertNull(LocalRecordingService.deletedOutcome(null))
    }

    // ---- Saved on this device ----

    private fun rec(id: String, saved: Boolean) = DvrViewModel.Recording(
        id = id, source = DvrViewModel.Source.Local, title = id, description = "",
        startMillis = 0L, endMillis = 0L, status = DvrViewModel.Recording.Status.Completed,
        fileSizeBytes = 0L, savedOnDevice = saved,
    )

    @Test
    fun keptRecordingsComeLastUnderTheirOwnHeader() {
        val rows = DvrSavedOnDevice.rows(listOf(rec("a", true), rec("b", false), rec("c", true)))
        assertEquals(listOf("b", DvrSavedOnDevice.Header, "a", "c"), rows.map { (it as? DvrViewModel.Recording)?.id ?: it })
        assertEquals(listOf("b"), DvrSavedOnDevice.rows(listOf(rec("b", false))).map { (it as DvrViewModel.Recording).id })
        assertEquals("Saved on this device", DvrSavedOnDevice.TITLE)
    }

    // ---- reminders stay, hidden, and come back with a playlist that has their channel ----

    private fun reminder(channel: String, start: Long = 10_000L, playlist: String? = "P1") =
        ReminderEntity("$channel|Show|$start", channel, "Show", start, start + 3_600_000L, 1, playlist)

    @Test
    fun anOrphanComesBackOnAPlaylistWithItsChannel() {
        val orphans = listOf(reminder("ESPN2"), reminder("News 4"), reminder("Kids"))
        val back = ReminderVisibility.comeBack(orphans, setOf(" espn2 ", "Weather"))
        assertEquals(listOf("ESPN2"), back.map { it.channelName })
        assertTrue(ReminderVisibility.comeBack(orphans, emptySet()).isEmpty())
        assertTrue(ReminderVisibility.comeBack(emptyList(), setOf("ESPN2")).isEmpty())
    }

    @Test
    fun onlyAProgramStillAheadGetsItsAlarmAgain() {
        val r = reminder("ESPN2", start = 1_000_000L)
        assertTrue(ReminderVisibility.stillAhead(r, now = 999_999L))
        assertFalse(ReminderVisibility.stillAhead(r, now = 1_000_000L))
        // Five minutes ahead of the start, never in the past.
        assertEquals(1_000_000L - 300_000L, ReminderVisibility.triggerAt(r, now = 0L))
        assertEquals(999_000L + 1_000L, ReminderVisibility.triggerAt(r, now = 999_000L))
    }

    @Test
    fun aReminderIsVisibleOnlyWhileItsPlaylistIsOnThisDevice() {
        // The SQL every list and badge uses (ReminderDao): no playlist id
        // (from before ids), or a playlist that exists here.
        assertEquals(
            "(playlistId IS NULL OR playlistId IN (SELECT id FROM playlists))",
            com.aeriotv.android.core.data.db.dao.ReminderDao.VISIBLE,
        )
    }

    @Test
    fun theMigrationKeepsEveryReminderAndDropsOnlyTheCascade() {
        val sql = com.aeriotv.android.core.di.DatabaseModule.MIGRATION_39_40_SQL
        assertTrue(sql.first().startsWith("CREATE TABLE IF NOT EXISTS `reminder_new`"))
        assertFalse(sql.first().contains("FOREIGN KEY"))
        assertTrue(sql[1].startsWith("INSERT INTO `reminder_new`") && sql[1].contains("`profileScope` FROM `reminder`"))
        assertTrue(sql.last().contains("index_reminder_playlistId"))
    }
}
