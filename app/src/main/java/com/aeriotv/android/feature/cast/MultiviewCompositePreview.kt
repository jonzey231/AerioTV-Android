package com.aeriotv.android.feature.cast

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
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
    val latestCount by rememberUpdatedState(count)
    val layoutMode = MultiviewCompositeLayout.effectiveMode(session?.layoutMode ?: MultiviewLayoutMode.Auto, count)
    val latestMode by rememberUpdatedState(layoutMode)
    val haptics = LocalHapticFeedback.current
    // Tile menu (position) and the Move Tile armed position, -1 for none.
    var menuIndex by remember { mutableIntStateOf(-1) }
    var movingFrom by remember { mutableIntStateOf(-1) }
    val menuGuard = rememberTvMenuGuard()
    // A long-press release also ends as a tap; that tap is ignored.
    val pressTimes = remember { longArrayOf(0L, 0L) } // [touch down, long-press start]
    // Settings > Player > Multiview: the frame's tile rects follow Padding
    // Between Tiles, so taps and the drop cue do too.
    val style by controller.style.collectAsStateWithLifecycle()
    val latestPadding by rememberUpdatedState(style.padding)
    val accent = MaterialTheme.colorScheme.primary
    SideEffect { controller.focusArgb = accent.toArgb() }
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
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
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
        // Taps and drags on a transparent layer above the TextureView (the
        // view would otherwise see them first).
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { pos ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            pressTimes[1] = System.nanoTime()
                            viewW = size.width
                            viewH = size.height
                            dragPos = pos
                            dragFrom = MultiviewCompositeLayout.hitTestView(
                                pos.x, pos.y, size.width.toFloat(), size.height.toFloat(), latestCount, latestPadding, latestMode,
                            )
                            dragTarget = dragFrom
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragPos += amount
                            if (dragFrom >= 0) {
                                dragTarget = MultiviewCompositeLayout.hitTestView(
                                    dragPos.x, dragPos.y, size.width.toFloat(), size.height.toFloat(), latestCount, latestPadding, latestMode,
                                )
                            }
                        },
                        onDragEnd = {
                            val from = dragFrom
                            val to = dragTarget
                            if (from >= 0 && to >= 0 && from != to) {
                                movingFrom = -1
                                controller.swap(from, to)
                            } else if (from >= 0 && to == from) {
                                // Released on the same tile: the tile menu.
                                Log.i("AerioCast", "[MV-CAST] preview long-press tile=$from: tile menu")
                                movingFrom = -1
                                menuGuard.arm()
                                menuIndex = from
                            }
                            dragFrom = -1
                            dragTarget = -1
                        },
                        onDragCancel = {
                            dragFrom = -1
                            dragTarget = -1
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = { pressTimes[0] = System.nanoTime() }) { pos ->
                        val tapAt = System.nanoTime()
                        if (pressTimes[1] > pressTimes[0]) return@detectTapGestures
                        val index = MultiviewCompositeLayout.hitTestView(
                            pos.x, pos.y, size.width.toFloat(), size.height.toFloat(), latestCount, latestPadding, latestMode,
                        )
                        val armed = movingFrom
                        if (armed >= 0) {
                            // Move Tile: a tap on another tile swaps, a tap on
                            // the armed tile (or a gap) cancels.
                            movingFrom = -1
                            if (index >= 0 && index != armed) controller.swap(armed, index)
                        } else if (index >= 0) {
                            Log.i("AerioCast", "[MV-CAST] preview tap tile=$index")
                            controller.setFocus(index, tapAt)
                        }
                    }
                },
        )
    }
    // Layout row: the options Settings > Player > Multiview offers for this
    // tile count; the composite re-lays out live and the preview follows.
    val options = MultiviewCompositeLayout.layoutOptions(count)
    if (count > 0 && options.size > 1) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Layout", style = MaterialTheme.typography.bodyMedium)
            options.forEach { mode ->
                FilterChip(
                    selected = mode == layoutMode,
                    onClick = {
                        movingFrom = -1
                        controller.setLayoutMode(mode)
                    },
                    label = { Text(mode.displayName) },
                )
            }
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
