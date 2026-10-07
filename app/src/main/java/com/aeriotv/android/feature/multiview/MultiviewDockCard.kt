package com.aeriotv.android.feature.multiview

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aeriotv.android.feature.livetv.RetainedCardControlHeight
import com.aeriotv.android.feature.livetv.RetainedCardVerticalPadding
import com.aeriotv.android.feature.main.floatingNavChrome
import com.aeriotv.android.ui.theme.textAccent

private const val MV_TAG = "AerioCast"

/** Background Multiview dock card title (shared wording with Apple). */
const val MULTIVIEW_BACKGROUND_TITLE = "Multiview playing in background"

/** "1 channel staged for Multiview" / "N channels staged for Multiview". */
fun multiviewStagedTitle(count: Int): String =
    if (count == 1) "1 channel staged for Multiview" else "$count channels staged for Multiview"

/**
 * Multiview staging dock card (Logan 2026-10-06), phones and tablets: the
 * exact shape of the cast card and the Kept Live card (capsule, floating nav
 * chrome, 8 dp vertical padding, 12 dp phone / 18 dp tablet side padding,
 * 40 dp tile, 48 dp trailing control). Tap opens the staged list sheet; the
 * accent "Play" plays (Play Here, or the play-where prompt while a Cast
 * Connect session to the AerioTV TV app is active). Android TV keeps the
 * in-guide [MultiviewStagingBanner] instead.
 */
@Composable
fun MultiviewDockCard(
    tiles: List<MultiviewTile>,
    tablet: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    /** Background Multiview card (round 2): "Multiview playing in background". */
    title: String = multiviewStagedTitle(tiles.size),
    /** Trailing accent control: "Play" (staging) or "Stop" (background). */
    actionLabel: String = "Play",
) {
    if (tiles.isEmpty()) return
    val capsule = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .floatingNavChrome(capsule)
            .clip(capsule)
            .clickable(onClick = onOpen)
            .padding(horizontal = if (tablet) 18.dp else 12.dp, vertical = RetainedCardVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.GridView,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tiles.joinToString(", ") { it.displayName },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.textAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier.heightIn(min = RetainedCardControlHeight),
            contentAlignment = Alignment.Center,
        ) {
            AccentTextControl(label = actionLabel, onClick = onPlay)
        }
    }
}

