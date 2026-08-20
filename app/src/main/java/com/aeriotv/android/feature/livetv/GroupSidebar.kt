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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.ui.settings.TvSettingsMetrics
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.settingsTitleStyle
import com.aeriotv.android.ui.tv.tvFocusScale

/**
 * Channel-group sidebar (Remote Control initiative, Logan spec 2026-07-20):
 * the left-anchored group rail the common IPTV-client convention slides in
 * when the user holds Left from the guide grid. Shared between the two
 * surfaces that need it:
 *  - the GUIDE (hold Left on a grid cell; reversed from short Left per Logan
 *    2026-08-06), via [GuideGroupSidebarPane]'s docked pane, where picking a
 *    group drives the same filter as the pills row;
 *  - the PLAYER's channel-list overlay (second Left), which embeds
 *    [GroupSidebarPanel] directly as its leading pane.
 *
 * Row styling matches the Settings sidebar/rail (SettingsNavRow's `flat`
 * treatment, Logan 2026-08-06): plain rows on the background, primary-alpha
 * fill + border only on focus, secondaryContainer for the active group. No
 * per-row cards, no hardcoded whites.
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
    /** Fires as D-pad focus lands on a row. The guide's docked pane uses it
     *  for live group preview (Logan 2026-08-06); the player's channel-list
     *  overlay leaves it a no-op. */
    onRowFocused: (String) -> Unit = {},
    /** GH #57 (Logan 2026-08-10): opens Manage Groups from the round button
     *  beside the "Groups" header. Sidebar mode hides the pill row, and with
     *  it the only entry into hide/reorder, so the sidebar has to carry its
     *  own. Null on the player's channel-list overlay, which is a transient
     *  tuning surface with no settings affordances. */
    onManageGroups: (() -> Unit)? = null,
    /** Warning dot on that button when groups are currently hidden. */
    hiddenGroupCount: Int = 0,
) {
    val listState = rememberLazyListState()
    val manageFocus = remember { FocusRequester() }
    val selectedIndex = groups.indexOf(selectedToken).coerceAtLeast(0)
    LaunchedEffect(Unit) {
        // Land with the active group visible + focused, like the common
        // IPTV-client sidebars (and unlike starting at the top of 100 groups).
        runCatching { listState.scrollToItem(selectedIndex) }
        initialFocus?.let { runCatching { it.requestFocus() } }
    }
    val isTv = rememberIsTvDevice()
    // Size the panel to the LONGEST group label (Logan 2026-07-20: a fixed
    // 280dp wasted space with short group names). Measure every label at the
    // row's type scale, take the widest, add the row's horizontal chrome, and
    // clamp to a sane min/max so one very long name can't dominate the guide
    // and a single short group isn't cramped. A LazyColumn can't be intrinsic-
    // measured, so this text-measure approach is the reliable way to fit.
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val rowLabelStyle = groupSidebarRowStyle()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val panelWidth = remember(groups, rowLabelStyle) {
        val widestPx = groups.maxOfOrNull { token ->
            textMeasurer.measure(
                text = groupSidebarLabel(token),
                style = rowLabelStyle.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
            ).size.width
        } ?: 0
        // Row padding each side + 2dp focus border each side + a little
        // breathing room past the text.
        with(density) { widestPx.toDp() } + 44.dp
    }.coerceIn(
        // GH #57: the header now carries the Manage Groups button beside the
        // title, so a short group list must not squeeze it off the panel.
        // 10dp lead + "Groups" + 12dp gap + the 30dp circle + 10dp trail.
        if (onManageGroups != null) 200.dp else 160.dp,
        340.dp,
    )
    // GH #57 focus routing, learned on the Streamer. GuideScreen pins
    // `focusProperties { up = <top nav pills> }` on an ancestor (audit task
    // #57 escape hatch) and that ancestor wins over an override placed on the
    // sidebar's list, so a plain D-pad Up from a group row sailed past the
    // header button and landed on "Live TV" - the button rendered, took no
    // focus, and OK went to the nav bar. Intercept Up here instead, BEFORE the
    // focus engine sees it, and hand it to the button; once the button holds
    // focus, Up falls through to the inherited escape so the nav bar is still
    // one press away.
    var manageFocused by remember { mutableStateOf(false) }
    // GH #60: the intercept below must fire ONLY when the TOP row is focused.
    // The first cut grabbed EVERY Up press inside the panel, so moving up the
    // list from row N jumped straight to the Manage Groups button instead of
    // row N-1 (lpukatch, 0.4.10). Track which row holds focus and let the
    // ordinary focus search handle row-to-row travel.
    var focusedRowIndex by remember { mutableStateOf(-1) }
    Column(
        modifier = modifier
            .width(panelWidth)
            .onPreviewKeyEvent { event ->
                if (onManageGroups == null ||
                    manageFocused ||
                    focusedRowIndex != 0 ||
                    event.key != androidx.compose.ui.input.key.Key.DirectionUp ||
                    event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown
                ) {
                    false
                } else {
                    runCatching { manageFocus.requestFocus() }.isSuccess
                }
            },
    ) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 10.dp, bottom = if (isTv) 8.dp else 10.dp),
        ) {
            Text(
                text = "Groups",
                style = settingsTitleStyle(),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            // GH #57: the sidebar's own entry into hide/reorder. It sits in
            // the header rather than the list so a D-pad Right out of a row
            // still commits and closes the pane (Compose's two-dimensional
            // focus search looks within the focused row's own horizontal
            // band, and this button is above every row); Up from the top row
            // is what reaches it.
            onManageGroups?.let { open ->
                TvManageGroupsCircle(
                    hiddenGroupsCount = hiddenGroupCount,
                    onClick = open,
                    modifier = Modifier
                        .focusRequester(manageFocus)
                        .onFocusChanged { manageFocused = it.isFocused },
                )
            }
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.fillMaxHeight(),
        ) {
            itemsIndexed(groups, key = { _, token -> token }) { index, token ->
                GroupSidebarRow(
                    label = groupSidebarLabel(token),
                    isActive = token == selectedToken,
                    onClick = { onSelect(token) },
                    onFocused = {
                        focusedRowIndex = index
                        onRowFocused(token)
                    },
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

/** Row label style: the Settings rail's TV type ladder, bodyLarge on touch. */
@Composable
private fun groupSidebarRowStyle(): androidx.compose.ui.text.TextStyle {
    val base = MaterialTheme.typography.bodyLarge
    return if (rememberIsTvDevice()) {
        base.copy(
            fontSize = TvSettingsMetrics.railTitleSize,
            lineHeight = TvSettingsMetrics.railTitleLineHeight,
        )
    } else {
        base
    }
}

@Composable
private fun GroupSidebarRow(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val isTv = rememberIsTvDevice()
    // No icon column here, so the row needs its own vertical padding where
    // SettingsNavRow's icon box sets the height; the resulting pitch matches
    // the Settings rail's on the same panel.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusScale(focused, focusedScale = 1.02f)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    isActive -> MaterialTheme.colorScheme.secondaryContainer
                    else -> Color.Transparent
                },
            )
            .then(
                if (focused) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                        RoundedCornerShape(12.dp),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(
                horizontal = if (isTv) 10.dp else 14.dp,
                vertical = if (isTv) 5.dp else 10.dp,
            ),
    ) {
        Text(
            text = label,
            style = groupSidebarRowStyle(),
            fontWeight = FontWeight.Medium,
            color = if (isActive && !focused) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Debounce before a FOCUSED sidebar row becomes the previewed group, so a
 * fast scroll through the group list previews only where focus stops
 * instead of re-filtering the whole guide once per row.
 *
 * E-2 (perf campaign 2026-08-19): raised 90ms -> 300ms. At 90ms every
 * deliberate D-pad step (~200-300ms apart) fired its own full grid rebuild,
 * measured at 1.1-1.4s of main-thread work per stop on the Streamer - queue a
 * few and input dispatch times out (the group-switch ANR in tonight's
 * dropbox). At 300ms a walk through the list coalesces to one rebuild at the
 * resting row. Context for the 90ms history: Logan's 2026-08-06 "switching
 * felt slow" was the REBUILD latency, not the debounce - E-1/E-3 attack the
 * rebuild itself, and this value can come back down once a switch is cheap.
 */
private const val SidebarPreviewDebounceMs = 300L

/**
 * DOCKED pane for the GUIDE surface (Logan 2026-07-20): a hard side menu -
 * the guide content sits in the same Row and shifts right while it is open,
 * so the channel rail stays fully readable (no scrim, no overlay).
 *
 * LIVE PREVIEW (Logan 2026-08-06): focusing a row applies its group after
 * [SidebarPreviewDebounceMs], so the guide behind shows the channels before
 * the user leaves the menu. OK or Right COMMIT the focused group and close;
 * Back (GuideScreen's handler) CANCELS - reverts to the group the sidebar
 * opened with.
 *
 * [topOffset] drops the pane so its top edge lines up with the guide's
 * TIME-HEADER row instead of the sort/search controls row (Logan 2026-08-06);
 * GuideScreen measures the live offset, so a multiview banner or status-bar
 * inset above the guide is accounted for automatically. The surface fill is
 * gone for the same reason the Settings sidebar has none: the guide shifts
 * beside it, nothing overlaps, and the hairline carries the separation.
 */
@Composable
internal fun GuideGroupSidebarPane(
    groups: List<String>,
    selectedToken: String,
    /** Debounced focus preview: apply this group NOW, sidebar stays open. */
    onPreview: (String) -> Unit,
    /** OK or Right: keep this group and close the sidebar. */
    onCommit: (String) -> Unit,
    topOffset: Dp = 0.dp,
    /** GH #57: opens Manage Groups from the header button. */
    onManageGroups: (() -> Unit)? = null,
    hiddenGroupCount: Int = 0,
) {
    val focus = remember { FocusRequester() }
    // The row focus currently rests on; commits use it directly so a Right
    // that lands inside the debounce window still keeps what the user sees
    // highlighted, not the last previewed group.
    var focusedToken by remember { mutableStateOf(selectedToken) }
    LaunchedEffect(focusedToken) {
        if (focusedToken != selectedToken) {
            kotlinx.coroutines.delay(SidebarPreviewDebounceMs)
            onPreview(focusedToken)
        }
    }
    Row(modifier = Modifier.fillMaxHeight().padding(top = topOffset)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = 20.dp, end = 12.dp, bottom = 12.dp)
                .onPreviewKeyEvent { event ->
                    if (event.key == androidx.compose.ui.input.key.Key.DirectionRight &&
                        event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
                    ) {
                        onCommit(focusedToken)
                        true
                    } else {
                        false
                    }
                },
        ) {
            GroupSidebarPanel(
                groups = groups,
                selectedToken = selectedToken,
                onSelect = onCommit,
                initialFocus = focus,
                onRowFocused = { focusedToken = it },
                onManageGroups = onManageGroups,
                hiddenGroupCount = hiddenGroupCount,
            )
        }
        // Hairline separating the menu from the shifted guide; same token as
        // the Settings sidebar's divider.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        )
    }
}
