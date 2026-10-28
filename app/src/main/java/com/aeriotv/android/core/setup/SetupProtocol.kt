package com.aeriotv.android.core.setup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Wire format for "Set up a TV from your phone". The authoritative spec is
 * docs/DeviceSetupHandoff.md (identical in both repos); keep the two in step.
 * Pure JVM so the unit tests cover encode, decode, and the crypto round trip.
 */
object SetupProtocol {
    const val VERSION = 1
    const val SERVICE_TYPE = "_aeriotv-setup._tcp"
    const val PATH = "/setup"
    const val TXT_VERSION = "v"
    const val TXT_NAME = "name"
    const val TXT_PK = "pk"
    const val WINDOW_MS = 10L * 60L * 1000L
    const val MAX_FAILURES = 3

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    // ---- QR / deep link ----

    data class Link(val ip: String, val port: Int, val code: String, val pk: ByteArray, val name: String)

    fun buildLink(ip: String, port: Int, code: String, pk: ByteArray, name: String): String =
        "aeriotv://setup?v=$VERSION&ip=$ip&port=$port&code=$code&pk=${SetupCrypto.b64Url(pk)}" +
            "&name=${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}"

    fun parseLink(raw: String): Link? = runCatching {
        val uri = URI(raw.trim())
        if (!uri.scheme.equals("aeriotv", true) || !uri.host.equals("setup", true)) return null
        val q = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        if (q["v"]?.toIntOrNull() != VERSION) return null
        val code = q["code"]?.takeIf { it.length == 6 && it.all(Char::isDigit) } ?: return null
        val pk = q["pk"]?.let(SetupCrypto::unb64)?.takeIf { it.size == 32 } ?: return null
        val port = q["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val ip = q["ip"]?.takeIf { it.isNotBlank() } ?: return null
        Link(ip, port, code, pk, q["name"].orEmpty())
    }.getOrNull()

    // ---- envelope (the POST body) ----

    @Serializable
    data class Envelope(val v: Int = VERSION, val phonePk: String, val nonce: String, val ciphertext: String)

    @Serializable
    data class Reply(val ok: Boolean, val error: String? = null)

    // ---- payload (the plaintext inside the envelope) ----

    @Serializable
    data class Payload(
        val playlist: PlaylistPart,
        val profiles: List<ProfilePart> = emptyList(),
        val sync: SyncPart? = null,
    )

    @Serializable
    data class PlaylistPart(
        /** "dispatcharr", "xtream", or "m3u". */
        val type: String,
        val name: String,
        val url: String,
        val xmltvUrl: String? = null,
        val localUrl: String? = null,
        val userAgent: String? = null,
        val guideDays: Int = 7,
        val catchupOffsetMinutes: Int = 0,
        val vodEnabled: Boolean = true,
        val channelProfileIds: List<Int> = emptyList(),
        /** The playlist's own sign-in (the default profile on Dispatcharr; the XC login). */
        val credential: Credential? = null,
    )

    @Serializable
    data class Credential(val apiKey: String? = null, val username: String? = null, val password: String? = null)

    @Serializable
    data class Avatar(val icon: String = "initial", val color: String = "")

    @Serializable
    data class ProfilePart(
        val username: String,
        val displayName: String,
        /** "admin", "user", or "child"; "" keeps the server-level default. */
        val role: String = "",
        val avatar: Avatar? = null,
        val order: Int = -1,
        /** True for the profile that signs in with the playlist credential. */
        val isDefault: Boolean = false,
        /** Absent on the default profile (it uses playlist.credential). */
        val credential: Credential? = null,
        /** "numbers", "directions", or "none". */
        val pinType: String = "none",
        val pinHash: String = "",
        val pinSalt: String = "",
    )

    @Serializable
    data class SyncPart(val provider: String = "dispatcharr", val enabled: Boolean = true)

    fun encodePayload(p: Payload): ByteArray = json.encodeToString(Payload.serializer(), p).toByteArray(Charsets.UTF_8)

    fun decodePayload(bytes: ByteArray): Payload? =
        runCatching { json.decodeFromString(Payload.serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull()

    // ---- both halves of the exchange ----

    /** Phone side: seal [payload] for the TV whose public key is [tvPk]. */
    fun sealForTv(payload: Payload, tvPk: ByteArray, code: String, random: SecureRandom = SecureRandom()): Envelope {
        val phone = SetupCrypto.newKeyPair(random)
        val key = SetupCrypto.deriveKey(phone.privateKey, tvPk, code, phone.publicKey, tvPk)
        val sealed = SetupCrypto.seal(key, encodePayload(payload), random)
        return Envelope(
            phonePk = SetupCrypto.b64(phone.publicKey),
            nonce = SetupCrypto.b64(sealed.nonce),
            ciphertext = SetupCrypto.b64(sealed.ciphertext),
        )
    }

    sealed interface OpenResult {
        data class Ok(val payload: Payload) : OpenResult
        /** Decrypt failed: wrong code (or tampering). Counts as a failed attempt. */
        data object WrongCode : OpenResult
        /** Not a version-1 envelope or unparseable. Does not count. */
        data object BadRequest : OpenResult
    }

    /** TV side: open an envelope with the TV key pair and the code on screen. */
    fun openOnTv(body: String, tv: SetupCrypto.KeyPair, code: String): OpenResult {
        val env = runCatching { json.decodeFromString(Envelope.serializer(), body) }.getOrNull()
            ?: return OpenResult.BadRequest
        if (env.v != VERSION) return OpenResult.BadRequest
        val phonePk = SetupCrypto.unb64(env.phonePk)?.takeIf { it.size == 32 } ?: return OpenResult.BadRequest
        val nonce = SetupCrypto.unb64(env.nonce)?.takeIf { it.size == 12 } ?: return OpenResult.BadRequest
        val ct = SetupCrypto.unb64(env.ciphertext)?.takeIf { it.size > 16 } ?: return OpenResult.BadRequest
        val key = runCatching { SetupCrypto.deriveKey(tv.privateKey, phonePk, code, phonePk, tv.publicKey) }
            .getOrNull() ?: return OpenResult.BadRequest
        val plain = SetupCrypto.open(key, nonce, ct) ?: return OpenResult.WrongCode
        return decodePayload(plain)?.let { OpenResult.Ok(it) } ?: OpenResult.BadRequest
    }
}
