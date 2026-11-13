package com.aeriotv.android.core.qr

import com.aeriotv.android.core.setup.SetupProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * What a scanned (or imported) QR code means to AerioTV. Two codes are read
 * by the same scanner:
 *
 * 1. The Dispatcharr user API-key QR from the web UI's User modal (Dispatcharr
 *    dev commit 52bdb33e, frontend/src/utils/forms/ApiKeyQrUtils.js). Its text
 *    is JSON: {"v":1,"type":"user_api_key","api_key":"...","base_url":"...",
 *    "username":"...","first_name":"","last_name":"","email":""}. Only type
 *    and v are checked; api_key is required; base_url is the server origin;
 *    the identity fields are advisory and may be blank or trimmed away.
 * 2. An AerioTV TV's setup link (`aeriotv://setup?...`, docs/DeviceSetupHandoff.md).
 *
 * Pure JVM so the unit tests cover parsing. Never log [Result.UserKey.apiKey];
 * its toString redacts it.
 */
object ScannedCode {
    const val INVALID_MESSAGE = "This is not a Dispatcharr user code or an AerioTV setup code."
    const val NO_QR_IN_IMAGE = "No QR code found in that image."

    // ---- Add Profile (Apple f4cf2e9): the user's Dispatcharr QR in API Key mode ----

    /** The camera prompt in Add Profile. */
    const val ADD_PROFILE_PROMPT = "Point the camera at a Dispatcharr user QR code."
    /** An AerioTV setup code scanned in Add Profile. */
    const val SETUP_CODE_IN_ADD_PROFILE = "This is an AerioTV setup code. Scan a Dispatcharr user QR code."
    /** Another server's user code scanned in Add Profile. */
    const val OTHER_SERVER_MESSAGE = "This user code is for a different Dispatcharr server."

    /** The lowercased host of a server address ("" or unparseable: null). */
    fun host(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) s = "http://$s"
        return runCatching { java.net.URI(s).host }.getOrNull()?.lowercase()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Why Add Profile refuses [r], or null to take it: a user code fills the
     * API Key field when its server is this playlist's ([playlistUrls], its
     * server and local URLs). A code names its server by host, so the same
     * server over another scheme or port counts; a code without a server
     * address, or a playlist without one, is taken as is, since Add Profile
     * checks the key with the server anyway.
     */
    fun addProfileProblem(r: Result, playlistUrls: List<String?>): String? = when (r) {
        is Result.UserKey -> {
            val code = host(r.baseUrl)
            val mine = playlistUrls.mapNotNull { host(it) }.toSet()
            if (code == null || mine.isEmpty() || code in mine) null else OTHER_SERVER_MESSAGE
        }
        is Result.SetupLink -> SETUP_CODE_IN_ADD_PROFILE
        Result.Invalid -> INVALID_MESSAGE
    }
    const val USER_KEY_TYPE = "user_api_key"
    const val USER_KEY_VERSION = 1

    sealed interface Result {
        data class UserKey(val apiKey: String, val baseUrl: String?, val username: String?) : Result {
            override fun toString(): String = "UserKey(baseUrl=$baseUrl, username=$username, apiKey=<redacted>)"
        }
        data class SetupLink(val link: String) : Result
        data object Invalid : Result
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parse(raw: String?): Result {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Result.Invalid
        if (text.startsWith("aeriotv:", ignoreCase = true)) {
            return if (SetupProtocol.parseLink(text) != null) Result.SetupLink(text) else Result.Invalid
        }
        return parseUserKey(text) ?: Result.Invalid
    }

    /**
     * The same JSON the Dispatcharr web UI encodes in its user QR, so another
     * AerioTV device (or anything that reads that QR) accepts it unchanged.
     */
    fun userKeyText(apiKey: String, baseUrl: String, username: String): String =
        kotlinx.serialization.json.buildJsonObject {
            put("v", JsonPrimitive(USER_KEY_VERSION))
            put("type", JsonPrimitive(USER_KEY_TYPE))
            put("api_key", JsonPrimitive(apiKey))
            put("base_url", JsonPrimitive(baseUrl.trim().trimEnd('/')))
            put("username", JsonPrimitive(username))
            put("first_name", JsonPrimitive(""))
            put("last_name", JsonPrimitive(""))
            put("email", JsonPrimitive(""))
        }.toString()

    fun parseUserKey(text: String): Result.UserKey? {
        val obj = runCatching { json.parseToJsonElement(text.trim()) }.getOrNull() as? JsonObject ?: return null
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (str("type") != USER_KEY_TYPE) return null
        if ((obj["v"] as? JsonPrimitive)?.intOrNull != USER_KEY_VERSION) return null
        val apiKey = str("api_key")?.takeIf { it.isNotEmpty() } ?: return null
        val rawBase = str("base_url").orEmpty().trimEnd('/')
        val baseUrl = when {
            rawBase.isEmpty() -> null
            rawBase.startsWith("http://", ignoreCase = true) || rawBase.startsWith("https://", ignoreCase = true) -> rawBase
            else -> return null
        }
        return Result.UserKey(apiKey, baseUrl, str("username")?.takeIf { it.isNotEmpty() })
    }
}
