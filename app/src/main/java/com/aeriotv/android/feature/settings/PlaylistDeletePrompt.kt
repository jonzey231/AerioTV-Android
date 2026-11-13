package com.aeriotv.android.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.aeriotv.android.ui.settings.AlertAction
import com.aeriotv.android.ui.settings.AlertPrompt
import com.aeriotv.android.ui.settings.AlertRole

/** The playlist delete prompt when on-device recordings came from it (Logan 2026-11-12, Apple the same). */
object PlaylistDeleteCopy {
    fun title(playlistName: String): String = "Delete $playlistName?"

    fun body(recordings: Int): String =
        "This removes the playlist and its sign-in from this device. " +
            if (recordings == 1) "1 recording saved on this device came from this playlist."
            else "$recordings recordings saved on this device came from this playlist."

    const val KEEP = "Delete, Keep Recordings"
    const val DELETE_ALL = "Delete Playlist and Recordings"
    const val CANCEL = "Cancel"
}

/**
 * Confirm deleting a playlist. With no on-device recordings from it, [plain]
 * shows today's confirmation for this screen (its confirm deletes, keeping
 * nothing to keep). With some, the recordings prompt: keep them as "Saved on
 * this device", or delete them with the playlist. Stacked buttons; on TV the
 * keep action takes focus. Nothing either way touches server recordings.
 */
@Composable
fun PlaylistDeleteConfirm(
    playlistId: String,
    playlistName: String,
    countRecordings: suspend (String) -> Int,
    onDismiss: () -> Unit,
    onDelete: (keepRecordings: Boolean) -> Unit,
    plain: @Composable (onConfirm: () -> Unit) -> Unit,
) {
    val count by produceState<Int?>(null, playlistId) {
        value = runCatching { countRecordings(playlistId) }.getOrDefault(0)
    }
    val n = count ?: return
    if (n == 0) {
        plain { onDelete(true) }
        return
    }
    AlertPrompt(
        onDismissRequest = onDismiss,
        title = PlaylistDeleteCopy.title(playlistName),
        message = PlaylistDeleteCopy.body(n),
        actions = listOf(
            AlertAction(PlaylistDeleteCopy.KEEP, AlertRole.Default) { onDelete(true) },
            AlertAction(PlaylistDeleteCopy.DELETE_ALL, AlertRole.Destructive) { onDelete(false) },
            AlertAction(PlaylistDeleteCopy.CANCEL, AlertRole.Cancel) { onDismiss() },
        ),
    )
}
