package com.aeriotv.android.core.profiles

import android.util.Log
import com.aeriotv.android.core.data.capability.CAPABILITIES_SCHEMA
import com.aeriotv.android.core.data.capability.CapabilityCorrections
import com.aeriotv.android.core.data.db.dao.ChannelSnapshotDao
import com.aeriotv.android.core.data.db.dao.EncryptingPlaylistDao
import com.aeriotv.android.core.data.db.dao.FavoriteChannelDao
import com.aeriotv.android.core.data.db.dao.LocalRecordingDao
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.ReminderDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.db.entity.isDispatcharrDirectConnect
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.data.vod.VodCatalogStore
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.DispatcharrError
import com.aeriotv.android.core.network.DispatcharrLoginGate
import com.aeriotv.android.core.network.DispatcharrTokenStore
import com.aeriotv.android.core.network.DispatcharrUserSummary
import com.aeriotv.android.core.network.MeResponse
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.timeshift.TimeshiftController
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Profiles for Dispatcharr playlists (phase 1, 2026-10-16). Owns the profile
 * records, the one-time migration (each Dispatcharr playlist gets exactly one
 * default profile named after its login), add / rename / remove, and the
 * switch flow. Xtream and M3U playlists never get a profile.
 *
 * PHASE 2 HOOKS: Drive sync of profiles (DriveSyncManager runs under
 * ProfileScope.defaultOnly() today), TV launch picker.
 */
