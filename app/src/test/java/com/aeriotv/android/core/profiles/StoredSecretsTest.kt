package com.aeriotv.android.core.profiles

import com.aeriotv.android.core.profiles.ProfileHostHold.Answer
import com.aeriotv.android.core.profiles.ProfileHostHold.Identity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A playlist's stored secrets after its server address changed (security
 * fix 2026-11-12, Apple ca72783). The phone's 21:55 and 22:06 logs: "stored
 * key authenticates as 'otherserveruser' ... re-authenticating", then a
 * token POST with the saved username and password to the other server.
 *
 * Every path that can send a stored secret asks [StoredSecrets.check] with
 * its own [StoredSecrets.Path] before it sends; these tests drive that gate
 * per path through the real persisted [HostHoldBook], plus the rules each
 * path applies around it.
 */
class StoredSecretsTest {

    private class MemoryBacking(var value: String? = null) : HostHoldBook.Backing {
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }

    private val admin = Identity(7, "admin")
    private val otherUser = Identity(4242, "otherserveruser")
    private val oldServer = "https://tv.example.test"
    private val newServer = "https://other.example.test"
    private val oldLan = "http://192.168.1.10:9191"
    private val newLan = "http://192.168.1.99:9191"
    private val k = ProfileHostHold::routeKey

    private val logged = mutableListOf<String>()
    private lateinit var backing: MemoryBacking
    private lateinit var book: HostHoldBook

    @Before fun setUp() {
        backing = MemoryBacking()
        book = HostHoldBook(backing)
        assertTrue(book.record("P1", admin, k(oldServer)))
        StoredSecrets.book = book
        StoredSecrets.logSink = { logged += it }
    }

    @After fun tearDown() {
        StoredSecrets.book = null
        StoredSecrets.logSink = null
    }

    /** Edit Playlist (or a Drive pull) moves P1 from the old server to [toBase]. */
    private fun move(toBase: String = newServer, toLan: String? = oldLan): Boolean =
        book.markRouteChange("P1", ProfileHostHold.routes(oldServer, oldLan), ProfileHostHold.routes(toBase, toLan))

    // ---- every path, through the gate ----

    @Test
    fun noPathSendsAStoredSecretToAnUnprovenAddress() {
        assertTrue(move())
        for (path in StoredSecrets.Path.entries) {
            assertFalse(path.label, StoredSecrets.check("P1", newServer, path))
        }
        // One line per refusal, naming the path; never a secret or an address.
        assertEquals(StoredSecrets.Path.entries.size, logged.size)
        StoredSecrets.Path.entries.forEachIndexed { i, p -> assertTrue(logged[i].contains("${p.label} refused for playlist=P1")) }
        assertTrue(logged.none { it.contains("other.example.test") || it.contains("tv.example.test") })
    }

    @Test
    fun identityRepairRunsOnlyWhereTheServerIsProvenTheSame() {
        // No address change: the repair may sign in (the old stale-key case).
        assertTrue(StoredSecrets.check("P1", oldServer, StoredSecrets.Path.IdentityRepair))
        // A change to a server where another user answers: refused, and that
        // answer holds the playlist's own key (the sign-in form).
        move()
        assertFalse(StoredSecrets.check("P1", newServer, StoredSecrets.Path.IdentityRepair))
        val o = book.onAnswer("P1", k(newServer), ProfileHostHold.routes(newServer, oldLan), Answer.Me(otherUser))
        assertEquals(HostHoldBook.Outcome.Failed("different-user"), o)
        assertTrue(StoredSecrets.holdsPlaylistKey("different-user", book.isRouteUnproven("P1")))
        // The other user's answer never becomes the record.
        assertEquals(admin, book.recorded("P1"))
    }

