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

    /** Where a launch goes when its refresh failed with no cached channels. */
    enum class LaunchFailed {
        /** A held Child: stays on Setting Up with [LINE]. */
        StayWithLine,

        /**
         * The sign-in's key is held: the main screen with its error state
         * (Connection Error, the reason, Try Again), and the rejected-key
         * prompt once the launch gate passes (Apple parity, owner 2026-11-12).
         * Never the add-playlist screen while a playlist exists.
         */
        MainWithError,

        /** Anything else: on to add a playlist, as before. */
        AddPlaylist,
    }

    /** [routeHeld]: the active Child is held at this address; [keyHeld]: the gate holds this playlist's key (its own or a profile's). */
    fun launchFailed(routeHeld: Boolean, keyHeld: Boolean): LaunchFailed = when {
        routeHeld -> LaunchFailed.StayWithLine
        keyHeld -> LaunchFailed.MainWithError
        else -> LaunchFailed.AddPlaylist
    }

    /**
     * The key-held launch's error state shows in place of the channel list
     * while nothing is loaded; channels that land replace it.
     */
    fun showsLaunchError(launchError: String?, hasChannels: Boolean): Boolean = launchError != null && !hasChannels

    /**
     * Channels landed from a later refresh (the hold lifted, a recovery):
     * a launch still on the Setting Up screen finishes there, so it never
     * stays up over a loaded list. Any other phase is left as it is.
     */
    fun phaseAfterChannelsLanded(current: PlaylistViewModel.Phase, hasChannels: Boolean): PlaylistViewModel.Phase =
        if (current == PlaylistViewModel.Phase.Bootstrapping && hasChannels) PlaylistViewModel.Phase.ChannelsReady else current
}
