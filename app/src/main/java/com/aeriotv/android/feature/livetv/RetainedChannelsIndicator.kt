package com.aeriotv.android.feature.livetv

import androidx.compose.foundation.background
import com.aeriotv.android.feature.main.floatingNavChrome
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import com.aeriotv.android.ui.theme.textAccent
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

/** Kept Live card vertical padding, the cast card's 8 dp. */
val RetainedCardVerticalPadding = 8.dp
/** Kept Live card trailing control height, the cast card's 48 dp. */
val RetainedCardControlHeight = 48.dp
/**
 * Height of the Kept Live card (64 dp): the Control a TV button beside it on
 * phones takes this diameter so the row reads as one shape (Logan 2026-10-05).
 */
val RetainedCardHeight = RetainedCardVerticalPadding * 2 + RetainedCardControlHeight

/**
 * Keep Recent Channels Live card (Apple parity, Logan 2026-10-04), phone and
 * tablet only, in the card slot above the bottom nav bar. One shape
 * everywhere, the cast card's (Logan 2026-10-05); sharing the row with the
 * Control a TV button it only gets narrower. TV uses the top-bar kept-live
 * circle and its dialog instead. One kept: tap tunes it (adopting the kept
 * stream exactly as a re-tune does), Stop. Two or more: tap opens the Kept
 * Live list, Stop All.
 */
@Composable
fun RetainedChannelsPill(
    retained: List<com.aeriotv.android.core.timeshift.TimeshiftController.RetainedChannel>,
    onTune: (String) -> Unit,
    onOpenList: () -> Unit,
    onStop: (String) -> Unit,
    onStopAll: () -> Unit,
    /** Tablets: 18 dp side padding, matching the tablet cast card. */
    capsule: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (retained.isEmpty()) return
    RetainedChannelsCard(retained, onTune, onOpenList, onStop, onStopAll, capsule, modifier)
}

/**
 * Kept Live dock card (Logan 2026-10-05): the exact shape of the cast card
 * (CastMiniController inside CastTransportCard): the same capsule and
 * floating nav chrome, 8 dp vertical padding, 18 dp (tablet) or 12 dp (phone)
 * side padding, 40 dp tile, 12 dp gap, a bold title and an accent subtitle,
 * and a 48 dp trailing control, so both cards measure the same height and
 * radius. Several kept channels stay one capsule; tap opens the list.
 */
@Composable
private fun RetainedChannelsCard(
    retained: List<com.aeriotv.android.core.timeshift.TimeshiftController.RetainedChannel>,
    onTune: (String) -> Unit,
    onOpenList: () -> Unit,
    onStop: (String) -> Unit,
    onStopAll: () -> Unit,
    tablet: Boolean,
    modifier: Modifier,
) {
    val single = retained.size == 1
    val latest = retained.last()
    val capsuleShape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50)
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .floatingNavChrome(capsuleShape)
            .clip(capsuleShape)
            .clickable(onClick = { if (single) onTune(latest.channelId) else onOpenList() })
            .padding(horizontal = if (tablet) 18.dp else 12.dp, vertical = RetainedCardVerticalPadding),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        val logo = latest.logoUrl
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(40.dp)
                .clip(com.aeriotv.android.core.ui.artworkTileShape(6.dp, model = if (single) logo else null))
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            if (single && !logo.isNullOrBlank()) {
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
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            androidx.compose.material3.Text(
                text = if (single) "Keeping ${latest.channelName} live"
                else "Keeping ${retained.size} channels live",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            androidx.compose.material3.Text(
                text = if (single) "Tap to watch ${latest.channelName}"
                else retained.asReversed().joinToString(", ") { it.channelName },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.textAccent,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.heightIn(min = RetainedCardControlHeight),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            if (single) {
                RetainedCardButton(label = "Stop", onClick = { onStop(latest.channelId) })
            } else {
                RetainedCardButton(label = "Stop All", onClick = onStopAll)
            }
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
