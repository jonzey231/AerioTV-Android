package com.aeriotv.android.feature.settings

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.ui.settings.SettingsAlertDialog
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The rejected-key prompt's sign-in form (shared spec 2026-11-11): the user
 * signs in with the Dispatcharr username and password, AerioTV reads the
 * account's current API key and keeps the password, so a later key change
 * heals through the silent re-login. Runs on the view model's scope, so a
 * focus change or recomposition never cancels a sign-in half way.
 */
@HiltViewModel
class KeyRejectedSignInViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val profiles: ProfileManager,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Busy : State
        data class Failed(val line: String) : State
        /** Signed in: the hold and the prompt are lifted; the caller reloads channels. */
        data class Done(val playlistId: String) : State
        /** The gate closed while the form was open (a profile switch, the launch PIN): nothing was sent. */
        data object Refused : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Re-read the gate when these change (the launch PIN screen, the TV launch picker). */
    val launchGate = profiles.launchGate
    val launchPicker = profiles.launchPicker

    /** Same gate as Set up: never a Child profile, never while the launch PIN screen is up. */
    fun canSignIn(playlistId: String): Boolean =
        com.aeriotv.android.feature.devicesetup.setupSendAllowed(
            locked = profiles.isPinGated(),
            canSetUpTv = profiles.canSetUpTv(playlistId),
        )

    /**
     * The username the form starts with: the same known-user chain the
     * sign-in is checked against (FixLogin.knownAccount, Apple parity
     * 2026-11-12): the recorded default identity, else the playlist's own
     * stored username, else the default profile's. Never the account facts,
     * which a non-default profile's session can write. Else "".
     */
    suspend fun knownUsername(playlistId: String): String {
        val row = runCatching { profiles.defaultPlaylistRow(playlistId) }.getOrNull()
        val known = com.aeriotv.android.core.setup.FixLogin.knownAccount(
            profiles.recordedDefaultIdentity(playlistId),
            row?.username,
            profiles.defaultProfileUsername(playlistId),
        )
        return KeyRejectedCopy.prefillUsername(known.username)
    }

    /** A held profile's own username (the form for a profile whose playlist moved), else "". */
    fun heldUsername(profileId: String): String = profiles.profileUsername(profileId)

    /**
     * Sign in as a held profile (its playlist moved to another server
     * address): replaces that profile's key and password, lifts its hold
     * only, and finishes the switch. No Set up gate here: the profile was
     * picked, its PIN (if any) passed before the form opened.
     */
    /**
     * The rejected-key prompt's Sign In for an active non-default profile
     * whose own key was refused (Apple parity, 2026-11-12): signs in as that
     * profile's own user only, stores its new key and password, and lifts
     * only its own hold. Same Set up gate as the playlist's form.
     */
    fun submitProfile(playlistId: String, profileId: String, username: String, password: String) {
        if (!KeyRejectedCopy.canSubmit(username, password, _state.value is State.Busy)) return
        if (!canSignIn(playlistId)) {
            Log.i(TAG, "[AUTH] sign-in form refused: this profile may not sign in here")
            _state.value = State.Refused
            return
        }
        _state.value = State.Busy
        viewModelScope.launch {
            profiles.signInRejectedProfile(playlistId, profileId, username, password).fold(
                onSuccess = { _state.value = State.Done(playlistId) },
                onFailure = {
                    // Never the password or the key: the error's class only.
                    Log.w(TAG, "[AUTH] profile sign-in from the rejected-key prompt failed: ${it.javaClass.simpleName}")
                    _state.value = State.Failed(KeyRejectedCopy.errorLine(it))
                },
            )
        }
    }

    fun submitHeld(playlistId: String, profileId: String, username: String, password: String) {
        if (!KeyRejectedCopy.canSubmit(username, password, _state.value is State.Busy)) return
        _state.value = State.Busy
        viewModelScope.launch {
            profiles.signInHeldProfile(playlistId, profileId, username, password).fold(
                onSuccess = { _state.value = State.Done(playlistId) },
                onFailure = {
                    // Never the password or the key: the error's class only.
                    Log.w(TAG, "[PROFILE-HOST] held profile sign-in failed: ${it.javaClass.simpleName}")
                    _state.value = State.Failed(KeyRejectedCopy.errorLine(it))
                },
            )
        }
    }

    fun reset() { if (_state.value !is State.Busy) _state.value = State.Idle }

    /**
     * The playlist's own sign-in from the form a switch to the default
     * profile opened (its key was held as rejected; Roku spec 8.4 step 4).
     * The choice, and its PIN, already authorized it, so there is no Set up
     * gate; the same-account check (item 11) still applies.
     */
    fun submitForSwitch(playlistId: String, username: String, password: String) {
        if (!KeyRejectedCopy.canSubmit(username, password, _state.value is State.Busy)) return
        _state.value = State.Busy
        viewModelScope.launch {
            repository.signInWithPassword(playlistId, username, password).fold(
                onSuccess = {
                    Log.i(TAG, "[AUTH] sign-in for a switch to the default profile on ${playlistId.take(8)}: signed in, hold lifted")
                    _state.value = State.Done(playlistId)
                },
                onFailure = {
                    Log.w(TAG, "[AUTH] sign-in for a switch to the default profile on ${playlistId.take(8)} failed: ${it.javaClass.simpleName}")
                    _state.value = State.Failed(KeyRejectedCopy.errorLine(it))
                },
            )
        }
    }

    fun submit(playlistId: String, username: String, password: String) {
        if (!KeyRejectedCopy.canSubmit(username, password, _state.value is State.Busy)) return
        if (!canSignIn(playlistId)) {
            Log.i(TAG, "[AUTH] sign-in form refused: this profile may not sign in here")
            _state.value = State.Refused
            return
        }
        _state.value = State.Busy
        viewModelScope.launch {
            repository.signInWithPassword(playlistId, username, password).fold(
                onSuccess = {
                    Log.i(TAG, "[AUTH] sign-in from the rejected-key prompt for ${playlistId.take(8)}: signed in, hold lifted")
                    _state.value = State.Done(playlistId)
                },
                onFailure = {
                    // Never the password or the key: the error's class only.
                    Log.w(TAG, "[AUTH] sign-in from the rejected-key prompt for ${playlistId.take(8)} failed: ${it.javaClass.simpleName}")
                    _state.value = State.Failed(KeyRejectedCopy.errorLine(it))
                },
            )
        }
    }

    private companion object { const val TAG = "DispatcharrAuthBroker" }
}

