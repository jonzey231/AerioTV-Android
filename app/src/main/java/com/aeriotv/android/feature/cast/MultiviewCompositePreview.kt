package com.aeriotv.android.feature.cast

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import com.aeriotv.android.feature.multiview.MultiviewLayoutMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.cast.multiview.MultiviewCastController
import com.aeriotv.android.core.cast.multiview.MultiviewCompositeLayout

/**
 * The phone's view of a composited Multiview cast (2026-10-06): the exact
 * frame the receiver gets, drawn by the compositor into a TextureView. Tap a
 * tile to give it the audio; the highlight follows on both screens and the
 * cast keeps running. Long-press a tile and drag it onto another to swap
 * their positions (round 2, 2026-10-07); the composite re-lays out live.
 * A long-press released on the same tile opens the tile menu titled with
 * the channel (Apple 3515e92 round 6): Make Audio (hidden on the audio
 * tile), Switch Stream (Dispatcharr, permission gated), Move Tile (the next
 * tap on another tile swaps, a tap on it again cancels), Remove from
 * Multiview, Cancel. Below the grid, the Layout row picks the composite's
 * grid from the options Settings > Player > Multiview offers.
 */
@Composable
fun MultiviewCompositePreview(
    controller: MultiviewCastController,
    modifier: Modifier = Modifier,
    /** Whether the tile with this id offers Switch Stream. */
    canSwitchStream: (tileId: String) -> Boolean = { false },
    /** Opens the Switch Stream sheet for the tile with this id. */
    onSwitchStream: (tileId: String) -> Unit = {},
) {
    val session by controller.session.collectAsStateWithLifecycle()
    val count = session?.tiles?.size ?: 0
    val notices by controller.tileNotices.collectAsStateWithLifecycle()
    val layoutMode = MultiviewCompositeLayout.effectiveMode(session?.layoutMode ?: MultiviewLayoutMode.Auto, count)
    val haptics = LocalHapticFeedback.current
    // Tile menu (position) and the Move Tile armed position, -1 for none.
    var menuIndex by remember { mutableIntStateOf(-1) }
    var movingFrom by remember { mutableIntStateOf(-1) }
    val menuGuard = rememberTvMenuGuard()
    // Settings > Player > Multiview: the frame's tile rects follow Padding
    // Between Tiles, so taps and the drop cue do too.
    val style by controller.style.collectAsStateWithLifecycle()
    val accent = MaterialTheme.colorScheme.primary
    // Tile gaps show the sheet's own color (black on touch, as iOS; the
    // TV dialog surface on TV); only the phone preview, never the cast frame.
    val sheetBg = if (rememberIsTvDevice()) {
        com.aeriotv.android.ui.tv.TvChrome.dialogSurface()
    } else {
        REMOTE_SHEET_BG
    }
    SideEffect {
        controller.focusArgb = accent.toArgb()
        controller.previewBackgroundArgb = sheetBg.toArgb()
    }
    // Drag-to-swap state: the picked-up position and the cell under the finger.
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragTarget by remember { mutableIntStateOf(-1) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var viewW by remember { mutableIntStateOf(0) }
    var viewH by remember { mutableIntStateOf(0) }
    Column(modifier = modifier.fillMaxWidth()) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(MultiviewCompositeLayout.WIDTH / MultiviewCompositeLayout.HEIGHT.toFloat())
            .clip(RoundedCornerShape(8.dp)),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var surface: Surface? = null
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            val s = Surface(st)
                            surface = s
                            controller.attachPreview(s)
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                            // A fresh EGL surface at the new size, so the
                            // frame always fills the view the taps map onto.
                            surface?.let {
                                controller.detachPreview(it)
                                controller.attachPreview(it)
                            }
                        }
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            surface?.let {
                                controller.detachPreview(it)
                                it.release()
                            }
                            surface = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
        )
        // Drop-target outline while dragging (the local Multiview's drop
        // cue), or the armed tile while Move Tile waits for its target.
        val target = if (dragTarget >= 0 && dragTarget != dragFrom) dragTarget else movingFrom
        if (target >= 0 && viewW > 0 && count > 0) {
            val cell = MultiviewCompositeLayout.tileRects(count, style.padding, mode = layoutMode).getOrNull(target)
            if (cell != null) {
                val density = LocalDensity.current
                val sx = viewW / MultiviewCompositeLayout.WIDTH.toFloat()
                val sy = viewH / MultiviewCompositeLayout.HEIGHT.toFloat()
                with(density) {
                    Box(
                        Modifier
                            .offset { IntOffset((cell.left * sx).toInt(), (cell.top * sy).toInt()) }
                            .size((cell.width * sx).toDp(), (cell.height * sy).toDp())
                            .border(3.dp, MaterialTheme.colorScheme.tertiary),
                    )
                }
            }
        }
        // A tile whose ingest is retrying (or gave up) says so over its cell.
        if (viewW > 0 && count > 0 && notices.isNotEmpty()) {
            val rects = MultiviewCompositeLayout.tileRects(count, style.padding, mode = layoutMode)
            val density = LocalDensity.current
            val sx = viewW / MultiviewCompositeLayout.WIDTH.toFloat()
            val sy = viewH / MultiviewCompositeLayout.HEIGHT.toFloat()
            notices.forEach { (pos, text) ->
                val cell = rects.getOrNull(pos) ?: return@forEach
                with(density) {
                    Box(
                        Modifier
                            .offset { IntOffset((cell.left * sx).toInt(), (cell.top * sy).toInt()) }
                            .size((cell.width * sx).toDp(), (cell.height * sy).toDp()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        // One gesture detector on a transparent layer above the TextureView
        // (the view would otherwise see the touches first). Nothing Phone
        // 2026-10-08: with separate tap and long-press-drag detectors the tap
        // detector (inner, so first in the Main pass) consumed the release of
        // a still long-press, the drag detector then saw a consumed up and
        // cancelled, and the tile menu never opened unless the finger had
        // wobbled. Here: a release before the long-press timeout is a tap
        // (audio focus); a press held still for the timeout is a long-press,
        // released on the same tile it opens the tile menu, dragged onto
        // another tile it swaps them. Moving past the touch slop first
        // leaves the touch to the sheet (scroll).
        Box(
            modifier = Modifier
                .matchParentSize()
                .onSizeChanged {
                    viewW = it.width
                    viewH = it.height
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        viewW = size.width
                        viewH = size.height
                        val downTile = controller.hitTestPreview(down.position.x, down.position.y, w, h)
                        Log.i(
                            "AerioCast",
                            "[MV-CAST] preview gesture down x=${down.position.x.toInt()} y=${down.position.y.toInt()} " +
                                "view=${size.width}x${size.height} tile=$downTile",
                        )
                        val slop = viewConfiguration.touchSlop
                        var released: PointerInputChange? = null
                        val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var result = "cancel"
                            while (true) {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (c.changedToUpIgnoreConsumed()) {
                                    if (!c.isConsumed) {
                                        released = c
                                        result = "tap"
                                    }
                                    break
                                }
                                if (c.isConsumed || (c.position - down.position).getDistance() > slop) break
                            }
                            result
                        }
                        if (outcome == "tap") {
                            val up = released ?: return@awaitEachGesture
                            up.consume()
                            val index = controller.hitTestPreview(up.position.x, up.position.y, w, h)
                            Log.i("AerioCast", "[MV-CAST] preview gesture tap tile=$index")
                            val armed = movingFrom
                            if (armed >= 0) {
                                // Move Tile: a tap on another tile swaps, a tap
                                // on the armed tile (or a gap) cancels.
                                movingFrom = -1
                                if (index >= 0 && index != armed) controller.swap(armed, index)
                            } else if (index >= 0) {
                                Log.i("AerioCast", "[MV-CAST] preview tap tile=$index")
                                controller.setFocus(index, System.nanoTime())
                            }
                            return@awaitEachGesture
                        }
                        if (outcome != null) {
                            // Moved past the slop or taken by the sheet.
                            Log.i("AerioCast", "[MV-CAST] preview gesture cancel (moved or taken by the sheet)")
                            return@awaitEachGesture
                        }
                        // Held still for the long-press timeout.
                        Log.i("AerioCast", "[MV-CAST] preview gesture longpress tile=$downTile")
                        if (downTile < 0) return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        dragFrom = downTile
                        dragTarget = downTile
                        dragPos = down.position
                        var lifted = false
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (c.changedToUpIgnoreConsumed()) {
                                c.consume()
                                lifted = true
                                break
                            }
                            if (c.positionChange() != Offset.Zero) {
                                c.consume()
                                dragPos = c.position
                                val t = controller.hitTestPreview(c.position.x, c.position.y, w, h)
                                if (t != dragTarget) {
                                    Log.i("AerioCast", "[MV-CAST] preview gesture drag tile=$dragFrom over=$t")
                                    dragTarget = t
                                }
                            }
                        }
                        val from = dragFrom
                        val to = dragTarget
                        dragFrom = -1
                        dragTarget = -1
                        if (!lifted) return@awaitEachGesture
                        if (to >= 0 && to != from) {
                            Log.i("AerioCast", "[MV-CAST] preview gesture drag tile=$from dropped on $to: swap")
                            movingFrom = -1
                            controller.swap(from, to)
                        } else if (to == from) {
                            // Released on the same tile: the tile menu.
                            Log.i("AerioCast", "[MV-CAST] preview long-press tile=$from: tile menu")
                            movingFrom = -1
                            menuGuard.arm()
                            menuIndex = from
                        }
                    }
                },
        )
    }
    // Layout row: the options Settings > Player > Multiview offers for this
    // tile count; the composite re-lays out live and the preview follows.
    val options = MultiviewCompositeLayout.layoutOptions(count)
    if (count > 0 && options.size > 1) {
        MultiviewLayoutRow(layoutMode, options) { mode ->
            movingFrom = -1
            Log.i("AerioCast", "[MV-CAST] preview layout menu: ${mode.displayName}")
            controller.setLayoutMode(mode)
        }
    }
    }

    val menuTile = session?.tiles?.getOrNull(menuIndex)
    if (menuIndex >= 0 && menuTile != null) {
        val idx = menuIndex
        val tiles = session?.tiles.orEmpty()
        TvActionMenuDialog(
            title = menuTile.displayName,
            actions = buildList {
                if (idx != session?.focused) {
                    add(
                        TvMenuAction("Make Audio") {
                            Log.i("AerioCast", "[MV-CAST] preview menu: Make Audio tile=$idx")
                            controller.setFocus(idx)
                        },
                    )
                }
                if (canSwitchStream(menuTile.id)) {
                    add(
                        TvMenuAction("Switch Stream") {
                            Log.i("AerioCast", "[MV-CAST] preview menu: Switch Stream tile=$idx")
                            onSwitchStream(menuTile.id)
                        },
                    )
                }
                if (tiles.size > 1) {
                    add(
                        TvMenuAction("Move Tile") {
                            Log.i("AerioCast", "[MV-CAST] preview menu: Move Tile tile=$idx")
                            movingFrom = idx
                        },
                    )
                }
                add(
                    TvMenuAction("Remove from Multiview", destructive = true) {
                        Log.i("AerioCast", "[MV-CAST] preview menu: Remove from Multiview tile=$idx (${tiles.size - 1} left)")
                        if (movingFrom == idx) movingFrom = -1
                        controller.removeAt(idx)
                    },
                )
            },
            guard = menuGuard,
            onDismiss = { menuIndex = -1 },
        )
    }
}