/** Accent text control, the dock cards' trailing action. */
@Composable
private fun AccentTextControl(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * The "Multiview" sheet behind the dock card: staged channels with logo and
 * name, swipe a row to remove it, Reorder / Finish (Switch Stream pattern)
 * with drag handles, Clear, then Play Here and, only when [receiverName] is
 * non-null (Cast Connect session to the AerioTV TV app, or a Cast web
 * receiver with 2 to 4 channels staged, which the phone composites), Play on
 * <device>. [castNote] (web receiver with more than 4 staged) is a one-line
 * note under the buttons. Buttons follow the play-where dialog
 * (PlayWhereRouter.kt).
 */
@Composable
fun MultiviewStagedSheet(
    tiles: List<MultiviewTile>,
    receiverName: String?,
    onRemove: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onClear: () -> Unit,
    onPlayHere: () -> Unit,
    onPlayOnReceiver: () -> Unit,
    onDismiss: () -> Unit,
    castNote: String? = null,
) {
    LaunchedEffect(tiles.isEmpty()) { if (tiles.isEmpty()) onDismiss() }
    var reordering by remember { mutableStateOf(false) }
    var rowDragging by remember { mutableStateOf(false) }
    var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
    val canReorder = tiles.size > 1
    val finishReorder: () -> Unit = {
        val ids = pendingOrder
        if (ids != null && ids != tiles.map { it.id }) onReorder(ids)
        pendingOrder = null
        reordering = false
    }
    com.aeriotv.android.ui.FormFactorModal(
        onDismiss = {
            if (!rowDragging) {
                if (reordering) finishReorder()
                onDismiss()
            }
        },
        sheetMaxWidth = 600.dp,
        sheetGesturesEnabled = !reordering && !rowDragging,
        traceName = "multiview-staged",
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Multiview",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (canReorder) {
                    TextButton(
                        enabled = !rowDragging,
                        onClick = { if (reordering) finishReorder() else reordering = true },
                    ) { Text(if (reordering) "Finish" else "Reorder") }
                }
                TextButton(enabled = !reordering, onClick = onClear) { Text("Clear") }
            }
            Spacer(Modifier.height(8.dp))
            StagedRows(
                tiles = tiles,
                reordering = reordering && canReorder,
                onRemove = onRemove,
                onDragActive = { rowDragging = it },
                onPendingOrder = { pendingOrder = it },
            )
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        if (reordering) finishReorder()
                        onPlayHere()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Play Here") }
                if (receiverName != null) {
                    FilledTonalButton(
                        onClick = {
                            if (reordering) finishReorder()
                            onPlayOnReceiver()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Play on $receiverName", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (castNote != null) {
                    Text(
                        text = castNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StagedRows(
    tiles: List<MultiviewTile>,
    reordering: Boolean,
    onRemove: (String) -> Unit,
    onDragActive: (Boolean) -> Unit,
    onPendingOrder: (List<String>) -> Unit,
) {
    // Local order so a drag previews; Finish hands the full id order back once.
    var order by remember { mutableStateOf(tiles) }
    var dragIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(tiles, reordering, dragIndex) {
        if (dragIndex < 0 && !reordering) order = tiles
        // A swipe removal while reordering is impossible (swipe is off), but a
        // store change from elsewhere still drops vanished rows.
        if (reordering) order = order.filter { o -> tiles.any { it.id == o.id } }
    }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(1f) }
    val latestOrder by rememberUpdatedState(order)
    val latestTiles by rememberUpdatedState(tiles)
    val latestDragActive by rememberUpdatedState(onDragActive)
    val latestPending by rememberUpdatedState(onPendingOrder)
    order.forEachIndexed { index, tile ->
        key(tile.id) {
            val dragging = dragIndex == index
            val rowContent: @Composable () -> Unit = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .onSizeChanged { rowHeightPx = it.height.toFloat().coerceAtLeast(1f) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(com.aeriotv.android.core.ui.artworkTileShape(6.dp, model = tile.logoUrl))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (tile.logoUrl.isNotBlank()) {
                            coil3.compose.AsyncImage(
                                model = tile.logoUrl,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                            )
                        } else {
                            Icon(
                                Icons.Filled.GridView,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = tile.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (reordering) Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .pointerInput(tile.id) {
                                var from = -1
                                detectDragGestures(
                                    onDragStart = {
                                        from = latestOrder.indexOfFirst { it.id == tile.id }
                                        dragIndex = from
                                        dragOffset = 0f
                                        latestDragActive(true)
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
                                        }
                                    },
                                    onDragEnd = {
                                        val to = dragIndex
                                        dragIndex = -1
                                        dragOffset = 0f
                                        if (from >= 0 && to >= 0 && from != to) {
                                            Log.i(MV_TAG, "[MV-CAST] staged reorder ${tile.displayName} $from->$to")
                                            latestPending(latestOrder.map { it.id })
                                        }
                                        latestDragActive(false)
                                    },
                                    onDragCancel = {
                                        dragIndex = -1
                                        dragOffset = 0f
                                        order = latestTiles
                                        latestDragActive(false)
                                    },
                                )
                            },
                    ) {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = "Drag to Reorder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            Box(Modifier.graphicsLayer { translationY = if (dragging) dragOffset else 0f }) {
                if (reordering) {
                    rowContent()
                } else {
                    val swipe = rememberSwipeToDismissBoxState(
                        confirmValueChange = { v ->
                            if (v != SwipeToDismissBoxValue.Settled) {
                                onRemove(tile.id)
                                true
                            } else false
                        },
                    )
                    SwipeToDismissBox(
                        state = swipe,
                        backgroundContent = {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .background(MaterialTheme.colorScheme.error)
                                    .padding(horizontal = 16.dp),
                                contentAlignment = if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                                    Alignment.CenterStart
                                } else Alignment.CenterEnd,
                            ) {
                                Text("Remove", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.SemiBold)
                            }
                        },
                    ) { rowContent() }
                }
            }
        }
    }
}
