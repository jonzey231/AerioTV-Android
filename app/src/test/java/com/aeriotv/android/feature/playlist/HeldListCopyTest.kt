package com.aeriotv.android.feature.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A held Child's lists (Apple parity, 2026-11-11): the bare line on a manual
 * refresh, and at launch with nothing cached it stays with that line instead
 * of going to the add-playlist screen. Other failures keep today's copy.
 */
class HeldListCopyTest {

    @Test fun aHeldManualRefreshShowsTheBareLine() {
        assertEquals("Could not reach your Dispatcharr server", HeldListCopy.refreshFailed(held = true, reason = "Could not reach your Dispatcharr server"))
        assertEquals("Could not reach your Dispatcharr server", HeldListCopy.refreshFailed(held = true, reason = "anything"))
    }

    @Test fun otherRefreshFailuresKeepTheirPrefix() {
        assertEquals("Refresh failed: HTTP 500", HeldListCopy.refreshFailed(held = false, reason = "HTTP 500"))
    }

    @Test fun aHeldLaunchWithNothingCachedStays() {
        assertEquals(HeldListCopy.LaunchFailed.StayWithLine, HeldListCopy.launchFailed(routeHeld = true, keyHeld = false))
        // A held route wins: its line, not the key's error state.
        assertEquals(HeldListCopy.LaunchFailed.StayWithLine, HeldListCopy.launchFailed(routeHeld = true, keyHeld = true))
        assertEquals(com.aeriotv.android.feature.settings.DISPATCHARR_UNREACHABLE, HeldListCopy.LINE)
    }

    @Test fun aKeyHeldAtLaunchNeverGoesToAddPlaylist() {
        // Apple parity (owner 2026-11-12): the main screen with the error state and the prompt.
        assertEquals(HeldListCopy.LaunchFailed.MainWithError, HeldListCopy.launchFailed(routeHeld = false, keyHeld = true))
        // Any other failure with nothing cached: on to add a playlist, as before.
        assertEquals(HeldListCopy.LaunchFailed.AddPlaylist, HeldListCopy.launchFailed(routeHeld = false, keyHeld = false))
    }

    @Test fun theErrorStateShowsUntilChannelsLand() {
        assertTrue(HeldListCopy.showsLaunchError("Dispatcharr no longer accepts this playlist's API key.", hasChannels = false))
        // Channels landed (a Sign In, Fix Login, or Edit Playlist recovery): the list replaces it.
        assertFalse(HeldListCopy.showsLaunchError("Dispatcharr no longer accepts this playlist's API key.", hasChannels = true))
        // No held-key launch: an empty list keeps its own notice.
        assertFalse(HeldListCopy.showsLaunchError(null, hasChannels = false))
    }

    @Test fun aLaunchStillOnSettingUpFinishesWhenChannelsLand() {
        // The hold lifted (or a recovery refreshed) while the launch waited: on to the guide.
        assertEquals(PlaylistViewModel.Phase.ChannelsReady, HeldListCopy.phaseAfterChannelsLanded(PlaylistViewModel.Phase.Bootstrapping, hasChannels = true))
        // Nothing loaded: it stays where it is.
        assertEquals(PlaylistViewModel.Phase.Bootstrapping, HeldListCopy.phaseAfterChannelsLanded(PlaylistViewModel.Phase.Bootstrapping, hasChannels = false))
        // Any other phase is left alone (a re-add on the Welcome screen is not interrupted).
        assertEquals(PlaylistViewModel.Phase.NeedsUrl, HeldListCopy.phaseAfterChannelsLanded(PlaylistViewModel.Phase.NeedsUrl, hasChannels = true))
        assertEquals(PlaylistViewModel.Phase.ChannelsReady, HeldListCopy.phaseAfterChannelsLanded(PlaylistViewModel.Phase.ChannelsReady, hasChannels = true))
    }
}
