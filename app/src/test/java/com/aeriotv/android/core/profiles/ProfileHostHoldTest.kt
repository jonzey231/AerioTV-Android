package com.aeriotv.android.core.profiles

import com.aeriotv.android.core.profiles.ProfileHostHold.Answer
import com.aeriotv.android.core.profiles.ProfileHostHold.Identity
import com.aeriotv.android.core.profiles.ProfileHostHold.SignIn
import com.aeriotv.android.core.profiles.ProfileHostHold.Verdict
import com.aeriotv.android.feature.settings.KeyRejectedCopy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile sign-ins after a server address change (A-APP-1, shared spec with
 * Apple 2026-11-11): route-change detection, the same-server decision, the
 * frozen identity, the gates that keep a held sign-in from going out, and the
 * hold surviving a relaunch.
 */
class ProfileHostHoldTest {

    private class MemoryBacking(var value: String? = null) : HostHoldBook.Backing {
        var writes = 0
        override fun read(): String? = value
        override fun write(value: String) { this.value = value; writes++ }
    }

    private val admin = Identity(7, "admin")
    private val oldServer = "https://tv.example.test"
    private val newServer = "https://other.example.test"
    private val oldLan = "http://192.168.1.10:9191"

    private fun profile(id: String, isDefault: Boolean = false, key: String? = "key-$id", password: String? = null) =
        DispatcharrProfile(id = id, playlistId = "P1", displayName = id, username = "user-$id", isDefault = isDefault, apiKey = key, password = password)

    private fun overlay(id: String) = ProfileScope.Overlay(
        playlistId = "P1", profileId = id, apiKey = "key-$id", username = "user-$id", password = "pw",
        userLevel = 1, dvrAccess = "", catchupEnabled = true, vodMoviesEnabled = true, vodSeriesEnabled = true,
        accountProfileIds = "", isStaff = false, isSuperuser = false, customProperties = "",
        capabilitiesFetchedAt = 0L, capabilitiesSchema = 0, capabilitiesStale = false,
    )

    private val stranger = Identity(42, "stranger")

    /**
     * A book with [admin] recorded at the old address, profiles B and C
     * tagged there (signed in at that server), and both held after a move.
     */
    private fun heldBook(backing: MemoryBacking = MemoryBacking()): HostHoldBook {
        val book = HostHoldBook(backing)
        assertTrue(book.record("P1", admin, ProfileHostHold.routeKey(oldServer)))
        book.tagUntagged(listOf("B", "C"), admin)
        assertNotNull(book.hold("P1", setOf("B", "C"), ProfileHostHold.routes(oldServer, oldLan)))
        return book
    }

    @After fun reset() {
        ProfileScope.held = emptySet()
        ProfileScope.activate(null)
    }

    // ---- Route change detection ----

    @Test fun schemeHostAndPortChangesAreRouteChanges() {
        assertTrue(ProfileHostHold.routeChanged("http://tv.example.test", "https://tv.example.test", null, null))
        assertTrue(ProfileHostHold.routeChanged(oldServer, newServer, null, null))
        assertTrue(ProfileHostHold.routeChanged("http://tv.example.test:9191", "http://tv.example.test:9192", null, null))
    }

    @Test fun localUrlAddedChangedOrRemovedIsARouteChange() {
        assertTrue(ProfileHostHold.routeChanged(oldServer, oldServer, null, oldLan))
        assertTrue(ProfileHostHold.routeChanged(oldServer, oldServer, oldLan, "http://192.168.1.11:9191"))
        assertTrue(ProfileHostHold.routeChanged(oldServer, oldServer, oldLan, null))
    }

    @Test fun pathOnlyCaseAndDefaultPortAreNotRouteChanges() {
        assertFalse(ProfileHostHold.routeChanged("https://tv.example.test/a", "https://tv.example.test/b/", oldLan, "$oldLan/x"))
        assertFalse(ProfileHostHold.routeChanged("https://TV.example.test", "https://tv.example.test:443", null, null))
        assertFalse(ProfileHostHold.routeChanged("http://tv.example.test", "tv.example.test:80", null, " "))
    }

    // ---- The same-server decision ----

    @Test fun sameIdAndUsernameIsTheSameServer() {
        assertEquals(Verdict.SameServer, ProfileHostHold.decide(admin, Answer.Me(Identity(7, "admin"))))
    }

