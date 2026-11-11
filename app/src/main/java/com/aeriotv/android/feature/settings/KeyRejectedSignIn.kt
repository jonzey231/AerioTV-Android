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
import com.aeriotv.android.core.preferences.AppPreferences
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
    private val prefs: AppPreferences,
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

    /** The playlist's known username (its own, the account facts, or the default profile's), else "". */
    suspend fun knownUsername(playlistId: String): String {
        val row = runCatching { profiles.defaultPlaylistRow(playlistId) }.getOrNull()
        val facts = runCatching { prefs.dispatcharrAccountFactsOnce(playlistId).username }.getOrNull()
        val default = runCatching { profiles.setupSources(playlistId).firstOrNull { it.profile.isDefault }?.profile?.username }.getOrNull()
        return KeyRejectedCopy.prefillUsername(row?.username, facts, default)
    }

    fun reset() { if (_state.value !is State.Busy) _state.value = State.Idle }

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
) {
    var username by rememberSaveable(playlistId) { mutableStateOf("") }
    var password by rememberSaveable(playlistId) { mutableStateOf("") }
    var prefilled by rememberSaveable(playlistId) { mutableStateOf(false) }
    val isTv = com.aeriotv.android.ui.settings.rememberIsTvDevice()
    val usernameFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val passwordFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(playlistId) {
        vm.reset()
        if (!prefilled) {
            prefilled = true
            if (username.isEmpty()) username = vm.knownUsername(playlistId)
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
    val submit = { vm.submit(playlistId, username, password) }
    SettingsAlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(KeyRejectedCopy.FORM_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
