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