    @Test
    fun silentReloginAndLaunchSignInWaitForTheSameServerCheck() {
        move()
        assertFalse(StoredSecrets.check("P1", newServer, StoredSecrets.Path.SilentRelogin))
        assertFalse(StoredSecrets.check("P1", newServer, StoredSecrets.Path.LaunchSignIn))
        // The default account answers at the new server URL as recorded: proven.
        val o = book.onAnswer("P1", k(newServer), ProfileHostHold.routes(newServer, oldLan), Answer.Me(admin))
        assertEquals(HostHoldBook.Outcome.Proven, o)
        assertFalse(book.isRouteUnproven("P1"))
        assertTrue(StoredSecrets.check("P1", newServer, StoredSecrets.Path.SilentRelogin))
        assertTrue(StoredSecrets.check("P1", newServer, StoredSecrets.Path.LaunchSignIn))
    }

    @Test
    fun aChangedLocalUrlKeepsTheProvenServerUrlUsableAndBlocksTheNewOne() {
        assertTrue(move(toBase = oldServer, toLan = newLan))
        assertTrue(StoredSecrets.check("P1", oldServer, StoredSecrets.Path.SilentRelogin))
        assertFalse(StoredSecrets.check("P1", newLan, StoredSecrets.Path.SilentRelogin))
        // Dispatcharr Sync waits for the whole playlist.
        assertFalse(StoredSecrets.maySync(book.isRouteUnproven("P1")))
        assertEquals(HostHoldBook.Outcome.Proven, book.onAnswer("P1", k(newLan), ProfileHostHold.routes(oldServer, newLan), Answer.Me(admin)))
        assertTrue(StoredSecrets.maySync(book.isRouteUnproven("P1")))
    }

    @Test
    fun editPlaylistSendsOnlyAPasswordTheUserTyped() {
        move()
        val may = StoredSecrets.mayUse("P1", newServer)
        assertFalse(may)
        // The form showed the saved password and nobody touched it: nothing goes.
        assertNull(StoredSecrets.password(typed = null, stored = "saved-pass", mayUseStored = may))
        // Typed: it goes where the user typed it.
        assertEquals("typed-pass", StoredSecrets.password(typed = "typed-pass", stored = "saved-pass", mayUseStored = may))
        // A proven address: the saved one may go.
        assertEquals("saved-pass", StoredSecrets.password(typed = null, stored = "saved-pass", mayUseStored = true))
    }

    @Test
    fun editPlaylistWithNothingTypedNeedsTheOwnKeyToAnswerAsTheRecordedAccount() {
        assertTrue(StoredSecrets.answersAsOwnAccount(admin, "admin", admin))
        // The phone log's case: the stored key answers as another user there.
        assertFalse(StoredSecrets.answersAsOwnAccount(admin, "admin", otherUser))
        // Same name, another server's account (another id): not the same.
        assertFalse(StoredSecrets.answersAsOwnAccount(admin, "admin", Identity(99, "admin")))
        // Nothing recorded: the playlist's username decides; none, the check decides later.
        assertTrue(StoredSecrets.answersAsOwnAccount(null, "Admin", Identity(5, "admin")))
        assertFalse(StoredSecrets.answersAsOwnAccount(null, "admin", otherUser))
        assertTrue(StoredSecrets.answersAsOwnAccount(null, null, otherUser))
    }

    @Test
    fun aTypedSignInProvesTheAddress() {
        move()
        assertTrue(book.markRouteProven("P1"))
        assertFalse(book.isRouteUnproven("P1"))
        assertTrue(StoredSecrets.check("P1", newServer, StoredSecrets.Path.RefreshSession))
        assertFalse(book.markRouteProven("P1"))
        // Recording resumes: the account signed in at the new address becomes the record.
        assertTrue(book.record("P1", otherUser, k(newServer)))
    }

    @Test
    fun aPromptSignInAtTheServerUrlLeavesAChangedLocalUrlUnproven() {
        // The local URL now points at another server; the rejected-key
        // prompt signs in at the server URL with a typed password.
        move(toBase = oldServer, toLan = newLan)
        assertFalse(book.markRouteProven("P1", k(oldServer)))
        assertTrue(book.isRouteUnproven("P1"))
        assertFalse(StoredSecrets.check("P1", newLan, StoredSecrets.Path.SilentRelogin))
        // A typed sign-in at the changed address itself proves it.
        assertTrue(book.markRouteProven("P1", k(newLan)))
        assertFalse(book.isRouteUnproven("P1"))
    }

