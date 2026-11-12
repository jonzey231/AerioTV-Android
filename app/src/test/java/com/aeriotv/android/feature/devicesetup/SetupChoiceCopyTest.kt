package com.aeriotv.android.feature.devicesetup

import org.junit.Assert.assertEquals
import org.junit.Test

/** The setup choices' words, the same on every platform (owner 2026-11-12). */
class SetupChoiceCopyTest {
    @Test fun theChoicesNameWhatASetupCarries() {
        assertEquals("Copy this playlist and its profiles", SetupChoiceCopy.PLAYLIST_AND_PROFILES)
        assertEquals("Import this user only", SetupChoiceCopy.USER_ONLY)
    }
}