@Singleton
class ProfileManager @Inject constructor(
    private val store: ProfileStore,
    private val playlistDao: PlaylistDao,
    private val repository: PlaylistRepository,
    private val client: DispatcharrClient,
    private val tokenStore: DispatcharrTokenStore,
    private val appPreferences: AppPreferences,
    private val favoriteDao: FavoriteChannelDao,
    private val watchProgressDao: WatchProgressDao,
    private val reminderDao: ReminderDao,
    private val localRecordingDao: LocalRecordingDao,
    private val channelSnapshotDao: ChannelSnapshotDao,
    private val vodCatalogStore: VodCatalogStore,
    private val timeshift: TimeshiftController,
    private val watchlistStore: com.aeriotv.android.core.preferences.WatchlistStore,
    private val hiddenTitlesStore: com.aeriotv.android.core.preferences.HiddenTitlesStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val pinStore: ProfilePinStore,
    private val snapshotStamps: ChannelSnapshotStamps,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var started = false

    /** Playlist ids whose channel list / groups / VOD must reload after a
     *  profile switch. A Channel so exactly one PlaylistViewModel handles it. */
    data class Reload(val playlistId: String, val gen: Long, val fromSwitch: Boolean = true)
    private val reloads = Channel<Reload>(Channel.BUFFERED)
    val reloadRequests: Flow<Reload> = reloads.receiveAsFlow()

    /** True from the first tap of a switch until the reload for the new
     *  profile has finished. MainActivity draws a blocking scrim while set. */
    private val _switching = kotlinx.coroutines.flow.MutableStateFlow(false)
    val switching: kotlinx.coroutines.flow.StateFlow<Boolean> = _switching

    /** Fired when a switch completes: any player still open is closed. */
    private val closePlayer = Channel<Unit>(Channel.CONFLATED)
    val closePlayerRequests: Flow<Unit> = closePlayer.receiveAsFlow()

    // Each switch gets its own generation; the watchdog and the completion
    // only act on the generation they belong to, so a stale timer from an
    // earlier switch can never release a newer switch's scrim.
    private val switchGen = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var watchdog: kotlinx.coroutines.Job? = null

    /** Called by PlaylistViewModel when the reload for [gen] has landed. */
    fun finishSwitch(gen: Long) {
        if (gen != switchGen.get()) return
        watchdog?.cancel()
        watchdog = null
        _switching.value = false
        closePlayer.trySend(Unit)
        ProfileSwitchFocus.onScrimReleased()
        val pp = pendingProbe?.takeIf { it.gen == gen } ?: return
        pendingProbe = null
        scope.launch {
            val t0 = System.currentTimeMillis()
            runCatching { repository.probeCapabilities(pp.playlistId, force = true, trigger = "profile switch") }
            val t1 = System.currentTimeMillis()
            Log.i(TAG, "[SWITCH] deferred probe=${t1 - t0}ms (scrim dropped ${t0 - pp.queuedAt}ms after queue)")
            refreshActiveRole(pp.playlistId)
        }
    }

    private data class PendingProbe(val playlistId: String, val gen: Long, val queuedAt: Long)
    @Volatile private var pendingProbe: PendingProbe? = null

    /** Header switcher "Manage Profiles": open Settings > this playlist. */
    private val manage = Channel<Unit>(Channel.CONFLATED)
    val manageRequests: Flow<Unit> = manage.receiveAsFlow()
    fun requestManageProfiles() { manage.trySend(Unit) }

    /**
     * Synchronous restore, called first thing in Application.onCreate so no
     * scoped read ever sees the default profile's state by mistake.
     */
    fun restoreSync() {
        ProfileScope.onOverlayChanged = { o -> store.update(o.profileId) { it.withOverlay(o) } }
        ProfileScope.activeUserIdLookup = { pid -> activeProfileIdNow(pid)?.let(store::byId)?.userId ?: 0 }
        scope.launch {
            store.profiles.collect { list ->
                ProfileScope.profileUsernames = list.filterNot { it.isDefault }
                    .groupBy { it.playlistId }
                    .mapValues { (_, v) -> v.map { it.username }.toSet() }
                refreshActiveRole()
            }
        }
        val pid = store.lastActivePlaylistId ?: return
        refreshActiveRole(pid)
        val active = store.activeProfileId(pid)?.let(store::byId)
        if (active != null && !active.isDefault && active.playlistId == pid) {
            ProfileScope.activate(active.toOverlay())
            Log.i(TAG, "[PROFILE] restored ${active.displayName} on ${pid.take(8)}")
        }
        // PIN: a locked restored profile (the default one included) shows the
        // PIN gate before any content, and nothing may start playback under it.
        val restored = active?.takeIf { it.playlistId == pid }
            ?: store.forPlaylist(pid).firstOrNull { it.isDefault }
        // TV launch picker (phase 2b): "Ask who is watching at launch". A
        // Child goes straight in; its PIN (if any) is the launch gate below.
        val all = store.forPlaylist(pid)
        if (RoleRules.showLaunchPicker(isTvDevice(), all.isNotEmpty(), store.askAtLaunch(pid), all.size, restored?.effectiveRole())) {
            _launchPicker.value = LaunchPicker(pid, restored?.id)
            Log.i(TAG, "[PROFILE] launch picker armed (${all.size} profiles)")
            return
        }
        if (restored != null && pinStore.hasPin(restored.id)) {
            _launchGate.value = restored
            Log.i(TAG, "[PROFILE] launch gate armed for ${restored.username}")
        }
    }

    // ---- PIN (phase 2a) ----

    sealed interface PinCheck {
        data object Ok : PinCheck
        data object Wrong : PinCheck
        data class Locked(val seconds: Int) : PinCheck
    }

    private val _launchGate = kotlinx.coroutines.flow.MutableStateFlow<DispatcharrProfile?>(null)
    /** The locked profile whose PIN must be entered before any content shows. */
    val launchGate: kotlinx.coroutines.flow.StateFlow<DispatcharrProfile?> = _launchGate

    /** True while the launch gate or the TV launch picker is up: no playback may start (media service). */
    fun isPinGated(): Boolean = _launchGate.value != null || _launchPicker.value != null

    // ---- TV launch picker (phase 2b) ----

    data class LaunchPicker(val playlistId: String, val lastProfileId: String?)

    private val _launchPicker = kotlinx.coroutines.flow.MutableStateFlow<LaunchPicker?>(null)
    /** Non-null while the TV "who is watching" picker covers the app at launch. */
    val launchPicker: kotlinx.coroutines.flow.StateFlow<LaunchPicker?> = _launchPicker

    /**
     * Picker choice. A locked profile has already passed [verifyPin] (its
     * ticket). The current profile just closes the picker; another one runs
     * the normal switch (scrim, reload).
     */
    suspend fun pickAtLaunch(profileId: String): Result<Unit> {
        val pk = _launchPicker.value ?: return Result.success(Unit)
        if (pinStore.hasPin(profileId) && !switchTickets.contains(profileId)) return Result.failure(IllegalStateException("Enter PIN"))
        if (profileId == activeProfileIdNow(pk.playlistId)) {
            switchTickets.remove(profileId)
            _launchPicker.value = null
            ProfileSwitchFocus.arm(ProfileSwitchFocus.Target.Guide)
            ProfileSwitchFocus.onScrimReleased()
            Log.i(TAG, "[PROFILE] launch picker: kept ${store.byId(profileId)?.username}")
            return Result.success(Unit)
        }
        Log.i(TAG, "[PROFILE] launch picker: chose ${store.byId(profileId)?.username}")
        ProfileSwitchFocus.arm(ProfileSwitchFocus.Target.Guide)
        // Run on the manager's scope and keep the picker up until the switch
        // has landed: the picker's own composition (and its scope) goes away
        // the moment it closes, which used to cancel the switch mid-flight.
        val r = scope.async { switchTo(pk.playlistId, profileId) }.await()
        if (r.isSuccess) _launchPicker.value = null
        return r
    }

    /** True while [profileId] holds a one-shot ticket from a verified PIN. */
    fun hasSwitchTicket(profileId: String): Boolean = switchTickets.contains(profileId)

    fun askAtLaunch(playlistId: String): Boolean = store.askAtLaunch(playlistId)
    fun setAskAtLaunch(playlistId: String, on: Boolean) {
        store.setAskAtLaunch(playlistId, on)
        Log.i(TAG, "[PROFILE] ask who is watching at launch = $on")
    }

    private fun isTvDevice(): Boolean {
        val ui = context.getSystemService(android.app.UiModeManager::class.java)
        return ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }

    // ---- Roles (phase 2b) ----

    /** Recompute [ProfileRoles.active] from the active playlist's active profile. */
    private fun refreshActiveRole(pid: String? = store.lastActivePlaylistId) {
        val role = pid?.let { activeProfileIdNow(it) }?.let(store::byId)?.effectiveRole()
        if (ProfileRoles.active.value != role) {
            ProfileRoles.set(role)
            Log.i(TAG, "[PROFILE] active role ${role?.label ?: "none"}")
        }
    }

    fun roleOf(profileId: String): ProfileRole? = store.byId(profileId)?.effectiveRole()

    fun activeRole(playlistId: String): ProfileRole? = activeProfileIdNow(playlistId)?.let(store::byId)?.effectiveRole()

    fun anyChild(playlistId: String): Boolean = store.forPlaylist(playlistId).any { it.effectiveRole() == ProfileRole.Child }

    /** Admin profiles on [playlistId] that have a PIN. */
    fun adminPinHolders(playlistId: String): List<DispatcharrProfile> =
        store.forPlaylist(playlistId).filter { it.effectiveRole() == ProfileRole.Admin && pinStore.hasPin(it.id) }

    fun adminHasPin(playlistId: String): Boolean = adminPinHolders(playlistId).isNotEmpty()

    /** The acting (active) profile on [playlistId]. */
    fun activeProfile(playlistId: String): DispatcharrProfile? = activeProfileIdNow(playlistId)?.let(store::byId)

    /** One-shot Admin authorizations per playlist (an Admin PIN was entered). */
    private val adminTickets = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * "Enter an Admin PIN": any Admin profile's PIN on [playlistId] passes.
     * Each Admin keeps its own wrong-PIN backoff. Ok grants one Admin ticket
     * and a switch ticket for the matching Admin.
     */
    @Synchronized
    fun verifyAnyAdminPin(playlistId: String, entries: List<String>): PinCheck {
        val holders = adminPinHolders(playlistId)
        if (holders.isEmpty()) return PinCheck.Wrong
        val now = System.currentTimeMillis()
        var minLock = Int.MAX_VALUE
        var tried = false
        for (h in holders) {
            val b = pinStore.backoff(h.id)
            val left = b.remainingSeconds(now)
            if (left > 0) { minLock = minOf(minLock, left); continue }
            tried = true
            if (pinStore.check(h.id, entries)) {
                pinStore.saveBackoff(h.id, b.onOk())
                switchTickets.add(h.id)
                adminTickets.add(playlistId)
                lastAdminUnlock = h.id
                Log.i(TAG, "[PROFILE] pin ok for ${h.username}")
                return PinCheck.Ok
            }
        }
        // Count the miss against every Admin that was tried.
        var lock = Int.MAX_VALUE
        holders.forEach { h ->
            val b = pinStore.backoff(h.id)
            if (b.remainingSeconds(now) > 0) return@forEach
            val next = b.onWrong(now)
            pinStore.saveBackoff(h.id, next)
            next.remainingSeconds(now).takeIf { it > 0 }?.let { lock = minOf(lock, it) }
            Log.i(TAG, "[PROFILE] pin wrong for ${h.username}")
        }
        if (!tried) return PinCheck.Locked(minLock)
        // Locked only when no Admin is left to try.
        val anyOpen = holders.any { pinStore.backoff(it.id).remainingSeconds(now) == 0 }
        return if (anyOpen) PinCheck.Wrong else PinCheck.Locked(minOf(lock, minLock))
    }

    /** The Admin whose PIN last passed [verifyAnyAdminPin] (holds a switch ticket). */
    @Volatile var lastAdminUnlock: String? = null
        private set

    /** True once per entered Admin PIN. */
    fun consumeAdminTicket(playlistId: String): Boolean = adminTickets.remove(playlistId)

    /** Pure decision for the UI: what setting [profileId] to [to] needs. */
    fun roleChangeGate(playlistId: String, profileId: String, to: ProfileRole): RoleRules.RoleChange {
        val target = store.byId(profileId) ?: return RoleRules.RoleChange.Denied("Profile not found")
        val admins = store.forPlaylist(playlistId).count { it.effectiveRole() == ProfileRole.Admin }
        return RoleRules.roleChange(
            actor = activeRole(playlistId),
            from = target.effectiveRole(),
            to = to,
            adminHasPin = adminHasPin(playlistId),
            targetIsLastAdmin = target.effectiveRole() == ProfileRole.Admin && admins <= 1,
        )
    }

    /** Set [profileId]'s role. Leaving Child needs an Admin ticket ([verifyAnyAdminPin]). */
    fun setRole(playlistId: String, profileId: String, to: ProfileRole): Result<Unit> = runCatching {
        val target = store.byId(profileId) ?: error("Profile not found")
        when (val g = roleChangeGate(playlistId, profileId, to)) {
            is RoleRules.RoleChange.Denied -> error(g.reason)
            RoleRules.RoleChange.NeedsAdminPinCreated -> error("Set a PIN on an Admin profile first.")
            RoleRules.RoleChange.NeedsAdminPinEntry -> check(consumeAdminTicket(playlistId)) { "Enter an Admin PIN" }
            RoleRules.RoleChange.Allowed -> Unit
        }
        val from = target.effectiveRole()
        store.update(profileId) { it.copy(role = to) }
        mutated(profileId, "role")
        refreshActiveRole(playlistId)
        Log.i(TAG, "[PROFILE] role ${from.label} -> ${to.label} for ${target.username}")
    }

    /** One-shot tickets: a verified PIN lets exactly one switch through. */
    private val switchTickets = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun hasPin(profileId: String): Boolean = pinStore.hasPin(profileId)
    fun pinType(profileId: String): PinType? = pinStore.type(profileId)
    fun pinLength(profileId: String): Int? = pinStore.length(profileId)
    fun pinLockSeconds(profileId: String): Int = pinStore.backoff(profileId).remainingSeconds(System.currentTimeMillis())

    /** Check [entries] against [profileId]'s PIN, with the wrong-PIN backoff. */
    @Synchronized
    fun verifyPin(profileId: String, entries: List<String>): PinCheck {
        val p = store.byId(profileId)
        val now = System.currentTimeMillis()
        val b = pinStore.backoff(profileId)
        b.remainingSeconds(now).takeIf { it > 0 }?.let { return PinCheck.Locked(it) }
        return if (pinStore.check(profileId, entries)) {
            pinStore.saveBackoff(profileId, b.onOk())
            switchTickets.add(profileId)
            Log.i(TAG, "[PROFILE] pin ok for ${p?.username}")
            PinCheck.Ok
        } else {
            val next = b.onWrong(now)
            pinStore.saveBackoff(profileId, next)
            Log.i(TAG, "[PROFILE] pin wrong for ${p?.username}")
            next.remainingSeconds(now).takeIf { it > 0 }?.let { PinCheck.Locked(it) } ?: PinCheck.Wrong
        }
    }

    /** Launch gate: enter the restored profile's PIN. */
    fun unlockLaunchGate(entries: List<String>): PinCheck {
        val g = _launchGate.value ?: return PinCheck.Ok
        val r = verifyPin(g.id, entries)
        if (r == PinCheck.Ok) {
            switchTickets.remove(g.id)
            _launchGate.value = null
            ProfileSwitchFocus.arm(ProfileSwitchFocus.Target.Guide)
            ProfileSwitchFocus.onScrimReleased()
        }
        return r
    }

    /** An Admin profile may manage any profile's PIN; anyone else only their own. */
    suspend fun canManagePin(playlistId: String, profileId: String): Boolean =
        activeRole(playlistId) == ProfileRole.Admin || activeProfileIdNow(playlistId) == profileId

    /** Changing or removing ANY PIN needs that profile's existing PIN (phase 2b). */
    @Suppress("UNUSED_PARAMETER")
    suspend fun needsCurrentPin(playlistId: String, profileId: String): Boolean = pinStore.hasPin(profileId)

    /** Null = removable; otherwise why the PIN must stay (last Admin PIN while a Child exists). */
    fun pinRemovalBlock(playlistId: String, profileId: String): String? {
        val role = roleOf(profileId) ?: return null
        val others = adminPinHolders(playlistId).count { it.id != profileId }
        return if (RoleRules.canRemoveAdminPin(role, anyChild(playlistId), others)) null
        else "This is the last Admin PIN. A Child profile needs an Admin PIN to leave it, so this PIN stays while any profile is a Child."
    }

    /** What an Admin reset of another profile's PIN needs. */
    fun resetGate(playlistId: String): RoleRules.ResetGate {
        val actor = activeProfile(playlistId)
        return RoleRules.resetGate(activeRole(playlistId), actor?.let { pinStore.hasPin(it.id) } == true, anyChild(playlistId))
    }

    /**
     * Admin reset of [profileId]'s forgotten PIN. [adminEntries] is the acting
     * Admin's own PIN (null for the confirm-only case).
     */
    suspend fun resetPin(playlistId: String, profileId: String, adminEntries: List<String>?): Result<Unit> = runCatching {
        val actor = activeProfile(playlistId) ?: error("Profile not found")
        check(actor.id != profileId) { "Use Remove PIN for this profile." }
        pinRemovalBlock(playlistId, profileId)?.let { error(it) }
        when (val g = resetGate(playlistId)) {
            is RoleRules.ResetGate.Denied -> error(g.reason)
            RoleRules.ResetGate.EnterOwnPin -> {
                check(verifyPin(actor.id, adminEntries.orEmpty()) == PinCheck.Ok) { "Wrong PIN" }
                switchTickets.remove(actor.id)
            }
            RoleRules.ResetGate.ConfirmOnly -> Unit
        }
        pinStore.remove(profileId)
        mutated(profileId, "pin reset")
        Log.i(TAG, "[PROFILE] pin removed for ${store.byId(profileId)?.username}")
    }

    suspend fun setPin(
        playlistId: String,
        profileId: String,
        type: PinType,
        entries: List<String>,
        current: List<String>?,
    ): Result<Unit> = runCatching {
        check(canManagePin(playlistId, profileId)) { "Only an Admin profile can change another profile's PIN." }
        val had = pinStore.hasPin(profileId)
        if (needsCurrentPin(playlistId, profileId)) {
            check(verifyPin(profileId, current.orEmpty()) == PinCheck.Ok) { "Wrong PIN" }
            switchTickets.remove(profileId)
        }
        check(pinStore.set(profileId, type, entries)) { "Enter 4 to 6" }
        mutated(profileId, "pin set")
        Log.i(TAG, "[PROFILE] pin ${if (had) "changed" else "set"} for ${store.byId(profileId)?.username}")
    }

    suspend fun removePin(playlistId: String, profileId: String, current: List<String>?): Result<Unit> = runCatching {
        check(canManagePin(playlistId, profileId)) { "Only an Admin profile can remove another profile's PIN." }
        pinRemovalBlock(playlistId, profileId)?.let { error(it) }
        if (needsCurrentPin(playlistId, profileId)) {
            check(verifyPin(profileId, current.orEmpty()) == PinCheck.Ok) { "Wrong PIN" }
            switchTickets.remove(profileId)
        }
        pinStore.remove(profileId)
        mutated(profileId, "pin removed")
        Log.i(TAG, "[PROFILE] pin removed for ${store.byId(profileId)?.username}")
    }

    private fun activeProfileIdNow(playlistId: String): String? =
        store.activeProfileId(playlistId)?.takeIf { store.byId(it) != null }
            ?: store.forPlaylist(playlistId).firstOrNull { it.isDefault }?.id

    // ---- Channel Profiles per user (phase 2a, Admin only) ----

    data class ChannelProfileChoice(val id: Int, val name: String)
    data class ChannelProfileAssignment(val userId: Int, val choices: List<ChannelProfileChoice>, val assigned: Set<Int>)

    /** The server's channel profiles and [profileId]'s user's current list. */
    suspend fun channelProfileAssignment(playlistId: String, profileId: String): Result<ChannelProfileAssignment> = runCatching {
        check(activeIsAdmin(playlistId)) { "Only an Admin profile can assign channel profiles." }
        val p = store.byId(profileId) ?: error("Profile not found")
        val row = playlistDao.byId(playlistId) ?: error("Playlist not found")
        val base = repository.effectiveBaseUrl(row)
        val key = row.apiKey?.takeIf { it.isNotBlank() } ?: error("No API key for this playlist")
        val user = client.listUsers(base, key).firstOrNull { it.username.equals(p.username, ignoreCase = true) }
            ?: error("${p.username} was not found on the server.")
        val choices = client.listProfiles(base, key).map { ChannelProfileChoice(it.id, it.name) }.sortedBy { it.name.lowercase() }
        ChannelProfileAssignment(user.id, choices, user.channelProfiles.toSet())
    }

    /** PATCH the user's channel_profiles (empty = All). Reloads channels when
     *  [profileId] is the active profile. */
    suspend fun saveChannelProfiles(playlistId: String, profileId: String, userId: Int, ids: List<Int>): Result<Unit> = runCatching {
        check(activeIsAdmin(playlistId)) { "Only an Admin profile can assign channel profiles." }
        val p = store.byId(profileId) ?: error("Profile not found")
        val row = playlistDao.byId(playlistId) ?: error("Playlist not found")
        val base = repository.effectiveBaseUrl(row)
        val key = row.apiKey?.takeIf { it.isNotBlank() } ?: error("No API key for this playlist")
        client.patchUserChannelProfiles(base, key, userId, ids)
        val joined = ids.sorted().joinToString(",")
        // The saved assignment is what the snapshot stamp is checked against.
        if (!p.isDefault) {
            store.update(profileId) { it.copy(accountProfileIds = joined) }
        } else if (activeProfileIdNow(playlistId) != profileId) {
            kotlinx.coroutines.withContext(ProfileScope.defaultOnly()) {
                playlistDao.rawById(playlistId)?.let { playlistDao.update(it.copy(dispatcharrAccountProfileIds = joined)) }
            }
        }
        Log.i(TAG, "[PROFILE] channel profiles for ${p.username} set to ${if (ids.isEmpty()) "All" else joined}")
        if (activeProfileIdNow(playlistId) == profileId) {
            if (p.isDefault) {
                playlistDao.rawById(playlistId)?.let { playlistDao.update(it.copy(dispatcharrAccountProfileIds = joined)) }
            } else {
                ProfileScope.updateOverlay { it.copy(accountProfileIds = joined) }
            }
            reloads.send(Reload(playlistId, switchGen.get(), fromSwitch = false))
        }
    }

    /** Migration plus active-playlist tracking. Idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            runCatching { playlistDao.rawAll().forEach { ensureDefaultProfile(it); promoteBaseProfile(it.id) } }
                .onFailure { Log.w(TAG, "[PROFILE] migration pass failed", it) }
            playlistDao.observeActive()
                .map { it.firstOrNull()?.id }
                .distinctUntilChanged()
                .collect { pid -> onActivePlaylist(pid) }
        }
    }

    private suspend fun onActivePlaylist(pid: String?) = mutex.withLock {
        if (pid == null) { ProfileScope.activate(null); ProfileRoles.set(null); return@withLock }
        store.lastActivePlaylistId = pid
        playlistDao.rawById(pid)?.let { ensureDefaultProfile(it) }
        val active = store.activeProfileId(pid)?.let(store::byId)
        val current = ProfileScope.activeOverlay()
        if (active != null && !active.isDefault) {
            if (current?.profileId != active.id) ProfileScope.activate(active.toOverlay())
        } else if (current != null) {
            ProfileScope.activate(null)
        }
        refreshActiveRole(pid)
    }

    /**
     * One-time: an existing base profile (the login that added the playlist)
     * becomes local Admin whatever its Dispatcharr level. A Child base stays.
     */
    private fun promoteBaseProfile(playlistId: String) {
        if (store.basePromoted(playlistId)) return
        val base = store.forPlaylist(playlistId).firstOrNull { it.isDefault } ?: return
        ProfileRole.basePromotion(base.role)?.let { to ->
            val wasAdmin = base.role == null && ProfileLevel.of(base.userLevel) == ProfileLevel.Admin
            store.update(base.id) { it.copy(role = to) }
            if (!wasAdmin) {
                Log.i(TAG, "[PROFILE] base profile promoted to local Admin (${base.username}, level ${ProfileLevel.of(base.userLevel).label})")
                mutated(base.id, "role")
            }
        }
        store.markBasePromoted(playlistId)
    }

    /** Every Dispatcharr playlist has exactly one default profile. Its state
     *  is the legacy (unscoped) state, so creating it moves no data. */
    private suspend fun ensureDefaultProfile(row: PlaylistEntity) {
        if (!row.isDispatcharrDirectConnect()) return
        if (store.forPlaylist(row.id).any { it.isDefault }) return
        var username = row.username.orEmpty()
        if (username.isBlank()) {
            // API-key login: ask the server who the key belongs to.
            val key = row.apiKey
            if (!key.isNullOrBlank()) {
                username = runCatching { client.fetchMe(repository.effectiveBaseUrl(row), key)?.username }
                    .getOrNull().orEmpty()
            }
        }
        store.upsert(
            DispatcharrProfile(
                id = UUID.randomUUID().toString(),
                playlistId = row.id,
                displayName = username.ifBlank { row.name },
                username = username,
                isDefault = true,
                userLevel = row.dispatcharrUserLevel,
                // The base login is always the local Admin.
                role = ProfileRole.Admin,
                createdAt = 0L,
            ),
        )
        Log.i(TAG, "[PROFILE] migrated playlist ${row.id} -> default profile")
    }

    fun profiles(playlistId: String): Flow<List<DispatcharrProfile>> =
        store.profiles.map { store.forPlaylist(playlistId) }

    /** Active profile id per playlist (default profile when none is set). */
    fun activeProfileId(playlistId: String): Flow<String?> =
        combine(store.profiles, ProfileScope.token) { _, _ ->
            store.activeProfileId(playlistId)?.takeIf { store.byId(it) != null }
                ?: store.forPlaylist(playlistId).firstOrNull { it.isDefault }?.id
        }

    /** Admin profiles may add and remove profiles. Reads the ACTIVE user. */
    suspend fun activeIsAdmin(playlistId: String): Boolean {
        val row = playlistDao.byId(playlistId) ?: return false
        return row.dispatcharrUserLevel >= 10 || row.dispatcharrIsSuperuser || row.dispatcharrIsStaff
    }

    /** The BASE login (the playlist row's own credentials) is a server admin. */
    suspend fun baseIsServerAdmin(playlistId: String): Boolean {
        val row = playlistDao.rawById(playlistId) ?: return false
        return row.dispatcharrUserLevel >= 10 || row.dispatcharrIsSuperuser || row.dispatcharrIsStaff
    }

    /** Result of [addCandidates]: the server's users, or the manual username form. */
    sealed interface AddCandidates {
        data class ServerList(val users: List<DispatcharrUserSummary>) : AddCandidates
        data object Manual : AddCandidates
    }

    /**
     * Add Profile entry: a server admin gets the users list (keyless adds);
     * anyone else, or a 403 from GET /api/accounts/users/, gets the manual
     * form (username, then password or API key, validated by signing in).
     */
    suspend fun addCandidates(playlistId: String): Result<AddCandidates> = runCatching {
        check(canAdd(playlistId)) { "Only an Admin profile can add profiles." }
        val serverAdmin = baseIsServerAdmin(playlistId) && activeIsAdmin(playlistId)
        if (RoleRules.addPath(serverAdmin, usersListForbidden = false) == RoleRules.AddPath.Manual) {
            return@runCatching AddCandidates.Manual
        }
        val listed = listServerUsers(playlistId)
        val forbidden = listed.exceptionOrNull()?.message?.let { it.contains("HTTP 403") || it.contains("HTTP 401") } == true
        when (RoleRules.addPath(serverAdmin, forbidden)) {
            RoleRules.AddPath.Manual -> AddCandidates.Manual
            RoleRules.AddPath.ServerList -> AddCandidates.ServerList(listed.getOrThrow())
        }
    }

    /** Manual add (non-admin base): [username] signs in with [password] or [apiKey]. */
    suspend fun addProfileManual(playlistId: String, username: String, password: String?, apiKey: String?): Result<DispatcharrProfile> {
        val name = username.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("Enter a username."))
        if (store.forPlaylist(playlistId).any { it.username.equals(name, ignoreCase = true) }) {
            return Result.failure(IllegalStateException("$name already has a profile."))
        }
        return addProfile(playlistId, DispatcharrUserSummary(id = 0, username = name), password, apiKey)
    }

    /**
     * Manual add by API key only: GET /api/accounts/users/me/ with [apiKey]
     * names the user (username, level, id). A key for a user that already
     * has a profile here is rejected.
     */
    suspend fun addProfileByApiKey(playlistId: String, apiKey: String): Result<DispatcharrProfile> {
        val key = apiKey.trim()
        if (key.isEmpty()) return Result.failure(IllegalArgumentException("Enter an API key."))
        val me = runCatching {
            val row = playlistDao.rawById(playlistId) ?: error("Playlist not found")
            client.fetchMe(repository.effectiveBaseUrl(row), key)
        }.getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalStateException("This API key was not accepted by the server."))
        if (store.forPlaylist(playlistId).any { it.username.equals(me.username, ignoreCase = true) }) {
            return Result.failure(IllegalStateException("${me.username} already has a profile."))
        }
        return addProfile(
            playlistId,
            DispatcharrUserSummary(id = me.id, username = me.username, userLevel = me.userLevel, isSuperuser = me.isSuperuser, isStaff = me.isStaff),
            null,
            key,
        )
    }

    /**
     * The TV picker's Add Profile (2c): an Admin PIN (or no Admin PIN on the
     * playlist) unlocks adding for one dialog, whoever is active.
     */
    @Volatile var pickerAddUnlocked: String? = null

    private fun canAdd(playlistId: String): Boolean =
        RoleRules.canManageProfiles(activeRole(playlistId)) || pickerAddUnlocked == playlistId

    /** Add Profile step 1: the server's users, fetched with the admin key. */
    suspend fun listServerUsers(playlistId: String): Result<List<DispatcharrUserSummary>> = runCatching {
        val row = playlistDao.byId(playlistId) ?: error("Playlist not found")
        check(activeIsAdmin(playlistId) && canAdd(playlistId)) { "Only an Admin profile can add profiles." }
        val base = repository.effectiveBaseUrl(row)
        val key = row.apiKey?.takeIf { it.isNotBlank() } ?: error("No API key for this playlist")
        val taken = store.forPlaylist(playlistId).map { it.username }.toSet()
        client.listUsers(base, key).filter { it.username !in taken }.sortedBy { it.username.lowercase() }
    }

    /**
     * Add Profile step 2: validate by signing in as [user] once (password, or
     * API key), then store the credential encrypted. Never changes the server.
     */
    suspend fun addProfile(
        playlistId: String,
        user: DispatcharrUserSummary,
        password: String?,
        apiKey: String?,
    ): Result<DispatcharrProfile> = runCatching {
        // Local Admin role, not the server level: a profile is added by
        // signing in as that user, which never changes the server.
        check(syncAdding || canAdd(playlistId)) { "Only an Admin profile can add profiles." }
        val row = playlistDao.rawById(playlistId) ?: error("Playlist not found")
        val base = repository.effectiveBaseUrl(row)
        val id = UUID.randomUUID().toString()
        // The admin users list carries each user's api_key: use it directly
        // (validated below by reading /me/ AS that user) and only fall back
        // to a typed password or key when the server has none.
        val listedKey = user.apiKey?.takeIf { it.isNotBlank() }
        val key = if (password.isNullOrBlank() && apiKey.isNullOrBlank() && listedKey != null) {
            authenticate(playlistId, id, base, user.username, null, listedKey).key
        } else {
            authenticate(playlistId, id, base, user.username, password, apiKey).key
        }
        val me = client.fetchMe(base, key) ?: error("Could not read ${user.username} from the server.")
        if (!me.username.equals(user.username, ignoreCase = true)) {
            error("This API key belongs to ${me.username}, not ${user.username}.")
        }
        val profile = DispatcharrProfile(
            id = id,
            playlistId = playlistId,
            displayName = user.username,
            username = me.username,
            password = password?.takeIf { it.isNotBlank() },
        ).applyMe(me, key)
        store.upsert(profile)
        appPreferences.seedProfileSettings(id)
        if (!syncAdding) mutated(id, "add")
        Log.i(TAG, "[PROFILE] added ${profile.username} on ${playlistId.take(8)} level=${ProfileLevel.of(profile.userLevel).label}")
        profile
    }

    // ---- Dispatcharr Sync `profiles` key (admin only, schema 4.12 and 5) ----

    /** Local profile id of [username] on [playlistId], or null. */
    /**
     * Dispatcharr Sync hook: every profile list mutation (add, remove, rename,
     * role, PIN) reports here so the `profiles` key is stamped and re-sent.
     * Set by DispatcharrSyncManager.
     */
    @Volatile var onProfilesMutated: ((playlistId: String, username: String, what: String) -> Unit)? = null

    private fun mutated(profileId: String, what: String) {
        val p = store.byId(profileId) ?: return
        runCatching { onProfilesMutated?.invoke(p.playlistId, p.username, what) }
    }

    fun profileIdFor(playlistId: String, username: String): String? =
        store.forPlaylist(playlistId).firstOrNull { it.username.equals(username, ignoreCase = true) }?.id

    @Volatile private var syncAdding = false

    /**
     * Merge a pulled `profiles` list on a device signed in as the admin, PER
     * ROW (schema 4.12): a missing profile is added with the api_key from the
     * admin user list (no typing) and adopts the row; an existing profile
     * takes the row's display name, role, and PIN only when the row's
     * updatedAt is newer than [localUpdatedAt] for that username. Local
     * profiles are never removed. [adopted] records the row stamp taken.
     * Returns how many profiles were added.
     */
    suspend fun mergeSyncedProfiles(
        playlistId: String,
        rows: List<com.aeriotv.android.core.sync.dsync.ProfileRow>,
        users: List<DispatcharrUserSummary>,
        localUpdatedAt: (String) -> Long = { 0L },
        adopted: (String, Long) -> Unit = { _, _ -> },
    ): Int {
        if (!RoleRules.canWriteProfilesKey(activeRole(playlistId))) return 0
        var added = 0
        rows.forEach { r ->
            var local = store.forPlaylist(playlistId).firstOrNull { it.username.equals(r.username, ignoreCase = true) }
            val isNew = local == null
            if (local == null) {
                val user = users.firstOrNull { it.id == r.userId }
                    ?: users.firstOrNull { it.username.equals(r.username, ignoreCase = true) }
                if (user == null || user.apiKey.isNullOrBlank()) {
                    Log.i(TAG, "[DSYNC] profiles: ${r.username} has no listed api key; not added")
                    return@forEach
                }
                syncAdding = true
                local = try {
                    addProfile(playlistId, user, null, null)
                        .onFailure { Log.w(TAG, "[DSYNC] profiles: add ${r.username} failed: ${it.message}") }
                        .getOrNull()
                } finally {
                    syncAdding = false
                }
                if (local == null) return@forEach
                added++
            }
            val id = local.id
            if (local.userId == 0 && r.userId > 0) store.update(id) { it.copy(userId = r.userId) }
            val mine = localUpdatedAt(local.username)
            if (!isNew && !com.aeriotv.android.core.sync.dsync.DsyncGate.profileRowWins(mine, r)) {
                Log.i(TAG, "[DSYNC] profiles: keep local row for ${local.username} (local $mine >= server ${r.updatedAt})")
                return@forEach
            }
            if (r.displayName.isNotBlank() && r.displayName != local.displayName) {
                store.update(id) { it.copy(displayName = r.displayName) }
            }
            ProfileRole.fromWire(r.role)?.let { role ->
                if (role != local.effectiveRole() || local.role == null) {
                    store.update(id) { it.copy(role = role) }
                    Log.i(TAG, "[DSYNC] profiles: role ${role.label} for ${local.username}")
                }
            }
            // Absent (older writer) keeps the local avatar; {} is a deliberate default.
            val avatar = r.avatar?.let(AvatarCatalog::normalize)
            if (r.avatar != null && avatar != local.avatar) {
                store.update(id) { it.copy(avatar = avatar) }
                Log.i(TAG, "[DSYNC] profiles: avatar for ${local.username}")
            }
            val type = PinRules.typeFromWire(r.pinType)
            if (type == null) {
                // A v1 PIN set on this device before sync stays until re-entered.
                if (pinStore.hasPin(id) && !pinStore.isLegacy(id)) {
                    pinStore.remove(id)
                    Log.i(TAG, "[DSYNC] profiles: pin removed for ${local.username} (server row newer)")
                }
            } else if (r.pinHash.length == 64 && r.pinSalt.length == 32) {
                pinStore.importSynced(id, type, r.pinHash, r.pinSalt)
            }
            adopted(local.username, r.updatedAt)
        }
        if (added > 0) Log.i(TAG, "[DSYNC] profiles: added $added from the synced list")
        refreshActiveRole()
        return added
    }

    /** Row menu "Avatar" (2c). Stamps the synced row like a rename. */
    fun setAvatar(playlistId: String, profileId: String, avatar: AvatarSpec?): Result<Unit> = runCatching {
        val target = store.byId(profileId) ?: error("Profile not found")
        val isOwn = activeProfileIdNow(playlistId) == profileId
        check(RoleRules.canEditAvatar(activeRole(playlistId), isOwn)) { "Only an Admin profile can change another profile's avatar." }
        val clean = AvatarCatalog.normalize(avatar)
        if (clean == target.avatar) return@runCatching
        store.update(profileId) { it.copy(avatar = clean) }
        mutated(profileId, "avatar")
        Log.i(TAG, "[PROFILE] avatar ${clean?.icon ?: AvatarCatalog.INITIAL}/${clean?.color?.ifEmpty { "default" } ?: "default"} for ${target.username}")
    }

    fun rename(profileId: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        store.update(profileId) { it.copy(displayName = clean) }
        mutated(profileId, "rename")
    }

    /** Remove a profile and its local state on this device. Never touches the
     *  server user. The default profile cannot be removed. */
    suspend fun remove(profileId: String): Result<Unit> = runCatching {
        val p = store.byId(profileId) ?: return@runCatching
        check(!p.isDefault) { "The default profile cannot be removed." }
        check(activeRole(p.playlistId) == ProfileRole.Admin) { "Only an Admin profile can remove profiles." }
        if (RoleRules.removeNeedsAdminPin(p.effectiveRole())) {
            check(consumeAdminTicket(p.playlistId)) { "Enter an Admin PIN" }
        }
        if (store.activeProfileId(p.playlistId) == profileId) {
            val def = store.forPlaylist(p.playlistId).first { it.isDefault }
            // The Admin removing the active profile: internal, no PIN prompt.
            switchTo(p.playlistId, def.id, bypassPin = true).getOrThrow()
        }
        runCatching { favoriteDao.deleteScope(profileId) }
        runCatching { watchProgressDao.deleteScope(profileId) }
        // Cancel the profile's scheduled reminder alarms BEFORE the rows go:
        // same PendingIntent identity as RemindersViewModel.cancelAlarm.
        runCatching {
            val mgr = context.getSystemService(android.app.AlarmManager::class.java)
            reminderDao.allOnceIn(profileId).forEach { r ->
                val pi = android.app.PendingIntent.getBroadcast(
                    context, r.alarmRequestCode,
                    android.content.Intent(context, com.aeriotv.android.feature.reminders.ReminderBroadcastReceiver::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
                mgr?.cancel(pi)
            }
            Log.i(TAG, "[PROFILE] cancelled reminder alarms for ${p.username}")
        }
        runCatching { reminderDao.deleteScope(profileId) }
        runCatching { watchlistStore.deleteScope(profileId) }
        runCatching { hiddenTitlesStore.deleteScope(profileId) }
        runCatching {
            localRecordingDao.allOnceIn(profileId).forEach { File(it.filePath).delete() }
            localRecordingDao.deleteScope(profileId)
        }
        runCatching { channelSnapshotDao.deleteForPlaylistKey("${p.playlistId}@$profileId") }
        runCatching { appPreferences.deleteProfileScope(profileId) }
        runCatching { pinStore.remove(profileId) }
        runCatching { snapshotStamps.remove("${p.playlistId}@$profileId") }
        runCatching { vodCatalogStore.deleteForPlaylistUser(p.playlistId, p.username) }
        runCatching { onProfilesMutated?.invoke(p.playlistId, p.username, "remove") }
        store.remove(profileId)
        Log.i(TAG, "[PROFILE] removed ${p.username} on ${p.playlistId.take(8)}; local state deleted")
    }

    /**
     * Switch [playlistId] to [profileId]. The caller has already confirmed
     * stopping playback; PlaylistViewModel stops it on the reload. Signs in
     * as the profile through DispatcharrLoginGate (one attempt per 60 s per
     * profile, 429 honored), re-reads permissions, swaps the scope, and asks
     * for a channel / group / VOD reload.
     */
    suspend fun switchTo(playlistId: String, profileId: String, bypassPin: Boolean = false): Result<Unit> = runCatching {
        if (!bypassPin && pinStore.hasPin(profileId)) {
            check(switchTickets.remove(profileId)) { "Enter PIN" }
        }
        val gen = switchGen.incrementAndGet()
        watchdog?.cancel()
        watchdog = null
        _switching.value = true
        try {
            // Off the main thread: prefs commits and the row write are disk I/O.
            kotlinx.coroutines.withContext(Dispatchers.IO) { switchToLocked(playlistId, profileId, gen) }
        } catch (t: Throwable) {
            if (gen == switchGen.get()) _switching.value = false
            throw t
        }
    }

    private suspend fun switchToLocked(playlistId: String, profileId: String, gen: Long) {
        val target = store.byId(profileId) ?: error("Profile not found")
        val from = store.activeProfileId(playlistId)?.let(store::byId)
            ?: store.forPlaylist(playlistId).firstOrNull { it.isDefault }
        val row = playlistDao.rawById(playlistId) ?: error("Playlist not found")
        val base = repository.effectiveBaseUrl(row)
        val t0 = System.currentTimeMillis()
        var tAuth = t0
        var tMe = t0
        var path = "key"
        var tLock = t0
        mutex.withLock {
            tLock = System.currentTimeMillis()
            if (target.isDefault) {
                val a = authenticate(playlistId, target.id, base, row.username.orEmpty(), row.password, row.apiKey)
                path = a.path
                tAuth = System.currentTimeMillis()
                // Phase 2b: /users/me/ is the only read that gates the scrim;
                // apply it to the playlist row (the default profile's home).
                val me = a.me ?: client.fetchMe(base, a.key)
                tMe = System.currentTimeMillis()
                ProfileScope.activate(null)
                store.setActiveProfileId(playlistId, null)
                if (me != null) applyMeToDefaultRow(playlistId, target.id, me)
            } else {
                val a = authenticate(playlistId, target.id, base, target.username, target.password, target.apiKey)
                path = a.path
                val key = a.key
                tAuth = System.currentTimeMillis()
                // The stored-key path already read /me/; only a fresh login
                // needs the extra read.
                val me = a.me ?: client.fetchMe(base, key) ?: error("Could not read ${target.username} from the server.")
                tMe = System.currentTimeMillis()
                val updated = target.applyMe(me, key)
                store.upsert(updated)
                ProfileScope.activate(updated.toOverlay())
                store.setActiveProfileId(playlistId, updated.id)
            }
            tokenStore.clear(playlistId)
            CapabilityCorrections.clear(playlistId)
            timeshift.stopAllRetained("profile switch")
        }
        refreshActiveRole(playlistId)
        Log.i(TAG, "[PROFILE] switch ${from?.displayName ?: "?"} -> ${target.displayName}")
        // Phase 2b: the rest of the capability probe (server settings, admin
        // check, version, account facts) runs AFTER the scrim drops; see
        // finishSwitch. The /me/ read above already set the permissions.
        pendingProbe = PendingProbe(playlistId, gen, System.currentTimeMillis())
        val tGate = System.currentTimeMillis()
        Log.i(
            TAG,
            "[SWITCH] profile lock=${tLock - t0}ms auth=${tAuth - tLock}ms me=${tMe - tAuth}ms " +
                "rest=${tGate - tMe}ms path=$path gate=${tGate - t0}ms (probe deferred)",
        )
        // Safety: never leave the scrim up if no PlaylistViewModel answers.
        watchdog = scope.launch {
            kotlinx.coroutines.delay(SWITCH_TIMEOUT_MS)
            if (_switching.value && gen == switchGen.get()) {
                Log.w(TAG, "[PROFILE] reload did not report back in ${SWITCH_TIMEOUT_MS / 1000}s; releasing the UI")
                finishSwitch(gen)
            }
        }
        logPermissions(playlistId, store.byId(target.id)?.username?.ifBlank { null } ?: row.username.orEmpty())
        reloads.send(Reload(playlistId, gen))
    }

    /** [username] is the Dispatcharr username, never the display name. */
    suspend fun logPermissions(playlistId: String, username: String) {
        val row = playlistDao.byId(playlistId) ?: return
        Log.i(
            TAG,
            "[PROFILE] permissions user=$username level=${ProfileLevel.of(row.dispatcharrUserLevel).label} " +
                "catchup=${row.dispatcharrCatchupEnabled} movies=${row.dispatcharrVodMoviesEnabled} " +
                "series=${row.dispatcharrVodSeriesEnabled} dvr=${row.dispatcharrDvrAccess.ifBlank { "default" }}",
        )
    }

    private data class Auth(val key: String, val me: MeResponse?, val path: String)

    /**
     * Sign in as one user; returns that user's API key. Fast switch: a stored
     * API key is tried first with ONE GET /me/ (which also answers who the
     * key belongs to); the password login (POST token + GET me) runs only
     * when that key is missing, rejected, or belongs to someone else.
     */
    private suspend fun authenticate(
        playlistId: String,
        profileId: String,
        base: String,
        username: String,
        password: String?,
        apiKey: String?,
    ): Auth {
        val stored = apiKey?.takeIf { it.isNotBlank() }
        if (stored != null) {
            val me = runCatching { client.fetchMe(base, stored) }.getOrNull()
            if (me != null && (username.isBlank() || me.username.equals(username, ignoreCase = true))) {
                return Auth(stored, me, "key")
            }
            if (password.isNullOrBlank()) {
                throw DispatcharrError.InvalidCredentials("The API key for $username was rejected.")
            }
        }
        return Auth(loginForKey(playlistId, profileId, base, username, password, apiKey), null, "login")
    }

    private suspend fun loginForKey(
        playlistId: String,
        profileId: String,
        base: String,
        username: String,
        password: String?,
        apiKey: String?,
    ): String {
        if (!password.isNullOrBlank() && username.isNotBlank()) {
            var rejected: DispatcharrError? = null
            val key = DispatcharrLoginGate.silentRelogin("$playlistId:$profileId") {
                try {
                    val pair = client.login(base, username, password)
                    client.fetchCurrentUserApiKey(base, pair.access)
                } catch (e: DispatcharrError.InvalidCredentials) {
                    throw e
                } catch (e: DispatcharrError) {
                    rejected = e
                    null
                }
            }
            rejected?.let { throw it }
            return key ?: throw DispatcharrError.InvalidCredentials(
                "Sign-in for $username was rejected. Try again in a minute.",
            )
        }
        val k = apiKey?.takeIf { it.isNotBlank() } ?: error("Enter a password or an API key.")
        client.fetchMe(base, k) ?: throw DispatcharrError.InvalidCredentials("The API key for $username was rejected.")
        return k
    }

    private fun DispatcharrProfile.applyMe(me: MeResponse, key: String): DispatcharrProfile {
        val props: JsonObject? = me.customProperties
        fun flag(name: String): Boolean = (props?.get(name) as? JsonPrimitive)?.booleanOrNull != false
        val level = if (me.isSuperuser || me.isStaff) 10 else (me.userLevel ?: 0)
        return copy(
            apiKey = key,
            userId = me.id,
            username = me.username,
            userLevel = level,
            streamLimit = me.streamLimit,
            hideAdultContent = (props?.get("hide_adult_content") as? JsonPrimitive)?.booleanOrNull == true,
            dvrAccess = (props?.get("dvr_access") as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty(),
            catchupEnabled = flag("catchup_enabled"),
            vodMoviesEnabled = flag("vod_movies_enabled"),
            vodSeriesEnabled = flag("vod_series_enabled"),
            accountProfileIds = me.channelProfiles.joinToString(","),
            isStaff = me.isStaff,
            isSuperuser = me.isSuperuser,
            customProperties = props?.toString().orEmpty(),
            capabilitiesFetchedAt = System.currentTimeMillis(),
            capabilitiesSchema = CAPABILITIES_SCHEMA,
            capabilitiesStale = false,
        )
    }

    /** The default profile's /me/ facts onto the playlist row, like the probe writes them. */
    private suspend fun applyMeToDefaultRow(playlistId: String, profileId: String, me: MeResponse) {
        val props: JsonObject? = me.customProperties
        fun flag(name: String): Boolean = (props?.get(name) as? JsonPrimitive)?.booleanOrNull != false
        kotlinx.coroutines.withContext(ProfileScope.defaultOnly()) {
            val raw = playlistDao.rawById(playlistId) ?: return@withContext
            val next = raw.copy(
                    // A legacy superuser can read as level 0 on /me/; the deferred
                    // probe's admin check settles it, so keep a known Admin until then.
                    dispatcharrUserLevel = when {
                        me.isStaff || me.isSuperuser -> 10
                        (me.userLevel ?: 0) < 10 && raw.dispatcharrUserLevel >= 10 -> raw.dispatcharrUserLevel
                        else -> me.userLevel ?: raw.dispatcharrUserLevel
                    },
                    dispatcharrIsStaff = me.isStaff,
                    dispatcharrIsSuperuser = me.isSuperuser,
                    dispatcharrCustomProperties = props?.toString().orEmpty(),
                    dispatcharrCapabilitiesFetchedAt = System.currentTimeMillis(),
                    dispatcharrCapabilitiesSchema = CAPABILITIES_SCHEMA,
                    dispatcharrCapabilitiesStale = false,
                    dispatcharrDvrAccess = (props?.get("dvr_access") as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty(),
                    dispatcharrCatchupEnabled = flag("catchup_enabled"),
                    dispatcharrVodMoviesEnabled = flag("vod_movies_enabled"),
                    dispatcharrVodSeriesEnabled = flag("vod_series_enabled"),
                    dispatcharrAccountProfileIds = me.channelProfiles.joinToString(","),
                )
            // Skip the write when /me/ changed nothing: a Room write here can
            // queue behind a channel snapshot transaction and hold the scrim.
            if (next.copy(dispatcharrCapabilitiesFetchedAt = raw.dispatcharrCapabilitiesFetchedAt) == raw) {
                Log.i(TAG, "[SWITCH] default row unchanged by /me/; no write")
            } else {
                val tw = System.currentTimeMillis()
                playlistDao.update(next)
                Log.i(TAG, "[SWITCH] default row write=${System.currentTimeMillis() - tw}ms")
            }
        }
        store.update(profileId) { it.copy(userId = me.id) }
    }

    private suspend fun PlaylistDao.rawById(id: String): PlaylistEntity? =
        (this as? EncryptingPlaylistDao)?.byIdDefaultProfile(id) ?: byId(id)

    private suspend fun PlaylistDao.rawAll(): List<PlaylistEntity> =
        kotlinx.coroutines.withContext(ProfileScope.defaultOnly()) { allOnce() }

    private companion object {
        const val TAG = "AerioProfiles"
        const val SWITCH_TIMEOUT_MS = 45_000L
    }
}
