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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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

    /**
     * Gates the one-time disclosure that server credentials sync to the user's
     * Drive in cleartext (audit task #53). The screen shows the notice the first
     * time sync is enabled, then calls [markCredentialsSyncDisclosed].
     */
    val credentialsSyncDisclosed: Flow<Boolean> = prefs.credentialsSyncDisclosed
    fun markCredentialsSyncDisclosed() {
        viewModelScope.launch { prefs.setCredentialsSyncDisclosed(true) }
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
     *  spinner + result line on the Push / Pull / Clear Drive Data rows
     *  (the previous Toast-only feedback never surfaced on Android TV). */
    sealed interface ActionStatus {
        data object Idle : ActionStatus
        data object Running : ActionStatus
        data class Success(val message: String) : ActionStatus
        data class Failure(val message: String) : ActionStatus
    }

    private val _clearStatus = MutableStateFlow<ActionStatus>(ActionStatus.Idle)
    val clearStatus: StateFlow<ActionStatus> = _clearStatus

    private val _pushStatus = MutableStateFlow<ActionStatus>(ActionStatus.Idle)
    val pushStatus: StateFlow<ActionStatus> = _pushStatus

    private val _pullStatus = MutableStateFlow<ActionStatus>(ActionStatus.Idle)
    val pullStatus: StateFlow<ActionStatus> = _pullStatus

    /** Reset both action rows to Idle; the screen calls this on dispose so a
     *  stale result line doesn't greet the next visit. */
    fun clearActionStatuses() {
        _clearStatus.value = ActionStatus.Idle
        _pushStatus.value = ActionStatus.Idle
        _pullStatus.value = ActionStatus.Idle
    }

    fun categoryEnabled(category: SyncCategory): Flow<Boolean> =
        prefs.syncCategoryEnabled(category)

    fun setCategoryEnabled(category: SyncCategory, value: Boolean) {
        viewModelScope.launch { prefs.setSyncCategoryEnabled(category, value) }
    }

    /** Slice of App Preferences, not a category of its own - see
     *  AppPreferences.syncRemoteControlMap for why. Per-device. */
    val syncRemoteControlMap: Flow<Boolean> = prefs.syncRemoteControlMap

    fun setSyncRemoteControlMap(value: Boolean) {
        viewModelScope.launch { prefs.setSyncRemoteControlMap(value) }
    }

    suspend fun signInWithGoogle(activity: Activity): String? =
        sync.signInWithGoogle(activity)

    /** One-shot events for the Settings > Sync sign-in flow. */
    sealed interface SignInEvent {
        /** Show a short toast with [text]. */
        data class Message(val text: String) : SignInEvent
        /** The screen must launch this consent IntentSender and hand the
         *  result back through [onConsentResult]. */
        data class LaunchConsent(val intentSender: android.content.IntentSender) : SignInEvent
    }

    private val _signInInFlight = MutableStateFlow(false)
    /** True from the Sign In tap until the flow finishes (success, failure,
     *  or the consent result returns). */
    val signInInFlight: StateFlow<Boolean> = _signInInFlight

    private val _signInEvents = Channel<SignInEvent>(Channel.BUFFERED)
    /** Buffered so an event emitted while the screen is out of composition
     *  (the GMS activity covering it) is delivered when it comes back. */
    val signInEvents: Flow<SignInEvent> = _signInEvents.receiveAsFlow()


    /**
     * Whole Settings > Sync sign-in chain on viewModelScope, so it survives the
     * screen leaving composition while the GMS account picker (a translucent
     * activity) covers it. Previously it ran on the composable's
     * rememberCoroutineScope and was cancelled whenever the pane swapped.
     */
    fun startSignIn(activity: Activity) {
        if (_signInInFlight.value) return
        _signInInFlight.value = true
        viewModelScope.launch {
            val email = sync.signInWithGoogle(activity)
            if (email == null) {
                _signInInFlight.value = false
                _signInEvents.send(SignInEvent.Message("Sign-in cancelled or failed."))
                return@launch
            }
            when (val driveResult = sync.requestDriveScope()) {
                is DriveSyncManager.RequestResult.Authorized -> {
                    _signInInFlight.value = false
                    _signInEvents.send(SignInEvent.Message("Signed in as $email"))
                }
                is DriveSyncManager.RequestResult.NeedsConsent -> {
                    // inFlight cleared by onConsentResult.
                    _signInEvents.send(SignInEvent.LaunchConsent(driveResult.intentSender))
                }
                DriveSyncManager.RequestResult.Failed,
                null -> {
                    _signInInFlight.value = false
                    _signInEvents.send(SignInEvent.Message("Drive authorization failed."))
                }
            }
        }
    }

    /** Result of the consent IntentSender the screen launched. */
    fun onConsentResult(data: android.content.Intent?) {
        viewModelScope.launch {
            try {
                sync.acceptConsentResult(data)
            } finally {
                _signInInFlight.value = false
            }
        }
    }

    suspend fun requestDriveScope(): DriveSyncManager.RequestResult? =
        sync.requestDriveScope()

    fun acceptConsentResult(data: android.content.Intent?) {
        viewModelScope.launch { sync.acceptConsentResult(data) }
    }

    /** Suspend variant for callers that must not proceed until the consent
     *  grant is fully applied (status flipped to SignedIn). The onboarding
     *  restore reads sync.status right after the consent launcher returns;
     *  the fire-and-forget form above races it because acceptConsentResult
     *  suspends on the encrypted token save BEFORE setting SignedIn, so a
     *  fresh install's first restore saw "not signed in" and pulled nothing
     *  (task #256). */
    suspend fun acceptConsentResultNow(data: android.content.Intent?) =
        sync.acceptConsentResult(data)

    /**
     * Silently restore the Drive session from the persisted token (or refresh
     * with no UI when it has lapsed) so the screen reflects signed-in and the
     * Push/Pull actions work without a manual re-login. Safe no-op when never
     * signed in.
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
            val pulled = sync.pullAll(token, enabled, replace = true)
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
     * Per-category result for the Sync Categories page's Delete actions.
     * Keyed by category so each row shows its own spinner and result line
     * (the Apple toast has no Android TV equivalent that reliably surfaces).
     */
    private val _categoryClearStatus =
        MutableStateFlow<Map<SyncCategory, ActionStatus>>(emptyMap())
    val categoryClearStatus: StateFlow<Map<SyncCategory, ActionStatus>> = _categoryClearStatus

    /** Reset the Sync Categories result lines; called when that page leaves. */
    fun clearCategoryStatuses() {
        _categoryClearStatus.value = emptyMap()
    }

    /**
     * Delete ONE category's Drive file, leaving local data (and every other
     * device's copy) alone. Apple SyncManager.clearCloudCategory parity.
     */
    fun runClearRemoteCategory(category: SyncCategory) {
        if (_categoryClearStatus.value[category] is ActionStatus.Running) return
        fun set(status: ActionStatus) {
            _categoryClearStatus.value = _categoryClearStatus.value + (category to status)
        }
        viewModelScope.launch {
            set(ActionStatus.Running)
            val token = (sync.status.value as? DriveSyncManager.Status.SignedIn)?.accessToken
            if (token == null) {
                set(ActionStatus.Failure("Not signed in to Drive"))
                return@launch
            }
            set(
                runCatching { sync.clearRemoteCategory(token, category) }.fold(
                    onSuccess = { ActionStatus.Success("${settingsCategoryTitle(category)} removed from Drive") },
                    onFailure = { ActionStatus.Failure(it.message ?: "Couldn't remove from Drive") },
                ),
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
        // Honour the per-category toggles. This USED to pull everything
        // regardless, on the reasoning that a first-launch restore wants the
        // full snapshot - but onboarding now asks the user what should come in
        // BEFORE sign-in (OnboardingSyncCategoryChooser, Logan 2026-08-10), and
        // ignoring that answer here would fetch the very data they just
        // declined. A declined category must never be pulled, not pulled and
        // then ignored. The tracked variant still publishes per-category
        // progress via [restoreSteps] for the onboarding restore screen.
        val wanted = SyncCategory.entries.filter { prefs.syncCategoryEnabled(it).first() }.toSet()
        if (wanted.isEmpty()) return emptyMap()
        return sync.pullAllTracked(token, wanted)
    }
}
