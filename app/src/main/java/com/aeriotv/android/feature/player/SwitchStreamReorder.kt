package com.aeriotv.android.feature.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import com.aeriotv.android.ui.settings.dpadFocusRing
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.util.Log
import com.aeriotv.android.core.pip.isTelevision
import com.aeriotv.android.ui.scale.DropdownMenu

/**
 * Switch Stream rows for Dispatcharr ADMINS: the same radio rows, plus a way
 * to change the channel's stream order. Touch drags a handle (no menu, no
 * long press: the row itself only selects); TV opens a Move Up / Move Down
 * menu from the row's options button or a long press. Every committed move
 * hands the FULL new id order to [onReorder]. On TV [saving] disables further
 * moves while the PATCH is in flight; touch drags stay live (the host
 * serializes saves) so a drag started right after a drop is not lost.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReorderableStreamList(
    streams: List<StreamOption>,
    currentStreamId: Int?,
    saving: Boolean,
    onSelect: (Int) -> Unit,
    onReorder: (List<Int>) -> Unit,
    /** Touch: true while a row is being dragged by its handle, so the host sheet can drop its own drag. */
    onDragActiveChange: (Boolean) -> Unit = {},
    /** Touch: Reorder mode is on (Apple parity: the sheet's Reorder / Finish
     *  button). Handles show and rows drag only while true; row taps select
     *  only while false. */
    touchReordering: Boolean = false,
    /** Touch: the full id order after each drop. The sheet saves it ONCE when
     *  Reorder mode ends (Finish or dismiss), not per drop. */
    onPendingOrder: (List<Int>) -> Unit = {},
) {
    val context = LocalContext.current
    val isTv = remember(context) { context.isTelevision() }
    // Local order so a drag can preview; reset whenever the server list changes.
    var order by remember { mutableStateOf(streams) }
    var dragIndex by remember { mutableStateOf(-1) }
    // After every save (success or failure) show the server's refetched order,
    // but never under a finger: a refetch landing mid-drag used to reset the
    // order and the drag index, and the drag died. It applies on the drop.
    // Touch Reorder mode keeps the local order until Finish saves it.
    val holdLocal = !isTv && touchReordering
    androidx.compose.runtime.LaunchedEffect(streams, saving, dragIndex, holdLocal) {
        if (!saving && dragIndex < 0 && !holdLocal) order = streams
    }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(1f) }
    val latestOrder by rememberUpdatedState(order)
    // The handle's pointerInput outlives recompositions (keyed by stream id
    // only), so it reads the newest server list and callbacks through these.
    val latestStreams by rememberUpdatedState(streams)
    val latestOnDragActive by rememberUpdatedState(onDragActiveChange)
    val latestOnPendingOrder by rememberUpdatedState(onPendingOrder)

    fun move(from: Int, to: Int) {
        if (saving || from !in order.indices || to !in order.indices || from == to) return
        val next = order.toMutableList().apply { add(to, removeAt(from)) }
        order = next
        onReorder(next.map { it.id })
    }

    if (saving) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                text = "  Saving Order",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    order.forEachIndexed { index, stream ->
      // Keyed by stream id so a swap mid-drag keeps the dragged row's handle
      // (and its pointerInput) alive. Unkeyed, the slot's handle restarted on
      // the first swap, the gesture died, and the sheet took the finger.
      androidx.compose.runtime.key(stream.id) {
        var menuOpen by remember(stream.id) { mutableStateOf(false) }
        // TV D-pad: the options button sits INSIDE the row's bounds, so a
        // directional search from the focused row rejects it (Compose never
        // offers a candidate the focused node contains; same reason as the
        // GH #79 reveal button in TvFocus.kt). Right from the row and Left
        // from the button are routed explicitly instead.
        val rowFocus = remember(stream.id) { FocusRequester() }
        val moreFocus = remember(stream.id) { FocusRequester() }
        var rowFocused by remember(stream.id) { mutableStateOf(false) }
        val dragging = dragIndex == index
        if (isTv) {
            // TV (Apple TV parity): the row (radio + title) is ONE focus stop
            // whose ring hugs the row content, and the options button is a
            // visibly separate rounded button to its right with its own ring.
            // Right from the row reaches it, Left leaves it. Long press on the
            // row still opens the same Move Up / Move Down menu.
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .dpadFocusRing(shape, washTint = MaterialTheme.colorScheme.primary)
                        .focusRequester(rowFocus)
                        .onPreviewKeyEvent { event ->
                            rowFocused && !saving &&
                                event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight &&
                                runCatching { moreFocus.requestFocus() }.isSuccess
                        }
                        .onFocusChanged {
                            rowFocused = it.isFocused
                            if (it.isFocused) com.aeriotv.android.ui.tv.TvFocusTrace.focus("switch:row$index")
                            else com.aeriotv.android.ui.tv.TvFocusTrace.blurred("switch:row$index")
                        }
                        .combinedClickable(
                            onClick = { onSelect(stream.id) },
                            onLongClick = { if (!saving) menuOpen = true },
                        )
                        .padding(vertical = 6.dp)
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Display only: the row is the select target on TV.
                    RadioButton(
                        selected = currentStreamId == stream.id,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Text(
                        text = stream.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(56.dp)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f), shape)
                        .dpadFocusRing(shape, washTint = MaterialTheme.colorScheme.primary)
                        .focusRequester(moreFocus)
                        .onPreviewKeyEvent { event ->
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft &&
                                runCatching { rowFocus.requestFocus() }.isSuccess
                        }
                        .onFocusChanged {
                            if (it.isFocused) com.aeriotv.android.ui.tv.TvFocusTrace.focus("switch:more$index")
                            else com.aeriotv.android.ui.tv.TvFocusTrace.blurred("switch:more$index")
                        }
                        .clickable(enabled = !saving) { menuOpen = true },
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Stream Order Options",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                    StreamMoveMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        canMoveUp = index > 0,
                        canMoveDown = index < order.lastIndex,
                        onMove = { delta -> menuOpen = false; move(index, index + delta) },
                    )
                }
            }
        } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowHeightPx = it.height.toFloat().coerceAtLeast(1f) }
                .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tap on the radio + title selects. The handle sits OUTSIDE this
            // click target, so a press held on the handle never selects the
            // stream on release. No long press on touch: it opened the TV
            // Move Up / Move Down menu under a finger resting on the handle.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !touchReordering) { onSelect(stream.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = currentStreamId == stream.id,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.padding(12.dp),
                )
                Text(
                    text = stream.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
            }
            // 48dp square hit box (the old 24dp icon was the whole target;
            // touches a few px off it fell to the row or the sheet). The drag
            // starts on the first move past touch slop, no long-press delay.
            if (touchReordering) Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .pointerInput(stream.id) {
                        var from = -1
                        detectDragGestures(
                            onDragStart = {
                                from = latestOrder.indexOfFirst { it.id == stream.id }
                                dragIndex = from
                                dragOffset = 0f
                                latestOnDragActive(true)
                                Log.i(SWITCH_TAG, "[SwitchStream] drag start id=${stream.id} index=$from")
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val cur = dragIndex
                                if (cur < 0) return@detectDragGestures
                                val step = when {
                                    dragOffset > rowHeightPx * 0.5f && cur < latestOrder.lastIndex -> 1
                                    dragOffset < -rowHeightPx * 0.5f && cur > 0 -> -1
                                    else -> 0
                                }
                                if (step != 0) {
                                    order = latestOrder.toMutableList().apply { add(cur + step, removeAt(cur)) }
                                    dragIndex = cur + step
                                    dragOffset -= step * rowHeightPx
                                    Log.i(SWITCH_TAG, "[SwitchStream] drag swap id=${stream.id} ${cur}->${cur + step}")
                                }
                            },
                            onDragEnd = {
                                val to = dragIndex
                                dragIndex = -1
                                dragOffset = 0f
                                val changed = from >= 0 && to >= 0 && from != to
                                Log.i(SWITCH_TAG, "[SwitchStream] drop id=${stream.id} from=$from to=$to save=$changed")
                                if (changed) latestOnPendingOrder(latestOrder.map { it.id })
                                latestOnDragActive(false)
                            },
                            onDragCancel = {
                                Log.i(SWITCH_TAG, "[SwitchStream] drag cancel id=${stream.id} from=$from")
                                dragIndex = -1
                                dragOffset = 0f
                                order = latestStreams
                                latestOnDragActive(false)
                            },
                        )
                    },
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "Drag to Reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        }
    }
    }
}

@Composable
private fun StreamMoveMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Header so the TV entry reads "Reorder" like the phone and tablet button.
        DropdownMenuItem(text = { Text("Reorder", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {}, enabled = false)
        if (canMoveUp) {
            DropdownMenuItem(text = { Text("Move Up") }, onClick = { onMove(-1) })
        }
        if (canMoveDown) {
            DropdownMenuItem(text = { Text("Move Down") }, onClick = { onMove(1) })
        }
    }
}

private const val SWITCH_TAG = "PlayerScreen"
