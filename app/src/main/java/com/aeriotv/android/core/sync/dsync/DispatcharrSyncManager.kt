package com.aeriotv.android.core.sync.dsync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.core.data.ChannelCollection
import com.aeriotv.android.core.data.db.dao.ChannelSnapshotDao
import com.aeriotv.android.core.data.db.dao.FavoriteChannelDao
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.ReminderDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import com.aeriotv.android.core.data.db.entity.FavoriteChannelEntity
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.db.entity.ReminderEntity
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity
import com.aeriotv.android.core.data.db.entity.isDispatcharrDirectConnect
import com.aeriotv.android.core.data.db.entity.reminderKey
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.DispatcharrUserSummary
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.preferences.HiddenTitlesStore
import com.aeriotv.android.core.preferences.WatchlistStore
import com.aeriotv.android.core.profiles.PinRules
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.core.profiles.effectiveRole
import com.aeriotv.android.core.profiles.ProfilePinStore
import com.aeriotv.android.core.profiles.ProfileScope
import com.aeriotv.android.core.profiles.ProfileStore
import com.aeriotv.android.feature.reminders.ReminderBroadcastReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Dispatcharr Sync (docs/DispatcharrSyncSchema.md, shared with the Apple
 * app): the third sync provider. Each category lives in the active profile's
 * own Dispatcharr App Data (`users/me/app-data/<key>/`, that profile's API
 * key), so every profile syncs as its own Dispatcharr user.
 *
 * One session per (Dispatcharr Direct Connect playlist, active profile). A
 * session pulls first; a key is never written before it was read in that
 * session. Cadence and error handling follow section 7 exactly.
 */
