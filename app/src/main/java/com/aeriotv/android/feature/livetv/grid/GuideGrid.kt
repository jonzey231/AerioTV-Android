package com.aeriotv.android.feature.livetv.grid

import android.os.Trace
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The rebuilt guide grid (design doc "Guide grid renderer"). One focus owner
 * (the grid), rows that draw all their cells in a single Canvas pass, cells
 * positioned from [GuideGridState.viewportStartMs] in the draw phase so a pan
 * redraws without recomposing, and every D-pad press resolved synchronously by
 * [GuideGridState]. No per-cell composables, no Compose focus search.
 */
@Composable
fun GuideGrid(
    state: GuideGridState,
    nowMs: Long,
    hourWidth: Dp,
    rowHeight: Dp,
    railWidth: Dp,
    headerHeight: Dp,
    favoriteIds: Set<String>,
    isTv: Boolean,
    onPlay: (M3UChannel, EPGProgramme) -> Unit,
    onOpenMenu: (M3UChannel, EPGProgramme) -> Unit,
    /** UP at the top row; return true if focus was taken. */
    onLeaveTop: () -> Boolean,
    onHoldLeft: () -> Unit,
    focusRequester: FocusRequester,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val hourWidthPx = with(density) { hourWidth.toPx() }
    val pxPerMs = hourWidthPx / 3_600_000f
    val textMeasurer = rememberTextMeasurer(cacheSize = 512)
    var gridFocused by remember { mutableStateOf(false) }
    var leftHoldLatched by remember { mutableStateOf(false) }
    var okLongLatched by remember { mutableStateOf(false) }

    // Lane: keep the focused row two rows below the top edge once past it.
    LaunchedEffect(state.focusRow, state.rows) {
        val row = state.focusRow
        if (row >= 0) listState.scrollToItem((row - LANE_ROWS).coerceAtLeast(0))
    }

    val keyHandler: (KeyEvent) -> Boolean = handler@{ event ->
        val repeat = (event.nativeKeyEvent as? AndroidKeyEvent)?.repeatCount ?: 0
        val down = event.type == KeyEventType.KeyDown
        val up = event.type == KeyEventType.KeyUp
        Trace.beginSection("GuideGrid.key")
        try {
            when (event.key) {
                Key.DirectionUp -> {
                    if (!down) return@handler true
                    if (!state.moveRows(-1)) return@handler onLeaveTop()
                    true
                }
                Key.DirectionDown -> { if (down) state.moveRows(+1); true }
                Key.DirectionRight -> { if (down) state.pan(+1); true }
                Key.DirectionLeft -> {
                    if (down) {
                        if (repeat == 0) leftHoldLatched = false
                        if (!leftHoldLatched && repeat >= HOLD_LEFT_REPEATS) {
                            leftHoldLatched = true
                            onHoldLeft()
                        } else if (repeat == 0) {
                            state.pan(-1)
                        }
                    }
                    true
                }
                Key.PageUp, Key.ChannelUp -> { if (down) state.moveRows(-pageRows(listState)); true }
                Key.PageDown, Key.ChannelDown -> { if (down) state.moveRows(+pageRows(listState)); true }
                Key.MediaRewind, Key.MediaPrevious -> { if (down) state.panBy(-TIMELINE_JUMP_MS); true }
                Key.MediaFastForward, Key.MediaNext -> { if (down) state.panBy(+TIMELINE_JUMP_MS); true }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    val channel = state.focusRow.takeIf { it >= 0 }?.let { state.rows.channel(it) }
                    val cell = state.focusedCell()
                    if (channel == null || cell == null) return@handler true
                    if (down) {
                        if (repeat == 0) okLongLatched = false
                        if (!okLongLatched && repeat >= OK_LONG_REPEATS) {
                            okLongLatched = true
                            onOpenMenu(channel, cell)
                        }
                    } else if (up && !okLongLatched) {
                        onPlay(channel, cell)
                    }
                    true
                }
                else -> false
            }
        } finally {
            Trace.endSection()
        }
    }

    Column(
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { gridFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent(keyHandler),
    ) {
        TimeHeader(state, nowMs, railWidth, headerHeight, pxPerMs, textMeasurer)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            val rows = state.rows
            items(count = rows.size, key = { rows.channel(it).id }) { row ->
                GridRow(
                    state = state,
                    row = row,
                    nowMs = nowMs,
                    rowHeight = rowHeight,
                    railWidth = railWidth,
                    pxPerMs = pxPerMs,
                    gridFocused = gridFocused || !isTv,
                    isFavorite = rows.channel(row).id in favoriteIds,
                    textMeasurer = textMeasurer,
                    onPlay = onPlay,
                    onOpenMenu = onOpenMenu,
                    onTapFocus = { r, cell ->
                        state.focusRowAt(r, cell.startMillis)
                        runCatching { focusRequester.requestFocus() }
                    },
                )
            }
        }
    }
}

