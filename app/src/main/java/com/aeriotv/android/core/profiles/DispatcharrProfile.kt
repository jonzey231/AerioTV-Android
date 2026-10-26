package com.aeriotv.android.core.profiles

import android.content.Context
import android.util.Log
import com.aeriotv.android.core.security.CredentialCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A profile is one Dispatcharr USER on a Dispatcharr playlist (server). The
 * playlist keeps the server-level data (URL, EPG, logos, VOD metadata); each
 * profile signs in as its own user and the server enforces that user's
 * permissions. The default profile's credentials stay on the playlist row
 * (never duplicated here); a non-default profile stores its own credential,
 * encrypted with the same CredentialCipher as the playlist login.
 */
@Serializable
data class DispatcharrProfile(
    val id: String,
    val playlistId: String,
    val displayName: String,
    /** The Dispatcharr username this profile authenticates as. */
    val username: String,
    val isDefault: Boolean = false,
    /** Encrypted at rest. Null on the default profile. */
    val password: String? = null,
    /** Encrypted at rest. Null on the default profile. */
    val apiKey: String? = null,
    /** Dispatcharr user_level: 10 admin, 1 standard, 0 streamer (Limited). */
    val userLevel: Int = 10,
    val streamLimit: Int = 0,
    val hideAdultContent: Boolean = false,
    // Per-user facts captured from /api/accounts/users/me/ for a non-default
    // profile. The default profile keeps them on the playlist row.
    val dvrAccess: String = "",
    val catchupEnabled: Boolean = true,
    val vodMoviesEnabled: Boolean = true,
    val vodSeriesEnabled: Boolean = true,
    val accountProfileIds: String = "",
    val isStaff: Boolean = false,
    val isSuperuser: Boolean = false,
    val customProperties: String = "",
    val capabilitiesFetchedAt: Long = 0L,
    val capabilitiesSchema: Int = 0,
    val capabilitiesStale: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** App-local role (phase 2b); null = the default for [userLevel]. */
    val role: ProfileRole? = null,
    /** Dispatcharr user id from /me/ (0 = not read yet). */
    val userId: Int = 0,
    /** App-defined avatar (2c); null = the initial on the username's default color. */
    val avatar: AvatarSpec? = null,
)

enum class ProfileLevel(val label: String) {
    Admin("Admin"), Standard("Standard"), Limited("Limited");

    companion object {
        fun of(userLevel: Int): ProfileLevel = when {
            userLevel >= 10 -> Admin
            userLevel >= 1 -> Standard
            else -> Limited
        }
    }
}

/**
 * Device-local profile records (per device, never synced in phase 1).
 * SharedPreferences rather than Room so the active profile is readable
 * synchronously in Application.onCreate, before any scoped read runs.
 */
