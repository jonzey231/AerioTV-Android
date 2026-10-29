package com.aeriotv.android.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Which sync providers the device owner runs (docs/DispatcharrSyncSchema.md,
 * section 10b). Rides every Drive snapshot envelope and the `syncProviders`
 * category of the Dispatcharr Sync key, so a new device can offer to turn on
 * the same ones. Apple writes [icloud]; Android only preserves it.
 */
@Serializable
data class SyncProvidersRecord(
    val dispatcharr: Boolean = false,
    val googleDrive: Boolean = false,
    val icloud: Boolean = false,
    /** Unix milliseconds of the last flag change. */
    val updatedAt: Long = 0L,
    val deviceId: String = "",
)

enum class SyncProviderId(val wire: String) { Dispatcharr("dispatcharr"), GoogleDrive("googleDrive"), ICloud("icloud") }

/** Pure rules, unit tested (SyncProvidersTest). */
object SyncProvidersRules {
    /** Pull fold: the newer record wins whole (ties: higher deviceId); null never replaces. */
    fun newer(a: SyncProvidersRecord?, b: SyncProvidersRecord?): SyncProvidersRecord? = when {
        a == null -> b
        b == null -> a
        b.updatedAt > a.updatedAt -> b
        b.updatedAt == a.updatedAt && b.deviceId > a.deviceId -> b
        else -> a
    }

    data class Push(val record: SyncProvidersRecord?, val reported: Set<SyncProviderId>)

    /**
     * The record to write on a push. Union: stored flags OR this device's own.
     * A flag goes back to false only when this device reported it true before
     * and the provider is now off here; that false is written once, then the
     * provider leaves [reported] so another device that still runs it can set
     * it again. [updatedAt] and [deviceId] move only when a flag changes.
     * [isBaseProfile] false (a sub-profile) never changes the record: it is
     * the device owner's record only, so the stored one is returned as is.
     */
    fun forPush(
        stored: SyncProvidersRecord?,
        isBaseProfile: Boolean,
        dispatcharrOn: Boolean,
        driveOn: Boolean,
        reported: Set<SyncProviderId>,
        deviceId: String,
        nowMs: Long,
    ): Push {
        if (!isBaseProfile) return Push(stored, reported)
        val rep = reported.toMutableSet()
        fun flag(id: SyncProviderId, remote: Boolean, on: Boolean): Boolean = when {
            on -> { rep += id; true }
            id in reported -> { rep -= id; false }
            else -> remote
        }
        val d = flag(SyncProviderId.Dispatcharr, stored?.dispatcharr == true, dispatcharrOn)
        val g = flag(SyncProviderId.GoogleDrive, stored?.googleDrive == true, driveOn)
        val i = stored?.icloud == true
        val changed = stored == null || d != stored.dispatcharr || g != stored.googleDrive
        val rec = if (changed) SyncProvidersRecord(d, g, i, nowMs, deviceId) else stored
        return Push(rec, rep)
    }

    /**
     * Providers to offer on this device after its first restore or pull:
     * the record says the owner runs it and it is off here. Dispatcharr only
     * when the active playlist is Dispatcharr Direct Connect. iCloud never.
     */
    fun offers(
        record: SyncProvidersRecord?,
        driveOnHere: Boolean,
        dispatcharrOnHere: Boolean,
        activeIsDispatcharr: Boolean,
    ): List<SyncProviderId> {
        val r = record ?: return emptyList()
        val out = ArrayList<SyncProviderId>()
        if (r.googleDrive && !driveOnHere) out += SyncProviderId.GoogleDrive
        if (r.dispatcharr && !dispatcharrOnHere && activeIsDispatcharr) out += SyncProviderId.Dispatcharr
        return out
    }

    /** "Google Drive", "Dispatcharr Sync", or "Google Drive and Dispatcharr Sync". */
    fun offerNames(offers: List<SyncProviderId>): String = offers.mapNotNull {
        when (it) {
            SyncProviderId.GoogleDrive -> "Google Drive"
            SyncProviderId.Dispatcharr -> "Dispatcharr Sync"
            SyncProviderId.ICloud -> null
        }
    }.joinToString(" and ")

    fun offerText(offers: List<SyncProviderId>): String =
        "Your other devices also use ${offerNames(offers)}. Turn it on here too?"

    // ---- wire (Dispatcharr Sync `syncProviders` category data, Drive envelope field) ----

    fun toJson(r: SyncProvidersRecord): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>(
            "dispatcharr" to JsonPrimitive(r.dispatcharr),
            "googleDrive" to JsonPrimitive(r.googleDrive),
            "icloud" to JsonPrimitive(r.icloud),
            "updatedAt" to JsonPrimitive(r.updatedAt),
            "deviceId" to JsonPrimitive(r.deviceId),
        ),
    )

    /** Tolerant: missing booleans read false; a non-object or missing updatedAt reads null. */
    fun fromJson(e: JsonElement?): SyncProvidersRecord? {
        val o = e as? JsonObject ?: return null
        fun b(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false
        val at = (o["updatedAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        val dev = (o["deviceId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        return SyncProvidersRecord(b("dispatcharr"), b("googleDrive"), b("icloud"), at, dev)
    }

    /** The record inside a Drive snapshot body (`envelope.syncProviders`), or null. */
    fun fromDriveBody(body: String): SyncProvidersRecord? = runCatching {
        val root = Json.parseToJsonElement(body) as? JsonObject
        fromJson((root?.get("envelope") as? JsonObject)?.get("syncProviders"))
    }.getOrNull()

    const val DSYNC_CATEGORY = "syncProviders"
}