/**
 * "Sign in to Dispatcharr": Username (prefilled when known), Password, the
 * footnote, an inline error line, Sign In, and Cancel. Same dialog
 * components as the rejected-key prompt, so TV focus and rings match. Sign
 * In is never disabled (a disabled button drops TV focus); it does nothing
 * until both fields are filled or while a sign-in runs.
 */
@Composable
fun KeyRejectedSignInDialog(
    playlistId: String,
    vm: KeyRejectedSignInViewModel,
    onCancel: () -> Unit,
    onSignedIn: (playlistId: String) -> Unit,
    /** A held profile (its playlist moved to another server address): sign in as it, not as the playlist. */
    heldProfileId: String? = null,
    /** Shown above the fields. */
    message: String? = null,
    /** [heldProfileId] is an active profile whose own key was refused (the rejected-key prompt), not a held one. */
    profileKeyRejected: Boolean = false,
    /** The playlist's own form, opened by choosing the default profile whose key is held (no Set up gate). */
    forSwitch: Boolean = false,
) {
    var username by rememberSaveable(playlistId, heldProfileId) { mutableStateOf("") }
    var password by rememberSaveable(playlistId, heldProfileId) { mutableStateOf("") }
    var prefilled by rememberSaveable(playlistId, heldProfileId) { mutableStateOf(false) }
    val isTv = com.aeriotv.android.ui.settings.rememberIsTvDevice()
    val usernameFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val passwordFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(playlistId, heldProfileId) {
        vm.reset()
        if (!prefilled) {
            prefilled = true
            if (username.isEmpty()) username = heldProfileId?.let { vm.heldUsername(it) } ?: vm.knownUsername(playlistId)
        }
        // TV: start where the remote types next (the password when the
        // username is known), so the keyboard opens on the right field and
        // its Done key signs in. Phones and tablets focus nothing.
        if (isTv) runCatching { (if (username.isNotBlank()) passwordFocus else usernameFocus).requestFocus() }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        when (val s = state) {
            is KeyRejectedSignInViewModel.State.Done -> { vm.reset(); onSignedIn(s.playlistId) }
            KeyRejectedSignInViewModel.State.Refused -> { vm.reset(); onCancel() }
            else -> {}
        }
    }
    val busy = state is KeyRejectedSignInViewModel.State.Busy
    val submit = {
        when {
            heldProfileId != null && profileKeyRejected -> vm.submitProfile(playlistId, heldProfileId, username, password)
            heldProfileId != null -> vm.submitHeld(playlistId, heldProfileId, username, password)
            forSwitch -> vm.submitForSwitch(playlistId, username, password)
            else -> vm.submit(playlistId, username, password)
        }
    }
    SettingsAlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(KeyRejectedCopy.FORM_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    singleLine = true,
                    label = { Text(KeyRejectedCopy.USERNAME) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(usernameFocus),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text(KeyRejectedCopy.PASSWORD) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
                )
                Text(
                    KeyRejectedCopy.FOOTNOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                (state as? KeyRejectedSignInViewModel.State.Failed)?.let {
                    Text(it.line, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { SettingsDialogTextButton(label = KeyRejectedCopy.FORM_SIGN_IN, onClick = submit, emphasized = true) },
        dismissButton = { SettingsDialogTextButton(label = KeyRejectedCopy.CANCEL, onClick = { if (!busy) onCancel() }) },
    )
}

/**
 * Picking a profile whose sign-in is held because its playlist moved to
 * another server address (A-APP-1, shared spec 2026-11-11) opens the same
 * "Sign in to Dispatcharr" form, prefilled with that profile's username and
 * a line naming the playlist. Sign In replaces that profile's key and
 * password and finishes the switch; Cancel stays on the current profile.
 * Mounted at the app root after the TV launch picker, so it shows above it.
 */
@Composable
fun HeldProfileSignInHost(manager: ProfileManager) {
    val request by manager.heldSignIn.collectAsStateWithLifecycle()
    val r = request ?: return
    val vm: KeyRejectedSignInViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(key = "held-profile-sign-in")
    KeyRejectedSignInDialog(
        playlistId = r.playlistId,
        vm = vm,
        onCancel = { manager.closeHeldSignIn() },
        onSignedIn = {
            // The default profile's playlist sign-in passed: the switch goes on.
            if (r.playlistOwn) manager.continueSwitchAfterOwnSignIn(r.playlistId, r.profileId) else manager.closeHeldSignIn()
        },
        // The default profile signs in as the playlist (its own form, the known user).
        heldProfileId = r.profileId.takeUnless { r.playlistOwn },
        forSwitch = r.playlistOwn,
        // A profile with no stored sign-in, or a held key, gets the plain form.
        message = if (r.addressChanged) KeyRejectedCopy.hostChanged(r.playlistName) else null,
    )
}
