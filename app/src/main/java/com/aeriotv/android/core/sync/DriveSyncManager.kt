package com.aeriotv.android.core.sync

import android.content.Context
import android.os.Build
import android.util.Log
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.ReminderDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.db.entity.ReminderEntity
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity
import com.aeriotv.android.core.preferences.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Orchestrates Drive AppData sync. Mirrors iOS SyncManager.swift surface:
 *   - signIn() / signOut() — wraps GoogleDriveAuth.
 *   - pushAll() / pullAll() — per-category JSON snapshots written to / read
 *     from the appDataFolder.
 *   - clearRemote() — Settings > "Clear Drive Data" hook.
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
    private val playlistDao: PlaylistDao,
    private val watchProgressDao: WatchProgressDao,
    private val reminderDao: ReminderDao,
    private val appPreferences: AppPreferences,
) {

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val driveClient = DriveAppDataClient(okHttp)
    private val auth = GoogleDriveAuth(context)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private val _status: MutableStateFlow<Status> = MutableStateFlow(Status.SignedOut)
    val status: StateFlow<Status> = _status.asStateFlow()

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
        (status.value as? Status.SignedIn)?.let { return it.accessToken }
        appPreferences.syncTokenOnce()?.let { (saved, expiry) ->
            if (System.currentTimeMillis() < expiry) {
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
        appPreferences.saveSyncToken(token, System.currentTimeMillis() + TOKEN_TTL_MS)
        _status.value = Status.SignedIn(token)
    }

    suspend fun signOut() {
        runCatching { auth.signOut() }
        appPreferences.setSyncAccountEmail("")
        appPreferences.clearSyncToken()
        _status.value = Status.SignedOut
    }

    /** Drop every appData file. Local DB rows are untouched (iOS parity). */
    suspend fun clearRemote(token: String) {
        val files = driveClient.listAll(token)
        files.forEach { (id, _) -> driveClient.delete(token, id) }
    }

    /**
     * Push enabled categories to Drive. Bails on the first failure but
     * continues across categories — a Reminders push fail doesn't block
     * Playlists. Returns a map of category → success.
     */
    suspend fun pushAll(token: String, enabled: Set<SyncCategory>): Map<SyncCategory, Boolean> {
        Log.i(TAG, "pushAll: categories=${enabled.map { it.name }}")
        val result = mutableMapOf<SyncCategory, Boolean>()
        enabled.forEach { category ->
            val ok = runCatching { pushCategory(token, category) }
                .onFailure { Log.w(TAG, "push ${category.name} failed", it) }
                .getOrDefault(false)
            result[category] = ok
        }
        appPreferences.setSyncLastPushAt(System.currentTimeMillis())
        return result
    }

    suspend fun pullAll(token: String, enabled: Set<SyncCategory>): Map<SyncCategory, Boolean> {
        val result = mutableMapOf<SyncCategory, Boolean>()
        enabled.forEach { category ->
            val ok = runCatching { pullCategory(token, category) }
                .onFailure { Log.w(TAG, "pull ${category.name} failed", it) }
                .getOrDefault(false)
            result[category] = ok
        }
        appPreferences.setSyncLastPullAt(System.currentTimeMillis())
        return result
    }

    private suspend fun pushCategory(token: String, category: SyncCategory): Boolean {
        val payload = when (category) {
            SyncCategory.Playlists -> json.encodeToString(buildPlaylistsSnapshot())
            SyncCategory.WatchProgress -> json.encodeToString(buildWatchProgressSnapshot())
            SyncCategory.Reminders -> json.encodeToString(buildRemindersSnapshot())
            SyncCategory.Preferences -> json.encodeToString(buildPreferencesSnapshot())
            SyncCategory.Credentials -> json.encodeToString(buildCredentialsSnapshot())
        }
        return driveClient.upload(token, category.fileName, payload).isSuccess
    }

    private suspend fun pullCategory(token: String, category: SyncCategory): Boolean {
        val fileId = driveClient.findFileId(token, category.fileName) ?: return false
        val body = driveClient.download(token, fileId) ?: return false
        return runCatching {
            when (category) {
                SyncCategory.Playlists -> applyPlaylistsSnapshot(json.decodeFromString(body))
                SyncCategory.WatchProgress -> applyWatchProgressSnapshot(json.decodeFromString(body))
                SyncCategory.Reminders -> applyRemindersSnapshot(json.decodeFromString(body))
                SyncCategory.Preferences -> applyPreferencesSnapshot(json.decodeFromString(body))
                SyncCategory.Credentials -> applyCredentialsSnapshot(json.decodeFromString(body))
            }
            true
        }.onFailure { Log.w(TAG, "decode ${category.name} failed", it) }
            .getOrDefault(false)
    }

    // ── Snapshot builders ─────────────────────────────────────────────────

    private fun envelope() = SyncEnvelope(
        lastModified = System.currentTimeMillis(),
        device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    )

    private suspend fun buildPlaylistsSnapshot(): PlaylistsSnapshot {
        val rows = playlistDao.allOnce()
        val active = rows.firstOrNull { it.isActive }?.id
        // Drop sensitive creds from this snapshot — they go in Credentials.
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
                )
            },
        )
    }

    private suspend fun buildWatchProgressSnapshot(): WatchProgressSnapshot {
        val rows = watchProgressDao.allOnce()
        return WatchProgressSnapshot(
            envelope = envelope(),
            entries = rows.map { row ->
                WatchProgressSnapshotEntry(
                    videoId = row.videoId,
                    title = row.title,
                    posterUrl = row.posterUrl,
                    positionMs = row.positionMs,
                    durationMs = row.durationMs,
                    updatedAt = row.updatedAt,
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
        // Synced subset — theme, default tab, palette overrides, UI toggles.
        // Avoids syncing network/device-local settings (buffer size, custom
        // DVR folder, network timeout) which are sensible per-device.
        val keys = appPreferences.snapshotSyncablePreferences()
        return PreferencesSnapshot(envelope = envelope(), keys = keys)
    }

    private suspend fun buildCredentialsSnapshot(): CredentialsSnapshot {
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

    private suspend fun applyPlaylistsSnapshot(snapshot: PlaylistsSnapshot) {
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
                isActive = entry.id == snapshot.active,
            )
            playlistDao.upsert(merged)
        }
    }

    private suspend fun applyWatchProgressSnapshot(snapshot: WatchProgressSnapshot) {
        snapshot.entries.forEach { remote ->
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
                )
                watchProgressDao.upsert(
                    base.copy(
                        title = remote.title,
                        posterUrl = remote.posterUrl ?: base.posterUrl,
                        positionMs = remote.positionMs,
                        durationMs = remote.durationMs,
                        updatedAt = remote.updatedAt,
                    ),
                )
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

    private suspend fun applyPreferencesSnapshot(snapshot: PreferencesSnapshot) {
        appPreferences.applySyncedPreferences(snapshot.keys)
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
    }
}
