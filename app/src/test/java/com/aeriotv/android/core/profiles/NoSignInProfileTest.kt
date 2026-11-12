package com.aeriotv.android.core.profiles

import com.aeriotv.android.core.network.HeldRouteGate
import com.aeriotv.android.core.network.heldRouteRefusal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A non-default profile with no stored sign-in (2026-11-11, security) never
 * runs on the playlist's own key or password: it is treated exactly like a
 * held profile. Picking it opens the sign-in form; reopened, a Child stays
 * with nothing sent to the playlist's addresses and the unreachable line,
 * and anyone else leaves for the default profile.
 */
class NoSignInProfileTest {

    private val base = "https://dispatcharr.example.test"
    private fun profile(apiKey: String?, password: String?, role: ProfileRole? = null, isDefault: Boolean = false) =
        DispatcharrProfile(id = "p1", playlistId = "P1", displayName = "kid", username = "kid", isDefault = isDefault, apiKey = apiKey, password = password, userLevel = 1, role = role)

    private fun overlay(apiKey: String?) = ProfileScope.Overlay(
        playlistId = "P1", profileId = "p1", apiKey = apiKey, username = "kid", password = null,
        userLevel = 1, dvrAccess = "", catchupEnabled = true, vodMoviesEnabled = true, vodSeriesEnabled = true,
        accountProfileIds = "", isStaff = false, isSuperuser = false, customProperties = "",
        capabilitiesFetchedAt = 0L, capabilitiesSchema = 0, capabilitiesStale = false,
    )

    @After fun reset() {
        ProfileScope.sealed = emptySet()
        ProfileScope.sealedRoutes = emptySet()
        ProfileScope.held = emptySet()
        ProfileScope.activate(null)
    }

    @Test fun noStoredSignInIsTreatedLikeHeld() {
        assertTrue(ProfileHostHold.hasNoSignIn(profile(null, null)))
        assertTrue(ProfileHostHold.hasNoSignIn(profile(" ", "")))
        assertTrue(ProfileHostHold.signInUnusable(profile(null, null), held = false))
        // A stored key or password is a sign-in; the default profile is never this.
        assertFalse(ProfileHostHold.signInUnusable(profile("k", null), held = false))
        assertFalse(ProfileHostHold.signInUnusable(profile(null, "pw"), held = false))
        assertFalse(ProfileHostHold.signInUnusable(profile(null, null, isDefault = true), held = false))
        // Held stays unusable whatever it stores.
        assertTrue(ProfileHostHold.signInUnusable(profile("k", "pw"), held = true))
        // Reopened: a Child stays (sealed), anyone else leaves for the default profile.
        assertTrue(ProfileHostHold.staysActiveWhenHeld(profile(null, null, ProfileRole.Child).effectiveRole()))
        assertFalse(ProfileHostHold.staysActiveWhenHeld(profile(null, null, ProfileRole.User).effectiveRole()))
    }

    @Test fun anInstalledProfileNeverCarriesThePlaylistsKey() {
        // No profile installed: the playlist's own key.
        assertEquals("own-key", ProfileScope.activeKeyFor("P1", "own-key"))
        // A signed-out profile installed: no key at all, even for a row read under defaultOnly.
        ProfileScope.activate(overlay(null))
        assertNull(ProfileScope.activeKeyFor("P1", "own-key"))
        assertNull(ProfileScope.overlayFor("P1")!!.apiKey)
        // A signed-in profile: its own key. Another playlist keeps its own.
        ProfileScope.activate(overlay("kid-key"))
        assertEquals("kid-key", ProfileScope.activeKeyFor("P1", "own-key"))
        assertEquals("other-key", ProfileScope.activeKeyFor("P2", "other-key"))
        // Sealed: none.
        ProfileScope.sealed = setOf("p1")
        assertNull(ProfileScope.activeKeyFor("P1", "own-key"))
    }

    @Test fun aSignedOutChildSendsNothingToThePlaylistsAddresses() {
        // What ProfileManager does for a reopened Child with no stored sign-in:
        // seal it at the playlist's routes (not held), then install it.
        ProfileScope.sealed = setOf("p1")
        ProfileScope.sealedRoutes = ProfileHostHold.routes(base, "http://192.168.1.5:9191")
        ProfileScope.activate(overlay(null))
        assertNotNull(ProfileScope.overlayFor("P1"))
        val refusal = heldRouteRefusal("$base/api/channels/channels/", bypass = false)
        assertEquals("Could not reach your Dispatcharr server", refusal?.message)
        assertTrue(HeldRouteGate.refuses("http://192.168.1.5:9191/api/channels/logos/1/cache/"))
        // Its own sign-in form still gets through.
        assertNull(heldRouteRefusal("$base/api/accounts/token/", bypass = true))
    }
}
