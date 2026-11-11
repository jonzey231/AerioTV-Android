package com.aeriotv.android.core.setup

import com.aeriotv.android.core.network.DispatcharrRequestGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fix Login (docs/DeviceSetupHandoff.md): the advert's TXT keys, the phone's
 * card rule, the TV's apply rule (sign-in only, the right playlist, hold
 * lifted), and backward compatibility (no mode = a normal setup).
 */
class FixLoginTest {
    @Before fun setUp() = DispatcharrRequestGate.resetForTests()
    @After fun tearDown() = DispatcharrRequestGate.resetForTests()

    private val pk = ByteArray(32) { (it + 1).toByte() }
    private val pkB64 = SetupCrypto.b64(pk)
    private val tvPid = "6f1c2a4e-0b7d-4e52-9c1a-2d3e4f5a6b7c"

    private fun target(txt: Map<String, String?>) =
        SetupDiscoveryRules.receiverTarget("Living Room TV", "10.0.0.5", 41000, txt) { false }

    // ---- TXT parsing ----

    @Test fun txtWithModeAndPlaylist() {
        val t = target(mapOf("v" to "1", "name" to "Living Room TV", "pk" to pkB64, "kind" to "tv", "mode" to "fixlogin", "pl" to tvPid.uppercase()))
        assertNotNull(t); t!!
        assertEquals(SetupProtocol.MODE_FIX_LOGIN, t.mode)
        assertTrue(t.isFixLogin)
        assertEquals("pl is read lowercase", tvPid, t.playlistId)
        assertEquals("Living Room TV", t.name)
    }

    @Test fun txtWithoutModeIsANormalSetup() {
        val t = target(mapOf("v" to "1", "name" to "TV", "pk" to pkB64))!!
        assertNull(t.mode)
        assertNull(t.playlistId)
        assertFalse(t.isFixLogin)
        assertEquals(SetupProtocol.KIND_TV, t.kind)
        // A blank mode is a normal setup too; an unknown one is kept as read (not offered).
        assertNull(target(mapOf("v" to "1", "pk" to pkB64, "mode" to " "))!!.mode)
        assertEquals("other", target(mapOf("v" to "1", "pk" to pkB64, "mode" to "Other"))!!.mode)
        assertNull(target(mapOf("v" to "1", "pk" to pkB64, "mode" to "fixlogin", "pl" to ""))!!.playlistId)
    }

    @Test fun txtStillNeedsVersionAndKey() {
        assertNull(target(mapOf("v" to "2", "pk" to pkB64, "mode" to "fixlogin", "pl" to tvPid)))
        assertNull(target(mapOf("v" to "1", "pk" to "short", "mode" to "fixlogin", "pl" to tvPid)))
    }

    // ---- Phone: card visibility ----

    private fun local(id: String, account: String? = "alex", held: Boolean = false, cred: Boolean = true, d: Boolean = true) =
        FixLogin.Local(id = id, isDispatcharr = d, account = account, hasCredential = cred, keyHeld = held)

    @Test fun cardMatchesTheSamePlaylistId() {
        val m = FixLogin.matchPlaylist(tvPid, listOf(local("other"), local(tvPid.uppercase())), learnedAccount = null)
        assertEquals("ids compare without case", tvPid.uppercase(), m?.id)
    }

    @Test fun cardFallsBackToTheSameAccount() {
        val locals = listOf(local("a", account = "Alex"), local("b", account = "sam"))
        assertEquals("a", FixLogin.matchPlaylist(tvPid, locals, learnedAccount = "alex")?.id)
        // Two playlists for that account: ambiguous, no card.
        assertNull(FixLogin.matchPlaylist(tvPid, locals + local("c", account = "ALEX"), learnedAccount = "alex"))
        // Nothing learned: no card.
        assertNull(FixLogin.matchPlaylist(tvPid, locals, learnedAccount = null))
    }

