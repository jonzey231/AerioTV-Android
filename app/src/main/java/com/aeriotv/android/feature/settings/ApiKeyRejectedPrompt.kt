package com.aeriotv.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
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
const val API_KEY_REJECTED_TITLE = "Sign-in not accepted"

/** Body of the rejected-key prompt (key-only playlist, or a password playlist whose re-login was refused too). */
fun apiKeyRejectedBody(playlistName: String, passwordToo: Boolean): String {
    val name = playlistName.ifBlank { "this playlist" }
    return if (passwordToo) {
        "The server did not accept the API key or the password for $name. Enter a new key or password in Edit Playlist to reconnect."
    } else {
        "The server did not accept the API key for $name. Enter a new key in Edit Playlist to reconnect."
    }
}

/**
 * Shown once per rejection ([DispatcharrRequestGate.prompt]): the server
 * refused a playlist's API key and AerioTV holds every API call for it
 * locally until Edit Playlist changes the key or credentials. Same dialog
 * components as the other Settings prompts, so TV focus and rings match.
 */
@Composable
fun ApiKeyRejectedPromptHost(onEditPlaylist: (playlistId: String) -> Unit) {
    val editRequest by EditPlaylistRequest.pending.collectAsStateWithLifecycle()
    LaunchedEffect(editRequest) {
        val id = editRequest ?: return@LaunchedEffect
        EditPlaylistRequest.consume()
        onEditPlaylist(id)
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
        },
    )
}
