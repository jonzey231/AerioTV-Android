package com.aeriotv.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.network.DispatcharrRequestGate
import com.aeriotv.android.core.setup.FixLogin
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
const val API_KEY_REJECTED_TITLE = com.aeriotv.android.core.network.KEY_REJECTED_TITLE

/** Body of the rejected-key prompt (key-only playlist, or a password playlist whose re-login was refused too). */
fun apiKeyRejectedBody(playlistName: String, passwordToo: Boolean): String {
    val name = playlistName.ifBlank { "this playlist" }
    return if (passwordToo) {
        "Dispatcharr no longer accepts the API key or password for $name. They may have been changed on the server. " +
            "Enter the current details in Edit Playlist, or ask your Dispatcharr administrator."
    } else {
        "Dispatcharr no longer accepts the API key for $name. It may have been changed or removed on the server. " +
            "Enter the current key in Edit Playlist, or ask your Dispatcharr administrator for a new one."
    }
}

/**
 * Shown once per rejection ([DispatcharrRequestGate.prompt]): the server
 * refused a playlist's API key and AerioTV holds every API call for it
 * locally until Edit Playlist changes the key or credentials. Same dialog
 * components as the other Settings prompts, so TV focus and rings match.
 *
 * TVs (Fix Login, docs/DeviceSetupHandoff.md): "Update from Phone"
 * (primary, default focus) opens the receive screen asking a phone for this
 * playlist's sign-in, "Update with Remote" opens Edit Playlist, and
 * "Not Now". Phones and tablets keep Edit Playlist and Not Now.
 */
@Composable
fun ApiKeyRejectedPromptHost(
    onEditPlaylist: (playlistId: String) -> Unit,
    /** Fix Login wrote [playlistId]'s sign-in and lifted its hold: reload its channels. */
    onFixedLogin: (playlistId: String) -> Unit = {},
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
                onSignedIn = onFixedLogin,
                vm = hiltViewModel(key = "fix-login"),
            )
        }
    }
    val prompt by DispatcharrRequestGate.prompt.collectAsStateWithLifecycle()
    val p = prompt ?: return
    val focus = remember(p) { FocusRequester() }
    LaunchedEffect(p) { runCatching { focus.requestFocus() } }
    SettingsAlertDialog(
        onDismissRequest = { DispatcharrRequestGate.consumePrompt() },
        title = { Text(API_KEY_REJECTED_TITLE) },
        text = { Text(apiKeyRejectedBody(p.playlistName, p.passwordToo)) },
        confirmButton = {
            if (isTv) {
                // Emitted straight into the dialog's button row (no inner Row),
                // so it wraps instead of clipping when the three do not fit.
                SettingsDialogTextButton(
                    FixLogin.PROMPT_FROM_PHONE,
                    {
                        DispatcharrRequestGate.consumePrompt()
                        android.util.Log.i("AerioSetup", "[SETUP] fix login screen opened from the rejected-key prompt")
                        fixLoginFor = p.playlistId
                    },
                    modifier = Modifier.focusRequester(focus),
                    emphasized = true,
                )
                SettingsDialogTextButton(
                    FixLogin.PROMPT_WITH_REMOTE,
                    {
                        DispatcharrRequestGate.consumePrompt()
                        onEditPlaylist(p.playlistId)
                    },
                )
                SettingsDialogTextButton(FixLogin.PROMPT_NOT_NOW, { DispatcharrRequestGate.consumePrompt() })
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsDialogTextButton("Not Now", { DispatcharrRequestGate.consumePrompt() })
                    SettingsDialogTextButton(
                        "Edit Playlist",
                        {
                            DispatcharrRequestGate.consumePrompt()
                            onEditPlaylist(p.playlistId)
                        },
                        modifier = Modifier.focusRequester(focus),
                        emphasized = true,
                    )
                }
            }
        },
    )
}
