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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
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
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.aeriotv.android.core.data.ChannelCollection
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.ui.settings.TvSettingsMetrics
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.settingsTitleStyle
import com.aeriotv.android.ui.tv.tvFocusScale
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

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
internal fun groupSidebarLabel(token: String): String = when (token) {
    PlaylistViewModel.ALL_GROUPS -> "All Channels"
    PlaylistViewModel.FAVORITES_GROUP -> "Favorites"
    else -> token
}

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
        // tvOS: proxy.scrollTo(target, anchor: .center). Centering clamps at
        // the top for the first rows, so the list never lands scrolled by one
        // row and then snaps back (Logan 2026-09-10: a visible hop from
        // Favorites down to All Channels on open).
        androidx.compose.runtime.withFrameNanos { }
        val viewport = listState.layoutInfo.viewportSize.height
        val rowPx = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
        runCatching { listState.scrollToItem(selectedIndex, scrollOffset = -((viewport - rowPx) / 2).coerceAtLeast(0)) }
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
            .then(if (isTv) Modifier.fillMaxWidth() else Modifier.width(panelWidth))
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
            // tvOS: 22 pt semibold in the secondary text colour (halved).
            Text(
                text = "Groups",
                style = if (isTv) MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp) else settingsTitleStyle(),
                fontWeight = if (isTv) FontWeight.SemiBold else FontWeight.Bold,
                color = if (isTv) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
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
            verticalArrangement = Arrangement.spacedBy(if (isTv) 2.dp else 3.dp),
            modifier = Modifier.fillMaxHeight(),
        ) {
            itemsIndexed(groups, key = { _, token -> token }) { index, token ->
                GroupSidebarRow(
                    label = groupSidebarLabel(token),
                    isActive = token == selectedToken,
                    leadingStar = token == PlaylistViewModel.FAVORITES_GROUP,
                    trailingPin = token == PlaylistViewModel.ALL_GROUPS,
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
    /** tvOS: star.fill before Favorites, pin.fill after the default group. */
    leadingStar: Boolean = false,
    trailingPin: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val isTv = rememberIsTvDevice()
    if (isTv) {
        // tvOS GroupSidebarRowButtonStyle (halved): 30 pt text, 20/12 padding,
        // corner 10, focused = white 16% wash + inset accent ring + white
        // text, active = accent text, semibold, accent 12% tint.
        val colors = MaterialTheme.colorScheme
        val fg = when { focused -> Color.White; isActive -> colors.primary; else -> colors.onBackground }
        val bg = when { focused -> Color.White.copy(alpha = 0.16f); isActive -> colors.primary.copy(alpha = 0.12f); else -> Color.Transparent }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .background(bg)
                .border(2.dp, if (focused) colors.primary else Color.Transparent, RoundedCornerShape(5.dp))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .focusable(interactionSource = interaction)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (leadingStar) Icon(Icons.Filled.Star, contentDescription = null, tint = fg, modifier = Modifier.size(11.dp))
            Text(
                text = label, fontSize = 15.sp, lineHeight = 18.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (trailingPin) Icon(Icons.Filled.PushPin, contentDescription = "Default group", tint = fg.copy(alpha = 0.7f), modifier = Modifier.size(7.dp))
        }
        return
    }
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
    val tv = rememberIsTvDevice()
    Row(modifier = Modifier.fillMaxHeight().padding(top = topOffset).then(if (tv) Modifier.background(MaterialTheme.colorScheme.background) else Modifier)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                // tvOS GuideGroupSidebarPane: 360 pt with 20 pt side padding
                // and 4 pt on top, halved.
                .then(if (tv) Modifier.width(180.dp).padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 12.dp)
                      else Modifier.padding(start = 20.dp, end = 12.dp, bottom = 12.dp))
                // tvOS lands on the ACTIVE group (defaultFocus). Route the
                // pane's first focus entry straight to that row so focus
                // never rests on Favorites for a frame before the
                // LaunchedEffect moves it (Logan 2026-09-10, visible jump).
                .focusGroup()
                .focusProperties {
                    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                    run {
                        enter = { focus }
                        // Focus stays inside the drawer while it is open
                        // (Logan 2026-09-10); Right commits, Back closes.
                        exit = { androidx.compose.ui.focus.FocusRequester.Cancel }
                    }
                }
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

/**
 * Phone group drawer (Apple `PhoneGroupDrawer`, ChannelListView.swift:4653-4723,
 * Logan 2026-09-05): the phone's default group selector. "CHANNEL GROUPS"
 * heading with the Manage Groups circle beside it, then Favorites, All and
 * the visible groups (collections ride along where their pill placement puts
 * them) as tight 34dp rows with no dividers. The default group carries a pin
 * (Android has no default-group setting yet, so All is the pinned row). A
 * long press lifts a row to reorder; the new order is the pill order too and
 * is written through [onReorder] without the collection tokens.
 */
@Composable
internal fun PhoneGroupDrawer(
    tokens: List<String>,
    selected: String,
    labelFor: (String) -> String,
    onSelect: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onManageGroups: () -> Unit,
    hiddenGroupCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    var order by remember(tokens) { mutableStateOf(tokens) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    LaunchedEffect(Unit) {
        val idx = tokens.indexOf(selected)
        if (idx > 0) runCatching { listState.scrollToItem(idx) }
    }
    Column(modifier = modifier.fillMaxHeight().padding(top = 2.dp)) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp),
        ) {
            Text(
                text = "CHANNEL GROUPS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                    .clickable(onClick = onManageGroups),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = if (hiddenGroupCount == 0) "Manage Groups"
                    else "Manage Groups ($hiddenGroupCount hidden)",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(order, key = { it }) { token ->
                val isCollection = token.startsWith(ChannelCollection.TOKEN_PREFIX)
                ReorderableItem(reorderState, key = token) { dragging ->
                    val isSelected = token == selected
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (dragging) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
                                else Color.Transparent,
                            )
                            .clickable { onSelect(token) }
                            // Collections keep their pill placement; only the
                            // real groups (and the pinned rows) reorder.
                            .then(
                                if (isCollection) Modifier
                                else Modifier.longPressDraggableHandle(
                                    onDragStopped = {
                                        onReorder(order.filterNot { it.startsWith(ChannelCollection.TOKEN_PREFIX) })
                                    },
                                ),
                            )
                            .heightIn(min = 34.dp)
                            .padding(start = 18.dp, end = 14.dp),
                    ) {
                        if (token == PlaylistViewModel.FAVORITES_GROUP) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                        Text(
                            text = labelFor(token),
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (token == PlaylistViewModel.ALL_GROUPS) {
                            Icon(
                                imageVector = Icons.Filled.PushPin,
                                contentDescription = "Default group",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                }
            }
            item(key = "__bottom_spacer__") { Spacer(Modifier.height(90.dp)) }
        }
    }
}

/**
 * Overlay host for [PhoneGroupDrawer]: a 45% black scrim (tap to dismiss)
 * with the drawer sliding in from the leading edge, drawn over the list or
 * the guide. Back closes it too. Place it as the LAST child of a Box that
 * wraps the screen so it paints above everything.
 */
@Composable
internal fun PhoneGroupDrawerHost(
    open: Boolean,
    onDismiss: () -> Unit,
    tokens: List<String>,
    selected: String,
    labelFor: (String) -> String,
    onSelect: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onManageGroups: () -> Unit,
    hiddenGroupCount: Int = 0,
) {
    androidx.activity.compose.BackHandler(enabled = open) { onDismiss() }
    androidx.compose.animation.AnimatedVisibility(
        visible = open,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = open,
        enter = androidx.compose.animation.slideInHorizontally { -it },
        exit = androidx.compose.animation.slideOutHorizontally { -it },
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.78f)
                .widthIn(max = 320.dp)
                .background(MaterialTheme.colorScheme.background)
                // Swallow taps so they never reach the scrim below.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .statusBarsPadding(),
        ) {
            PhoneGroupDrawer(
                tokens = tokens,
                selected = selected,
                labelFor = labelFor,
                onSelect = { onSelect(it); onDismiss() },
                onReorder = onReorder,
                onManageGroups = onManageGroups,
                hiddenGroupCount = hiddenGroupCount,
            )
        }
    }
}
