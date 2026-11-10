package com.aeriotv.android.feature.devicesetup

/**
 * Who may act on an `aeriotv://setup` link and when (security, 2026-11-10).
 *
 * The sender hands the other device the playlist with the DEFAULT profile's
 * credential, so it is offered only where Settings offers "Set up another
 * device": an Admin profile or the default profile
 * ([com.aeriotv.android.core.profiles.ProfileManager.canSetUpTv]). A setup QR
 * code scanned with the system camera used to open the sender with no such
 * check, so a Child profile, or anyone holding the phone at the launch PIN
 * screen, could send the playlist and its key.
 */
internal enum class SetupLinkAction {
    /** Open the sender on this link (or hand it to the sheet that is already open). */
    Open,
    /** Keep the link until the app is unlocked and the active playlist is known, then decide again. */
    Hold,
    /** Forget the link without a word. */
    Drop,
}

/**
 * @param isTv a TV is the receiving side and never sends.
 * @param locked the launch PIN screen or the launch picker is up, or a profile
 *   switch is still landing: the profile that will be active is not settled.
 * @param starting the active playlist is not known yet (cold start).
 * @param canSetUpTv the sender gate for the active profile of the active
 *   playlist; false when there is no playlist.
 */
internal fun setupLinkAction(
    isTv: Boolean,
    locked: Boolean,
    starting: Boolean,
    canSetUpTv: Boolean,
): SetupLinkAction = when {
    isTv -> SetupLinkAction.Drop
    locked || starting -> SetupLinkAction.Hold
    canSetUpTv -> SetupLinkAction.Open
    else -> SetupLinkAction.Drop
}

/** The last check before anything is sent: never while locked, never for a profile that may not set up a device. */
internal fun setupSendAllowed(locked: Boolean, canSetUpTv: Boolean): Boolean = !locked && canSetUpTv

/**
 * A newer setup link replaces the device the open sheet is pointed at,
 * except while a send is under way (the key check or the send itself): that
 * send finishes for the device it started with.
 */
internal fun setupLinkMayReplace(step: SetUpTvViewModel.Step): Boolean =
    step !is SetUpTvViewModel.Step.Sending && step !is SetUpTvViewModel.Step.Checking
