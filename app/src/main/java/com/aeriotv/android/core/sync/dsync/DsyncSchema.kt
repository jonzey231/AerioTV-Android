package com.aeriotv.android.core.sync.dsync

import com.aeriotv.android.core.sync.SyncCategory
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Dispatcharr Sync v1, the pure half: keys, envelope, payload codecs, caps,
 * merge rules, trim order, and size guards, exactly as
 * docs/DispatcharrSyncSchema.md defines them (shared with the Apple app).
 * No Android types, so every rule here is unit tested.
 */
enum class DsyncKey(
    val suffix: String,
    /** Which existing Sync Categories toggle gates this key; null = always (profiles). */
    val category: SyncCategory?,
    val schemaVersion: Int = 1,
) {
    Favorites("favorites", SyncCategory.Favorites),
    Recents("recents", SyncCategory.Favorites),
    Progress("progress", SyncCategory.WatchProgress),
    Reminders("reminders", SyncCategory.Reminders),
    Watchlist("watchlist", SyncCategory.Watchlist),
    HiddenTitles("hiddenTitles", SyncCategory.Watchlist),
    HiddenGroups("hiddenGroups", SyncCategory.Preferences),
    GroupOrder("groupOrder", SyncCategory.Preferences),
    Settings("settings", SyncCategory.Preferences),
    RemoteMap("remoteMap", SyncCategory.Preferences),
    RadioDefault("radioDefault", SyncCategory.Preferences),
    Collections("collections", SyncCategory.Favorites),
    Profiles("profiles", null),
    ;

    val wire: String get() = PREFIX + suffix

    companion object {
        const val PREFIX = "app.molinete.aerio."
        private val KEY_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{7,63}$")
        fun fromWire(name: String): DsyncKey? = entries.firstOrNull { it.wire == name }
        fun isValidKeyName(name: String): Boolean = KEY_PATTERN.matches(name)
    }
}

object DsyncLimits {
    const val KEY_BYTES = 32 * 1024
    const val TOTAL_BYTES = 128 * 1024
    const val FAVORITES = 1000
    const val RECENTS = 50
    const val PROGRESS = 300
    const val REMINDERS = 200
    const val WATCHLIST = 500
    const val HIDDEN_TITLES = 500
    const val GROUP_NAMES = 500
    const val COLLECTIONS = 50
    const val COLLECTION_MEMBERS = 500
    const val PROFILES = 25
    const val PROGRESS_TITLE_BYTES = 40
    const val REMINDER_TITLE_BYTES = 60
    const val CHANNEL_NAME_BYTES = 40
    const val COLLECTION_NAME_BYTES = 40
    const val DISPLAY_NAME_BYTES = 40
    const val CUSTOM_CATEGORIES_BYTES = 4 * 1024
    const val FINISHED_MAX_AGE_S = 30L * 24 * 3600
}

/** Envelope (section 3). [raw] is the whole stored object, kept for round-trip. */
data class DsyncEnvelope(
    val schemaVersion: Int,
    val deviceId: String,
    val platform: String,
    val appVersion: String,
    /** Unix seconds parsed from the ISO 8601 `updatedAt`. */
    val updatedAt: Long,
    val data: JsonObject,
    val extra: JsonObject?,
    val raw: JsonObject,
)

sealed interface DsyncDecode {
    data class Ok(val envelope: DsyncEnvelope) : DsyncDecode
    data object Malformed : DsyncDecode
    data class TooNew(val version: Int) : DsyncDecode
}

object DsyncEnvelopeCodec {
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    fun isoSeconds(unixSeconds: Long): String =
        ISO.format(Instant.ofEpochSecond(unixSeconds).truncatedTo(ChronoUnit.SECONDS))

    fun parseIso(s: String): Long? = runCatching { Instant.parse(s).epochSecond }.getOrNull()

