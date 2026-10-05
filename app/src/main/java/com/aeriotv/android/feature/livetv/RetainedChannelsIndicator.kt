package com.aeriotv.android.feature.livetv

import androidx.compose.foundation.background
import com.aeriotv.android.feature.main.floatingNavChrome
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * Kept Live list. TV: the action-menu dialog opened from the floating circle
 * in TvTopTabBar. Phones and tablets: the same contents in a bottom sheet
 * (FormFactorModal), opened by the multi-channel text of
 * [RetainedChannelsPill] (iPhone parity): a Stop All header, then each kept
 * channel with Watch and Stop. Closes on Watch, on Stop All, or when nothing
 * is kept any more.
 */
@Composable
fun RetainedChannelsDialog(
    viewModel: RetainedChannelsViewModel,
    onJumpToChannel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val retained by viewModel.retained.collectAsStateWithLifecycle()
    if (!com.aeriotv.android.ui.settings.rememberIsTvDevice()) {
        RetainedChannelsSheet(
            retained = retained,
            onWatch = { id ->
                onDismiss()
                onJumpToChannel(id)
            },
            onStop = viewModel::stop,
            onStopAll = {
                viewModel.stopAll()
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        return
    }
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

@Composable
private fun RetainedChannelsSheet(
    retained: List<com.aeriotv.android.core.timeshift.TimeshiftController.RetainedChannel>,
    onWatch: (String) -> Unit,
    onStop: (String) -> Unit,
    onStopAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Nothing kept any more (last Stop, a server end): close the sheet.
    androidx.compose.runtime.LaunchedEffect(retained.isEmpty()) {
        if (retained.isEmpty()) onDismiss()
    }
    com.aeriotv.android.ui.FormFactorModal(
        onDismiss = onDismiss,
        sheetMaxWidth = 600.dp,
        traceName = "kept-live",
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Text(
                text = "Kept Live",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (retained.size > 1) {
                RetainedCardButton(label = "Stop All", onClick = onStopAll)
            }
        }
        retained.asReversed().forEach { ch ->
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(com.aeriotv.android.core.ui.artworkTileShape(6.dp, model = ch.logoUrl))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    if (!ch.logoUrl.isNullOrBlank()) {
                        coil3.compose.AsyncImage(
                            model = ch.logoUrl,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.FiberSmartRecord,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                androidx.compose.material3.Text(
                    text = ch.channelName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RetainedCardButton(label = "Watch", onClick = { onWatch(ch.channelId) })
                RetainedCardButton(label = "Stop", onClick = { onStop(ch.channelId) })
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
    }
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
 * Keep Recent Channels Live pill (Apple parity, Logan 2026-10-04), phone and
 * tablet only. One compact line in the card slot above the bottom nav bar,
 * sharing its row with the Control a TV button (pill leading, button
 * trailing). TV uses the top-bar kept-live circle and its dialog instead.
 * One kept: channel logo, "Keeping <name> live" (tap tunes it, adopting the
 * kept stream exactly as a re-tune does) and Stop. Two or more: "Keeping N
 * channels live" (tap opens the Kept Live list) and Stop All.
 */
@Composable
fun RetainedChannelsPill(
    retained: List<com.aeriotv.android.core.timeshift.TimeshiftController.RetainedChannel>,
    onTune: (String) -> Unit,
    onOpenList: () -> Unit,
    onStop: (String) -> Unit,
    onStopAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (retained.isEmpty()) return
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp)
    val single = retained.size == 1
    val latest = retained.last()
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .height(52.dp)
            // Same chrome as the floating nav bar and the Control a TV
            // button so the row reads as one layer with them.
            .floatingNavChrome(shape)
            .padding(start = 6.dp, end = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .weight(1f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .clickable(onClick = { if (single) onTune(latest.channelId) else onOpenList() })
                .padding(4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            val logo = latest.logoUrl
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(com.aeriotv.android.core.ui.artworkTileShape(6.dp, model = logo))
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                if (single && !logo.isNullOrBlank()) {
                    coil3.compose.AsyncImage(
                        model = logo,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.FiberSmartRecord,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
            androidx.compose.material3.Text(
                text = if (single) "Keeping ${latest.channelName} live"
                else "Keeping ${retained.size} channels live",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (single) {
            RetainedCardButton(label = "Stop", onClick = { onStop(latest.channelId) })
        } else {
            RetainedCardButton(label = "Stop All", onClick = onStopAll)
        }
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
