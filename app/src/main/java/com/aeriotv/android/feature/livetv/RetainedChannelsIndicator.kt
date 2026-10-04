package com.aeriotv.android.feature.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberSmartRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    fun stopAll() = timeshift.stopAllRetainedByUser()
}

/**
 * TV kept-live menu, opened from the floating circle in TvTopTabBar. Phones
 * and tablets use [RetainedChannelsCard] above the bottom nav bar instead.
 */
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


/**
 * Ids of the channels currently kept live, provided at the scaffold level so
 * the guide rail and the channel list rows can draw their KEPT badge. Dynamic
 * (not static) for the same reason as LocalShowEpgBadges: the rows live in a
 * LazyColumn subcomposition and must recompose on their own when the set
 * changes.
 */
val LocalRetainedChannelIds = androidx.compose.runtime.compositionLocalOf { emptySet<String>() }

/**
 * Keep Recent Channels Live card (Apple parity), phone and tablet only. Sits
 * where the cast card sits, above the bottom nav bar. TV uses the top-bar
 * kept-live circle and its dialog instead (Apple TV parity). Same shell as the cast card (20 dp corners, accent hairline, surface
 * row, 40 dp logo tile). One line per kept channel, "Keeping <name> live", with
 * its own Stop; "Stop All" when more than one is kept. Tapping a name tunes it,
 * which adopts the kept stream exactly as a re-tune does.
 */
@Composable
fun RetainedChannelsCard(
    retained: List<com.aeriotv.android.core.timeshift.TimeshiftController.RetainedChannel>,
    onTune: (String) -> Unit,
    onStop: (String) -> Unit,
    onStopAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (retained.isEmpty()) return
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    // Most recently kept first, like the dialog.
    val rows = retained.asReversed()
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), shape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        rows.forEachIndexed { index, ch ->
            if (index > 0) {
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                )
            }
            RetainedChannelRow(
                channel = ch,
                onTune = { onTune(ch.channelId) },
                onStop = { onStop(ch.channelId) },
            )
        }
        if (rows.size > 1) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
            ) {
                RetainedCardButton(label = "Stop All", onClick = onStopAll)
            }
        }
    }
}

@Composable
private fun RetainedChannelRow(
    channel: com.aeriotv.android.core.timeshift.TimeshiftController.RetainedChannel,
    onTune: () -> Unit,
    onStop: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .weight(1f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .clickable(onClick = onTune)
                .padding(4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            val logo = channel.logoUrl
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(com.aeriotv.android.core.ui.artworkTileShape(6.dp, model = logo))
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                if (!logo.isNullOrBlank()) {
                    coil3.compose.AsyncImage(
                        model = logo,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.FiberSmartRecord,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            androidx.compose.material3.Text(
                text = "Keeping ${channel.channelName} live",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        RetainedCardButton(label = "Stop", onClick = onStop)
    }
}

/** Accent text button, the card's Stop and Stop All. */
@Composable
private fun RetainedCardButton(label: String, onClick: () -> Unit) {
    androidx.compose.material3.Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