    /** Reader rules 1 and 2. A lower schemaVersion decodes (rule 3: upgrade in memory). */
    fun decode(raw: JsonObject, knownVersion: Int): DsyncDecode {
        val v = (raw["schemaVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: return DsyncDecode.Malformed
        val device = (raw["deviceId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return DsyncDecode.Malformed
        val platform = (raw["platform"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return DsyncDecode.Malformed
        val app = (raw["appVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return DsyncDecode.Malformed
        val at = (raw["updatedAt"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::parseIso)
            ?: return DsyncDecode.Malformed
        val data = raw["data"] as? JsonObject ?: return DsyncDecode.Malformed
        val extraEl = raw["extra"]
        val extra = when (extraEl) {
            null, JsonNull -> null
            is JsonObject -> extraEl
            else -> return DsyncDecode.Malformed
        }
        if (v > knownVersion) return DsyncDecode.TooNew(v)
        return DsyncDecode.Ok(DsyncEnvelope(v, device, platform, app, at, data, extra, raw))
    }

    /**
     * Build the value to PUT. Starts from [previous] (the raw last-pulled
     * object) so unknown envelope members and `extra` are re-emitted untouched.
     */
    fun encode(
        key: DsyncKey,
        previous: JsonObject?,
        deviceId: String,
        appVersion: String,
        updatedAt: Long,
        data: JsonObject,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out["schemaVersion"] = JsonPrimitive(key.schemaVersion)
        out["deviceId"] = JsonPrimitive(deviceId)
        out["platform"] = JsonPrimitive("android")
        out["appVersion"] = JsonPrimitive(appVersion)
        out["updatedAt"] = JsonPrimitive(isoSeconds(updatedAt))
        out["data"] = data
        previous?.forEach { (k, v) -> if (k !in out) out[k] = v }
        return JsonObject(out)
    }

    fun byteSize(value: JsonObject): Int = value.toString().toByteArray(Charsets.UTF_8).size

    /** Rule 6.1: remote wins when strictly newer; ties go to the higher deviceId. */
    fun remoteWins(localAt: Long, localDevice: String, remoteAt: Long, remoteDevice: String): Boolean =
        remoteAt > localAt || (remoteAt == localAt && remoteDevice > localDevice)
}

// ---- small JSON helpers ----

internal fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
internal fun JsonElement?.asLong(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
internal fun JsonElement?.asStr(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal fun JsonElement?.asBool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
internal fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

/** Truncate to at most [maxBytes] UTF-8 bytes on a code point boundary. */
fun truncateUtf8(s: String, maxBytes: Int): String {
    if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
    val sb = StringBuilder()
    var used = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
        if (used + n > maxBytes) break
        sb.appendCodePoint(cp)
        used += n
        i += Character.charCount(cp)
    }
    return sb.toString()
}

/** A decoded row: the known positions plus any trailing positions a newer writer appended. */
data class Tail(val extra: List<JsonElement> = emptyList())

private fun tailOf(row: JsonArray, known: Int): Tail =
    if (row.size > known) Tail(row.subList(known, row.size).toList()) else Tail()

private fun JsonArray.plusTail(t: Tail): JsonArray = if (t.extra.isEmpty()) this else JsonArray(this + t.extra)

// ---- payload models (section 4) ----

data class RecentRow(val channelId: Int, val watchedAt: Long, val tail: Tail = Tail())

data class ProgressRow(
    /** Dispatcharr integer primary key of the movie or episode. */
    val vodId: Int,
    val type: String,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
    val finished: Boolean,
    /** Dispatcharr integer series id; null for movies. */
    val seriesId: Int?,
    val season: Int,
    val episode: Int,
    val title: String,
    val tail: Tail = Tail(),
)

data class ReminderRow(
    val channelId: Int,
    val start: Long,
    val end: Long,
    val title: String,
    val channelName: String,
    val tail: Tail = Tail(),
)

/** watchlist and hiddenTitles: `[vodId, "m"|"s", at]`. */
data class VodMarkRow(val vodId: Int, val type: String, val at: Long, val tail: Tail = Tail())

data class CollectionRow(
    val id: String,
    val name: String,
    val placement: String,
    val members: List<Int>,
    val tail: Tail = Tail(),
)

data class ProfileRow(
    val userId: Int,
    val username: String,
    val displayName: String,
    val pinType: String,
    val pinHash: String,
    val pinSalt: String,
    /** "admin" | "user" | "child"; "" = absent (older writer), reader keeps its own. */
    val role: String = "",
    /** Unix seconds of the last change to this row's name, PIN, role, or avatar; 0 = absent (older writer). */
    val updatedAt: Long = 0L,
    /** Position 8, `{"icon","color"}`; null = absent (older writer, reader keeps its own). */
    val avatar: com.aeriotv.android.core.profiles.AvatarSpec? = null,
    /** Position 9, list position (0 first); -1 = absent (older writer, insertion order). */
    val order: Int = -1,
    val tail: Tail = Tail(),
)

/**
 * Codecs. Each `decodeX` returns null on a wrong shape (the caller logs
 * malformed and leaves the key alone). Each `encodeX` replaces only the
 * members it owns in [base] (the last pulled `data`, rule 6.2).
 */
object DsyncPayloads {

    private fun rows(data: JsonObject): JsonArray? = data["rows"] as? JsonArray ?: if (data["rows"] == null) JsonArray(emptyList()) else null

    private fun JsonObject?.with(vararg pairs: Pair<String, JsonElement>): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        this?.forEach { (k, v) -> m[k] = v }
        pairs.forEach { (k, v) -> m[k] = v }
        return JsonObject(m)
    }

    // favorites
    fun decodeFavorites(data: JsonObject): List<Int>? {
        val arr = data["ids"] as? JsonArray ?: return if (data["ids"] == null) emptyList() else null
        return arr.map { it.asInt() ?: return null }
    }
    fun encodeFavorites(base: JsonObject?, ids: List<Int>): JsonObject =
        base.with("ids" to JsonArray(ids.distinct().take(DsyncLimits.FAVORITES).map { JsonPrimitive(it) }))

    // recents
    fun decodeRecents(data: JsonObject): List<RecentRow>? = rows(data)?.map { el ->
        val r = el as? JsonArray ?: return null
        RecentRow(r.getOrNull(0).asInt() ?: return null, r.getOrNull(1).asLong() ?: return null, tailOf(r, 2))
    }
    fun encodeRecents(base: JsonObject?, rows: List<RecentRow>): JsonObject =
        base.with("rows" to JsonArray(capRecents(rows).map {
            JsonArray(listOf(JsonPrimitive(it.channelId), JsonPrimitive(it.watchedAt))).plusTail(it.tail)
        }))
    fun capRecents(rows: List<RecentRow>, cap: Int = DsyncLimits.RECENTS): List<RecentRow> =
        rows.groupBy { it.channelId }.map { (_, v) -> v.maxBy { it.watchedAt } }
            .sortedByDescending { it.watchedAt }.take(cap)

    // progress
    fun decodeProgress(data: JsonObject): List<ProgressRow>? = rows(data)?.map { el ->
        val r = el as? JsonArray ?: return null
        ProgressRow(
            vodId = r.getOrNull(0).asInt() ?: return null,
            type = r.getOrNull(1).asStr() ?: return null,
            position = r.getOrNull(2).asLong() ?: return null,
            duration = r.getOrNull(3).asLong() ?: 0L,
            updatedAt = r.getOrNull(4).asLong() ?: return null,
            finished = (r.getOrNull(5).asInt() ?: 0) == 1,
            seriesId = r.getOrNull(6).asInt(),
            season = r.getOrNull(7).asInt() ?: 0,
            episode = r.getOrNull(8).asInt() ?: 0,
            title = r.getOrNull(9).asStr() ?: "",
            tail = tailOf(r, 10),
        )
    }
    fun progressRowJson(p: ProgressRow, titleBytes: Int = DsyncLimits.PROGRESS_TITLE_BYTES): JsonArray = JsonArray(
        listOf(
            JsonPrimitive(p.vodId), JsonPrimitive(p.type), JsonPrimitive(p.position), JsonPrimitive(p.duration),
            JsonPrimitive(p.updatedAt), JsonPrimitive(if (p.finished) 1 else 0),
            p.seriesId?.let { JsonPrimitive(it) } ?: JsonNull,
            JsonPrimitive(p.season), JsonPrimitive(p.episode), JsonPrimitive(truncateUtf8(p.title, titleBytes)),
        ),
    ).plusTail(p.tail)
    fun encodeProgress(base: JsonObject?, rows: List<ProgressRow>, now: Long): JsonObject =
        base.with("rows" to JsonArray(capProgress(rows, now).map { progressRowJson(it) }))
    /** Finished rows older than 30 days go first, then the 300 newest by updatedAt stay. */
    fun capProgress(rows: List<ProgressRow>, now: Long, cap: Int = DsyncLimits.PROGRESS): List<ProgressRow> =
        rows.groupBy { it.type to it.vodId }.map { (_, v) -> v.maxBy { it.updatedAt } }
            .filterNot { it.finished && now - it.updatedAt > DsyncLimits.FINISHED_MAX_AGE_S }
            .sortedByDescending { it.updatedAt }.take(cap)

    // reminders
    fun decodeReminders(data: JsonObject): List<ReminderRow>? = rows(data)?.map { el ->
        val r = el as? JsonArray ?: return null
        ReminderRow(
            channelId = r.getOrNull(0).asInt() ?: return null,
            start = r.getOrNull(1).asLong() ?: return null,
            end = r.getOrNull(2).asLong() ?: return null,
            title = r.getOrNull(3).asStr() ?: "",
            channelName = r.getOrNull(4).asStr() ?: "",
            tail = tailOf(r, 5),
        )
    }
    fun encodeReminders(base: JsonObject?, rows: List<ReminderRow>, now: Long): JsonObject =
        base.with("rows" to JsonArray(capReminders(rows, now).map {
            JsonArray(
                listOf(
                    JsonPrimitive(it.channelId), JsonPrimitive(it.start), JsonPrimitive(it.end),
                    JsonPrimitive(truncateUtf8(it.title, DsyncLimits.REMINDER_TITLE_BYTES)),
                    JsonPrimitive(truncateUtf8(it.channelName, DsyncLimits.CHANNEL_NAME_BYTES)),
                ),
            ).plusTail(it.tail)
        }))
    /** Future only (end not in the past), soonest first, cap 200. */
    fun capReminders(rows: List<ReminderRow>, now: Long, cap: Int = DsyncLimits.REMINDERS): List<ReminderRow> =
        rows.filter { it.end >= now }.distinctBy { it.channelId to it.start }.sortedBy { it.start }.take(cap)

    // watchlist / hiddenTitles
    fun decodeVodMarks(data: JsonObject): List<VodMarkRow>? = rows(data)?.map { el ->
        val r = el as? JsonArray ?: return null
        VodMarkRow(r.getOrNull(0).asInt() ?: return null, r.getOrNull(1).asStr() ?: return null, r.getOrNull(2).asLong() ?: 0L, tailOf(r, 3))
    }
    fun encodeVodMarks(base: JsonObject?, rows: List<VodMarkRow>, cap: Int): JsonObject =
        base.with("rows" to JsonArray(capVodMarks(rows, cap).map {
            JsonArray(listOf(JsonPrimitive(it.vodId), JsonPrimitive(it.type), JsonPrimitive(it.at))).plusTail(it.tail)
        }))
    fun capVodMarks(rows: List<VodMarkRow>, cap: Int): List<VodMarkRow> =
        rows.distinctBy { it.type to it.vodId }.sortedByDescending { it.at }.take(cap)

    // hiddenGroups
    data class HiddenGroups(val live: List<String>, val movies: List<String>, val series: List<String>)
    fun decodeHiddenGroups(data: JsonObject): HiddenGroups? {
        fun list(name: String): List<String>? {
            val el = data[name] ?: return emptyList()
            val arr = el as? JsonArray ?: return null
            return arr.map { it.asStr() ?: return null }
        }
        return HiddenGroups(list("live") ?: return null, list("movies") ?: return null, list("series") ?: return null)
    }
    fun encodeHiddenGroups(base: JsonObject?, g: HiddenGroups): JsonObject {
        fun arr(l: List<String>) = JsonArray(l.distinct().take(DsyncLimits.GROUP_NAMES).map { JsonPrimitive(it) })
        return base.with("live" to arr(g.live), "movies" to arr(g.movies), "series" to arr(g.series))
    }

    // groupOrder
    data class GroupOrder(val mode: String, val order: List<String>)
    val GROUP_MODES = setOf("default", "alphabetical", "manual")
    fun decodeGroupOrder(data: JsonObject): GroupOrder? {
        val mode = data["mode"]?.let { it.asStr() ?: return null } ?: "default"
        val order = data["order"]?.let { el -> (el as? JsonArray ?: return null).map { it.asStr() ?: return null } } ?: emptyList()
        return GroupOrder(mode, order)
    }
    fun encodeGroupOrder(base: JsonObject?, g: GroupOrder): JsonObject =
        base.with(
            "mode" to JsonPrimitive(g.mode),
            "order" to JsonArray(g.order.distinct().take(DsyncLimits.GROUP_NAMES).map { JsonPrimitive(it) }),
        )

    // settings: names this platform owns are overwritten with local values;
    // every other name (and an owned name this device has never set) stays.
    fun decodeSettings(data: JsonObject): Map<String, JsonPrimitive>? {
        val v = data["v"] ?: return emptyMap()
        val obj = v as? JsonObject ?: return null
        return obj.mapNotNull { (k, e) -> (e as? JsonPrimitive)?.takeIf { it != JsonNull }?.let { k to it } }.toMap()
    }
    fun encodeSettings(base: JsonObject?, owned: Set<String>, values: Map<String, JsonPrimitive>): JsonObject {
        val prev = base?.get("v") as? JsonObject
        val m = LinkedHashMap<String, JsonElement>()
        prev?.forEach { (k, e) -> m[k] = e }
        values.forEach { (k, e) -> if (k in owned) m[k] = e }
        return base.with("v" to JsonObject(m))
    }

    // remoteMap: `tv` is opaque to this codec.
    fun decodeRemoteMap(data: JsonObject): JsonObject? {
        val tv = data["tv"] ?: return JsonObject(emptyMap())
        return tv as? JsonObject
    }
    fun encodeRemoteMap(base: JsonObject?, tv: JsonObject): JsonObject = base.with("tv" to tv)

    // radioDefault
    fun decodeRadioDefault(data: JsonObject): Boolean? {
        val on = data["on"] ?: return false
        return on.asBool()
    }
    fun encodeRadioDefault(base: JsonObject?, on: Boolean): JsonObject = base.with("on" to JsonPrimitive(on))

    // collections
    fun decodeCollections(data: JsonObject): List<CollectionRow>? = rows(data)?.map { el ->
        val r = el as? JsonArray ?: return null
        CollectionRow(
            id = r.getOrNull(0).asStr() ?: return null,
            name = r.getOrNull(1).asStr() ?: "",
            placement = r.getOrNull(2).asStr() ?: "e",
            members = (r.getOrNull(3) as? JsonArray)?.map { it.asInt() ?: return null } ?: emptyList(),
            tail = tailOf(r, 4),
        )
    }
    fun encodeCollections(base: JsonObject?, rows: List<CollectionRow>): JsonObject =
        base.with("rows" to JsonArray(rows.distinctBy { it.id }.take(DsyncLimits.COLLECTIONS).map {
            JsonArray(
                listOf(
                    JsonPrimitive(it.id),
                    JsonPrimitive(truncateUtf8(it.name, DsyncLimits.COLLECTION_NAME_BYTES)),
                    JsonPrimitive(if (it.placement == "b") "b" else "e"),
                    JsonArray(it.members.take(DsyncLimits.COLLECTION_MEMBERS).map { m -> JsonPrimitive(m) }),
                ),
            ).plusTail(it.tail)
        }))

    // profiles
    val PIN_TYPES = setOf("none", "numbers", "directions")
    val ROLES = setOf("admin", "user", "child")
    fun decodeProfiles(data: JsonObject): List<ProfileRow>? = rows(data)?.map { el ->
        val r = el as? JsonArray ?: return null
        ProfileRow(
            userId = r.getOrNull(0).asInt() ?: return null,
            username = r.getOrNull(1).asStr() ?: return null,
            displayName = r.getOrNull(2).asStr() ?: "",
            pinType = r.getOrNull(3).asStr()?.takeIf { it in PIN_TYPES } ?: "none",
            pinHash = r.getOrNull(4).asStr() ?: "",
            pinSalt = r.getOrNull(5).asStr() ?: "",
            role = r.getOrNull(6).asStr()?.takeIf { it in ROLES } ?: "",
            updatedAt = r.getOrNull(7).asLong() ?: 0L,
            avatar = decodeAvatar(r.getOrNull(8)),
            order = r.getOrNull(9).asInt()?.takeIf { it >= 0 } ?: -1,
            tail = tailOf(r, 10),
        )
    }
    fun encodeProfiles(base: JsonObject?, rows: List<ProfileRow>): JsonObject =
        base.with("rows" to JsonArray(rows.distinctBy { it.userId }.take(DsyncLimits.PROFILES).map {
            val none = it.pinType == "none"
            JsonArray(
                listOf(
                    JsonPrimitive(it.userId), JsonPrimitive(it.username),
                    JsonPrimitive(truncateUtf8(it.displayName, DsyncLimits.DISPLAY_NAME_BYTES)),
                    JsonPrimitive(it.pinType),
                    JsonPrimitive(if (none) "" else it.pinHash),
                    JsonPrimitive(if (none) "" else it.pinSalt),
                    JsonPrimitive(it.role.takeIf { r -> r in ROLES } ?: "user"),
                    JsonPrimitive(it.updatedAt),
                    encodeAvatar(it.avatar),
                    JsonPrimitive(it.order.coerceAtLeast(0)),
                ),
            ).plusTail(it.tail)
        }))

    /** `{}` or a non-object reads as the default; unknown ids fall back per field. */
    fun decodeAvatar(el: JsonElement?): com.aeriotv.android.core.profiles.AvatarSpec? {
        if (el == null || el is JsonNull) return null
        val o = el as? JsonObject ?: return com.aeriotv.android.core.profiles.AvatarSpec()
        val cat = com.aeriotv.android.core.profiles.AvatarCatalog
        return com.aeriotv.android.core.profiles.AvatarSpec(
            icon = o["icon"].asStr()?.takeIf { it in cat.ICONS } ?: cat.INITIAL,
            color = o["color"].asStr()?.takeIf { it in cat.COLORS } ?: "",
        )
    }

    fun encodeAvatar(a: com.aeriotv.android.core.profiles.AvatarSpec?): JsonObject {
        val n = com.aeriotv.android.core.profiles.AvatarCatalog.normalize(a) ?: return JsonObject(emptyMap())
        return JsonObject(
            buildMap {
                if (n.icon != com.aeriotv.android.core.profiles.AvatarCatalog.INITIAL) put("icon", JsonPrimitive(n.icon))
                if (n.color.isNotEmpty()) put("color", JsonPrimitive(n.color))
            },
        )
    }

    /** Carry trailing positions from the last pulled rows onto our rebuilt rows (rule 6.2). */
    fun <T> carryTails(ours: List<T>, remote: List<T>?, id: (T) -> Any, tail: (T) -> Tail, withTail: (T, Tail) -> T): List<T> {
        if (remote.isNullOrEmpty()) return ours
        val byId = remote.associateBy(id)
        return ours.map { o ->
            val r = byId[id(o)]
            if (r != null && tail(o).extra.isEmpty() && tail(r).extra.isNotEmpty()) withTail(o, tail(r)) else o
        }
    }
}

/** Section 6.1 exception: row merge for progress and recents before writing. */
object DsyncMerge {
    fun progress(local: List<ProgressRow>, remote: List<ProgressRow>): List<ProgressRow> {
        val out = LinkedHashMap<Pair<String, Int>, ProgressRow>()
        (remote + local).forEach { r ->
            val cur = out[r.type to r.vodId]
            if (cur == null || r.updatedAt >= cur.updatedAt) {
                out[r.type to r.vodId] = if (cur != null && r.tail.extra.isEmpty()) r.copy(tail = cur.tail) else r
            }
        }
        return out.values.sortedByDescending { it.updatedAt }
    }

    fun recents(local: List<RecentRow>, remote: List<RecentRow>): List<RecentRow> {
        val out = LinkedHashMap<Int, RecentRow>()
        (remote + local).forEach { r ->
            val cur = out[r.channelId]
            if (cur == null || r.watchedAt >= cur.watchedAt) out[r.channelId] = r
        }
        return out.values.sortedByDescending { it.watchedAt }
    }
}

/** Pending-write and dirty-key rules (section 7), pure so the ordering is unit tested. */
object DsyncGate {
    /** Keys merged row by row with the server copy when this device has an unsent change. */
    val ROW_MERGE_KEYS = setOf(DsyncKey.Favorites, DsyncKey.Recents, DsyncKey.Progress, DsyncKey.Collections, DsyncKey.Reminders)

    /** Reminders union by identity (channelId, start); local row first. */
    fun remindersUnion(local: List<ReminderRow>, remote: List<ReminderRow>): List<ReminderRow> =
        (local + remote).distinctBy { it.channelId to it.start }

    /**
     * A write queued by a session may go out only under that session's own
     * profile token, after that session's first pull, for a key it has read.
     */
    fun mayWrite(sessionToken: String, currentToken: String, firstPullDone: Boolean, keyRead: Boolean): Boolean =
        sessionToken == currentToken && firstPullDone && keyRead

    enum class DirtyPull { TakeRemote, Merge, KeepLocal }

    /**
     * A pulled value that is newer than the last one applied here, against
     * this device's unsent change ([dirtyAt], Unix seconds): row keys merge,
     * whole-value keys keep the newer side. Never a blind overwrite of a dirty key.
     */
    fun onNewerPull(key: DsyncKey, dirty: Boolean, dirtyAt: Long, remoteAt: Long): DirtyPull = when {
        !dirty -> DirtyPull.TakeRemote
        key in ROW_MERGE_KEYS -> DirtyPull.Merge
        dirtyAt > remoteAt -> DirtyPull.KeepLocal
        else -> DirtyPull.TakeRemote
    }

    /** Favorites union: local order first, then server ids this device lacks. */
    fun favoritesUnion(local: List<Int>, remote: List<Int>): List<Int> = (local + remote).distinct()

    /** Collections union by id; members of a shared collection are the union too. */
    fun collectionsUnion(local: List<CollectionRow>, remote: List<CollectionRow>): List<CollectionRow> {
        val r = remote.associateBy { it.id }
        val merged = local.map { l -> r[l.id]?.let { l.copy(members = (l.members + it.members).distinct()) } ?: l }
        val have = local.map { it.id }.toSet()
        return merged + remote.filter { it.id !in have }
    }

    /** Profiles merge per row: a pulled row replaces local fields only when strictly newer. */
    fun profileRowWins(localUpdatedAt: Long, remote: ProfileRow): Boolean = remote.updatedAt > localUpdatedAt
}

/**
 * Section 8 trim order. Each step rewrites one key's `data`; the guards apply
 * steps in order until the key (32 KiB) or the user total (128 KiB) fits.
 */
object DsyncTrim {

    data class Step(val key: DsyncKey, val label: String, val apply: (JsonObject, Long) -> JsonObject)

    private fun JsonObject.withRows(rows: List<JsonElement>): JsonObject =
        JsonObject(LinkedHashMap(this).apply { put("rows", JsonArray(rows)) })

    private fun JsonObject.rowArrays(): List<JsonArray> = (this["rows"] as? JsonArray)?.filterIsInstance<JsonArray>() ?: emptyList()

    val STEPS: List<Step> = listOf(
        Step(DsyncKey.Progress, "progress: drop finished") { d, _ ->
            d.withRows(d.rowArrays().filterNot { it.getOrNull(5).asInt() == 1 })
        },
        Step(DsyncKey.Progress, "progress: oldest down to 200") { d, _ ->
            d.withRows(d.rowArrays().sortedByDescending { it.getOrNull(4).asLong() ?: 0L }.take(200))
        },
        Step(DsyncKey.Progress, "progress: titles to 20 B") { d, _ ->
            d.withRows(d.rowArrays().map { r ->
                val t = r.getOrNull(9).asStr()
                if (t == null) r else JsonArray(r.toMutableList().also { it[9] = JsonPrimitive(truncateUtf8(t, 20)) })
            })
        },
        Step(DsyncKey.Reminders, "reminders: drop rows starting more than 14 days out") { d, now ->
            d.withRows(d.rowArrays().filter { (it.getOrNull(1).asLong() ?: 0L) <= now + 14L * 24 * 3600 })
        },
        Step(DsyncKey.Recents, "recents: down to 25") { d, _ ->
            d.withRows(d.rowArrays().sortedByDescending { it.getOrNull(1).asLong() ?: 0L }.take(25))
        },
        Step(DsyncKey.Watchlist, "watchlist: oldest down to 300") { d, _ ->
            d.withRows(d.rowArrays().sortedByDescending { it.getOrNull(2).asLong() ?: 0L }.take(300))
        },
        Step(DsyncKey.HiddenTitles, "hiddenTitles: oldest down to 300") { d, _ ->
            d.withRows(d.rowArrays().sortedByDescending { it.getOrNull(2).asLong() ?: 0L }.take(300))
        },
        Step(DsyncKey.Settings, "settings: drop customCategories") { d, _ ->
            val v = d["v"] as? JsonObject ?: return@Step d
            JsonObject(LinkedHashMap(d).apply { put("v", JsonObject(v.filterKeys { it != "customCategories" })) })
        },
    )

    fun stepsFor(key: DsyncKey): List<Step> = STEPS.filter { it.key == key }

    /**
     * Fit one key under 32 KiB. [wrap] turns `data` into the full envelope.
     * Returns the fitted data, or null when every step ran and it still does
     * not fit (favorites, collections, groups, and profiles are never trimmed).
     */
    fun fitKey(key: DsyncKey, data: JsonObject, now: Long, force: Boolean = false, wrap: (JsonObject) -> JsonObject): JsonObject? {
        var d = data
        if (!force && DsyncEnvelopeCodec.byteSize(wrap(d)) <= DsyncLimits.KEY_BYTES) return d
        for (s in stepsFor(key)) {
            d = s.apply(d, now)
            if (!force && DsyncEnvelopeCodec.byteSize(wrap(d)) <= DsyncLimits.KEY_BYTES) return d
        }
        return d.takeIf { DsyncEnvelopeCodec.byteSize(wrap(it)) <= DsyncLimits.KEY_BYTES }
    }

    /**
     * Fit the user total under 128 KiB. [datas] are this device's keys,
     * [otherBytes] the server sizes of keys not in [datas]. Steps run in the
     * global order across keys. Returns the trimmed map (changed keys only
     * differ) or null if it cannot fit.
     */
    fun fitTotal(
        datas: Map<DsyncKey, JsonObject>,
        otherBytes: Long,
        now: Long,
        wrap: (DsyncKey, JsonObject) -> JsonObject,
    ): Map<DsyncKey, JsonObject>? {
        val m = LinkedHashMap(datas)
        fun total(): Long = otherBytes + m.entries.sumOf { (k, d) -> DsyncEnvelopeCodec.byteSize(wrap(k, d)).toLong() }
        if (total() <= DsyncLimits.TOTAL_BYTES) return m
        for (s in STEPS) {
            val d = m[s.key] ?: continue
            m[s.key] = s.apply(d, now)
            if (total() <= DsyncLimits.TOTAL_BYTES) return m
        }
        return null
    }
}

/** Section 4.12 PIN hash: lowercase hex SHA-256 of `salt bytes || UTF-8 PIN`. */
object DsyncPinHash {
    fun hash(saltHex: String, normalizedPin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(hexToBytes(saltHex))
        md.update(normalizedPin.toByteArray(Charsets.UTF_8))
        return md.digest().toHex()
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
