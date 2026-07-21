package com.aeriotv.android.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.feature.livetv.GroupSidebarPanel
import com.aeriotv.android.feature.livetv.groupSidebarLabel
import com.aeriotv.android.feature.playlist.PlaylistViewModel

/**
 * Player Left-press "Channels" overlay (Remote Control initiative / GH #54,
 * Logan spec 2026-07-20): the channels of the previously selected group as a
 * focusable list over the live picture. A further Left from the list slides
 * in the shared [GroupSidebarPanel] to switch groups (Right or Back returns
 * to the list); OK tunes; Back dismisses (PlayerScreen's BackHandler guard,
 * sidebar first). Group selection here is OVERLAY-LOCAL: it seeds from the
 * guide's active group on each open but never mutates the guide filter -
 * browsing channels while watching shouldn't silently re-filter the guide.
 */
@Composable
internal fun ChannelListOverlay(
    groups: List<String>,
    activeGroup: String,
    channelsFor: (String) -> List<M3UChannel>,
    currentChannelId: String?,
    nowTitleFor: (M3UChannel) -> String?,
    sidebarOpen: Boolean,
    onSidebarOpenChange: (Boolean) -> Unit,
    onGroupChange: (String) -> Unit,
    onSelect: (M3UChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = remember(activeGroup, channelsFor) { channelsFor(activeGroup) }
    val listFocus = remember { FocusRequester() }
    val sidebarFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // Focus follows the stage: list on open + whenever the sidebar closes
    // (group pick or Right/Back), sidebar row when it slides in.
    LaunchedEffect(sidebarOpen, activeGroup) {
        if (sidebarOpen) {
            runCatching { sidebarFocus.requestFocus() }
        } else {
            runCatching { listState.scrollToItem(0) }
            runCatching { listFocus.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.9f),
                    0.5f to Color.Black.copy(alpha = 0.5f),
                    1f to Color.Transparent,
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = 36.dp, top = 32.dp, bottom = 32.dp),
        ) {
            if (sidebarOpen) {
                GroupSidebarPanel(
                    groups = groups,
                    selectedToken = activeGroup,
                    onSelect = { token ->
                        onGroupChange(token)
                        onSidebarOpenChange(false)
                    },
                    initialFocus = sidebarFocus,
                    modifier = Modifier
                        // Right from the sidebar returns to the channel list
                        // without changing the group (Back does the same via
                        // PlayerScreen's guard).
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.DirectionRight &&
                                event.type == KeyEventType.KeyDown
                            ) {
                                onSidebarOpenChange(false)
                                true
                            } else {
                                false
                            }
                        },
                )
                Spacer(Modifier.width(20.dp))
            }
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    // Left from the channel list slides the group sidebar in
                    // (spec: "Left again = enter sidebar to select other
                    // groups"). Only when it isn't already showing - with it
                    // open, Left stays normal focus traversal into it.
                    .onPreviewKeyEvent { event ->
                        if (!sidebarOpen && event.key == Key.DirectionLeft &&
                            event.type == KeyEventType.KeyDown
                        ) {
                            onSidebarOpenChange(true)
                            true
                        } else {
                            false
                        }
                    },
            ) {
                Text(
                    text = groupSidebarLabel(activeGroup),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Spacer(Modifier.height(14.dp))
                if (entries.isEmpty()) {
                    Text(
                        text = "No channels in this group.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                LazyColumn(
                    state = listState,
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
                                Modifier.focusRequester(listFocus)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
        // Touch escape hatch over the exposed video area.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.3f)
                .align(Alignment.CenterEnd)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }
}
