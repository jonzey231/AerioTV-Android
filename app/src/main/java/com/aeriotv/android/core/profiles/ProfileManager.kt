package com.aeriotv.android.core.profiles

import kotlinx.coroutines.flow.update
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

    /** Server address changes (A-APP-1): held profile sign-ins, persisted before anything is sent. */
    private val hostHolds = HostHoldBook(
        object : HostHoldBook.Backing {
            private val prefs = context.getSharedPreferences("aerio_profile_host", android.content.Context.MODE_PRIVATE)
            override fun read(): String? = prefs.getString("state.v1", null)
            override fun write(value: String) { prefs.edit().putString("state.v1", value).commit() }
        },
    ).also { StoredSecrets.book = it }

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
        // Every switch lands on the Live TV root, so focus goes to the grid.
        ProfileSwitchFocus.arm(ProfileSwitchFocus.Target.Guide)
        ProfileSwitchFocus.onScrimReleased()
        pendingVerify?.takeIf { it.gen == gen }?.let { pv ->
            pendingVerify = null
            Log.i(TAG, "[SWITCH] warm verify started after the scrim dropped")
            scope.launch { pv.run() }
        }
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

    private class PendingVerify(val gen: Long, val run: suspend () -> Unit)
    @Volatile private var pendingVerify: PendingVerify? = null

    /** Header switcher "Manage Profiles": open Settings > this playlist. */
    private val manage = Channel<Unit>(Channel.CONFLATED)
    val manageRequests: Flow<Unit> = manage.receiveAsFlow()
    fun requestManageProfiles() { manage.trySend(Unit) }

    /**
     * Synchronous restore, called first thing in Application.onCreate so no
     * scoped read ever sees the default profile's state by mistake.
     */
    fun restoreSync() {
        // First: a sign-in held after a server address change never goes out
        // after a relaunch, whatever was active when the app stopped, and
        // those playlists' roster sync stays paused.
        ProfileScope.held = hostHolds.heldProfileIds()
        _rosterPaused.value = hostHolds.pausedPlaylists()
        _rosterPaused.value.forEach { Log.i(TAG, "[PROFILE-HOST] roster sync paused playlist=${it.take(8)}") }
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
        var active = store.activeProfileId(pid)?.let(store::byId)
        // Held, or with no stored sign-in (treated the same, 2026-11-11).
        if (active != null && !active.isDefault && unusable(active.id)) {
            if (ProfileHostHold.staysActiveWhenHeld(active.effectiveRole())) {
                // A held Child stays active (no reset to the default, so no
                // launch picker either), sending nothing until the hold lifts.
                val held = hostHolds.isHeld(active.id)
                val row = if (held) null else rawRowBlocking(pid)
                seal(active.id, hostHolds.hold(pid)?.current?.takeIf { it.isNotEmpty() }
                    ?: row?.let { ProfileHostHold.routes(it.urlString, it.lanUrlString) }.orEmpty())
                if (!held) openNoSignInForm(pid, active.id, row?.name.orEmpty())
                Log.i(TAG, "[PROFILE-HOST] restored the ${if (held) "held" else "signed-out"} Child on ${pid.take(8)}; it stays until it signs in, the hold lifts, or a PIN switch")
            } else {
                store.setActiveProfileId(pid, null)
                Log.i(TAG, "[PROFILE-HOST] restored the default profile on ${pid.take(8)}: the active profile's sign-in is held or missing")
                active = null
            }
        }
        refreshActiveRole(pid)
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
            com.aeriotv.android.core.app.LaunchWorkHold.set(true)
            Log.i(TAG, "[PROFILE] launch picker armed (${all.size} profiles)")
            return
        }
        if (restored != null && pinStore.hasPin(restored.id)) {
            _launchGate.value = restored
            com.aeriotv.android.core.app.LaunchWorkHold.set(true)
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

    /** Releases [com.aeriotv.android.core.app.LaunchWorkHold] once neither
     *  the launch gate nor the launch picker is up. */
    private fun releaseLaunchHoldIfClear() {
        if (_launchGate.value == null && _launchPicker.value == null) {
            com.aeriotv.android.core.app.LaunchWorkHold.set(false)
        }
    }
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
        if (pinStore.hasPin(profileId) && !switchTickets.contains(profileId)) {
            return Result.failure(IllegalStateException(PinCopy.switchNeedsPin(store.byId(profileId)?.displayName.orEmpty())))
        }
        if (profileId == activeProfileIdNow(pk.playlistId) && !unusable(profileId)) {
            switchTickets.remove(profileId)
            _launchPicker.value = null
            releaseLaunchHoldIfClear()
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
        if (r.isSuccess) { _launchPicker.value = null; releaseLaunchHoldIfClear() }
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

    /** Admin and Standard profiles on [playlistId] that have a PIN (equivalent in AerioTV). */
    fun adminPinHolders(playlistId: String): List<DispatcharrProfile> =
        store.forPlaylist(playlistId).filter { RoleRules.isFullAccess(it.effectiveRole()) && pinStore.hasPin(it.id) }

    fun adminHasPin(playlistId: String): Boolean = adminPinHolders(playlistId).isNotEmpty() || hasDeviceAdminPin(playlistId)

    /**
     * Assumption A: Admin and Standard profiles that still need a PIN while
     * a Child exists (or once [becomingChild] turns Child), own first.
     */
    fun pinsMissingForChild(playlistId: String, becomingChild: String? = null): List<DispatcharrProfile> {
        val actor = activeProfileIdNow(playlistId)
        val all = store.forPlaylist(playlistId)
        val ids = RoleRules.pinsMissingForChild(
            all.map { RoleRules.PinHolder(it.id, it.effectiveRole(), pinStore.hasPin(it.id), it.id == actor) },
            becomingChild,
        )
        return ids.mapNotNull { id -> all.firstOrNull { it.id == id } }
    }

    /** Profiles added while a Child exists that still owe their PIN (assumption A, add flow). */
    private val pendingPinAdds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun isPendingPinAdd(profileId: String): Boolean = profileId in pendingPinAdds

    /** True when [profileId] was just added while a Child exists and has no PIN yet. */
    fun needsPinAfterAdd(playlistId: String, profileId: String): Boolean =
        anyChild(playlistId) && RoleRules.isFullAccess(roleOf(profileId)) && !pinStore.hasPin(profileId)

    /**
     * The PIN step after an add was cancelled: the adds [addedInAction] made
     * are undone (a Child exists, so a PIN is required). Profiles other
     * actions added, a setup handoff's included, stay pending as they were.
     */
    suspend fun discardPendingAdds(playlistId: String, addedInAction: Collection<String>) {
        val ids = RoleRules.pinStepCancelDiscards(addedInAction, pendingPinAdds, pinStore::hasPin).toSet()
        val drop = store.forPlaylist(playlistId).filter { it.id in ids && !it.isDefault }
        drop.forEach { p ->
            pendingPinAdds.remove(p.id)
            // A roster add goes on this device only, so it is offered again.
            val rosterAdd = undoesRosterAdd(p.id)
            deleteLocal(p, fromSync = false, tombstone = ProfileRoster.undoWritesTombstone(rosterAdd))
            freshUndoable.remove(p.id)
            rosterUndoable.update { it - p.id }
            Log.i(TAG, "[PROFILE] add of ${p.username} undone: no PIN while a Child exists${if (rosterAdd) " (roster add: this device only)" else ""}")
        }
    }

    /** Profiles added from the synced roster in this process (a roster add, a manual add that finished as one, or a pause add that took its row). */
    private val rosterAdds = RosterAdds()

    /**
     * Undoing [profileId]'s add leaves no tombstone: it came from the roster,
     * or it was added during a roster pause and could not tell (a tombstone
     * then could delete that user's row everywhere once the pause ends).
     */
    private fun undoesRosterAdd(profileId: String): Boolean = rosterAdds.undoIsLocalOnly(profileId, hostHolds.isPausedAdd(profileId))

    // ---- Device Admin PIN (device-local, never synced) ----
    // A PIN tied to no profile: set in onboarding when the first profile is a
    // Child (a kid-only device), and kept valid alongside Admin profiles'
    // own PINs. Stored in the PIN store under a reserved id that is never a
    // profile id, so the `profiles` key (built from profiles) never carries it.

    fun deviceAdminPinId(playlistId: String): String = DevicePin.id(playlistId)
    fun hasDeviceAdminPin(playlistId: String): Boolean = pinStore.hasPin(deviceAdminPinId(playlistId))
    fun deviceAdminPinType(playlistId: String): PinType? = pinStore.type(deviceAdminPinId(playlistId))

    /** Every Admin PIN id that passes "Enter an Admin PIN": Admin profiles, then the device PIN. */
    fun adminPinIds(playlistId: String): List<String> =
        adminPinHolders(playlistId).map { it.id } + listOfNotNull(deviceAdminPinId(playlistId).takeIf { pinStore.hasPin(it) })

    /**
     * Set or change the device Admin PIN. Changing needs the current device
     * PIN ([current]); setting the first one needs an Admin role, an Admin
     * ticket, or onboarding ([onboarding]).
     */
    fun setDeviceAdminPin(playlistId: String, type: PinType, entries: List<String>, current: List<String>?, onboarding: Boolean = false): Result<Unit> = runCatching {
        val id = deviceAdminPinId(playlistId)
        if (pinStore.hasPin(id)) {
            check(verifyPin(id, current.orEmpty()) == PinCheck.Ok) { "Wrong PIN" }
        } else {
            check(onboarding || RoleRules.isFullAccess(activeRole(playlistId))) { "Only an Admin or Standard profile can set the Device Admin PIN." }
        }
        check(pinStore.set(id, type, entries)) { PinCopy.PIN_LENGTH }
        Log.i(TAG, "[PROFILE] device admin pin set on ${playlistId.take(8)}")
    }

    /** Null = removable; otherwise why the device PIN must stay. */
    fun deviceAdminPinRemovalBlock(playlistId: String): String? =
        if (RoleRules.canRemoveDevicePin(anyChild(playlistId), adminPinHolders(playlistId).size)) null
        else PinCopy.DEVICE_ADMIN_PIN_STAYS

    fun removeDeviceAdminPin(playlistId: String, current: List<String>): Result<Unit> = runCatching {
        val id = deviceAdminPinId(playlistId)
        deviceAdminPinRemovalBlock(playlistId)?.let { error(it) }
        check(verifyPin(id, current) == PinCheck.Ok) { "Wrong PIN" }
        pinStore.remove(id)
        Log.i(TAG, "[PROFILE] device admin pin removed on ${playlistId.take(8)}")
    }

    /**
     * Onboarding "Who is this profile for?" = Child: the base profile becomes
     * Child once a device Admin PIN exists (set just before). Bypasses the
     * "one profile must stay Admin" rule on purpose: the device PIN is the
     * Admin on a kid-only device.
     */
    fun makeBaseChild(playlistId: String): Result<Unit> = runCatching {
        check(hasDeviceAdminPin(playlistId)) { "Set a Device Admin PIN first." }
        val base = store.forPlaylist(playlistId).firstOrNull { it.isDefault } ?: error("Profile not found")
        check(!ProfileRole.isServerAdmin(base.userLevel)) { RoleRules.SERVER_ADMIN_STAYS }
        store.update(base.id) { it.copy(role = ProfileRole.Child) }
        refreshActiveRole(playlistId)
        Log.i(TAG, "[PROFILE] onboarding: ${base.username} is a Child; device admin pin holds Admin")
    }

    /** The acting (active) profile on [playlistId]. */
    fun activeProfile(playlistId: String): DispatcharrProfile? = activeProfileIdNow(playlistId)?.let(store::byId)

    /** One-shot Admin authorizations per playlist (an Admin PIN was entered). */
    private val adminTickets = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * "Enter an Admin or Standard profile PIN, or the Device Admin PIN": any full-access
     * profile's PIN (or the Device Admin PIN) on [playlistId] passes. Each
     * keeps its own wrong-PIN backoff. Ok grants one ticket and a switch
     * ticket for the matching profile.
     */
    @Synchronized
    fun verifyAnyAdminPin(playlistId: String, entries: List<String>): PinCheck {
        val holders = adminPinIds(playlistId)
        if (holders.isEmpty()) return PinCheck.Wrong
        val device = deviceAdminPinId(playlistId)
        val now = System.currentTimeMillis()
        var minLock = Int.MAX_VALUE
        var tried = false
        for (h in holders) {
            val b = pinStore.backoff(h)
            val left = b.remainingSeconds(now)
            if (left > 0) { minLock = minOf(minLock, left); continue }
            tried = true
            if (pinStore.check(h, entries)) {
                pinStore.saveBackoff(h, b.onOk())
                adminTickets.add(playlistId)
                if (h != device) {
                    switchTickets.add(h)
                    lastAdminUnlock = h
                }
                Log.i(TAG, "[PROFILE] pin ok for ${if (h == device) "device admin" else store.byId(h)?.username}")
                return PinCheck.Ok
            }
        }
        // Count the miss against every Admin that was tried.
        var lock = Int.MAX_VALUE
        holders.forEach { h ->
            val b = pinStore.backoff(h)
            if (b.remainingSeconds(now) > 0) return@forEach
            val next = b.onWrong(now)
            pinStore.saveBackoff(h, next)
            next.remainingSeconds(now).takeIf { it > 0 }?.let { lock = minOf(lock, it) }
            Log.i(TAG, "[PROFILE] pin wrong for ${if (h == device) "device admin" else store.byId(h)?.username}")
        }
        if (!tried) return PinCheck.Locked(minLock)
        // Locked only when no Admin is left to try.
        val anyOpen = holders.any { pinStore.backoff(it).remainingSeconds(now) == 0 }
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
        val full = store.forPlaylist(playlistId).count { RoleRules.isFullAccess(it.effectiveRole()) }
        return RoleRules.roleChange(
            actor = activeRole(playlistId),
            from = target.effectiveRole(),
            to = to,
            missingPins = pinsMissingForChild(playlistId, becomingChild = profileId).size,
            anyPinExists = adminPinIds(playlistId).any { it != profileId },
            targetIsLastFullAccess = RoleRules.isFullAccess(target.effectiveRole()) && full <= 1,
            targetIsServerAdmin = ProfileRole.isServerAdmin(target.userLevel),
        )
    }

    /** Set [profileId]'s role. Leaving Child needs a PIN ticket ([verifyAnyAdminPin]). */
    fun setRole(playlistId: String, profileId: String, to: ProfileRole): Result<Unit> = runCatching {
        val target = store.byId(profileId) ?: error("Profile not found")
        when (val g = roleChangeGate(playlistId, profileId, to)) {
            is RoleRules.RoleChange.Denied -> error(g.reason)
            RoleRules.RoleChange.NeedsPinsCreated -> error(RoleRules.KEEP_PIN_REASON)
            RoleRules.RoleChange.NeedsPinEntry -> check(consumeAdminTicket(playlistId)) { PinCopy.ADMIN_PAD }
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
            releaseLaunchHoldIfClear()
            ProfileSwitchFocus.arm(ProfileSwitchFocus.Target.Guide)
            ProfileSwitchFocus.onScrimReleased()
        }
        return r
    }

    /**
     * An Admin or Standard profile may manage any profile's PIN; a Child only
     * its own. A profile just added while a Child exists may set its first
     * PIN from the add flow whoever is active (kid-only device picker add).
     */
    suspend fun canManagePin(playlistId: String, profileId: String): Boolean =
        RoleRules.isFullAccess(activeRole(playlistId)) || activeProfileIdNow(playlistId) == profileId ||
            (profileId in pendingPinAdds && !pinStore.hasPin(profileId))

    /** Changing or removing ANY PIN needs that profile's existing PIN (phase 2b). */
    @Suppress("UNUSED_PARAMETER")
    suspend fun needsCurrentPin(playlistId: String, profileId: String): Boolean = pinStore.hasPin(profileId)

    /** Null = removable; otherwise why the PIN must stay (assumption A: a Child exists). */
    fun pinRemovalBlock(playlistId: String, profileId: String): String? {
        val role = roleOf(profileId) ?: return null
        return if (RoleRules.canRemovePin(role, anyChild(playlistId))) null else RoleRules.KEEP_PIN_REASON
    }

    /** What a reset of another profile's PIN needs. */
    fun resetGate(playlistId: String): RoleRules.ResetGate {
        val actor = activeProfile(playlistId)
        return RoleRules.resetGate(activeRole(playlistId), actor?.let { pinStore.hasPin(it.id) } == true, anyChild(playlistId))
    }

    /**
     * Reset of [profileId]'s forgotten PIN by an Admin or Standard profile.
     * [adminEntries] is the actor's own PIN (null for the confirm-only case).
     * While a Child exists an Admin or Standard target must keep a PIN, so
     * [replacement] (a temporary PIN the actor sets) is required then.
     */
    suspend fun resetPin(
        playlistId: String,
        profileId: String,
        adminEntries: List<String>?,
        replacement: Pair<PinType, List<String>>? = null,
    ): Result<Unit> = runCatching {
        val actor = activeProfile(playlistId) ?: error("Profile not found")
        check(actor.id != profileId) { "Use Remove PIN for this profile." }
        val mustKeep = pinRemovalBlock(playlistId, profileId)
        if (mustKeep != null && replacement == null) error(mustKeep)
        when (val g = resetGate(playlistId)) {
            is RoleRules.ResetGate.Denied -> error(g.reason)
            RoleRules.ResetGate.EnterOwnPin -> {
                check(verifyPin(actor.id, adminEntries.orEmpty()) == PinCheck.Ok) { "Wrong PIN" }
                switchTickets.remove(actor.id)
            }
            RoleRules.ResetGate.ConfirmOnly -> Unit
        }
        if (replacement != null) {
            check(pinStore.set(profileId, replacement.first, replacement.second)) { PinCopy.PIN_LENGTH }
            mutated(profileId, "pin reset")
            Log.i(TAG, "[PROFILE] pin reset to a temporary pin for ${store.byId(profileId)?.username}")
        } else {
            pinStore.remove(profileId)
            mutated(profileId, "pin reset")
            Log.i(TAG, "[PROFILE] pin removed for ${store.byId(profileId)?.username}")
        }
    }

    suspend fun setPin(
        playlistId: String,
        profileId: String,
        type: PinType,
        entries: List<String>,
        current: List<String>?,
    ): Result<Unit> = runCatching {
        check(canManagePin(playlistId, profileId)) { "Only an Admin or Standard profile can change another profile's PIN." }
        val had = pinStore.hasPin(profileId)
        if (needsCurrentPin(playlistId, profileId)) {
            check(verifyPin(profileId, current.orEmpty()) == PinCheck.Ok) { "Wrong PIN" }
            switchTickets.remove(profileId)
        }
        check(pinStore.set(profileId, type, entries)) { PinCopy.PIN_LENGTH }
        pendingPinAdds.remove(profileId)
        mutated(profileId, "pin set")
        Log.i(TAG, "[PROFILE] pin ${if (had) "changed" else "set"} for ${store.byId(profileId)?.username}")
    }

    suspend fun removePin(playlistId: String, profileId: String, current: List<String>?): Result<Unit> = runCatching {
        check(canManagePin(playlistId, profileId)) { "Only an Admin or Standard profile can remove another profile's PIN." }
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
        check(activeIsAdmin(playlistId)) { "Only a Dispatcharr admin can assign channel profiles." }
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
        check(activeIsAdmin(playlistId)) { "Only a Dispatcharr admin can assign channel profiles." }
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
            runCatching {
                playlistDao.rawAll().forEach { row ->
                    ensureDefaultProfile(row)
                    // The admin probe updates the row's level; the base profile follows it.
                    store.forPlaylist(row.id).firstOrNull { it.isDefault }?.let { base ->
                        followServerRole(base.id, if (row.dispatcharrIsSuperuser || row.dispatcharrIsStaff) 10 else row.dispatcharrUserLevel)
                    }
                }
            }
                .onFailure { Log.w(TAG, "[PROFILE] migration pass failed", it) }
            // Held playlists (a held Child may be active): the same-server check
            // runs in the background with the default profile's sign-in.
            // So does a playlist whose own route is still unproven (StoredSecrets).
            (hostHolds.pausedPlaylists() + hostHolds.unprovenPlaylists()).forEach { scheduleSameServerCheck(it) }
            // Per-profile settings (2026-10-27): seed existing profiles once from the global values.
            runCatching { appPreferences.seedExistingProfilesOnce(store.profiles.value.filterNot { it.isDefault }.map { it.id }) }
                .onFailure { Log.w(TAG, "[PROFILE] settings seed pass failed", it) }
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
        var active = store.activeProfileId(pid)?.let(store::byId)
        if (active != null && !active.isDefault && unusable(active.id)) {
            if (ProfileHostHold.staysActiveWhenHeld(active.effectiveRole())) {
                // A held Child (or one with no stored sign-in) stays, sending nothing to its playlist's addresses.
                playlistDao.rawById(pid)?.let {
                    seal(active!!.id, ProfileHostHold.routes(it.urlString, it.lanUrlString))
                    openNoSignInForm(pid, active!!.id, it.name)
                }
            } else {
                // Its sign-in is held after a server address change: the default profile it is.
                store.setActiveProfileId(pid, null)
                active = null
            }
        }
        val current = ProfileScope.activeOverlay()
        if (active != null && !active.isDefault) {
            if (current?.profileId != active.id) ProfileScope.activate(active.toOverlay())
        } else if (current != null) {
            ProfileScope.activate(null)
        }
        refreshActiveRole(pid)
    }

    /** Every Dispatcharr playlist has exactly one default profile. Its state
     *  is the legacy (unscoped) state, so creating it moves no data. */
    /**
     * A device setup creating [playlistId] with the sender's id also keeps the
     * sender's default profile id ([profileId]); [ensureDefaultProfile] uses it.
     */
    fun seedDefaultProfileId(playlistId: String, profileId: String?) {
        val id = profileId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (store.byId(id) == null) seededDefaultIds[playlistId] = id
    }

    private val seededDefaultIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun ensureDefaultProfile(row: PlaylistEntity) {
        if (!row.isDispatcharrDirectConnect()) return
        if (store.forPlaylist(row.id).any { it.isDefault }) return
        var username = row.username.orEmpty()
        if (username.isBlank()) {
            // API-key login: ask the server who the key belongs to.
            val key = row.apiKey
            if (!key.isNullOrBlank()) {
                val base = repository.effectiveBaseUrl(row)
                val me = runCatching { client.fetchMe(base, key) }.getOrNull()
                me?.let { runCatching { noteDefaultMe(row.id, base, key, it.id, it.username) } }
                username = me?.username.orEmpty()
            }
        }
        val seeded = seededDefaultIds.remove(row.id)?.takeIf { store.byId(it) == null }
        store.upsert(
            DispatcharrProfile(
                id = seeded ?: UUID.randomUUID().toString(),
                playlistId = row.id,
                displayName = username.ifBlank { row.name },
                username = username,
                isDefault = true,
                userLevel = row.dispatcharrUserLevel,
                // The role follows the Dispatcharr access (owner 2026-10-29).
                role = ProfileRole.defaultFor(row.dispatcharrUserLevel),
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
        check(canAdd(playlistId)) { "Only an Admin or Standard profile can add profiles." }
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
    suspend fun addProfileManual(playlistId: String, username: String, password: String?, apiKey: String?, role: ProfileRole? = null): Result<DispatcharrProfile> {
        val name = username.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("Enter a username."))
        if (store.forPlaylist(playlistId).any { it.username.equals(name, ignoreCase = true) }) {
            return Result.failure(IllegalStateException("$name already has a profile."))
        }
        rosterRowForManualAdd(playlistId, name)?.let { row ->
            return addFromRoster(playlistId, row, DispatcharrUserSummary(id = 0, username = name), password, apiKey, fallbackRole = role)
        }
        return addProfile(playlistId, DispatcharrUserSummary(id = 0, username = name), password, apiKey, role)
    }

    /**
     * A manual add of a user the synced roster already has (offered under
     * "From your other devices") finishes as a roster add: the row's role,
     * PIN, avatar, name, order, and stamp, with the credentials typed here. A
     * plain add would stamp the row now with no PIN and the picked role, and
     * the next push would drop that profile's PIN and Child role on every
     * device. Null = a plain add.
     */
    private fun rosterRowForManualAdd(playlistId: String, username: String): com.aeriotv.android.core.sync.dsync.ProfileRow? =
        ProfileRoster.rowForManualAdd(
            RosterPause.visibleRows(_roster.value[playlistId].orEmpty(), hostHolds.isPaused(playlistId)),
            store.forPlaylist(playlistId).map { it.username },
            username,
        )
            ?.also { Log.i(TAG, "[DSYNC] profiles: manual add of ${it.username} is in the synced roster; finishing as a roster add") }

    /**
     * Manual add by API key only: GET /api/accounts/users/me/ with [apiKey]
     * names the user (username, level, id). A key for a user that already
     * has a profile here is rejected.
     */
    suspend fun addProfileByApiKey(playlistId: String, apiKey: String, role: ProfileRole? = null): Result<DispatcharrProfile> {
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
        rosterRowForManualAdd(playlistId, me.username)?.let { row ->
            return addFromRoster(
                playlistId,
                row,
                DispatcharrUserSummary(id = me.id, username = me.username, userLevel = me.userLevel, isSuperuser = me.isSuperuser, isStaff = me.isStaff),
                null,
                key,
                fallbackRole = role,
            )
        }
        return addProfile(
            playlistId,
            DispatcharrUserSummary(id = me.id, username = me.username, userLevel = me.userLevel, isSuperuser = me.isSuperuser, isStaff = me.isStaff),
            null,
            key,
            role,
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
        check(canAdd(playlistId)) { "Only an Admin or Standard profile can add profiles." }
        check(activeIsAdmin(playlistId)) { "Only a Dispatcharr admin can list server users." }
        val base = repository.effectiveBaseUrl(row)
        val key = row.apiKey?.takeIf { it.isNotBlank() } ?: error("No API key for this playlist")
        val taken = store.forPlaylist(playlistId).map { it.username }.toSet()
        client.listUsers(base, key).filter { it.username !in taken }.sortedBy { it.username.lowercase() }
    }

    /** Enable API access is offered: the base login is a server admin and the active profile is an Admin. */
    suspend fun canManageUserApiKeys(playlistId: String): Boolean =
        baseIsServerAdmin(playlistId) && activeIsAdmin(playlistId)

    /** Mint a key for server user [userId] with the base admin key. Returns the raw key (never logged). */
    suspend fun generateUserApiKey(playlistId: String, userId: Int): Result<String> = runCatching {
        val row = playlistDao.byId(playlistId) ?: error("Playlist not found")
        check(canManageUserApiKeys(playlistId)) { "Only a Dispatcharr admin can enable API access." }
        val key = row.apiKey?.takeIf { it.isNotBlank() } ?: error("No API key for this playlist")
        client.generateUserApiKey(repository.effectiveBaseUrl(row), key, userId)
    }

    /** Clear server user [userId]'s key with the base admin key. */
    suspend fun revokeUserApiKey(playlistId: String, userId: Int): Result<Unit> = runCatching {
        val row = playlistDao.byId(playlistId) ?: error("Playlist not found")
        check(canManageUserApiKeys(playlistId)) { "Only a Dispatcharr admin can revoke API access." }
        val key = row.apiKey?.takeIf { it.isNotBlank() } ?: error("No API key for this playlist")
        client.revokeUserApiKey(repository.effectiveBaseUrl(row), key, userId)
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
        role: ProfileRole? = null,
        seed: com.aeriotv.android.core.sync.dsync.ProfileRow? = null,
        /** A device setup keeps the sender's profile id (unless one here already has it). */
        wantedId: String? = null,
    ): Result<DispatcharrProfile> = runCatching {
        // Same rule as setRole (assumption A): before a Child is added, every
        // Admin and Standard profile holds a PIN.
        if (role == ProfileRole.Child) check(adminHasPin(playlistId) && pinsMissingForChild(playlistId).isEmpty()) { RoleRules.KEEP_PIN_REASON }
        // Local Admin role, not the server level: a profile is added by
        // signing in as that user, which never changes the server.
        check(canAdd(playlistId)) { "Only an Admin or Standard profile can add profiles." }
        val row = playlistDao.rawById(playlistId) ?: error("Playlist not found")
        val base = repository.effectiveBaseUrl(row)
        val id = wantedId?.trim()?.takeIf { it.isNotEmpty() && store.byId(it) == null } ?: UUID.randomUUID().toString()
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
        ).applyMe(me, key).let { it.copy(role = ProfileRole.resolve(it.userLevel, role)) }
            // A roster add: name, role, avatar, order, and user id land in the
            // FIRST store write, so the row never exists (or syncs) half built.
            .let { if (seed != null) ProfileRoster.applyRow(it, seed) else it }
        store.upsert(profile)
        if (seed != null) rosterAdds.addedFromRoster(id)
        // Signed in just now at the playlist's current address: no older hold
        // applies to this id, and its tag is the server it signed in at.
        dropHold(id, "added")
        runCatching { tagLive(playlistId, id, base) }
        // Added while the roster sync is paused: it could not see the roster.
        // The first pull after the pause applies its row, if there is one.
        if (seed == null && hostHolds.isPaused(playlistId)) {
            hostHolds.markPausedAdd(id)
            Log.i(TAG, "[PROFILE-HOST] ${user.username} added during the roster pause on ${playlistId.take(8)}")
        }
        if (needsPinAfterAdd(playlistId, id)) pendingPinAdds.add(id)
        appPreferences.seedProfileSettings(id)
        if (seed != null) {
            PinRules.typeFromWire(seed.pinType)?.let { type ->
                if (seed.pinHash.length == 64 && seed.pinSalt.length == 32) pinStore.importSynced(id, type, seed.pinHash, seed.pinSalt)
            }
            // Keep the row's own stamp: the server row stays authoritative
            // for every field this device did not change.
            mutated(id, "roster-add", ProfileRoster.addStampAt(seed))
        } else {
            mutated(id, "add")
        }
        Log.i(TAG, "[PROFILE] added ${profile.username} on ${playlistId.take(8)} level=${ProfileLevel.of(profile.userLevel).label} role=${profile.effectiveRole().label}")
        profile
    }

    // ---- Dispatcharr Sync `profiles` key (admin only, schema 4.12 and 5) ----

    /** Local profile id of [username] on [playlistId], or null. */
    /**
     * Dispatcharr Sync hook: every profile list mutation (add, remove, rename,
     * role, PIN) reports here so the `profiles` key is stamped and re-sent.
     * Set by DispatcharrSyncManager.
     */
    @Volatile var onProfilesMutated: ((playlistId: String, username: String, what: String, stampAt: Long) -> Unit)? = null

    /**
     * Dispatcharr Sync hook: a profile picker surface opened ("Who is
     * watching?" at launch or on return to the foreground, Switch Profile,
     * Add Profile). Sync pulls the `profiles` key right away (bounded) so a
     * removal or add made on another device shows without waiting for the
     * 15 minute periodic pull. Set by DispatcharrSyncManager.
     */
    @Volatile var onPickerOpened: ((playlistId: String, reason: String) -> Unit)? = null

    /** [reason] is launch, switch, addProfile, or foreground. Never blocks. */
    fun notePickerOpened(playlistId: String, reason: String) {
        runCatching { onPickerOpened?.invoke(playlistId, reason) }
    }

    /** [stampAt] > 0 stamps the row with that time (a roster add); 0 = now. */
    private fun mutated(profileId: String, what: String, stampAt: Long = 0L) {
        val p = store.byId(profileId) ?: return
        runCatching { onProfilesMutated?.invoke(p.playlistId, p.username, what, stampAt) }
    }

    fun profileIdFor(playlistId: String, username: String): String? =
        store.forPlaylist(playlistId).firstOrNull { it.username.equals(username, ignoreCase = true) }?.id

    /**
     * Merge a pulled `profiles` list on a device signed in as the admin, PER
     * ROW (schema 4.12). The roster is a catalog: a row with no local profile
     * is never added here (it is offered via [rosterSuggestions] and added
     * only by an explicit choice, [addFromRoster]); an existing profile
     * takes the row's display name, role, and PIN only when the row's
     * updatedAt is newer than [localUpdatedAt] for that username. Local
     * profiles are not removed here (the caller applies synced tombstones
     * first, schema 4.12), and a row this device removed at or after its
     * updatedAt ([removedAt]) is skipped, never re-created. [adopted]
     * records the row stamp taken.
     * Returns how many profiles were added (always 0: opt-in only).
     */
    suspend fun mergeSyncedProfiles(
        playlistId: String,
        rows: List<com.aeriotv.android.core.sync.dsync.ProfileRow>,
        users: List<DispatcharrUserSummary>,
        localUpdatedAt: (String) -> Long = { 0L },
        adopted: (String, Long) -> Unit = { _, _ -> },
        removedAt: (String) -> Long = { 0L },
        customized: (String, String, AvatarSpec?) -> Boolean = { _, _, _ -> true },
    ): Int {
        // Paused (a profile here is held after a server change): the server's
        // rows, tombstones, and suggestions stay out. Sync never calls this
        // while paused; this is the backstop.
        if (hostHolds.isPaused(playlistId)) {
            Log.i(TAG, "[PROFILE-HOST] roster rows not applied: roster sync paused playlist=${playlistId.take(8)}")
            return 0
        }
        publishRoster(playlistId, rows)
        val plan = ProfileRoster.plan(rows, store.forPlaylist(playlistId).map { it.username })
        Log.i(TAG, "[DSYNC] profiles: ${plan.available.size} roster rows available, not added (opt-in)")
        if (!RoleRules.canWriteProfilesKey(activeRole(playlistId))) return 0
        val added = 0
        plan.merge.forEach { r ->
            var local = store.forPlaylist(playlistId).firstOrNull { it.username.equals(r.username, ignoreCase = true) }
            Log.i(TAG, "[DSYNC] profiles: row ${r.username} updatedAt=${r.updatedAt} removedAt=${removedAt(r.username)} local=${if (local == null) "no" else "yes"}")
            // Catalog semantics: a pull never creates a profile. The row is
            // offered under "From your other devices" until chosen.
            if (local == null) return@forEach
            val id = local.id
            // Added during a roster pause: it never saw this row, so it takes it
            // now, row stamp and all, as a manual add of a roster user does.
            val pausedAdd = hostHolds.isPausedAdd(id)
            if (local.userId == 0 && r.userId > 0) store.update(id) { it.copy(userId = r.userId) }
            val mine = localUpdatedAt(local.username)
            // No local claim on name/avatar (never renamed or given an avatar
            // here): the server's name and avatar apply whatever the stamps say.
            val adopt = ProfileRoster.adoptUncustomized(local.displayName, local.avatar, customized(local.username, local.displayName, local.avatar), r)
            if (adopt != null) {
                store.update(id) { it.copy(displayName = adopt.first, avatar = adopt.second) }
                Log.i(TAG, "[DSYNC] profiles: adopted synced name/avatar for ${local.username}")
                local = store.byId(id) ?: local
            }
            val repair = if (pausedAdd) ProfileRoster.Repair.None else ProfileRoster.repair(local.displayName, local.avatar, mine, r)
            if (repair == ProfileRoster.Repair.ResendLocal) {
                Log.i(TAG, "[DSYNC] profiles: server row ${local.username} looks stripped; re-sending local")
                mutated(id, "repair")
                return@forEach
            }
            if (repair == ProfileRoster.Repair.ApplyServer) {
                Log.i(TAG, "[DSYNC] profiles: local row ${local.username} is stripped; applying server name/avatar")
            } else if (!RosterPause.rowApplies(pausedAdd, mine, r)) {
                Log.i(TAG, "[DSYNC] profiles: keep local row for ${local.username} (local $mine >= server ${r.updatedAt})")
                return@forEach
            }
            if (pausedAdd) {
                // It is a roster add from now on: undoing it (still possible
                // in the onboarding step or the PIN step) leaves no tombstone.
                rosterAdds.pauseAddMerged(id, tookRow = true)
                Log.i(TAG, "[PROFILE-HOST] ${local.username} added during the roster pause takes its roster row")
            }
            if (r.displayName.isNotBlank() && r.displayName != local.displayName) {
                store.update(id) { it.copy(displayName = r.displayName) }
            }
            ProfileRole.fromWire(r.role)?.let { ProfileRole.resolve(local.userLevel, it) }?.let { role ->
                if (role != local.effectiveRole() || local.role == null) {
                    store.update(id) { it.copy(role = role) }
                    Log.i(TAG, "[DSYNC] profiles: role ${role.label} for ${local.username}")
                }
            }
            // Absent order (older writer, -1) keeps the local position.
            if (r.order >= 0 && r.order != local.order) {
                store.update(id) { it.copy(order = r.order) }
                Log.i(TAG, "[DSYNC] profiles: order ${r.order} for ${local.username}")
            }
            // Absent (older writer) keeps the local avatar; {} is a deliberate default.
            val avatar = r.avatar?.let(AvatarCatalog::normalize)
            if (r.avatar != null && avatar != local.avatar) {
                store.update(id) { it.copy(avatar = avatar) }
                Log.i(TAG, "[DSYNC] profiles: avatar for ${local.username}")
            }
            val type = PinRules.typeFromWire(r.pinType)
            if (type == null) {
                // A v1 PIN set on this device before sync stays until re-entered,
                // and so does a PIN set right after an add during the pause.
                if (!pausedAdd && pinStore.hasPin(id) && !pinStore.isLegacy(id)) {
                    pinStore.remove(id)
                    Log.i(TAG, "[DSYNC] profiles: pin removed for ${local.username} (server row newer)")
                }
            } else if (r.pinHash.length == 64 && r.pinSalt.length == 32) {
                pinStore.importSynced(id, type, r.pinHash, r.pinSalt)
            }
            adopted(local.username, r.updatedAt)
        }
        // Every profile added during the pause has now met the roster: one with
        // a row took it above; one without stays a plain add.
        // Settled only here, inside the merge of a pull that decoded the roster:
        // sync never calls this for a failed, absent, or malformed read.
        val marked = store.forPlaylist(playlistId).filter { hostHolds.isPausedAdd(it.id) }.map { it.id }
        RosterPause.settledPauseAdds(marked, rows).forEach { hostHolds.clearPausedAdd(it) }
        refreshActiveRole()
        return added
    }

    private val _roster = kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<com.aeriotv.android.core.sync.dsync.ProfileRow>>>(emptyMap())

    private fun publishRoster(playlistId: String, rows: List<com.aeriotv.android.core.sync.dsync.ProfileRow>) {
        _roster.value = _roster.value + (playlistId to rows)
    }

    /**
     * Synced roster rows for users with no profile on this device: the
     * "From your other devices" suggestions. Empty until a pull lands (or
     * with Dispatcharr Sync off); updates when it does.
     */
    fun rosterSuggestions(playlistId: String): Flow<List<com.aeriotv.android.core.sync.dsync.ProfileRow>> =
        combine(_roster, store.profiles, _rosterPaused) { r, local, paused ->
            ProfileRoster.suggestions(
                RosterPause.visibleRows(r[playlistId].orEmpty(), playlistId in paused),
                local.filter { it.playlistId == playlistId }.map { it.username },
            )
        }.distinctUntilChanged()

    /**
     * Add a profile from a roster suggestion: the same credential path as
     * [addProfile] (listed api key, else the typed password or key), then the
     * row's role, PIN, avatar, display name, and order. A Child row needs an
     * Admin PIN on this playlist first (RoleFlow rule, enforced in [addProfile]).
     */
    suspend fun addFromRoster(
        playlistId: String,
        row: com.aeriotv.android.core.sync.dsync.ProfileRow,
        user: DispatcharrUserSummary,
        password: String?,
        apiKey: String?,
        /** A device setup keeps the sender's profile id. */
        wantedId: String? = null,
        /** A manual add's picked role, for a row from an older writer that carries none. */
        fallbackRole: ProfileRole? = null,
    ): Result<DispatcharrProfile> {
        val role = ProfileRole.fromWire(row.role) ?: fallbackRole
        // One step: the row's fields and PIN are applied inside addProfile
        // before the sync hook fires (an applyRow after the add raced the push).
        val added = addProfile(playlistId, user, password, apiKey, role, seed = row, wantedId = wantedId).getOrElse { return Result.failure(it) }
        refreshActiveRole()
        Log.i(TAG, "[DSYNC] profiles: added ${row.username} from the roster (explicit choice) role=${role?.label ?: "default"} order=${row.order}")
        rosterUndoable.update { it + added.id }
        return Result.success(store.byId(added.id) ?: added)
    }

    // ---- Set up a TV from your phone (docs/DeviceSetupHandoff.md) ----

    /** True when [profileId] has its own saved API key or password here (the default profile uses the playlist login). */
    fun hasSavedCredential(profileId: String): Boolean =
        store.byId(profileId)?.let { ProfileHostHold.withoutHeldSignIn(it, hostHolds.isHeld(it.id)) }
            ?.let { it.isDefault || !it.apiKey.isNullOrBlank() || !it.password.isNullOrBlank() } ?: false

    /** Sign in once as [profileId]'s user and save the password or key on that profile. */
    suspend fun saveProfileCredential(playlistId: String, profileId: String, password: String?, apiKey: String?): Result<Unit> = runCatching {
        val p = store.byId(profileId) ?: error("Profile not found")
        val row = playlistDao.rawById(playlistId) ?: error("Playlist not found")
        val base = repository.effectiveBaseUrl(row)
        // The user typed this sign-in on purpose: let it reach the server even
        // if an earlier copy of the key is held as rejected.
        apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { com.aeriotv.android.core.network.DispatcharrRequestGate.clearKey(it) }
        val key = authenticate(playlistId, p.id, base, p.username, password, apiKey).key
        val me = client.fetchMe(base, key) ?: error("Could not read ${p.username} from the server.")
        if (!me.username.equals(p.username, ignoreCase = true)) error("This API key belongs to ${me.username}, not ${p.username}.")
        store.update(profileId) { it.copy(password = password?.takeIf { s -> s.isNotBlank() } ?: it.password).applyMe(me, key) }
        ownSignInRepaired(playlistId, profileId)
        Log.i(TAG, "[PROFILE] credential saved for ${p.username}")
    }

    // ---- Show User QR Code (docs/DeviceSetupHandoff.md) ----

    /** The `user_api_key` QR text for [profileId], or null when no API key is saved here. Never log it. */
    suspend fun userQrText(playlistId: String, profileId: String): String? {
        val row = playlistDao.rawById(playlistId) ?: return null
        if (!row.sourceType.startsWith("Dispatcharr")) return null
        val p = store.byId(profileId)?.takeIf { it.playlistId == playlistId }
            ?.let { ProfileHostHold.withoutHeldSignIn(it, hostHolds.isHeld(it.id)) } ?: return null
        val key = (if (p.isDefault) row.apiKey else p.apiKey)?.takeIf { it.isNotBlank() } ?: return null
        val user = p.username.ifBlank { row.username.orEmpty() }
        return com.aeriotv.android.core.qr.ScannedCode.userKeyText(key, row.urlString, user)
    }

    /** True when showing [profileId]'s QR needs a PIN: that profile's PIN or the Device Admin PIN. */
    fun userQrNeedsPin(playlistId: String, profileId: String): Boolean =
        pinStore.hasPin(profileId) || hasDeviceAdminPin(playlistId)

    fun userQrPinType(playlistId: String, profileId: String): PinType? =
        pinStore.type(profileId) ?: deviceAdminPinType(playlistId)

    /** Either the profile's own PIN or the Device Admin PIN passes; each keeps its own backoff. */
    @Synchronized
    fun verifyUserQrPin(playlistId: String, profileId: String, entries: List<String>): PinCheck {
        val ids = listOf(profileId, deviceAdminPinId(playlistId)).filter { pinStore.hasPin(it) }
        if (ids.isEmpty()) return PinCheck.Ok
        val now = System.currentTimeMillis()
        var lock = Int.MAX_VALUE
        var tried = false
        for (id in ids) {
            val b = pinStore.backoff(id)
            val left = b.remainingSeconds(now)
            if (left > 0) { lock = minOf(lock, left); continue }
            tried = true
            if (pinStore.check(id, entries)) { pinStore.saveBackoff(id, b.onOk()); return PinCheck.Ok }
            val next = b.onWrong(now)
            pinStore.saveBackoff(id, next)
            next.remainingSeconds(now).takeIf { it > 0 }?.let { lock = minOf(lock, it) }
        }
        Log.i(TAG, "[PROFILE] user QR pin wrong")
        val open = ids.any { pinStore.backoff(it).remainingSeconds(now) == 0 }
        return if (tried && open) PinCheck.Wrong else PinCheck.Locked(if (lock == Int.MAX_VALUE) 1 else lock)
    }

    /** Sender gate: an Admin profile, or the default profile, is active here. */
    fun canSetUpTv(playlistId: String): Boolean =
        RoleRules.isFullAccess(activeRole(playlistId)) || activeProfile(playlistId)?.isDefault != false

    // ---- The account a playlist signs in as (item 11: same-account sign-ins) ----

    /** The default profile's Dispatcharr username on [playlistId], or null. */
    fun defaultProfileUsername(playlistId: String): String? =
        store.forPlaylist(playlistId).firstOrNull { it.isDefault }?.username?.trim()?.takeIf { it.isNotEmpty() }

    /** The default identity recorded for [playlistId] (users/me with the default sign-in; frozen while held), or null. */
    fun recordedDefaultIdentity(playlistId: String): ProfileHostHold.Identity? = hostHolds.recorded(playlistId)

    /** The active or the default profile on [playlistId] is a Child. */
    fun childInvolved(playlistId: String): Boolean =
        activeRole(playlistId) == ProfileRole.Child ||
            store.forPlaylist(playlistId).firstOrNull { it.isDefault }?.effectiveRole() == ProfileRole.Child

    /** The playlist row with the DEFAULT profile's credential (never a switched profile's). */
    suspend fun defaultPlaylistRow(playlistId: String): PlaylistEntity? = playlistDao.rawById(playlistId)

    /** Every playlist row with the DEFAULT profile's credential (Fix Login's card rule). */
    suspend fun defaultPlaylistRows(): List<PlaylistEntity> = playlistDao.rawAll()

    /** A local profile plus its v2 PIN record, for the setup payload. */
    data class SetupSource(val profile: DispatcharrProfile, val pin: ProfilePinStore.Record?)

    /** A held profile (server address changed) comes without its stored sign-in, so no payload carries it. */
    fun setupSources(playlistId: String): List<SetupSource> =
        store.forPlaylist(playlistId).map { SetupSource(ProfileHostHold.withoutHeldSignIn(it, hostHolds.isHeld(it.id)), pinStore.syncRecord(it.id)) }

    /**
     * TV side: apply one handed-over profile. The default profile already
     * exists once the playlist is active (it signs in with the playlist
     * credential), so it only takes the row's name, role, avatar, order, and
     * PIN. Any other profile is added through [addFromRoster], the same
     * credential check as Add Profile, unlocked for this one call as the TV
     * picker's Add Profile is.
     */
    suspend fun applySetupProfile(
        playlistId: String,
        row: com.aeriotv.android.core.sync.dsync.ProfileRow,
        isDefault: Boolean,
        password: String?,
        apiKey: String?,
        /** The sender's profile id (docs/DeviceSetupHandoff.md), kept for a profile created here. */
        profileId: String? = null,
    ): Result<Unit> = runCatching {
        if (isDefault) {
            var local: DispatcharrProfile? = null
            for (i in 0 until 50) {
                local = store.forPlaylist(playlistId).firstOrNull { it.isDefault }
                if (local != null) break
                kotlinx.coroutines.delay(200)
            }
            val p = local ?: error("The default profile is not ready yet.")
            store.update(p.id) { ProfileRoster.applyRow(it, row) }
            PinRules.typeFromWire(row.pinType)?.let { type ->
                if (row.pinHash.length == 64 && row.pinSalt.length == 32) pinStore.importSynced(p.id, type, row.pinHash, row.pinSalt)
            }
            mutated(p.id, "setup")
            refreshActiveRole()
            Log.i(TAG, "[SETUP] default profile ${p.username} updated from the phone")
            return@runCatching
        }
        if (store.forPlaylist(playlistId).any { it.username.equals(row.username, ignoreCase = true) }) return@runCatching
        pickerAddUnlocked = playlistId
        try {
            addFromRoster(playlistId, row, DispatcharrUserSummary(id = row.userId, username = row.username), password, apiKey, wantedId = profileId).getOrThrow()
        } finally {
            pickerAddUnlocked = null
        }
        Log.i(TAG, "[SETUP] added ${row.username} from the phone")
    }

    /** Row menu "Avatar" (2c). Stamps the synced row like a rename. */
    fun setAvatar(playlistId: String, profileId: String, avatar: AvatarSpec?): Result<Unit> = runCatching {
        val target = store.byId(profileId) ?: error("Profile not found")
        val isOwn = activeProfileIdNow(playlistId) == profileId
        check(RoleRules.canEditAvatar(activeRole(playlistId), isOwn)) { "Only an Admin or Standard profile can change another profile's avatar." }
        val clean = AvatarCatalog.normalize(avatar)
        if (clean == target.avatar) return@runCatching
        store.update(profileId) { it.copy(avatar = clean) }
        mutated(profileId, "avatar")
        Log.i(TAG, "[PROFILE] avatar ${clean?.icon ?: AvatarCatalog.INITIAL}/${clean?.color?.ifEmpty { "default" } ?: "default"} for ${target.username}")
    }

    enum class Move { Earlier, Later, First }

    /**
     * Admin reorder (picker "Move Left" / "Move Right" / "Move to Front",
     * Settings "Move Up" / "Move Down"). Every profile, the default one
     * included, can move. Rows whose position changed are stamped so the
     * order syncs per row. Returns the moved profile's new index.
     */
    fun move(playlistId: String, profileId: String, how: Move): Result<Int> = runCatching {
        check(RoleRules.isFullAccess(activeRole(playlistId))) { "Only an Admin or Standard profile can reorder profiles." }
        val list = store.forPlaylist(playlistId).toMutableList()
        val from = list.indexOfFirst { it.id == profileId }
        check(from >= 0) { "Profile not found" }
        val to = when (how) {
            Move.Earlier -> from - 1
            Move.Later -> from + 1
            Move.First -> 0
        }.coerceIn(0, list.lastIndex)
        if (to == from && list.all { it.order >= 0 }) return@runCatching from
        val p = list.removeAt(from)
        list.add(to, p)
        commitOrder(playlistId, list)
        Log.i(TAG, "[PROFILE] moved ${p.username} $from -> $to on ${playlistId.take(8)}")
        to
    }

    /**
     * Admin reorder with the FULL new order (TV pick-up place). Same role
     * check, store write, and per-row "order" stamps as [move], so sync,
     * tombstones, and roster rules see exactly what a run of single moves
     * would produce. Returns true when anything was written.
     */
    fun setOrder(playlistId: String, orderedIds: List<String>): Result<Boolean> = runCatching {
        check(RoleRules.isFullAccess(activeRole(playlistId))) { "Only an Admin or Standard profile can reorder profiles." }
        val current = store.forPlaylist(playlistId)
        val ids = ProfileOrder.resolve(current.map { it.id }, orderedIds)
        if (ids == current.map { it.id } && current.all { it.order >= 0 }) return@runCatching false
        val byId = current.associateBy { it.id }
        commitOrder(playlistId, ids.mapNotNull { byId[it] })
        Log.i(TAG, "[PROFILE] set order n=${ids.size} on ${playlistId.take(8)}")
        true
    }

    /** Persist [list] as the playlist's order and stamp every row whose position changed. */
    private fun commitOrder(playlistId: String, list: List<DispatcharrProfile>) {
        val before = list.associate { it.id to it.order }
        store.setOrder(playlistId, list.map { it.id })
        list.forEachIndexed { i, q -> if (before[q.id] != i) mutated(q.id, "order") }
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
        check(RoleRules.isFullAccess(activeRole(p.playlistId))) { "Only an Admin or Standard profile can remove profiles." }
        if (RoleRules.removeNeedsPin(p.effectiveRole())) {
            check(consumeAdminTicket(p.playlistId)) { PinCopy.ADMIN_PAD }
        }
        deleteLocal(p, fromSync = false)
    }

    /**
     * A synced removal tombstone (schema 4.12) beat this local profile: the
     * same local cleanup as [remove], with no PIN prompt and no new
     * tombstone (the removal is already on the server). The default
     * profile is never removed.
     */
    suspend fun removeFromSync(playlistId: String, username: String): Boolean {
        val p = store.forPlaylist(playlistId).firstOrNull { it.username.equals(username, ignoreCase = true) } ?: return false
        if (p.isDefault) return false
        if (!RoleRules.canWriteProfilesKey(activeRole(playlistId))) return false
        return runCatching { deleteLocal(p, fromSync = true) }
            .onFailure { Log.w(TAG, "[DSYNC] profiles: synced removal of ${p.username} failed: ${it.message}") }
            .isSuccess
    }

    /** Profile ids added from the roster in this process; only these can be undone. */
    private val rosterUndoable = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** Ids [undoAdd] accepts right now (onboarding shows Remove on these rows). */
    val undoableRosterAdds: kotlinx.coroutines.flow.StateFlow<Set<String>> get() = rosterUndoable

    /** Of [rosterUndoable], the ids created fresh on this device (onboarding manual add), not from the roster. */
    private val freshUndoable = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Onboarding manual add: the profile just created here may be removed
     * with the red minus. Unlike a roster undo, its server row exists only
     * because this device pushed it, so its undo writes the tombstone.
     */
    fun markFreshAddUndoable(profileId: String) {
        // A manual add that finished as a roster add is already undoable, and
        // its undo must write no tombstone: the row is on the server.
        if (profileId in rosterUndoable.value) return
        freshUndoable += profileId
        rosterUndoable.update { it + profileId }
    }
    fun canUndoAdd(profileId: String): Boolean = profileId in rosterUndoable.value

    /**
     * Undo a roster add ("Remove" beside "Added"): the same local cleanup as
     * [remove] on THIS DEVICE ONLY, with no Admin PIN and no tombstone.
     *
     * Server copy decision: a roster row is by definition already in the
     * server `profiles` key (another device pushed it). The add may have
     * re-sent that row (same userId, server name and avatar kept by
     * ProfileRoster.guardFreshRosterAdd). After the undo the local profile is
     * gone, and buildProfiles keeps every server row this device has no
     * profile for, so the server copy stays exactly as the other devices
     * wrote it. The undo therefore writes no tombstone, marks nothing
     * pending, and pushes nothing; the sync hook only drops this device's
     * per-row stamps ("undo-add").
     */
    suspend fun undoAdd(profileId: String): Result<Unit> = runCatching {
        check(profileId in rosterUndoable.value) { "Only a profile just added here can be undone." }
        val p = store.byId(profileId) ?: run { rosterUndoable.update { it - profileId }; return@runCatching }
        check(!p.isDefault) { "The default profile cannot be removed." }
        freshUndoable.remove(profileId)
        deleteLocal(p, fromSync = false, tombstone = ProfileRoster.undoWritesTombstone(undoesRosterAdd(profileId)))
        rosterUndoable.update { it - profileId }
        refreshActiveRole()
        Log.i(TAG, "[PROFILES] undo add ${p.username}")
    }

    /**
     * [fromSync]: the removal came from a synced tombstone (log wording only
     * beyond the hook). [tombstone]: emit the "remove" sync hook, which
     * stamps a schema 4.12 tombstone that removes the profile on every
     * device. A roster undo passes false and gets the "undo-add" hook instead.
     */
    private suspend fun deleteLocal(p: DispatcharrProfile, fromSync: Boolean, tombstone: Boolean = !fromSync) {
        val profileId = p.id
        if (store.activeProfileId(p.playlistId) == profileId) {
            val def = store.forPlaylist(p.playlistId).first { it.isDefault }
            // The Admin removing the active profile: internal, no PIN prompt.
            switchTo(p.playlistId, def.id, bypassPin = true, appMade = true).getOrThrow()
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
        // A held profile is removed on this device only: its row was never on
        // the new server's roster, and a tombstone sent there after the pause
        // could delete a same-named profile on that server's devices.
        val held = hostHolds.isHeld(profileId)
        if (RosterPause.writesTombstone(tombstone, held)) runCatching { onProfilesMutated?.invoke(p.playlistId, p.username, "remove", 0L) }
        else if (held && !fromSync) runCatching { onProfilesMutated?.invoke(p.playlistId, p.username, "local-remove", 0L) }
        else if (!fromSync) runCatching { onProfilesMutated?.invoke(p.playlistId, p.username, "undo-add", 0L) }
        dropHold(profileId, "removed")
        rosterAdds.forget(profileId)
        hostHolds.tag(profileId, null)
        hostHolds.clearPausedAdd(profileId)
        store.remove(profileId)
        Log.i(TAG, "[PROFILE] removed ${p.username} on ${p.playlistId.take(8)}; local state deleted${if (fromSync) " (synced removal)" else ""}")
    }

    /**
     * Switch [playlistId] to [profileId]. The caller has already confirmed
     * stopping playback; PlaylistViewModel stops it on the reload. Signs in
     * as the profile through DispatcharrLoginGate (one attempt per 60 s per
     * profile, 429 honored), re-reads permissions, swaps the scope, and asks
     * for a channel / group / VOD reload.
     */
    suspend fun switchTo(
        playlistId: String,
        profileId: String,
        bypassPin: Boolean = false,
        /**
         * The app switched on its own (a removal, a synced tombstone, a
         * switch back after a failed fast switch), not the switcher or the
         * launch picker: no sign-in form ever opens, and a target whose key
         * is held is landed on with nothing sent (APP8-1, Apple parity).
         */
        appMade: Boolean = false,
    ): Result<Unit> = runCatching {
        if (!bypassPin && pinStore.hasPin(profileId)) {
            check(switchTickets.remove(profileId)) { PinCopy.switchNeedsPin(store.byId(profileId)?.displayName.orEmpty()) }
        }
        if (appMade) {
            val target = store.byId(profileId) ?: error("Profile not found")
            val def = store.forPlaylist(playlistId).firstOrNull { it.isDefault }
            val to = def?.let { RejectedKeySwitch.appMadeTarget(target, it.id, unusable(target.id)) } ?: profileId
            if (unusable(to)) {
                // A Child whose sign-in cannot be used: no form and no switch.
                Log.i(TAG, "[PROFILE] app-made switch on ${playlistId.take(8)} not run: the target's sign-in cannot be used")
                return@runCatching
            }
            val row = playlistDao.rawById(playlistId)
            val landHeld = store.byId(to)?.let {
                RejectedKeySwitch.step(it, row?.apiKey, com.aeriotv.android.core.network.DispatcharrRequestGate::isKeyRejected, chosenByUser = false)
            } == RejectedKeySwitch.Step.LandHeld
            runSwitch(playlistId, to, landHeld)
            return@runCatching
        }
        if (unusable(profileId)) {
            // Its sign-in is held after a server address change, or it has
            // none stored: nothing is sent, and never the playlist's own.
            // The "Sign in to Dispatcharr" form asks for a fresh one.
            val held = hostHolds.isHeld(profileId)
            _heldSignIn.value = HeldSignIn(playlistId, profileId, playlistDao.rawById(playlistId)?.name.orEmpty(), addressChanged = held)
            Log.i(TAG, "[PROFILE-HOST] sign-in form opened for a ${if (held) "held" else "signed-out"} profile on ${playlistId.take(8)}")
            throw ProfileSignInHeld()
        }
        // Its key is held as rejected (2026-11-12, Roku spec 8.4 step 4): the
        // sign-in form opens now, before sync or playback is touched, and
        // nothing is sent with the held key. A pass continues this switch.
        store.byId(profileId)?.let { target ->
            val row = playlistDao.rawById(playlistId)
            val form = RejectedKeySwitch.formFor(target, row?.apiKey, com.aeriotv.android.core.network.DispatcharrRequestGate::isKeyRejected)
            if (form != RejectedKeySwitch.Form.None) {
                _heldSignIn.value = HeldSignIn(playlistId, profileId, row?.name.orEmpty(), addressChanged = false, playlistOwn = form == RejectedKeySwitch.Form.PlaylistOwn)
                Log.i(TAG, "[AUTH] sign-in form opened: the chosen profile's key is held as rejected on ${playlistId.take(8)}")
                throw ProfileSignInHeld()
            }
        }
        runSwitch(playlistId, profileId, landHeld = false)
    }

    /** The switch proper, after the PIN and any form: scrim, sign-in (none when [landHeld]), scope swap, reload. */
    private suspend fun runSwitch(playlistId: String, profileId: String, landHeld: Boolean) {
        val gen = switchGen.incrementAndGet()
        watchdog?.cancel()
        watchdog = null
        _switching.value = true
        // Under the scrim, before any reload: land on the Live TV root now so
        // the scrim drops onto the guide, never onto the previous tab.
        closePlayer.trySend(Unit)
        ProfileSwitchFocus.onSwitchStarted()
        try {
            // Off the main thread: prefs commits and the row write are disk I/O.
            kotlinx.coroutines.withContext(Dispatchers.IO) { switchToLocked(playlistId, profileId, gen, landHeld) }
        } catch (t: Throwable) {
            if (gen == switchGen.get()) _switching.value = false
            throw t
        }
    }

    private suspend fun switchToLocked(playlistId: String, profileId: String, gen: Long, landHeld: Boolean = false) {
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
        // Warm switch (2026-10-27): the target has a stored key and a stamped
        // channel snapshot, so the guide can paint from cache at once. The
        // scope is activated from the STORED profile and the reload is sent
        // before any network; sign-in and /me/ run behind the painted guide
        // (verifyWarmSwitch). Only a profile with no snapshot waits on them.
        val targetKey = if (target.isDefault) playlistId else "$playlistId@${target.id}"
        val signIn = ProfileHostHold.usable(target, hostHolds.isHeld(target.id))
        val storedKey = (if (target.isDefault) row.apiKey else signIn.apiKey)?.takeIf { it.isNotBlank() }
        val warm = storedKey != null &&
            snapshotStamps.get(targetKey) != null &&
            runCatching { channelSnapshotDao.newestFetchedAtKey(targetKey) }.getOrNull() != null
        // An app-made switch to a target whose key is held: land on it like
        // a warm switch, with no sign-in and nothing sent with the held key.
        if (warm || landHeld) {
            mutex.withLock {
                tLock = System.currentTimeMillis()
                // A hold that landed since switchTo looked: never activate it.
                if (!target.isDefault && unusable(target.id)) throw ProfileSignInHeld()
                if (target.isDefault) {
                    ProfileScope.activate(null)
                    store.setActiveProfileId(playlistId, null)
                } else {
                    ProfileScope.activate(target.toOverlay())
                    store.setActiveProfileId(playlistId, target.id)
                }
                tokenStore.clear(playlistId)
                CapabilityCorrections.clear(playlistId)
                timeshift.stopAllRetained("profile switch")
            }
            refreshActiveRole(playlistId)
            if (landHeld) {
                Log.i(TAG, "[PROFILE] switch ${from?.displayName ?: "?"} -> ${target.displayName} (app-made; its key is held as rejected: no sign-in, nothing sent with it)")
            } else {
                Log.i(TAG, "[PROFILE] switch ${from?.displayName ?: "?"} -> ${target.displayName} (warm: cache first, sign-in behind)")
                pendingProbe = PendingProbe(playlistId, gen, System.currentTimeMillis())
            }
            Log.i(TAG, "[SWITCH] profile lock=${tLock - t0}ms gate=${System.currentTimeMillis() - t0}ms path=warm (auth and /me/ deferred)")
            watchdog = scope.launch {
                kotlinx.coroutines.delay(SWITCH_TIMEOUT_MS)
                if (_switching.value && gen == switchGen.get()) {
                    Log.w(TAG, "[PROFILE] reload did not report back in ${SWITCH_TIMEOUT_MS / 1000}s; releasing the UI")
                    finishSwitch(gen)
                }
            }
            // The sign-in and /me/ read run AFTER the scrim drops (finishSwitch),
            // not beside the reload: launched here they finished before the
            // cached paint in both directions (Streamer 2026-10-27 step4/step5),
            // competing with it for the network and the Room writer. Nothing
            // waits on the verification; it only sends a silent reload when
            // the permissions changed.
            if (!landHeld) pendingVerify = PendingVerify(gen) { verifyWarmSwitch(playlistId, target, from, base, row, gen) }
            reloads.send(Reload(playlistId, gen))
            return
        }
        mutex.withLock {
            tLock = System.currentTimeMillis()
            if (target.isDefault) {
                val a = authenticate(playlistId, target.id, base, row.username.orEmpty(), ownPasswordFor(playlistId, base, row), row.apiKey)
                path = a.path
                tAuth = System.currentTimeMillis()
                // Phase 2b: /users/me/ is the only read that gates the scrim;
                // apply it to the playlist row (the default profile's home).
                val me = a.me ?: client.fetchMe(base, a.key)
                tMe = System.currentTimeMillis()
                ProfileScope.activate(null)
                store.setActiveProfileId(playlistId, null)
                if (me != null) applyMeToDefaultRow(playlistId, target.id, me, base, a.key)
            } else {
                if (unusable(target.id)) throw ProfileSignInHeld()
                val held = ProfileHostHold.usable(target, hostHolds.isHeld(target.id))
                val a = authenticate(playlistId, target.id, base, target.username, held.password, held.apiKey)
                path = a.path
                val key = a.key
                tAuth = System.currentTimeMillis()
                // The stored-key path already read /me/; only a fresh login
                // needs the extra read.
                val me = a.me ?: client.fetchMe(base, key) ?: error("Could not read ${target.username} from the server.")
                tMe = System.currentTimeMillis()
                val updated = target.applyMe(me, key)
                store.upsert(updated)
                ownSignInRepaired(playlistId, updated.id)
                followServerRole(updated.id, updated.userLevel)
                runCatching { tagVerified(playlistId, updated.id) }
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

    /**
     * Behind a warm switch's cached paint: sign in as the target and read
     * /me/ exactly as the cold path does before the reload. Fresh
     * permissions are applied; a changed channel_profiles assignment or
     * permission set asks for a silent channel refresh. A rejected login
     * switches back to the previous profile; a network failure keeps the
     * stored profile (its key is what every request uses anyway).
     */
    private suspend fun verifyWarmSwitch(
        playlistId: String,
        target: DispatcharrProfile,
        from: DispatcharrProfile?,
        base: String,
        row: PlaylistEntity,
        gen: Long,
    ) {
        val t0 = System.currentTimeMillis()
        try {
            var changed = false
            mutex.withLock {
                if (gen != switchGen.get()) return
                if (target.isDefault) {
                    val a = authenticate(playlistId, target.id, base, row.username.orEmpty(), ownPasswordFor(playlistId, base, row), row.apiKey)
                    val me = a.me ?: client.fetchMe(base, a.key)
                    if (gen != switchGen.get()) return
                    if (me != null) {
                        val before = playlistDao.rawById(playlistId)
                        applyMeToDefaultRow(playlistId, target.id, me, base, a.key)
                        val after = playlistDao.rawById(playlistId)
                        changed = SnapshotStaleness.normalize(before?.dispatcharrAccountProfileIds.orEmpty()) !=
                            SnapshotStaleness.normalize(after?.dispatcharrAccountProfileIds.orEmpty()) ||
                            before?.dispatcharrCatchupEnabled != after?.dispatcharrCatchupEnabled
                    }
                } else {
                    // Held since the switch (a server address change): its stored sign-in stays here.
                    if (hostHolds.isHeld(target.id)) return
                    val a = authenticate(playlistId, target.id, base, target.username, target.password, target.apiKey)
                    val me = a.me ?: client.fetchMe(base, a.key)
                        ?: error("Could not read ${target.username} from the server.")
                    if (gen != switchGen.get()) return
                    val updated = target.applyMe(me, a.key)
                    store.upsert(updated)
                    ownSignInRepaired(playlistId, updated.id)
                    followServerRole(updated.id, updated.userLevel)
                    runCatching { tagVerified(playlistId, updated.id) }
                    if (ProfileScope.current() == target.id) ProfileScope.activate(updated.toOverlay())
                    val o1 = target.toOverlay()
                    val o2 = updated.toOverlay()
                    changed = SnapshotStaleness.normalize(o1.accountProfileIds) != SnapshotStaleness.normalize(o2.accountProfileIds) ||
                        o1.catchupEnabled != o2.catchupEnabled
                }
            }
            refreshActiveRole(playlistId)
            logPermissions(playlistId, store.byId(target.id)?.username?.ifBlank { null } ?: row.username.orEmpty())
            Log.i(TAG, "[SWITCH] warm switch verified in ${System.currentTimeMillis() - t0}ms (permissions changed=$changed)")
            if (changed && gen == switchGen.get()) reloads.send(Reload(playlistId, gen, fromSwitch = false))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: DispatcharrError.InvalidCredentials) {
            Log.w(TAG, "[SWITCH] warm switch sign-in rejected for ${target.displayName}; returning to ${from?.displayName ?: "the previous profile"}")
            if (gen == switchGen.get() && from != null && from.id != target.id) {
                switchTo(playlistId, from.id, bypassPin = true, appMade = true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[SWITCH] warm switch verification failed (stored profile kept): ${t.message}")
        }
    }

    // ---- Server address change (security fix A-APP-1, shared spec 2026-11-11) ----
    // Edit Playlist, a device setup in place, and a Google Drive pull can move
    // a playlist to another scheme, host, or port. Every non-default profile's
    // stored sign-in is held from before the first request until users/me
    // with the DEFAULT profile's sign-in proves the new address is the same
    // server, or the profile signs in again. Logs never carry a key or a
    // password, only the [PROFILE-HOST] lines below.

    /** A held profile the user picked: the "Sign in to Dispatcharr" form is up for it. */
    data class HeldSignIn(
        val playlistId: String,
        val profileId: String,
        val playlistName: String,
        /** Held after a server address change; false = it has no stored sign-in, or its key is held as rejected (the plain form). */
        val addressChanged: Boolean = true,
        /** The default profile, whose key (the playlist's own) is held as rejected: the playlist's own sign-in form, then the switch goes on. */
        val playlistOwn: Boolean = false,
    )

    private val _heldSignIn = kotlinx.coroutines.flow.MutableStateFlow<HeldSignIn?>(null)
    val heldSignIn: kotlinx.coroutines.flow.StateFlow<HeldSignIn?> = _heldSignIn

    /**
     * Dispatcharr Sync hook (set by DispatcharrSyncManager): a server address
     * change on a playlist starts (landed false, before anything is sent to
     * the new address) or lands (true). Sync drops its saved server copy for
     * every profile of that playlist and restarts at the current address.
     */
    @Volatile var onRouteChange: ((playlistId: String, landed: Boolean) -> Unit)? = null
    private val routeChangeStarted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** A server address change on a playlist starts or lands, as [onRouteChange] sees it. */
    data class RouteChange(val playlistId: String, val landed: Boolean)

    /**
     * The same starts and landings as [onRouteChange], for On Demand: a
     * running catalog sweep walks the address it read when it started, so it
     * stops when a change starts and starts again once the change lands.
     * Emitted with tryEmit only, so it is as safe as [onRouteChange] inside
     * the Drive pull's transaction.
     */
    private val _routeChanges = kotlinx.coroutines.flow.MutableSharedFlow<RouteChange>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val routeChanges: kotlinx.coroutines.flow.SharedFlow<RouteChange> = _routeChanges

    /** True from the start of a server address change on [playlistId] until it lands. */
    fun isRouteChanging(playlistId: String): Boolean = routeChangeStarted.contains(playlistId)

    private fun routeChange(playlistId: String, landed: Boolean) {
        runCatching { onRouteChange?.invoke(playlistId, landed) }
        _routeChanges.tryEmit(RouteChange(playlistId, landed))
    }

    /** Playlists whose live profile a hold left for the default one; their screen reloads once the change lands. */
    private val leftLive = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val checks = HashMap<String, kotlinx.coroutines.Job>()

    fun isSignInHeld(profileId: String): Boolean = unusable(profileId)

    /** [profileId] is non-default and its sign-in cannot be used: held, or none stored (treated the same). */
    private fun unusable(profileId: String): Boolean =
        store.byId(profileId)?.let { ProfileHostHold.signInUnusable(it, hostHolds.isHeld(it.id)) } == true

    /** Open the plain sign-in form for [profileId], which has no stored sign-in. */
    private fun openNoSignInForm(playlistId: String, profileId: String, playlistName: String) {
        if (hostHolds.isHeld(profileId)) return
        if (store.byId(profileId)?.let(ProfileHostHold::hasNoSignIn) != true) return
        _heldSignIn.value = HeldSignIn(playlistId, profileId, playlistName, addressChanged = false)
        Log.i(TAG, "[PROFILE] sign-in form opened: the active profile on ${playlistId.take(8)} has no stored sign-in")
    }

    /** Playlists whose Dispatcharr Sync `profiles` category is paused (some profile on them is held). */
    private val _rosterPaused = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** [playlistId]'s roster sync is paused in both directions (item 4): read by Dispatcharr Sync. */
    fun isRosterPaused(playlistId: String): Boolean = hostHolds.isPaused(playlistId)

    /**
     * After any hold change: the held set the overlay checks, and the roster
     * pause per playlist. A pause that ends asks for a profiles pull right
     * away, so the roster merges and the edits that waited go out now.
     */
    private fun heldChanged() {
        val heldNow = hostHolds.heldProfileIds()
        // A Child with no stored sign-in stays sealed until it signs in.
        val signedOut = ProfileScope.sealed.filter { id -> store.byId(id)?.let(ProfileHostHold::hasNoSignIn) == true }
        val released = ProfileScope.sealed - heldNow - signedOut.toSet()
        if (released.isNotEmpty()) {
            ProfileScope.sealed = ProfileScope.sealed - released
            if (ProfileScope.sealed.isEmpty()) ProfileScope.sealedRoutes = emptySet()
            released.mapNotNull { store.byId(it)?.playlistId }.toSet().forEach { pid ->
                Log.i(TAG, "[PROFILE-HOST] the held Child on ${pid.take(8)} is lifted; loading again")
                scope.launch { reloads.send(Reload(pid, switchGen.get(), fromSwitch = false)) }
            }
        }
        ProfileScope.held = heldNow
        val now = hostHolds.pausedPlaylists()
        val before = _rosterPaused.value
        if (now == before) return
        _rosterPaused.value = now
        (now - before).forEach { Log.i(TAG, "[PROFILE-HOST] roster sync paused playlist=${it.take(8)}") }
        (before - now).forEach { pid ->
            Log.i(TAG, "[PROFILE-HOST] roster sync resumed playlist=${pid.take(8)}")
            notePickerOpened(pid, "roster-resumed")
        }
    }

    /** [profileId]'s Dispatcharr username (the held sign-in form's prefill), or "". */
    fun profileUsername(profileId: String): String = store.byId(profileId)?.username.orEmpty()

    /** The held profiles' stored sign-ins on [playlistId], only so a save can tell them from the playlist's own. Never sent. */
    fun heldSignIns(playlistId: String): List<ProfileHostHold.SignIn> =
        store.forPlaylist(playlistId).filter { !it.isDefault && hostHolds.isHeld(it.id) }
            .map { ProfileHostHold.SignIn(it.apiKey, it.username, it.password) }

    /**
     * A route change on [playlistId]: BEFORE the caller sends anything, hold
     * every non-default profile's stored sign-in, and leave a held active
     * profile for the default one the way the tombstone path does, minus that
     * path's sign-in (it would go to whichever address is current, and a
     * failed one would leave the held profile active). Synchronous, with no
     * network, no Room, and no lock, so it is safe inside the Drive pull's
     * transaction. True when a profile is held.
     */
    fun holdSignInsForRouteChange(
        playlistId: String,
        oldBase: String?,
        oldLan: String?,
        newBase: String?,
        newLan: String?,
        /** Dispatcharr only: Xtream Codes and M3U sign-ins are part of their URLs (Apple parity). */
        dispatcharr: Boolean = true,
    ): Boolean {
        if (!ProfileHostHold.routeChanged(oldBase, newBase, oldLan, newLan)) return false
        // The playlist's own stored secrets wait for proof too, profiles or not (StoredSecrets).
        if (dispatcharr) {
            val was = hostHolds.isRouteUnproven(playlistId)
            if (hostHolds.markRouteChange(playlistId, ProfileHostHold.routes(oldBase, oldLan), ProfileHostHold.routes(newBase, newLan))) {
                Log.i(TAG, "[PROFILE-HOST] route unproven playlist=${playlistId.take(8)}: no saved password or stored sign-in goes there until it is proven")
            } else if (was) {
                routeProven(playlistId, "back on addresses it used before")
            }
        }
        // Dispatcharr Sync drops its saved copy of the old server's record for
        // every profile here, whether or not any profile is held (item 6).
        routeChangeStarted += playlistId
        routeChange(playlistId, landed = false)
        val ids = ProfileHostHold.toHold(store.forPlaylist(playlistId))
        if (ids.isEmpty() && hostHolds.hold(playlistId) == null) return false
        return placeHold(playlistId, ids, ProfileHostHold.routes(oldBase, oldLan), hostHolds.recorded(playlistId), ProfileHostHold.routes(newBase, newLan))
    }

    /**
     * A sync merge (PlaylistCollapse) moved [moved] profiles from
     * [fromPlaylist] into [toPlaylist]. The two share a server URL, but their
     * local URLs can differ, so the moved sign-ins can land on an address
     * they were never used at. When either route differs they are held like
     * a route change, trusted at the old playlist's routes and checked
     * against the identity recorded for it (every moved non-default profile,
     * keyless ones included). Profiles already held stay held.
     * Synchronous, no network, no lock. True when a profile is held.
     */
    fun holdMergedSignIns(
        fromPlaylist: String,
        toPlaylist: String,
        moved: Collection<String>,
        fromBase: String?,
        fromLan: String?,
        toBase: String?,
        toLan: String?,
    ): Boolean {
        if (!ProfileHostHold.routeChanged(fromBase, toBase, fromLan, toLan)) return false
        val ids = ProfileHostHold.toHold(moved.mapNotNull(store::byId)).filterNot(hostHolds::isHeld).toSet()
        if (ids.isEmpty()) return false
        return placeHold(toPlaylist, ids, ProfileHostHold.routes(fromBase, fromLan), hostHolds.recorded(fromPlaylist), ProfileHostHold.routes(toBase, toLan))
    }

    /** The playlist row, read off the main thread (restoreSync runs before any coroutine). Null on failure. */
    private fun rawRowBlocking(playlistId: String): com.aeriotv.android.core.data.db.entity.PlaylistEntity? =
        runCatching { kotlinx.coroutines.runBlocking(Dispatchers.IO) { playlistDao.rawById(playlistId) } }.getOrNull()

    /** Keep the held Child [profileId] installed with no sign-in, and refuse Dispatcharr requests to [routes]. */
    private fun seal(profileId: String, routes: Set<String>) {
        ProfileScope.sealed = ProfileScope.sealed + profileId
        if (routes.isNotEmpty()) ProfileScope.sealedRoutes = routes
    }

    /** Hold [ids] on [playlistId] before anything is sent, and leave a held active profile for the default one (a Child stays). */
    private fun placeHold(
        playlistId: String,
        ids: Set<String>,
        priorRoutes: Set<String>,
        expected: ProfileHostHold.Identity?,
        currentRoutes: Set<String>,
    ): Boolean {
        val activeId = store.activeProfileId(playlistId)
        val live = ProfileScope.activeOverlay()?.let { it.profileId in ids } == true
        // A Child active here stays active (item 8): sealed before it is held,
        // so its overlay stays installed with no sign-in and nothing is sent.
        val child = activeId?.takeIf { it in ids }?.let(store::byId)
            ?.takeIf { ProfileHostHold.staysActiveWhenHeld(it.effectiveRole()) }
        if (child != null) seal(child.id, currentRoutes)
        // Memory first: from here no row is overlaid with a held sign-in.
        ProfileScope.held = ProfileScope.held + ids
        val rec = hostHolds.hold(playlistId, ids, priorRoutes, expected, currentRoutes) ?: return false
        heldChanged()
        Log.i(TAG, "[PROFILE-HOST] hold playlist=${playlistId.take(8)} profiles=${rec.profileIds.size}")
        if (child != null) {
            if (live) {
                closePlayer.trySend(Unit)
                tokenStore.clear(playlistId)
            }
            Log.i(TAG, "[PROFILE-HOST] the active Child on ${playlistId.take(8)} stays, held; leaving it needs a PIN")
        } else if (activeId != null && hostHolds.isHeld(activeId)) {
            store.setActiveProfileId(playlistId, null)
            if (live) {
                ProfileScope.activate(null)
                tokenStore.clear(playlistId)
                CapabilityCorrections.clear(playlistId)
                closePlayer.trySend(Unit)
                leftLive += playlistId
                scope.launch { runCatching { timeshift.stopAllRetained("profile sign-in held") } }
            }
            refreshActiveRole(playlistId)
            Log.i(TAG, "[PROFILE-HOST] switched playlist=${playlistId.take(8)} to the default profile")
        }
        return true
    }

    /**
     * The route change on [playlistId] has landed, or its save failed and the
     * row is back. [reloadUi]: the caller did not reload the playlist on
     * screen, so a profile the hold left gets a silent channel refresh as the
     * default one. Then the same-server check runs.
     */
    fun routeChangeSettled(playlistId: String, reloadUi: Boolean) {
        if (routeChangeStarted.remove(playlistId)) routeChange(playlistId, landed = true)
        if (leftLive.remove(playlistId) && reloadUi) {
            scope.launch { reloads.send(Reload(playlistId, switchGen.get(), fromSwitch = false)) }
        }
        if (hostHolds.hold(playlistId) != null || hostHolds.isRouteUnproven(playlistId)) scheduleSameServerCheck(playlistId)
    }

    // ---- the playlist's own stored secrets (StoredSecrets) ----

    /** Dispatcharr Sync hook: [playlistId]'s route was proven, so a session may start there. */
    @Volatile var onRouteProven: ((playlistId: String) -> Unit)? = null

    fun isRouteUnproven(playlistId: String): Boolean = hostHolds.isRouteUnproven(playlistId)

    private fun routeProven(playlistId: String, why: String) {
        Log.i(TAG, "[PROFILE-HOST] route proven playlist=${playlistId.take(8)} ($why)")
        // The key held because another account answered at the unproven
        // address is the playlist's again, and so is its prompt's place.
        if (heldForRoute.remove(playlistId)) {
            com.aeriotv.android.core.network.DispatcharrRequestGate.clearPlaylist(playlistId)
            Log.i(TAG, "[PROFILE-HOST] playlist=${playlistId.take(8)}: its key, held for the unproven address, is released")
        }
        runCatching { onRouteProven?.invoke(playlistId) }
    }

    /** Playlists whose own key this manager held for an unproven address (released once it is proven). */
    private val heldForRoute = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * The user signed [playlistId] in with what they typed: an Edit Playlist
     * save with a typed password or key proves every address that save set
     * ([at] null); the rejected-key prompt's sign-in proves only the address
     * it signed in at ([at]).
     */
    fun noteTypedSignIn(playlistId: String, at: String? = null) {
        val route = at?.let { ProfileHostHold.routeKey(it) }?.takeIf { it.isNotEmpty() }
        if (hostHolds.markRouteProven(playlistId, route)) routeProven(playlistId, "signed in with typed credentials")
    }

    /**
     * Another account answered at [playlistId]'s unproven address with the
     * playlist's own key: the key is held, so nothing more goes there with
     * it, the default profile shows the sign-in form, and that account's
     * permissions are not applied. No automatic re-check (it would only send
     * the key there again). True when it is held.
     */
    private suspend fun holdOwnKeyAtUnprovenRoute(playlistId: String, reason: String): Boolean {
        if (!StoredSecrets.holdsPlaylistKey(reason, hostHolds.isRouteUnproven(playlistId))) return false
        return holdOwnKeyForUnprovenRoute(playlistId)
    }

    /** [holdOwnKeyAtUnprovenRoute] without the verdict: the identity repair, refused at an unproven address, holds it too. */
    suspend fun holdOwnKeyForUnprovenRoute(playlistId: String): Boolean {
        val row = playlistDao.rawById(playlistId) ?: return false
        val key = row.apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        heldForRoute += playlistId
        if (com.aeriotv.android.core.network.DispatcharrRequestGate.markKeyRejected(
                playlistId, key, playlistName = row.name,
            )
        ) {
            Log.i(TAG, "[PROFILE-HOST] playlist=${playlistId.take(8)}: another account answers at the new address; its key is held (sign-in form)")
        }
        return true
    }

    /**
     * Every successful users/me with the DEFAULT profile's sign-in reports
     * here, from every path that reads it. With no hold the identity is
     * recorded. With a hold it is only compared with the identity recorded
     * before the change (never recorded), and the check runs again for any
     * address still unproven. [key] must be the playlist's own key; another
     * profile's answer is ignored.
     */
    suspend fun noteDefaultMe(playlistId: String, base: String, key: String, userId: Int, username: String): Boolean {
        val row = playlistDao.rawById(playlistId) ?: return false
        val own = row.apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (own != key.trim()) return false
        val current = ProfileHostHold.routes(row.urlString, row.lanUrlString)
        val identity = ProfileHostHold.Identity(userId, username.trim())
        val wasUnproven = hostHolds.isRouteUnproven(playlistId)
        when (val o = hostHolds.onAnswer(playlistId, ProfileHostHold.routeKey(base), current, ProfileHostHold.Answer.Me(identity))) {
            is HostHoldBook.Outcome.Failed -> {
                Log.i(TAG, "[PROFILE-HOST] same-server check failed reason=${o.reason} playlist=${playlistId.take(8)}")
                return holdOwnKeyAtUnprovenRoute(playlistId, o.reason)
            }
            // Profiles from before tags existed belong to the server this record came from.
            HostHoldBook.Outcome.Recorded ->
                hostHolds.tagUntagged(store.forPlaylist(playlistId).filterNot { it.isDefault }.map { it.id }, identity)
            HostHoldBook.Outcome.Proven -> {
                Log.i(TAG, "[PROFILE-HOST] same-server check passed reason=same-user playlist=${playlistId.take(8)}")
                if (wasUnproven && !hostHolds.isRouteUnproven(playlistId)) routeProven(playlistId, "same server")
                scheduleSameServerCheck(playlistId)
            }
            HostHoldBook.Outcome.Ignored ->
                if (hostHolds.hold(playlistId) != null || hostHolds.isRouteUnproven(playlistId)) scheduleSameServerCheck(playlistId)
        }
        return false
    }

    /**
     * Tag [profileId] with the default identity users/me answers now at
     * [base], where it just signed in: the live answer, never the frozen
     * record. With no answer it stays untagged and never lifts on a check.
     */
    private suspend fun tagLive(playlistId: String, profileId: String, base: String) {
        val key = playlistDao.rawById(playlistId)?.apiKey?.takeIf { it.isNotBlank() }
        val me = key?.let { (client.checkMe(base, it) as? DispatcharrClient.MeCheck.Ok)?.me }
        hostHolds.tag(profileId, me?.let { ProfileHostHold.Identity(it.id, it.username) })
        if (me != null && key != null) runCatching { noteDefaultMe(playlistId, base, key, me.id, me.username) }
    }

    /** [profileId] was verified by a switch: tagged with the record when that is a live answer here. */
    private suspend fun tagVerified(playlistId: String, profileId: String) {
        val row = playlistDao.rawById(playlistId) ?: return
        hostHolds.liveRecord(playlistId, ProfileHostHold.routes(row.urlString, row.lanUrlString))?.let { hostHolds.tag(profileId, it) }
    }

    private fun scheduleSameServerCheck(playlistId: String) {
        synchronized(checks) {
            if (checks[playlistId]?.isActive == true) return
            checks[playlistId] = scope.launch {
                try {
                    sameServerCheck(playlistId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "[PROFILE-HOST] same-server check failed reason=error playlist=${playlistId.take(8)} (${t.javaClass.simpleName})")
                }
            }
        }
    }

    /**
     * users/me with the default profile's sign-in at each address the
     * playlist uses now that no check has proven, through the request gate.
     * Same user id and username as recorded before the change at every one:
     * the hold lifts. Anything else keeps it.
     */
    private suspend fun sameServerCheck(playlistId: String) {
        val rec = hostHolds.hold(playlistId)
        if (rec == null && !hostHolds.isRouteUnproven(playlistId)) return
        val row = playlistDao.rawById(playlistId) ?: return
        val current = ProfileHostHold.routes(row.urlString, row.lanUrlString)
        // The playlist's own route: a failed save put the row back, or a move back onto trusted addresses.
        if (hostHolds.isRouteUnproven(playlistId) && !hostHolds.settleRoutes(playlistId, current)) routeProven(playlistId, "back on addresses it used before")
        val proof = hostHolds.routeProof(playlistId)
        // The snapshot taken before the change; with none it is not the same server.
        if ((rec?.expected() ?: hostHolds.recorded(playlistId)) == null) {
            Log.i(TAG, "[PROFILE-HOST] same-server check failed reason=no-record playlist=${playlistId.take(8)}")
            return
        }
        val unproven = rec?.let { ProfileHostHold.unproven(current, it.trusted) }.orEmpty() + proof?.unproven.orEmpty()
        if (unproven.isEmpty()) {
            // Every address in use is proven: an earlier answer matched, or it is one the snapshot came from (a failed save, or a move back).
            if (rec != null) liftHeld(playlistId, current, "proven")
            return
        }
        val key = row.apiKey?.takeIf { it.isNotBlank() }
        // Primes this playlist's auth header mode and User-Agent for the calls below.
        if (key != null) repository.effectiveBaseUrl(row)
        for (url in listOfNotNull(row.urlString, row.lanUrlString).filter { ProfileHostHold.routeKey(it) in unproven }) {
            val answer = if (key == null) ProfileHostHold.Answer.NoKey else when (val r = client.checkMe(url, key)) {
                is DispatcharrClient.MeCheck.Ok -> ProfileHostHold.Answer.Me(ProfileHostHold.Identity(r.me.id, r.me.username))
                DispatcharrClient.MeCheck.Rejected -> ProfileHostHold.Answer.Rejected
                DispatcharrClient.MeCheck.Unreachable -> ProfileHostHold.Answer.Unreachable
            }
            val wasUnproven = hostHolds.isRouteUnproven(playlistId)
            when (val o = hostHolds.onAnswer(playlistId, ProfileHostHold.routeKey(url), current, answer)) {
                is HostHoldBook.Outcome.Failed -> {
                    Log.i(TAG, "[PROFILE-HOST] same-server check failed reason=${o.reason} playlist=${playlistId.take(8)}")
                    holdOwnKeyAtUnprovenRoute(playlistId, o.reason)
                    return
                }
                HostHoldBook.Outcome.Proven -> {
                    Log.i(TAG, "[PROFILE-HOST] same-server check passed reason=same-user playlist=${playlistId.take(8)}")
                    if (wasUnproven && !hostHolds.isRouteUnproven(playlistId)) routeProven(playlistId, "same server")
                }
                else -> Unit
            }
        }
        if (rec != null) liftHeld(playlistId, current, "same-user")
    }

    /** Lifts the held profiles whose tag is the snapshot; the rest stay held and ask to sign in. */
    private fun liftHeld(playlistId: String, current: Set<String>, reason: String) {
        val ids = hostHolds.liftMatching(playlistId, current)
        heldChanged()
        if (ids.isEmpty()) return
        val kept = hostHolds.hold(playlistId)?.profileIds?.size ?: 0
        Log.i(TAG, "[PROFILE-HOST] lifted playlist=${playlistId.take(8)} profiles=${ids.size} kept=$kept reason=$reason")
    }

    /** [profileId] signed in again, was removed, or was just added: it is no longer held. */
    private fun dropHold(profileId: String, reason: String) {
        val pid = hostHolds.liftProfile(profileId) ?: return
        heldChanged()
        Log.i(TAG, "[PROFILE-HOST] lifted playlist=${pid.take(8)} profiles=1 reason=$reason")
    }

    /**
     * The playlist was deleted: its recorded identity and its hold go. Held
     * profiles a sync merge moved to another playlist stay held until they
     * sign in again.
     */
    fun forgetPlaylistHost(playlistId: String) {
        val moved = hostHolds.hold(playlistId)?.profileIds.orEmpty()
            .filter { id -> store.byId(id)?.playlistId?.let { it != playlistId } == true }.toSet()
        hostHolds.forgetPlaylist(playlistId, moved)
        heldChanged()
    }

    /**
     * The playlist's own sign-in passed in the form a switch to the default
     * profile opened (its key was held as rejected): the switch goes on. Only
     * for the request that form was opened for (its PIN, if any, passed).
     */
    fun continueSwitchAfterOwnSignIn(playlistId: String, profileId: String) {
        val req = _heldSignIn.value
        if (req == null || !req.playlistOwn || req.playlistId != playlistId || req.profileId != profileId) return
        _heldSignIn.value = null
        ProfileSwitchFocus.arm(ProfileSwitchFocus.Target.Guide)
        scope.launch { finishHeldSwitch(playlistId, profileId) }
    }

    /** Cancel on the form: stay on the current profile. */
    fun closeHeldSignIn() {
        if (_heldSignIn.value != null) Log.i(TAG, "[PROFILE-HOST] sign-in form closed")
        _heldSignIn.value = null
    }

    /**
     * The form for a held profile: sign in at the playlist's current address
     * with the typed username and password (the existing login path and
     * throttle), read the account's current key from users/me, replace that
     * profile's key and password, lift its hold only, and finish the switch.
     * Only for the profile whose switch opened the form (its PIN, if any,
     * passed there). Never logs the password or the key.
     */
    suspend fun signInHeldProfile(playlistId: String, profileId: String, username: String, password: String): Result<Unit> = runCatching {
        val req = _heldSignIn.value
        check(req != null && !req.playlistOwn && req.playlistId == playlistId && req.profileId == profileId) { "Choose the profile again." }
        val p = store.byId(profileId)?.takeIf { it.playlistId == playlistId && !it.isDefault } ?: error("Profile not found")
        val row = playlistDao.rawById(playlistId) ?: error("Playlist not found")
        val base = repository.effectiveBaseUrl(row)
        val jwt = client.login(base, username.trim(), password, childSealBypass = true)
        val key = client.fetchCurrentUserApiKey(base, jwt.access, childSealBypass = true)
        // The key the sign-in was just issued is checked as it is (never swapped, past the held-key gate).
        val me = client.fetchMe(base, key, childSealBypass = true, keyCheck = true) ?: throw DispatcharrError.Transport("Could not read the account.")
        // Another account's sign-in never becomes this profile's.
        if (!ProfileHostHold.isOwnUser(p.username, me.username)) throw DispatcharrError.InvalidCredentials("Signed in as another user.")
        store.update(profileId) { it.copy(password = password).applyMe(me, key) }
        ownSignInRepaired(playlistId, profileId)
        DispatcharrLoginGate.reset(DispatcharrLoginGate.slotFor(playlistId, profileId))
        dropHold(profileId, "sign-in")
        // A Child that had no stored sign-in is no longer sealed.
        heldChanged()
        runCatching { tagLive(playlistId, profileId, base) }
        // The form closes now and the switch's scrim takes over; run it on
        // the manager's scope so closing the form never cancels it.
        ProfileSwitchFocus.arm(ProfileSwitchFocus.Target.Guide)
        scope.launch { finishHeldSwitch(playlistId, profileId) }
        Unit
    }

    /**
     * The rejected-key prompt's Sign In when the refused key is a non-default
     * profile's own (Apple parity, 2026-11-12): sign in as THAT profile's own
     * user only (the held-profile form's rule), at the playlist's address,
     * store its new key and password, and lift only its own hold. The
     * playlist's own sign-in is never touched. Requests and streams still
     * built with the refused key go out with the new one. Never logs the
     * password or the key.
     */
    suspend fun signInRejectedProfile(playlistId: String, profileId: String, username: String, password: String): Result<Unit> = runCatching {
        val p = store.byId(profileId)?.takeIf { it.playlistId == playlistId && !it.isDefault } ?: error("Profile not found")
        // A held profile signs in through its own form (the address changed).
        if (hostHolds.isHeld(profileId)) throw ProfileSignInHeld()
        val row = playlistDao.rawById(playlistId) ?: error("Playlist not found")
        val base = repository.effectiveBaseUrl(row)
        val jwt = client.login(base, username.trim(), password)
        val key = client.fetchCurrentUserApiKey(base, jwt.access)
        // The key the sign-in was just issued is checked as it is (never swapped, past the held-key gate).
        val me = client.fetchMe(base, key, keyCheck = true) ?: throw DispatcharrError.Transport("Could not read the account.")
        if (!ProfileHostHold.isOwnUser(p.username, me.username)) throw DispatcharrError.InvalidCredentials("Signed in as another user.")
        val oldKey = p.apiKey?.trim()?.takeIf { it.isNotEmpty() }
        store.update(profileId) { it.copy(password = password).applyMe(me, key) }
        val updated = store.byId(profileId)
        updated?.let { followServerRole(it.id, it.userLevel) }
        ownSignInRepaired(playlistId, profileId)
        com.aeriotv.android.core.network.DispatcharrRequestGate.clearKey(key)
        if (oldKey != null && oldKey != key.trim()) com.aeriotv.android.core.network.DispatcharrRequestGate.noteKeyReplaced(oldKey, key)
        DispatcharrLoginGate.reset(DispatcharrLoginGate.slotFor(playlistId, profileId))
        // Still the active one: it goes on with its new key.
        if (ProfileScope.current() == profileId) store.byId(profileId)?.let { ProfileScope.activate(it.toOverlay()) }
        refreshActiveRole(playlistId)
        Log.i(TAG, "[AUTH] profile ${profileId.take(8)} on ${playlistId.take(8)} signed in from the rejected-key prompt; its own hold lifted")
        Unit
    }

    private suspend fun finishHeldSwitch(playlistId: String, profileId: String) {
        val r = switchTo(playlistId, profileId, bypassPin = true)
        if (r.isSuccess && _launchPicker.value?.playlistId == playlistId) {
            switchTickets.remove(profileId)
            _launchPicker.value = null
            releaseLaunchHoldIfClear()
        }
        r.exceptionOrNull()?.let { Log.w(TAG, "[PROFILE-HOST] switch after sign-in failed (${it.javaClass.simpleName})") }
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
     * The playlist's saved password for the default profile's sign-in at
     * [base], or null where it may not go (StoredSecrets: an address not
     * proven the same server). Its own key is still tried there.
     */
    private fun ownPasswordFor(playlistId: String, base: String, row: PlaylistEntity): String? {
        val p = row.password?.takeIf { it.isNotBlank() } ?: return null
        return p.takeIf { StoredSecrets.check(playlistId, base, StoredSecrets.Path.SilentRelogin) }
    }

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
                    val minted = client.fetchCurrentUserApiKey(base, pair.access)
                    // One request checks the key before it is stored or sent
                    // (2026-11-12, Apple 44c544c): a refused one ends the
                    // re-login with no key and parks this profile's slot.
                    val proof = client.probeApiKeyOnce(base, minted)
                    com.aeriotv.android.core.network.reloginOutcome(minted, proof).also {
                        if (proof is com.aeriotv.android.core.network.DispatcharrClient.KeyProbe.Accepted) {
                            com.aeriotv.android.core.network.DispatcharrRequestGate.clearKey(it)
                            DispatcharrLoginGate.noteKeyVerified("$playlistId:$profileId", it)
                        }
                    }
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

    /**
     * [profileId] just signed in as itself (its key read back from users/me):
     * a rejection held for its own earlier key is released. Holds are per
     * identity; repairing the playlist's own sign-in never does this.
     */
    private fun ownSignInRepaired(playlistId: String, profileId: String) {
        com.aeriotv.android.core.network.DispatcharrRequestGate.clearProfile(playlistId, profileId)
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
    private suspend fun applyMeToDefaultRow(playlistId: String, profileId: String, me: MeResponse, base: String, key: String) {
        // The default profile's users/me: recorded, or the same-server check while a hold is in place.
        runCatching { noteDefaultMe(playlistId, base, key, me.id, me.username) }
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
        playlistDao.rawById(playlistId)?.let { row ->
            val level = if (row.dispatcharrIsSuperuser || row.dispatcharrIsStaff) 10 else row.dispatcharrUserLevel
            followServerRole(profileId, level)
        }
    }

    /**
     * Owner rule 2026-10-29: the AerioTV role follows the Dispatcharr access.
     * Called wherever /me/ refreshes a profile's level (launch, Settings, and
     * switch paths). The Child PIN rules are computed live from the roles, so
     * a changed non-Child set is caught by the existing PIN gates at next use.
     * The role change rides the normal profiles-key role sync.
     */
    private fun followServerRole(profileId: String, newLevel: Int) {
        val p = store.byId(profileId) ?: return
        val next = RoleRules.serverRoleChange(p.role, newLevel)
        if (next == null && p.userLevel == newLevel) return
        store.update(profileId) { it.copy(userLevel = newLevel, role = next ?: it.role) }
        if (next != null) {
            Log.i(TAG, "[PROFILES] server role changed for ${p.username}: ${p.role?.label ?: "none"} -> ${next.label}")
            mutated(profileId, "role")
            refreshActiveRole(p.playlistId)
        }
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
