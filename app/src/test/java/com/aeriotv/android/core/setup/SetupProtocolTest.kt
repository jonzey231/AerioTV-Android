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
}
