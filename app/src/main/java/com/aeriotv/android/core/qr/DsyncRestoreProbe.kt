package com.aeriotv.android.core.qr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Restore offer after a QR sign-in: does the existing App Data list
 * (GET /api/accounts/users/me/app-data/, body {"apps":[{"key":...,"bytes":...}]})
 * hold AerioTV's main Dispatcharr Sync key? That is `<user>.aeriotv`
 * (DsyncKeyName), or the legacy `app.molinete.aerio` a not yet migrated
 * record still uses. Runs before the username is read, so any user name
 * counts. Pure JVM for the unit tests.
 */
object DsyncRestoreProbe {
    const val LEGACY_KEY = "app.molinete.aerio"
    const val SUFFIX = ".aeriotv"

    fun isMainKey(key: String): Boolean = key == LEGACY_KEY || (key.endsWith(SUFFIX) && key.length > SUFFIX.length)
    private val json = Json { ignoreUnknownKeys = true }

    fun listsMainKey(body: String?): Boolean = runCatching {
        val root = json.parseToJsonElement(body.orEmpty()) as? JsonObject ?: return false
        (root["apps"] as? JsonArray).orEmpty().any { el ->
            ((el as? JsonObject)?.get("key") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::isMainKey) == true
        }
    }.getOrDefault(false)
}