    @Test fun anotherIdOrUsernameKeepsTheHold() {
        assertEquals(Verdict.NotSame("different-user"), ProfileHostHold.decide(admin, Answer.Me(Identity(8, "admin"))))
        assertEquals(Verdict.NotSame("different-user"), ProfileHostHold.decide(admin, Answer.Me(Identity(7, "someone"))))
    }

    @Test fun noRecordKeepsTheHold() {
        assertEquals(Verdict.NotSame("no-record"), ProfileHostHold.decide(null, Answer.Me(admin)))
        assertEquals(Verdict.NotSame("no-record"), ProfileHostHold.decide(Identity(0, ""), Answer.Me(admin)))
    }

    @Test fun aFailedCheckKeepsTheHold() {
        assertEquals(Verdict.NotSame("rejected"), ProfileHostHold.decide(admin, Answer.Rejected))
        assertEquals(Verdict.NotSame("network"), ProfileHostHold.decide(admin, Answer.Unreachable))
        assertEquals(Verdict.NotSame("no-key"), ProfileHostHold.decide(admin, Answer.NoKey))
    }

    // ---- The hold and the frozen identity ----

    @Test fun onlyNonDefaultProfilesWithAStoredSignInAreHeld() {
        val held = ProfileHostHold.toHold(
            listOf(profile("A", isDefault = true), profile("B"), profile("C", key = null, password = "pw"), profile("D", key = null)),
        )
        assertEquals(setOf("B", "C"), held)
    }

    @Test fun nothingToHoldPlacesNoHold() {
        val book = HostHoldBook(MemoryBacking())
        assertNull(book.hold("P1", emptySet(), ProfileHostHold.routes(oldServer, null)))
        assertTrue(book.record("P1", Identity(9, "new")))
    }

    @Test fun aDifferentUserAtTheNewAddressNeitherChangesTheRecordNorLifts() {
        val book = heldBook()
        val now = ProfileHostHold.routes(newServer, oldLan)
        repeat(3) {
            val o = book.onAnswer("P1", ProfileHostHold.routeKey(newServer), now, Answer.Me(stranger))
            assertEquals(HostHoldBook.Outcome.Failed("different-user"), o)
            // The record stays the one from before the change, and so does the hold's copy of it.
            assertEquals(admin, book.recorded("P1"))
            assertEquals(admin, book.hold("P1")?.expected())
            assertFalse(book.record("P1", stranger))
            assertFalse(book.allProven("P1", now))
            assertTrue(book.liftMatching("P1", now).isEmpty())
            assertTrue(book.isHeld("B") && book.isHeld("C"))
        }
    }

    @Test fun theSameUserAtTheNewAddressLiftsEveryMatchingProfile() {
        val book = heldBook()
        val now = ProfileHostHold.routes(newServer, oldLan)
        assertEquals(HostHoldBook.Outcome.Proven, book.onAnswer("P1", ProfileHostHold.routeKey(newServer), now, Answer.Me(Identity(7, "admin"))))
        assertTrue(book.allProven("P1", now))
        assertEquals(setOf("B", "C"), book.liftMatching("P1", now))
        assertFalse(book.isHeld("B") || book.isHeld("C"))
        assertNull(book.hold("P1"))
    }

    @Test fun aPassingCheckLiftsOnlyProfilesTaggedWithTheSnapshot() {
        val book = heldBook()
        book.tag("C", stranger)
        book.hold("P1", setOf("D"), emptySet()) // D: never tagged
        assertEquals(setOf("B"), book.liftMatching("P1", ProfileHostHold.routes(oldServer, oldLan)))
        assertTrue(book.isHeld("C") && book.isHeld("D"))
        // Still held, so the record stays frozen.
        assertFalse(book.record("P1", stranger))
    }

    @Test fun movingAwayAddingAProfileThereAndMovingBackKeepsThatProfileHeld() {
        val book = heldBook()
        // Moved to another server: B and C are held. Q is added there and
        // tagged with that server's live answer; the record stays frozen.
        book.tag("Q", stranger)
        val there = ProfileHostHold.routes(newServer, oldLan)
        assertEquals(HostHoldBook.Outcome.Failed("different-user"), book.onAnswer("P1", ProfileHostHold.routeKey(newServer), there, Answer.Me(stranger)))
        assertEquals(admin, book.recorded("P1"))
        // Moved back: Q (it has a stored sign-in now) is held too; the snapshot and trust are unchanged.
        val back = book.hold("P1", setOf("B", "C", "Q"), there)!!
        assertEquals(admin, back.expected())
        val home = ProfileHostHold.routes(oldServer, oldLan)
        assertTrue(book.allProven("P1", home))
        assertEquals(setOf("B", "C"), book.liftMatching("P1", home))
        // Q's key belongs to the other server: it stays held and must sign in again here.
        assertTrue(book.isHeld("Q"))
    }