private fun pageRows(listState: LazyListState): Int =
    (listState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)

@Composable
private fun TimeHeader(
    state: GuideGridState,
    nowMs: Long,
    railWidth: Dp,
    headerHeight: Dp,
    pxPerMs: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val labelStyle = TextStyle(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
    val rule = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val fmt = remember { SimpleDateFormat("h:mma", Locale.getDefault()) }
    Row(modifier = Modifier.fillMaxWidth().height(headerHeight)) {
        Box(modifier = Modifier.width(railWidth).fillMaxSize(), contentAlignment = Alignment.Center) {
            val clock = remember(nowMs / 60_000L) { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(nowMs)) }
            Text(clock, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val vs = state.viewportStartMs
            // Half-hour slots from the first slot boundary at or before the viewport edge.
            val slot = 30 * 60_000L
            var t = (vs / slot) * slot
            val ve = vs + (size.width / pxPerMs).toLong()
            while (t < ve) {
                val x = (t - vs) * pxPerMs
                drawLine(rule, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                val label = fmt.format(Date(t)).lowercase(Locale.getDefault())
                drawText(textMeasurer, label, topLeft = Offset(x + 6f, (size.height - 16.sp.toPx()) / 2f), style = labelStyle, maxLines = 1)
                t += slot
            }
            drawLine(rule, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 1f)
        }
    }
}

@Composable
private fun GridRow(
    state: GuideGridState,
    row: Int,
    nowMs: Long,
    rowHeight: Dp,
    railWidth: Dp,
    pxPerMs: Float,
    gridFocused: Boolean,
    isFavorite: Boolean,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    onPlay: (M3UChannel, EPGProgramme) -> Unit,
    onOpenMenu: (M3UChannel, EPGProgramme) -> Unit,
    onTapFocus: (Int, EPGProgramme) -> Unit,
) {
    val channel = state.rows.channel(row)
    val colors = MaterialTheme.colorScheme
    val titleStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val titleDimStyle = titleStyle.copy(color = Color.White.copy(alpha = 0.55f), fontWeight = FontWeight.Normal)
    val timeStyle = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
    val fmt = remember { SimpleDateFormat("h:mma", Locale.getDefault()) }
    val seam = 2f
    val padH = 8f
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
        Rail(channel, railWidth, isFavorite)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(row) {
                    detectTapGestures(
                        onTap = { pos ->
                            val t = state.viewportStartMs + (pos.x / pxPerMs).toLong()
                            state.rows.cellAt(row, t)?.let { cell -> onTapFocus(row, cell); onPlay(channel, cell) }
                        },
                        onLongPress = { pos ->
                            val t = state.viewportStartMs + (pos.x / pxPerMs).toLong()
                            state.rows.cellAt(row, t)?.let { cell -> onTapFocus(row, cell); onOpenMenu(channel, cell) }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { _, dx ->
                        state.scrollViewportTo(state.viewportStartMs - (dx / pxPerMs).toLong())
                    }
                },
        ) {
            Trace.beginSection("GuideGrid.row")
            val vs = state.viewportStartMs
            val ve = vs + (size.width / pxPerMs).toLong()
            val focusedHere = gridFocused && state.focusRow == row
            val focusStart = state.focusCellStartMs
            val cells = state.rows.cells(row)
            // First cell that can intersect the viewport.
            var i = state.rows.cellIndexAt(row, vs).let { if (it < 0) 0 else it }
            while (i < cells.size) {
                val cell = cells[i]
                if (cell.startMillis >= ve) break
                i++
                if (cell.endMillis <= vs) continue
                val x0 = ((cell.startMillis - vs) * pxPerMs).coerceAtLeast(0f)
                val x1 = ((cell.endMillis - vs) * pxPerMs).coerceAtMost(size.width)
                val w = (x1 - x0 - seam).coerceAtLeast(MIN_CELL_PX)
                val focused = focusedHere && cell.startMillis == focusStart
                val airing = nowMs in cell.startMillis until cell.endMillis
                val fill = when {
                    focused -> Color.White.copy(alpha = 0.25f)
                    cell.isPlaceholder -> Color.White.copy(alpha = 0.03f)
                    airing -> Color.White.copy(alpha = 0.12f)
                    else -> Color.White.copy(alpha = 0.05f)
                }
                val radius = if (focused) CornerRadius(4.dp.toPx()) else CornerRadius.Zero
                drawRoundRect(fill, topLeft = Offset(x0, 2f), size = Size(w, size.height - 4f), cornerRadius = radius)
                if (focused) {
                    val bw = 4.dp.toPx()
                    drawRoundRect(
                        Color.White,
                        topLeft = Offset(x0 + bw / 2, 2f + bw / 2),
                        size = Size(w - bw, size.height - 4f - bw),
                        cornerRadius = radius,
                        style = Stroke(width = bw),
                    )
                }
                if (w >= TEXT_MIN_PX) {
                    clipRect(x0, 0f, x0 + w, size.height) {
                        val textW = (w - 2 * padH).toInt().coerceAtLeast(1)
                        drawText(
                            textMeasurer, cell.title,
                            topLeft = Offset(x0 + padH, 6.dp.toPx()),
                            style = if (cell.isPlaceholder) titleDimStyle else titleStyle,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            size = Size(textW.toFloat(), 20.sp.toPx()),
                        )
                        if (!cell.isPlaceholder && size.height >= 40.dp.toPx()) {
                            val range = fmt.format(Date(cell.startMillis)).lowercase(Locale.getDefault()) +
                                " - " + fmt.format(Date(cell.endMillis)).lowercase(Locale.getDefault())
                            drawText(
                                textMeasurer, range,
                                topLeft = Offset(x0 + padH, size.height - 6.dp.toPx() - 14.sp.toPx()),
                                style = timeStyle, maxLines = 1, overflow = TextOverflow.Clip,
                                size = Size(textW.toFloat(), 16.sp.toPx()),
                            )
                        }
                    }
                }
            }
            if (nowMs in (vs + 1) until ve) {
                val x = (nowMs - vs) * pxPerMs
                drawLine(NOW_RED, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
            drawLine(colors.primary.copy(alpha = 0.25f), Offset(0f, size.height - 0.75f), Offset(size.width, size.height - 0.75f), strokeWidth = 0.75f.dp.toPx())
            Trace.endSection()
        }
    }
}

@Composable
private fun Rail(channel: M3UChannel, railWidth: Dp, isFavorite: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .width(railWidth)
            .fillMaxSize()
            .background(colors.surface)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            channel.channelNumber.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = colors.tertiary,
            maxLines = 1,
            modifier = Modifier.width(30.dp),
        )
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (channel.tvgLogo.isNotBlank()) {
                AsyncImage(
                    model = channel.tvgLogo,
                    contentDescription = null,
                    modifier = Modifier.width(36.dp).height(24.dp),
                )
            }
            Text(
                (if (isFavorite) "★ " else "") + channel.name,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val LANE_ROWS = 2
private const val HOLD_LEFT_REPEATS = 4
private const val OK_LONG_REPEATS = 1
private const val TIMELINE_JUMP_MS = 2L * 3_600_000L + 30L * 60_000L
private const val MIN_CELL_PX = 6f
private const val TEXT_MIN_PX = 28f
private val NOW_RED = Color(0xFFFF4757)
