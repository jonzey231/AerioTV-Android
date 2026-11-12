package com.aeriotv.android.core.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 13 (Apple parity, security, 2026-11-11): Edit Playlist shows and
 * edits the playlist's own (default) sign-in, whatever profile is active,
 * and never writes a non-default profile's.
 */
class EditPlaylistSignInTest {

    private val own = ProfileHostHold.SignIn("parent-key", "parent", "parent-pass")
    private val kid = ProfileHostHold.SignIn("kid-key", "kid", "kid-pass")

    @Test
    fun theFormShowsTheDefaultsSignInWithAnotherProfileActive() {
        // The form reads the playlist's own row, never the active profile's overlay.
        assertEquals(own, EditPlaylistSignIn.shown(own))
        assertEquals(ProfileHostHold.SignIn(null, null, null), EditPlaylistSignIn.shown(null))
    }

    @Test
    fun withAnotherProfileActiveTheSaveKeepsItsSignInAndWritesOnlyTheOwnRow() {
        val asProfile = EditPlaylistSignIn.savesAsProfile(isActivePlaylist = true, profileInstalled = true, routeChanged = false)
        assertTrue(asProfile)
        // A new username and password typed for the playlist itself.
        val typed = ProfileHostHold.SignIn(null, "newparent", "new-pass")
        assertTrue(EditPlaylistSignIn.ownChanged(typed, own))
        // The rest of the save carries the active profile's sign-in, unchanged.
        assertEquals(kid, EditPlaylistSignIn.forSave(typed, kid, asProfile))
        // The playlist's own row gets the typed sign-in and the key it resolved to.
        assertEquals(
            ProfileHostHold.SignIn("new-key", "newparent", "new-pass"),
            EditPlaylistSignIn.ownAfter(typed, "new-key", own),
        )
    }

    @Test
    fun aKeyTypedInApiKeyModeLandsOnTheOwnRowAndKeepsTheStoredPair() {
        val typed = ProfileHostHold.SignIn("fresh-key", null, null)
        assertTrue(EditPlaylistSignIn.ownChanged(typed, own))
        assertEquals(ProfileHostHold.SignIn("fresh-key", "parent", "parent-pass"), EditPlaylistSignIn.ownAfter(typed, "fresh-key", own))
    }

    @Test
    fun anUnchangedOwnSignInWritesNothing() {
        assertFalse(EditPlaylistSignIn.ownChanged(ProfileHostHold.SignIn(" parent-key ", null, null), own))
        assertFalse(EditPlaylistSignIn.ownChanged(ProfileHostHold.SignIn(null, "parent", "parent-pass"), own))
        assertFalse(EditPlaylistSignIn.ownChanged(ProfileHostHold.SignIn("", " ", ""), own))
    }

    @Test
    fun everyOtherSaveRunsOnTheOwnRowWithWhatWasTyped() {
        val typed = ProfileHostHold.SignIn("fresh-key", null, null)
        // The default profile is active (nothing installed), the playlist is not
        // the active one, or the address moves (every non-default profile held).
        for ((active, installed, moved) in listOf(Triple(true, false, false), Triple(false, false, false), Triple(true, true, true))) {
            val asProfile = EditPlaylistSignIn.savesAsProfile(active, installed, moved)
            assertFalse(asProfile)
            assertEquals(typed, EditPlaylistSignIn.forSave(typed, kid.takeIf { installed }, asProfile))
        }
    }
}