    @Test fun movingAwayWithNothingHeldThenAddingAndMovingBackKeepsThatProfileHeld() {
        val book = HostHoldBook(MemoryBacking())
        book.record("P1", admin, ProfileHostHold.routeKey(oldServer))
        // No profile to hold: the other server becomes the trusted one.
        assertNull(book.hold("P1", emptySet(), ProfileHostHold.routes(oldServer, null)))
        val there = ProfileHostHold.routes(newServer, null)
        assertEquals(HostHoldBook.Outcome.Recorded, book.onAnswer("P1", ProfileHostHold.routeKey(newServer), there, Answer.Me(stranger)))
        book.tag("Q", stranger)
        // Moving back holds Q against that server's identity; the original server answers as someone else.
        book.hold("P1", setOf("Q"), there)
        val home = ProfileHostHold.routes(oldServer, null)
        assertEquals(HostHoldBook.Outcome.Failed("different-user"), book.onAnswer("P1", ProfileHostHold.routeKey(oldServer), home, Answer.Me(admin)))
        assertTrue(book.isHeld("Q"))
        assertEquals(stranger, book.recorded("P1"))
    }

    /**
     * Every path places the hold (the snapshot) before it sends anything, so a
     * users/me the change itself makes at the new address only ever meets a
     * frozen record: Edit Playlist's save (its validation and the probe after
     * it), the host-change cleanup's re-probe, a setup applied in place, and a
     * Google Drive pull (held before the row is written).
     */
    @Test fun aUsersMeDuringTheChangeNeverUpdatesTheRecordOnAnyPath() {
        for (path in listOf("edit-save-validation", "host-change-reprobe", "setup-in-place", "drive-pull")) {
            val book = HostHoldBook(MemoryBacking())
            book.record("P1", admin, ProfileHostHold.routeKey(oldServer))
            book.tagUntagged(listOf("B"), admin)
            // 1. The change starts: hold first.
            book.hold("P1", ProfileHostHold.toHold(listOf(profile("A", isDefault = true), profile("B"))), ProfileHostHold.routes(oldServer, null))
            // 2. The change's own users/me at the new address, from another server.
            val now = ProfileHostHold.routes(newServer, null)
            book.onAnswer("P1", ProfileHostHold.routeKey(newServer), now, Answer.Me(stranger))
            assertEquals(path, admin, book.recorded("P1"))
            assertEquals(path, admin, book.hold("P1")?.expected())
            // 3. The next check compares with the snapshot, so nothing lifts.
            assertEquals(path, HostHoldBook.Outcome.Failed("different-user"), book.onAnswer("P1", ProfileHostHold.routeKey(newServer), now, Answer.Me(stranger)))
            assertFalse(path, book.allProven("P1", now))
            assertTrue(path, book.liftMatching("P1", now).isEmpty() && book.isHeld("B"))
        }
    }

    @Test fun anAnswerFromAnAddressTheUnheldPlaylistNoLongerUsesIsNotRecorded() {
        val book = HostHoldBook(MemoryBacking())
        book.record("P1", admin, ProfileHostHold.routeKey(oldServer))
        assertEquals(HostHoldBook.Outcome.Ignored, book.onAnswer("P1", ProfileHostHold.routeKey(oldServer), ProfileHostHold.routes(newServer, null), Answer.Me(stranger)))
        assertEquals(admin, book.recorded("P1"))
    }

    @Test fun theRecordIsALiveAnswerOnlyUnheldAndFromAnAddressInUse() {
        val book = HostHoldBook(MemoryBacking())
        book.record("P1", admin, ProfileHostHold.routeKey(oldServer))
        assertEquals(admin, book.liveRecord("P1", ProfileHostHold.routes(oldServer, null)))
        assertNull(book.liveRecord("P1", ProfileHostHold.routes(newServer, null)))
        book.hold("P1", setOf("B"), ProfileHostHold.routes(oldServer, null))
        assertNull(book.liveRecord("P1", ProfileHostHold.routes(oldServer, null)))
    }

