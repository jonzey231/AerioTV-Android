package com.aeriotv.android.core.data.capability

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-user capabilities probed from Dispatcharr, replacing the old hard
 * "Admin vs Standard" split.
 *
 * WHY: Dispatcharr resolves most feature access PER USER from
 * `/api/accounts/users/me/` `custom_properties` (dvr_access,
 * vod_movies_enabled, vod_series_enabled, catchup_enabled, ...), not from the
 * user_level alone, and it keeps adding keys. Hard-coding "level >= 10 or
 * nothing" both hid features a standard account really could use and showed
 * features a demoted admin could not. This layer stores the RAW snapshot of
 * what the server said about the user and derives named capabilities on read,
 * so a new server-side key is a derivation change, not a schema change.
 *
 * Three-state on purpose ([CapabilityState]): we never silently downgrade on
 * an unread snapshot. [CapabilityState.Unknown] renders the affordance ENABLED
 * and lets the server speak; a 403 then self-corrects through
 * [CapabilityCorrections].
 */
enum class CapabilityState {
    /** The snapshot says the user can do this. */
    Allowed,

    /** The snapshot says the user cannot. Hide or disable the affordance. */
    Denied,

    /** No usable snapshot. Render ENABLED and let the server answer. */
    Unknown,
}

/** The named capabilities the app gates on. Extend as Dispatcharr grows keys. */
enum class Capability {
    CanViewVod,
    CanViewSeries,
    CanUseCatchup,
    CanViewDvr,
    CanManageDvr,
    CanSwitchStream,
    CanManagePlaylists,
    CanReadServerSettings,
}

/**
 * Bump to force a one-time re-probe of every playlist on upgrade. Rows whose
 * stored `dispatcharrCapabilitiesSchema` is below this are treated as having
 * NO snapshot (everything [CapabilityState.Unknown], so nothing is hidden)
 * until the next probe lands.
 *
 * 1 = first capability snapshot (repairs users stuck at view-only DVR because
 * the old code coalesced a missing dvr_access onto a stale stored "view").
 */
const val CAPABILITIES_SCHEMA: Int = 1

/** How long a snapshot is considered fresh before an opportunistic re-probe. */
const val CAPABILITIES_TTL_MS: Long = 6L * 60L * 60L * 1000L

/**
 * A derived, read-only view over one playlist's capability snapshot.
 *
 * [allows] is the gate the UI should use: it is true for both Allowed AND
 * Unknown, because an unknown capability must not hide an affordance. Use
 * [isDenied] only where you need to show a "your account cannot do this"
 * explanation.
 */
data class CapabilitySet(
    private val states: Map<Capability, CapabilityState>,
    /** Effective level: staff / superuser read as 10 regardless of user_level. */
    val effectiveUserLevel: Int,
    /** True when there is no usable snapshot at all (never probed, or schema bump). */
    val isUnknownSnapshot: Boolean,
    /** True when the last probe failed and this is the previous good snapshot. */
    val isStale: Boolean,
) {
    operator fun get(capability: Capability): CapabilityState =
        states[capability] ?: CapabilityState.Unknown

    /** Render the affordance? True for Allowed and Unknown; false only for Denied. */
    fun allows(capability: Capability): Boolean = this[capability] != CapabilityState.Denied

    fun isDenied(capability: Capability): Boolean = this[capability] == CapabilityState.Denied

    fun isKnownAllowed(capability: Capability): Boolean = this[capability] == CapabilityState.Allowed

    companion object {
        /** Everything allowed: non-Dispatcharr sources have no server gate at all. */
        val PERMISSIVE = CapabilitySet(
            states = Capability.entries.associateWith { CapabilityState.Allowed },
            effectiveUserLevel = 10,
            isUnknownSnapshot = false,
            isStale = false,
        )

        /** Nothing known: renders every affordance enabled and lets the server speak. */
        val UNKNOWN = CapabilitySet(
            states = emptyMap(),
            effectiveUserLevel = 10,
            isUnknownSnapshot = true,
            isStale = false,
        )
    }
}

/**
 * The raw, per-user snapshot persisted on the playlist row. Everything here is
 * exactly what `/api/accounts/users/me/` returned, so derivation can change
 * without a migration.
 */
