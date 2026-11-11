package com.aeriotv.android.core.setup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupProtocolTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun x25519MatchesRfc7748Vectors() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePub = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        assertArrayEquals(alicePub, X25519.publicKey(alicePriv))
        assertArrayEquals(bobPub, X25519.publicKey(bobPriv))
        assertArrayEquals(shared, X25519.scalarMult(alicePriv, bobPub))
        assertArrayEquals(shared, X25519.scalarMult(bobPriv, alicePub))
    }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val okm = Hkdf.sha256(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    private val payload = SetupProtocol.Payload(
        playlist = SetupProtocol.PlaylistPart(
            type = "dispatcharr",
            name = "Home",
            url = "https://tv.example.com",
            localUrl = "http://192.168.1.10:9191",
            guideDays = 3,
            catchupOffsetMinutes = -60,
            channelProfileIds = listOf(2),
            credential = SetupProtocol.Credential(apiKey = "k1"),
        ),
        profiles = listOf(
            SetupProtocol.ProfilePart(username = "admin", displayName = "Admin", role = "admin", isDefault = true, order = 0),
            SetupProtocol.ProfilePart(
                username = "kid", displayName = "Kid", role = "child", order = 1,
                avatar = SetupProtocol.Avatar("star", "blue"),
                credential = SetupProtocol.Credential(password = "pw"),
                pinType = "numbers", pinHash = "a".repeat(64), pinSalt = "b".repeat(32),
            ),
        ),
        sync = SetupProtocol.SyncPart(),
    )

    @Test
    fun payloadRoundTrips() {
        val back = SetupProtocol.decodePayload(SetupProtocol.encodePayload(payload))
        assertEquals(payload, back)
        assertNull(SetupProtocol.decodePayload("not json".toByteArray()))
    }

    @Test
    fun syncBoxIsPreTickedOnlyWhenDispatcharrSyncIsOnHere() {
        assertTrue(SetupSender.syncPreTicked(isDispatcharr = true, syncOnHere = true))
        // Fold 2026-11-10: Dispatcharr Sync off here, the box was ticked anyway.
        assertTrue(!SetupSender.syncPreTicked(isDispatcharr = true, syncOnHere = false))
        assertTrue(!SetupSender.syncPreTicked(isDispatcharr = false, syncOnHere = true))
    }

    @Test
    fun idsTravelAsPlaylistIdAndProfileIdAndAreOptional() {
        val withIds = payload.copy(
            playlist = payload.playlist.copy(id = "6F1C2A4E-0B7D-4E52-9C1A-2D3E4F5A6B7C"),
            profiles = payload.profiles.mapIndexed { i, p -> p.copy(id = "p$i") },
        )
        val text = SetupProtocol.encodePayload(withIds).toString(Charsets.UTF_8)
        // The wire names the shared spec fixes: playlist.id and profiles[].id.
        val obj = SetupProtocol.json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        assertEquals("6F1C2A4E-0B7D-4E52-9C1A-2D3E4F5A6B7C", (obj["playlist"] as kotlinx.serialization.json.JsonObject)["id"].toString().trim('"'))
        assertEquals("p0", ((obj["profiles"] as kotlinx.serialization.json.JsonArray)[0] as kotlinx.serialization.json.JsonObject)["id"].toString().trim('"'))
        assertEquals(withIds, SetupProtocol.decodePayload(text.toByteArray()))
        // An older sender's payload has no ids: they read as null.
        val old = SetupProtocol.decodePayload(SetupProtocol.encodePayload(payload))!!
        assertNull(old.playlist.id)
        assertNull(old.profiles.first().id)
        assertTrue(!SetupProtocol.encodePayload(payload).toString(Charsets.UTF_8).contains("\"id\""))
    }

    @Test
    fun sealOpenRoundTripAndWrongCode() {
        val tv = SetupCrypto.newKeyPair()
        val env = SetupProtocol.sealForTv(payload, tv.publicKey, "042917")
        val body = SetupProtocol.json.encodeToString(SetupProtocol.Envelope.serializer(), env)
        val ok = SetupProtocol.openOnTv(body, tv, "042917")
        assertTrue(ok is SetupProtocol.OpenResult.Ok)
        assertEquals(payload, (ok as SetupProtocol.OpenResult.Ok).payload)
        assertEquals(SetupProtocol.OpenResult.WrongCode, SetupProtocol.openOnTv(body, tv, "042918"))
        // A different TV key pair cannot open it either.
        assertEquals(SetupProtocol.OpenResult.WrongCode, SetupProtocol.openOnTv(body, SetupCrypto.newKeyPair(), "042917"))
        assertEquals(SetupProtocol.OpenResult.BadRequest, SetupProtocol.openOnTv("{}", tv, "042917"))
    }

    @Test
    fun linkRoundTrips() {
        val pk = SetupCrypto.newKeyPair().publicKey
        val url = SetupProtocol.buildLink("192.168.1.20", 41234, "000123", pk, "Living Room TV")
        val link = SetupProtocol.parseLink(url)
        assertNotNull(link)
        assertEquals("192.168.1.20", link!!.ip)
        assertEquals(41234, link.port)
        assertEquals("000123", link.code)
        assertEquals("Living Room TV", link.name)
        assertArrayEquals(pk, link.pk)
        assertNull(SetupProtocol.parseLink("aeriotv://setup?v=2&ip=1&port=1&code=000000&pk=x"))
    }

    @Test
    fun newCodeIsSixDigits() {
        assertTrue(SetupCrypto.newCode().matches(Regex("\\d{6}")))
    }

    @Test
    fun envelopeVersionMustBeTheNumberOne() {
        val tv = SetupCrypto.newKeyPair()
        val env = SetupProtocol.sealForTv(payload, tv.publicKey, "042917")
        val body = SetupProtocol.json.encodeToString(SetupProtocol.Envelope.serializer(), env)
        assertTrue(body.contains("\"v\":1"))
        val asString = body.replace("\"v\":1", "\"v\":\"1\"")
        val missing = body.replace("\"v\":1,", "")
        assertEquals(SetupProtocol.OpenResult.BadRequest, SetupProtocol.openOnTv(asString, tv, "042917"))
        assertEquals(SetupProtocol.OpenResult.BadRequest, SetupProtocol.openOnTv(missing, tv, "042917"))
        assertEquals(SetupProtocol.OpenResult.BadRequest, SetupProtocol.openOnTv(body.replace("\"v\":1", "\"v\":2"), tv, "042917"))
        assertTrue(SetupProtocol.openOnTv(body, tv, "042917") is SetupProtocol.OpenResult.Ok)
    }

    @Test
    fun linkAcceptsEveryBase64Variant() {
        // 0xfb 0xff gives "+/" in standard base64 and "-_" in base64url.
        val pk = ByteArray(32) { if (it % 2 == 0) 0xfb.toByte() else 0xff.toByte() }
        val std = java.util.Base64.getEncoder().encodeToString(pk)
        assertTrue(std.contains('+') && std.contains('/') && std.endsWith("="))
        val url = java.util.Base64.getUrlEncoder().encodeToString(pk)
        for (v in listOf(std, std.trimEnd('='), url, url.trimEnd('='))) {
            val link = SetupProtocol.parseLink("aeriotv://setup?v=1&ip=192.168.1.20&port=41234&code=000123&pk=$v&name=TV")
            assertNotNull(v, link)
            assertArrayEquals(pk, link!!.pk)
        }
    }

    @Test
    fun bodyWithoutLengthIsCapped() = kotlinx.coroutines.runBlocking {
        val max = SetupReceiver.MAX_BODY
        val ok = ByteArray(max.toInt()) { 'a'.code.toByte() }
        assertEquals(max.toInt(), SetupReceiver.readCapped(io.ktor.utils.io.ByteReadChannel(ok), max)!!.size)
        assertNull(SetupReceiver.readCapped(io.ktor.utils.io.ByteReadChannel(ByteArray(max.toInt() + 1)), max))
    }

    @Test
    fun userOnlyCredentialKeepsUsernameBesideApiKey() {
        val c = userOnlyCredential("key123", "alex", null)!!
        assertEquals("key123", c.apiKey)
        assertEquals("alex", c.username)
        assertNull(c.password)
        assertEquals("alex", userOnlyCredential(null, "alex", "pw")!!.username)
        assertNull(userOnlyCredential(null, "alex", null))
    }
}
