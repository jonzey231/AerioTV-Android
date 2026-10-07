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
 */
@Composable
fun MultiviewCompositePreview(controller: MultiviewCastController, modifier: Modifier = Modifier) {
    val session by controller.session.collectAsStateWithLifecycle()
    val count = session?.tiles?.size ?: 0
    val latestCount by rememberUpdatedState(count)
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
    Box(
        modifier = modifier
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
        // Drop-target outline while dragging (the local Multiview's drop cue).
        val target = dragTarget
        if (target >= 0 && target != dragFrom && viewW > 0 && count > 0) {
            val cell = MultiviewCompositeLayout.tileRects(count, style.padding).getOrNull(target)
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
                            viewW = size.width
                            viewH = size.height
                            dragPos = pos
                            dragFrom = MultiviewCompositeLayout.hitTestView(
                                pos.x, pos.y, size.width.toFloat(), size.height.toFloat(), latestCount, latestPadding,
                            )
                            dragTarget = dragFrom
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragPos += amount
                            if (dragFrom >= 0) {
                                dragTarget = MultiviewCompositeLayout.hitTestView(
                                    dragPos.x, dragPos.y, size.width.toFloat(), size.height.toFloat(), latestCount, latestPadding,
                                )
                            }
                        },
                        onDragEnd = {
                            val from = dragFrom
                            val to = dragTarget
                            if (from >= 0 && to >= 0 && from != to) controller.swap(from, to)
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
                    detectTapGestures { pos ->
                        val tapAt = System.nanoTime()
                        val index = MultiviewCompositeLayout.hitTestView(
                            pos.x, pos.y, size.width.toFloat(), size.height.toFloat(), latestCount, latestPadding,
                        )
                        if (index >= 0) {
                            Log.i("AerioCast", "[MV-CAST] preview tap tile=$index")
                            controller.setFocus(index, tapAt)
                        }
                    }
                },
        )
    }
}