data class CapabilitySnapshot(
    val userLevel: Int,
    val isStaff: Boolean,
    val isSuperuser: Boolean,
    /** The FULL custom_properties object, verbatim, as JSON text ("" = none). */
    val customPropertiesJson: String,
    val fetchedAtMillis: Long,
    val schema: Int,
    /** True when the last probe failed and this snapshot is the last good one. */
    val isStale: Boolean,
    /**
     * `system_settings.catchup_enabled` from `/api/core/settings/`, or null
     * when unread (that endpoint needs level >= 1). Catch-up needs BOTH the
     * per-user flag and the system flag.
     */
    val systemCatchupEnabled: Boolean?,
)

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Parse the stored custom_properties text; empty/garbage yields an empty object. */
fun parseCustomProperties(json: String): JsonObject? {
    if (json.isBlank()) return null
    return runCatching { lenientJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
}

/**
 * Read a tri-state boolean flag out of custom_properties.
 * Absent = null (caller applies the SERVER's default, which for every flag we
 * gate on today is "enabled"); only an explicit `false` denies.
 */
private fun JsonObject?.boolFlag(key: String): Boolean? =
    (this?.get(key) as? JsonPrimitive)?.booleanOrNull

private fun JsonObject?.stringValue(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.lowercase()?.takeIf { it.isNotBlank() }

/**
 * Derive named capabilities from a snapshot, mirroring the server's own
 * resolution EXACTLY:
 *
 *  - effective level: `is_staff || is_superuser` -> 10, else `user_level`.
 *  - DVR (apps/channels/dvr_access.py): level >= 10 -> manage; level < 1 ->
 *    none; else custom_properties.dvr_access when it is one of
 *    {none, view, manage}, else "view" (ABSENT = view).
 *  - VOD (apps/vod/utils.py): vod_movies_enabled / vod_series_enabled absent =
 *    ENABLED; only an explicit false disables.
 *  - Catch-up: the per-user flag AND system_settings.catchup_enabled.
 *  - change_stream and the other /proxy control endpoints are still IsAdmin,
 *    so CanSwitchStream is level >= 10. Routed through a capability anyway so
 *    a future server change is a one-line edit here.
 *  - /api/core/settings/ GET is level >= 1.
 */
fun deriveCapabilities(snapshot: CapabilitySnapshot?): CapabilitySet {
    if (snapshot == null || snapshot.schema < CAPABILITIES_SCHEMA || snapshot.fetchedAtMillis <= 0L) {
        return CapabilitySet.UNKNOWN
    }
    val props = parseCustomProperties(snapshot.customPropertiesJson)
    val level = if (snapshot.isStaff || snapshot.isSuperuser) 10 else snapshot.userLevel

    val dvrAccess = when {
        level >= 10 -> "manage"
        level < 1 -> "none"
        else -> when (val raw = props.stringValue("dvr_access")) {
            "none", "view", "manage" -> raw
            else -> "view"
        }
    }

    fun flagState(key: String): CapabilityState =
        if (props.boolFlag(key) == false) CapabilityState.Denied else CapabilityState.Allowed

    val perUserCatchup = props.boolFlag("catchup_enabled") != false
    val systemCatchup = snapshot.systemCatchupEnabled
    val catchup = when {
        !perUserCatchup -> CapabilityState.Denied
        systemCatchup == false -> CapabilityState.Denied
        // Per-user says yes but the system flag was never readable: unknown,
        // so the affordance stays and the server decides.
        systemCatchup == null -> CapabilityState.Unknown
        else -> CapabilityState.Allowed
    }

    val states = mapOf(
        Capability.CanViewVod to flagState("vod_movies_enabled"),
        Capability.CanViewSeries to flagState("vod_series_enabled"),
        Capability.CanUseCatchup to catchup,
        Capability.CanViewDvr to
            if (dvrAccess == "none") CapabilityState.Denied else CapabilityState.Allowed,
        Capability.CanManageDvr to
            if (dvrAccess == "manage") CapabilityState.Allowed else CapabilityState.Denied,
        Capability.CanSwitchStream to
            if (level >= 10) CapabilityState.Allowed else CapabilityState.Denied,
        Capability.CanManagePlaylists to
            if (level >= 10) CapabilityState.Allowed else CapabilityState.Denied,
        Capability.CanReadServerSettings to
            if (level >= 1) CapabilityState.Allowed else CapabilityState.Denied,
    )
    return CapabilitySet(
        states = states,
        effectiveUserLevel = level,
        isUnknownSnapshot = false,
        isStale = snapshot.isStale,
    )
}

/**
 * In-memory, per-session corrections learned from what the SERVER actually
 * answered, applied on top of the derived snapshot.
 *
 * A mutating call that 403s demotes the capability it assumed (so the
 * affordance disappears immediately instead of failing again); a 2xx on
 * something we believed Denied promotes it (so a server-side grant applies
 * without waiting for the TTL). Both also kick a re-probe, which replaces the
 * correction with persisted truth; corrections are deliberately NOT persisted
 * so a one-off server hiccup cannot durably lock a user out.
 */
object CapabilityCorrections {
    private val corrections = ConcurrentHashMap<String, MutableMap<Capability, CapabilityState>>()

    private fun keyOf(playlistId: String, capability: Capability) = playlistId to capability

    fun record(playlistId: String, capability: Capability, state: CapabilityState) {
        corrections.getOrPut(playlistId) { ConcurrentHashMap() }[capability] = state
    }

    fun clear(playlistId: String) {
        corrections.remove(playlistId)
    }

    fun apply(playlistId: String?, set: CapabilitySet): CapabilitySet {
        val overrides = playlistId?.let { corrections[it] }?.takeIf { it.isNotEmpty() } ?: return set
        val merged = Capability.entries.associateWith { overrides[it] ?: set[it] }
        return set.copy(states = merged)
    }
}

/**
 * A capability problem the user needs to see. Never swallowed: a 403 on a
 * feature we offered is either a permission the admin must grant or a network
 * policy on the account, and both need naming.
 */
data class CapabilityNotice(
    val playlistId: String,
    val capability: Capability,
    val message: String,
    /** True when permissions say this SHOULD be allowed, so the block is elsewhere. */
    val looksLikeNetworkRestriction: Boolean,
)

/** Process-wide bus for [CapabilityNotice]; UI surfaces collect it. */
object CapabilityNotices {
    private val _notices = MutableSharedFlow<CapabilityNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<CapabilityNotice> = _notices.asSharedFlow()

    fun emit(notice: CapabilityNotice) {
        _notices.tryEmit(notice)
    }
}

/** User-facing copy for a denied capability. Kept here so every surface agrees. */
fun Capability.deniedMessage(): String = when (this) {
    Capability.CanManageDvr ->
        "Your Dispatcharr account can view recordings but not manage them. " +
            "Ask your server administrator for DVR manage access."
    Capability.CanViewDvr ->
        "Your Dispatcharr account does not have DVR access. " +
            "Ask your server administrator to enable it."
    Capability.CanViewVod ->
        "Your Dispatcharr account does not have access to movies. " +
            "Ask your server administrator to enable it."
    Capability.CanViewSeries ->
        "Your Dispatcharr account does not have access to TV shows. " +
            "Ask your server administrator to enable it."
    Capability.CanUseCatchup ->
        "Catch-up is not enabled for your Dispatcharr account. " +
            "Ask your server administrator to enable it."
    Capability.CanSwitchStream ->
        "Your Dispatcharr account can't switch streams. " +
            "Switching needs an administrator account."
    Capability.CanManagePlaylists ->
        "Your Dispatcharr account cannot change server playlists. " +
            "Ask your server administrator for access."
    Capability.CanReadServerSettings ->
        "Your Dispatcharr account cannot read server settings. " +
            "Ask your server administrator for access."
}

/** Copy for a 403 on something the account's permissions say it SHOULD be able to do. */
fun Capability.networkRestrictionMessage(): String =
    "The server refused this even though your Dispatcharr account has permission. " +
        "This usually means a network restriction on the account (allowed networks). " +
        "Ask your server administrator to allow this device's network."