    @Test fun cardHiddenWhenThePhonesOwnKeyIsHeldOrMissing() {
        assertNull(FixLogin.matchPlaylist(tvPid, listOf(local(tvPid, held = true)), null))
        assertNull(FixLogin.matchPlaylist(tvPid, listOf(local(tvPid, cred = false)), null))
        assertNull("only Dispatcharr playlists", FixLogin.matchPlaylist(tvPid, listOf(local(tvPid, d = false)), null))
        assertNull("no pl, no card", FixLogin.matchPlaylist(null, listOf(local(tvPid)), null))
    }

    @Test fun offerFollowsModeAndTheSenderGate() {
        val allowed = setOf("active", tvPid)
        fun offer(mode: String?, fix: String?) = FixLogin.offer(mode, "active", fix) { it in allowed }
        assertEquals(FixLogin.Offer.Setup, offer(null, null))
        assertEquals(FixLogin.Offer.Fix(tvPid), offer(SetupProtocol.MODE_FIX_LOGIN, tvPid))
        assertEquals("no matching playlist", FixLogin.Offer.None, offer(SetupProtocol.MODE_FIX_LOGIN, null))
        assertEquals("unknown mode", FixLogin.Offer.None, offer("other", tvPid))
        // Child (or any profile that may not send) on that playlist: no card.
        assertEquals(FixLogin.Offer.None, FixLogin.offer(SetupProtocol.MODE_FIX_LOGIN, "active", tvPid) { it == "active" })
        assertEquals(FixLogin.Offer.None, FixLogin.offer(null, null, null) { true })
        assertEquals("Fix Login", FixLogin.buttonLabel(SetupProtocol.MODE_FIX_LOGIN))
        assertEquals("Set up", FixLogin.buttonLabel(null))
    }

    // ---- Phone: payload ----

    private val playlistPart = SetupProtocol.PlaylistPart(
        id = "phone-id",
        type = "dispatcharr",
        name = "Home",
        url = "https://tv.example.com",
        credential = SetupProtocol.Credential(apiKey = "new-key", username = "alex"),
    )

    @Test fun fixLoginPayloadIsThatPlaylistAndItsSignInOnly() {
        val p = fixLoginPayload(playlistPart, tvPid)!!
        assertEquals(SetupProtocol.MODE_FIX_LOGIN, p.mode)
        assertEquals("addressed to the TV's playlist", tvPid, p.playlist.id)
        assertEquals("new-key", p.playlist.credential?.apiKey)
        assertTrue(p.profiles.isEmpty())
        assertNull(p.sync)
        val json = SetupProtocol.encodePayload(p).toString(Charsets.UTF_8)
        assertTrue(json, json.contains("\"mode\":\"fixlogin\""))
        assertFalse(json, json.contains("\"sync\""))
        assertNull("no sign-in, nothing to send", fixLoginPayload(playlistPart.copy(credential = null), tvPid))
        assertNull(fixLoginPayload(playlistPart.copy(credential = SetupProtocol.Credential(username = "alex")), tvPid))
        assertEquals("Sent. Living Room TV is signing in.", FixLogin.sentLine("Living Room TV"))
    }

    @Test fun fixLoginPayloadSealsAndOpens() {
        val tv = SetupCrypto.newKeyPair()
        val env = SetupProtocol.sealForTv(fixLoginPayload(playlistPart, tvPid)!!, tv.publicKey, "012345")
        val body = SetupProtocol.json.encodeToString(SetupProtocol.Envelope.serializer(), env)
        val r = SetupProtocol.openOnTv(body, tv, "012345") as SetupProtocol.OpenResult.Ok
        assertEquals(SetupProtocol.MODE_FIX_LOGIN, r.payload.mode)
        assertEquals(tvPid, r.payload.playlist.id)
        assertEquals(SetupProtocol.OpenResult.WrongCode, SetupProtocol.openOnTv(body, tv, "999999"))
    }

    // ---- TV: which playlist, and only the sign-in ----