    @Test fun profilesFromBeforeTagsGetTheFirstRecordAndKeepTheirOwnTag() {
        val book = HostHoldBook(MemoryBacking())
        book.tag("Q", stranger)
        book.tagUntagged(listOf("B", "Q"), admin)
        assertEquals(admin, book.tagOf("B"))
        assertEquals(stranger, book.tagOf("Q"))
        book.tag("B", null)
        assertNull(book.tagOf("B"))
    }

    @Test fun everyChangedAddressMustAnswerAsTheRecordedUser() {
        val book = heldBook()
        val newLan = "http://192.168.1.20:9191"
        val now = ProfileHostHold.routes(newServer, newLan)
        assertEquals(HostHoldBook.Outcome.Proven, book.onAnswer("P1", ProfileHostHold.routeKey(newServer), now, Answer.Me(admin)))
        assertFalse(book.allProven("P1", now))
        assertEquals(HostHoldBook.Outcome.Failed("network"), book.onAnswer("P1", ProfileHostHold.routeKey(newLan), now, Answer.Unreachable))
        assertFalse(book.allProven("P1", now))
        assertEquals(HostHoldBook.Outcome.Proven, book.onAnswer("P1", ProfileHostHold.routeKey(newLan), now, Answer.Me(admin)))
        assertTrue(book.allProven("P1", now))
    }

    @Test fun anAnswerFromAnAddressNoLongerInUseDecidesNothing() {
        val book = heldBook()
        val now = ProfileHostHold.routes(newServer, oldLan)
        assertEquals(HostHoldBook.Outcome.Ignored, book.onAnswer("P1", ProfileHostHold.routeKey("https://gone.example.test"), now, Answer.Me(admin)))
        assertEquals(HostHoldBook.Outcome.Ignored, book.onAnswer("P1", ProfileHostHold.routeKey(oldLan), now, Answer.Me(admin)))
        assertFalse(book.allProven("P1", now))
    }

    @Test fun noRecordBeforeTheChangeKeepsTheHold() {
        val book = HostHoldBook(MemoryBacking())
        book.hold("P1", setOf("B"), ProfileHostHold.routes(oldServer, null))
        val now = ProfileHostHold.routes(newServer, null)
        assertEquals(HostHoldBook.Outcome.Failed("no-record"), book.onAnswer("P1", ProfileHostHold.routeKey(newServer), now, Answer.Me(admin)))
        assertNull(book.recorded("P1"))
        assertTrue(book.isHeld("B"))
    }

    @Test fun aSecondMoveKeepsTheOriginalTrustAndIdentity() {
        val book = heldBook()
        book.onAnswer("P1", ProfileHostHold.routeKey(newServer), ProfileHostHold.routes(newServer, oldLan), Answer.Me(Identity(42, "stranger")))
        val again = book.hold("P1", setOf("D"), ProfileHostHold.routes(newServer, oldLan))!!
        assertEquals(setOf("B", "C", "D"), again.profileIds)
        assertEquals(ProfileHostHold.routes(oldServer, oldLan), again.trusted)
        assertEquals(admin, again.expected())
    }

    @Test fun movingBackToTheTrustedAddressNeedsNoNewAnswer() {
        val book = heldBook()
        assertTrue(book.allProven("P1", ProfileHostHold.routes(oldServer, oldLan)))
    }

    @Test fun recordingResumesOnlyAfterTheLastHeldProfileSignsInAgain() {
        val book = heldBook()
        assertEquals("P1", book.liftProfile("B"))
        assertTrue(book.isHeld("C"))
        assertFalse(book.record("P1", Identity(42, "stranger")))
        assertEquals(admin, book.recorded("P1"))
        assertEquals("P1", book.liftProfile("C"))
        assertNull(book.hold("P1"))
        // From now on the new address is the trusted one.
        assertTrue(book.record("P1", Identity(42, "stranger")))
        assertEquals(Identity(42, "stranger"), book.recorded("P1"))
        assertEquals(HostHoldBook.Outcome.Recorded, book.onAnswer("P1", ProfileHostHold.routeKey(newServer), ProfileHostHold.routes(newServer, null), Answer.Me(Identity(42, "stranger"))))
    }

