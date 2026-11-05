package com.aeriotv.android.core.sync.dsync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Dispatcharr Sync schema v3 (docs/DispatcharrSyncSchema.md): ONE App Data
 * key, `<username>.aeriotv` (DsyncKeyName), so AerioTV uses 1 of a user's 25. It keeps the
 * envelope and holds one sub-object per category under `categories`, each
 * with its own `updatedAt`, `deviceId`, `data`, and optional `extra`, so the
 * per-category last-writer-wins and row merges are unchanged from v1.
 */
enum class DsyncBucket(val local: String, val keys: List<DsyncKey>) {
    /** Schema v3: ONE key holds every category. [local] names the device-local cache only; the server key is [wire]. */
    Main("app.molinete.aerio", DsyncKey.entries.toList()),
    ;

    fun wire(userKey: String): String = userKey

    companion object {
        fun of(key: DsyncKey): DsyncBucket = Main
    }
}

/**
 * Dispatcharr App Data key names (schema section 2.1, 2026-11-05). The key is
 * `<user>.aeriotv`, where <user> is the signed-in Dispatcharr username: ASCII
 * A-Z lowercased; a-z, 0-9, `_`, `.`, and `-` kept; every other UTF-8 byte
 * written as `-` plus two lowercase hex digits (percent-encoding with `-` in
 * place of `%`, which the server key pattern rejects); a `u` prefix when the
 * result does not start with a letter or digit; cut to 46 characters so
 * `<user>.aeriotv.favorites` stays within the 64 the server allows.
 * Collisions cannot mix data: App Data is stored per user. Apple
 * Shared/Dsync/DsyncV2.swift DsyncKeyName is identical.
 */
object DsyncKeyName {
    /** The key every release before 2026-11-05 used, plus its `.`-suffixed v1 and v2 keys. */
    const val LEGACY_MAIN = "app.molinete.aerio"
    const val SUFFIX = ".aeriotv"
    val MAX_USER = 64 - ".aeriotv.favorites".length

    fun user(username: String): String? {
        if (username.isEmpty()) return null
        val hex = "0123456789abcdef"
        val out = StringBuilder()
        for (byte in username.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            when (b) {
                in 0x41..0x5A -> out.append((b + 32).toChar())
                in 0x61..0x7A, in 0x30..0x39, 0x2D, 0x2E, 0x5F -> out.append(b.toChar())
                else -> out.append('-').append(hex[b shr 4]).append(hex[b and 0x0F])
            }
        }
        val f = out[0]
        if (!(f in 'a'..'z' || f in '0'..'9')) out.insert(0, 'u')
        return out.toString().take(MAX_USER)
    }

    /** The main key for [username]; null when the username is unknown. */
    fun main(username: String): String? = user(username)?.let { it + SUFFIX }
    /** The v2 three-key layout under the new name (never written by v3). */
    fun v2Wires(main: String): Set<String> = setOf(main, "$main.favorites", "$main.progress")
    fun isLegacy(name: String): Boolean = name == LEGACY_MAIN || name.startsWith("$LEGACY_MAIN.")
    /** Any user's main key (the restore probe, which runs before the username is read). */
    fun isMain(name: String): Boolean = name.endsWith(SUFFIX) && name.length > SUFFIX.length && DsyncKey.isValidKeyName(name)

    /** Legacy keys to move under [main]: every `app.molinete.aerio*` key, sorted, when [main] is absent from the list. */
    fun renamePlan(listed: Collection<String>, main: String): List<String> =
        if (main in listed) emptyList() else listed.filter(::isLegacy).sorted()

    /**
     * The value to PUT under the new key from the legacy keys' values: the
     * legacy main key (v2 or v3) and its v1 and v2 sibling keys merge per
     * category by updatedAt (DsyncV2.migrate). Null when nothing decodes.
     */
    fun renameValue(values: List<Pair<String, JsonObject>>, deviceId: String, appVersion: String, now: Long): JsonObject? {
        val v1 = LinkedHashMap<DsyncKey, DsyncEnvelope>()
        val v2 = ArrayList<DsyncV2.Bucket>()
        var v3: JsonObject? = null
        for ((name, raw) in values) {
            if (name == LEGACY_MAIN) {
                (DsyncV2.decode(raw, min = DsyncV2.MIN_SUBOBJECT_SCHEMA) as? DsyncV2.Decode.Ok)?.let {
                    if (it.bucket.schemaVersion >= DsyncV2.SCHEMA) v3 = raw else v2 += it.bucket
                }
                continue
            }
            val k1 = DsyncKey.fromWire(name)
            val d1 = if (k1 != null) DsyncEnvelopeCodec.decode(raw, 1) else null
            if (k1 != null && d1 is DsyncDecode.Ok) { v1[k1] = d1.envelope; continue }
            (DsyncV2.decode(raw, min = DsyncV2.MIN_SUBOBJECT_SCHEMA) as? DsyncV2.Decode.Ok)?.let { v2 += it.bucket }
        }
        return DsyncV2.migrate(v1, v2, v3, deviceId, appVersion, now) ?: v3
    }
}

