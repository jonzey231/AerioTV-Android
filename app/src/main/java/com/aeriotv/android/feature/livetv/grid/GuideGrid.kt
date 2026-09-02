package com.aeriotv.android.feature.livetv.grid

import android.os.Trace
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.ui.epgFlags
import com.aeriotv.android.core.ui.seasonEpisodeLabel
import androidx.compose.material.icons.outlined.History
import com.aeriotv.android.core.remote.GuideRemoteAction
import com.aeriotv.android.core.remote.RemoteSlot
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
    /** Scheduled / in-progress recording windows by Dispatcharr channel id (record dots). */
    recordingWindows: Map<Int, List<LongRange>>,
    isTv: Boolean,
    onPlay: (M3UChannel, EPGProgramme) -> Unit,
    onOpenMenu: (M3UChannel, EPGProgramme) -> Unit,
    /** UP at the top row; return true if focus was taken. */
    onLeaveTop: () -> Boolean,
    /** Remote map lookup for a slot (media keys, held Left/Right). */
    remoteAction: (RemoteSlot) -> GuideRemoteAction,
    /** Sidebar group mode: a held Left always opens the docked group menu, whatever the map says. */
    holdLeftOpensGroups: Boolean,
    /** Host-level actions (group pills, mini player, program info, search). Return true if handled. */
    onHostAction: (GuideRemoteAction) -> Boolean,
    focusRequester: FocusRequester,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val hourWidthPx = with(density) { hourWidth.toPx() }
    val pxPerMs = hourWidthPx / 3_600_000f
    val textMeasurer = rememberTextMeasurer(cacheSize = 512)
    val context = androidx.compose.ui.platform.LocalContext.current
    val logoCache = remember(context) { GuideLogoCache(context.applicationContext) }
    var gridFocused by remember { mutableStateOf(false) }
    var leftHoldLatched by remember { mutableStateOf(false) }
    var rightHoldLatched by remember { mutableStateOf(false) }
    var okLongLatched by remember { mutableStateOf(false) }
    var okDownSeen by remember { mutableStateOf(false) }

    // Lane: keep the focused row two rows below the top edge once past it.
    // snapshotFlow, not effect keys: reading focusRow in composition would
    // recompose the whole grid (and every visible row) on each D-pad press.
    val rowHeightPx = with(density) { rowHeight.toPx() }
    LaunchedEffect(state, listState, rowHeightPx) {
        snapshotFlow { state.focusRow to state.rows }.collect { (row, _) ->
            if (row < 0) return@collect
            val target = (row - LANE_ROWS).coerceAtLeast(0)
            val current = listState.firstVisibleItemIndex
            val delta = (target - current) * rowHeightPx - listState.firstVisibleItemScrollOffset
            if (kotlin.math.abs(target - current) <= 3) {
                // A one-row step goes through the scrollable so the lazy list
                // PREFETCHES the next row after this frame; scrollToItem would
                // compose the incoming row inside this press's measure pass.
                if (delta != 0f) listState.scrollBy(delta)
            } else {
                listState.scrollToItem(target)
            }
        }
    }

    val runAction: (GuideRemoteAction) -> Boolean = { action ->
        android.util.Log.d("GuideGrid", "remote action $action")
        when (action) {
            GuideRemoteAction.JUMP_TO_NOW -> { state.anchorToNow(nowMs); true }
            GuideRemoteAction.JUMP_TO_TOP -> { state.anchorToNow(nowMs); state.focusRowAt(0); true }
            GuideRemoteAction.TIMELINE_BACK -> { state.panBy(-(state.viewportDurationMs * 0.85f).toLong()); true }
            GuideRemoteAction.TIMELINE_FORWARD -> { state.panBy((state.viewportDurationMs * 0.85f).toLong()); true }
            GuideRemoteAction.PAGE_UP -> { state.moveRows(-pageRows(listState)); true }
            GuideRemoteAction.PAGE_DOWN -> { state.moveRows(+pageRows(listState)); true }
            GuideRemoteAction.NONE -> true
            else -> onHostAction(action)
        }
    }
    val keyHandler: (KeyEvent) -> Boolean = handler@{ event ->
        val native = event.nativeKeyEvent as? AndroidKeyEvent
        val repeat = native?.repeatCount ?: 0
        // Some remotes (and adb --longpress) flag a hold instead of repeating.
        val held = repeat >= HOLD_LEFT_REPEATS || (native?.isLongPress == true)
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
                Key.DirectionRight -> {
                    if (down) {
                        if (repeat == 0) rightHoldLatched = false
                        if (!rightHoldLatched && held) {
                            rightHoldLatched = true
                            runAction(remoteAction(RemoteSlot.RIGHT_LONG))
                        } else if (repeat == 0) {
                            state.pan(+1)
                        }
                    }
                    true
                }
                Key.DirectionLeft -> {
                    if (down) {
                        if (repeat == 0) leftHoldLatched = false
                        if (!leftHoldLatched && held) {
                            leftHoldLatched = true
                            // Held Left is the group menu unless the user mapped it away.
                            val mapped = if (holdLeftOpensGroups) GuideRemoteAction.FOCUS_GROUP_PILLS else remoteAction(RemoteSlot.LEFT_LONG)
                            runAction(if (mapped == GuideRemoteAction.NONE) GuideRemoteAction.FOCUS_GROUP_PILLS else mapped)
                        } else if (repeat == 0) {
                            state.pan(-1)
                        }
                    }
                    true
                }
                Key.PageUp -> { if (down) state.moveRows(-pageRows(listState)); true }
                Key.PageDown -> { if (down) state.moveRows(+pageRows(listState)); true }
                Key.ChannelUp -> { if (down && repeat == 0) runAction(remoteAction(RemoteSlot.CHANNEL_UP).orDefault(GuideRemoteAction.PAGE_UP)); true }
                Key.ChannelDown -> { if (down && repeat == 0) runAction(remoteAction(RemoteSlot.CHANNEL_DOWN).orDefault(GuideRemoteAction.PAGE_DOWN)); true }
                Key.MediaRewind, Key.MediaPrevious -> { if (down && repeat == 0) runAction(remoteAction(RemoteSlot.REWIND).orDefault(GuideRemoteAction.TIMELINE_BACK)); true }
                Key.MediaFastForward, Key.MediaNext -> { if (down && repeat == 0) runAction(remoteAction(RemoteSlot.FFWD).orDefault(GuideRemoteAction.TIMELINE_FORWARD)); true }
                Key.MediaPlayPause, Key.MediaPlay -> { if (down && repeat == 0) runAction(remoteAction(RemoteSlot.PLAY_PAUSE).orDefault(GuideRemoteAction.RESUME_PLAYER)); true }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    val channel = state.focusRow.takeIf { it >= 0 }?.let { state.rows.channel(it) }
                    val cell = state.focusedCell()
                    if (channel == null || cell == null) return@handler true
                    if (down) {
                        if (repeat == 0) { okLongLatched = false; okDownSeen = true }
                        if (!okLongLatched && repeat >= OK_LONG_REPEATS) {
                            okLongLatched = true
                            onOpenMenu(channel, cell)
                        }
                    } else if (up) {
                        // Only a press that STARTED on the grid plays: the KeyUp of
                        // the press that opened the tab must not tune a channel.
                        if (okDownSeen && !okLongLatched) onPlay(channel, cell)
                        okDownSeen = false
                    }
                    true
                }
                else -> false
            }
        } finally {
            Trace.endSection()
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalLogoCache provides logoCache) {
    Column(
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { gridFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent(keyHandler),
    ) {
        TimeHeader(state, nowMs, railWidth, headerHeight, pxPerMs, textMeasurer)
        val railPx = with(density) { railWidth.toPx() }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    val stripPx = (size.width - railPx).coerceAtLeast(1f)
                    state.viewportDurationMs = (stripPx / pxPerMs).toLong()
                },
        ) {
            val rows = state.rows
            items(count = rows.size, key = { rows.channel(it).id }) { row ->
                GridRow(
                    state = state,
                    row = row,
                    nowMs = nowMs,
                    rowHeight = rowHeight,
                    railWidth = railWidth,
                    pxPerMs = pxPerMs,
                    gridFocused = gridFocused,
                    isFavorite = rows.channel(row).id in favoriteIds,
                    recordingWindows = rows.channel(row).dispatcharrChannelId?.let { recordingWindows[it] } ?: emptyList(),
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
}

/** Rows per page: the fully visible rows minus one, so a page keeps one row of context. Deterministic (no partial-row dependence). */
private fun pageRows(listState: LazyListState): Int {
    val info = listState.layoutInfo
    val rowH = info.visibleItemsInfo.firstOrNull()?.size ?: return 1
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    return ((viewport / rowH) - 1).coerceAtLeast(1)
}

@Composable
private fun TimeHeader(
    state: GuideGridState,
    nowMs: Long,
    railWidth: Dp,
    headerHeight: Dp,
    pxPerMs: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    // Apple TV: time labels in the accent colour.
    val labelStyle = TextStyle(
        color = MaterialTheme.colorScheme.primary,
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
            clipRect {
                while (t < ve) {
                    val x = (t - vs) * pxPerMs
                    drawLine(rule, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    val label = fmt.format(Date(t)).lowercase(Locale.getDefault())
                    // A label whose slot started before the edge hugs the edge (clipped
                    // text aligns to the clipped edge) unless the next label would collide.
                    val nextX = (t + slot - vs) * pxPerMs
                    if (x >= 0f || nextX >= 72.dp.toPx()) {
                        drawText(textMeasurer, label, topLeft = Offset(x.coerceAtLeast(0f) + 6f, (size.height - 16.sp.toPx()) / 2f), style = labelStyle, maxLines = 1)
                    }
                    t += slot
                }
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
    recordingWindows: List<LongRange>,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    onPlay: (M3UChannel, EPGProgramme) -> Unit,
    onOpenMenu: (M3UChannel, EPGProgramme) -> Unit,
    onTapFocus: (Int, EPGProgramme) -> Unit,
) {
    val channel = state.rows.channel(row)
    val colors = MaterialTheme.colorScheme
    // Only the row that gains or loses focus redraws on a vertical move:
    // derivedStateOf notifies the draw scope only when THIS row's answer
    // changes, instead of every visible row re-recording its cells.
    val focusedCellStart by remember(state, row) {
        derivedStateOf { if (state.focusRow == row) state.focusCellStartMs else Long.MIN_VALUE }
    }
    val titleStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val titleDimStyle = titleStyle.copy(color = Color.White.copy(alpha = 0.55f), fontWeight = FontWeight.Normal)
    // Apple TV cell: bold title, italic accent subtitle, accent-tinted
    // description, then a dim time line with the S/E pill and flag badges.
    val accent = MaterialTheme.colorScheme.primary
    val timeStyle = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 10.5.sp)
    val descStyle = TextStyle(color = accent.copy(alpha = 0.85f), fontSize = 10.5.sp)
    val subStyle = TextStyle(color = accent, fontSize = 10.5.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
    val pillStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f), fontSize = 7.5.sp, fontWeight = FontWeight.Medium)
    val badgeStyle = TextStyle(color = Color.White, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
    val outline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val showBadges = com.aeriotv.android.core.ui.LocalShowEpgBadges.current
    val hiddenBadges = com.aeriotv.android.core.ui.LocalHiddenEpgBadges.current
    val shortFmt = remember { SimpleDateFormat("h:mm", Locale.getDefault()) }
    val catchupPainter = androidx.compose.ui.graphics.vector.rememberVectorPainter(androidx.compose.material.icons.Icons.Outlined.History)
    val fmt = remember { SimpleDateFormat("h:mma", Locale.getDefault()) }
    val seam = 2f
    val padH = 8f
    // Text layouts are measured once per (cell, width) and drawn many times:
    // measuring through TextMeasurer on every draw was ~1 ms per row.
    val textCache = remember(state.rows, row) { HashMap<Long, CellText>() }
    val rangeCache = remember(state.rows, row) { HashMap<Long, String>() }
    val railWidthPx = with(LocalDensity.current) { railWidth.toPx() }
    val logos = LocalLogoCache.current
    val onSurface = colors.onSurface
    val tertiary = colors.tertiary
    val surface = colors.surface
    val railNumberStyle = TextStyle(color = tertiary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val railNameStyle = TextStyle(color = onSurface, fontSize = 10.sp)
    // TalkBack: one node per row (channel + what is on now). Read in
    // composition on the 30 s tick only, never per press.
    val rowDescription = remember(channel.id, nowMs / 60_000L) {
        val onNow = state.rows.cellAt(row, nowMs)?.takeIf { !it.isPlaceholder }?.title
        buildString {
            channel.channelNumber?.let { append(it).append(", ") }
            append(channel.name)
            if (onNow != null) append(", now: ").append(onNow)
        }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .semantics { contentDescription = rowDescription }
            .pointerInput(row) {
                detectTapGestures(
                    onTap = { pos ->
                        if (pos.x < railWidthPx) { onTapFocus(row, state.rows.cells(row).first()); onPlay(channel, state.rows.cells(row).first()); return@detectTapGestures }
                        val t = state.viewportStartMs + ((pos.x - railWidthPx) / pxPerMs).toLong()
                        state.rows.cellAt(row, t)?.let { cell -> onTapFocus(row, cell); onPlay(channel, cell) }
                    },
                    onLongPress = { pos ->
                        val t = state.viewportStartMs + ((pos.x - railWidthPx).coerceAtLeast(0f) / pxPerMs).toLong()
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
        // Rail: number, logo, name. Drawn here so a row is ONE draw node and
        // composing a newly visible row costs nothing measurable.
        drawRect(surface, topLeft = Offset.Zero, size = Size(railWidthPx, size.height))
        val number = textCache.getOrPut(RAIL_NUMBER_KEY) {
            CellText(textMeasurer.measure(channel.channelNumber.orEmpty(), style = railNumberStyle, maxLines = 1, constraints = Constraints(maxWidth = 30.dp.roundToPx())), null)
        }
        // Narrow (phone) rail: number tucked top-left, logo and name centred across the full rail.
        val narrowRail = railWidthPx < 100.dp.toPx()
        val nameLeft = 4.dp.toPx()
        val nameW = (railWidthPx - 8.dp.toPx()).toInt().coerceAtLeast(1)
        if (narrowRail) drawText(number.title, topLeft = Offset(3.dp.toPx(), 2.dp.toPx()))
        else drawText(number.title, topLeft = Offset(6.dp.toPx(), (size.height - number.title.size.height) / 2f))
        val name = textCache.getOrPut(RAIL_NAME_KEY) {
            CellText(textMeasurer.measure((if (isFavorite) "\u2605 " else "") + channel.name, style = railNameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = nameW)), null)
        }
        val logo = if (channel.tvgLogo.isNotBlank()) logos.bitmap(channel.tvgLogo) else null
        val logoH = 24.dp.toPx(); val logoW = 36.dp.toPx()
        val nameX = nameLeft + (nameW - name.title.size.width) / 2f
        if (logo != null) {
            val top = (size.height - logoH - name.title.size.height - 2.dp.toPx()) / 2f
            val scale = minOf(logoW / logo.width, logoH / logo.height)
            val dw = logo.width * scale; val dh = logo.height * scale
            drawImage(
                logo,
                dstOffset = IntOffset((nameLeft + (nameW - dw) / 2f).toInt(), (top + (logoH - dh) / 2f).toInt()),
                dstSize = IntSize(dw.toInt(), dh.toInt()),
            )
            drawText(name.title, topLeft = Offset(nameX, top + logoH + 2.dp.toPx()))
        } else {
            drawText(name.title, topLeft = Offset(nameX, (size.height - name.title.size.height) / 2f))
        }
        if (channel.hasCatchup) {
            val iconPx = 12.dp.toPx()
            translate(left = railWidthPx - iconPx - 4.dp.toPx(), top = 4.dp.toPx()) {
                with(catchupPainter) { draw(Size(iconPx, iconPx), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tertiary.copy(alpha = 0.8f))) }
            }
        }
        drawLine(colors.primary.copy(alpha = 0.2f), Offset(railWidthPx - 0.5f, 0f), Offset(railWidthPx - 0.5f, size.height), strokeWidth = 1.dp.toPx())

        // Programme strip.
        clipRect(railWidthPx, 0f, size.width, size.height) {
        translate(left = railWidthPx) {
            val stripW = size.width - railWidthPx
            val vs = state.viewportStartMs
            val ve = vs + (stripW / pxPerMs).toLong()
            val focusStart = focusedCellStart
            val focusedHere = gridFocused && focusStart != Long.MIN_VALUE
            val cells = state.rows.cells(row)
            var i = state.rows.cellIndexAt(row, vs).let { if (it < 0) 0 else it }
            while (i < cells.size) {
                val cell = cells[i]
                if (cell.startMillis >= ve) break
                i++
                if (cell.endMillis <= vs) continue
                val x0 = ((cell.startMillis - vs) * pxPerMs).coerceAtLeast(0f)
                val x1 = ((cell.endMillis - vs) * pxPerMs).coerceAtMost(stripW)
                val w = (x1 - x0 - seam).coerceAtLeast(MIN_CELL_PX)
                val focused = focusedHere && cell.startMillis == focusStart
                val airing = nowMs in cell.startMillis until cell.endMillis
                val fill = when {
                    focused -> Color.White.copy(alpha = 0.3f)
                    cell.isPlaceholder -> Color.White.copy(alpha = 0.03f)
                    airing -> Color.White.copy(alpha = 0.12f)
                    else -> Color.White.copy(alpha = 0.05f)
                }
                val radius = if (focused) CornerRadius(4.dp.toPx()) else CornerRadius.Zero
                drawRoundRect(fill, topLeft = Offset(x0, 2f), size = Size(w, size.height - 4f), cornerRadius = radius)
                if (focused) {
                    // Apple TV draws a 4pt ring at 1080p (about 4px); Android TV
                    // density is 2x, so 2dp is the same visual weight (Logan 2026-09-01).
                    val bw = 2.dp.toPx()
                    drawRoundRect(
                        Color.White,
                        topLeft = Offset(x0 + bw / 2, 2f + bw / 2),
                        size = Size(w - bw, size.height - 4f - bw),
                        cornerRadius = radius,
                        style = Stroke(width = bw),
                    )
                }
                if (w >= 40.dp.toPx()) {
                    val textW = (w - 2 * padH).toInt().coerceAtLeast(1)
                    val tall = size.height >= 44.dp.toPx()
                    val key = cell.startMillis * 31 + textW
                    val text = textCache.getOrPut(key) {
                        fun measure(t: String, st: TextStyle, maxH: Float, ellipsis: Boolean = true) = textMeasurer.measure(
                            text = t, style = st, maxLines = 1,
                            overflow = if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
                            constraints = Constraints(maxWidth = textW, maxHeight = maxH.toInt().coerceAtLeast(1)),
                        )
                        val title = measure(cell.title, if (cell.isPlaceholder) titleDimStyle else titleStyle, 20.sp.toPx())
                        if (cell.isPlaceholder || !tall) CellText(title, null) else {
                            val sub = cell.subTitle?.takeIf { it.isNotBlank() }?.let { measure(it, subStyle, 15.sp.toPx()) }
                            val desc = cell.description.takeIf { it.isNotBlank() }?.let { measure(it, descStyle, 15.sp.toPx()) }
                            val range = rangeCache.getOrPut(cell.startMillis) {
                                shortFmt.format(Date(cell.startMillis)) + " - " + shortFmt.format(Date(cell.endMillis))
                            }
                            val time = measure(range, timeStyle, 15.sp.toPx(), ellipsis = false)
                            val pill = if (showBadges) cell.seasonEpisodeLabel()?.let { measure(it, pillStyle, 12.sp.toPx(), ellipsis = false) } else null
                            val badges = if (showBadges) cell.epgFlags().filter { it.label !in hiddenBadges }
                                .map { measure(it.label, badgeStyle, 12.sp.toPx(), ellipsis = false) to it.color } else emptyList()
                            CellText(title, time, desc, sub, pill, badges)
                        }
                    }
                    val recording = !cell.isPlaceholder && recordingWindows.any { win ->
                        cell.startMillis < win.last && cell.endMillis > win.first
                    }
                    clipRect(x0, 0f, x0 + w, size.height) {
                        val x = x0 + padH
                        var y = 3.dp.toPx()
                        drawText(text.title, topLeft = Offset(x, y)); y += text.title.size.height - 1.dp.toPx()
                        text.sub?.let { drawText(it, topLeft = Offset(x, y)); y += it.size.height - 1.dp.toPx() }
                        text.desc?.let { drawText(it, topLeft = Offset(x, y)); y += it.size.height - 1.dp.toPx() }
                        text.range?.let { time ->
                            // Bottom line sits on the row floor; the lines above stack from the top.
                            val ty = maxOf(y, size.height - 3.dp.toPx() - time.size.height)
                            var bx = x
                            drawText(time, topLeft = Offset(bx, ty)); bx += time.size.width + 5.dp.toPx()
                            val chipH = time.size.height - 4.dp.toPx()
                            val chipY = ty + (time.size.height - chipH) / 2f
                            text.pill?.let { pill ->
                                val cw = pill.size.width + 5.dp.toPx()
                                if (bx + cw <= x0 + w) {
                                    drawRoundRect(outline, topLeft = Offset(bx, chipY), size = Size(cw, chipH), cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(width = 1.dp.toPx()))
                                    drawText(pill, topLeft = Offset(bx + 2.5.dp.toPx(), chipY + (chipH - pill.size.height) / 2f))
                                }
                                bx += cw + 4.dp.toPx()
                            }
                            for ((badge, color) in text.badges) {
                                val cw = badge.size.width + 5.dp.toPx()
                                if (bx + cw > x0 + w) break
                                drawRoundRect(color, topLeft = Offset(bx, chipY), size = Size(cw, chipH), cornerRadius = CornerRadius(3.dp.toPx()))
                                drawText(badge, topLeft = Offset(bx + 2.5.dp.toPx(), chipY + (chipH - badge.size.height) / 2f))
                                bx += cw + 3.dp.toPx()
                            }
                        }
                        if (recording) {
                            drawCircle(NOW_RED, radius = 4.dp.toPx(), center = Offset(x0 + w - 10.dp.toPx(), 10.dp.toPx()))
                        }
                    }
                }
            }
            if (nowMs in (vs + 1) until ve) {
                val x = (nowMs - vs) * pxPerMs
                drawLine(NOW_RED, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
        }
        drawLine(colors.primary.copy(alpha = 0.25f), Offset(0f, size.height - 0.75f), Offset(size.width, size.height - 0.75f), strokeWidth = 0.75f.dp.toPx())
        Trace.endSection()
    }
}

/**
 * Channel logos as bitmaps for the row draw pass. A miss enqueues a Coil
 * load; when it lands the snapshot map changes and the rows that read the
 * url redraw. Bounded by the number of distinct logo urls in the playlist.
 */
class GuideLogoCache(private val context: android.content.Context) {
    private val bitmaps = mutableStateMapOf<String, ImageBitmap?>()
    private val inFlight = HashSet<String>()

    fun bitmap(url: String): ImageBitmap? {
        bitmaps[url]?.let { return it }
        if (bitmaps.containsKey(url) || !inFlight.add(url)) return null
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(128, 128)
            .target(
                onSuccess = { image -> bitmaps[url] = image.toBitmap().asImageBitmap(); inFlight.remove(url) },
                onError = { bitmaps[url] = null; inFlight.remove(url) },
            )
            .build()
        SingletonImageLoader.get(context).enqueue(request)
        return null
    }
}

val LocalLogoCache = staticCompositionLocalOf<GuideLogoCache> { error("GuideLogoCache not provided") }

private const val LANE_ROWS = 2
private const val HOLD_LEFT_REPEATS = 4
private const val OK_LONG_REPEATS = 1
private fun GuideRemoteAction.orDefault(default: GuideRemoteAction) = if (this == GuideRemoteAction.NONE) default else this
private const val MIN_CELL_PX = 6f
private const val RAIL_NUMBER_KEY = Long.MIN_VALUE + 1
private const val RAIL_NAME_KEY = Long.MIN_VALUE + 2
private val NOW_RED = Color(0xFFFF4757)

private class CellText(
    val title: TextLayoutResult,
    val range: TextLayoutResult?,
    val desc: TextLayoutResult? = null,
    val sub: TextLayoutResult? = null,
    val pill: TextLayoutResult? = null,
    val badges: List<Pair<TextLayoutResult, Color>> = emptyList(),
)