    @Test fun deletingThePlaylistClearsItsRecordAndKeepsMovedProfilesHeld() {
        val book = heldBook()
        book.forgetPlaylist("P1", movedProfiles = setOf("C"))
        assertNull(book.recorded("P1"))
        assertFalse(book.isHeld("B"))
        assertTrue(book.isHeld("C"))
        book.forgetPlaylist("P1", movedProfiles = emptySet())
        assertNull(book.hold("P1"))
    }

    // ---- A sync merge moving profiles between playlists that share a server URL ----

    @Test fun mergedPlaylistsWithTheSameLocalUrlMoveNothingToANewAddress() {
        assertFalse(ProfileHostHold.routeChanged(oldServer, oldServer, oldLan, oldLan))
        assertTrue(ProfileHostHold.routeChanged(oldServer, oldServer, oldLan, "http://192.168.1.20:9191"))
        assertTrue(ProfileHostHold.routeChanged(oldServer, oldServer, null, oldLan))
    }

    @Test fun movedProfilesAreCheckedAgainstTheIdentityOfThePlaylistTheyCameFrom() {
        val book = HostHoldBook(MemoryBacking())
        // The winner's own record is someone else entirely; the loser's server answered as admin.
        book.record("W", stranger, ProfileHostHold.routeKey(oldServer))
        book.tag("M", admin)
        val winnerLan = "http://192.168.1.20:9191"
        val rec = book.hold("W", setOf("M"), ProfileHostHold.routes(oldServer, oldLan), expected = admin)!!
        assertEquals(admin, rec.expected())
        val now = ProfileHostHold.routes(oldServer, winnerLan)
        assertEquals(setOf(ProfileHostHold.routeKey(winnerLan)), ProfileHostHold.unproven(now, rec.trusted))
        // Another server behind the winner's local URL: held.
        assertEquals(HostHoldBook.Outcome.Failed("different-user"), book.onAnswer("W", ProfileHostHold.routeKey(winnerLan), now, Answer.Me(stranger)))
        assertTrue(book.liftMatching("W", now).isEmpty())
        // The same server there: the moved profile lifts.
        assertEquals(HostHoldBook.Outcome.Proven, book.onAnswer("W", ProfileHostHold.routeKey(winnerLan), now, Answer.Me(admin)))
        assertEquals(setOf("M"), book.liftMatching("W", now))
    }

    @Test fun aSignInLiftsAProfileFromEveryHoldThatHasIt() {
        val book = HostHoldBook(MemoryBacking())
        book.hold("L", setOf("M"), ProfileHostHold.routes(oldServer, null), expected = admin)
        book.hold("W", setOf("M", "N"), ProfileHostHold.routes(oldServer, null), expected = admin)
        assertEquals("L", book.liftProfile("M"))
        assertFalse(book.isHeld("M"))
        assertNull(book.hold("L"))
        assertEquals(setOf("N"), book.hold("W")?.profileIds)
    }

    // ---- A held sign-in never goes out ----

    @Test fun aHeldProfileIsNeverLaidOverRequestsOrSyncSessions() {
        ProfileScope.activate(overlay("B"))
        assertEquals("B", ProfileScope.token.value)
        ProfileScope.held = setOf("B")
        // Request builders (the playlist DAO overlay) and Dispatcharr Sync
        // sessions (keyed by the token, reading the overlaid row) see only
        // the playlist's own sign-in.
        assertNull(ProfileScope.overlayFor("P1"))
        assertNull(ProfileScope.activeOverlay())
        assertEquals("", ProfileScope.token.value)
        // And it cannot be installed again while held.
        ProfileScope.activate(overlay("B"))
        assertNull(ProfileScope.overlayFor("P1"))
        assertEquals("", ProfileScope.token.value)
        ProfileScope.held = emptySet()
        ProfileScope.activate(overlay("B"))
        assertEquals("key-B", ProfileScope.overlayFor("P1")?.apiKey)
    }

    @Test fun aHeldProfileGivesASwitchOrATokenLoginNothingToSend() {
        val b = profile("B", key = "key-B", password = "pw-B")
        val held = ProfileHostHold.usable(b, held = true)
        assertNull(held.apiKey)
        assertNull(held.password)
        assertEquals(SignIn("key-B", "user-B", "pw-B"), ProfileHostHold.usable(b, held = false))
        // The default profile signs in with the playlist's own sign-in, never held.
        val a = profile("A", isDefault = true, key = "key-A")
        assertEquals("key-A", ProfileHostHold.usable(a, held = true).apiKey)
    }

