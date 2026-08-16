package com.aeriotv.android.feature.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.TvOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.tv.tvFocusScale

/**
 * GH #72 (ochaos, reported on 1.8.16): selecting a channel group with no
 * channels rendered a completely blank grid / list. On TV that was a dead end:
 * with nothing focusable left on the screen, the hold-LEFT handler that opens
 * the group sidebar could never fire (onPreviewKeyEvent only runs along the
 * FOCUSED node's ancestor chain), and in sidebar mode the group pill row is
 * not composed at all, so there was no other control to move to either.
 *
 * This notice is the escape hatch. It is deliberately placed INSIDE the same
 * LazyColumn the channel rows would occupy, which does double duty: it gives
 * the user an explicit button back to every channel, AND it puts a focusable
 * node back inside the grid's key-handler subtree so hold-LEFT reaches the
 * sidebar again.
 *
 * It does NOT request focus on its own. The sidebar live-previews the focused
 * group, so scrolling onto an empty group empties the grid while the sidebar
 * still owns focus; grabbing focus here would yank the user out of the pane
 * mid-navigation.
 *
 * Apple twin: ChannelListView.emptyGroupNotice.
 */
@Composable
fun EmptyGroupNotice(
    isSearching: Boolean,
    onShowAllChannels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTv = rememberIsTvDevice()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = if (isTv) 56.dp else 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (isSearching) Icons.Outlined.SearchOff else Icons.Outlined.TvOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(if (isTv) 64.dp else 56.dp),
        )
        Text(
            text = if (isSearching) "No Matching Channels" else "No Channels in This Group",
            style = if (isTv) MaterialTheme.typography.headlineSmall
            else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = if (isSearching) {
                "No channels in this group match that search."
            } else {
                "This group has no channels yet. It fills in as soon as the provider assigns some."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        ShowAllChannelsButton(
            onClick = onShowAllChannels,
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}

/** Focus visual matches GroupSidebarRow: scale + tinted fill + accent border. */
@Composable
private fun ShowAllChannelsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val isTv = rememberIsTvDevice()
    Box(
        modifier = modifier
            .tvFocusScale(focused, focusedScale = 1.04f)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
            )
            .then(
                if (focused) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(14.dp),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(
                horizontal = if (isTv) 28.dp else 22.dp,
                vertical = if (isTv) 12.dp else 10.dp,
            ),
    ) {
        Text(
            text = "Show All Channels",
            style = if (isTv) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onBackground,
        )
    }
}