object DsyncV2 {
    const val SCHEMA = 3
    /** The v2 sub-object shape is the same; only the key layout differed. */
    const val MIN_SUBOBJECT_SCHEMA = 2
    const val CATEGORIES = "categories"

    /** A decoded v2 key: envelope fields, raw category sub-objects by name, and the whole raw value. */
    data class Bucket(
        val schemaVersion: Int,
        val deviceId: String,
        val platform: String,
        val appVersion: String,
        val updatedAt: Long,
        val categories: Map<String, JsonObject>,
        val raw: JsonObject,
    )

    sealed interface Decode {
        data class Ok(val bucket: Bucket) : Decode
        data object Malformed : Decode
        data class TooNew(val version: Int) : Decode
    }

    fun decode(raw: JsonObject, known: Int = SCHEMA, min: Int = SCHEMA): Decode {
        val v = raw["schemaVersion"].asInt() ?: return Decode.Malformed
        val device = raw["deviceId"].asStr() ?: return Decode.Malformed
        val platform = raw["platform"].asStr() ?: return Decode.Malformed
        val app = raw["appVersion"].asStr() ?: return Decode.Malformed
        val at = raw["updatedAt"].asStr()?.let(DsyncEnvelopeCodec::parseIso) ?: return Decode.Malformed
        if (v > known) return Decode.TooNew(v)
        if (v < min) return Decode.Malformed
        val cats = raw[CATEGORIES] as? JsonObject ?: return Decode.Malformed
        val subs = cats.mapNotNull { (k, e) -> (e as? JsonObject)?.let { k to it } }.toMap()
        return Decode.Ok(Bucket(v, device, platform, app, at, subs, raw))
    }

    /**
     * One category as an envelope, so the v1 per-category logic runs as is.
     * Null when the category is absent; [DsyncDecode.Malformed] shape when it
     * lacks `updatedAt` or `data`.
     */
    fun category(b: Bucket, key: DsyncKey): DsyncDecode? {
        val sub = b.categories[key.suffix] ?: return null
        val at = sub["updatedAt"].asStr()?.let(DsyncEnvelopeCodec::parseIso) ?: return DsyncDecode.Malformed
        val data = sub["data"] as? JsonObject ?: return DsyncDecode.Malformed
        val dev = sub["deviceId"].asStr() ?: b.deviceId
        val extra = when (val e = sub["extra"]) {
            null, JsonNull -> null
            is JsonObject -> e
            else -> return DsyncDecode.Malformed
        }
        return DsyncDecode.Ok(DsyncEnvelope(b.schemaVersion, dev, b.platform, b.appVersion, at, data, extra, sub))
    }

    /** A category sub-object; unknown members and `extra` of [previous] are re-emitted. */
    fun encodeCategory(previous: JsonObject?, deviceId: String, updatedAt: Long, data: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out["updatedAt"] = JsonPrimitive(DsyncEnvelopeCodec.isoSeconds(updatedAt))
        out["deviceId"] = JsonPrimitive(deviceId)
        out["data"] = data
        previous?.forEach { (k, v) -> if (k !in out) out[k] = v }
        return JsonObject(out)
    }

    /**
     * The value to PUT for one key. Starts from [previous] (the last pulled
     * raw value) so categories this device did not touch, unknown categories,
     * and unknown envelope members are round-tripped; [replace] swaps in the
     * categories this device rebuilt.
     */
    fun encodeBucket(
        previous: JsonObject?,
        deviceId: String,
        appVersion: String,
        updatedAt: Long,
        replace: Map<DsyncKey, JsonObject>,
    ): JsonObject {
        val cats = LinkedHashMap<String, JsonElement>()
        (previous?.get(CATEGORIES) as? JsonObject)?.forEach { (k, v) -> cats[k] = v }
        replace.forEach { (k, v) -> cats[k.suffix] = v }
        val out = LinkedHashMap<String, JsonElement>()
        out["schemaVersion"] = JsonPrimitive(SCHEMA)
        out["deviceId"] = JsonPrimitive(deviceId)
        out["platform"] = JsonPrimitive("android")
        out["appVersion"] = JsonPrimitive(appVersion)
        out["updatedAt"] = JsonPrimitive(DsyncEnvelopeCodec.isoSeconds(updatedAt))
        out[CATEGORIES] = JsonObject(cats)
        previous?.forEach { (k, v) -> if (k !in out) out[k] = v }
        return JsonObject(out)
    }

    /** One 413 trim step on one category's `data`. */
    data class Trim(val key: DsyncKey, val label: String, val apply: (JsonObject) -> JsonObject)

    private fun JsonObject.rowsOf(): List<kotlinx.serialization.json.JsonArray> =
        (this["rows"] as? kotlinx.serialization.json.JsonArray)?.filterIsInstance<kotlinx.serialization.json.JsonArray>() ?: emptyList()

