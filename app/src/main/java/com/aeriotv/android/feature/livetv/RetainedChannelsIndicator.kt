package com.aeriotv.android.feature.livetv

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberSmartRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.timeshift.TimeshiftController
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Keep Recent Channels Live (iOS parity): thin surface over the
 * controller's retained-session list for the Live TV header indicator.
 */
@HiltViewModel
class RetainedChannelsViewModel @Inject constructor(
    private val timeshift: TimeshiftController,
) : ViewModel() {
    val retained = timeshift.retainedChannels
    fun stop(channelId: String) = timeshift.stopRetainedChannel(channelId)
    fun stopAll() = timeshift.stopAllRetained()
}

/**
 * Live TV header action: visible only while at least one flipped-away
 * channel is being kept live. Opens the house TvActionMenuDialog (both
 * form factors, per the OnDemand precedent) listing each kept channel
 * with Watch / Stop, plus Stop All. Composed inside [LiveTvTopBar]'s
 * action slot on phone; the TV control row renders its own circle and
 * shares only the dialog via [RetainedChannelsDialog].
 */
@Composable
fun RetainedChannelsAction(
    viewModel: RetainedChannelsViewModel,
    buttonSize: Dp,
    iconSize: Dp,
    onJumpToChannel: (String) -> Unit,
) {
    val retained by viewModel.retained.collectAsStateWithLifecycle()
    if (retained.isEmpty()) return
    var dialogOpen by remember { mutableStateOf(false) }
    IconButton(onClick = { dialogOpen = true }, modifier = Modifier.size(buttonSize)) {
        Icon(
            imageVector = Icons.Filled.FiberSmartRecord,
            contentDescription = "${retained.size} channels kept live",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(iconSize),
        )
    }
    if (dialogOpen) {
        RetainedChannelsDialog(
            viewModel = viewModel,
            onJumpToChannel = onJumpToChannel,
            onDismiss = { dialogOpen = false },
        )
    }
}

@Composable
fun RetainedChannelsDialog(
    viewModel: RetainedChannelsViewModel,
    onJumpToChannel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val retained by viewModel.retained.collectAsStateWithLifecycle()
    val guard = rememberTvMenuGuard()
    val actions = buildList {
        retained.asReversed().forEach { ch ->
            add(
                TvMenuAction(
                    label = "Watch ${ch.channelName}",
                    icon = Icons.Filled.PlayArrow,
                ) { onJumpToChannel(ch.channelId) },
            )
            add(
                TvMenuAction(
                    label = "Stop ${ch.channelName}",
                    icon = Icons.Filled.Stop,
                    destructive = true,
                ) { viewModel.stop(ch.channelId) },
            )
        }
        if (retained.size > 1) {
            add(
                TvMenuAction(
                    label = "Stop All",
                    icon = Icons.Filled.Stop,
                    destructive = true,
                ) { viewModel.stopAll() },
            )
        }
    }
    TvActionMenuDialog(
        title = "Kept Live",
        actions = actions,
        guard = guard,
        onDismiss = onDismiss,
    )
}
