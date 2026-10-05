package com.aeriotv.android.feature.livetv

import com.aeriotv.android.ui.theme.textAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.PushPin
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
internal fun groupSidebarLabel(token: String): String = groupDisplayName(token)

/** Hint copy under the TV sidebar's "Groups" heading. */
internal const val GROUP_DEFAULT_HINT_TV =
    "Hold Select on a group to set it as default."

@Composable
internal fun GroupSidebarPanel(
    groups: List<String>,
    selectedToken: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Per-playlist default group (GH #81, Manage Groups owns the write).
     *  Blank means nothing stored, and All Channels is then the effective
     *  default, matching Apple GroupSidebar.swift:254. */
    defaultToken: String = "",
    /** Long press (D-pad center held on TV) makes that group the default, or
     *  clears the default when it is already the pinned one. The thumbtack in
     *  the row is the only indicator (Logan 2026-09-17: no menu). */
    onSetDefault: ((String) -> Unit)? = null,
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
    /** TV drawer: hold focus inside the panel at every edge. */
    trapFocus: Boolean = false,
    /** True only where the HOST already gives the panel a finite width (the
     *  guide's docked drawer pane, which sizes itself from the same label
     *  measurement). Then the panel fills that width. Everywhere else -- the
     *  player's channel-list overlay, which hands the panel an UNBOUNDED Row
     *  slot -- the panel takes its own measured width, so a focused row's
     *  highlight ends with the panel instead of running the whole screen
     *  (Logan 2026-09-13, 0.5.2 regression report). */
    hostConstrainsWidth: Boolean = false,
    /** Row [initialFocus] moves to and focuses whenever [refocusRequest] changes. */
    refocusToken: String? = null,
    refocusRequest: Int = 0,
) {
    val listState = rememberLazyListState()
    val manageFocus = remember { FocusRequester() }
    // Once a refocus ran, [initialFocus] follows that row instead of the
    // active one (a preview can make them differ).
    var focusTargetToken by remember { mutableStateOf<String?>(null) }
    val selectedIndex = groups.indexOf(focusTargetToken ?: selectedToken).coerceAtLeast(0)
    val latestRefocusToken by androidx.compose.runtime.rememberUpdatedState(refocusToken)
    val latestGroups by androidx.compose.runtime.rememberUpdatedState(groups)
    LaunchedEffect(refocusRequest) {
        if (refocusRequest == 0 || initialFocus == null) return@LaunchedEffect
        // Let the Manage Groups sheet leave composition and the hidden-group
        // list settle first, as the guide's grid focus retry does.
        repeat(3) { androidx.compose.runtime.withFrameNanos { } }
        val token = latestRefocusToken ?: return@LaunchedEffect
        val index = latestGroups.indexOf(token)
        if (index < 0) return@LaunchedEffect
        focusTargetToken = token
        runCatching { listState.scrollToItem(index) }
        androidx.compose.runtime.withFrameNanos { }
        runCatching { initialFocus.requestFocus() }
    }
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
    val panelWidth = rememberGroupLabelPanelWidth(groups).coerceIn(
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
            .then(if (hostConstrainsWidth) Modifier.fillMaxWidth() else Modifier.width(panelWidth))
            .onPreviewKeyEvent { event ->
                val down = event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
                val key = event.key
                when {
                    // TV: focus never leaves the open drawer (Logan
                    // 2026-09-10). A focusProperties exit = Cancel trap also
                    // cancelled the in-pane hop from the list to the Manage
                    // Groups circle, so the edges are held by key instead:
                    // Left always, Up on the circle (or the top row when
                    // there is no circle), Down on the last row. Right
                    // commits in the pane, Back closes.
                    isTv && trapFocus && down && key == androidx.compose.ui.input.key.Key.DirectionLeft -> true
                    isTv && trapFocus && key == androidx.compose.ui.input.key.Key.DirectionUp &&
                        (manageFocused || (onManageGroups == null && focusedRowIndex == 0)) -> true
                    isTv && trapFocus && key == androidx.compose.ui.input.key.Key.DirectionDown &&
                        focusedRowIndex == groups.lastIndex -> true
                    onManageGroups == null ||
                        manageFocused ||
                        focusedRowIndex != 0 ||
                        key != androidx.compose.ui.input.key.Key.DirectionUp ||
                        !down -> false
                    else -> runCatching { manageFocus.requestFocus() }.getOrDefault(false)
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
        // Hold-Select hint (Logan 2026-09-18). TV only, and gated on the same
        // "Show Remote Hints" toggle as every other remote hint, so a user who
        // turned hints off does not get a new one here. Plain text, never
        // focusable, so it cannot sit in the D-pad path between the header
        // button and the first group row.
        if (isTv && onSetDefault != null) {
            val hintSettingsVm: com.aeriotv.android.feature.settings.SettingsViewModel =
                androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
            val hintsEnabled by hintSettingsVm.showRemoteHints
                .collectAsStateWithLifecycle(initialValue = true)
            if (hintsEnabled) {
                Text(
                    text = GROUP_DEFAULT_HINT_TV,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                )
            }
        }
        LazyColumn(
            state = listState,
            // The pane's exit = Cancel is inherited by the list's own focus
            // group, which blocked Up from the top row into the Manage
            // Groups circle (Logan 2026-09-10). Restore the default here so
            // only leaving the PANE is cancelled.
            verticalArrangement = Arrangement.spacedBy(if (isTv) 2.dp else 3.dp),
            modifier = Modifier.fillMaxHeight().focusProperties { @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class) run { exit = { FocusRequester.Default } } },
        ) {
            itemsIndexed(groups, key = { _, token -> token }) { index, token ->
                GroupSidebarRow(
                    label = groupSidebarLabel(token),
                    isActive = token == selectedToken,
                    leadingStar = token == PlaylistViewModel.FAVORITES_GROUP,
                    trailingPin = token == defaultToken ||
                        (token == PlaylistViewModel.ALL_GROUPS && defaultToken.isBlank()),
                    onClick = { onSelect(token) },
                    onSetDefault = onSetDefault?.let { set -> { set(token) } },
                    onFocused = {
                        focusedRowIndex = index
                        onRowFocused(token)
                    },
                    modifier = (if (index == selectedIndex && initialFocus != null) {
                        Modifier.focusRequester(initialFocus)
                    } else {
                        Modifier
                    })
                        // Up from the top row reaches Manage Groups by
                        // focus property as well as the key intercept above:
                        // the pane's exit=Cancel trap otherwise swallows it.
                        .then(if (index == 0 && onManageGroups != null) Modifier.focusProperties { up = manageFocus } else Modifier),
                )
            }
        }
    }
}

/**
 * Width that fits the LONGEST group label at the sidebar row's own type scale,
 * plus the row's horizontal chrome (padding each side, the 2dp focus border
 * each side and a little breathing room). Callers clamp it to their own
 * min/max; rows ellipsize past that. Recomputed whenever the group list or the
 * row style changes, so a playlist switch resizes the panel.
 *
 * A LazyColumn cannot be intrinsic-measured, so measuring the text is the
 * reliable way to fit the panel to its content.
 */
@Composable
internal fun rememberGroupLabelPanelWidth(groups: List<String>): Dp {
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val rowLabelStyle = groupSidebarRowStyle()
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(groups, rowLabelStyle, density) {
        val widestPx = groups.maxOfOrNull { token ->
            textMeasurer.measure(
                text = groupSidebarLabel(token),
                style = rowLabelStyle.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
            ).size.width
        } ?: 0
        with(density) { widestPx.toDp() } + 44.dp
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
    /** Long press: set this group as the Live TV default (or clear it). */
    onSetDefault: (() -> Unit)? = null,
    /** tvOS: star.fill before Favorites, pin.fill after the default group. */
    leadingStar: Boolean = false,
    trailingPin: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val isTv = rememberIsTvDevice()
    // Same long-press mechanism the channel rows use: onLongClick fires while
    // OK is still held, and the guard swallows the release so it cannot also
    // register as a select (core/tv/TvMenuGuard).
    val menuGuard = com.aeriotv.android.core.tv.rememberTvMenuGuard()
    val rowClick: Modifier = if (onSetDefault == null) {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        Modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = menuGuard.wrap(onClick),
            onLongClick = { onSetDefault(); menuGuard.arm() },
        )
    }
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
                .then(rowClick)
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
            .then(rowClick)
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
private const val SidebarPreviewDebounceMs = 150L

private const val GroupSidebarLogTag = "GroupSidebar"

/**
 * Debounced focus preview shared by BOTH sidebar hosts (the guide drawer and
 * the player's channel-list overlay): moving D-pad focus onto a row applies
 * that group [SidebarPreviewDebounceMs] later, through the SAME callback
 * Select uses, so persistence, the guide window, the channel-list rebuild and
 * the focus model all follow one code path. Focus stays in the sidebar.
 *
 * Keyed on both tokens: the next focus change cancels the pending apply, and
 * once a preview lands (active == focused) the effect restarts and no-ops, so
 * the already active group is never re-applied. A Select commits immediately
 * and tears this down with the sidebar.
 */
@Composable
internal fun GroupFocusPreview(
    focusedToken: String,
    activeToken: String,
    onPreview: (String) -> Unit,
) {
    LaunchedEffect(focusedToken, activeToken) {
        if (focusedToken == activeToken) return@LaunchedEffect
        kotlinx.coroutines.delay(SidebarPreviewDebounceMs)
        android.util.Log.d(GroupSidebarLogTag, "focus preview -> ${groupSidebarLabel(focusedToken)}")
        onPreview(focusedToken)
    }
}

/**
 * DOCKED pane for the GUIDE surface (Logan 2026-07-20): a hard side menu -
 * the guide content sits in the same Row and shifts right while it is open,
 * so the channel rail stays fully readable (no scrim, no overlay).
 *
 * LIVE PREVIEW (Apple TV parity, 2026-09-14): focusing a row applies its
 * group after [SidebarPreviewDebounceMs] WITHOUT persisting, so the guide
 * behind shows the channels before the user leaves the menu. OK or Right
 * COMMIT (persist) the focused group and close; any other close (GuideScreen)
 * restores the group the sidebar opened with. Null [onPreview] = no preview
 * (the phone drawer, which is touch driven).
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
    /** Per-playlist default group token; blank falls back to All Channels. */
    defaultToken: String = "",
    /** Long press on a row sets or clears the Live TV default group. */
    onSetDefault: ((String) -> Unit)? = null,
    /** Debounced focus preview: apply this group NOW (not persisted), sidebar stays open. */
    onPreview: ((String) -> Unit)? = null,
    /** OK or Right: keep this group and close the sidebar. */
    onCommit: (String) -> Unit,
    topOffset: Dp = 0.dp,
    /** GH #57: opens Manage Groups from the header button. */
    onManageGroups: (() -> Unit)? = null,
    hiddenGroupCount: Int = 0,
    /** Row to refocus each time [refocusRequest] changes (after Manage Groups closes). */
    refocusToken: String? = null,
    refocusRequest: Int = 0,
) {
    val focus = remember { FocusRequester() }
    // The row focus currently rests on; commits use it directly so a Right
    // that lands inside the debounce window still keeps what the user sees
    // highlighted, not the last previewed group.
    var focusedToken by remember { mutableStateOf(selectedToken) }
    // Leaving composition (close or commit) cancels a pending debounce.
    if (onPreview != null) {
        GroupFocusPreview(focusedToken = focusedToken, activeToken = selectedToken, onPreview = onPreview)
    }
    val tv = rememberIsTvDevice()
    // Fit the drawer to the longest group name instead of the fixed tvOS 180dp
    // (Logan 2026-09-13: names like "Auto | Football | N..." were truncated).
    // Floor = the old 180dp so short lists look unchanged; ceiling = 40% of the
    // screen so one very long name cannot swallow the guide (rows ellipsize
    // past it). 20dp covers the pane's own 10dp side padding.
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val measuredPaneWidth = rememberGroupLabelPanelWidth(groups)
    val tvPaneWidth = (measuredPaneWidth + 20.dp).coerceIn(180.dp, screenWidth * 0.4f)
    Row(modifier = Modifier.fillMaxHeight().padding(top = topOffset).then(if (tv) Modifier.background(MaterialTheme.colorScheme.background) else Modifier)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                // tvOS GuideGroupSidebarPane: 360 pt with 20 pt side padding
                // and 4 pt on top, halved.
                .then(if (tv) Modifier.width(tvPaneWidth).padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 12.dp)
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
                defaultToken = defaultToken,
                onSetDefault = onSetDefault,
                onSelect = onCommit,
                initialFocus = focus,
                onRowFocused = { focusedToken = it },
                onManageGroups = onManageGroups,
                hiddenGroupCount = hiddenGroupCount,
                refocusToken = refocusToken,
                refocusRequest = refocusRequest,
                trapFocus = true,
                hostConstrainsWidth = true,
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
 * Every horizontal metric the phone drawer uses, in ONE place, so the
 * fitted-width math and the row that draws itself can never drift apart
 * (Apple `PhoneDrawerMetrics`, ChannelListView.swift).
 */
internal object PhoneDrawerMetrics {
    /** Row label size. */
    val rowFontSize = 15.sp
    /** Leading status glyph (Favorites star). */
    val rowIconSize = 13.dp
    /** Spacing between every element of the row. */
    val rowSpacing = 8.dp
    /** Minimum gap the label keeps before the pin column. */
    val labelSpacerMin = 4.dp
    /** Pin touch target (square); the glyph inside stays small. */
    val pinTouchSide = 44.dp
    val pinGlyphSize = 13.dp
    /** Row horizontal insets. */
    val rowInsetLeading = 18.dp
    val rowInsetTrailing = 4.dp
    /** Header row: horizontal padding, gap before the circle, circle side. */
    val headerHPadding = 18.dp
    val headerGap = 12.dp
    val headerCircleSide = 34.dp
    val headerFontSize = 12.sp
    val headerTracking = 1.2.sp
    /** Row height; the 44dp pin target overflows it instead of growing it. */
    val rowMinHeight = 34.dp
    /** Slack so sub-pixel measurement differences never truncate a label. */
    val safety = 4.dp
    /** Never narrower than this, however short the group names are. */
    val minWidth = 200.dp
    /** Gutter the drawer always leaves on the trailing side of the screen,
     *  so a very long group name can never cover the whole window. */
    val screenGutter = 24.dp

    /** Everything a row reserves horizontally OUTSIDE the label text. */
    val rowChrome: Dp
        get() = rowInsetLeading + rowInsetTrailing + labelSpacerMin + rowSpacing + pinTouchSide
}

/**
 * Fitted phone-drawer width: the wider of the longest group row and the
 * header row, floored at max(200dp, header row) and capped only at the screen
 * width minus [PhoneDrawerMetrics.screenGutter] (Logan 2026-10-04: phones and
 * tablets alike follow the longest name; no fixed tablet cap). It grows AND shrinks with the token list; names past the
 * ceiling ellipsize.
 *
 * Measured with a [androidx.compose.ui.text.TextMeasurer] at the row's exact
 * bold style (the widest a row ever draws) and recomputed only when the token
 * list or the font scale changes. NO BoxWithConstraints / SubcomposeLayout:
 * this app crashes when one lands inside an intrinsic-measured parent, so the
 * screen width comes from LocalConfiguration.
 */
@Composable
internal fun rememberPhoneDrawerWidth(
    tokens: List<String>,
    labelFor: (String) -> String,
): Dp {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fontScale = density.fontScale
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidth = config.screenWidthDp.dp
    val M = PhoneDrawerMetrics
    val rowStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = M.rowFontSize,
        fontWeight = FontWeight.Bold,
    )
    val headerStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = M.headerFontSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = M.headerTracking,
    )
    return remember(tokens, fontScale, screenWidth, rowStyle, headerStyle, density) {
        fun textWidth(text: String, style: androidx.compose.ui.text.TextStyle): Dp {
            val px = measurer.measure(text = text, style = style, maxLines = 1).size.width
            return with(density) { kotlin.math.ceil(px.toFloat()).toInt().toDp() }
        }
        val header = (M.headerHPadding * 2) + textWidth("CHANNEL GROUPS", headerStyle) +
            M.headerGap + M.headerCircleSide + M.safety
        val widestRow = tokens.maxOfOrNull { token ->
            var w = textWidth(labelFor(token), rowStyle)
            if (token == PlaylistViewModel.FAVORITES_GROUP) w += M.rowIconSize + M.rowSpacing
            w + M.rowChrome + M.safety
        } ?: 0.dp
        val floor = maxOf(M.minWidth, header)
        val ceiling = maxOf(floor, screenWidth - M.screenGutter)
        minOf(maxOf(widestRow, floor), ceiling)
    }
}

/**
 * Phone group drawer (Apple `PhoneGroupDrawer`, ChannelListView.swift:4653-4723,
 * Logan 2026-09-05): the phone's default group selector. "CHANNEL GROUPS"
 * heading with the Manage Groups circle beside it, then Favorites, All and
 * the visible groups (collections ride along where their pill placement puts
 * them) as tight 34dp rows with no dividers. The default group carries a pin,
 * driven by the per-playlist Default Group that Manage Groups writes (GH #81);
 * with nothing stored, All Channels is the effective default and keeps it. A
 * long press lifts a row to reorder; the new order is the pill order too and
 * is written through [onReorder] without the collection tokens.
 *
 * Each group row (not the collections) carries a TAPPABLE thumbtack at its
 * trailing edge that sets or clears the per-playlist default. Apple parity
 * (Logan 2026-09-18): a stationary long press used to do this, but it
 * collided with the list's own hold-and-drag reorder, so a reorder also
 * pinned the moved group.
 */
@Composable
internal fun PhoneGroupDrawer(
    tokens: List<String>,
    selected: String,
    /** Per-playlist default group token; blank falls back to All Channels. */
    defaultToken: String = "",
    labelFor: (String) -> String,
    onSelect: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onManageGroups: () -> Unit,
    /** Trailing thumbtack tap pins that group as the per-playlist default, or
     *  clears it when it is already pinned. Apple parity (Logan 2026-09-18);
     *  null leaves the drawer reorder-only with no pin column. */
    onSetDefault: ((String) -> Unit)? = null,
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
        // No gesture hint here (Logan 2026-09-18): the tappable pin is
        // self-explanatory. The TV sidebar keeps its own Hold Select hint.
        Spacer(Modifier.height(8.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(order, key = { it }) { token ->
                val isCollection = token.startsWith(ChannelCollection.TOKEN_PREFIX)
                ReorderableItem(reorderState, key = token) { dragging ->
                    val isSelected = token == selected
                    val M = PhoneDrawerMetrics
                    // The default group. Unchanged rule: the stored token, or
                    // All Channels while nothing is stored.
                    val isDefault = token == defaultToken ||
                        (token == PlaylistViewModel.ALL_GROUPS && defaultToken.isBlank())
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (dragging) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
                                else Color.Transparent,
                            )
                            .heightIn(min = M.rowMinHeight),
                    ) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(M.rowSpacing),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSelect(token) }
                                // Collections keep their pill placement; only
                                // the real groups reorder.
                                .then(
                                    if (isCollection) Modifier
                                    else Modifier.longPressDraggableHandle(
                                        onDragStopped = {
                                            onReorder(
                                                order.filterNot {
                                                    it.startsWith(ChannelCollection.TOKEN_PREFIX)
                                                },
                                            )
                                        },
                                    ),
                                )
                                .heightIn(min = M.rowMinHeight)
                                .padding(start = M.rowInsetLeading),
                        ) {
                            if (token == PlaylistViewModel.FAVORITES_GROUP) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(M.rowIconSize),
                                )
                            }
                            Text(
                                text = labelFor(token),
                                fontSize = M.rowFontSize,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.textAccent
                                else MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(M.labelSpacerMin))
                        }
                        // Tappable thumbtack. 44dp touch target on a 34dp row:
                        // requiredSize lets the target overflow the row
                        // vertically instead of growing it.
                        Box(
                            modifier = Modifier
                                .width(M.pinTouchSide)
                                .height(M.rowMinHeight),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            if (!isCollection && onSetDefault != null) {
                                Box(
                                    modifier = Modifier
                                        .requiredSize(M.pinTouchSide)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            // Tapping All while it is only the
                                            // IMPLICIT default is a no-op:
                                            // there is nothing to clear.
                                            val implicitAll = token == PlaylistViewModel.ALL_GROUPS &&
                                                defaultToken.isBlank()
                                            if (!implicitAll) onSetDefault(token)
                                        },
                                    contentAlignment = androidx.compose.ui.Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = if (isDefault) Icons.Filled.PushPin
                                        else Icons.Outlined.PushPin,
                                        contentDescription = if (isDefault) "Clear default group"
                                        else "Set as default group",
                                        tint = if (isDefault) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                        modifier = Modifier.size(M.pinGlyphSize),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(M.rowInsetTrailing))
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
    /** Per-playlist default group token; blank falls back to All Channels. */
    defaultToken: String = "",
    labelFor: (String) -> String,
    onSelect: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onManageGroups: () -> Unit,
    /** Thumbtack tap pins the default group; see [PhoneGroupDrawer]. */
    onSetDefault: ((String) -> Unit)? = null,
    hiddenGroupCount: Int = 0,
) {
    // One width for the surface AND the slide animation: the drawer fits its
    // longest label instead of a fixed 78 percent of the screen.
    val drawerWidth = rememberPhoneDrawerWidth(tokens, labelFor)
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
                .width(drawerWidth)
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
                defaultToken = defaultToken,
                labelFor = labelFor,
                onSelect = { onSelect(it); onDismiss() },
                onReorder = onReorder,
                onManageGroups = onManageGroups,
                onSetDefault = onSetDefault,
                hiddenGroupCount = hiddenGroupCount,
            )
        }
    }
}
