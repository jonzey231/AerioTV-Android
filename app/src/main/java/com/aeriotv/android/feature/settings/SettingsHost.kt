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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.ui.settings.LocalSettingsInPane
import com.aeriotv.android.ui.settings.SettingsNavRow
import com.aeriotv.android.ui.settings.SettingsSectionHeader

/** Touch sidebar width (plan B3). The TV rail widens to 300dp in B4. */
private val SidebarWidth = 320.dp

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
                CompositionLocalProvider(LocalSettingsInPane provides true) {
                    detail(selection)
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
                )
            }
        }
    }
}
