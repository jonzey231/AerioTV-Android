package com.aeriotv.android.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import kotlinx.coroutines.launch

/**
 * GH #59: a touch-only `verticalScroll` block is unreachable on Android TV.
 * The update dialog's changelog and the What's New sheet rendered their
 * notes but the D-pad could not scroll them. On TV this makes the block
 * focusable, shows a subtle focus border so the affordance is visible, and
 * maps D-pad Up/Down onto the same scroll state - consuming the press only
 * while there is somewhere left to scroll, so focus still escapes to the
 * dialog's buttons at either end. Off TV it is a no-op and touch scrolling
 * behaves exactly as before.
 */
fun Modifier.tvDpadScrollable(state: ScrollState): Modifier = composed {
    if (!rememberIsTvDevice()) return@composed this
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 120.dp.toPx() }
    var focused by remember { mutableStateOf(false) }
    val focusTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = 2.dp,
            color = if (focused) focusTint else Color.Transparent,
            shape = RoundedCornerShape(8.dp),
        )
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (event.key) {
                Key.DirectionDown ->
                    if (state.value < state.maxValue) {
                        scope.launch { state.animateScrollBy(step) }
                        true
                    } else {
                        false
                    }
                Key.DirectionUp ->
                    if (state.value > 0) {
                        scope.launch { state.animateScrollBy(-step) }
                        true
                    } else {
                        false
                    }
                else -> false
            }
        }
        .focusable()
}
