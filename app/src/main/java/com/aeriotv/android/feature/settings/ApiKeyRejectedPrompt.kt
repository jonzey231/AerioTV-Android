package com.aeriotv.android.feature.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.network.DispatcharrRequestGate
import com.aeriotv.android.ui.settings.SettingsAlertDialog
import com.aeriotv.android.ui.settings.SettingsDialogTextButton

/**
 * "Open Edit Playlist for this playlist" from places with no Settings
 * navigation of their own (the Set up another device sheet). The prompt host
 * below hands it to Settings.
 */
object EditPlaylistRequest {
    private val _pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val pending: kotlinx.coroutines.flow.StateFlow<String?> = _pending
    fun open(playlistId: String) { _pending.value = playlistId }
    fun consume() { _pending.value = null }
}

/** Title of the rejected-key prompt. */
const val API_KEY_REJECTED_TITLE = KeyRejectedCopy.TITLE

/**
 * Shown once per rejection ([DispatcharrRequestGate.prompt]): the server
 * refused a playlist's API key and AerioTV holds every API call for it
 * locally until its sign-in changes. Same dialog components as the other
 * Settings prompts, so TV focus and rings match. Copy and button sets:
 * [KeyRejectedCopy].
 *
 * Phones and tablets: "Sign In" (primary; the sign-in form), "Edit
 * Playlist", and "Not Now". TVs: "Update from Phone" (primary, default
 * focus; Fix Login, docs/DeviceSetupHandoff.md), "Sign In with Remote" (the
 * same sign-in form; key entry stays in Settings > Edit Playlist), and "Not
 * Now". The sign-in buttons follow the Set up gate (no Child profile, not
 * while the launch PIN screen is up), and a Child never sees Edit Playlist.
 */
@Composable
fun ApiKeyRejectedPromptHost(
    onEditPlaylist: (playlistId: String) -> Unit,
    /** Fix Login or the sign-in form replaced [playlistId]'s sign-in and lifted its hold: reload its channels. */
    onSignedIn: (playlistId: String) -> Unit = {},
) {
    val editRequest by EditPlaylistRequest.pending.collectAsStateWithLifecycle()
    LaunchedEffect(editRequest) {
        val id = editRequest ?: return@LaunchedEffect
        EditPlaylistRequest.consume()
        onEditPlaylist(id)
    }
    val isTv = com.aeriotv.android.ui.settings.rememberIsTvDevice()
    // Fix Login receive screen (TVs): the playlist it asks a phone about.
    var fixLoginFor by rememberSaveable { mutableStateOf<String?>(null) }
    fixLoginFor?.let { pid ->
        val close = {
            android.util.Log.i("AerioSetup", "[SETUP] fix login screen closed")
            fixLoginFor = null
        }
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            com.aeriotv.android.feature.devicesetup.TvSetupScreen(
                // Fix Login never adds a playlist; only the sign-in is written.
                addPlaylist = { _, _ -> Result.failure(IllegalStateException("Fix Login adds no playlist")) },
                onCancel = close,
                onDone = { close() },
                fixLoginPlaylistId = pid,
                onSignedIn = onSignedIn,
                vm = hiltViewModel(key = "fix-login"),
            )
        }
    }
    // The sign-in form (every device): the playlist it signs in to.
    val signInVm: KeyRejectedSignInViewModel = hiltViewModel(key = "key-rejected-sign-in")
    var signInFor by rememberSaveable { mutableStateOf<String?>(null) }
    // The refused key was an active non-default profile's own: its form signs THAT profile in.
    var signInProfile by rememberSaveable { mutableStateOf<String?>(null) }
    signInFor?.let { pid ->
        KeyRejectedSignInDialog(
            playlistId = pid,
            vm = signInVm,
            onCancel = {
                android.util.Log.i("DispatcharrAuthBroker", "[AUTH] sign-in form closed")
                signInFor = null
                signInProfile = null
            },
            onSignedIn = { id ->
                signInFor = null
                signInProfile = null
                onSignedIn(id)
            },
            heldProfileId = signInProfile,
            profileKeyRejected = signInProfile != null,
        )
    }
    val prompt by DispatcharrRequestGate.prompt.collectAsStateWithLifecycle()
    // The gate is re-read when the launch PIN screen, the launch picker, or the active profile changes.
    val launchGate by signInVm.launchGate.collectAsStateWithLifecycle()
    val launchPicker by signInVm.launchPicker.collectAsStateWithLifecycle()
    val profileToken by com.aeriotv.android.core.profiles.ProfileScope.token.collectAsStateWithLifecycle()
    // A Child never gets Edit Playlist (the Settings root hides it too).
    val activeRole by com.aeriotv.android.core.profiles.ProfileRoles.active.collectAsStateWithLifecycle()
    val p = prompt ?: return
    val canSignIn = remember(p, launchGate, launchPicker, profileToken) { signInVm.canSignIn(p.playlistId) }
    val isChild = activeRole == com.aeriotv.android.core.profiles.ProfileRole.Child
    val buttons = KeyRejectedCopy.buttons(isTv, canSignIn, isChild = isChild)
    val focus = remember(p) { FocusRequester() }
    LaunchedEffect(p) { runCatching { focus.requestFocus() } }
    val act: (KeyRejectedCopy.Button) -> Unit = { b ->
        DispatcharrRequestGate.consumePrompt()
        when (b) {
            KeyRejectedCopy.Button.UpdateFromPhone -> {
                android.util.Log.i("AerioSetup", "[SETUP] fix login screen opened from the rejected-key prompt")
                fixLoginFor = p.playlistId
            }
            KeyRejectedCopy.Button.SignIn, KeyRejectedCopy.Button.SignInWithRemote -> {
                android.util.Log.i("DispatcharrAuthBroker", "[AUTH] sign-in form opened from the rejected-key prompt${if (p.profileId != null) " for the profile whose key was refused" else ""}")
                signInProfile = p.profileId
                signInFor = p.playlistId
            }
            KeyRejectedCopy.Button.EditPlaylist -> onEditPlaylist(p.playlistId)
            KeyRejectedCopy.Button.NotNow -> Unit
        }
    }
    SettingsAlertDialog(
        onDismissRequest = { DispatcharrRequestGate.consumePrompt() },
        title = { Text(KeyRejectedCopy.TITLE) },
        text = { Text(KeyRejectedCopy.body(p.playlistName, p.passwordToo, p.noApiKey, isChild = isChild, canSignIn = canSignIn)) },
        confirmButton = {
            // Emitted straight into the dialog's button row (no inner Row),
            // in order, so it wraps instead of clipping when they do not fit.
            buttons.forEachIndexed { i, b ->
                SettingsDialogTextButton(
                    KeyRejectedCopy.label(b),
                    { act(b) },
                    modifier = if (i == 0) Modifier.focusRequester(focus) else Modifier,
                    emphasized = i == 0,
                )
            }
        },
    )
}
