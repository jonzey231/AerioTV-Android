package com.aeriotv.android.feature.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.cast.AerioCastSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Where should this play?" while a Google Cast session is active.
 *
 * Every play entry point on a phone or tablet (channel list, guide, Kept
 * Live, reminder banner, catch-up, movies, episodes, recordings) calls
 * [route]. With no connected session the local action runs immediately,
 * exactly as before. With a session, [PlayWhereDialogHost] asks: Play Here,
 * Play on <receiver>, or Cancel.
 *
 * Play Here raises [localWhileCasting] for the rest of the session. The
 * local player surfaces (PlayerScreen, the phone mini, the playback
 * notification) read it and stay local instead of mirroring to the cast
 * session and closing, so the receiver keeps playing what it has.
 *
 * Channel up/down inside a local player never comes through here (exempt by
 * design), and neither do the cast card's own Switch / channel flips or a
 * Change Cast Device handoff: those already target the receiver.
 *
 * Android TV is never a cast sender, so its state never reaches Connected
 * and [route] always plays locally there.
 */
object PlayWhereRouter {

    private const val TAG = "AerioCast"

    /** A pending choice shown by [PlayWhereDialogHost]. */
    data class Request(
        val title: String,
        val receiverName: String,
        val playHere: () -> Unit,
        /** Null when this content type has no sender cast path; the dialog
         *  then hides the receiver button. */
        val playOnReceiver: (() -> Unit)?,
    )

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private val _localWhileCasting = MutableStateFlow(false)

    /** True after the user chose Play Here in the current cast session. */
    val localWhileCasting: StateFlow<Boolean> = _localWhileCasting.asStateFlow()

    /**
     * The single play entry point. [playHere] is the unchanged local play
     * path; [playOnReceiver] sends the item through the existing cast path,
     * or is null when the content cannot be cast.
     */
    fun route(
        sender: AerioCastSender,
        title: String,
        playHere: () -> Unit,
        playOnReceiver: (() -> Unit)?,
    ) {
        val connected = sender.state.value as? AerioCastSender.State.Connected
        if (connected == null) {
            playHere()
            return
        }
        _pending.value = Request(
            title = title,
            receiverName = connected.deviceName?.takeIf { it.isNotBlank() } ?: "TV",
            playHere = playHere,
            playOnReceiver = playOnReceiver,
        )
    }

    internal fun chooseHere(request: Request) {
        _pending.value = null
        android.util.Log.i(TAG, "CastPlayWhere: here")
        _localWhileCasting.value = true
        request.playHere()
    }

    internal fun chooseReceiver(request: Request) {
        val action = request.playOnReceiver ?: return
        _pending.value = null
        android.util.Log.i(TAG, "CastPlayWhere: receiver=${request.receiverName}")
        action()
    }

    internal fun cancel() {
        _pending.value = null
    }

    /** The session ended (or never connected): local is simply local again. */
    internal fun onSessionGone() {
        _pending.value = null
        _localWhileCasting.value = false
    }
}

/** Hosts the Play Where dialog; compose once at the NavHost scope. */
@Composable
fun PlayWhereDialogHost(sender: AerioCastSender) {
    val castState by sender.state.collectAsStateWithLifecycle()
    val connected = castState is AerioCastSender.State.Connected
    LaunchedEffect(connected) {
        if (!connected) PlayWhereRouter.onSessionGone()
    }
    val request by PlayWhereRouter.pending.collectAsStateWithLifecycle()
    val r = request ?: return
    AlertDialog(
        onDismissRequest = { PlayWhereRouter.cancel() },
        title = { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { PlayWhereRouter.chooseHere(r) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Play Here") }
                if (r.playOnReceiver != null) {
                    FilledTonalButton(
                        onClick = { PlayWhereRouter.chooseReceiver(r) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Play on ${r.receiverName}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { PlayWhereRouter.cancel() }) { Text("Cancel") }
        },
    )
}