@Singleton
class ProfileStore @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: CredentialCipher,
) {
    private val prefs = context.getSharedPreferences("aerio_profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _profiles = MutableStateFlow(load())
    /** Decrypted profiles, every playlist. */
    val profiles: StateFlow<List<DispatcharrProfile>> = _profiles.asStateFlow()

    private fun load(): List<DispatcharrProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<DispatcharrProfile>>(raw) }
            .onFailure { Log.w(TAG, "[PROFILE] store unreadable: ${it.message}") }
            .getOrDefault(emptyList())
            .map { it.copy(password = cipher.decrypt(it.password), apiKey = cipher.decrypt(it.apiKey)) }
    }

    @Synchronized
    private fun save(list: List<DispatcharrProfile>) {
        val encrypted = list.map {
            it.copy(password = cipher.encrypt(it.password), apiKey = cipher.encrypt(it.apiKey))
        }
        prefs.edit().putString(KEY_PROFILES, json.encodeToString(encrypted)).apply()
        _profiles.value = list
    }

    fun forPlaylist(playlistId: String): List<DispatcharrProfile> =
        _profiles.value.filter { it.playlistId == playlistId }
            .sortedWith(compareByDescending<DispatcharrProfile> { it.isDefault }.thenBy { it.createdAt })

    fun byId(id: String): DispatcharrProfile? = _profiles.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(profile: DispatcharrProfile) {
        val list = _profiles.value.filterNot { it.id == profile.id } + profile
        save(list)
    }

    @Synchronized
    fun update(id: String, transform: (DispatcharrProfile) -> DispatcharrProfile) {
        val list = _profiles.value.map { if (it.id == id) transform(it) else it }
        save(list)
    }

    @Synchronized
    fun remove(id: String) {
        val p = byId(id) ?: return
        save(_profiles.value.filterNot { it.id == id })
        if (activeProfileId(p.playlistId) == id) prefs.edit().remove(KEY_ACTIVE + p.playlistId).commit()
    }

    @Synchronized
    fun removeForPlaylist(playlistId: String) {
        save(_profiles.value.filterNot { it.playlistId == playlistId })
        prefs.edit().remove(KEY_ACTIVE + playlistId).commit()
    }

    /** Active profile id for [playlistId] on this device; null = default. */
    fun activeProfileId(playlistId: String): String? = prefs.getString(KEY_ACTIVE + playlistId, null)

    fun setActiveProfileId(playlistId: String, profileId: String?) {
        prefs.edit().apply {
            if (profileId == null) remove(KEY_ACTIVE + playlistId) else putString(KEY_ACTIVE + playlistId, profileId)
        }.commit()
    }

    /** TV launch picker toggle ("Ask who is watching at launch"), per playlist. */
    fun askAtLaunch(playlistId: String): Boolean = prefs.getBoolean(KEY_ASK + playlistId, true)
    fun setAskAtLaunch(playlistId: String, on: Boolean) { prefs.edit().putBoolean(KEY_ASK + playlistId, on).commit() }

    /** One-time base profile promotion to local Admin, per playlist. */
    fun basePromoted(playlistId: String): Boolean = prefs.getBoolean(KEY_BASE_PROMOTED + playlistId, false)
    fun markBasePromoted(playlistId: String) { prefs.edit().putBoolean(KEY_BASE_PROMOTED + playlistId, true).apply() }

    /** Last active playlist id, so onCreate can restore the scope synchronously. */
    var lastActivePlaylistId: String?
        get() = prefs.getString(KEY_LAST_PLAYLIST, null)
        set(value) { prefs.edit().putString(KEY_LAST_PLAYLIST, value).commit() }

    private companion object {
        const val TAG = "AerioProfiles"
        const val KEY_PROFILES = "profiles.v1"
        const val KEY_ACTIVE = "active."
        const val KEY_LAST_PLAYLIST = "last_active_playlist"
        const val KEY_ASK = "ask_at_launch."
        const val KEY_BASE_PROMOTED = "base_admin_promoted."
    }
}

fun DispatcharrProfile.toOverlay(): ProfileScope.Overlay = ProfileScope.Overlay(
    playlistId = playlistId,
    profileId = id,
    apiKey = apiKey,
    username = username,
    password = password,
    userLevel = userLevel,
    dvrAccess = dvrAccess,
    catchupEnabled = catchupEnabled,
    vodMoviesEnabled = vodMoviesEnabled,
    vodSeriesEnabled = vodSeriesEnabled,
    accountProfileIds = accountProfileIds,
    isStaff = isStaff,
    isSuperuser = isSuperuser,
    customProperties = customProperties,
    capabilitiesFetchedAt = capabilitiesFetchedAt,
    capabilitiesSchema = capabilitiesSchema,
    capabilitiesStale = capabilitiesStale,
)

fun DispatcharrProfile.withOverlay(o: ProfileScope.Overlay): DispatcharrProfile = copy(
    apiKey = o.apiKey,
    password = o.password,
    userLevel = o.userLevel,
    dvrAccess = o.dvrAccess,
    catchupEnabled = o.catchupEnabled,
    vodMoviesEnabled = o.vodMoviesEnabled,
    vodSeriesEnabled = o.vodSeriesEnabled,
    accountProfileIds = o.accountProfileIds,
    isStaff = o.isStaff,
    isSuperuser = o.isSuperuser,
    customProperties = o.customProperties,
    capabilitiesFetchedAt = o.capabilitiesFetchedAt,
    capabilitiesSchema = o.capabilitiesSchema,
    capabilitiesStale = o.capabilitiesStale,
)
