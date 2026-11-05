package com.aeriotv.android.core.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Per-sign-in nonce for Sign in with Google, the Credential Manager
 * equivalent of the OAuth state parameter. A fresh random raw nonce is made
 * for every sign-in, its SHA-256 hex goes into the request, and the returned
 * ID token must carry that same value in its nonce claim. A token minted for
 * a different request (replayed or injected) fails the match and is rejected.
 */
object SignInNonce {
    private const val RAW_BYTES = 32
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    /** New raw nonce: 32 random bytes, base64url without padding. */
    fun newRaw(): String {
        val bytes = ByteArray(RAW_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** SHA-256 hex of the raw nonce; this is what is sent via setNonce. */
    fun hashed(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** The nonce claim from an ID token's payload, or null if absent or malformed. */
    fun nonceClaim(idToken: String): String? = runCatching {
        val parts = idToken.split('.')
        if (parts.size < 2) return null
        val payload = String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')), Charsets.UTF_8)
        (json.parseToJsonElement(payload) as? JsonObject)?.get("nonce")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /** True when [idToken]'s nonce claim equals the hash sent for [raw]. Constant-time compare. */
    fun matches(idToken: String, raw: String): Boolean {
        val claim = nonceClaim(idToken) ?: return false
        return MessageDigest.isEqual(
            claim.toByteArray(Charsets.UTF_8),
            hashed(raw).toByteArray(Charsets.UTF_8),
        )
    }
}