/**
 * iOS MultiviewCompositeLayoutRow: "Layout" left, the current value right
 * with an up/down chevron opening a menu. Android's native equivalent is a
 * DropdownMenu anchored to the value. Shared by the composited Multiview
 * preview and the Cast Connect Multiview panel.
 */
@Composable
internal fun MultiviewLayoutRow(
    current: MultiviewLayoutMode,
    options: List<MultiviewLayoutMode>,
    onPick: (MultiviewLayoutMode) -> Unit,
) {
    var layoutMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Layout",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { layoutMenuOpen = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    current.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.Filled.UnfoldMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = layoutMenuOpen,
                onDismissRequest = { layoutMenuOpen = false },
            ) {
                options.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.displayName) },
                        trailingIcon = if (mode == current) {
                            { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                        } else {
                            null
                        },
                        onClick = {
                            layoutMenuOpen = false
                            onPick(mode)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The cast sheet's view of a Multiview the AerioTV Android TV app runs
 * natively over Cast Connect (Logan 2026-10-08). There is no phone-side
 * frame to preview, so the channels are listed in grid order: the audio
 * channel carries the speaker, a tap gives a channel the audio on the TV.
 * Below, the same Layout row as the composited path. Every change rides the
 * multiview.* control messages and the TV's state push redraws this.
 */
@Composable
fun NativeMultiviewPanel(
    state: com.aeriotv.android.core.cast.CastControl.MultiviewState,
    onFocus: (Int) -> Unit,
    onLayout: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        state.channels.forEachIndexed { i, ch ->
            val focused = i == state.focus
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !focused) {
                        Log.i("AerioCast", "[MV-CAST] native panel: Make Audio tile=$i")
                        onFocus(i)
                    }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ch.name.ifBlank { "Channel ${i + 1}" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (focused) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Audio",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        val options = state.layouts.map { MultiviewLayoutMode.from(it) }.distinct()
        if (options.size > 1) {
            MultiviewLayoutRow(MultiviewLayoutMode.from(state.layout), options) { mode ->
                Log.i("AerioCast", "[MV-CAST] native panel layout menu: ${mode.displayName}")
                onLayout(mode.key)
            }
        }
    }
}
