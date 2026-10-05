package com.aeriotv.android.feature.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.IconButton
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
import com.aeriotv.android.core.pip.isTelevision
import com.aeriotv.android.ui.scale.DropdownMenu

/**
 * Switch Stream rows for Dispatcharr ADMINS: the same radio rows, plus a way
 * to change the channel's stream order. Touch drags a handle; TV (and a
 * long-press on touch) opens a Move Up / Move Down menu from the row's
 * options button. Every committed move hands the FULL new id order to
 * [onReorder]; [saving] disables further moves while the PATCH is in flight.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReorderableStreamList(
    streams: List<StreamOption>,
    currentStreamId: Int?,
    saving: Boolean,
    onSelect: (Int) -> Unit,
    onReorder: (List<Int>) -> Unit,
) {
    val context = LocalContext.current
    val isTv = remember(context) { context.isTelevision() }
    // Local order so a drag can preview; reset whenever the server list changes.
    var order by remember { mutableStateOf(streams) }
    // After every save (success or failure) show the server's refetched order.
    androidx.compose.runtime.LaunchedEffect(streams, saving) { if (!saving) order = streams }
    var dragIndex by remember(streams) { mutableStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(1f) }
    val latestOrder by rememberUpdatedState(order)

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowHeightPx = it.height.toFloat().coerceAtLeast(1f) }
                .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                .focusRequester(rowFocus)
                .onPreviewKeyEvent { event ->
                    isTv && rowFocused && !saving &&
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
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = currentStreamId == stream.id,
                // TV: the row itself is the select target (OK selects, long
                // press opens the menu), so the radio is display only and the
                // row's D-pad stops are just row and options button.
                onClick = if (isTv) null else ({ onSelect(stream.id) }),
                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
            )
            Text(
                text = stream.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Box {
                if (isTv) {
                    IconButton(
                        onClick = { menuOpen = true },
                        enabled = !saving,
                        modifier = Modifier
                            .focusRequester(moreFocus)
                            .onPreviewKeyEvent { event ->
                                event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft &&
                                    runCatching { rowFocus.requestFocus() }.isSuccess
                            }
                            .onFocusChanged {
                                if (it.isFocused) com.aeriotv.android.ui.tv.TvFocusTrace.focus("switch:more$index")
                                else com.aeriotv.android.ui.tv.TvFocusTrace.blurred("switch:more$index")
                            },
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Stream Order Options")
                    }
                } else {
                    Icon(
                        imageVector = Icons.Filled.DragHandle,
                        contentDescription = "Drag to Reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .size(24.dp)
                            .pointerInput(stream.id, saving) {
                                if (saving) return@pointerInput
                                var from = -1
                                detectDragGestures(
                                    onDragStart = {
                                        from = latestOrder.indexOfFirst { it.id == stream.id }
                                        dragIndex = from
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val cur = dragIndex
                                        val step = when {
                                            dragOffset > rowHeightPx * 0.6f && cur < latestOrder.lastIndex -> 1
                                            dragOffset < -rowHeightPx * 0.6f && cur > 0 -> -1
                                            else -> 0
                                        }
                                        if (step != 0) {
                                            order = latestOrder.toMutableList().apply { add(cur + step, removeAt(cur)) }
                                            dragIndex = cur + step
                                            dragOffset -= step * rowHeightPx
                                        }
                                    },
                                    onDragEnd = {
                                        val to = dragIndex
                                        dragIndex = -1
                                        dragOffset = 0f
                                        if (from >= 0 && to >= 0 && from != to) onReorder(latestOrder.map { it.id })
                                    },
                                    onDragCancel = {
                                        dragIndex = -1
                                        dragOffset = 0f
                                        order = streams
                                    },
                                )
                            },
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (index > 0) {
                        DropdownMenuItem(
                            text = { Text("Move Up") },
                            onClick = { menuOpen = false; move(index, index - 1) },
                        )
                    }
                    if (index < order.lastIndex) {
                        DropdownMenuItem(
                            text = { Text("Move Down") },
                            onClick = { menuOpen = false; move(index, index + 1) },
                        )
                    }
                }
            }
        }
    }
}