@Singleton
class DispatcharrSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val playlistDao: PlaylistDao,
    private val repository: Provider<com.aeriotv.android.core.data.repository.PlaylistRepository>,
    private val client: DispatcharrClient,
    private val favoriteDao: FavoriteChannelDao,
    private val watchProgressDao: WatchProgressDao,
    private val reminderDao: ReminderDao,
    private val channelSnapshotDao: ChannelSnapshotDao,
    private val watchlistStore: WatchlistStore,
    private val hiddenTitlesStore: HiddenTitlesStore,
    private val profileStore: ProfileStore,
    private val pinStore: ProfilePinStore,
    private val profileManager: Provider<ProfileManager>,
    private val vodIds: VodIdResolver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = context.getSharedPreferences("aerio_dsync", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val isTv: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.packageManager.hasSystemFeature("android.software.leanback_only")

    /** What Settings shows on the Dispatcharr Sync row. */
    data class Ui(
        val active: Boolean = false,
        val lastSyncAt: Long = 0L,
        /** "Sync data too large", "Sync storage on the server is full", "Sign in again to sync", or null. */
        val message: String? = null,
        val running: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    /** Random install id (section 3), never tied to hardware ids. */
    val deviceId: String by lazy {
        state.getString(K_DEVICE, null) ?: UUID.randomUUID().toString().uppercase().also {
            state.edit().putString(K_DEVICE, it).apply()
        }
    }

    // ---- session ----

    private data class Target(val playlistId: String, val profileToken: String)
    private val HOLD = Target("\u0000hold", "")

    /**
     * One sync session, bound to ONE Dispatcharr user: the profile token and
     * the API key captured at its start. Nothing it queued may ever go out
     * under another profile (2026-10-18 cross-profile write on switch).
     */
    private inner class Session(val playlistId: String, val profileKey: String, val token: String) {
        val mutex = Mutex()
        @Volatile var bound: Conn? = null
        /** No key is written before this session's first complete pull. */
        @Volatile var firstPullDone = false
        /** The last list call answered (false while offline). */
        @Volatile var listOk = false
        /** Keys whose last send failed, or that were unsent when the session began. */
        val resend = java.util.Collections.synchronizedSet(HashSet<DsyncKey>())
        @Volatile var reconnectJob: Job? = null
        val read = HashSet<DsyncKey>()
        val blocked = HashSet<DsyncKey>()
        val tooLarge = HashSet<DsyncKey>()
        var serverFull = false
        var paused = false
        val serverBytes = HashMap<String, Long>()
        /** Bucket wire name -> list `bytes` when that bucket was last fetched. */
        val fetchedBytes = HashMap<String, Long>()
        /** Categories present inside their v2 key on the server. */
        val present = java.util.Collections.synchronizedSet(HashSet<DsyncKey>())
        val pending = HashMap<DsyncKey, Job>()
        /** Keys already checked by [healIfLocalDiffers] this session. */
        val healed = java.util.Collections.synchronizedSet(HashSet<DsyncKey>())
        val retries = HashMap<DsyncKey, Int>()
        val suppressUntil = HashMap<DsyncKey, Long>()
        var users: List<DispatcharrUserSummary>? = null
        var lastPullAt = 0L
    }

    @Volatile private var session: Session? = null
    @Volatile private var started = false
    @Volatile private var foreground = true
    @Volatile private var backgroundSince = 0L

    /** Called once from Application.onCreate (main thread). */
    fun start() {
        if (started) return
        started = true
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    foreground = true
                    val s = session ?: return
                    if (System.currentTimeMillis() - s.lastPullAt >= FOREGROUND_PULL_MS) {
                        scope.launch { pullAll(s, "foreground") }
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    foreground = false
                    backgroundSince = System.currentTimeMillis()
                    session?.let { s -> scope.launch { flush(s) } }
                }
            })
        }
        profileManager.get().onProfilesMutated = { pid, user, what, at -> onProfilesMutated(pid, user, what, at) }
        registerNetworkCallback()
        watchBetweenSessions()
        scope.launch {
            combine(
                prefs.syncProvider,
                playlistDao.observeActive().map { rows -> rows.firstOrNull()?.takeIf { it.isDispatcharrDirectConnect() }?.id },
                ProfileScope.token,
                // The picker reason only: an in-app PIN pad (Set Admin PIN, Add Profile)
                // used to end the session here, and edits made under the pad had no session.
                kotlinx.coroutines.flow.combine(profileManager.get().switching, com.aeriotv.android.core.app.LaunchWorkHold.pickerHold) { sw, held -> sw || held },
            ) { provider, pid, token, switching ->
                // A profile switch (or the TV launch picker) is in flight: hold until its sign-in and
                // permissions are in place (the scrim drops), then start.
                if (switching) HOLD else if (provider == PROVIDER && pid != null) Target(pid, token) else null
            }
                .distinctUntilChanged()
                .collectLatest { t ->
                    session?.let { old ->
                        // Send what the old session queued while its profile is
                        // still the active one; the gate drops anything once
                        // the token moved (the dirty flag keeps it for later).
                        Log.i(TAG, "[DSYNC] session end user=${old.profileKey.take(8)} (${if (t === HOLD) "switch" else "change"})")
                        runCatching { flush(old) }
                        synchronized(old.pending) { old.pending.values.forEach { it.cancel() }; old.pending.clear() }
                        lastEnded = old
                    }
                    session = null
                    if (t === HOLD) return@collectLatest
                    if (t == null) {
                        _ui.value = Ui()
                        return@collectLatest
                    }
                    val prev = lastTarget
                    lastTarget = t
                    val why = if (prev != null && prev.playlistId == t.playlistId && prev.profileToken != t.profileToken) "profile" else "launch"
                    com.aeriotv.android.core.app.LaunchWorkHold.awaitStage(com.aeriotv.android.core.app.LaunchWorkHold.STAGE_NETWORK, com.aeriotv.android.core.app.LaunchWorkHold.STAGE_NETWORK_NAME, "Dispatcharr Sync session", com.aeriotv.android.core.app.LaunchWorkHold.IDLE_NETWORK_MS)
                    runSession(t, why)
                }
        }
    }

    @Volatile private var lastTarget: Target? = null

    /** The last session that ended; edits made before the next one starts are marked on its keys. */
    @Volatile private var lastEnded: Session? = null

    /**
     * Local changes with no session (between sessions, offline start, a hold)
     * used to be dropped: the per-session collectors skip their first value.
     * Mark them dirty on the last session's persisted keys so the next session
     * for the same playlist and profile sends them. A changed profile token
     * (switch re-keys every flow) is never marked.
     */
    private fun watchBetweenSessions() {
        DsyncKey.entries.filter { it != DsyncKey.Profiles }.forEach { key ->
            scope.launch {
                sourceFor(key, null).distinctUntilChanged().drop(1).collect {
                    if (session != null) return@collect
                    val old = lastEnded ?: return@collect
                    if (profileManager.get().switching.value) return@collect
                    if (ProfileScope.token.value != old.token) return@collect
                    if (!isDirty(old, key)) Log.i(TAG, "[DSYNC] ${key.suffix} dirty; no session (kept for the next one)")
                    setDirty(old, key, true)
                }
            }
        }
    }

    private fun profilesPendingKey(playlistId: String) = "pdirty|$playlistId"
    private fun profilesPushedKey(playlistId: String) = "ppushed|$playlistId"
    private fun newestRowStamp(playlistId: String): Long {
        val prefix = "prow|$playlistId|"
        return state.all.entries.filter { it.key.startsWith(prefix) }.maxOfOrNull { (it.value as? Long) ?: 0L } ?: 0L
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    val s = session ?: return
                    reconnect(s)
                }
            })
        }
    }

    /**
     * The network came back: the interface is often up before routes and DNS
     * are, so retry the pull at 2 s, 5 s, 15 s, then every 60 s until the list
     * call answers (or the session ends).
     */
    private fun reconnect(s: Session) {
        s.reconnectJob?.cancel()
        s.reconnectJob = scope.launch {
            var i = 0
            while (isActive && session === s) {
                delay(RECONNECT_MS[minOf(i, RECONNECT_MS.size - 1)])
                pullAll(s, "reconnect")
                if (s.listOk) return@launch
                i++
                Log.i(TAG, "[DSYNC] reconnect pull failed; retry ${i + 1}")
            }
        }
    }

    /** ProfileManager mutation hook: stamp the row and re-send `profiles` from an admin session. */
    /** Roster adds by row key, wall-clock ms: the push guard's 60 s window. */
    private val rosterAdds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun onProfilesMutated(playlistId: String, username: String, what: String, stampAt: Long = 0L) {
        // Called from UI actions on the main thread: all disk work happens on IO.
        // A roster add keeps the row's own stamp (stampAt); every real edit stamps now.
        val now = if (stampAt > 0) stampAt else System.currentTimeMillis() / 1000
        if (what == "roster-add") rosterAdds[rowKey(playlistId, username)] = System.currentTimeMillis()
        // Stamp synchronously (apply() writes to disk in the background): the
        // caller drops the local profile right after this returns, and a pull
        // racing that removal must already see the removed stamp.
        val e = state.edit().putLong(rowKey(playlistId, username), now)
        if (what == "remove") e.putLong(removedKey(playlistId, username), now) else e.remove(removedKey(playlistId, username))
        // Playlist-level pending mark: survives no session, a non-admin
        // session, and process death; cleared only by a successful PUT.
        e.putBoolean(profilesPendingKey(playlistId), true)
        e.commit()
        if (what == "remove") Log.i(TAG, "[DSYNC] profiles: stamped removed ${removedKey(playlistId, username)}=$now read=${state.getLong(removedKey(playlistId, username), 0L)}")
        scope.launch {
            val s = session?.takeIf { it.playlistId == playlistId }
            Log.i(TAG, "[DSYNC] profiles dirty ($what ${username})${if (s == null) "; no session" else ""}")
            if (s == null) return@launch
            if (!isAdmin(s)) return@launch
            setDirty(s, DsyncKey.Profiles, true)
            schedule(s, DsyncKey.Profiles, DEBOUNCE_MS)
        }
    }

    /** The local removed stamps (`pgone|`), a cache of this device's
     *  removals until the payload `removed` list carries them (schema 4.12). */
    private fun localTombstones(playlistId: String): List<ProfileTombstone> {
        val prefix = "pgone|$playlistId|"
        return state.all.entries.filter { it.key.startsWith(prefix) }
            .mapNotNull { e -> (e.value as? Long)?.takeIf { it > 0 }?.let { ProfileTombstone(e.key.removePrefix(prefix), it) } }
    }

    /** Server `removed` plus local stamps, newest per username, minus expired ones. */
    private fun tombstonesFor(playlistId: String, data: JsonObject?): List<ProfileTombstone> =
        ProfileTombstones.expire(
            ProfileTombstones.union(DsyncPayloads.decodeProfileTombstones(data), localTombstones(playlistId)),
            System.currentTimeMillis() / 1000,
        )

    /** Local profiles a tombstone beats (removedAt >= the local row stamp). */
    private fun tombstonedLocal(playlistId: String, tombs: List<ProfileTombstone>): List<String> =
        profileStore.forPlaylist(playlistId).filter { !it.isDefault }
            .filter { ProfileTombstones.kills(tombs, it.username, state.getLong(rowKey(playlistId, it.username), 0L)) }
            .map { it.username }

    /** Delete [users] locally (same cleanup as a user remove, no new tombstone).
     *  Launched off the sync mutex: a removal of the active profile switches profiles. */
    private fun removeSynced(playlistId: String, users: List<String>, tombs: List<ProfileTombstone>) {
        if (users.isEmpty()) return
        scope.launch {
            users.forEach { u ->
                val at = ProfileTombstones.find(tombs, u)?.removedAt ?: 0L
                if (profileManager.get().removeFromSync(playlistId, u)) {
                    state.edit().remove(rowKey(playlistId, u)).apply()
                    Log.i(TAG, "[DSYNC] profiles: removed local $u (tombstone removedAt=$at)")
                }
            }
        }
    }

    /** After a 200 (or an identical server copy): a local removed stamp clears
     *  once the payload's `removed` list carries it, a row edited after it
     *  revived the profile, or it expired. */
    private fun clearAckedRemovals(playlistId: String, data: JsonObject) {
        val sent = DsyncPayloads.decodeProfileTombstones(data)
        val rows = DsyncPayloads.decodeProfiles(data).orEmpty()
        val now = System.currentTimeMillis() / 1000
        val prefix = "pgone|$playlistId|"
        val e = state.edit()
        var any = false
        localTombstones(playlistId).forEach { t ->
            val why = when {
                (ProfileTombstones.find(sent, t.username)?.removedAt ?: 0L) >= t.removedAt -> "written to the server payload"
                rows.any { it.username.equals(t.username, ignoreCase = true) && it.updatedAt > t.removedAt } -> "superseded by a newer row"
                now - t.removedAt > ProfileTombstones.WINDOW_S -> "expired"
                else -> return@forEach
            }
            e.remove(prefix + t.username)
            any = true
            Log.i(TAG, "[DSYNC] profiles: removal of ${t.username} $why; local stamp cleared")
        }
        if (any) e.apply()
    }

    private fun rowKey(playlistId: String, username: String) = "prow|$playlistId|${username.lowercase()}"
    private fun removedKey(playlistId: String, username: String) = "pgone|$playlistId|${username.lowercase()}"

    private suspend fun runSession(t: Target, trigger: String) {
        val profileKey = currentProfileId(t.playlistId) ?: t.profileToken.ifEmpty { "default" }
        val s = Session(t.playlistId, profileKey, t.profileToken)
        session = s
        s.bound = conn(s)
        _ui.value = Ui(active = true, lastSyncAt = state.getLong(k("last", s), 0L))
        if (state.getBoolean(profilesPendingKey(s.playlistId), false)) {
            Log.i(TAG, "[DSYNC] profiles: pending local change from between sessions, re-sending")
            setDirty(s, DsyncKey.Profiles, true)
        } else if (newestRowStamp(s.playlistId) > state.getLong(profilesPushedKey(s.playlistId), 0L)) {
            Log.i(TAG, "[DSYNC] profiles: local roster newer than last push, re-sending")
            setDirty(s, DsyncKey.Profiles, true)
        }
        var who = profileStore.byId(profileKey)
        if (who != null && who.userId <= 0) {
            // Not read yet (a default profile never switched to): ask /me/ once.
            conn(s)?.let { c ->
                runCatching { client.fetchMe(c.base, c.apiKey) }.getOrNull()
                    ?.takeIf { it.username.equals(who?.username, ignoreCase = true) }
                    ?.let { me -> profileStore.update(profileKey) { it.copy(userId = me.id) }; who = profileStore.byId(profileKey) }
            }
        }
        Log.i(
            TAG,
            "[DSYNC] session start playlist=${t.playlistId.take(8)} user=${who?.username ?: "?"} " +
                "userId=${who?.userId?.takeIf { it > 0 } ?: "?"}",
        )
        pullAll(s, trigger)
        kotlinx.coroutines.coroutineScope {
            DsyncKey.entries.forEach { key ->
                launch {
                    sourceFor(key, s.playlistId, s.token).distinctUntilChanged().drop(1).collect { onLocalChange(s, key) }
                }
            }
            launch {
                while (isActive) {
                    delay(PERIODIC_PULL_MS)
                    if (foreground) pullAll(s, "periodic")
                }
            }
        }
    }

    /**
     * Onboarding Turn On: wait (bounded) until the session the provider change
     * starts has finished its first complete pull. False on timeout.
     */
    suspend fun awaitFirstPull(timeoutMs: Long = 20_000L): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            while (session?.firstPullDone != true) delay(200)
            true
        } ?: false

    /** Settings > Sync Now: pull, then flush anything waiting. */
    fun syncNow() {
        val s = session ?: return
        scope.launch {
            s.paused = false
            pullAll(s, "manual")
            flush(s)
        }
    }

    private fun currentProfileId(playlistId: String): String? =
        profileStore.activeProfileId(playlistId)?.takeIf { profileStore.byId(it) != null }
            ?: profileStore.forPlaylist(playlistId).firstOrNull { it.isDefault }?.id

    // ---- per-key enablement ----

    private suspend fun enabled(key: DsyncKey, s: Session): Boolean {
        // Per-item choices (Sync Categories): data keys follow their item's
        // on/off; `settings` is gated per name by scope (lanes), so it is
        // always considered and an all-Off device simply writes nothing.
        if (key == DsyncKey.Settings) return true
        val item = com.aeriotv.android.core.sync.SyncItemCatalog.itemForDsync(key.suffix)
        val on = item == null || prefs.syncScopesOnce()[item.id] != com.aeriotv.android.core.sync.SyncScope.Off
        if (!on) return false
        return when (key) {
            DsyncKey.Profiles -> isAdmin(s)
            else -> true
        }
    }

    /**
     * Writes and applies the `profiles` key: the local Admin (the base
     * profile by default), server admin or not. It is stored in that user's
     * own App Data, so no server admin rights are needed.
     */
    private suspend fun isAdmin(s: Session): Boolean = runCatching {
        com.aeriotv.android.core.profiles.RoleRules.canWriteProfilesKey(profileManager.get().activeRole(s.playlistId))
    }.getOrDefault(false)

    private suspend fun isServerAdmin(s: Session): Boolean = runCatching {
        profileManager.get().activeIsAdmin(s.playlistId) && profileManager.get().baseIsServerAdmin(s.playlistId)
    }.getOrDefault(false)

    // ---- connection ----

    private data class Conn(val base: String, val apiKey: String, val row: PlaylistEntity)

    /** The session's own credential, captured while its profile was active. */
    private suspend fun conn(s: Session): Conn? {
        s.bound?.let { return it }
        if (ProfileScope.token.value != s.token) return null
        return freshConn(s)?.also { s.bound = it }
    }

    private suspend fun freshConn(s: Session): Conn? {
        if (ProfileScope.token.value != s.token) return null
        val row = playlistDao.byId(s.playlistId) ?: return null
        val key = row.apiKey?.takeIf { it.isNotBlank() } ?: return null
        return Conn(repository.get().effectiveBaseUrl(row), key, row)
    }

    // ---- persisted per (playlist, profile, key) state ----

    private fun k(name: String, s: Session, key: DsyncKey? = null) =
        "$name|${s.playlistId}|${s.profileKey}" + (key?.let { "|${it.suffix}" } ?: "")

    private fun rawOf(s: Session, key: DsyncKey): JsonObject? =
        state.getString(k("raw", s, key), null)?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun lastDataOf(s: Session, key: DsyncKey): String? = state.getString(k("data", s, key), null)

    private fun saveAfter(s: Session, key: DsyncKey, raw: JsonObject, at: Long, dev: String, data: JsonObject) {
        state.edit()
            .putString(k("raw", s, key), raw.toString())
            .putLong(k("at", s, key), at)
            .putString(k("dev", s, key), dev)
            .putString(k("data", s, key), data.toString())
            .apply()
    }

    /** Persistent (survives process death). [dirtyAt] = the last local change, Unix seconds. */
    private fun setDirty(s: Session, key: DsyncKey, dirty: Boolean) {
        val e = state.edit().putBoolean(k("dirty", s, key), dirty)
        if (dirty) e.putLong(k("dirtyAt", s, key), System.currentTimeMillis() / 1000)
        e.commit()
    }

    private fun dirtyAt(s: Session, key: DsyncKey) = state.getLong(k("dirtyAt", s, key), 0L)

    private fun isDirty(s: Session, key: DsyncKey) = state.getBoolean(k("dirty", s, key), false)

    private fun stampLast(s: Session) {
        val now = System.currentTimeMillis()
        state.edit().putLong(k("last", s), now).apply()
        _ui.value = _ui.value.copy(lastSyncAt = now)
    }

    private fun refreshMessage(s: Session) {
        val msg = when {
            s.paused -> "Sign in again to sync"
            s.tooLarge.isNotEmpty() -> "Sync data too large"
            s.serverFull -> "Sync storage on the server is full"
            else -> null
        }
        if (session === s) _ui.value = _ui.value.copy(message = msg)
    }

    // ---- pull (section 7.1, 7.2) ----

    private fun bucketRaw(s: Session, b: DsyncBucket): JsonObject? =
        state.getString(k("braw", s) + "|" + b.name, null)?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun saveBucketRaw(s: Session, b: DsyncBucket, raw: JsonObject) {
        state.edit().putString(k("braw", s) + "|" + b.name, raw.toString()).apply()
    }

    private suspend fun pullAll(s: Session, reason: String) = s.mutex.withLock {
        if (session !== s) return@withLock
        val c = conn(s) ?: return@withLock
        s.listOk = false
        _ui.value = _ui.value.copy(running = true)
        try {
            val list = runCatching { client.appDataList(c.base, c.apiKey) }.getOrNull()
            if (list == null || list.status >= 500) {
                Log.w(TAG, "[DSYNC] list failed status=${list?.status ?: "network"} ($reason)")
                return@withLock
            }
            if (list.status == 401 || list.status == 403) {
                s.paused = true
                refreshMessage(s)
                Log.w(TAG, "[DSYNC] list status=${list.status}; paused for this profile")
                return@withLock
            }
            if (!list.ok) {
                Log.w(TAG, "[DSYNC] list status=${list.status} code=${list.code}")
                return@withLock
            }
            s.paused = false
            s.listOk = true
            s.serverBytes.clear()
            runCatching {
                val root = json.parseToJsonElement(list.body.orEmpty()) as? JsonObject
                (root?.get("apps") as? JsonArray)?.forEach { el ->
                    val o = el as? JsonObject ?: return@forEach
                    val name = o["key"].asStr() ?: return@forEach
                    s.serverBytes[name] = o["bytes"].asLong() ?: 0L
                }
            }
            if (!migrateV1(s, c)) return@withLock
            for (b in DsyncBucket.entries) {
                if (session !== s) return@withLock
                val keys = b.keys.filter { enabled(it, s) }
                if (keys.isNotEmpty()) pullBucket(s, c, b, keys)
            }
            s.lastPullAt = System.currentTimeMillis()
            s.firstPullDone = true
            stampLast(s)
            Log.i(TAG, "[DSYNC] pull done ($reason) read=${s.read.size} server=${s.serverBytes.size}")
            // A category absent on the server, or changed here before this
            // session could write it, goes up now.
            for (key in s.read.toList()) {
                val dirty = isDirty(s, key)
                if (dirty && (reason == "reconnect" || reason == "launch" || reason == "profile")) s.resend += key
                if (dirty || key !in s.present) schedule(s, key, 0L)
            }
        } finally {
            _ui.value = _ui.value.copy(running = false)
            refreshMessage(s)
        }
    }

    private sealed interface Fetched {
        data object Absent : Fetched
        data object Failed : Fetched
        data object Malformed : Fetched
        data class TooNew(val version: Int) : Fetched
        data class Got(val bucket: DsyncV2.Bucket, val bytes: Int) : Fetched
    }

    private suspend fun fetchBucket(c: Conn, b: DsyncBucket): Fetched {
        val r = runCatching { client.appDataGet(c.base, c.apiKey, b.wire) }.getOrNull()
        if (r == null || r.status >= 500) {
            Log.w(TAG, "[DSYNC] get ${b.wire} status=${r?.status ?: "network"}")
            return Fetched.Failed
        }
        if (r.status == 404) return Fetched.Absent
        if (!r.ok) {
            Log.w(TAG, "[DSYNC] get ${b.wire} status=${r.status} code=${r.code}")
            return Fetched.Failed
        }
        val raw = runCatching { json.parseToJsonElement(r.body.orEmpty()) as? JsonObject }.getOrNull() ?: return Fetched.Malformed
        return when (val d = DsyncV2.decode(raw)) {
            is DsyncV2.Decode.Ok -> Fetched.Got(d.bucket, r.body?.toByteArray(Charsets.UTF_8)?.size ?: 0)
            is DsyncV2.Decode.TooNew -> Fetched.TooNew(d.version)
            DsyncV2.Decode.Malformed -> Fetched.Malformed
        }
    }

    private suspend fun pullBucket(s: Session, c: Conn, b: DsyncBucket, keys: List<DsyncKey>) {
        if (b.wire !in s.serverBytes) {
            keys.forEach { s.present -= it; s.read += it }
            return
        }
        // No HEAD / ETag on App Data, so the list's `bytes` is the only
        // change signal. A pull under BUCKET_REFETCH_MIN_MS after the last
        // one that sees the same size skips the GET (review 2026-10-27);
        // anything older, or any size change, fetches as before.
        val listed = s.serverBytes[b.wire]
        if (shouldSkipBucketGet(s.lastPullAt, System.currentTimeMillis(), s.fetchedBytes[b.wire], listed)) {
            Log.d(TAG, "[DSYNC] skip get ${b.wire}: unchanged ${listed}B, last pull under ${BUCKET_REFETCH_MIN_MS / 1000}s ago")
            return
        }
        when (val f = fetchBucket(c, b)) {
            Fetched.Absent -> keys.forEach { s.present -= it; s.read += it }
            Fetched.Failed -> Unit
            Fetched.Malformed -> Log.w(TAG, "[DSYNC] malformed ${b.wire}")
            is Fetched.TooNew -> {
                s.blocked += keys
                Log.w(TAG, "[DSYNC] skip ${b.wire} v${f.version} > v${DsyncV2.SCHEMA}")
            }
            is Fetched.Got -> {
                listed?.let { s.fetchedBytes[b.wire] = it }
                saveBucketRaw(s, b, f.bucket.raw)
                keys.forEach { key ->
                    when (val d = DsyncV2.category(f.bucket, key)) {
                        null -> { s.present -= key; s.read += key }
                        is DsyncDecode.Ok -> {
                            s.present += key
                            processCategory(s, key, d.envelope, f.bytes)
                        }
                        else -> Log.w(TAG, "[DSYNC] malformed ${b.wire} category ${key.suffix}")
                    }
                }
            }
        }
    }

    /**
     * Migration to schema v3 (one key): when any other `app.molinete.aerio.*`
     * key exists (the 13 v1 keys or the v2 favorites/progress keys) or the
     * main key still holds a v2 value, read them all, merge per category by
     * updatedAt into the single key, PUT it, then DELETE every other
     * `app.molinete.aerio.*` key. False = the server could not be reached.
     */
    private suspend fun migrateV1(s: Session, c: Conn): Boolean {
        val main = DsyncBucket.Main.wire
        val others = s.serverBytes.keys.filter { it.startsWith("$main.") }
        var mainRaw: JsonObject? = null
        var mainIsOld = false
        if (main in s.serverBytes && !state.getBoolean(k("v3seen", s), false)) {
            val r = runCatching { client.appDataGet(c.base, c.apiKey, main) }.getOrNull()
            if (r == null || r.status >= 500) return false
            val got = if (r.ok) runCatching { json.parseToJsonElement(r.body.orEmpty()) as? JsonObject }.getOrNull() else null
            mainRaw = got
            mainIsOld = got != null && DsyncV2.decode(got) !is DsyncV2.Decode.Ok
            if (!mainIsOld) state.edit().putBoolean(k("v3seen", s), true).apply()
        }
        if (others.isEmpty() && !mainIsOld) return true
        if (mainRaw == null && main in s.serverBytes) {
            val r = runCatching { client.appDataGet(c.base, c.apiKey, main) }.getOrNull()
            if (r == null || r.status >= 500) return false
            mainRaw = if (r.ok) runCatching { json.parseToJsonElement(r.body.orEmpty()) as? JsonObject }.getOrNull() else null
        }
        val v1 = LinkedHashMap<DsyncKey, DsyncEnvelope>()
        val v2 = ArrayList<DsyncV2.Bucket>()
        var v3: JsonObject? = null
        val mainNow = mainRaw
        mainNow?.let { raw ->
            when (val d = DsyncV2.decode(raw, min = DsyncV2.MIN_SUBOBJECT_SCHEMA)) {
                is DsyncV2.Decode.Ok -> if (d.bucket.schemaVersion >= DsyncV2.SCHEMA) v3 = raw else v2 += d.bucket
                else -> Unit
            }
        }
        for (name in others) {
            val r = runCatching { client.appDataGet(c.base, c.apiKey, name) }.getOrNull()
            if (r == null || r.status >= 500) return false
            if (!r.ok) continue
            val raw = runCatching { json.parseToJsonElement(r.body.orEmpty()) as? JsonObject }.getOrNull() ?: continue
            val k1 = DsyncKey.fromWire(name)
            val d1 = if (k1 != null) DsyncEnvelopeCodec.decode(raw, 1) else null
            if (k1 != null && d1 is DsyncDecode.Ok) { v1[k1] = d1.envelope; continue }
            (DsyncV2.decode(raw, min = DsyncV2.MIN_SUBOBJECT_SCHEMA) as? DsyncV2.Decode.Ok)?.let { v2 += it.bucket }
        }
        val now = System.currentTimeMillis() / 1000
        val value = DsyncV2.migrate(v1, v2, v3, deviceId, BuildConfig.VERSION_NAME, now)
        if (value != null) {
            var body: JsonObject = value
            var step = 0
            while (true) {
                val r = runCatching { client.appDataPut(c.base, c.apiKey, main, body.toString()) }.getOrNull()
                Log.i(TAG, "[DSYNC] migration put $main bytes=${DsyncEnvelopeCodec.byteSize(body)} status=${r?.status ?: "network"}")
                if (r == null || r.status >= 500) return false
                if (r.ok) break
                if (r.status != 413) return true // old keys stay; try again next pull
                val next = nextTrim(body, step) ?: run {
                    Log.w(TAG, "[DSYNC] migration: still too large after every trim; old keys kept")
                    return true
                }
                body = next.first
                step = next.second
            }
            s.serverBytes[main] = DsyncEnvelopeCodec.byteSize(body).toLong()
        }
        var removed = 0
        for (name in others) {
            val r = runCatching { client.appDataDelete(c.base, c.apiKey, name) }.getOrNull()
            if (r != null && (r.ok || r.status == 404)) { removed++; s.serverBytes.remove(name) }
        }
        state.edit().putBoolean(k("v3seen", s), true).apply()
        Log.i(TAG, "[DSYNC] migrated to the single key ($removed keys removed)")
        return true
    }

    /** The next 413 trim at or after [from]; returns the trimmed value and the step to try after it. */
    private fun nextTrim(value: JsonObject, from: Int): Pair<JsonObject, Int>? {
        var i = from
        while (i < DsyncV2.TRIMS.size) {
            val t = DsyncV2.applyTrim(value, i)
            val label = DsyncV2.TRIMS[i].label
            i++
            if (t != null && t != value) {
                Log.i(TAG, "[DSYNC] 413: trimmed $label")
                return t to i
            }
        }
        return null
    }

    /** One pulled category (v1 rules per category, section 6). */
    private suspend fun processCategory(s: Session, key: DsyncKey, env: DsyncEnvelope, bytes: Int) {
        val raw = env.raw
        val localAt = state.getLong(k("at", s, key), 0L)
        val localDev = state.getString(k("dev", s, key), "").orEmpty()
        val wins = DsyncEnvelopeCodec.remoteWins(localAt, localDev, env.updatedAt, env.deviceId)
        // profiles merges per row on every pull; the rows carry their own updatedAt.
        val decision = if (key == DsyncKey.Profiles) DsyncGate.DirtyPull.Merge
        else DsyncGate.onNewerPull(key, isDirty(s, key), dirtyAt(s, key), env.updatedAt)
        if (key == DsyncKey.Profiles || (wins && decision == DsyncGate.DirtyPull.Merge)) {
            val merged = runCatching { mergedEnvelope(s, key, env) }
                .onFailure { Log.w(TAG, "[DSYNC] merge ${key.suffix} failed: ${it.javaClass.simpleName}") }
                .getOrNull() ?: return
            val applied = runCatching { apply(s, key, merged) }.getOrDefault(ApplyResult.Failed)
            if (applied != ApplyResult.Applied) {
                if (applied == ApplyResult.Malformed) Log.w(TAG, "[DSYNC] malformed category ${key.suffix}")
                return
            }
            s.suppressUntil[key] = System.currentTimeMillis() + SUPPRESS_MS
            saveAfter(s, key, raw, env.updatedAt, env.deviceId, env.data)
            if (key != DsyncKey.Profiles) {
                setDirty(s, key, true)
                Log.i(TAG, "[DSYNC] pull ${key.suffix} bytes=$bytes merged with local change")
            } else {
                Log.i(TAG, "[DSYNC] pull ${key.suffix} bytes=$bytes merged per row")
            }
            s.read += key
            return
        }
        if (wins && decision == DsyncGate.DirtyPull.KeepLocal) {
            state.edit().putString(k("raw", s, key), raw.toString()).apply()
            Log.i(TAG, "[DSYNC] pull ${key.suffix} bytes=$bytes kept local (local change newer)")
            s.read += key
            return
        }
        if (wins) {
            if (isDirty(s, key)) setDirty(s, key, false)
            val applied = runCatching { apply(s, key, env) }
                .onFailure { Log.w(TAG, "[DSYNC] apply ${key.suffix} failed: ${it.javaClass.simpleName}") }
                .getOrDefault(ApplyResult.Failed)
            when (applied) {
                ApplyResult.Applied -> {
                    s.suppressUntil[key] = System.currentTimeMillis() + SUPPRESS_MS
                    saveAfter(s, key, raw, env.updatedAt, env.deviceId, env.data)
                    Log.i(TAG, "[DSYNC] pull ${key.suffix} bytes=$bytes applied")
                }
                ApplyResult.Malformed -> {
                    Log.w(TAG, "[DSYNC] malformed category ${key.suffix}")
                    return
                }
                // The channel list is not loaded yet: do not count as read.
                ApplyResult.NotReady, ApplyResult.Failed -> return
            }
        } else {
            // Keep the newest raw for round-trip and the row merges.
            state.edit().putString(k("raw", s, key), raw.toString()).apply()
            Log.i(TAG, "[DSYNC] pull ${key.suffix} bytes=$bytes not newer")
            if (healIfLocalDiffers(s, key, env) == ApplyResult.NotReady) return
        }
        s.read += key
    }

    /**
     * Self-heal, once per session per key: a stamp this device already
     * consumed skips the apply ("not newer"), so a pull that once applied
     * into the wrong scope or mapped nothing (2026-10-28 roster add + switch:
     * Jordan's favorites never appeared) would stay wrong forever. When the
     * session's own local copy is empty or differs from a non-empty server
     * copy and there is no unsent local change, apply the server copy anyway.
     */
    private suspend fun healIfLocalDiffers(s: Session, key: DsyncKey, env: DsyncEnvelope): ApplyResult? {
        if (key !in HEAL_KEYS || isDirty(s, key) || key in s.healed) return null
        val d = env.data
        val check: Pair<Boolean, Int>? = runCatching {
            when (key) {
                DsyncKey.Favorites -> {
                    val ids = DsyncPayloads.decodeFavorites(d).orEmpty()
                    val m = channelMap(s)
                    if (ids.isEmpty() || m.toInt.isEmpty()) null else {
                        val local = favoriteDao.allOnceIn(s.token).map { it.channelId }.filter { it in m.toInt }.toSet()
                        val server = ids.mapNotNull { m.toLocal[it] }.toSet()
                        if (server.isEmpty() || local == server) null else (local.isEmpty() to ids.size)
                    }
                }
                DsyncKey.Recents -> {
                    val remote = DsyncPayloads.decodeRecents(d).orEmpty()
                    val m = channelMap(s)
                    if (remote.isEmpty() || m.toInt.isEmpty()) null else {
                        val local = prefs.recentChannelIdsIn(s.token).filter { it in m.toInt }.toSet()
                        val server = remote.mapNotNull { m.toLocal[it.channelId] }.toSet()
                        if (server.isEmpty() || local.containsAll(server)) null else (local.isEmpty() to remote.size)
                    }
                }
                DsyncKey.Collections -> {
                    val remote = DsyncPayloads.decodeCollections(d).orEmpty()
                    val m = channelMap(s)
                    if (remote.isEmpty() || m.toInt.isEmpty()) null else {
                        val local = prefs.channelCollectionsIn(s.token)
                        val l = local.associate { it.id to (it.name to it.memberIds.filter { id -> id in m.toInt }.toSet()) }
                        val r = remote.associate { it.id to (it.name to it.members.mapNotNull { id -> m.toLocal[id] }.toSet()) }
                        if (l == r) null else (local.isEmpty() to remote.size)
                    }
                }
                DsyncKey.HiddenGroups -> {
                    val g = DsyncPayloads.decodeHiddenGroups(d)
                    if (g == null || (g.live.isEmpty() && g.movies.isEmpty() && g.series.isEmpty())) null else {
                        val l = Triple(prefs.hiddenGroupsIn(s.token), prefs.hiddenMovieGroupsIn(s.token), prefs.hiddenSeriesGroupsIn(s.token))
                        if (l == Triple(g.live.toSet(), g.movies.toSet(), g.series.toSet())) null
                        else ((l.first.isEmpty() && l.second.isEmpty() && l.third.isEmpty()) to (g.live.size + g.movies.size + g.series.size))
                    }
                }
                DsyncKey.GroupOrder -> {
                    val g = DsyncPayloads.decodeGroupOrder(d)
                    if (g == null || (g.order.isEmpty() && g.mode == "default")) null else {
                        val mode = prefs.groupSortModeIn(s.token).lowercase()
                        val order = prefs.groupOrderIn(s.token)
                        if (mode == g.mode && order == g.order) null else (order.isEmpty() to g.order.size)
                    }
                }
                else -> null
            }
        }.getOrNull()
        if (check == null) {
            s.healed += key
            return null
        }
        val applied = runCatching { apply(s, key, env) }.getOrDefault(ApplyResult.Failed)
        if (applied == ApplyResult.NotReady) return applied // channel list not loaded: try again next pull
        s.healed += key
        if (applied == ApplyResult.Applied) {
            s.suppressUntil[key] = System.currentTimeMillis() + SUPPRESS_MS
            Log.i(TAG, "[DSYNC] ${key.suffix} in: re-applied after ${if (check.first) "empty local" else "local differs"} (${check.second})")
        }
        return applied
    }

    // ---- write (section 7.3 to 7.8) ----

    private fun onLocalChange(s: Session, key: DsyncKey) {
        if (session !== s) return
        // Flows re-key on a profile switch: that emission is the NEW profile's data.
        if (ProfileScope.token.value != s.token) return
        if ((s.suppressUntil[key] ?: 0L) > System.currentTimeMillis()) return
        s.tooLarge -= key
        setDirty(s, key, true)
        schedule(s, key, DEBOUNCE_MS)
    }

    private fun schedule(s: Session, key: DsyncKey, delayMs: Long) {
        synchronized(s.pending) {
            s.pending.remove(key)?.cancel()
            s.pending[key] = scope.launch {
                delay(delayMs)
                synchronized(s.pending) { s.pending.remove(key) }
                write(s, key)
            }
        }
    }

    private suspend fun flush(s: Session) {
        val keys = synchronized(s.pending) {
            val ks = s.pending.keys.toList()
            ks.forEach { s.pending.remove(it)?.cancel() }
            ks
        }
        keys.forEach { write(s, it) }
    }

    private suspend fun write(s: Session, key: DsyncKey) {
        s.mutex.withLock { writeLocked(s, key, retry413 = true) }
    }

    private fun retryLater(s: Session, key: DsyncKey) {
        s.resend += key
        val n = (s.retries[key] ?: 0)
        if (n < BACKOFF_MS.size) {
            s.retries[key] = n + 1
            schedule(s, key, BACKOFF_MS[n])
        }
    }

    private suspend fun writeLocked(s: Session, key: DsyncKey, retry413: Boolean) {
        if (session !== s || s.paused) return
        if (!DsyncGate.mayWrite(s.token, ProfileScope.token.value, s.firstPullDone, key in s.read)) {
            // Section 7.2 and the switch rule: the dirty flag stays set for this profile's next session.
            if (ProfileScope.token.value != s.token) Log.i(TAG, "[DSYNC] drop ${key.suffix}: profile changed before send")
            return
        }
        if (key in s.blocked || !enabled(key, s)) return
        val c = conn(s) ?: return
        val b = DsyncBucket.of(key)
        if (key in s.resend) Log.i(TAG, "[DSYNC] retry ${key.suffix} after offline")
        // Every PUT reads the key first: row categories merge, whole-value
        // categories keep the newer side, and every other category in the key
        // is re-emitted at its current server value.
        when (refreshBeforePut(s, c, b, key)) {
            Refresh.Unreachable -> { retryLater(s, key); return }
            Refresh.RemoteTook -> return
            Refresh.Ok -> Unit
        }
        val raw = rawOf(s, key)
        val base = raw?.get("data") as? JsonObject
        val now = System.currentTimeMillis() / 1000
        val data = runCatching { build(s, key, base, now) }
            .onFailure { Log.w(TAG, "[DSYNC] build ${key.suffix} failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return
        if (session !== s || ProfileScope.token.value != s.token) return
        if (data.toString() == lastDataOf(s, key) && key in s.present) {
            setDirty(s, key, false)
            if (key == DsyncKey.Profiles) {
                state.edit()
                    .remove(profilesPendingKey(s.playlistId))
                    .putLong(profilesPushedKey(s.playlistId), newestRowStamp(s.playlistId))
                    .apply()
                clearAckedRemovals(s.playlistId, data)
            }
            return
        }
        if (s.serverFull && b.wire !in s.serverBytes) {
            refreshMessage(s)
            return
        }
        val sub = DsyncV2.encodeCategory(raw, deviceId, now, data)
        // No byte guard before the PUT: send the full value; trim only on a 413.
        var value = DsyncV2.encodeBucket(bucketRaw(s, b), deviceId, BuildConfig.VERSION_NAME, now, mapOf(key to sub))
        var step = 0
        while (true) {
            when (put(s, c, b, key, value, data)) {
                PutStatus.Ok -> {
                    s.resend -= key
                    setDirty(s, key, false)
                    if (key == DsyncKey.Profiles) {
                        state.edit()
                            .remove(profilesPendingKey(s.playlistId))
                            .putLong(profilesPushedKey(s.playlistId), newestRowStamp(s.playlistId))
                            .apply()
                        clearAckedRemovals(s.playlistId, data)
                    }
                    s.retries.remove(key)
                    s.tooLarge -= key
                }
                PutStatus.TooLarge -> {
                    val next = nextTrim(value, step)
                    if (next != null) {
                        value = next.first
                        step = next.second
                        continue
                    }
                    s.tooLarge += key
                    Log.w(TAG, "[DSYNC] ${b.wire} still too large after every trim; stopped until the next change")
                }
                PutStatus.Full -> s.serverFull = true
                PutStatus.Auth -> s.paused = true
                PutStatus.Retry -> retryLater(s, key)
                PutStatus.Rejected, null -> Unit
            }
            break
        }
        refreshMessage(s)
    }

    private enum class PutStatus { Ok, TooLarge, Full, Auth, Retry, Rejected }
    private enum class Refresh { Ok, Unreachable, RemoteTook }

    /**
     * GET the key that holds [key]. Saves it (so the PUT re-emits the other
     * categories at their server values); when [key]'s category changed since
     * this device last read or wrote it, rows merge into local state first,
     * and a newer whole value is taken instead of overwriting it.
     */
    private suspend fun refreshBeforePut(s: Session, c: Conn, b: DsyncBucket, key: DsyncKey): Refresh {
        val f = fetchBucket(c, b)
        val got = when (f) {
            Fetched.Failed -> return Refresh.Unreachable
            Fetched.Absent, Fetched.Malformed -> return Refresh.Ok
            is Fetched.TooNew -> { s.blocked += b.keys; return Refresh.RemoteTook }
            is Fetched.Got -> f
        }
        saveBucketRaw(s, b, got.bucket.raw)
        s.serverBytes[b.wire] = got.bytes.toLong()
        val d = DsyncV2.category(got.bucket, key) as? DsyncDecode.Ok ?: return Refresh.Ok
        val env = d.envelope
        s.present += key
        val at = state.getLong(k("at", s, key), 0L)
        val dev = state.getString(k("dev", s, key), "").orEmpty()
        if (env.updatedAt == at && env.deviceId == dev && (rawOf(s, key)?.get("data") as? JsonObject) == env.data) return Refresh.Ok
        if (ProfileScope.token.value != s.token) return Refresh.Unreachable
        if (key in DsyncGate.ROW_MERGE_KEYS || key == DsyncKey.Profiles) {
            val merged = runCatching { mergedEnvelope(s, key, env) }.getOrNull() ?: return Refresh.Unreachable
            val applied = runCatching { apply(s, key, merged) }.getOrDefault(ApplyResult.Failed)
            if (applied != ApplyResult.Applied) return if (applied == ApplyResult.Malformed) Refresh.Ok else Refresh.Unreachable
            s.suppressUntil[key] = System.currentTimeMillis() + SUPPRESS_MS
            saveAfter(s, key, env.raw, env.updatedAt, env.deviceId, env.data)
            Log.i(TAG, "[DSYNC] pull ${key.suffix} bytes=${got.bytes} merged with local change (before put)")
            return Refresh.Ok
        }
        if (!DsyncEnvelopeCodec.remoteWins(at, dev, env.updatedAt, env.deviceId)) return Refresh.Ok
        return when (DsyncGate.onNewerPull(key, isDirty(s, key), dirtyAt(s, key), env.updatedAt)) {
            DsyncGate.DirtyPull.KeepLocal -> {
                state.edit().putString(k("raw", s, key), env.raw.toString()).apply()
                Refresh.Ok
            }
            else -> {
                val applied = runCatching { apply(s, key, env) }.getOrDefault(ApplyResult.Failed)
                if (applied != ApplyResult.Applied) return Refresh.Unreachable
                s.suppressUntil[key] = System.currentTimeMillis() + SUPPRESS_MS
                saveAfter(s, key, env.raw, env.updatedAt, env.deviceId, env.data)
                setDirty(s, key, false)
                Log.i(TAG, "[DSYNC] ${key.suffix}: server copy newer than the local change; applied")
                Refresh.RemoteTook
            }
        }
    }

    private suspend fun put(s: Session, c: Conn, b: DsyncBucket, key: DsyncKey, value: JsonObject, data: JsonObject): PutStatus? {
        val body = value.toString()
        val bytes = body.toByteArray(Charsets.UTF_8).size
        var r = runCatching { client.appDataPut(c.base, c.apiKey, b.wire, body) }.getOrNull()
        if (r != null && (r.status == 401 || r.status == 403)) {
            // Section 7.7: take the current credential (a re-login stores a new
            // key on the profile) and try once more before pausing.
            val again = freshConn(s)?.also { s.bound = it }
            if (again != null && again.apiKey != c.apiKey) {
                r = runCatching { client.appDataPut(again.base, again.apiKey, b.wire, body) }.getOrNull()
            }
        }
        Log.i(TAG, "[DSYNC] put ${b.wire} (${key.suffix}) bytes=$bytes status=${r?.status ?: "network"}${r?.code?.let { " code=$it" } ?: ""}")
        if (r == null || r.status >= 500) return PutStatus.Retry
        return when {
            r.ok -> {
                saveBucketRaw(s, b, value)
                val sub = (value[DsyncV2.CATEGORIES] as? JsonObject)?.get(key.suffix) as? JsonObject ?: JsonObject(emptyMap())
                val at = sub["updatedAt"].asStr()?.let(DsyncEnvelopeCodec::parseIso) ?: (System.currentTimeMillis() / 1000)
                saveAfter(s, key, sub, at, deviceId, data)
                s.serverBytes[b.wire] = bytes.toLong()
                s.present += key
                stampLast(s)
                PutStatus.Ok
            }
            r.status == 413 -> PutStatus.TooLarge
            r.status == 409 -> PutStatus.Full
            r.status == 401 || r.status == 403 -> PutStatus.Auth
            else -> PutStatus.Rejected
        }
    }

    // ---- local sources (change detection) ----

    private fun sourceFor(key: DsyncKey, playlistId: String?, token: String? = null): Flow<Any?> = when (key) {
        // A session watches its own scope; the between-sessions watcher follows the active one.
        DsyncKey.Favorites -> (if (token != null) favoriteDao.observeAllIn(token) else favoriteDao.observeAll())
            .map { rows -> rows.map { it.channelId to it.displayOrder } }
        DsyncKey.Recents -> prefs.recentChannelIds.map { it.also { ids -> stampRecents(ids) } }
        DsyncKey.Progress -> watchProgressDao.observeRecent(1000).map { rows -> rows.map { listOf(it.videoId, it.positionMs, it.updatedAt, it.isFinished) } }
        DsyncKey.Reminders -> reminderDao.observeAll().map { rows -> rows.map { it.reminderKey } }
        DsyncKey.Watchlist -> watchlistStore.observe(null).map { rows -> rows.map { it.key to it.playlistId } }
        DsyncKey.HiddenTitles -> hiddenTitlesStore.observe(null)
        DsyncKey.HiddenGroups -> combine(prefs.hiddenGroups, prefs.hiddenMovieGroups, prefs.hiddenSeriesGroups) { a, b, c -> Triple(a, b, c) }
        DsyncKey.GroupOrder -> combine(prefs.groupOrder, prefs.groupSortMode) { a, b -> a to b }
        DsyncKey.Settings -> if (playlistId != null) combine(prefs.dsyncSettings(playlistId), prefs.syncScopes) { a, b -> a to b }
        else combine(prefs.dsyncSettings(null), prefs.defaultGroupTokensAll, prefs.syncScopes) { a, b, c -> Triple(a, b, c) }
        DsyncKey.RemoteMap -> prefs.remoteControlMap
        DsyncKey.RadioDefault -> prefs.radioModeByDefault
        DsyncKey.Collections -> prefs.channelCollections
        DsyncKey.Profiles -> combine(profileStore.profiles, pinStore.changes) { p, v -> p.map { listOf(it.username, it.displayName) } to v }
    }

    // ---- channel id mapping (schema 4: Dispatcharr channel `id` int) ----

    private data class ChannelMap(
        val toInt: Map<String, Int>,
        val toLocal: Map<Int, String>,
        val nameOf: Map<Int, String>,
        val intByName: Map<String, Int>,
    )

    /**
     * The channel snapshot key of the session's own profile (the same shape
     * ProfileScope.cacheKey writes: bare playlist id for the default profile,
     * "<playlistId>@<profileId>" for another). Never the global current scope.
     */
    private fun snapshotKey(s: Session): String =
        if (s.token.isEmpty()) s.playlistId else "${s.playlistId}@${s.token}"

    private suspend fun channelMap(s: Session): ChannelMap {
        val rows = channelSnapshotDao.forPlaylistKey(snapshotKey(s))
        val toInt = HashMap<String, Int>()
        val toLocal = HashMap<Int, String>()
        val nameOf = HashMap<Int, String>()
        val byName = HashMap<String, Int>()
        rows.forEach { r ->
            val id = r.dispatcharrChannelId ?: return@forEach
            toInt[r.channelId] = id
            toLocal.putIfAbsent(id, r.channelId)
            nameOf.putIfAbsent(id, r.name)
            byName.putIfAbsent(r.name, id)
        }
        return ChannelMap(toInt, toLocal, nameOf, byName)
    }

    private fun stampRecents(ids: List<String>) {
        val s = session ?: return
        val now = System.currentTimeMillis() / 1000
        val stamps = recentStamps(s).toMutableMap()
        val head = ids.firstOrNull() ?: return
        // The head moved or is new: it was just watched.
        val prevHead = state.getString(k("rhead", s), null)
        if (prevHead != head || head !in stamps) stamps[head] = now
        ids.forEachIndexed { i, id -> stamps.putIfAbsent(id, now - i) }
        val keep = stamps.filterKeys { it in ids.toSet() }
        state.edit()
            .putString(k("rstamp", s), JsonObject(keep.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString())
            .putString(k("rhead", s), head)
            .apply()
    }

    private fun recentStamps(s: Session): Map<String, Long> =
        runCatching {
            (json.parseToJsonElement(state.getString(k("rstamp", s), "{}")!!) as JsonObject)
                .mapNotNull { (id, v) -> v.asLong()?.let { id to it } }.toMap()
        }.getOrDefault(emptyMap())

    private suspend fun scopeHost(s: Session): String? =
        playlistDao.byId(s.playlistId)?.let { WatchlistStore.scopeFor(it) }

    // ---- build (local -> data), section 4 and 6.2 ----

    private suspend fun build(s: Session, key: DsyncKey, base: JsonObject?, now: Long): JsonObject? = when (key) {
        DsyncKey.Favorites -> {
            val m = channelMap(s)
            if (m.toInt.isEmpty()) null else {
                val rows = favoriteDao.allOnceIn(s.token).sortedBy { it.displayOrder }
                val local = rows.mapNotNull { m.toInt[it.channelId] }
                // Remote ids this device cannot map (another channel set) are kept.
                val keep = base?.let(DsyncPayloads::decodeFavorites).orEmpty().filter { it !in m.toLocal }
                val out = (local + keep).distinct()
                Log.i(TAG, DsyncFavorites.logLine("out", rows.size, local.size, out.size, s.token, s.playlistId))
                DsyncPayloads.encodeFavorites(base, out)
            }
        }
        DsyncKey.Recents -> {
            val m = channelMap(s)
            if (m.toInt.isEmpty()) null else {
                val stamps = recentStamps(s)
                val ids = prefs.recentChannelIdsIn(s.token)
                val local = ids.mapIndexedNotNull { i, id -> m.toInt[id]?.let { RecentRow(it, stamps[id] ?: (now - i)) } }
                val remote = base?.let(DsyncPayloads::decodeRecents).orEmpty()
                DsyncPayloads.encodeRecents(base, DsyncMerge.recents(local, remote))
            }
        }
        DsyncKey.Progress -> {
            val c = conn(s) ?: return null
            val local = watchProgressDao.allOnce()
                .filter { (it.playlistId == null || it.playlistId == s.playlistId) && !it.videoId.contains("://") }
                .filter { it.vodType == "movie" || it.vodType == "episode" }
                .mapNotNull { p ->
                    val ep = p.vodType == "episode"
                    val sid = p.seriesId?.toIntOrNull()
                    // Rows carry the server's integer ids; a row that cannot be resolved stays local.
                    val id = if (ep) vodIds.episodeId(c.base, c.apiKey, p.videoId, sid) else vodIds.movieId(c.row, p.videoId)
                    if (id == null) { unresolvedOnce(s, "progress", p.videoId); return@mapNotNull null }
                    ProgressRow(
                        vodId = id, type = if (ep) "e" else "m",
                        position = p.positionMs / 1000, duration = p.durationMs / 1000,
                        updatedAt = p.updatedAt / 1000, finished = p.isFinished,
                        seriesId = if (ep) sid else null,
                        season = if (ep) p.seasonNumber else 0, episode = if (ep) p.episodeNumber else 0,
                        title = p.title,
                    )
                }
            val remote = base?.let(DsyncPayloads::decodeProgress).orEmpty()
            DsyncPayloads.encodeProgress(base, DsyncMerge.progress(local, remote), now)
        }
        DsyncKey.Reminders -> {
            val m = channelMap(s)
            if (m.toInt.isEmpty()) null else {
                val local = reminderDao.allOnce()
                    .filter { it.playlistId == null || it.playlistId == s.playlistId }
                    .mapNotNull { r ->
                        val cid = m.intByName[r.channelName] ?: return@mapNotNull null
                        ReminderRow(cid, r.startMillis / 1000, r.endMillis / 1000, r.programTitle, r.channelName)
                    }
                val remote = base?.let(DsyncPayloads::decodeReminders).orEmpty()
                val keep = remote.filter { it.channelId !in m.toLocal }
                val rows = DsyncPayloads.carryTails(local, remote, { it.channelId to it.start }, { it.tail }) { r, t -> r.copy(tail = t) }
                DsyncPayloads.encodeReminders(base, rows + keep, now)
            }
        }
        DsyncKey.Watchlist -> {
            val host = scopeHost(s)
            val local = watchlistStore.allOnce().filter { it.isLive && (it.playlistId == host || it.playlistId == null) }
                .mapNotNull { e -> markRow(s, "watchlist", e.key, e.addedAt / 1000) }
            val remote = base?.let(DsyncPayloads::decodeVodMarks).orEmpty()
            val rows = DsyncPayloads.carryTails(local, remote, { it.type to it.vodId }, { it.tail }) { r, t -> r.copy(tail = t) }
            DsyncPayloads.encodeVodMarks(base, rows + unknownMovieRows(s, remote, rows), DsyncLimits.WATCHLIST)
        }
        DsyncKey.HiddenTitles -> {
            val host = scopeHost(s)
            val local = hiddenTitlesStore.allOnce().filter { it.isLive && (it.playlistId == host || it.playlistId == null) }
                .mapNotNull { e -> markRow(s, "hiddenTitles", e.key, e.hiddenAt / 1000) }
            val remote = base?.let(DsyncPayloads::decodeVodMarks).orEmpty()
            val rows = DsyncPayloads.carryTails(local, remote, { it.type to it.vodId }, { it.tail }) { r, t -> r.copy(tail = t) }
            DsyncPayloads.encodeVodMarks(base, rows + unknownMovieRows(s, remote, rows), DsyncLimits.HIDDEN_TITLES)
        }
        DsyncKey.HiddenGroups -> DsyncPayloads.encodeHiddenGroups(
            base,
            DsyncPayloads.HiddenGroups(
                prefs.hiddenGroupsIn(s.token).sorted(),
                prefs.hiddenMovieGroupsIn(s.token).sorted(),
                prefs.hiddenSeriesGroupsIn(s.token).sorted(),
            ),
        )
        DsyncKey.GroupOrder -> {
            val mode = prefs.groupSortModeIn(s.token).lowercase().takeIf { it in DsyncPayloads.GROUP_MODES } ?: "default"
            DsyncPayloads.encodeGroupOrder(base, DsyncPayloads.GroupOrder(mode, prefs.groupOrderIn(s.token)))
        }
        DsyncKey.Settings -> {
            val v = prefs.dsyncSettingsOnce(s.playlistId, s.token)
            Log.i(TAG, "[DSYNC] settings: defaultGroup=${v["defaultGroup"]?.content ?: ""}")
            // Each name goes to the lane its item's scope picks on this device
            // (v, or this device class's own lane); Off writes nothing.
            val scopes = prefs.syncScopesOnce()
            val lanes = com.aeriotv.android.core.sync.laneValues(
                v.filterKeys { DsyncSettingsValidator.isOwned(it) }, { prefs.syncScopeForName(scopes, it) }, isTv,
            )
            Log.i(TAG, "[DSYNC] settings: lanes ${lanes.entries.joinToString { "${it.key}=${it.value.size}" }}")
            DsyncPayloads.encodeSettingsLanes(base, lanes)
        }
        DsyncKey.RemoteMap -> {
            // Phones and tablets round-trip the TV map untouched: never write it.
            if (!isTv) null else {
                val blob = prefs.remoteControlMap.first()
                val tv = if (blob.isBlank()) JsonObject(emptyMap())
                else runCatching { json.parseToJsonElement(blob) as? JsonObject }.getOrNull() ?: return null
                DsyncPayloads.encodeRemoteMap(base, tv)
            }
        }
        DsyncKey.RadioDefault -> DsyncPayloads.encodeRadioDefault(base, prefs.radioModeByDefaultIn(s.token))
        DsyncKey.Collections -> {
            val m = channelMap(s)
            if (m.toInt.isEmpty()) null else {
                val remote = base?.let(DsyncPayloads::decodeCollections).orEmpty().associateBy { it.id }
                val rows = prefs.channelCollectionsIn(s.token).map { c ->
                    val r = remote[c.id]
                    val stale = r?.members.orEmpty().filter { it !in m.toLocal }
                    CollectionRow(
                        id = c.id, name = c.name,
                        placement = if (c.placement == ChannelCollection.PLACEMENT_BEGINNING) "b" else "e",
                        members = (c.memberIds.mapNotNull { m.toInt[it] } + stale).distinct(),
                        tail = r?.tail ?: Tail(),
                    )
                }
                DsyncPayloads.encodeCollections(base, rows)
            }
        }
        DsyncKey.Profiles -> buildProfiles(s, base)
    }

    /** A local "m:<uuid>" / "s:<id>" key as a row with the integer id, or null (kept local, logged once). */
    private suspend fun markRow(s: Session, what: String, key: String, at: Long): VodMarkRow? {
        val c = conn(s) ?: return null
        return when {
            key.startsWith("s:") -> key.substring(2).toIntOrNull()?.let { VodMarkRow(it, "s", at) }
            key.startsWith("m:") -> vodIds.movieId(c.row, key.substring(2))?.let { VodMarkRow(it, "m", at) }
                ?: run { unresolvedOnce(s, what, key); null }
            else -> null
        }
    }

    /** Server movie rows this device cannot map (not in its catalog) are kept, never dropped. */
    private suspend fun unknownMovieRows(s: Session, remote: List<VodMarkRow>, ours: List<VodMarkRow>): List<VodMarkRow> {
        val c = conn(s) ?: return emptyList()
        val have = ours.map { it.type to it.vodId }.toSet()
        val movies = remote.filter { it.type == "m" && ("m" to it.vodId) !in have }
        if (movies.isEmpty()) return emptyList()
        val known = vodIds.movieUuids(c.row, movies.map { it.vodId }.toSet())
        return movies.filter { it.vodId !in known }
    }

    private val unresolvedLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun unresolvedOnce(s: Session, what: String, localId: String) {
        if (unresolvedLogged.add("${s.profileKey}|$what|$localId")) {
            Log.i(TAG, "[DSYNC] $what: no server id for a local row yet; kept on this device only")
        }
    }

    private suspend fun adminUsers(s: Session): List<DispatcharrUserSummary> {
        s.users?.let { return it }
        if (!isServerAdmin(s)) {
            // No users list without a server admin key (403): the local
            // profiles' own user ids (read from /me/ as each user) stand in.
            return profileStore.forPlaylist(s.playlistId).filter { it.userId > 0 }
                .map { DispatcharrUserSummary(id = it.userId, username = it.username) }
        }
        val c = conn(s) ?: return emptyList()
        val list = runCatching { client.listUsers(c.base, c.apiKey) }.getOrDefault(emptyList())
        if (list.isNotEmpty()) s.users = list
        return list
    }

    private suspend fun buildProfiles(s: Session, base: JsonObject?): JsonObject? {
        if (!isAdmin(s)) return null
        val users = adminUsers(s)
        if (users.isEmpty()) return null
        val remote = base?.let(DsyncPayloads::decodeProfiles).orEmpty()
        val tombs = tombstonesFor(s.playlistId, base)
        // Tombstoned local profiles are deleted, never re-sent (schema 4.12).
        val gone = tombstonedLocal(s.playlistId, tombs).map { it.lowercase() }.toSet()
        removeSynced(s.playlistId, gone.toList(), tombs)
        val rows = profileStore.forPlaylist(s.playlistId).filter { it.username.lowercase() !in gone }.withIndex().mapNotNull { (index, p) ->
            val user = users.firstOrNull { it.username.equals(p.username, ignoreCase = true) } ?: return@mapNotNull null
            val prev = remote.firstOrNull { it.userId == user.id }
            val rec = pinStore.syncRecord(p.id)
            val (type, hash, salt) = when {
                rec != null -> Triple(PinRules.wireType(rec.type), rec.hash, rec.salt.orEmpty())
                // A v1 PIN cannot sync until its next correct entry: keep what the server has.
                pinStore.isLegacy(p.id) && prev != null -> Triple(prev.pinType, prev.pinHash, prev.pinSalt)
                else -> Triple("none", "", "")
            }
            var at = state.getLong(rowKey(s.playlistId, p.username), 0L)
            if (at <= 0L) {
                at = prev?.updatedAt?.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
                state.edit().putLong(rowKey(s.playlistId, p.username), at).apply()
            }
            val out = ProfileRow(
                user.id, user.username, p.displayName, type, hash, salt,
                role = p.effectiveRole().wire,
                updatedAt = at,
                avatar = p.avatar ?: com.aeriotv.android.core.profiles.AvatarSpec(),
                order = index,
                tail = prev?.tail ?: Tail(),
            )
            val fresh = (rosterAdds[rowKey(s.playlistId, p.username)] ?: 0L) > System.currentTimeMillis() - 60_000L
            com.aeriotv.android.core.profiles.ProfileRoster.guardFreshRosterAdd(out, prev, fresh)?.also {
                Log.i(TAG, "[DSYNC] profiles: row ${p.username} kept server name/avatar (fresh roster add)")
            } ?: out
        }
        // Stale-device guard: a local row the server list lacks, with no
        // unsent local change, older than the server roster is not re-sent.
        val serverAt = state.getLong(k("at", s, DsyncKey.Profiles), 0L)
        val pushedAt = state.getLong(profilesPushedKey(s.playlistId), 0L)
        val localNewest = rows.maxOfOrNull { it.updatedAt } ?: 0L
        val sent = rows.filter { r ->
            val stale = ProfileTombstones.staleLocal(r, remote, serverAt, localNewest, pushedAt)
            if (stale) Log.i(TAG, "[DSYNC] profiles: local row ${r.username} older than server roster, not re-sent")
            !stale
        }
        // Rows for users this device has no profile for stay, unless a
        // tombstone covers them (removed here or on any other device).
        val mine = rows.map { it.userId }.toSet()
        val kept = remote.filter { it.userId !in mine }
        val resolved = ProfileTombstones.resolve(sent + kept, tombs)
        resolved.dropped.forEach { Log.i(TAG, "[DSYNC] profiles: row ${it.username} updatedAt=${it.updatedAt} tombstoned; left out") }
        return DsyncPayloads.encodeProfiles(base, resolved.rows, resolved.removed)
    }

    // ---- apply (data -> local), section 6.1 full replace ----

    private enum class ApplyResult { Applied, Malformed, NotReady, Failed }

    /** Pulled rows with integer ids as local "m:<uuid>" / "s:<id>" keys; rows with no local movie are skipped. */
    private suspend fun localMarkKeys(s: Session, rows: List<VodMarkRow>): List<Pair<String, VodMarkRow>> {
        val c = conn(s) ?: return emptyList()
        val uuids = vodIds.movieUuids(c.row, rows.filter { it.type == "m" }.map { it.vodId }.toSet())
        return rows.mapNotNull { r ->
            when (r.type) {
                "s" -> "s:${r.vodId}" to r
                "m" -> uuids[r.vodId]?.let { "m:$it" to r }
                else -> null
            }
        }
    }

    /**
     * A newer server value for a key with an unsent local change: the row
     * union of both sides (favorites, collections); progress and recents
     * already merge per row inside [apply]; profiles merge per row there too.
     */
    private suspend fun mergedEnvelope(s: Session, key: DsyncKey, env: DsyncEnvelope): DsyncEnvelope? = when (key) {
        DsyncKey.Favorites -> {
            val m = channelMap(s)
            if (m.toInt.isEmpty()) null else {
                val remote = DsyncPayloads.decodeFavorites(env.data)
                if (remote == null) null else {
                    val local = favoriteDao.allOnceIn(s.token).sortedBy { it.displayOrder }.mapNotNull { m.toInt[it.channelId] }
                    env.copy(data = DsyncPayloads.encodeFavorites(env.data, DsyncGate.favoritesUnion(local, remote)))
                }
            }
        }
        DsyncKey.Collections -> {
            val m = channelMap(s)
            val remote = DsyncPayloads.decodeCollections(env.data)
            if (m.toInt.isEmpty() || remote == null) null else {
                val local = prefs.channelCollectionsIn(s.token).map { c ->
                    CollectionRow(
                        id = c.id, name = c.name,
                        placement = if (c.placement == ChannelCollection.PLACEMENT_BEGINNING) "b" else "e",
                        members = c.memberIds.mapNotNull { m.toInt[it] },
                    )
                }
                env.copy(data = DsyncPayloads.encodeCollections(env.data, DsyncGate.collectionsUnion(local, remote)))
            }
        }
        DsyncKey.Reminders -> {
            val m = channelMap(s)
            val remote = DsyncPayloads.decodeReminders(env.data)
            if (m.toInt.isEmpty() || remote == null) null else {
                val local = reminderDao.allOnce()
                    .filter { it.playlistId == null || it.playlistId == s.playlistId }
                    .mapNotNull { r ->
                        val cid = m.intByName[r.channelName] ?: return@mapNotNull null
                        ReminderRow(cid, r.startMillis / 1000, r.endMillis / 1000, r.programTitle, r.channelName)
                    }
                val nowS = System.currentTimeMillis() / 1000
                env.copy(data = DsyncPayloads.encodeReminders(env.data, DsyncGate.remindersUnion(local, remote), nowS))
            }
        }
        else -> env
    }

    private suspend fun apply(s: Session, key: DsyncKey, env: DsyncEnvelope): ApplyResult {
        val d = env.data
        // Every write below names the session's scope (s.token). The keys whose
        // stores still read the global scope (progress, reminders, VOD marks)
        // are held back once the active profile is no longer this session's.
        if (ProfileScope.token.value != s.token) {
            Log.i(TAG, "[DSYNC] apply ${key.suffix}: profile changed; skipped (scope=${s.token.take(8).ifEmpty { "default" }})")
            return ApplyResult.NotReady
        }
        when (key) {
            DsyncKey.Favorites -> {
                val ids = DsyncPayloads.decodeFavorites(d) ?: return ApplyResult.Malformed
                val m = channelMap(s)
                if (m.toInt.isEmpty()) {
                    Log.i(TAG, "[DSYNC] favorites in: ${ids.size} ids, snapshot ${snapshotKey(s).take(8)} not loaded; retry next pull")
                    return ApplyResult.NotReady
                }
                val plan = DsyncFavorites.plan(
                    ids, m.toLocal, m.toInt, m.nameOf, favoriteDao.allOnceIn(s.token), s.token, System.currentTimeMillis(),
                )
                val written = favoriteDao.applySyncIn(s.token, plan.deletes, plan.upserts)
                Log.i(TAG, DsyncFavorites.logLine("in", ids.size, plan.wanted.size, written, s.token, s.playlistId))
            }
            DsyncKey.Recents -> {
                val remote = DsyncPayloads.decodeRecents(d) ?: return ApplyResult.Malformed
                val m = channelMap(s)
                if (m.toInt.isEmpty()) return ApplyResult.NotReady
                val stamps = recentStamps(s)
                val ids = prefs.recentChannelIdsIn(s.token)
                val nowS = System.currentTimeMillis() / 1000
                val local = ids.mapIndexedNotNull { i, id -> m.toInt[id]?.let { RecentRow(it, stamps[id] ?: (nowS - i)) } }
                val merged = DsyncMerge.recents(local, remote).mapNotNull { r -> m.toLocal[r.channelId]?.let { it to r.watchedAt } }
                val others = ids.filter { it !in m.toInt }
                prefs.setRecentChannelsIn(merged.map { it.first } + others, s.token)
                val st = stamps.toMutableMap().apply { merged.forEach { (id, at) -> put(id, at) } }
                state.edit()
                    .putString(k("rstamp", s), JsonObject(st.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString())
                    .putString(k("rhead", s), merged.firstOrNull()?.first ?: others.firstOrNull())
                    .apply()
            }
            DsyncKey.Progress -> {
                val rows = DsyncPayloads.decodeProgress(d) ?: return ApplyResult.Malformed
                val c = conn(s) ?: return ApplyResult.Failed
                val movieUuids = vodIds.movieUuids(c.row, rows.filter { it.type == "m" }.map { it.vodId }.toSet())
                rows.forEach { r ->
                    // Integer id back to the local uuid; a row this device cannot map is skipped.
                    val uuid = if (r.type == "e") vodIds.episodeUuid(c.base, c.apiKey, r.vodId, r.seriesId) else movieUuids[r.vodId]
                    if (uuid == null) { unresolvedOnce(s, "progress in", "${r.type}${r.vodId}"); return@forEach }
                    val local = watchProgressDao.getOnce(uuid)
                    if (local != null && r.updatedAt * 1000 <= local.updatedAt) return@forEach
                    val ep = r.type == "e"
                    val baseRow = local ?: WatchProgressEntity(
                        videoId = uuid, title = r.title, posterUrl = null,
                        positionMs = 0, durationMs = 0, updatedAt = 0, playlistId = s.playlistId,
                    )
                    watchProgressDao.upsert(
                        baseRow.copy(
                            title = if (local != null && local.title.length > r.title.length && local.title.startsWith(r.title)) local.title else r.title,
                            positionMs = r.position * 1000,
                            durationMs = if (r.duration > 0) r.duration * 1000 else baseRow.durationMs,
                            updatedAt = r.updatedAt * 1000,
                            vodType = if (ep) "episode" else "movie",
                            seriesId = r.seriesId?.toString() ?: baseRow.seriesId,
                            seasonNumber = if (r.season > 0) r.season else baseRow.seasonNumber,
                            episodeNumber = if (r.episode > 0) r.episode else baseRow.episodeNumber,
                            isFinished = r.finished,
                        ),
                    )
                }
            }
            DsyncKey.Reminders -> {
                val rows = DsyncPayloads.decodeReminders(d) ?: return ApplyResult.Malformed
                val m = channelMap(s)
                if (m.toInt.isEmpty()) return ApplyResult.NotReady
                applyReminders(s, rows, m)
            }
            DsyncKey.Watchlist -> {
                val rows = DsyncPayloads.decodeVodMarks(d) ?: return ApplyResult.Malformed
                val host = scopeHost(s)
                val keyed = localMarkKeys(s, rows)
                // Local rows that have no server id yet are kept as they are.
                val keepLocal = watchlistStore.allOnce()
                    .filter { it.isLive && it.playlistId == host && it.key.startsWith("m:") && markRow(s, "watchlist", it.key, 0) == null }
                watchlistStore.replaceScope(
                    host,
                    keyed.map { (key, r) ->
                        WatchlistStore.Entry(key = key, title = "", isMovie = r.type == "m", playlistId = host, addedAt = r.at * 1000)
                    } + keepLocal,
                )
            }
            DsyncKey.HiddenTitles -> {
                val rows = DsyncPayloads.decodeVodMarks(d) ?: return ApplyResult.Malformed
                val host = scopeHost(s)
                val keepLocal = hiddenTitlesStore.allOnce()
                    .filter { it.isLive && it.playlistId == host && it.key.startsWith("m:") && markRow(s, "hiddenTitles", it.key, 0) == null }
                    .associate { it.key to it.hiddenAt }
                hiddenTitlesStore.replaceScope(host, localMarkKeys(s, rows).associate { (key, r) -> key to r.at * 1000 } + keepLocal)
            }
            DsyncKey.HiddenGroups -> {
                val g = DsyncPayloads.decodeHiddenGroups(d) ?: return ApplyResult.Malformed
                prefs.setHiddenGroupsIn(g.live.toSet(), g.movies.toSet(), g.series.toSet(), s.token)
            }
            DsyncKey.GroupOrder -> {
                val g = DsyncPayloads.decodeGroupOrder(d) ?: return ApplyResult.Malformed
                prefs.setGroupOrderIn(
                    when (g.mode) { "alphabetical" -> "Alphabetical"; "manual" -> "Manual"; else -> "Default" },
                    g.order, s.token,
                )
            }
            DsyncKey.Settings -> {
                val lanes = DsyncPayloads.decodeSettingsLanes(d) ?: return ApplyResult.Malformed
                // Each name is read from the one lane its scope picks here (no
                // fallback across lanes); Off reads nothing. Only owned names
                // are validated; foreign names round-trip untouched.
                val scopes = prefs.syncScopesOnce()
                val v = com.aeriotv.android.core.sync.pickFromLanes(lanes, emptyList(), { prefs.syncScopeForName(scopes, it) }, isTv)
                val r = DsyncSettingsValidator.filter(v.filterKeys { DsyncSettingsValidator.isOwned(it) })
                r.ignored.forEach { Log.i(TAG, "[DSYNC] settings: ignored $it") }
                r.accepted["defaultGroup"]?.let { Log.i(TAG, "[DSYNC] settings: defaultGroup=${it.content}") }
                prefs.applyDsyncSettings(r.accepted, s.playlistId, s.token)
            }
            DsyncKey.RemoteMap -> {
                val tv = DsyncPayloads.decodeRemoteMap(d) ?: return ApplyResult.Malformed
                if (isTv) prefs.setRemoteControlMap(if (tv.isEmpty()) "" else tv.toString())
            }
            DsyncKey.RadioDefault -> {
                val on = DsyncPayloads.decodeRadioDefault(d) ?: return ApplyResult.Malformed
                prefs.setRadioModeByDefaultIn(on, s.token)
            }
            DsyncKey.Collections -> {
                val rows = DsyncPayloads.decodeCollections(d) ?: return ApplyResult.Malformed
                val m = channelMap(s)
                if (m.toInt.isEmpty()) return ApplyResult.NotReady
                prefs.setChannelCollectionsIn(
                    rows.map { r ->
                        ChannelCollection(
                            id = r.id, name = r.name,
                            memberIds = r.members.mapNotNull { m.toLocal[it] },
                            placement = if (r.placement == "b") ChannelCollection.PLACEMENT_BEGINNING else ChannelCollection.PLACEMENT_END,
                        )
                    },
                    s.token,
                )
            }
            DsyncKey.Profiles -> {
                val rows = DsyncPayloads.decodeProfiles(d) ?: return ApplyResult.Malformed
                if (!isAdmin(s)) return ApplyResult.Applied
                Log.i(TAG, "[DSYNC] profiles: pull stamps ${state.all.keys.filter { it.startsWith("pgone|") }.joinToString { "$it=${state.getLong(it, 0L)}" }.ifEmpty { "none" }} session=${s.playlistId}")
                val tombs = tombstonesFor(s.playlistId, d)
                removeSynced(s.playlistId, tombstonedLocal(s.playlistId, tombs), tombs)
                val resolved = ProfileTombstones.resolve(rows, tombs)
                resolved.dropped.forEach { Log.i(TAG, "[DSYNC] profiles: server row ${it.username} updatedAt=${it.updatedAt} tombstoned; dropped") }
                // A local removal the server payload does not carry yet: send it.
                val onServer = DsyncPayloads.decodeProfileTombstones(d)
                val unsent = localTombstones(s.playlistId).filter { l ->
                    ProfileTombstones.find(resolved.removed, l.username) != null &&
                        (ProfileTombstones.find(onServer, l.username)?.removedAt ?: 0L) < l.removedAt
                }
                if (unsent.isNotEmpty() && !isDirty(s, DsyncKey.Profiles)) {
                    Log.i(TAG, "[DSYNC] profiles: tombstones ${unsent.joinToString { it.username }} not on the server yet; re-sending")
                    setDirty(s, DsyncKey.Profiles, true)
                    schedule(s, DsyncKey.Profiles, DEBOUNCE_MS)
                }
                profileManager.get().mergeSyncedProfiles(
                    s.playlistId, resolved.rows, adminUsers(s),
                    localUpdatedAt = { u -> state.getLong(rowKey(s.playlistId, u), 0L) },
                    adopted = { u, at -> if (at > 0) state.edit().putLong(rowKey(s.playlistId, u), at).apply() },
                    removedAt = { u -> state.getLong(removedKey(s.playlistId, u), 0L) },
                )
            }
        }
        return ApplyResult.Applied
    }

    private suspend fun applyReminders(s: Session, rows: List<ReminderRow>, m: ChannelMap) {
        val now = System.currentTimeMillis()
        val wanted = rows.filter { it.end * 1000 >= now }.associateBy { it.channelId to it.start }
        val local = reminderDao.allOnce().filter { it.playlistId == null || it.playlistId == s.playlistId }
        val have = HashSet<Pair<Int, Long>>()
        local.forEach { r ->
            val cid = m.intByName[r.channelName] ?: return@forEach // unresolvable: never removed by a pull
            val id = cid to r.startMillis / 1000
            if (id in wanted) have += id
            else {
                cancelAlarm(r.alarmRequestCode)
                reminderDao.delete(r.reminderKey)
            }
        }
        wanted.forEach { (id, r) ->
            if (id in have) return@forEach
            val localId = m.toLocal[r.channelId] ?: return@forEach
            val name = m.nameOf[r.channelId] ?: r.channelName
            val startMs = r.start * 1000
            val key = reminderKey(name, r.title, startMs)
            reminderDao.upsert(
                ReminderEntity(
                    reminderKey = key, channelName = name, programTitle = r.title,
                    startMillis = startMs, endMillis = r.end * 1000,
                    alarmRequestCode = key.hashCode(), playlistId = s.playlistId,
                ),
            )
            scheduleAlarm(key, name, r.title, localId, startMs)
        }
    }

    /** Same PendingIntent identity and 5-minute lead as RemindersViewModel. */
    private fun scheduleAlarm(key: String, channelName: String, title: String, channelId: String, startMs: Long) {
        val mgr = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = (startMs - 5 * 60 * 1000L).coerceAtLeast(System.currentTimeMillis() + 1_000L)
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_KEY, key)
            putExtra(ReminderBroadcastReceiver.EXTRA_TITLE, title)
            putExtra(ReminderBroadcastReceiver.EXTRA_CHANNEL_NAME, channelName)
            putExtra(ReminderBroadcastReceiver.EXTRA_CHANNEL_ID, channelId)
        }
        val pi = PendingIntent.getBroadcast(
            context, key.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching { mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
    }

    private fun cancelAlarm(requestCode: Int) {
        val mgr = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, requestCode, Intent(context, ReminderBroadcastReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mgr.cancel(pi)
    }

    companion object {
        private const val TAG = "AerioDsync"
        const val PROVIDER = "dispatcharr"
        private const val K_DEVICE = "deviceId"
        const val DEBOUNCE_MS = 5_000L
        const val FOREGROUND_PULL_MS = 5L * 60 * 1000
        const val PERIODIC_PULL_MS = 15L * 60 * 1000
        private const val SUPPRESS_MS = 3_000L
        private val HEAL_KEYS = setOf(DsyncKey.Favorites, DsyncKey.Recents, DsyncKey.Collections, DsyncKey.HiddenGroups, DsyncKey.GroupOrder)
        val BACKOFF_MS = longArrayOf(30_000L, 120_000L, 600_000L)
        val RECONNECT_MS = longArrayOf(2_000L, 5_000L, 15_000L, 60_000L)

        /** Schema 4.9 names with an Android key (prefix families via DsyncSettingsValidator.isOwned). */
        val SETTINGS_OWNED: Set<String> get() = DsyncSettingsValidator.NAMES
    }
}

/** Dispatcharr Sync: skip a bucket GET pulled moments ago with the same listed size. */
internal const val BUCKET_REFETCH_MIN_MS = 60_000L

internal fun shouldSkipBucketGet(lastPullAt: Long, now: Long, fetchedBytes: Long?, listedBytes: Long?): Boolean =
    lastPullAt > 0L && now - lastPullAt in 0 until BUCKET_REFETCH_MIN_MS &&
        fetchedBytes != null && listedBytes != null && fetchedBytes == listedBytes
