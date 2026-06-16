package com.aeriotv.android.feature.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * How the Live TV channel groups are ordered. Default keeps the playlist's
 * first-occurrence (source) order; Alphabetical sorts A-Z; Manual uses the
 * user's saved drag/move order. Android-only enhancement (iOS groups are
 * always source-ordered).
 */
enum class GroupSortMode {
    Default, Alphabetical, Manual;

    val label: String
        get() = when (this) {
            Default -> "Default"
            Alphabetical -> "A-Z"
            Manual -> "Manual"
        }

    companion object {
        fun from(name: String?): GroupSortMode =
            entries.firstOrNull { it.name == name } ?: Default
    }
}

/**
 * Apply the user's group sort preference to the live group-name list.
 * - [GroupSortMode.Default]: the order passed in (source / first-occurrence).
 * - [GroupSortMode.Alphabetical]: case-insensitive A-Z.
 * - [GroupSortMode.Manual]: the saved order, reconciled against the live groups
 *   (saved entries first in their saved order, then any new groups appended in
 *   source order; removed/renamed groups drop out). Robust to playlist changes.
 */
fun orderGroups(
    allGroups: List<String>,
    sortMode: GroupSortMode,
    savedOrder: List<String>,
): List<String> = when (sortMode) {
    GroupSortMode.Default -> allGroups
    GroupSortMode.Alphabetical -> allGroups.sortedBy { it.lowercase() }
    GroupSortMode.Manual -> {
        val live = allGroups.toSet()
        val ordered = savedOrder.filter { it in live }
        val orderedSet = ordered.toSet()
        ordered + allGroups.filterNot { it in orderedSet }
    }
}

private fun moveInList(list: List<String>, item: String, delta: Int): List<String> {
    val idx = list.indexOf(item)
    if (idx < 0) return list
    val target = (idx + delta).coerceIn(0, list.lastIndex)
    if (target == idx) return list
    return list.toMutableList().apply { add(target, removeAt(idx)) }
}

/**
 * Manage Groups bottom sheet. Mirrors iOS Settings > Manage Groups modal:
 * a scrollable checkbox list with "All / None" toggles in the header. Checked
 * groups stay visible in the Live TV filter row; unchecked groups disappear
 * from the chips but their channels remain in "All" so they can still be
 * found via search.
 *
 * Hide/show persists via the [hiddenGroups] set in AppPreferences (working copy,
 * committed on Done). When [reorderEnabled] (Live TV only), a sort selector is
 * shown (Default / A-Z / Manual); in Manual mode each row gets a drag handle for
 * touch reordering, committed via [onReorder] on drag-stop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageGroupsSheet(
    allGroups: List<String>,
    hiddenGroups: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    reorderEnabled: Boolean = false,
    sortMode: GroupSortMode = GroupSortMode.Default,
    onSortModeChange: (GroupSortMode) -> Unit = {},
    onReorder: (List<String>) -> Unit = {},
) {
    var working by remember(hiddenGroups) { mutableStateOf(hiddenGroups.toMutableSet()) }
    val manualReorder = reorderEnabled && sortMode == GroupSortMode.Manual

    com.aeriotv.android.ui.FormFactorModal(
        onDismiss = onDismiss,
        tvWidthFraction = 0.6f,
        tvMaxHeight = 620.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 600.dp)
                .padding(bottom = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Manage Groups",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    onSave(working.toSet())
                    onDismiss()
                }) {
                    Text("Done", color = MaterialTheme.colorScheme.primary)
                }
            }
            if (reorderEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Order",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                    )
                    GroupSortMode.entries.forEach { mode ->
                        val selected = mode == sortMode
                        TextButton(onClick = { onSortModeChange(mode) }) {
                            Text(
                                text = mode.label,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (manualReorder) "Drag to reorder, check to show or hide."
                    else "Check groups to show, uncheck to hide.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { working = mutableSetOf() }) {
                    Text("All", color = MaterialTheme.colorScheme.primary)
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                TextButton(onClick = { working = allGroups.toMutableSet() }) {
                    Text("None", color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            if (allGroups.isEmpty()) {
                // Mirrors iOS ManageGroupsSheet.swift line 50-53 empty
                // state. Hit when the active playlist has no #EXTINF
                // group-title field set on any channel -- the Manage
                // button still appears but tapping it would otherwise
                // show a blank list with no explanation.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No groups available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }
            if (manualReorder) {
                // Local working copy so the drag preview updates fluidly; the
                // new order commits to DataStore on drag-stop via onReorder
                // (same pattern as the Favorites tab).
                val lazyListState = rememberLazyListState()
                var workingOrder by remember(allGroups) { mutableStateOf(allGroups) }
                LaunchedEffect(allGroups) { workingOrder = allGroups }
                val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
                    workingOrder = workingOrder.toMutableList().apply {
                        add(to.index, removeAt(from.index))
                    }
                }
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 6.dp),
                ) {
                    items(workingOrder, key = { it }) { group ->
                        ReorderableItem(reorderState, key = group) { _ ->
                            val visible = group !in working
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        working = working.toMutableSet().apply {
                                            if (visible) add(group) else remove(group)
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = visible,
                                    onCheckedChange = { checked ->
                                        working = working.toMutableSet().apply {
                                            if (checked) remove(group) else add(group)
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = group,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = "Drag to reorder",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .draggableHandle(
                                            onDragStopped = { onReorder(workingOrder) },
                                        )
                                        .size(24.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(allGroups, key = { it }) { group ->
                        val visible = group !in working
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    working = working.toMutableSet().apply {
                                        if (visible) add(group) else remove(group)
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = visible,
                                onCheckedChange = { checked ->
                                    working = working.toMutableSet().apply {
                                        if (checked) remove(group) else add(group)
                                    }
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = group,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * TV-native group on/off picker: a centered, D-pad-driven dialog instead of the
 * touch bottom sheet. Each row toggles live and the focused row is highlighted,
 * mirroring the tvOS guide's "Toggle groups on or off" popup. [onToggle] passes
 * the group and its new visibility so the caller updates hiddenGroups.
 *
 * When [reorderEnabled] (Live TV only), a sort selector is shown (Default / A-Z
 * / Manual). In Manual mode a row enters "move mode" on a long-press of the
 * D-pad center; while moving, Up/Down reposition the group and OK/Back commit
 * the new order via [onReorder] (Multiview Move-Tile parity).
 */
