// SettingsHost.kt
//
// Settings redesign Phase B3 (SettingsUIRedesign.md B3): the tablet list-detail
// host. A 320dp touch sidebar of selected-tonal rows on the left, the selected
// pane on the right, and a hairline between them.
//
// Hand-rolled rather than material3-adaptive, per the plan's decision: the
// library's NavigableListDetailPaneScaffold owns its own back stack, which
// would fight the one B2 just built, and it buys nothing this layout needs.
//
// Rev 2, SELECTION IS NOT THE STACK. The sidebar mutates `selection` and never
// pushes, so Back can never replay sidebar browsing. `SettingsNavState` holds
// only what was pushed ABOVE the selected pane. The posture mapping in
// [rememberSettingsPaneSelection] translates between the two when the window
// crosses the two-pane threshold (rotation, fold, resizable-window drag).

package com.aeriotv.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.ui.settings.LocalSettingsInPane
import com.aeriotv.android.ui.settings.SettingsNavRow
import com.aeriotv.android.ui.settings.SettingsSectionHeader
import com.aeriotv.android.ui.settings.TvSettingsMetrics

/** Touch sidebar width (plan B3). The TV rail widens to 300dp in B4. */
private val SidebarWidth = 320.dp

/**
 * Pane-swap crossfade (plan B5: "about 150ms crossfade on pane content swaps;
 * no shared-element transitions").
 *
 * Short on purpose. The rail commits a selection through its own debounce, so
 * a longer fade would still be running when a fast scroll lands on the next
 * row and the pane would visibly lag the highlight.
 */
private const val SettingsPaneCrossfadeMs = 150

/** Where a fresh two-pane host lands. Canon puts Playlists first. */
val DefaultSettingsPaneSelection: SettingsRoute = SettingsRoute.Playlists

/**
 * The pane selection, kept across process death and mapped across posture
 * changes.
 *
 * [twoPane] is the CURRENT layout. When it flips, the mapping runs once:
 *
 *  - two-pane -> stacked: the selection is inserted UNDER any existing pushes,
 *    so the stacked view shows exactly the page the pane was showing and Back
 *    walks out through it into the root list.
 *  - stacked -> two-pane: if the bottom of the stack is a page a pane can host
 *    (a section, Playlists, or About) it becomes the selection and is removed
 *    from the stack; deeper pushes stay, so a playlist detail opened on a phone
 *    is still open after unfolding.
 */
@Composable
fun rememberSettingsPaneSelection(
    nav: SettingsNavState,
    twoPane: Boolean,
): MutableState<SettingsRoute> {
    val selection = rememberSaveable(stateSaver = SettingsRouteSaver) {
        mutableStateOf(DefaultSettingsPaneSelection)
    }
    // Tracks the posture the mapping last ran for. Saveable so a process death
    // in one posture does not replay the mapping on restore. Starts null and is
    // seeded on first composition WITHOUT mapping: at that point the stack and
    // the selection were saved together and already agree.
    var lastTwoPane by rememberSaveable { mutableStateOf<Boolean?>(null) }
    androidx.compose.runtime.LaunchedEffect(twoPane) {
        val previous = lastTwoPane
        lastTwoPane = twoPane
        if (previous == null || previous == twoPane) return@LaunchedEffect
        if (twoPane) {
            val baseline = nav.stack.firstOrNull()
            if (baseline != null && baseline.isPaneBaseline) {
                nav.removeBaseline()
                selection.value = baseline
            }
        } else if (selection.value !is SettingsRoute.Playlists) {
            nav.insertAtBaseline(selection.value)
        }
        // Playlists is the exception: as a PANE it renders the root's playlist
        // block, and the stacked root already leads with that same block. Push
        // nothing, so collapsing shows the user the content they were looking
        // at instead of the Manage Playlists page, which is a different screen.
    }
    return selection
}

/**
 * Tablet list-detail Settings. [detail] renders the route it is handed; the
 * host decides which route that is (the selected pane, or whatever was pushed
 * above it) and whether it is a pane or a full-screen takeover.
 *
 * [takeover] returning true for a route means it fills the window and the
 * sidebar is hidden. Per plan B3 that is the log viewer only on tablet: its
 * lines want the width.
 */
