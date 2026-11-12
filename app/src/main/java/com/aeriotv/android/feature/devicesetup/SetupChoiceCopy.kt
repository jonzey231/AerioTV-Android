package com.aeriotv.android.feature.devicesetup

/**
 * The two choices for what a setup sends (Set up another device, and the
 * User QR offer on the receiving device), the same words on every platform.
 * A setup carries only the active playlist, its profiles, and preferences, so
 * the first choice names exactly that (owner 2026-11-12; it read "Copy entire
 * setup from this device", which suggested every playlist).
 */
object SetupChoiceCopy {
    const val PLAYLIST_AND_PROFILES = "Copy this playlist and its profiles"
    const val USER_ONLY = "Import this user only"

    /** The User QR offer's line under "Copy from {name}": one playlist, its profiles, or just the user in the code. */
    fun userQrOffer(deviceName: String): String =
        "Copy the playlist and its profiles from $deviceName, or add just the user in this code."
}
