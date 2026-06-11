package com.aeriotv.android.feature.settings

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.sync.DriveSyncManager
import com.aeriotv.android.core.sync.DriveSyncWorker
import com.aeriotv.android.core.sync.SyncCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Backs SyncSettingsScreen. Drives the two-step sign-in (Credential Manager
 * identity → Drive scope authorization) and serializes the push-then-pull
 * sync flow.
 */
@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val sync: DriveSyncManager,
) : ViewModel() {

    val masterEnabled: Flow<Boolean> = prefs.syncMasterEnabled
    fun setMasterEnabled(value: Boolean) {
        viewModelScope.launch {
            prefs.setSyncMasterEnabled(value)
            if (value) DriveSyncWorker.enqueuePeriodic(context)
            else DriveSyncWorker.cancel(context)
        }
    }

    val accountEmail: Flow<String> = prefs.syncAccountEmail
    val lastPushAt: Flow<Long> = prefs.syncLastPushAt
    val lastPullAt: Flow<Long> = prefs.syncLastPullAt
    val driveStatus: StateFlow<DriveSyncManager.Status> = sync.status

    /** Per-category progress for the onboarding restore screen; populated by
     *  [restoreFromDrive] (which routes through the tracked pull). */
    val restoreSteps: StateFlow<List<DriveSyncManager.RestoreStep>> = sync.restoreSteps

    /** Drop stale restore progress so a later visit starts from a clean list. */
    fun clearRestoreProgress() = sync.clearRestoreProgress()

    /** Same shape as PlaylistViewModel.ActionStatus: drives the inline
     *  spinner + result line on the Sync Now / Clear Drive Data rows
     *  (the previous Toast-only feedback never surfaced on Android TV). */
    sealed interface ActionStatus {
        data object Idle : ActionStatus
        data object Running : ActionStatus
        data class Success(val message: String) : ActionStatus
        data class Failure(val message: String) : ActionStatus
    }

    private val _syncNowStatus = MutableStateFlow<ActionStatus>(ActionStatus.Idle)
    val syncNowStatus: StateFlow<ActionStatus> = _syncNowStatus

    private val _clearStatus = MutableStateFlow<ActionStatus>(ActionStatus.Idle)
    val clearStatus: StateFlow<ActionStatus> = _clearStatus

    private val _pushStatus = MutableStateFlow<ActionStatus>(ActionStatus.Idle)
    val pushStatus: StateFlow<ActionStatus> = _pushStatus

    private val _pullStatus = MutableStateFlow<ActionStatus>(ActionStatus.Idle)
    val pullStatus: StateFlow<ActionStatus> = _pullStatus

    /** Reset both action rows to Idle; the screen calls this on dispose so a
     *  stale result line doesn't greet the next visit. */
    fun clearActionStatuses() {
        _syncNowStatus.value = ActionStatus.Idle
        _clearStatus.value = ActionStatus.Idle
        _pushStatus.value = ActionStatus.Idle
        _pullStatus.value = ActionStatus.Idle
    }

    fun categoryEnabled(category: SyncCategory): Flow<Boolean> =
        prefs.syncCategoryEnabled(category)

    fun setCategoryEnabled(category: SyncCategory, value: Boolean) {
        viewModelScope.launch { prefs.setSyncCategoryEnabled(category, value) }
    }

    suspend fun signInWithGoogle(activity: Activity): String? =
        sync.signInWithGoogle(activity)

    suspend fun requestDriveScope(): DriveSyncManager.RequestResult? =
        sync.requestDriveScope()

    fun acceptConsentResult(data: android.content.Intent?) {
        viewModelScope.launch { sync.acceptConsentResult(data) }
    }

    /**
     * Silently restore the Drive session from the persisted token (or refresh
     * with no UI when it has lapsed) so the screen reflects signed-in and Sync
     * Now works without a manual re-login. Safe no-op when never signed in.
     */
    fun restoreSessionIfPossible() {
        viewModelScope.launch { sync.ensureSignedIn() }
    }

    fun signOut() {
        viewModelScope.launch {
            sync.signOut()
            DriveSyncWorker.cancel(context)
        }
    }

    /**
     * Push-then-pull over the user's enabled categories, reporting progress
     * and outcome through [syncNowStatus]. DriveSyncManager.pushAll/pullAll
     * each return a Map<SyncCategory, Boolean> (per-category success); a
     * category counts as synced only when both directions succeeded.
     */
    fun runSyncNow() {
        if (_syncNowStatus.value is ActionStatus.Running) return
        viewModelScope.launch {
            _syncNowStatus.value = ActionStatus.Running
            val token = (sync.status.value as? DriveSyncManager.Status.SignedIn)?.accessToken
            if (token == null) {
                _syncNowStatus.value = ActionStatus.Failure("Not signed in to Drive")
                return@launch
            }
            val enabled = SyncCategory.entries
                .filter { prefs.syncCategoryEnabled(it).first() }
                .toSet()
            if (enabled.isEmpty()) {
                _syncNowStatus.value = ActionStatus.Failure("No sync categories enabled")
                return@launch
            }
            // Same blank-install guard as the periodic worker: the push leg
            // waits until this install has pulled once (the pull below sets
            // the flag, so the NEXT Sync Now pushes normally).
            val initialPullDone = prefs.syncInitialPullDone.first()
            val pushed = if (initialPullDone) sync.pushAll(token, enabled) else emptyMap()
            val pulled = sync.pullAll(token, enabled)
            val merged = pulled.mapValues { (cat, ok) ->
                (pushed[cat] != false) && ok
            }
            val failed = merged.count { !it.value }
            _syncNowStatus.value = if (failed == 0) {
                val n = merged.size
                ActionStatus.Success("Synced $n ${if (n == 1) "category" else "categories"}")
            } else {
                ActionStatus.Failure("$failed of ${merged.size} categories failed to sync")
            }
        }
    }

    /**
     * EXPLICIT one-way push: overwrite the Drive backup with this device's
     * current configuration. Deliberate user action from Settings > Sync, so
     * it bypasses the initial-pull guard (and satisfies it afterwards: once
     * the cloud matches this device, automatic pushes can't make it worse).
     */
    fun runPushOnly() {
        if (_pushStatus.value is ActionStatus.Running) return
        viewModelScope.launch {
            _pushStatus.value = ActionStatus.Running
            val token = (sync.status.value as? DriveSyncManager.Status.SignedIn)?.accessToken
            if (token == null) {
                _pushStatus.value = ActionStatus.Failure("Not signed in to Drive")
                return@launch
            }
            val enabled = SyncCategory.entries
                .filter { prefs.syncCategoryEnabled(it).first() }
                .toSet()
            if (enabled.isEmpty()) {
                _pushStatus.value = ActionStatus.Failure("No sync categories enabled")
                return@launch
            }
            val pushed = sync.pushAll(token, enabled)
            val failed = pushed.count { !it.value }
            if (failed == 0) prefs.setSyncInitialPullDone(true)
            _pushStatus.value = if (failed == 0) {
                ActionStatus.Success("Pushed ${pushed.size} ${if (pushed.size == 1) "category" else "categories"} to Drive")
            } else {
                ActionStatus.Failure("$failed of ${pushed.size} categories failed to push")
            }
        }
    }

    /**
     * EXPLICIT one-way pull: overwrite this device's configuration with the
     * Drive backup. Reports through [pullStatus]; also satisfies the
     * initial-pull guard (DriveSyncManager.pullAll sets the flag).
     */
    fun runPullOnly() {
        if (_pullStatus.value is ActionStatus.Running) return
        viewModelScope.launch {
            _pullStatus.value = ActionStatus.Running
            val token = (sync.status.value as? DriveSyncManager.Status.SignedIn)?.accessToken
            if (token == null) {
                _pullStatus.value = ActionStatus.Failure("Not signed in to Drive")
                return@launch
            }
            val enabled = SyncCategory.entries
                .filter { prefs.syncCategoryEnabled(it).first() }
                .toSet()
            if (enabled.isEmpty()) {
                _pullStatus.value = ActionStatus.Failure("No sync categories enabled")
                return@launch
            }
            val pulled = sync.pullAll(token, enabled)
            val failed = pulled.count { !it.value }
            _pullStatus.value = if (failed == 0) {
                ActionStatus.Success("Pulled ${pulled.size} ${if (pulled.size == 1) "category" else "categories"} from Drive")
            } else {
                ActionStatus.Failure("$failed of ${pulled.size} categories did not apply (missing in Drive or failed)")
            }
        }
    }

    /**
     * Delete every AppData file from Drive (local rows untouched), reporting
     * through [clearStatus]. DriveSyncManager.clearRemote returns Unit and
     * throws on network/auth failure, hence the runCatching fold.
     */
    fun runClearRemote() {
        if (_clearStatus.value is ActionStatus.Running) return
        viewModelScope.launch {
            _clearStatus.value = ActionStatus.Running
            val token = (sync.status.value as? DriveSyncManager.Status.SignedIn)?.accessToken
            if (token == null) {
                _clearStatus.value = ActionStatus.Failure("Not signed in to Drive")
                return@launch
            }
            _clearStatus.value = runCatching { sync.clearRemote(token) }.fold(
                onSuccess = { ActionStatus.Success("Drive data cleared") },
                onFailure = { ActionStatus.Failure(it.message ?: "Couldn't clear Drive data") },
            )
        }
    }

    /**
     * Pull-only counterpart to [syncNow], used by the Welcome onboarding
     * "Sign in with Google" pill. Right after the user authorizes the Drive
     * scope on a fresh device, we want to lift any playlists / watch progress
     * / reminders / prefs / credentials from their Drive AppData folder so
     * the device is fully usable without re-typing a server URL. Push is
     * deliberately skipped here -- a fresh install has nothing to push, and
     * a partial local state would otherwise overwrite the canonical remote
     * snapshot on a phantom write.
     *
     * Returns the count of categories that successfully pulled at least one
     * row. Callers can branch on `playlistsPulled` specifically (the first
     * value of the returned map keyed by [SyncCategory.Playlists]) to decide
     * whether to auto-advance the Welcome flow into the main app.
     */
    suspend fun restoreFromDrive(): Map<SyncCategory, Boolean> {
        val token = (sync.status.value as? DriveSyncManager.Status.SignedIn)?.accessToken
            ?: return emptyMap()
        // Pull everything regardless of the user's per-category toggles --
        // first-launch restore wants the full snapshot. The toggles still
        // gate subsequent SyncWorker passes once the user is settled. The
        // tracked variant additionally publishes per-category progress via
        // [restoreSteps] for the onboarding restore screen.
        return sync.pullAllTracked(token, SyncCategory.entries.toSet())
    }
}