@Composable
fun SettingsTwoPaneHost(
    selection: SettingsRoute,
    onSelect: (SettingsRoute) -> Unit,
    pushed: SettingsRoute?,
    sections: List<SettingsSectionGroupSpec>,
    activePlaylistName: String?,
    takeover: (SettingsRoute) -> Boolean,
    detail: @Composable (SettingsRoute) -> Unit,
) {
    // See the TV host: a takeover removes this whole subtree, so each pane's
    // scroll offset has to be retained outside it to survive the round trip.
    val paneStateHolder = rememberSaveableStateHolder()

    if (pushed != null && takeover(pushed)) {
        detail(pushed)
        return
    }
    Row(modifier = Modifier.fillMaxSize()) {
        SettingsSidebar(
            selection = selection,
            onSelect = onSelect,
            sections = sections,
            activePlaylistName = activePlaylistName,
            modifier = Modifier.width(SidebarWidth),
        )
        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (pushed != null) {
                // A push keeps its own back affordance: the sidebar is beside
                // it, not above it, so system Back is not the only way out.
                detail(pushed)
            } else {
                // Same crossfade as the TV host; see SettingsPaneCrossfadeMs.
                Crossfade(
                    targetState = selection,
                    animationSpec = tween(SettingsPaneCrossfadeMs),
                    label = "settingsPane",
                ) { paneRoute ->
                    paneStateHolder.SaveableStateProvider(encodeSettingsRoute(paneRoute)) {
                        CompositionLocalProvider(LocalSettingsInPane provides true) {
                            detail(paneRoute)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSidebar(
    selection: SettingsRoute,
    onSelect: (SettingsRoute) -> Unit,
    sections: List<SettingsSectionGroupSpec>,
    activePlaylistName: String?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item("title") {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 10.dp),
            )
        }
        // Canon order: Playlists first, then the section groups, About last.
        item("playlists") {
            SettingsNavRow(
                title = "Playlists",
                subtitle = activePlaylistName,
                icon = Icons.AutoMirrored.Filled.List,
                onClick = { onSelect(SettingsRoute.Playlists) },
                selected = selection is SettingsRoute.Playlists,
                trailingChevron = false,
                flat = true,
            )
        }
        sections.forEach { group ->
            item("header-${group.key}") {
                Column {
                    Spacer(Modifier.height(10.dp))
                    SettingsSectionHeader(group.header)
                }
            }
            items(items = group.sections, key = { "row-${it.name}" }) { section ->
                SettingsNavRow(
                    title = section.title,
                    subtitle = section.subtitle,
                    icon = section.icon,
                    onClick = { onSelect(SettingsRoute.Section(section)) },
                    selected = selection is SettingsRoute.Section &&
                        selection.section == section,
                    trailingChevron = false,
                    flat = true,
                )
            }
        }
        item("about") {
            Column {
                Spacer(Modifier.height(10.dp))
                SettingsNavRow(
                    title = "About",
                    subtitle = null,
                    icon = Icons.Outlined.Info,
                    onClick = { onSelect(SettingsRoute.About) },
                    selected = selection is SettingsRoute.About,
                    trailingChevron = false,
                    flat = true,
                )
            }
        }
    }
}

// MARK: - TV rail (plan B2/B4)

/**
 * Rail width and overscan-safe start inset for the 10-foot host.
 *
 * Sized as a FRACTION of the canvas, not by copying tvOS's point value.
 * A 1080p panel gives Android 960x540dp (1920x1080 at density 320) where
 * tvOS gets 1920x1080pt, so an identical number is twice the physical size
 * here. tvOS's rail is 430pt of 1920 = 22.4%; 220dp of 960 = 22.9%, which
 * lands the two rails at the same size on the same TV. The plan's "~300dp"
 * predated this measurement and read as 36% of the screen on the Streamer.
 *
 * The type ladder already scales correctly by the same logic (tvOS 30pt of
 * 1920 = 1.56%; bodyLarge 16sp at the 0.9 TV scale = 14.4dp of 960 = 1.5%),
 * so the narrower rail fits its labels exactly as tvOS's does.
 */
private val TvRailWidth = 220.dp

/**
 * The rail's left inset. Aliases the shared metric so full-screen takeovers
 * (which have no rail to align against) start at the exact same edge.
 */
private val TvOverscanStart = TvSettingsMetrics.overscanStart

/**
 * Debounce before a focused rail row becomes the SELECTED one.
 *
 * Focus-follows-selection means holding DOWN through the rail would
 * otherwise rebuild the detail pane once per row. The same 150ms as the
 * tvOS rail (plan A3), so the two TV apps feel identical. DPAD_RIGHT
 * FLUSHES a pending selection before moving focus, so entering the pane
 * never lands on the previous row's content.
 */
private const val RailSelectDebounceMs = 150L

/**
 * Android TV Settings: a persistent rail of categories on the left, the
 * selected pane on the right.
 *
 * Focus contract, mirroring `GroupSidebarPanel` (the proven in-app TV rail):
 *  - focusing a rail row selects it after [RailSelectDebounceMs]
 *  - DPAD_RIGHT flushes that pending selection, then moves focus into the pane
 *  - Back with focus in the pane returns it to the rail rather than exiting
 *  - Back with focus on the rail falls through to the tab bar, unchanged
 */
@Composable
fun SettingsTvRailHost(
    selection: SettingsRoute,
    onSelect: (SettingsRoute) -> Unit,
    pushed: SettingsRoute?,
    sections: List<SettingsSectionGroupSpec>,
    activePlaylistName: String?,
    takeover: (SettingsRoute) -> Boolean,
    detail: @Composable (SettingsRoute) -> Unit,
) {
    // Retains each pane's own UI state (crucially its LazyColumn scroll
    // offset) while that pane is out of composition. The takeover below
    // REMOVES the whole rail+pane subtree, so without this the pane is rebuilt
    // from scratch on the way back and lands at the top: open Add More
    // Categories from the bottom of Appearance, press Back, and you are
    // returned to the Theme list having lost your place. Declared before the
    // early return so the holder itself survives the takeover.
    val paneStateHolder = rememberSaveableStateHolder()

    if (pushed != null && takeover(pushed)) {
        // Edit / Add-playlist / log viewer / category editors replace the whole
        // host, exactly as they do today, so all the TV keyboard and IME
        // plumbing on those screens is untouched.
        detail(pushed)
        return
    }

    val railFocus = remember { FocusRequester() }
    val detailFocus = remember { FocusRequester() }
    var railHasFocus by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SettingsRoute?>(null) }

    // Debounced commit. Keyed on `pending`, so each new focus restarts the
    // timer and a fast scroll through the rail commits only where it stops.
    androidx.compose.runtime.LaunchedEffect(pending) {
        val next = pending ?: return@LaunchedEffect
        kotlinx.coroutines.delay(RailSelectDebounceMs)
        if (next != selection) onSelect(next)
    }
    val flushPending = {
        pending?.let { if (it != selection) onSelect(it) }
    }

    // A pane-level push (playlist detail, Manage Playlists, a section's own
    // sub-page) swaps the PANE's content while the rail stays on screen. Focus
    // has to follow it in, or Compose falls back to the first focusable in the
    // tree - the rail's top row - and the user is silently back on the rail
    // with a push they can no longer see. The next D-pad move then reads as a
    // rail selection and popToRoot() throws the push away. Takeovers are
    // excluded: they replace the whole host and bring their own focus.
    //
    // POPPING one is the mirror case, and it needs handling here too. In a
    // two-pane host MainScaffold's `subScreenKey` is null for a pane push AND
    // for its pop, so its focus pull never fires on either edge; without this,
    // Back left focus to Compose's fallback, which parked it on the top tab
    // bar's Live TV pill (observed on the Streamer 2026-08-05). Logan's call is
    // that Back returns to the SIDEBAR, so aim at `railFocus` - which is
    // attached to the SELECTED rail row, landing the user exactly where they
    // pushed from. Takeovers are excluded on this edge too: MainScaffold's
    // exiting-to-root branch already focuses them out correctly.
    var prevPushed by remember { mutableStateOf<SettingsRoute?>(null) }
    androidx.compose.runtime.LaunchedEffect(pushed) {
        val previous = prevPushed
        if (pushed != null && !takeover(pushed)) {
            runCatching { detailFocus.requestFocus() }
        } else if (pushed == null && previous != null && !takeover(previous)) {
            runCatching { railFocus.requestFocus() }
        }
        prevPushed = pushed
    }

    // Back in the pane returns focus to the rail instead of leaving Settings.
    // Disabled while something is pushed so the nav stack's own handler pops
    // first, and while the rail already holds focus so Back reaches the tabs.
    androidx.activity.compose.BackHandler(enabled = com.aeriotv.android.feature.main.LocalTabIsActive.current && pushed == null && !railHasFocus) {
        runCatching { railFocus.requestFocus() }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        SettingsTvRail(
            selection = selection,
            sections = sections,
            activePlaylistName = activePlaylistName,
            onFocusRoute = { pending = it },
            onClickRoute = { route ->
                pending = route
                if (route != selection) onSelect(route)
                runCatching { detailFocus.requestFocus() }
            },
            railFocus = railFocus,
            modifier = Modifier
                .width(TvRailWidth + TvOverscanStart)
                .onFocusChanged { railHasFocus = it.hasFocus }
                .onPreviewKeyEvent { event ->
                    val right = event.key == Key.DirectionRight &&
                        event.type == KeyEventType.KeyDown
                    if (right) {
                        // Apply the pending selection BEFORE crossing the
                        // boundary, or the pane the user just entered would
                        // still be showing the previously committed route.
                        flushPending()
                        runCatching { detailFocus.requestFocus() }
                        true
                    } else {
                        false
                    }
                },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(detailFocus)
                .focusGroup(),
        ) {
            if (pushed != null) {
                detail(pushed)
            } else {
                // Plan B5: a short crossfade on pane swaps, so walking the rail
                // does not hard-cut the whole right half of the screen. Content
                // only; explicitly NOT a shared-element transition.
                Crossfade(
                    targetState = selection,
                    animationSpec = tween(SettingsPaneCrossfadeMs),
                    label = "settingsPane",
                ) { paneRoute ->
                    // Keyed per pane so each section keeps its OWN scroll
                    // offset; switching rail rows still starts at the top.
                    paneStateHolder.SaveableStateProvider(encodeSettingsRoute(paneRoute)) {
                        CompositionLocalProvider(LocalSettingsInPane provides true) {
                            detail(paneRoute)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTvRail(
    selection: SettingsRoute,
    sections: List<SettingsSectionGroupSpec>,
    activePlaylistName: String?,
    onFocusRoute: (SettingsRoute) -> Unit,
    onClickRoute: (SettingsRoute) -> Unit,
    railFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    // Flatten to one list so the selected index (for initial scroll + focus) is
    // a simple lookup rather than a per-section calculation.
    val rows = remember(sections, activePlaylistName) {
        buildList {
            add(Triple(SettingsRoute.Playlists as SettingsRoute, "Playlists", activePlaylistName))
            sections.forEach { group ->
                group.sections.forEach { section ->
                    add(Triple(SettingsRoute.Section(section), section.title, section.subtitle))
                }
            }
            add(Triple(SettingsRoute.About as SettingsRoute, "About", null))
        }
    }
    val selectedIndex = rows.indexOfFirst { it.first == selection }.coerceAtLeast(0)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Land with the selected row visible and focused, like GroupSidebarPanel.
        runCatching { listState.scrollToItem(selectedIndex) }
        runCatching { railFocus.requestFocus() }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxHeight(),
        // Top inset aligns the first rail row with the DETAIL PANE'S TITLE.
        // At 8dp the rail started ~20dp above it and read as floating higher
        // than the body it belongs to (Logan, on the Streamer). tvOS lines its
        // rail up with the pane content for the same reason.
        //
        // Still budgeted so ALL rail items clear the fold: tvOS fits its 9
        // without scrolling, Android carries 11 (Remote Control is TV-only,
        // App Updates sideload-only), so the pitch stays near 40dp and the
        // inset can only spend what the budget leaves.
        contentPadding = PaddingValues(
            start = TvOverscanStart,
            end = 12.dp,
            top = 30.dp,
            bottom = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> encodeSettingsRoute(row.first) }) { index, row ->
            val (route, title, subtitle) = row
            SettingsNavRow(
                title = title,
                subtitle = subtitle,
                icon = settingsRouteIcon(route),
                onClick = { onClickRoute(route) },
                selected = route == selection,
                trailingChevron = false,
                flat = true,
                modifier = Modifier
                    .onFocusChanged { if (it.isFocused) onFocusRoute(route) }
                    .then(
                        if (index == selectedIndex) Modifier.focusRequester(railFocus)
                        else Modifier,
                    ),
            )
        }
    }
}

/** Rail/sidebar glyph for a route. Sections carry their own. */
private fun settingsRouteIcon(route: SettingsRoute) = when (route) {
    is SettingsRoute.Section -> route.section.icon
    is SettingsRoute.About -> Icons.Outlined.Info
    else -> Icons.AutoMirrored.Filled.List
}
