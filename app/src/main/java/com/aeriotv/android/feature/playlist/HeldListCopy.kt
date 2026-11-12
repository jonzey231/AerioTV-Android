package com.aeriotv.android.feature.playlist

/**
 * A held Child's channel list (Apple parity, 2026-11-11, following item 15):
 * every request to its playlist's moved address is refused, so the lists show
 * the existing line "Could not reach your Dispatcharr server" on its own.
 * Any other failure keeps today's copy and behavior. Pure, for the tests.
 */
object HeldListCopy {
    const val LINE = com.aeriotv.android.feature.settings.DISPATCHARR_UNREACHABLE

    /** A manual refresh that failed: the bare line for a held Child, else "Refresh failed: <reason>". */
    fun refreshFailed(held: Boolean, reason: String): String = if (held) LINE else "Refresh failed: $reason"

    /**
     * The launch refresh failed with no cached channels: a held Child stays
     * where it is and shows [LINE]; anyone else goes on to add a playlist.
     */
    fun launchGoesToAdd(held: Boolean): Boolean = !held

    /**
     * Channels landed from a later refresh (the hold lifted, a recovery):
     * a launch still on the Setting Up screen finishes there, so it never
     * stays up over a loaded list. Any other phase is left as it is.
     */
    fun phaseAfterChannelsLanded(current: PlaylistViewModel.Phase, hasChannels: Boolean): PlaylistViewModel.Phase =
        if (current == PlaylistViewModel.Phase.Bootstrapping && hasChannels) PlaylistViewModel.Phase.ChannelsReady else current
}
