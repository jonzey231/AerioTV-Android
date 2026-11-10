package com.aeriotv.android.feature.playlist

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A failed Add Playlist test must never touch the ACTIVE playlist's state:
 * Live TV reads its channel list from the same view model state, and a
 * failed test wrote nothing to the database.
 */
class AddTestFailureKeepsActiveTest {
    private val active = PlaylistEntity(id = "active", name = "Home", urlString = "https://example.invalid")
    private val channels = listOf(
        M3UChannel(id = "c1", name = "One", url = "https://example.invalid/1"),
        M3UChannel(id = "c2", name = "Two", url = "https://example.invalid/2"),
    )
    private val epg = mapOf(
        "c1" to listOf(EPGProgramme(channelId = "c1", title = "News", description = "", startMillis = 0L, endMillis = 1L, category = "")),
    )

    private fun testing() = PlaylistViewModel.UiState(
        phase = PlaylistViewModel.Phase.ChannelsReady,
        playlist = active,
        channels = channels,
        epgByChannel = epg,
        selectedGroup = "Sports",
        url = "https://typed.invalid",
        apiKey = "typed-key",
        isLoading = true,
    )

    @Test fun failedTestKeepsActiveChannelsGuideAndPlaylist() {
        val before = testing()
        val after = addTestFailedState(before, "Failed to load: HTTP 401")
        assertSame(before.channels, after.channels)
        assertSame(before.epgByChannel, after.epgByChannel)
        assertSame(before.playlist, after.playlist)
        assertEquals(before.phase, after.phase)
        assertEquals(before.selectedGroup, after.selectedGroup)
    }

    @Test fun failedTestOnlyEndsLoadingAndShowsTheError() {
        val before = testing()
        val after = addTestFailedState(before, "Failed to load: HTTP 401")
        assertFalse(after.isLoading)
        assertEquals("Failed to load: HTTP 401", after.error)
        assertEquals(before.copy(isLoading = false, error = "Failed to load: HTTP 401"), after)
    }

    @Test fun failedSetupAddClearsTheErrorLineOnly() {
        val before = testing().copy(error = "old")
        val after = addTestFailedState(before, error = null)
        assertEquals(before.copy(isLoading = false, error = null), after)
        assertSame(before.channels, after.channels)
    }
}
