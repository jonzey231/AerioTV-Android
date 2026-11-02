package com.aeriotv.android.core.sync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import com.aeriotv.android.core.data.db.AerioDatabase
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.ReminderDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import com.aeriotv.android.core.data.db.entity.FavoriteChannelEntity
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.db.entity.ReminderEntity
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity
import com.aeriotv.android.core.preferences.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Orchestrates Drive AppData sync. Mirrors iOS SyncManager.swift surface:
 *   - signIn() / signOut(), wraps GoogleDriveAuth.
 *   - pushAll() / pullAll(), per-category JSON snapshots written to / read
 *     from the appDataFolder.
 *   - clearRemote(), Settings > "Clear Drive Data" hook.
 *   - status flow exposed for SyncSettingsScreen badge.
 *
 * Conflict resolution: last-writer-wins by [SyncEnvelope.lastModified]. A
 * pull only overwrites local rows when the remote envelope is newer; a push
 * always wins over older remote data because the local DB is treated as the
 * latest writer at sync time.
 */
@Singleton
class DriveSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AerioDatabase,
    private val playlistDao: PlaylistDao,
    private val watchProgressDao: WatchProgressDao,
    private val reminderDao: ReminderDao,
    private val favoriteChannelDao: com.aeriotv.android.core.data.db.dao.FavoriteChannelDao,
    private val appPreferences: AppPreferences,
    private val watchlistStore: com.aeriotv.android.core.preferences.WatchlistStore,
    private val hiddenTitlesStore: com.aeriotv.android.core.preferences.HiddenTitlesStore,
    /** Provider: a replacing pull deletes local playlists through the
     * repository's full cleanup path (guide, channel cache, VOD, tokens). */
    private val playlistRepository: javax.inject.Provider<com.aeriotv.android.core.data.repository.PlaylistRepository>,
    /** Coexistence Rule 1: Dispatcharr Sync owns the shared categories for its playlist's user. */
    private val dispatcharrSync: javax.inject.Provider<com.aeriotv.android.core.sync.dsync.DispatcharrSyncManager>,
    private val providersStore: SyncProvidersStore,
    /** Rule 4 canonical ids (DriveCanon): Dispatcharr ids written next to local ids. */
    private val idLookup: DriveIdLookup,
    /** Rule 4 retry after an unknown-Drive probe (Provider: the coordinator depends on this class). */
    private val setupCoordinator: javax.inject.Provider<SetupSourceCoordinator>,
) {
    /** The record every file of the current push carries (envelope.syncProviders). */
    @Volatile private var pushProviders: SyncProvidersRecord? = null

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val auth = GoogleDriveAuth(context)
    private val authState = context.getSharedPreferences("aerio_drive_auth", Context.MODE_PRIVATE)
    private val refreshLock = kotlinx.coroutines.sync.Mutex()
    /** The last token a refresh replaced, and its replacement: callers holding the stale one switch over. */
    @Volatile private var replaced: Pair<String, String>? = null
    /** Wall clock after which the in-memory token is not trusted (same budget as the persisted one). */
    @Volatile private var tokenExpiry: Long = 0L

    private val _needsSignIn = MutableStateFlow(authState.getBoolean(K_NEEDS_SIGN_IN, false))
    /** Drive rejected the token and a silent refresh could not fix it: the Sync screen asks the user to sign in again. */
    val needsSignIn: StateFlow<Boolean> = _needsSignIn.asStateFlow()

    private val authHandler = object : DriveAuthHandler {
        override fun needsSignIn(): Boolean = _needsSignIn.value
        override fun current(token: String): String = replaced?.takeIf { it.first == token }?.second ?: token
        override suspend fun refresh(stale: String): String? = refreshAfter401(stale)
        override fun markNeedsSignIn(reason: String) = setNeedsSignIn(reason)
    }

    private val driveClient = DriveAppDataClient(okHttp, authHandler)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private val _status: MutableStateFlow<Status> = MutableStateFlow(Status.SignedOut)
    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * Per-category progress for the onboarding restore screen. Empty when no
     * tracked restore has run this process; otherwise one entry per category
     * in pull order. Only [pullAllTracked] writes here -- the background
     * worker and Settings "Sync Now" use the untracked [pullAll] so a routine
     * sync never repaints the onboarding progress UI.
     */
    private val _restoreSteps: MutableStateFlow<List<RestoreStep>> = MutableStateFlow(emptyList())
    val restoreSteps: StateFlow<List<RestoreStep>> = _restoreSteps.asStateFlow()

    /** Reset the tracked restore progress (call when leaving the restore UI). */
    fun clearRestoreProgress() {
        _restoreSteps.value = emptyList()
    }

    private fun updateRestoreStep(category: SyncCategory, state: RestoreStepState, detail: String? = null) {
        _restoreSteps.value = _restoreSteps.value.map { step ->
            if (step.category == category) step.copy(state = state, detail = detail) else step
        }
    }

    /**
     * Step 1 of the two-step flow: open the Sign-in-with-Google sheet via
     * Credential Manager. Returns the user's email on success so the caller
     * can persist it; the resulting identity has no Drive scope yet, so the
     * UI should call [requestDriveScope] next to obtain the bearer token.
     */
    suspend fun signInWithGoogle(activity: android.app.Activity): String? {
        if (!SyncConfig.isConfigured()) {
            _status.value = Status.MissingConfig
            return null
        }
        val result = runCatching { auth.signIn(activity) }.getOrNull() ?: return null
        appPreferences.setSyncAccountEmail(result.email)
        return result.email
    }

    /**
     * Step 2: ask AuthorizationClient for the Drive AppData scope. Returns
     *  - [Status.SignedIn] when the user has already granted the scope.
     *  - [Status.NeedsConsent] when consent UI must be shown by the caller.
     *
     * The caller (SyncSettingsScreen) is responsible for launching the
     * consent intent via an ActivityResultLauncher and feeding the result
     * back through [acceptConsentResult].
     */
    suspend fun requestDriveScope(): RequestResult? {
        if (!SyncConfig.isConfigured()) return null
        val authResult = runCatching { auth.requestDriveAuthorization() }.getOrNull()
            ?: return RequestResult.Failed
        val token = authResult.accessToken
        if (token != null) {
            setSignedIn(token)
            return RequestResult.Authorized(token)
        }
        val pi = authResult.pendingIntent
        return if (pi != null) {
            RequestResult.NeedsConsent(pi.intentSender)
        } else {
            RequestResult.Failed
        }
    }

    /** Consume the activity result intent from the consent flow. Persists the
     * resulting token so the session survives a process restart. */
    suspend fun acceptConsentResult(data: android.content.Intent?) {
        val token = auth.extractAccessToken(data)
        if (token != null) setSignedIn(token) else _status.value = Status.SignedOut
    }

    /**
     * Restore a usable Drive access token across process restarts WITHOUT a
     * manual re-login. Order:
     *   1. Already [Status.SignedIn] this process -> return its token.
     *   2. A persisted token that hasn't hit its estimated expiry -> adopt it
     *      (pure disk read, no network, no consent UI). This is the common
     *      "it just stays signed in" path.
     *   3. Otherwise refresh silently via AuthorizationClient, which returns a
     *      fresh token with NO UI because the Drive scope is already granted
     *      (the access token Google issues only lives ~1h, so this fires after
     *      long gaps). Returns null only when no token can be had without user
     *      interaction (never signed in, scope revoked, offline) so background
     *      callers just skip instead of popping a sheet.
     */
    suspend fun ensureSignedIn(): String? {
        // Drive rejected the token and the silent refresh failed: no call goes
        // out until the user signs in again (Settings > Sync notice).
        if (_needsSignIn.value) return null
        // The in-memory token used to live for the whole process; it now
        // honors the same expiry as the persisted one.
        (status.value as? Status.SignedIn)?.let { if (System.currentTimeMillis() < tokenExpiry) return it.accessToken }
        appPreferences.syncTokenOnce()?.let { (saved, expiry) ->
            if (System.currentTimeMillis() < expiry) {
                tokenExpiry = expiry
                _status.value = Status.SignedIn(saved)
                return saved
            }
        }
        if (!SyncConfig.isConfigured()) return null
        // Only refresh for a user who has opted into sync before -- never
        // trigger an authorize() for someone who never signed in.
        if (appPreferences.syncAccountEmailOnce().isBlank()) return null
        val authResult = runCatching { auth.requestDriveAuthorization() }.getOrNull() ?: return null
        val token = authResult.accessToken ?: return null
        setSignedIn(token)
        return token
    }

    /** Persist the token with a conservative expiry and flip status to signed-in. */
    private suspend fun setSignedIn(token: String) {
        val expiry = System.currentTimeMillis() + TOKEN_TTL_MS
        appPreferences.saveSyncToken(token, expiry)
        tokenExpiry = expiry
        _status.value = Status.SignedIn(token)
        if (_needsSignIn.value) {
            _needsSignIn.value = false
            authState.edit().remove(K_NEEDS_SIGN_IN).commit()
            Log.i(TAG, "[DRIVE] signed in again; Drive calls resume")
        }
    }

    /**
     * A Drive call answered 401 with [stale]. Clears it from the Play
     * services cache (authorize() would hand the same token back otherwise),
     * then asks for a new one with no UI. Concurrent 401s share one refresh.
     * Null: no working token without the user (the caller marks needs sign-in).
     */
    private suspend fun refreshAfter401(stale: String): String? = refreshLock.withLock {
        replaced?.takeIf { it.first == stale }?.let { return@withLock it.second }
        if (_needsSignIn.value) return@withLock null
        Log.i(TAG, "[DRIVE] HTTP 401: refreshing the access token once")
        runCatching { auth.clearToken(stale) }
        appPreferences.clearSyncToken()
        tokenExpiry = 0L
        val fresh = if (!SyncConfig.isConfigured()) null
        else runCatching { auth.requestDriveAuthorization() }.getOrNull()?.accessToken
        if (fresh == null || fresh == stale) {
            Log.w(TAG, "[DRIVE] token refresh failed: ${if (fresh == null) "no token without sign-in" else "same token returned"}")
            return@withLock null
        }
        replaced = stale to fresh
        setSignedIn(fresh)
        Log.i(TAG, "[DRIVE] token refreshed after 401")
        fresh
    }

    private fun setNeedsSignIn(reason: String) {
        if (_needsSignIn.value) return
        _needsSignIn.value = true
        authState.edit().putBoolean(K_NEEDS_SIGN_IN, true).commit()
        _status.value = Status.SignedOut
        tokenExpiry = 0L
        Log.w(TAG, "[DRIVE] needs sign-in: $reason; Drive calls stop until the user signs in again")
    }

    suspend fun signOut() {
        runCatching { auth.signOut() }
        appPreferences.setSyncAccountEmail("")
        appPreferences.clearSyncToken()
        tokenExpiry = 0L
        _status.value = Status.SignedOut
        if (_needsSignIn.value) {
            _needsSignIn.value = false
            authState.edit().remove(K_NEEDS_SIGN_IN).commit()
        }
    }

    /** Drop every appData file. Local DB rows are untouched (iOS parity). */
    suspend fun clearRemote(token: String) {
        val files = driveClient.listAll(token)
        files.forEach { (id, _) -> driveClient.delete(token, id) }
    }

    /**
     * Drop ONE category's appData file (Apple SyncCategoriesSettingsView's
     * per-category "Delete from iCloud"). Local rows are untouched, so other
     * devices keep their copy. Matches by name rather than findFileId so a
     * duplicate left by an interrupted first upload goes too. Throws on the
     * first failed delete, like [clearRemote]'s callers expect.
     */
    suspend fun clearRemoteCategory(token: String, category: SyncCategory) {
        driveClient.listAll(token)
            .filter { (_, name) -> name == category.fileName }
            .forEach { (id, _) -> driveClient.delete(token, id).getOrThrow() }
    }

    /**
     * Push enabled categories to Drive. Bails on the first failure but
     * continues across categories, a Reminders push fail doesn't block
     * Playlists. Returns a map of category → success.
     */
    suspend fun pushAll(token: String, enabled: Set<SyncCategory>): Map<SyncCategory, Boolean> {
        Log.i(TAG, "pushAll: categories=${enabled.map { it.name }}")
        val result = mutableMapOf<SyncCategory, Boolean>()
        // The Drive backup belongs to the base (signed-in) profile: read its state only.
        pushProviders = runCatching {
            kotlinx.coroutines.withContext(com.aeriotv.android.core.profiles.ProfileScope.defaultOnly()) {
                providersStore.forPush(
                    SyncProvidersStore.DRIVE,
                    isBaseProfile = true,
                    dispatcharrOn = appPreferences.dispatcharrSyncEnabledOnce(),
                    driveOn = appPreferences.syncMasterEnabled.first(),
                    deviceId = dispatcharrSync.get().deviceId,
                )
            }
        }.getOrNull()
        for (category in enabled) {
            // Worker cancelled (Drive turned off, or a newer push replaced
            // this one): stop before the next category starts; a category
            // already uploading commits whole.
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                val driveOn = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { appPreferences.syncMasterEnabled.first() }.getOrDefault(false)
                }
                val reason = if (driveOn) "superseded by a newer push" else "sync cancelled (Drive turned off)"
                Log.i(TAG, "[DRIVE] push stopped before ${category.name}: $reason")
                break
            }
            val ok = runCatching { pushCategory(token, category) }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) Log.w(TAG, "push ${category.name} failed", it) }
                .getOrDefault(false)
            result[category] = ok
        }
        if (result.values.any { it }) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            appPreferences.setSyncLastPushAt(System.currentTimeMillis())
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return result
    }

    /**
     * [replace] true is the explicit Settings "Pull from Drive": Playlists and
     * Preferences REPLACE this device's copy (owner decision, Settings phase 3,
     * iPhone parity) instead of merging onto it. Background and onboarding
     * pulls keep merging.
     */
    suspend fun pullAll(
        token: String,
        enabled: Set<SyncCategory>,
        replace: Boolean = false,
        /** Rule 4: the user chose Google Drive, so apply even a category Dispatcharr Sync owns. */
        ignoreOwnership: Boolean = false,
    ): Map<SyncCategory, Boolean> {
        val result = mutableMapOf<SyncCategory, Boolean>()
        enabled.forEach { category ->
            val ok = runCatching { pullCategory(token, category, replace, ignoreOwnership) }
                .onFailure { Log.w(TAG, "pull ${category.name} failed", it) }
                .getOrDefault(false)
            result[category] = ok
        }
        appPreferences.setSyncLastPullAt(System.currentTimeMillis())
        // This install has seen the remote state; automatic pushes are now
        // safe (see AppPreferences.syncInitialPullDone).
        appPreferences.setSyncInitialPullDone(true)
        return result
    }

    /**
     * [pullAll] variant that publishes per-category progress through
     * [restoreSteps] for the onboarding restore screen. Same return shape and
     * same per-category semantics (a category maps to true only when a remote
     * snapshot existed AND applied cleanly); the only addition is the step
     * state machine: Pending -> Running -> Done/Failed. A category whose file
     * simply doesn't exist in Drive yet settles as Done with a
     * "Nothing in Drive yet" detail instead of Failed -- a fresh account
     * isn't an error.
     */
    suspend fun pullAllTracked(token: String, enabled: Set<SyncCategory>): Map<SyncCategory, Boolean> {
        _restoreSteps.value = enabled.map { RestoreStep(category = it) }
        val result = mutableMapOf<SyncCategory, Boolean>()
        enabled.forEach { category ->
            updateRestoreStep(category, RestoreStepState.Running)
            val outcome = runCatching { pullCategoryDetailed(token, category) }
                .onFailure { Log.w(TAG, "pull ${category.name} failed", it) }
                .getOrDefault(PullOutcome.Failed)
            result[category] = outcome == PullOutcome.Applied
            when (outcome) {
                PullOutcome.Applied, PullOutcome.Owned -> updateRestoreStep(category, RestoreStepState.Done)
                PullOutcome.NoRemote -> updateRestoreStep(category, RestoreStepState.Done, "Nothing in Drive yet")
                PullOutcome.Failed -> updateRestoreStep(category, RestoreStepState.Failed, "Couldn't restore")
            }
        }
        appPreferences.setSyncLastPullAt(System.currentTimeMillis())
        appPreferences.setSyncInitialPullDone(true)
        return result
    }

    // Profiles phase 1: sync carries the DEFAULT profile only. defaultOnly()
    // pins every scoped DAO/pref read and write to scope "" and makes the
    // playlist DAO return the stored (default) login, so a non-default active
    // profile never leaks into, or is overwritten by, the Drive snapshot.
    // PHASE 2 HOOK: per-profile sync replaces this with a profile id per file.
    private suspend fun pushCategory(token: String, category: SyncCategory, label: String? = null): Boolean =
        kotlinx.coroutines.withContext(com.aeriotv.android.core.profiles.ProfileScope.defaultOnly()) {
            pushCategoryDefaultProfile(token, category, label)
        }

    private suspend fun pushCategoryDefaultProfile(token: String, category: SyncCategory, label: String?): Boolean {
        val shared = category in com.aeriotv.android.core.sync.dsync.DsyncCoexistence.SHARED_CATEGORIES
        // Rule 4: never push this device's (not yet chosen) shared data over the backup.
        if (sharedHeld() && shared) {
            Log.i(TAG, "[DRIVE] push ${category.name} held: waiting for the setup choice")
            return true
        }
        // Unknown Drive (the setup probe failed): no choice was made, so no
        // shared file goes up until the probe reads Drive and decides.
        if (driveUnknown() && shared) {
            Log.i(TAG, "[DRIVE] push ${category.name} held: Drive state unknown (setup probe failed)")
            return true
        }
        // Pull before replace: the user chose Dispatcharr but its pull has not
        // applied yet, so this device's copy may be older than the server's.
        if (replaceDeferred() && shared) {
            Log.i(TAG, "[DRIVE] push ${category.name} held: replace deferred until the Dispatcharr pull applies")
            return true
        }
        val payload = when (category) {
            SyncCategory.Playlists -> json.encodeToString(buildPlaylistsSnapshot())
            SyncCategory.WatchProgress -> json.encodeToString(driveProgressSnapshot(label ?: "push"))
            SyncCategory.Reminders -> json.encodeToString(buildRemindersSnapshot())
            SyncCategory.Favorites -> json.encodeToString(withCanonicalIds(buildFavoritesSnapshot()))
            SyncCategory.Watchlist -> json.encodeToString(withCanonicalIds(buildWatchlistSnapshot()))
            SyncCategory.Preferences -> json.encodeToString(withCanonicalIds(buildPreferencesSnapshot()))
            SyncCategory.Credentials -> json.encodeToString(buildCredentialsSnapshot())
        }
        // Fresh-device order (Rule 4, Dispatcharr chosen; section 10a): the first
        // push of each shared file waits until the Dispatcharr pull applied its
        // categories here, so an empty fresh device never overwrites a
        // populated backup (field run 2026-11-01: Drive progress 1 fell to 0).
        val waiting = shared && replaceWaiting(category)
        val local = if (shared) localSharedRows(category, payload) else emptyMap()
        if (waiting) {
            if (!appPreferences.dispatcharrSyncEnabledOnce()) {
                clearReplaceWait(category)
            } else {
                val st = runCatching { dispatcharrSync.get().driveOrderState() }.getOrNull()
                // Released once the Dispatcharr pull of each name completed: after
                // that, local is the Dispatcharr copy whatever it counts.
                val scopes = appPreferences.syncScopesOnce()
                val names = SetupSourceChoice.namesInFile(category)
                    .filter { n -> SetupSourceChoice.itemId(n)?.let { !SetupSourceChoice.itemOff(scopes, it) } ?: true }
                val hold = SetupSourceChoice.freshDeviceHold(names, st?.read)
                if (hold != null) {
                    Log.i(TAG, "[DRIVE] push ${category.name} held: $hold (fresh-device order)")
                    return true
                }
                clearReplaceWait(category)
            }
        }
        // Atomic category commit (section 10a, field run 2026-11-01: Drive
        // turned off mid-push logged "upload OK" then "Job was cancelled"):
        // once the upload starts, the upload, the follow bookkeeping, and the
        // push line finish together; cancellation only stops the NEXT
        // category (pushAll checks between categories).
        return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            commitCategory(token, category, payload, label ?: if (waiting) "replace" else null, local)
        }
    }

    /** Upload plus bookkeeping for one category; always runs to completion (NonCancellable caller). */
    private suspend fun commitCategory(token: String, category: SyncCategory, payload: String, why: String?, local: Map<String, Int>): Boolean {
        val ok = driveClient.upload(token, category.fileName, payload).isSuccess
        val follows = DriveSyncWorker.takeFollows(category)
        if (!ok && follows.isNotEmpty()) follows.forEach { DriveSyncWorker.noteFollow(category, it) }
        if (why != null || follows.isNotEmpty()) {
            Log.i(TAG, SetupSourceChoice.drivePushLine(category.name, snapshotRows(category, payload, local), local, why, follows, ok))
        }
        return ok
    }

    /**
     * Canonical rows per shared category in a payload this device is about to
     * push: the same definition the setup probe reads back from Drive
     * (readSharedSummary, DriveCanon) and Dispatcharr Sync counts
     * (DsyncCoexistence.rowCount). The payload already carries the canonical
     * ids (withCanonicalIds), so no lookups are needed beyond the host.
     */
    private suspend fun localSharedRows(category: SyncCategory, payload: String): Map<String, Int> =
        localSharedCategories(category, payload).mapValues { it.value.rows }

    /** The canonical categories a payload carries (rows and fingerprint), the probe's definition. */
    private suspend fun localSharedCategories(category: SyncCategory, payload: String): Map<String, SetupSourceChoice.Category> = runCatching {
        val now = System.currentTimeMillis()
        val ids = DriveCanon.Ids()
        val out = LinkedHashMap<String, SetupSourceChoice.Category>()
        when (category) {
            SyncCategory.Favorites -> out["favorites"] = DriveCanon.favorites(json.decodeFromString(payload), ids)
            SyncCategory.WatchProgress -> out["progress"] = DriveCanon.progress(json.decodeFromString(payload), ids, now)
            SyncCategory.Reminders -> out["reminders"] = DriveCanon.reminders(json.decodeFromString(payload), now)
            SyncCategory.Watchlist -> {
                val w = json.decodeFromString<WatchlistSnapshot>(payload)
                val host = idLookup.activeDispatcharr()?.let { idLookup.host(it) }
                out["watchlist"] = DriveCanon.watchlist(w, ids, host)
                out["hiddenTitles"] = DriveCanon.hiddenTitles(w, ids, host)
            }
            SyncCategory.Preferences -> {
                val p = json.decodeFromString<PreferencesSnapshot>(payload)
                out["recents"] = DriveCanon.recentsCategory(p, ids)
                out["collections"] = DriveCanon.collectionsCategory(p, ids)
                out["hiddenGroups"] = DriveCanon.hiddenGroupsCategory(p)
                out["groupOrder"] = DriveCanon.groupOrderCategory(p)
                // Settings too, counted the way the probe counts them (it was
                // missing, so every push line and hold read settings as 0).
                val scopes = appPreferences.syncScopesOnce()
                val lanes = appPreferences.driveLanesAsWire(
                    mapOf(SyncScope.LANE_ALL to p.keys, SyncScope.LANE_TV to p.tv, SyncScope.LANE_MOBILE to p.mobile),
                    idLookup.activeDispatcharr()?.id,
                )
                val (n, f) = DriveCanon.dsyncSettings(DriveCanon.lanesOn(lanes) { SetupSourceChoice.nameOff(scopes, it) })
                out["settings"] = SetupSourceChoice.Category(n, p.envelope.lastModified, f)
            }
            else -> Unit
        }
        val scopes = appPreferences.syncScopesOnce()
        SetupSourceChoice.forDevice(SetupSourceChoice.Summary(out)) { SetupSourceChoice.itemOff(scopes, it) }
            .categories
    }.getOrDefault(emptyMap())

    /** What a push of [category] would carry now, without uploading (converge mark check). */
    private suspend fun currentSharedCategories(category: SyncCategory): Map<String, SetupSourceChoice.Category> = runCatching {
        kotlinx.coroutines.withContext(com.aeriotv.android.core.profiles.ProfileScope.defaultOnly()) {
            val payload = when (category) {
                SyncCategory.WatchProgress -> json.encodeToString(driveProgressSnapshot(null))
                SyncCategory.Reminders -> json.encodeToString(buildRemindersSnapshot())
                SyncCategory.Favorites -> json.encodeToString(withCanonicalIds(buildFavoritesSnapshot()))
                SyncCategory.Watchlist -> json.encodeToString(withCanonicalIds(buildWatchlistSnapshot()))
                SyncCategory.Preferences -> json.encodeToString(withCanonicalIds(buildPreferencesSnapshot()))
                else -> return@withContext emptyMap()
            }
            localSharedCategories(category, payload)
        }
    }.getOrDefault(emptyMap())

    /** Rows in a pushed snapshot, for the push line (counts only): canonical rows for a one-category shared file. */
    private fun snapshotRows(category: SyncCategory, payload: String, shared: Map<String, Int> = emptyMap()): Int = runCatching {
        if (shared.size == 1) return@runCatching shared.values.first()
        when (category) {
            SyncCategory.WatchProgress -> json.decodeFromString<WatchProgressSnapshot>(payload).entries.size
            SyncCategory.Favorites -> json.decodeFromString<FavoritesSnapshot>(payload).entries.size
            SyncCategory.Reminders -> json.decodeFromString<RemindersSnapshot>(payload).entries.size
            SyncCategory.Watchlist -> json.decodeFromString<WatchlistSnapshot>(payload).let { it.entries.size + it.hidden.size }
            SyncCategory.Preferences -> json.decodeFromString<PreferencesSnapshot>(payload).let { it.keys.size + it.tv.size + it.mobile.size }
            else -> -1
        }
    }.getOrDefault(-1)

    private suspend fun pullCategory(token: String, category: SyncCategory, replace: Boolean = false, ignoreOwnership: Boolean = false): Boolean =
        pullCategoryDetailed(token, category, replace, ignoreOwnership).let { it == PullOutcome.Applied || it == PullOutcome.Owned }

    /** Tri-state pull so [pullAllTracked] can tell "no remote snapshot yet"
     * (fresh account, not an error) apart from a genuine failure. The Boolean
     * [pullCategory] wrapper preserves the original semantics for [pullAll]. */
    private suspend fun pullCategoryDetailed(
        token: String,
        category: SyncCategory,
        replace: Boolean = false,
        ignoreOwnership: Boolean = false,
        /** Rule 4 replace (the user chose Google Drive): the row categories drop local rows the backup lacks. */
        forcedReplace: Boolean = false,
    ): PullOutcome {
        // Rule 4: a shared category waits while the setup choice is pending.
        if (sharedHeld() && category in com.aeriotv.android.core.sync.dsync.DsyncCoexistence.SHARED_CATEGORIES) {
            Log.i(TAG, "[DRIVE] pull ${category.name} held: waiting for the setup choice")
            return PullOutcome.Owned
        }
        if (!ignoreOwnership && driveUnknown() && category in com.aeriotv.android.core.sync.dsync.DsyncCoexistence.SHARED_CATEGORIES) {
            Log.i(TAG, "[DRIVE] pull ${category.name} held: Drive state unknown (setup probe failed)")
            return PullOutcome.Owned
        }
        // Coexistence Rule 1 (docs/DispatcharrSyncSchema.md): Drive still
        // pushes this category as a backup, but does not apply it on pull.
        if (!ignoreOwnership && runCatching { dispatcharrSync.get().ownsCategory(category) }.getOrDefault(false)) {
            Log.i(TAG, "[DRIVE] pull ${category.name} skipped: Dispatcharr Sync owns it")
            return PullOutcome.Owned
        }
        val fileId = driveClient.findFileId(token, category.fileName) ?: return PullOutcome.NoRemote
        val body = driveClient.download(token, fileId) ?: return PullOutcome.Failed
        providersStore.fold(SyncProvidersStore.DRIVE, SyncProvidersRules.fromDriveBody(body))
        return kotlinx.coroutines.withContext(com.aeriotv.android.core.profiles.ProfileScope.defaultOnly()) { runCatching {
            when (category) {
                SyncCategory.Playlists -> applyPlaylistsSnapshot(json.decodeFromString(body), replace)
                // Drive rows carry local ids (no server-id translation), so no
                // cache gate is needed; the counts measure what each apply carried.
                SyncCategory.WatchProgress -> json.decodeFromString<WatchProgressSnapshot>(body).let {
                    Log.i(TAG, "[DRIVE] apply ${category.name} rows=${it.entries.size}"); applyWatchProgressSnapshot(it)
                    recordCanonicalIds(it)
                    holdPlaceholders(it, forcedReplace)
                    if (forcedReplace) replaceProgress(it)
                }
                SyncCategory.Reminders -> json.decodeFromString<RemindersSnapshot>(body).let {
                    Log.i(TAG, "[DRIVE] apply ${category.name} rows=${it.entries.size}"); applyRemindersSnapshot(it)
                    if (forcedReplace) replaceReminders(it)
                }
                SyncCategory.Favorites -> json.decodeFromString<FavoritesSnapshot>(body).let {
                    applyFavoritesSnapshot(it)
                    if (forcedReplace) replaceFavorites(it)
                    Log.i(TAG, "[DRIVE] apply ${category.name} rows=${it.entries.size} local now=${favoriteChannelDao.allOnce().size}")
                }
                SyncCategory.Watchlist -> json.decodeFromString<WatchlistSnapshot>(body).let {
                    recordCanonicalIds(it)
                    Log.i(TAG, "[DRIVE] apply ${category.name} rows=${it.entries.size} hidden=${it.hidden.size}"); if (forcedReplace) replaceWatchlist(it) else applyWatchlistSnapshot(it)
                }
                SyncCategory.Preferences -> json.decodeFromString<PreferencesSnapshot>(body).let {
                    if (forcedReplace) {
                        val local = appPreferences.snapshotSyncablePreferences().let { l -> l.v.size + l.tv.size + l.mobile.size }
                        Log.i(TAG, DriveCanon.replaceLine("preferences", local, it.keys.size + it.tv.size + it.mobile.size, 0))
                    }
                    applyPreferencesSnapshot(it, replace || forcedReplace)
                }
                SyncCategory.Credentials -> applyCredentialsSnapshot(json.decodeFromString(body))
            }
            PullOutcome.Applied
        }.onFailure { Log.w(TAG, "decode ${category.name} failed", it) }
            .getOrDefault(PullOutcome.Failed) }
    }

    /**
     * Section 10a: the canonical ids a format 2 snapshot carries go into the
     * Dispatcharr Sync id map (active Dispatcharr playlist only), so the
     * forced push after "Google Drive" translates those rows from the
     * snapshot first and the local catalog second. Counts only.
     */
    private suspend fun recordCanonicalIds(w: WatchProgressSnapshot) {
        if (idLookup.activeDispatcharr() == null) return
        val (m, e) = DriveCanon.progressCanonical(w)
        idLookup.recordCanonical(m, e)
        Log.i(TAG, "[DRIVE] canonical ids progress movies=${m.size} episodes=${e.size} without=${w.entries.size - m.size - e.size}")
    }

    /** Placeholder rows (held by canonical id on the writer) are held here too, never written as local rows. */
    private suspend fun holdPlaceholders(w: WatchProgressSnapshot, replace: Boolean) {
        val row = idLookup.activeDispatcharr() ?: return
        val rows = w.entries.mapNotNull { com.aeriotv.android.core.sync.dsync.DsyncHeld.fromEntry(it) }
        if (rows.isEmpty() && !replace) return
        dispatcharrSync.get().holdFromDrive(row.id, rows, replace)
        Log.i(TAG, "[DRIVE] held progress rows=${rows.size}${if (replace) " (replace)" else ""}")
    }

    private suspend fun recordCanonicalIds(w: WatchlistSnapshot) {
        if (idLookup.activeDispatcharr() == null) return
        val m = DriveCanon.watchlistCanonical(w)
        idLookup.recordCanonical(m, emptyMap())
        Log.i(TAG, "[DRIVE] canonical ids watchlist movies=${m.size}")
    }

    private enum class PullOutcome { Applied, NoRemote, Failed, Owned }

    // ── Rule 4: choosing a source when both hold data ─────────────────────

    private val setupState = context.getSharedPreferences("aerio_setup_source", Context.MODE_PRIVATE)

    /**
     * Onboarding with both providers selected: hold the shared categories
     * (pull and push) until the user's setup choice. Playlists and
     * Credentials still restore, since the probe needs them. Expires after
     * [HOLD_TTL_MS] so an abandoned flow never blocks Drive for good.
     */
    fun holdShared() {
        setupState.edit().putLong(K_HOLD, System.currentTimeMillis()).commit()
        Log.i(TAG, "[DRIVE] shared categories held until the setup choice")
    }

    fun sharedHeld(): Boolean {
        val at = setupState.getLong(K_HOLD, 0L)
        return at > 0L && System.currentTimeMillis() - at < HOLD_TTL_MS
    }

    /** Pull before replace (section 10a): the Dispatcharr choice's pull failed; Drive is not replaced until it applies. */
    fun markReplaceDeferred() { setupState.edit().putBoolean(K_REPLACE_DEFERRED, true).commit() }
    fun clearReplaceDeferred() { setupState.edit().remove(K_REPLACE_DEFERRED).commit() }
    fun replaceDeferred(): Boolean = setupState.getBoolean(K_REPLACE_DEFERRED, false)

    fun releaseHold() {
        if (!setupState.contains(K_HOLD)) return
        setupState.edit().remove(K_HOLD).commit()
        Log.i(TAG, "[DRIVE] setup hold released")
    }

    /**
     * The setup probe could not read Drive while Dispatcharr Sync could be
     * read (section 10a, unknown Drive): nothing is decided. Shared files
     * are neither pushed nor converged until a probe reads Drive again.
     */
    fun markDriveUnknown() {
        if (driveUnknown()) return
        setupState.edit().putBoolean(K_DRIVE_UNKNOWN, true).commit()
        Log.i(TAG, "[DRIVE] Drive state unknown: shared files wait for a working setup probe")
    }

    fun driveUnknown(): Boolean = setupState.getBoolean(K_DRIVE_UNKNOWN, false)

    fun clearDriveUnknown() {
        if (!driveUnknown()) return
        setupState.edit().remove(K_DRIVE_UNKNOWN).commit()
        Log.i(TAG, "[DRIVE] Drive state known again")
    }

    /** Apply the Drive snapshot's shared categories now (the user chose Google Drive). */
    suspend fun applySharedNow(ignoreOwnership: Boolean): Map<SyncCategory, Boolean> {
        val token = ensureSignedIn() ?: return emptyMap()
        val wanted = SyncCategory.entries
            .filter { it in com.aeriotv.android.core.sync.dsync.DsyncCoexistence.SHARED_CATEGORIES }
            .filter { appPreferences.syncCategoryEnabled(it).first() }
            .toSet()
        Log.i(TAG, "[DRIVE] applying backup (user chose Google Drive) categories=${wanted.map { it.name }}")
        return pullAll(token, wanted, ignoreOwnership = ignoreOwnership)
    }

    /**
     * [applySharedNow] for the Rule 4 Drive choice, per category: true when the
     * backup applied or had no file (nothing to apply is fine), false when the
     * apply failed (network, decode, an exception) or did not run (owned, off).
     */
    suspend fun applySharedForReplace(ignoreOwnership: Boolean): Map<SyncCategory, Boolean> {
        val token = ensureSignedIn() ?: return emptyMap()
        val wanted = SyncCategory.entries
            .filter { it in com.aeriotv.android.core.sync.dsync.DsyncCoexistence.SHARED_CATEGORIES }
            .filter { appPreferences.syncCategoryEnabled(it).first() }
        Log.i(TAG, "[DRIVE] applying backup (user chose Google Drive) categories=${wanted.map { it.name }}")
        val out = wanted.associateWith { c ->
            // Rule 4 mirror: the Drive copy REPLACES local where the apply can
            // replace (Preferences: settings, recents, collections, hidden
            // groups, group order), so the forced push sends exactly the Drive
            // key set. A merge kept local settings the backup did not carry
            // (field run 2026-11-01: Drive settings=7, forced push rows=11).
            // Every shared category replaces (DriveSyncManager.replace*): local
            // rows the backup lacks are removed, so the forced push carries the
            // Drive rows only and both sides converge (no prompt on the next
            // Drive off/on).
            val r = runCatching { pullCategoryDetailed(token, c, ignoreOwnership = ignoreOwnership, forcedReplace = com.aeriotv.android.core.sync.dsync.DsyncCoexistence.replaceOnForcedApply(c)) }
                .onFailure { Log.w(TAG, "pull ${c.name} failed", it) }
                .getOrDefault(PullOutcome.Failed)
            r == PullOutcome.Applied || r == PullOutcome.NoRemote
        }
        appPreferences.setSyncLastPullAt(System.currentTimeMillis())
        appPreferences.setSyncInitialPullDone(true)
        return out
    }

    // Fresh-device order: shared Drive files waiting for their first push after a Dispatcharr choice.
    private fun waitKey(c: SyncCategory) = "replaceWait|${c.name}"
    private fun replaceWaiting(c: SyncCategory) = setupState.getBoolean(waitKey(c), false)
    private fun clearReplaceWait(c: SyncCategory) { setupState.edit().remove(waitKey(c)).commit() }

    /**
     * The user chose Dispatcharr (prompt or automatic UseDispatcharr): every
     * shared Drive file waits for its Dispatcharr pull before its first push
     * (persisted, so a restart keeps the order). Armed before the hold is
     * released, so no worker or follow push can slip in ahead of it.
     */
    fun armReplaceWait() {
        val e = setupState.edit()
        com.aeriotv.android.core.sync.dsync.DsyncCoexistence.SHARED_CATEGORIES.forEach { e.putBoolean(waitKey(it), true) }
        e.commit()
    }

    /** The user chose Google Drive: nothing waits on Dispatcharr. */
    fun clearReplaceWaits() {
        val e = setupState.edit()
        com.aeriotv.android.core.sync.dsync.DsyncCoexistence.SHARED_CATEGORIES.forEach { e.remove(waitKey(it)) }
        e.commit()
    }

    /**
     * The user chose Dispatcharr: overwrite the Drive backup with this
     * device's copy of every shared category NOW, in this session (field run
     * 2026-11-01: the old debounced worker push went out with no per-category
     * line, and a later follow could replace it). Each file logs
     * "[DRIVE] push <Category> rows=N (replace)" or why it waits (fresh-device
     * order); a waiting file goes out with the next push once its Dispatcharr
     * pull has applied (the pull's Drive follow queues it).
     */
    suspend fun replaceBackupNow() {
        appPreferences.setSyncInitialPullDone(true)
        Log.i(TAG, "[DRIVE] next push replaces backup (user chose Dispatcharr)")
        val on = kotlinx.coroutines.withContext(com.aeriotv.android.core.profiles.ProfileScope.defaultOnly()) { appPreferences.syncMasterEnabled.first() }
        if (!on) return
        val token = ensureSignedIn() ?: run { DriveSyncWorker.enqueueOneShotPush(context); return }
        val wanted = SyncCategory.entries
            .filter { it in com.aeriotv.android.core.sync.dsync.DsyncCoexistence.SHARED_CATEGORIES }
            .filter { appPreferences.syncCategoryEnabled(it).first() }
        val ok = wanted.associateWith { c ->
            runCatching { pushCategory(token, c, "replace") }
                .onFailure { Log.w(TAG, "[DRIVE] replace push ${c.name} failed: ${it.javaClass.simpleName}") }
                .getOrDefault(false)
        }
        if (ok.values.any { it }) appPreferences.setSyncLastPushAt(System.currentTimeMillis())
    }

    /**
     * Read-only probe for SetupSourceChoice: the Drive snapshot's shared
     * categories, summarized. Nothing is applied. Null when Drive could not
     * be read (401, HTTP error, network, needs sign-in): unknown, never
     * empty. Only a listing Drive answered can read a missing file as an
     * empty category.
     */
    suspend fun readSharedSummary(token: String): SetupSourceChoice.Summary? = runCatching {
        val files = driveClient.listAll(token).associate { (id, name) -> name to id }
        suspend fun body(c: SyncCategory): String? {
            val id = files[c.fileName] ?: return null
            return driveClient.download(token, id) ?: throw IllegalStateException("download ${c.name}")
        }
        val out = LinkedHashMap<String, SetupSourceChoice.Category>()
        val now = System.currentTimeMillis()
        var carriedP: WatchProgressSnapshot? = null
        var carriedW: WatchlistSnapshot? = null
        var carriedIds = DriveCanon.Ids()
        val scopes = kotlinx.coroutines.withContext(com.aeriotv.android.core.profiles.ProfileScope.defaultOnly()) { appPreferences.syncScopesOnce() }
        kotlinx.coroutines.withContext(com.aeriotv.android.core.profiles.ProfileScope.defaultOnly()) {
            val row = idLookup.activeDispatcharr()
            // Canonical-id rows only: a failed channel lookup would hide legacy
            // rows, so the probe fails (no choice) instead of undercounting.
            val channels = row?.let { idLookup.channels(it) }.orEmpty()
            val host = row?.let { idLookup.host(it) }
            body(SyncCategory.Favorites)?.let { json.decodeFromString<FavoritesSnapshot>(it) }?.let { f ->
                out["favorites"] = DriveCanon.favorites(f, DriveCanon.Ids(channels = channels))
            }
            body(SyncCategory.WatchProgress)?.let { json.decodeFromString<WatchProgressSnapshot>(it) }?.let { w ->
                // Snapshots older than the id migration resolve their local ids here.
                val (movies, episodes) = DriveCanon.progressNeeds(w)
                val ids = row?.let { r ->
                    runCatching { DriveCanon.Ids(movies = idLookup.movies(r, movies), episodes = idLookup.episodes(r, episodes, network = true)) }.getOrNull()
                } ?: DriveCanon.Ids()
                out["progress"] = DriveCanon.progress(w, ids, now)
                carriedP = w
                carriedIds = carriedIds.copy(movies = carriedIds.movies + ids.movies, episodes = ids.episodes)
            }
            body(SyncCategory.Reminders)?.let { json.decodeFromString<RemindersSnapshot>(it) }?.let { r ->
                out["reminders"] = DriveCanon.reminders(r, now)
            }
            body(SyncCategory.Watchlist)?.let { json.decodeFromString<WatchlistSnapshot>(it) }?.let { w ->
                val ids = row?.let { r -> runCatching { DriveCanon.Ids(movies = idLookup.movies(r, DriveCanon.watchlistMovieNeeds(w))) }.getOrNull() }
                    ?: DriveCanon.Ids()
                out["watchlist"] = DriveCanon.watchlist(w, ids, host)
                out["hiddenTitles"] = DriveCanon.hiddenTitles(w, ids, host)
                carriedW = w
                carriedIds = carriedIds.copy(movies = carriedIds.movies + ids.movies)
            }
            // Section 10a: the rows the Drive copy carries with a canonical id are
            // representable here, so the Dispatcharr probe keeps them.
            row?.let { r -> dispatcharrSync.get().noteDriveItems(r.id, DriveCanon.carriedItems(carriedP, carriedW, carriedIds)) }
            body(SyncCategory.Preferences)?.let { json.decodeFromString<PreferencesSnapshot>(it) }?.let { p ->
                val ids = DriveCanon.Ids(channels = channels)
                out["recents"] = DriveCanon.recentsCategory(p, ids)
                // Recent channels the Drive copy carries are representable here too (10a).
                row?.let { r -> dispatcharrSync.get().noteDriveItems(r.id, DriveCanon.carriedItems(carriedP, carriedW, carriedIds) + DriveCanon.recentChannelItems(p, ids)) }
                out["collections"] = DriveCanon.collectionsCategory(p, ids)
                out["hiddenGroups"] = DriveCanon.hiddenGroupsCategory(p)
                out["groupOrder"] = DriveCanon.groupOrderCategory(p)
                // The wire form a push sends (typed by the registry), the same
                // input the Dispatcharr side fingerprints (DriveCanon.dsyncSettings).
                val lanes = appPreferences.driveLanesAsWire(
                    mapOf(SyncScope.LANE_ALL to p.keys, SyncScope.LANE_TV to p.tv, SyncScope.LANE_MOBILE to p.mobile),
                    row?.id,
                )
                val (n, f) = DriveCanon.dsyncSettings(DriveCanon.lanesOn(lanes) { SetupSourceChoice.nameOff(scopes, it) })
                out["settings"] = SetupSourceChoice.Category(n, p.envelope.lastModified, f)
            }
        }
        val kept = SetupSourceChoice.forDevice(SetupSourceChoice.Summary(out), { SetupSourceChoice.itemOff(scopes, it) }).categories
        Log.i(TAG, "[DRIVE] setup probe ${kept.entries.joinToString { "${it.key}=${it.value.rows}" }.ifEmpty { "empty" }}")
        SetupSourceChoice.Summary(kept, lastWrite = kept.values.maxOfOrNull { it.updatedAt })
    }.onFailure { Log.w(TAG, SetupSourceChoice.driveProbeFailedLine(it)) }.getOrNull()

    // ── Rule 5: convergence with Dispatcharr Sync ─────────────────────────

    /**
     * A Rule 4 "Which setup" prompt is open or unanswered this app session
     * (SetupSourceCoordinator). Converge never runs then: the prompt decides.
     */
    @Volatile var setupChoicePending = false

    private val convergeState = context.getSharedPreferences("aerio_converge", Context.MODE_PRIVATE)
    private fun convergeKey(pid: String, name: String) = "conv|$pid|$name"

    /**
     * Rule 5 (docs/DispatcharrSyncSchema.md 10a): with both providers on and
     * the setup choice settled, Dispatcharr is the source of truth for the
     * shared categories. [dsync] is the summary of the server copy the
     * Dispatcharr pull just read; [skip] are categories with a local change
     * still on its way there. Reads the Drive files once, and pushes this
     * device's (Dispatcharr-derived) copy of each differing file to Drive.
     * Never prompts and never pulls Drive. A category pushed once for a given
     * Dispatcharr state is not pushed again until that state changes, so it
     * cannot ping-pong. Returns the categories pushed.
     */
    suspend fun converge(playlistId: String, dsync: SetupSourceChoice.Summary, skipReasons: Map<String, String>): List<String> {
        val skip = skipReasons.keys
        // Next cycle after a failed choice pull: retry pull then replace.
        if (replaceDeferred()) {
            runCatching { setupCoordinator.get().retryDeferredReplace() }
                .onFailure { Log.w(TAG, "[DRIVE] replace retry failed: ${it.javaClass.simpleName}") }
            return emptyList()
        }
        if (setupChoicePending || sharedHeld()) return emptyList()
        if (com.aeriotv.android.core.profiles.ProfileScope.token.value.isNotEmpty()) return emptyList()
        val gates = kotlinx.coroutines.withContext(com.aeriotv.android.core.profiles.ProfileScope.defaultOnly()) {
            appPreferences.syncMasterEnabled.first() && appPreferences.syncInitialPullDone.first()
        }
        if (!gates) return emptyList()
        if (!runCatching { dispatcharrSync.get().ownsCategory(SyncCategory.Favorites) }.getOrDefault(false)) return emptyList()
        // Unknown Drive: no converge push. Retry the setup probe instead; it
        // decides once Drive answers.
        if (driveUnknown()) {
            if (!_needsSignIn.value) runCatching { setupCoordinator.get().retryUnknownDrive() }
                .onFailure { Log.w(TAG, "[SYNC] setup probe retry failed: ${it.javaClass.simpleName}") }
            return emptyList()
        }
        val token = ensureSignedIn() ?: return emptyList()
        val d = readSharedSummary(token) ?: run {
            Log.i(TAG, "[SYNC] converge skipped: Drive unreadable (unknown, not empty)")
            return emptyList()
        }
        val marked = SetupSourceChoice.SHARED.mapNotNull { n -> convergeState.getString(convergeKey(playlistId, n), null)?.let { n to it } }.toMap()
        // A mark means "this Dispatcharr state was pushed once and Drive still
        // differs". It stands only while this device's push would still carry
        // what Drive holds; local rows that changed since (held rows resolved,
        // a pull applied) clear it, so untranslatable means rows that cannot
        // exist here (section 10a).
        val stale = SetupSourceChoice.convergeSkips(d, dsync, skipReasons, marked).filter { it.second == "untranslatable" }.map { it.first }
            .filter { n ->
                val file = SetupSourceChoice.driveFile(n) ?: return@filter false
                val now = currentSharedCategories(file)[n]
                now != null && !SetupSourceChoice.categoryEqual(now, d.categories[n])
            }
        stale.forEach { Log.i(TAG, "[SYNC] converge mark cleared $it (local copy changed)") }
        val last = marked - stale.toSet()
        val plan = SetupSourceChoice.convergePlan(d, dsync, skip, last)
        val skipped = SetupSourceChoice.convergeSkips(d, dsync, skipReasons, last)
        skipped.forEach { (n, why) -> Log.i(TAG, SetupSourceChoice.convergeSkipLine(n, why, d.categories[n], dsync.categories[n])) }
        if (plan.isEmpty()) {
            val n = SetupSourceChoice.SHARED.count { it in d.categories || it in dsync.categories }
            if (skipped.isEmpty()) Log.i(TAG, SetupSourceChoice.convergeEqualLine(n, skipped))
            else Log.i(TAG, "[SYNC] converge nothing pushed categories=$n differing=${skipped.size}")
            return emptyList()
        }
        plan.forEach { Log.i(TAG, SetupSourceChoice.convergeLine(it, d.categories[it], dsync.categories[it])) }
        val files = plan.mapNotNull { SetupSourceChoice.driveFile(it) }.distinct()
        val enabledFiles = files.filter { appPreferences.syncCategoryEnabled(it).first() }
        val ok = enabledFiles.associateWith { c ->
            // A file still waiting (fresh-device order) is not marked pushed, so the next converge retries it.
            (runCatching { pushCategory(token, c, "converge") }.onFailure { Log.w(TAG, "[SYNC] converge push ${c.name} failed: ${it.javaClass.simpleName}") }.getOrDefault(false)) &&
                !replaceWaiting(c)
        }
        val e = convergeState.edit()
        plan.forEach { n ->
            if (ok[SetupSourceChoice.driveFile(n)] == true) e.putString(convergeKey(playlistId, n), SetupSourceChoice.convergeMark(dsync.categories[n]))
        }
        e.commit()
        if (ok.values.any { it }) appPreferences.setSyncLastPushAt(System.currentTimeMillis())
        return plan.filter { ok[SetupSourceChoice.driveFile(it)] == true }
    }

    // ── Rule 4 canonical ids (DriveCanon) ─────────────────────────────────

    /**
     * Every push writes the Dispatcharr ids next to the local ids when the
     * active playlist is Dispatcharr. Lookups are local (channel snapshot,
     * VOD catalog, saved episode map); an id that does not resolve stays
     * null and never holds the push.
     */
    private suspend fun withCanonicalIds(s: FavoritesSnapshot): FavoritesSnapshot = runCatching {
        val row = idLookup.activeDispatcharr() ?: return s
        DriveCanon.enrichFavorites(s, DriveCanon.Ids(channels = idLookup.channels(row))).first
    }.getOrDefault(s)

    private suspend fun withCanonicalIds(s: WatchProgressSnapshot): WatchProgressSnapshot = runCatching {
        val row = idLookup.activeDispatcharr() ?: return s
        val (movies, episodes) = DriveCanon.progressNeeds(s)
        DriveCanon.enrichProgress(s, DriveCanon.Ids(movies = idLookup.movies(row, movies), episodes = idLookup.episodes(row, episodes, network = false))).first
    }.onFailure {
        if (it is kotlinx.coroutines.CancellationException) throw it
        Log.w(TAG, "[DRIVE] progress canonical ids failed: ${it.javaClass.simpleName}")
    }.getOrDefault(s)

    /**
     * Section 10a: Dispatcharr rows this device holds by canonical id only
     * (no local uuid yet) ride in the snapshot as placeholder rows, so the
     * Drive copy carries every row the Dispatcharr copy holds here.
     */
    /**
     * THE Drive WatchProgress snapshot (section 10a, single builder): every
     * push entry point (replace, converge, follow local, follow pull, the
     * periodic and one-shot worker, Push to Drive) and the converge mark check
     * build it here, so held placeholder rows and the Dispatcharr rows this
     * device last read always ride with the local rows (DsyncHeld.driveSnapshot).
     * The Dispatcharr rows come from the Dispatcharr Sync session, not from a
     * lookup that can fail: a canonical-id lookup that failed in one run
     * (field run 2026-11-01: the local row existed, yet the push counted 0)
     * no longer drops a row from Drive.
     */
    private suspend fun driveProgressSnapshot(why: String?): WatchProgressSnapshot {
        val local = buildWatchProgressSnapshot()
        val enriched = withCanonicalIds(local)
        val d = runCatching { dispatcharrSync.get() }.getOrNull()
        val (pid, extra) = runCatching { d?.driveProgressRows() }.getOrNull() ?: (null to emptyList())
        val active = runCatching { idLookup.activeDispatcharr()?.id }.getOrNull()
        // Only the active Dispatcharr playlist's rows (all media is scoped to the active playlist).
        val rows = if (pid != null && (active == null || active == pid)) extra else emptyList()
        val out = com.aeriotv.android.core.sync.dsync.DsyncHeld.driveSnapshot(enriched, rows) { t, id -> d?.cachedUuid(t, id) }
        if (why != null) {
            val canonical = runCatching { DriveCanon.progress(out, DriveCanon.Ids(), System.currentTimeMillis()).rows }.getOrDefault(-1)
            val ph = out.entries.count { com.aeriotv.android.core.sync.dsync.DsyncHeld.isPlaceholder(it.videoId) }
            Log.i(TAG, com.aeriotv.android.core.sync.dsync.DsyncHeld.snapshotLine(why, local.entries.size, canonical, rows.size, ph))
        }
        return out
    }

    private suspend fun withCanonicalIds(s: WatchlistSnapshot): WatchlistSnapshot = runCatching {
        val row = idLookup.activeDispatcharr() ?: return s
        DriveCanon.enrichWatchlist(s, DriveCanon.Ids(movies = idLookup.movies(row, DriveCanon.watchlistMovieNeeds(s)))).first
    }.getOrDefault(s)

    private suspend fun withCanonicalIds(s: PreferencesSnapshot): PreferencesSnapshot = runCatching {
        val row = idLookup.activeDispatcharr() ?: return s
        DriveCanon.enrichPreferences(s, DriveCanon.Ids(channels = idLookup.channels(row))).first
    }.getOrDefault(s)

    private val idMigration = context.getSharedPreferences("aerio_drive_ids", Context.MODE_PRIVATE)

    /** The shared files the backup update rewrites. */
    private val updateFiles = listOf(
        SyncCategory.Favorites, SyncCategory.WatchProgress, SyncCategory.Reminders,
        SyncCategory.Watchlist, SyncCategory.Preferences,
    )

    /**
     * Backup update (Rule 4), only after the user's "Update backup": rewrite
     * each shared Drive file once at format 2, keeping its envelope time and
     * every other field. With an active Dispatcharr playlist the Dispatcharr
     * ids are filled where they resolve; the channel categories wait for the
     * channel list and the VOD categories for the movie catalog
     * (DsyncReadiness), flag unset, and finish on a later sync. Any other
     * playlist type rewrites at once with the ids left null. Gated per base
     * user (active playlist) and category by a saved flag. Logs counts only.
     */
    suspend fun migrateCanonicalIds(token: String, enabled: Set<SyncCategory>) =
        kotlinx.coroutines.withContext(com.aeriotv.android.core.profiles.ProfileScope.defaultOnly()) {
            // Not gated on the Rule 4 hold: the rewrite carries the Drive file's
            // own rows (format stamp and ids only), never this device's data, and
            // the welcome flow offers "Update backup" while the hold is on.
            if (com.aeriotv.android.core.profiles.ProfileScope.token.value.isNotEmpty()) return@withContext
            val key = idLookup.activeKey()
            if (!idUpdateAccepted(key)) return@withContext
            val row = idLookup.activeDispatcharr()
            // Every shared file, Sync Category on or off: the offer reads all of
            // them (backupOutdated), so a skipped file would bring it back.
            val cats = DriveCanon.pendingCategories(true, updateFiles, updateFiles.toSet()) { idMigration.getBoolean(migrationKey(key, it), false) }
            if (cats.isEmpty()) return@withContext
            val files = driveClient.listAll(token).associate { (id, name) -> name to id }
            cats.forEach { c ->
                runCatching { if (migrateCategory(token, row, c, files[c.fileName])) idMigration.edit().putBoolean(migrationKey(key, c), true).commit() }
                    .onFailure { Log.w(TAG, "[DRIVE] id migration ${c.name} failed: ${it.javaClass.simpleName}") }
            }
        }

    private val retryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    @Volatile private var retryStarted = false

    /** Categories still waiting after "Update backup" (none when not accepted). */
    private suspend fun pendingIdUpdate(): List<SyncCategory> =
        kotlinx.coroutines.withContext(com.aeriotv.android.core.profiles.ProfileScope.defaultOnly()) {
            val key = idLookup.activeKey()
            DriveCanon.pendingCategories(idUpdateAccepted(key), updateFiles, updateFiles.toSet()) { idMigration.getBoolean(migrationKey(key, it), false) }
        }

    private suspend fun retryIdUpdate(reason: String) {
        if (!appPreferences.syncMasterEnabled.first()) return
        if (pendingIdUpdate().isEmpty()) return
        val row = kotlinx.coroutines.withContext(com.aeriotv.android.core.profiles.ProfileScope.defaultOnly()) { idLookup.activeDispatcharr() }
        // Catalog page ticks: only once the movie catalog is built (launch always tries).
        if (reason == "catalog" && row != null && !idLookup.moviesReady(row)) return
        val token = ensureSignedIn() ?: return
        Log.i(TAG, "[DRIVE] id migration retry ($reason)")
        val enabled = SyncCategory.entries.filter { appPreferences.syncCategoryEnabled(it).first() }.toSet()
        runCatching { migrateCanonicalIds(token, enabled) }
    }

    /**
     * Retries a deferred backup update without waiting for the 6-hour worker:
     * once at app launch, and on the VOD catalog page signal Dispatcharr
     * Sync uses (VodIdSink.pageWritten), debounced by
     * DsyncReadiness.PAGE_RECHECK_MS so it runs seconds after the sweep ends.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun startIdUpdateRetry() {
        if (retryStarted) return
        retryStarted = true
        retryScope.launch { runCatching { retryIdUpdate("launch") } }
        retryScope.launch {
            com.aeriotv.android.core.sync.dsync.VodIdSink.pageWritten
                .debounce(com.aeriotv.android.core.sync.dsync.DsyncReadiness.PAGE_RECHECK_MS)
                .collect { runCatching { retryIdUpdate("catalog") } }
        }
    }

    private fun migrationKey(key: String, c: SyncCategory) = "idmig|$key|default|${c.storageSuffix}"

    fun idUpdateAccepted(key: String): Boolean = idMigration.getBoolean("idmig_ok|$key|default", false)

    /** Whether any shared Drive file is old format (DriveCanon.backupOutdated). Read only; false when Drive cannot be read. */
    suspend fun backupOutdated(token: String): Boolean = runCatching {
        val listing = driveClient.listAll(token)
        listing.groupingBy { it.second }.eachCount().filterValues { it > 1 }.takeIf { it.isNotEmpty() }
            ?.let { d -> Log.i(TAG, "[DRIVE] duplicate files: ${d.entries.joinToString(",") { "${it.key}=${it.value}" }}") }
        val files = listing.associate { (id, name) -> name to id }
        val formats = LinkedHashMap<String, Int?>()
        updateFiles.forEach { c ->
            val id = files[c.fileName] ?: return@forEach
            val body = driveClient.download(token, id) ?: return@forEach
            formats[c.name] = envelopeFormat(body)
        }
        val old = DriveCanon.oldFormatNames(formats)
        if (old.isNotEmpty()) Log.i(TAG, "[DRIVE] backup update needed: categories=${old.joinToString(",")}")
        DriveCanon.backupOutdated(formats.values.toList())
    }.getOrDefault(false)

    @kotlinx.serialization.Serializable
    private data class EnvelopeOnly(val envelope: SyncEnvelope)

    private fun envelopeFormat(body: String): Int? = runCatching { json.decodeFromString<EnvelopeOnly>(body).envelope.format }.getOrNull()

    /** The user chose "Update backup": remember it (waiting categories finish on later syncs) and run now. */
    suspend fun acceptIdUpdate(key: String) {
        idMigration.edit().putBoolean("idmig_ok|$key|default", true).commit()
        Log.i(TAG, "[DRIVE] backup update accepted")
        val token = ensureSignedIn() ?: return
        val enabled = SyncCategory.entries.filter { appPreferences.syncCategoryEnabled(it).first() }.toSet()
        runCatching { migrateCanonicalIds(token, enabled) }
    }

    /** Offer check: the base user key (active playlist id) when the backup is old format and not yet accepted, else null. */
    suspend fun idUpdateCandidate(): String? {
        val key = kotlinx.coroutines.withContext(com.aeriotv.android.core.profiles.ProfileScope.defaultOnly()) { idLookup.activeKey() }
        if (idUpdateAccepted(key)) return null
        val token = ensureSignedIn() ?: return null
        return key.takeIf { backupOutdated(token) }
    }

    /** True when done for good (written, already current, or no file); false to try again later. */
    private suspend fun migrateCategory(
        token: String,
        row: PlaylistEntity?,
        c: SyncCategory,
        fileId: String?,
    ): Boolean {
        fileId ?: return true
        if (c !in updateFiles) return true
        val body = driveClient.download(token, fileId) ?: return false
        val wasOld = DriveCanon.isOldFormat(envelopeFormat(body))
        fun SyncEnvelope.v2() = copy(format = DriveCanon.FORMAT)
        // The id fill waits (channel list, movie catalog) or the body does not
        // decode: still stamp format 2 now so no old-format file remains after
        // "Update backup"; the done flag stays unset and the ids fill later.
        suspend fun stampOnly(why: String): Boolean {
            if (wasOld) {
                val stamped = DriveCanon.stampFormat(body)
                val ok = stamped != null && driveClient.upload(token, c.fileName, stamped).isSuccess
                Log.i(TAG, "[DRIVE] id migration: ${c.name} format stamped ok=$ok ($why)")
            }
            return false
        }
        val (payload: String, filled: Int) = runCatching { migratedPayload(c, body, row) { it.v2() } }
            .getOrElse { return stampOnly("decode ${it.javaClass.simpleName}") }
            ?: return stampOnly("ids waiting")
        if (!wasOld && filled == 0) return true
        return driveClient.upload(token, c.fileName, payload).isSuccess
    }

    /** The rewritten file and rows filled; null when the id fill has to wait. */
    private suspend fun migratedPayload(
        c: SyncCategory,
        body: String,
        row: PlaylistEntity?,
        v2: (SyncEnvelope) -> SyncEnvelope,
    ): Pair<String, Int>? {
        fun SyncEnvelope.v2() = v2(this)
        return when (c) {
            SyncCategory.Favorites -> {
                val f = json.decodeFromString<FavoritesSnapshot>(body)
                val ch = row?.let { idLookup.channels(it) }.orEmpty()
                // Channel ids wait for the channel list, as Dispatcharr Sync does.
                if (row != null && ch.isEmpty() && f.entries.isNotEmpty()) return null
                val (s, n) = DriveCanon.enrichFavorites(f, DriveCanon.Ids(channels = ch))
                if (row != null) Log.i(TAG, DriveCanon.migrationLine("favorites", n))
                json.encodeToString(s.copy(envelope = s.envelope.v2())) to n
            }
            SyncCategory.WatchProgress -> {
                val w = json.decodeFromString<WatchProgressSnapshot>(body)
                var ids = DriveCanon.Ids()
                if (row != null) {
                    val (movies, episodes) = DriveCanon.progressNeeds(w)
                    ids = DriveCanon.Ids(movies = idLookup.movies(row, movies), episodes = idLookup.episodes(row, episodes, network = true))
                    if (com.aeriotv.android.core.sync.dsync.DsyncReadiness.decide(
                            com.aeriotv.android.core.sync.dsync.DsyncKey.Progress, idLookup.moviesReady(row), movies.count { it !in ids.movies },
                        ) == com.aeriotv.android.core.sync.dsync.DsyncReadiness.Decision.Defer
                    ) {
                        Log.i(TAG, "[DRIVE] id migration: progress waiting for the VOD catalog")
                        return null
                    }
                }
                val (s, n) = DriveCanon.enrichProgress(w, ids)
                if (row != null) Log.i(TAG, DriveCanon.migrationLine("progress", n))
                json.encodeToString(s.copy(envelope = s.envelope.v2())) to n
            }
            SyncCategory.Reminders -> {
                val r = json.decodeFromString<RemindersSnapshot>(body)
                json.encodeToString(r.copy(envelope = r.envelope.v2())) to 0
            }
            SyncCategory.Watchlist -> {
                val w = json.decodeFromString<WatchlistSnapshot>(body)
                var ids = DriveCanon.Ids()
                if (row != null) {
                    val needs = DriveCanon.watchlistMovieNeeds(w)
                    ids = DriveCanon.Ids(movies = idLookup.movies(row, needs))
                    if (com.aeriotv.android.core.sync.dsync.DsyncReadiness.decide(
                            com.aeriotv.android.core.sync.dsync.DsyncKey.Watchlist, idLookup.moviesReady(row), needs.count { it !in ids.movies },
                        ) == com.aeriotv.android.core.sync.dsync.DsyncReadiness.Decision.Defer
                    ) {
                        Log.i(TAG, "[DRIVE] id migration: watchlist waiting for the VOD catalog")
                        return null
                    }
                }
                val (s, nw, nh) = DriveCanon.enrichWatchlist(w, ids)
                if (row != null) {
                    Log.i(TAG, DriveCanon.migrationLine("watchlist", nw))
                    Log.i(TAG, DriveCanon.migrationLine("hiddenTitles", nh))
                }
                json.encodeToString(s.copy(envelope = s.envelope.v2())) to (nw + nh)
            }
            SyncCategory.Preferences -> {
                val p = json.decodeFromString<PreferencesSnapshot>(body)
                val ch = row?.let { idLookup.channels(it) }.orEmpty()
                if (row != null && ch.isEmpty() && DriveCanon.referencedChannels(p).isNotEmpty()) return null
                val (s, n) = DriveCanon.enrichPreferences(p, DriveCanon.Ids(channels = ch))
                if (row != null) Log.i(TAG, DriveCanon.migrationLine("recents and collections", n))
                json.encodeToString(s.copy(envelope = s.envelope.v2())) to n
            }
            else -> null
        }
    }

    // ── Snapshot builders ─────────────────────────────────────────────────

    /** The one envelope every push of every file carries (format 2, DriveCanon.writerEnvelope). */
    private fun envelope() = DriveCanon.writerEnvelope(
        System.currentTimeMillis(), "${Build.MANUFACTURER} ${Build.MODEL}".trim(), pushProviders,
    )

    private suspend fun buildPlaylistsSnapshot(): PlaylistsSnapshot {
        val rows = playlistDao.allOnce()
        val active = rows.firstOrNull { it.isActive }?.id
        // Drop sensitive creds from this snapshot, they go in Credentials.
        return PlaylistsSnapshot(
            envelope = envelope(),
            active = active,
            playlists = rows.map { e ->
                PlaylistSnapshotEntry(
                    id = e.id,
                    name = e.name,
                    sourceType = e.sourceType,
                    urlString = e.urlString,
                    epgUrlString = e.epgUrl,
                    lanUrlString = e.lanUrlString,
                    username = null,
                    apiKey = null,
                    lastUsedMillis = e.lastRefreshedAt,
                    dispatcharrAccountProfileIds = e.dispatcharrAccountProfileIds,
                    vodEnabled = e.vodEnabled,
                )
            },
        )
    }

    /** Task #52: favorites + the user's manual order (iOS parity). */
    private suspend fun buildWatchlistSnapshot(): WatchlistSnapshot =
        WatchlistSnapshot(
            envelope = envelope(),
            entries = watchlistStore.allOnce().map { e ->
                WatchlistSnapshotEntry(
                    vodId = e.key.substringAfter(':'),
                    vodType = if (e.isMovie) "movie" else "series",
                    title = e.title,
                    posterUrl = e.posterUrl,
                    releaseYear = e.year?.toString() ?: "",
                    rating = e.rating ?: "",
                    serverId = e.playlistId,
                    addedAt = e.addedAt,
                    removedAt = e.removedAt,
                )
            },
            // Hidden Titles off in Sync Categories: send none (readers treat an
            // empty list as "nothing to merge", so other devices keep theirs).
            hidden = if (!appPreferences.syncItemOnOnce("hiddenTitles")) emptyList() else hiddenTitlesStore.allOnce().map { e ->
                HiddenTitleSnapshotEntry(
                    vodId = e.key.substringAfter(':'),
                    vodType = if (e.key.startsWith("s:")) "series" else "movie",
                    serverId = e.playlistId,
                    hiddenAt = e.hiddenAt,
                    unhiddenAt = e.unhiddenAt,
                )
            },
        )

    /** Tombstone-aware merge; see WatchlistStore.mergeRemote. */
    private suspend fun applyWatchlistSnapshot(snapshot: WatchlistSnapshot) {
        watchlistStore.mergeRemote(
            snapshot.entries.map { r ->
                val movie = r.vodType != "series"
                com.aeriotv.android.core.preferences.WatchlistStore.Entry(
                    key = (if (movie) "m:" else "s:") + r.vodId,
                    title = r.title,
                    posterUrl = r.posterUrl,
                    year = r.releaseYear.toIntOrNull(),
                    rating = r.rating.ifBlank { null },
                    isMovie = movie,
                    playlistId = r.serverId,
                    addedAt = r.addedAt,
                    removedAt = r.removedAt,
                )
            },
        )
        // Absent in payloads written before hidden titles shipped, in which
        // case this is an empty list and the local rows are left alone.
        if (appPreferences.syncItemOnOnce("hiddenTitles")) hiddenTitlesStore.mergeRemote(
            snapshot.hidden.map { r ->
                com.aeriotv.android.core.preferences.HiddenTitlesStore.Entry(
                    key = (if (r.vodType == "series") "s:" else "m:") + r.vodId,
                    playlistId = r.serverId,
                    hiddenAt = r.hiddenAt,
                    unhiddenAt = r.unhiddenAt,
                )
            },
        )
    }

    private suspend fun buildFavoritesSnapshot(): FavoritesSnapshot {
        val rows = favoriteChannelDao.allOnce()
        return FavoritesSnapshot(
            envelope = envelope(),
            entries = rows.map { row ->
                FavoriteSnapshotEntry(
                    channelId = row.channelId,
                    channelName = row.channelName,
                    displayOrder = row.displayOrder,
                    addedAt = row.addedAt,
                )
            },
        )
    }

    private suspend fun buildWatchProgressSnapshot(): WatchProgressSnapshot {
        val rows = watchProgressDao.allOnce()
        return WatchProgressSnapshot(
            envelope = envelope(),
            // URL-keyed rows never leave the device: they are device- and
            // host-specific (LAN vs WAN base, file:// paths) so they can't
            // match anywhere else, and they can leak hosts into the
            // snapshot. Stable ids (movie/episode UUIDs, dvr-<id>) sync.
            entries = rows.filter { !it.videoId.contains("://") }.map { row ->
                WatchProgressSnapshotEntry(
                    videoId = row.videoId,
                    title = row.title,
                    posterUrl = row.posterUrl,
                    positionMs = row.positionMs,
                    durationMs = row.durationMs,
                    updatedAt = row.updatedAt,
                    vodType = row.vodType,
                    seriesId = row.seriesId,
                    seasonNumber = row.seasonNumber,
                    episodeNumber = row.episodeNumber,
                    isFinished = row.isFinished,
                )
            },
        )
    }

    private suspend fun buildRemindersSnapshot(): RemindersSnapshot {
        val rows = reminderDao.allOnce()
        return RemindersSnapshot(
            envelope = envelope(),
            entries = rows.map { row ->
                ReminderSnapshotEntry(
                    reminderKey = row.reminderKey,
                    channelName = row.channelName,
                    programTitle = row.programTitle,
                    startMillis = row.startMillis,
                    endMillis = row.endMillis,
                )
            },
        )
    }

    private suspend fun buildPreferencesSnapshot(): PreferencesSnapshot {
        // Synced subset, theme, default tab, palette overrides, UI toggles.
        // Avoids syncing network/device-local settings (buffer size, custom
        // DVR folder, network timeout) which are sensible per-device.
        val lanes = appPreferences.snapshotSyncablePreferences()
        return PreferencesSnapshot(envelope = envelope(), keys = lanes.v, tv = lanes.tv, mobile = lanes.mobile)
    }

    private suspend fun buildCredentialsSnapshot(): CredentialsSnapshot {
        // playlistDao is the EncryptingPlaylistDao decorator, so allOnce() yields
        // CLEARTEXT credentials (decrypted from their at-rest ciphertext). They
        // are uploaded to Drive AppData in cleartext by design; see the trust
        // model on CredentialsSnapshotEntry (audit task #53). The matching pull
        // path (applyCredentialsSnapshot) writes back through the same decorator,
        // re-encrypting at rest on the receiving device.
        val rows = playlistDao.allOnce()
        return CredentialsSnapshot(
            envelope = envelope(),
            entries = rows.mapNotNull { row ->
                if (row.username == null && row.apiKey == null && row.password == null) null
                else CredentialsSnapshotEntry(
                    playlistId = row.id,
                    username = row.username,
                    password = row.password,
                    apiKey = row.apiKey,
                )
            },
        )
    }

    // ── Snapshot appliers ─────────────────────────────────────────────────

    private suspend fun applyPlaylistsSnapshot(snapshot: PlaylistsSnapshot, replace: Boolean = false) {
        if (replace && snapshot.playlists.isNotEmpty()) {
            // Replace: a local playlist the snapshot does not carry is removed,
            // through the same cleanup a manual delete runs. An EMPTY snapshot
            // never wipes the device (a blank sender is not a real backup).
            val keep = snapshot.playlists.map { it.id }.toSet()
            playlistDao.allOnce().filter { it.id !in keep }.forEach { row ->
                Log.i(TAG, "pull replace: removing local playlist ${row.id}")
                playlistRepository.get().deletePlaylist(row.id)
                    .onFailure { Log.w(TAG, "pull replace: delete ${row.id} failed", it) }
            }
        }
        mergePlaylistsSnapshot(snapshot)
    }

    private suspend fun mergePlaylistsSnapshot(snapshot: PlaylistsSnapshot) = database.withTransaction {
        val existing = playlistDao.allOnce().associateBy { it.id }
        snapshot.playlists.forEach { entry ->
            val current = existing[entry.id]
            val merged = (current ?: PlaylistEntity(id = entry.id, name = entry.name, urlString = entry.urlString)).copy(
                name = entry.name,
                urlString = entry.urlString,
                sourceType = entry.sourceType,
                epgUrl = entry.epgUrlString,
                // Old snapshots have no lanUrlString; keep whatever this
                // device already has instead of wiping it.
                lanUrlString = entry.lanUrlString ?: current?.lanUrlString,
                // isActive is reconciled below so EXACTLY one row ends up active;
                // don't trust the per-row flag here. Keep the existing flag for
                // now (overwritten by switchActive).
                isActive = current?.isActive ?: false,
                // Adopt the synced child-safety account-profile filter. Empty
                // from an older sender keeps this device's own value rather than
                // wiping a good fail-closed snapshot; the next live load
                // re-captures the real value regardless.
                dispatcharrAccountProfileIds =
                    entry.dispatcharrAccountProfileIds.takeIf { it.isNotBlank() }
                        ?: current?.dispatcharrAccountProfileIds ?: "",
                // Task #52: per-playlist On Demand opt-in follows the sync
                // (iOS ServerConnection.vodEnabled parity). Old snapshots
                // decode with the default true, matching the entity default.
                vodEnabled = entry.vodEnabled,
            )
            playlistDao.upsert(merged)
        }
        // Enforce the single-active-row invariant that bootstrap relies on.
        // The old code set isActive per row from snapshot.active, which left the
        // app with TWO active rows when a local row outside the snapshot stayed
        // active, or ZERO active when snapshot.active was missing/stale (deleted
        // playlist). switchActive() deactivates every row and activates exactly
        // one in a single transaction. Prefer the synced active id; fall back to
        // the first row by display order so there is always exactly one active
        // when any playlist exists.
        val all = playlistDao.allOnce()
        if (all.isNotEmpty()) {
            // The active playlist is a PER-DEVICE choice (Streamer 2026-09-01:
            // this device kept reverting to the "Kids" playlist because another
            // device's snapshot said so). Keep this device's own active row when
            // it still exists; consult the synced active id only when nothing
            // here is active (first sync / the active playlist was deleted).
            val localActive = all.firstOrNull { it.isActive }?.id
            val targetId = localActive
                ?: all.firstOrNull { it.id == snapshot.active }?.id
                ?: all.minByOrNull { it.displayOrder }?.id
                ?: all.first().id
            playlistDao.switchActive(targetId)
        }
    }

    private suspend fun applyWatchProgressSnapshot(snapshot: WatchProgressSnapshot) {
        snapshot.entries.forEach { remote ->
            // URL-keyed rows are legacy identity (dropped locally by
            // MIGRATION_23_24 and excluded from pushes), but snapshots
            // written by pre-fix builds still carry them and re-imported
            // them on every restore (field find 2026-08-31: fresh install
            // came back with http://...recordings/93/hls/... rows). Skip
            // on apply too; the next push writes a clean snapshot.
            if (remote.videoId.contains("://")) return@forEach
            // Held placeholders have no local title yet (holdPlaceholders keeps them).
            if (com.aeriotv.android.core.sync.dsync.DsyncHeld.isPlaceholder(remote.videoId)) return@forEach
            val local = watchProgressDao.getOnce(remote.videoId)
            if (local == null || remote.updatedAt > local.updatedAt) {
                // The snapshot only carries the synced position fields, so MERGE
                // onto any existing local row to preserve its local-only episode
                // metadata (vodType, seriesId, season/episode, streamUrl,
                // isFinished, upNextQueue) and playlistId. Matches iOS, where the
                // iCloud merge only overwrites the synced fields (Issue #19).
                val base = local ?: WatchProgressEntity(
                    videoId = remote.videoId,
                    title = remote.title,
                    posterUrl = remote.posterUrl,
                    positionMs = remote.positionMs,
                    durationMs = remote.durationMs,
                    updatedAt = remote.updatedAt,
                    // Fresh device: classification comes from the wire so the
                    // row lands in the right rail (recordings/episodes were
                    // arriving typed "movie" before the fields were synced).
                    vodType = remote.vodType,
                    seriesId = remote.seriesId,
                    seasonNumber = remote.seasonNumber,
                    episodeNumber = remote.episodeNumber,
                    isFinished = remote.isFinished,
                )
                watchProgressDao.upsert(
                    base.copy(
                        title = remote.title,
                        posterUrl = remote.posterUrl ?: base.posterUrl,
                        positionMs = remote.positionMs,
                        durationMs = remote.durationMs,
                        updatedAt = remote.updatedAt,
                        vodType = remote.vodType,
                        seriesId = remote.seriesId ?: base.seriesId,
                        seasonNumber = if (remote.seasonNumber > 0) remote.seasonNumber else base.seasonNumber,
                        episodeNumber = if (remote.episodeNumber > 0) remote.episodeNumber else base.episodeNumber,
                        isFinished = remote.isFinished || base.isFinished,
                    ),
                )
            }
        }
    }

    /**
     * Task #52: additive favorites merge, mirroring the reminders policy: a
     * remote favorite this device lacks is added with its synced order; a
     * favorite both sides have adopts the remote order only when the remote
     * snapshot is fresher than the local add (so a reorder travels without a
     * local drag being clobbered by a stale snapshot). Removals deliberately
     * do NOT propagate - same create-only limitation as reminders (audit #24
     * design note): a device can't tell "removed elsewhere" from "not added
     * here yet" without tombstones.
     */
    private suspend fun applyFavoritesSnapshot(snapshot: FavoritesSnapshot) {
        snapshot.entries.forEach { remote ->
            val local = favoriteChannelDao.getOnce(remote.channelId)
            if (local == null) {
                favoriteChannelDao.upsert(
                    FavoriteChannelEntity(
                        channelId = remote.channelId,
                        channelName = remote.channelName,
                        displayOrder = remote.displayOrder,
                        addedAt = remote.addedAt,
                    ),
                )
            } else if (snapshot.envelope.lastModified > local.addedAt &&
                local.displayOrder != remote.displayOrder
            ) {
                favoriteChannelDao.setDisplayOrder(remote.channelId, remote.displayOrder)
            }
        }
    }

    private suspend fun applyRemindersSnapshot(snapshot: RemindersSnapshot) {
        snapshot.entries.forEach { remote ->
            val local = reminderDao.getOnce(remote.reminderKey)
            if (local == null) {
                reminderDao.upsert(
                    ReminderEntity(
                        reminderKey = remote.reminderKey,
                        channelName = remote.channelName,
                        programTitle = remote.programTitle,
                        startMillis = remote.startMillis,
                        endMillis = remote.endMillis,
                        alarmRequestCode = remote.reminderKey.hashCode(),
                    ),
                )
            }
        }
    }

    private suspend fun applyPreferencesSnapshot(snapshot: PreferencesSnapshot, replace: Boolean = false) {
        appPreferences.applySyncedPreferences(
            com.aeriotv.android.core.preferences.AppPreferences.DriveLanes(snapshot.keys, snapshot.tv, snapshot.mobile),
            replace,
        )
    }

    // ── Rule 4 replace (the user chose Google Drive; DriveCanon.replace*) ──

    private suspend fun replaceProgress(s: WatchProgressSnapshot) {
        val now = System.currentTimeMillis()
        val local = watchProgressDao.allOnce().map { r ->
            WatchProgressSnapshotEntry(
                videoId = r.videoId, title = r.title, posterUrl = r.posterUrl, positionMs = r.positionMs,
                durationMs = r.durationMs, updatedAt = r.updatedAt, vodType = r.vodType, seriesId = r.seriesId,
                seasonNumber = r.seasonNumber, episodeNumber = r.episodeNumber, isFinished = r.isFinished,
            )
        }
        val gone = DriveCanon.progressReplaceDeletes(local, s, now)
        gone.forEach { watchProgressDao.delete(it) }
        Log.i(TAG, DriveCanon.replaceLine("progress", DriveCanon.progressCounted(local, now).size, DriveCanon.progress(s, DriveCanon.Ids(), now).rows, gone.size))
    }

    private suspend fun replaceReminders(s: RemindersSnapshot) {
        val now = System.currentTimeMillis()
        val rows = reminderDao.allOnce()
        val local = rows.map { ReminderSnapshotEntry(it.reminderKey, it.channelName, it.programTitle, it.startMillis, it.endMillis) }
        val gone = DriveCanon.reminderReplaceDeletes(local, s, now).toSet()
        rows.filter { it.reminderKey in gone }.forEach { r ->
            cancelReminderAlarm(r.alarmRequestCode)
            reminderDao.delete(r.reminderKey)
        }
        Log.i(TAG, DriveCanon.replaceLine("reminders", local.count { it.endMillis >= now }, DriveCanon.reminders(s, now).rows, gone.size))
    }

    /** Same PendingIntent identity the reminder scheduler uses (DispatcharrSyncManager.cancelAlarm). */
    private fun cancelReminderAlarm(requestCode: Int) {
        val mgr = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        val pi = android.app.PendingIntent.getBroadcast(
            context, requestCode,
            android.content.Intent(context, com.aeriotv.android.feature.reminders.ReminderBroadcastReceiver::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching { mgr.cancel(pi) }
    }

    private suspend fun replaceFavorites(s: FavoritesSnapshot) {
        val local = favoriteChannelDao.allOnce().map { it.channelId }
        val gone = DriveCanon.favoriteReplaceDeletes(local, s)
        gone.forEach { favoriteChannelDao.delete(it) }
        Log.i(TAG, DriveCanon.replaceLine("favorites", local.size, s.entries.size, gone.size))
    }

    /** Watchlist and hidden titles: the active playlist's scope (or unscoped rows) become the backup rows. */
    private suspend fun replaceWatchlist(s: WatchlistSnapshot) {
        val host = playlistDao.allOnce().firstOrNull { it.isActive }?.let { com.aeriotv.android.core.preferences.WatchlistStore.scopeFor(it) }
        val now = System.currentTimeMillis()
        val remote = s.entries.map { r ->
            val movie = r.vodType != "series"
            com.aeriotv.android.core.preferences.WatchlistStore.Entry(
                key = (if (movie) "m:" else "s:") + r.vodId, title = r.title, posterUrl = r.posterUrl,
                year = r.releaseYear.toIntOrNull(), rating = r.rating.ifBlank { null }, isMovie = movie,
                playlistId = r.serverId, addedAt = r.addedAt, removedAt = r.removedAt,
            )
        }
        var localLive = 0
        var removed = 0
        watchlistStore.rewrite { cur ->
            val inScope = { e: com.aeriotv.android.core.preferences.WatchlistStore.Entry -> e.playlistId == null || e.playlistId == host }
            localLive = DriveCanon.liveInScope(cur, { it.isLive }, inScope)
            val ids = remote.map { it.key to it.playlistId }.toSet()
            removed = cur.count { it.isLive && inScope(it) && (it.key to it.playlistId) !in ids }
            DriveCanon.replaceMarks(cur, remote, { it.key to it.playlistId }, { it.isLive }, inScope, { it.copy(removedAt = now) })
        }
        Log.i(TAG, DriveCanon.replaceLine("watchlist", localLive, DriveCanon.watchlist(s, DriveCanon.Ids(), host).rows, removed))
        if (!appPreferences.syncItemOnOnce("hiddenTitles")) return
        val hidden = s.hidden.map { r ->
            com.aeriotv.android.core.preferences.HiddenTitlesStore.Entry(
                key = (if (r.vodType == "series") "s:" else "m:") + r.vodId, playlistId = r.serverId,
                hiddenAt = r.hiddenAt, unhiddenAt = r.unhiddenAt,
            )
        }
        var hLocal = 0
        var hRemoved = 0
        hiddenTitlesStore.rewrite { cur ->
            val inScope = { e: com.aeriotv.android.core.preferences.HiddenTitlesStore.Entry -> e.playlistId == null || e.playlistId == host }
            hLocal = DriveCanon.liveInScope(cur, { it.isLive }, inScope)
            val ids = hidden.map { it.key to it.playlistId }.toSet()
            hRemoved = cur.count { it.isLive && inScope(it) && (it.key to it.playlistId) !in ids }
            DriveCanon.replaceMarks(cur, hidden, { it.key to it.playlistId }, { it.isLive }, inScope, { it.copy(unhiddenAt = now) })
        }
        Log.i(TAG, DriveCanon.replaceLine("hiddenTitles", hLocal, DriveCanon.hiddenTitles(s, DriveCanon.Ids(), host).rows, hRemoved))
    }

    private suspend fun applyCredentialsSnapshot(snapshot: CredentialsSnapshot) {
        snapshot.entries.forEach { entry ->
            val local = playlistDao.byId(entry.playlistId) ?: return@forEach
            playlistDao.upsert(
                local.copy(
                    username = entry.username,
                    password = entry.password,
                    apiKey = entry.apiKey,
                ),
            )
        }
    }

    sealed interface Status {
        object SignedOut : Status
        object MissingConfig : Status
        data class SignedIn(val accessToken: String) : Status
    }

    /** One line on the onboarding restore screen: a category and where its
     * pull currently stands. [detail] is an optional sub-line ("Nothing in
     * Drive yet" / "Couldn't restore"), mirroring the iOS SyncStage detail. */
    data class RestoreStep(
        val category: SyncCategory,
        val state: RestoreStepState = RestoreStepState.Pending,
        val detail: String? = null,
    )

    enum class RestoreStepState { Pending, Running, Done, Failed }

    /** Two outcomes from [requestDriveScope]: token in hand, or needs consent UI. */
    sealed interface RequestResult {
        data class Authorized(val accessToken: String) : RequestResult
        data class NeedsConsent(val intentSender: android.content.IntentSender) : RequestResult
        object Failed : RequestResult
    }

    companion object {
        private const val TAG = "DriveSyncManager"
        /** Conservative cache lifetime for a persisted access token. Google's
         * tokens last ~1h; refreshing at 50min avoids handing out a token
         * that's about to expire mid-sync. */
        private const val TOKEN_TTL_MS = 50L * 60L * 1000L
        private const val K_HOLD = "holdShared"
        private const val K_NEEDS_SIGN_IN = "needsSignIn"
        private const val K_DRIVE_UNKNOWN = "driveUnknown"
        private const val K_REPLACE_DEFERRED = "replaceDeferred"
        /** An abandoned setup choice stops holding Drive after 30 minutes. */
        private const val HOLD_TTL_MS = 30L * 60L * 1000L
    }
}
