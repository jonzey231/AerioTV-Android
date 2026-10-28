package com.aeriotv.android.core.qr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Restore offer after a QR sign-in: does the existing App Data list
 * (GET /api/accounts/users/me/app-data/, body {"apps":[{"key":...,"bytes":...}]})
 * hold AerioTV's main Dispatcharr Sync key? Pure JVM for the unit tests.
 */
object DsyncRestoreProbe {
    const val MAIN_KEY = "app.molinete.aerio"
    private val json = Json { ignoreUnknownKeys = true }

    fun listsMainKey(body: String?): Boolean = runCatching {
        val root = json.parseToJsonElement(body.orEmpty()) as? JsonObject ?: return false
        (root["apps"] as? JsonArray).orEmpty().any { el ->
            ((el as? JsonObject)?.get("key") as? JsonPrimitive)?.takeIf { it.isString }?.content == MAIN_KEY
        }
    }.getOrDefault(false)
}