    @Test fun aHeldProfileIsNeverInASetupPayloadOrAUserQr() {
        val b = profile("B", key = "key-B", password = "pw-B")
        val stripped = ProfileHostHold.withoutHeldSignIn(b, held = true)
        assertNull(stripped.apiKey)
        assertNull(stripped.password)
        assertEquals("user-B", stripped.username)
        assertEquals(b, ProfileHostHold.withoutHeldSignIn(b, held = false))
    }

    @Test fun aSaveCarryingAHeldSignInSendsThePlaylistsOwnInstead() {
        val own = SignIn("key-A", "user-A", "pw-A")
        val held = listOf(SignIn("key-B", "user-B", "pw-B"))
        // Edit Playlist showed the active profile's sign-in: key, password, or username.
        assertEquals(own, ProfileHostHold.scrub(SignIn("key-B", "user-B", null), own, held))
        assertEquals(own, ProfileHostHold.scrub(SignIn(null, "user-B", "pw-B"), own, held))
        assertEquals(own, ProfileHostHold.scrub(SignIn(null, "USER-B", null), own, held))
        // The playlist's own sign-in, or one typed fresh, goes as it is.
        assertEquals(own, ProfileHostHold.scrub(own, own, held))
        val fresh = SignIn("key-new", "user-A", "pw-new")
        assertEquals(fresh, ProfileHostHold.scrub(fresh, own, held))
        assertEquals(fresh, ProfileHostHold.scrub(fresh, own, emptyList()))
    }

    // ---- Persistence ----

    @Test fun theHoldSurvivesARelaunch() {
        val backing = MemoryBacking()
        heldBook(backing)
        val relaunched = HostHoldBook(backing)
        assertTrue(relaunched.isHeld("B") && relaunched.isHeld("C"))
        assertEquals(setOf("B", "C"), relaunched.heldProfileIds())
        assertEquals(admin, relaunched.hold("P1")?.expected())
        assertEquals(ProfileHostHold.routes(oldServer, oldLan), relaunched.hold("P1")?.trusted)
        assertEquals(admin, relaunched.tagOf("B"))
        assertFalse(relaunched.record("P1", Identity(42, "stranger")))
        // What a relaunch restores first: the held profile's overlay never goes back on.
        ProfileScope.held = relaunched.heldProfileIds()
        ProfileScope.activate(overlay("B"))
        assertNull(ProfileScope.overlayFor("P1"))
    }

    @Test fun aLiftSurvivesARelaunchAndTheStoreHoldsNoSecrets() {
        val backing = MemoryBacking()
        heldBook(backing).liftProfile("B")
        assertFalse(HostHoldBook(backing).isHeld("B"))
        assertTrue(HostHoldBook(backing).isHeld("C"))
        val raw = backing.value.orEmpty()
        assertFalse(raw.contains("key-") || raw.contains("pw"))
    }

    @Test fun anUnreadableStoreStartsEmpty() {
        val book = HostHoldBook(MemoryBacking("not json"))
        assertTrue(book.heldProfileIds().isEmpty())
        assertNull(book.recorded("P1"))
    }

    @Test fun aHeldProfileWithNoStoredSignInStillOpensTheFormWithTheLine() {
        val book = heldBook()
        // Held by id: losing its stored key and password does not release it.
        val b = profile("B", key = null, password = null)
        assertTrue(book.isHeld(b.id))
        assertEquals(SignIn(null, "user-B", null), ProfileHostHold.usable(b, held = true))
        // Picking it opens the form with the line above the fields (ProfileManager.switchTo -> HeldProfileSignInHost).
        assertEquals(
            "The server address for Home changed. Sign in again to use this profile.",
            KeyRejectedCopy.hostChanged("Home"),
        )
        // A profile that never had a stored sign-in is not held: it behaves as before.
        assertFalse(profile("E", key = null, password = null).id in ProfileHostHold.toHold(listOf(profile("E", key = null, password = null))))
    }

    // ---- Copy ----

    @Test fun theFormLineNamesThePlaylist() {
        assertEquals(
            "The server address for Home changed. Sign in again to use this profile.",
            KeyRejectedCopy.hostChanged("Home"),
        )
        assertEquals(
            "The server address for this playlist changed. Sign in again to use this profile.",
            KeyRejectedCopy.hostChanged(""),
        )
        assertFalse(KeyRejectedCopy.hostChanged("Home").contains('\u2014'))
    }
}
