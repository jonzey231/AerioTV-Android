package com.aeriotv.android.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aeriotv.android.core.data.M3UChannel

/**
 * Hold-Up "Recently Watched" overlay (Remote Control initiative, Logan spec
 * 2026-07-20): the last [com.aeriotv.android.core.preferences.AppPreferences
 * .RECENT_CHANNELS_CAP] channels from the recents LRU as a focusable list
 * over the live picture, most recent first. OK tunes, Back dismisses
 * (PlayerScreen's BackHandler guard). Channel-flip keys are blocked while
 * open (flipBlockedByChrome) so Up/Down walk this list.
 *
 * Left-anchored panel with a horizontal scrim fade, mirroring the common
 * IPTV-client convention of channel surfaces sliding in from the left while
 * the video keeps playing behind.
 */
@Composable
internal fun RecentChannelsOverlay(
    recentIds: List<String>,
    channels: List<M3UChannel>,
    currentChannelId: String?,
    nowTitleFor: (M3UChannel) -> String?,
    onSelect: (M3UChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    // Resolve ids -> channels, preserving LRU order; ids whose channel left
    // the playlist are silently skipped (the LRU is playlist-agnostic).
    val entries = remember(recentIds, channels) {
        val byId = channels.associateBy { it.id }
        recentIds.mapNotNull { byId[it] }
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(entries.isNotEmpty()) {
        if (entries.isNotEmpty()) runCatching { firstFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.88f),
                    0.42f to Color.Black.copy(alpha = 0.55f),
                    1f to Color.Transparent,
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 460.dp)
                .padding(start = 40.dp, top = 32.dp, bottom = 32.dp),
        ) {
            Text(
                text = "Recently Watched",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Spacer(Modifier.height(14.dp))
            if (entries.isEmpty()) {
                Text(
                    text = "Channels you watch will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxHeight(),
            ) {
                itemsIndexed(entries, key = { _, ch -> ch.id }) { index, ch ->
                    ChannelPickRow(
                        channel = ch,
                        nowTitle = nowTitleFor(ch),
                        isPlaying = ch.id == currentChannelId,
                        onClick = { onSelect(ch) },
                        modifier = if (index == 0) {
                            Modifier.focusRequester(firstFocus)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
        // Touch escape hatch (phone/tablet or a TV with a pointer): tapping
        // the exposed video area dismisses, matching Back.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.35f)
                .align(Alignment.CenterEnd)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }
}

/** Focusable channel row shared by the Recently Watched and Channels
 *  overlays (logo, number, name, now-playing line, Watching badge). */
@Composable
internal fun ChannelPickRow(
    channel: M3UChannel,
    nowTitle: String?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> Color.White.copy(alpha = 0.18f)
                    else -> Color.White.copy(alpha = 0.06f)
                },
            )
            .border(
                width = 2.dp,
                color = if (focused) accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (channel.tvgLogo.isNotBlank()) {
            AsyncImage(
                model = channel.tvgLogo,
                contentDescription = null,
                modifier = Modifier.size(width = 52.dp, height = 34.dp),
            )
        } else {
            Spacer(Modifier.size(width = 52.dp, height = 34.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                channel.channelNumber?.takeIf { it.isNotBlank() }?.let { num ->
                    Text(
                        text = num,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            nowTitle?.takeIf { it.isNotBlank() }?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isPlaying) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Watching",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
        }
    }
}