@Composable
fun TvGroupPicker(
    allGroups: List<String>,
    hiddenGroups: Set<String>,
    onToggle: (group: String, visible: Boolean) -> Unit,
    onDismiss: () -> Unit,
    reorderEnabled: Boolean = false,
    sortMode: GroupSortMode = GroupSortMode.Default,
    onSortModeChange: (GroupSortMode) -> Unit = {},
    onReorder: (List<String>) -> Unit = {},
) {
    val manualReorder = reorderEnabled && sortMode == GroupSortMode.Manual
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .width(640.dp)
                .heightIn(max = 640.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 20.dp)) {
                Text(
                    text = if (manualReorder) {
                        "Hold OK on a group to move it, then use up and down. OK or Back to finish."
                    } else {
                        "Toggle groups on or off to show or hide them."
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                )
                if (reorderEnabled) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GroupSortMode.entries.forEach { mode ->
                            val selected = mode == sortMode
                            var chipFocused by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .padding(end = 10.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                        else Color.Transparent,
                                    )
                                    .border(
                                        width = if (chipFocused) 2.dp else 0.dp,
                                        color = if (chipFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                    .onFocusChanged { chipFocused = it.isFocused }
                                    .clickable { onSortModeChange(mode) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                if (allGroups.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No groups available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    return@Column
                }
                val firstFocus = remember { FocusRequester() }
                val moveFocus = remember { FocusRequester() }
                var workingOrder by remember(allGroups) { mutableStateOf(allGroups) }
                LaunchedEffect(allGroups) { workingOrder = allGroups }
                var movingGroup by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
                // Keep the D-pad cursor pinned on the moving row as it changes
                // position (Multiview re-pins dpadIndex the same way).
                LaunchedEffect(movingGroup, workingOrder) {
                    if (movingGroup != null) runCatching { moveFocus.requestFocus() }
                }
                val displayGroups = if (manualReorder) workingOrder else allGroups
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(displayGroups, key = { _, g -> g }) { index, group ->
                        val visible = group !in hiddenGroups
                        val isMoving = movingGroup == group
                        var focused by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when {
                                        isMoving -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                        else -> Color.Transparent
                                    },
                                )
                                .border(
                                    width = if (focused || isMoving) 2.dp else 0.dp,
                                    color = if (focused || isMoving) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .then(
                                    when {
                                        isMoving -> Modifier.focusRequester(moveFocus)
                                        index == 0 -> Modifier.focusRequester(firstFocus)
                                        else -> Modifier
                                    },
                                )
                                .onFocusChanged { focused = it.isFocused }
                                .then(
                                    if (manualReorder) {
                                        // Manual mode handles ALL keys via onKeyEvent and stays
                                        // focusable on its own (a disabled clickable would drop
                                        // focusability the moment move mode starts, so the D-pad
                                        // could no longer reach the moving row).
                                        Modifier
                                            .focusable()
                                            .onKeyEvent { ev ->
                                                if (isMoving) {
                                                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                                                    when (ev.key) {
                                                        Key.DirectionUp -> {
                                                            workingOrder = moveInList(workingOrder, group, -1); true
                                                        }
                                                        Key.DirectionDown -> {
                                                            workingOrder = moveInList(workingOrder, group, +1); true
                                                        }
                                                        Key.DirectionCenter, Key.Enter, Key.Back -> {
                                                            movingGroup = null; onReorder(workingOrder); true
                                                        }
                                                        else -> false
                                                    }
                                                } else when {
                                                    // Long-press OK (auto-repeat / long-press flag)
                                                    // picks the group up into move mode.
                                                    ev.type == KeyEventType.KeyDown &&
                                                        (ev.key == Key.DirectionCenter || ev.key == Key.Enter) &&
                                                        (ev.nativeKeyEvent.repeatCount >= 1 ||
                                                            ev.nativeKeyEvent.isLongPress) -> {
                                                        movingGroup = group; true
                                                    }
                                                    // Short press toggles visibility on release.
                                                    ev.type == KeyEventType.KeyUp &&
                                                        (ev.key == Key.DirectionCenter || ev.key == Key.Enter) -> {
                                                        onToggle(group, !visible); true
                                                    }
                                                    else -> false
                                                }
                                            }
                                    } else {
                                        Modifier.clickable { onToggle(group, !visible) }
                                    },
                                )
                                .padding(horizontal = 22.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = group,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = when {
                                    isMoving -> "Moving"
                                    visible -> "On"
                                    else -> "Off"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (visible || isMoving) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