    @Test
    fun refreshSessionAndFixLoginRefuseWithApplesCopy() {
        move()
        assertFalse(StoredSecrets.check("P1", newServer, StoredSecrets.Path.RefreshSession))
        assertEquals("Enter the password to sign in at the new server address.", StoredSecrets.TYPE_PASSWORD_AT_NEW_ADDRESS)
        assertFalse(StoredSecrets.check("P1", newServer, StoredSecrets.Path.FixLogin))
        assertEquals(
            "This playlist's server address changed. Sign in on this device to use the new address.",
            StoredSecrets.FIX_LOGIN_NEW_ADDRESS,
        )
        assertEquals(StoredSecrets.FIX_LOGIN_NEW_ADDRESS, StoredSecrets.Refused(StoredSecrets.FIX_LOGIN_NEW_ADDRESS).message)
    }

    @Test
    fun onlyAnotherAccountHoldsThePlaylistKey() {
        assertTrue(StoredSecrets.holdsPlaylistKey("different-user", routeUnproven = true))
        // A refused key, no answer, or no record leave it to the usual paths.
        assertFalse(StoredSecrets.holdsPlaylistKey("rejected", routeUnproven = true))
        assertFalse(StoredSecrets.holdsPlaylistKey("network", routeUnproven = true))
        assertFalse(StoredSecrets.holdsPlaylistKey("no-record", routeUnproven = true))
        // A proven route: the profile hold's own rules apply, not this one.
        assertFalse(StoredSecrets.holdsPlaylistKey("different-user", routeUnproven = false))
    }

    // ---- the persisted state ----

    @Test
    fun anUnprovenRouteSurvivesARelaunch() {
        move()
        val relaunched = HostHoldBook(MemoryBacking(backing.value))
        assertTrue(relaunched.isRouteUnproven("P1"))
        StoredSecrets.book = relaunched
        assertFalse(StoredSecrets.check("P1", newServer, StoredSecrets.Path.LaunchSignIn))
        assertEquals(setOf("P1"), relaunched.unprovenPlaylists())
    }

    @Test
    fun aSecondChangeKeepsTheAddressesTrustedBeforeTheFirst() {
        move(toBase = newServer)
        assertTrue(move(toBase = "https://third.example.test"))
        assertEquals(ProfileHostHold.routes(oldServer, oldLan), book.routeProof("P1")!!.trusted)
        // Moving back onto the original addresses proves it at once.
        assertFalse(book.markRouteChange("P1", ProfileHostHold.routes("https://third.example.test", oldLan), ProfileHostHold.routes(oldServer, oldLan)))
        assertFalse(book.isRouteUnproven("P1"))
    }

    @Test
    fun aFailedSavePutsTheRowBackAndTheRouteSettles() {
        move()
        assertFalse(book.settleRoutes("P1", ProfileHostHold.routes(oldServer, oldLan)))
        assertFalse(book.isRouteUnproven("P1"))
    }

    @Test
    fun nothingTheNewServerAnswersIsRecordedUntilProven() {
        move()
        assertFalse(book.record("P1", otherUser, k(newServer)))
        assertNull(book.liveRecord("P1", ProfileHostHold.routes(newServer, oldLan)))
        assertEquals(admin, book.recorded("P1"))
    }

    @Test
    fun aDeletedPlaylistTakesItsRouteStateWithIt() {
        move()
        book.forgetPlaylist("P1", emptySet())
        assertFalse(book.isRouteUnproven("P1"))
    }

    @Test
    fun noChangeMeansNoGateAndNoBookMeansNothingKnown() {
        assertTrue(StoredSecrets.check("P1", oldServer, StoredSecrets.Path.SilentRelogin))
        StoredSecrets.book = null
        assertTrue(StoredSecrets.mayUse("P1", newServer))
        assertTrue(logged.isEmpty())
    }
}
