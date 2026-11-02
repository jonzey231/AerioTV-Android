package com.aeriotv.android.ui.tv

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Shared TV pick-up reorder visuals and keys (Switch Stream sheet, Settings >
 * Profiles), backed by [com.aeriotv.android.feature.player.PickUpReorder].
 */

/** Lifted tile scales to 1.03; the other tiles dim while one is lifted. */
fun Modifier.pickUpLift(lifted: Boolean, dimmed: Boolean): Modifier = graphicsLayer {
    val sc = if (lifted) 1.03f else 1f
    scaleX = sc; scaleY = sc
    alpha = if (dimmed) 0.5f else 1f
}

/**
 * While [lifted]: Up/Down (hold repeats via the system key repeat) call
 * [onMove] with -1 / +1, OK KeyUp calls [onOkUp] (the caller swallows the
 * KeyUp of the long press that lifted), Back KeyUp calls [onCancel].
 * Every other key is swallowed so focus and the screen stay put. Resting:
 * nothing is consumed.
 */
fun Modifier.pickUpKeys(
    lifted: Boolean,
    onMove: (Int) -> Unit,
    onOkUp: () -> Unit,
    onCancel: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    if (!lifted) return@onPreviewKeyEvent false
    val down = event.type == KeyEventType.KeyDown
    when (event.key) {
        Key.DirectionUp -> { if (down) onMove(-1) }
        Key.DirectionDown -> { if (down) onMove(1) }
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
            if (event.type == KeyEventType.KeyUp) onOkUp()
        }
        Key.Back, Key.Escape -> { if (!down) onCancel() }
        else -> Unit
    }
    true
}