    @Test fun receiverUpdatesOnlyThePlaylistItAskedFor() {
        val here = listOf("aaaa", tvPid)
        assertEquals(tvPid, FixLogin.receiverPlaylistId(tvPid, tvPid.uppercase(), here))
        assertNull("another playlist's sign-in", FixLogin.receiverPlaylistId(tvPid, "aaaa", here))
        assertNull("no id", FixLogin.receiverPlaylistId(tvPid, null, here))
        assertNull("not on this device", FixLogin.receiverPlaylistId(tvPid, tvPid, listOf("aaaa")))
        // A fixlogin payload on a normal screen: the playlist it names, if here.
        assertEquals("aaaa", FixLogin.receiverPlaylistId(null, "AAAA", here))
        assertNull(FixLogin.receiverPlaylistId(null, "zzzz", here))
    }

    @Test fun signInOnlyTheSentFieldsChange() {
        val keyOnly = FixLogin.signInToWrite("old-key", "alex", null, SetupProtocol.Credential(apiKey = " new-key "))!!
        assertEquals("new-key", keyOnly.apiKey)
        assertEquals("username kept", "alex", keyOnly.username)
        assertNull(keyOnly.password)
        assertFalse(keyOnly.accountChanged)

        val all = FixLogin.signInToWrite("old-key", "alex", "old-pw", SetupProtocol.Credential(apiKey = "k2", username = "alex", password = "new-pw"))!!
        assertEquals(Triple("k2", "alex", "new-pw"), Triple(all.apiKey, all.username, all.password))
        assertTrue("password changed: the old session goes", all.accountChanged)

        // Blank fields never replace stored ones.
        val blanks = FixLogin.signInToWrite("old-key", "alex", "pw", SetupProtocol.Credential(apiKey = "k3", username = " ", password = ""))!!
        assertEquals(Triple("k3", "alex", "pw"), Triple(blanks.apiKey, blanks.username, blanks.password))
        assertNull("nothing sent", FixLogin.signInToWrite("old-key", "alex", null, SetupProtocol.Credential()))
        assertNull(FixLogin.signInToWrite("old-key", "alex", null, null))
    }

    @Test fun holdIsLiftedAndOldRequestsUseTheNewKey() {
        DispatcharrRequestGate.markKeyRejected(tvPid, "old-key", "Home", passwordToo = false)
        assertNotNull(DispatcharrRequestGate.prompt.value)
        FixLogin.liftHold(tvPid, "old-key", "new-key")
        assertFalse(DispatcharrRequestGate.isPlaylistRejected(tvPid))
        assertFalse(DispatcharrRequestGate.isKeyRejected("old-key"))
        assertNull("prompt cleared", DispatcharrRequestGate.prompt.value)
        assertEquals("new-key", DispatcharrRequestGate.replacementFor("old-key"))
        // The same key handed back (the server takes it again): hold lifted, no replacement.
        DispatcharrRequestGate.markKeyRejected("p2", "same-key")
        FixLogin.liftHold("p2", "same-key", "same-key")
        assertFalse(DispatcharrRequestGate.isKeyRejected("same-key"))
        assertNull(DispatcharrRequestGate.replacementFor("same-key"))
    }

    // ---- Backward compatibility ----

    @Test fun noModeIsANormalSetup() {
        val old = """{"playlist":{"type":"dispatcharr","name":"Home","url":"https://x","credential":{"apiKey":"k"}},"profiles":[],"sync":{"provider":"dispatcharr","enabled":true}}"""
        val p = SetupProtocol.decodePayload(old.toByteArray())!!
        assertNull(p.mode)
        assertFalse(FixLogin.appliesAsFixLogin(screenPid = null, payloadMode = p.mode))
        // A normal payload never writes a mode key.
        val json = SetupProtocol.encodePayload(p).toString(Charsets.UTF_8)
        assertFalse(json, json.contains("\"mode\""))
        // A Fix Login screen takes only the sign-in, whatever an older sender sent.
        assertTrue(FixLogin.appliesAsFixLogin(screenPid = tvPid, payloadMode = null))
        assertTrue(FixLogin.appliesAsFixLogin(screenPid = null, payloadMode = "FixLogin"))
        // Unknown payload keys are still ignored (readers before this field).
        assertNotNull(SetupProtocol.decodePayload("""{"playlist":{"type":"m3u","name":"","url":"https://x"},"mode":"fixlogin","future":1}""".toByteArray()))
    }
}
