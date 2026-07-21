package com.aeriotv.android.feature.livetv

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.aeriotv.android.feature.playlist.PlaylistViewModel

/**
 * Channel-group sidebar (Remote Control initiative, Logan spec 2026-07-20):
 * the left-anchored group rail the common IPTV-client convention slides in
 * when the user presses Left from the "now" column. Shared between the two
 * surfaces that need it:
 *  - the GUIDE (short Left on a currently-airing cell), via
 *    [GuideGroupSidebarOverlay]'s fullscreen Popup, where picking a group
 *    drives the same filter as the pills row;
 *  - the PLAYER's channel-list overlay (second Left), which embeds
 *    [GroupSidebarPanel] directly as its leading pane.
 *
 * Tokens are the pill tokens: [PlaylistViewModel.ALL_GROUPS] or a raw group
 * title. Collections deliberately stay pills-only for now (their sentinel
 * lifecycle - dangling ids, hidden-group bypass - is pill-tested; fold them
 * in when the sidebar earns a settings surface).
 */
internal fun groupSidebarLabel(token: String): String =
    if (token == PlaylistViewModel.ALL_GROUPS) "All Channels" else token

@Composable
internal fun GroupSidebarPanel(
    groups: List<String>,
    selectedToken: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialFocus: FocusRequester? = null,
) {
    val listState = rememberLazyListState()
    val selectedIndex = groups.indexOf(selectedToken).coerceAtLeast(0)
    LaunchedEffect(Unit) {
        // Land with the active group visible + focused, like the common
        // IPTV-client sidebars (and unlike starting at the top of 100 groups).
        runCatching { listState.scrollToItem(selectedIndex) }
        initialFocus?.let { runCatching { it.requestFocus() } }
    }
    // Size the panel to the LONGEST group label (Logan 2026-07-20: a fixed
    // 280dp wasted space with short group names). Measure every label at the
    // row's type scale, take the widest, add the row's horizontal chrome, and
    // clamp to a sane min/max so one very long name can't dominate the guide
    // and a single short group isn't cramped. A LazyColumn can't be intrinsic-
    // measured, so this text-measure approach is the reliable way to fit.
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val rowLabelStyle = MaterialTheme.typography.bodyLarge
    val density = androidx.compose.ui.platform.LocalDensity.current
    val panelWidth = remember(groups, rowLabelStyle) {
        val widestPx = groups.maxOfOrNull { token ->
            textMeasurer.measure(
                text = groupSidebarLabel(token),
                style = rowLabelStyle.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            ).size.width
        } ?: 0
        // 16dp row padding each side + 2dp focus border each side + a little
        // breathing room past the text.
        with(density) { widestPx.toDp() } + 44.dp
    }.coerceIn(160.dp, 340.dp)
    Column(modifier = modifier.width(panelWidth)) {
        Text(
            text = "Groups",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.padding(start = 16.dp, bottom = 10.dp),
        )
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxHeight(),
        ) {
            itemsIndexed(groups, key = { _, token -> token }) { index, token ->
                GroupSidebarRow(
                    label = groupSidebarLabel(token),
                    isActive = token == selectedToken,
                    onClick = { onSelect(token) },
                    modifier = if (index == selectedIndex && initialFocus != null) {
                        Modifier.focusRequester(initialFocus)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun GroupSidebarRow(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused -> Color.White.copy(alpha = 0.18f)
                    isActive -> Color.White.copy(alpha = 0.08f)
                    else -> Color.Transparent
                },
            )
            .border(
                width = 2.dp,
                color = if (focused) accent else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isActive && !focused) accent else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * DOCKED pane for the GUIDE surface (Logan 2026-07-20): a hard, opaque
 * side menu - the guide content sits in the same Row and shifts right
 * while it is open, so the channel rail stays fully readable (no scrim,
 * no overlay). Right steps back out to the grid without changing the
 * group; Back does the same via GuideScreen's handler.
 */
@Composable
internal fun GuideGroupSidebarPane(
    groups: List<String>,
    selectedToken: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    Row(modifier = Modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 20.dp, end = 12.dp, top = 24.dp, bottom = 24.dp)
                .onPreviewKeyEvent { event ->
                    if (event.key == androidx.compose.ui.input.key.Key.DirectionRight &&
                        event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
                    ) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
        ) {
            GroupSidebarPanel(
                groups = groups,
                selectedToken = selectedToken,
                onSelect = onSelect,
                initialFocus = focus,
            )
        }
        // Hairline separating the menu from the shifted guide.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        )
    }
}
