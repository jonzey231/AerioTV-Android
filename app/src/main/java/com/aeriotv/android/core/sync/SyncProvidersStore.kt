package com.aeriotv.android.core.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Last known [SyncProvidersRecord] per owner scope, plus the one-time offer
 * state. Scopes: [DRIVE] (the Google account that owns the backup) and
 * [dsyncScope] (one Dispatcharr playlist's BASE user). Records never merge
 * across scopes, so another profile's key never feeds the owner's record.
 */
@Singleton
class SyncProvidersStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("aerio_sync_providers", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _armed = MutableStateFlow(prefs.getBoolean(K_ARMED, false))
    /** True after an onboarding first restore or first pull, until the offer is evaluated. */
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    @Synchronized fun record(scope: String): SyncProvidersRecord? =
        prefs.getString("rec|$scope", null)?.let { runCatching { json.decodeFromString<SyncProvidersRecord>(it) }.getOrNull() }

    /** Pull: keep the newer of the stored and the incoming record. */
    @Synchronized fun fold(scope: String, incoming: SyncProvidersRecord?) {
        incoming ?: return
        val next = SyncProvidersRules.newer(record(scope), incoming) ?: return
        prefs.edit().putString("rec|$scope", json.encodeToString(SyncProvidersRecord.serializer(), next)).apply()
    }

    /** Push: the record to write for [scope] (null = nothing to write). */
    @Synchronized fun forPush(scope: String, isBaseProfile: Boolean, dispatcharrOn: Boolean, driveOn: Boolean, deviceId: String): SyncProvidersRecord? {
        val reported = prefs.getStringSet("rep|$scope", emptySet()).orEmpty()
            .mapNotNull { w -> SyncProviderId.entries.firstOrNull { it.wire == w } }.toSet()
        val p = SyncProvidersRules.forPush(record(scope), isBaseProfile, dispatcharrOn, driveOn, reported, deviceId, System.currentTimeMillis())
        val e = prefs.edit().putStringSet("rep|$scope", p.reported.map { it.wire }.toSet())
        p.record?.let { e.putString("rec|$scope", json.encodeToString(SyncProvidersRecord.serializer(), it)) }
        e.apply()
        return p.record
    }

    fun arm() { prefs.edit().putBoolean(K_ARMED, true).apply(); _armed.value = true }
    fun disarm() { prefs.edit().putBoolean(K_ARMED, false).apply(); _armed.value = false }
    fun offerShown(playlistId: String): Boolean = prefs.getBoolean("shown|$playlistId", false)
    fun markOfferShown(playlistId: String) { prefs.edit().putBoolean("shown|$playlistId", true).apply() }

    companion object {
        const val DRIVE = "drive"
        fun dsyncScope(playlistId: String) = "dsync|$playlistId"
        private const val K_ARMED = "armed"
    }
}