    private fun JsonObject.withMember(name: String, v: JsonElement): JsonObject = JsonObject(LinkedHashMap(this).apply { put(name, v) })

    private fun keepNewest(d: JsonObject, atPos: Int, n: Int): JsonObject =
        d.withMember("rows", kotlinx.serialization.json.JsonArray(d.rowsOf().sortedByDescending { it.getOrNull(atPos).asLong() ?: 0L }.take(n)))

    /**
     * The 413 trim sequence (schema v3, section 8): one step per retry, in
     * this order: progress oldest first, then recents, then watchlist and
     * hiddenTitles oldest first, then favorites from the end of the list.
     */
    val TRIMS: List<Trim> = listOf(
        Trim(DsyncKey.Progress, "progress: oldest down to 200") { keepNewest(it, 4, 200) },
        Trim(DsyncKey.Progress, "progress: oldest down to 100") { keepNewest(it, 4, 100) },
        Trim(DsyncKey.Recents, "recents: down to 25") { keepNewest(it, 1, 25) },
        Trim(DsyncKey.Watchlist, "watchlist: oldest down to 300") { keepNewest(it, 2, 300) },
        Trim(DsyncKey.HiddenTitles, "hiddenTitles: oldest down to 300") { keepNewest(it, 2, 300) },
        Trim(DsyncKey.Watchlist, "watchlist: oldest down to 150") { keepNewest(it, 2, 150) },
        Trim(DsyncKey.HiddenTitles, "hiddenTitles: oldest down to 150") { keepNewest(it, 2, 150) },
        Trim(DsyncKey.Favorites, "favorites: down to 500") { d ->
            val ids = d["ids"] as? kotlinx.serialization.json.JsonArray ?: return@Trim d
            d.withMember("ids", kotlinx.serialization.json.JsonArray(ids.take(500)))
        },
    )

    /** Apply trim [step] (index into [TRIMS]) to [value]; null when that category is absent (skip to the next step). */
    fun applyTrim(value: JsonObject, step: Int): JsonObject? {
        val t = TRIMS.getOrNull(step) ?: return null
        val cats = value[CATEGORIES] as? JsonObject ?: return null
        val sub = cats[t.key.suffix] as? JsonObject ?: return null
        val data = sub["data"] as? JsonObject ?: return null
        val newSub = sub.withMember("data", t.apply(data))
        return value.withMember(CATEGORIES, cats.withMember(t.key.suffix, newSub))
    }

    /** A category from a v1 envelope as a v3 sub-object (keeps updatedAt, deviceId, data, extra). */
    fun subFromV1(e: DsyncEnvelope): JsonObject {
        val sub = LinkedHashMap<String, JsonElement>()
        sub["updatedAt"] = JsonPrimitive(DsyncEnvelopeCodec.isoSeconds(e.updatedAt))
        sub["deviceId"] = JsonPrimitive(e.deviceId)
        sub["data"] = e.data
        e.extra?.let { sub["extra"] = it }
        return JsonObject(sub)
    }

    /**
     * Migration to the single key: every category from the v1 keys ([v1]),
     * the v2 keys ([v2], decoded buckets), and an existing v3 value ([v3])
     * merges per category by updatedAt (ties: higher deviceId), newest wins.
     * Null when there is nothing to migrate.
     */
    fun migrate(
        v1: Map<DsyncKey, DsyncEnvelope>,
        v2: List<Bucket>,
        v3: JsonObject?,
        deviceId: String,
        appVersion: String,
        now: Long,
    ): JsonObject? {
        if (v1.isEmpty() && v2.isEmpty()) return null
        val best = LinkedHashMap<String, Pair<DsyncEnvelope, JsonObject>>()
        fun offer(name: String, e: DsyncEnvelope, sub: JsonObject) {
            val cur = best[name]
            if (cur == null || DsyncEnvelopeCodec.remoteWins(cur.first.updatedAt, cur.first.deviceId, e.updatedAt, e.deviceId)) best[name] = e to sub
        }
        v1.forEach { (k, e) -> offer(k.suffix, e, subFromV1(e)) }
        v2.forEach { b ->
            DsyncKey.entries.forEach { k -> (category(b, k) as? DsyncDecode.Ok)?.envelope?.let { offer(k.suffix, it, it.raw) } }
        }
        val v3b = v3?.let { (decode(it) as? Decode.Ok)?.bucket }
        if (v3b != null) DsyncKey.entries.forEach { k -> (category(v3b, k) as? DsyncDecode.Ok)?.envelope?.let { offer(k.suffix, it, it.raw) } }
        val replace = LinkedHashMap<DsyncKey, JsonObject>()
        best.forEach { (name, p) -> DsyncKey.entries.firstOrNull { it.suffix == name }?.let { replace[it] = p.second } }
        return encodeBucket(v3, deviceId, appVersion, now, replace)
    }
}
