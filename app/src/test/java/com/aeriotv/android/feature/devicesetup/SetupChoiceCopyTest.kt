package com.aeriotv.android.feature.devicesetup

import org.junit.Assert.assertEquals
import org.junit.Test

/** The setup choices' words, the same on every platform (owner 2026-11-12). */
class SetupChoiceCopyTest {
    @Test fun theChoicesNameWhatASetupCarries() {
        assertEquals("Copy this playlist and its profiles", SetupChoiceCopy.PLAYLIST_AND_PROFILES)
        assertEquals("Import this user only", SetupChoiceCopy.USER_ONLY)
    }

    @Test fun theUserQrOfferNamesOnePlaylist() {
        assertEquals(
            "Copy the playlist and its profiles from Living Room, or add just the user in this code.",
            SetupChoiceCopy.userQrOffer("Living Room"),
        )
    }
}
