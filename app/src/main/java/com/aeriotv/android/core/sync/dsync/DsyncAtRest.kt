package com.aeriotv.android.core.sync.dsync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The TMDB key at rest (schema section 4.13; hardening 2026-11-13). The
 * manager keeps the server's copy of each category in its state file
 * (aerio_dsync) to merge, compare, and round-trip it, and the tmdbKey
 * category carries the user's key. Before a copy is written there the key
 * is sealed with the device's key ([seal], CredentialCipher, which also
 * encrypts it in the settings store), and a read opens it again ([open]),
 * so every reader sees the server's value and the file never holds it in
 * plain text. A copy whose key cannot be sealed or opened loses its tmdbKey
 * category (or, for the category's own entries, is not kept), so a failure
 * never writes the key in the clear and never sends a sealed value or an
 * empty key to the server. Pure, so the unit test pins it.
 */
object DsyncAtRest {
    private const val API_KEY = "apiKey"
    private const val DATA = "data"

    /** The tmdbKey data with its key sealed; null when it cannot be. No key or "" is nothing secret. */
    fun sealData(data: JsonObject, seal: (String) -> String?): JsonObject? = mapKey(data, seal)

    /** The tmdbKey data with its key opened; null when it cannot be. A plain value (an older build's) reads as is. */
    fun openData(data: JsonObject, open: (String) -> String?): JsonObject? = mapKey(data, open)

    /** A tmdbKey category sub-object ({updatedAt, deviceId, data}) with its key sealed, or null. */
    fun sealCategory(sub: JsonObject, seal: (String) -> String?): JsonObject? = mapData(sub, seal)

    /** A tmdbKey category sub-object with its key opened, or null. */
    fun openCategory(sub: JsonObject, open: (String) -> String?): JsonObject? = mapData(sub, open)

    /** A whole saved bucket with the tmdbKey category's key sealed; the category is left out when it cannot be. */
    fun sealBucket(raw: JsonObject, seal: (String) -> String?): JsonObject = mapBucket(raw, seal)

    /** A whole saved bucket with the tmdbKey category's key opened; the category is left out when it cannot be. */
    fun openBucket(raw: JsonObject, open: (String) -> String?): JsonObject = mapBucket(raw, open)

    private fun mapKey(data: JsonObject, f: (String) -> String?): JsonObject? {
        val v = (data[API_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return data
        if (v.isEmpty()) return data
        val out = f(v) ?: return null
        return JsonObject(LinkedHashMap(data).apply { put(API_KEY, JsonPrimitive(out)) })
    }

    private fun mapData(sub: JsonObject, f: (String) -> String?): JsonObject? {
        val data = sub[DATA] as? JsonObject ?: return sub
        val mapped = mapKey(data, f) ?: return null
        return JsonObject(LinkedHashMap(sub).apply { put(DATA, mapped) })
    }

    private fun mapBucket(raw: JsonObject, f: (String) -> String?): JsonObject {
        val cats = raw[DsyncV2.CATEGORIES] as? JsonObject ?: return raw
        val sub = cats[DsyncKey.TmdbKey.suffix] as? JsonObject ?: return raw
        val mapped = mapData(sub, f)
        val newCats = LinkedHashMap(cats).apply {
            if (mapped == null) remove(DsyncKey.TmdbKey.suffix) else put(DsyncKey.TmdbKey.suffix, mapped)
        }
        return JsonObject(LinkedHashMap(raw).apply { put(DsyncV2.CATEGORIES, JsonObject(newCats)) })
    }
}
