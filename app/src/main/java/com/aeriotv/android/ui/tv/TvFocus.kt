package com.aeriotv.android.ui.tv

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.zIndex

/**
 * tvOS-style focus grow.
 *
 * Apple TV grows the focused element with a quick, slightly springy scale (and
 * lifts it above its neighbours). Android TV focusables in this app previously
 * only swapped a border/background colour the instant focus landed, which is
 * exactly the "rigid vs the smoothness of the tvOS app" feel Archie called out.
 *
 * Drop [tvFocusScale] into a focusable's modifier chain BEFORE the
 * clip/background/border so the whole card scales as one unit, e.g.
 *
 * ```
 * Modifier
 *     .onFocusChanged { focused = it.isFocused }
 *     .tvFocusScale(focused)
 *     .clip(shape)
 *     .background(bg)
 *     .border(...)
 *     .focusable()
 * ```
 *
 * Keyed on [focused], which is only ever true under D-pad focus, so this is a
 * no-op on touch devices (their elements never gain focus) and is safe to apply
 * unconditionally without an `isTv` guard.
 *
 * The [zIndex] bump means the focused, scaled element draws on top of its
 * siblings (so a grown poster/cell/pill is never clipped by the next item),
 * matching how the tvOS focus engine lifts the focused view.
 */
@Composable
fun Modifier.tvFocusScale(
    focused: Boolean,
    focusedScale: Float = 1.08f,
): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        // A gentle spring (slight overshoot, no visible bounce) reads as
        // "alive" the way a flat tween does not. StiffnessMediumLow settles in
        // ~250ms which matches the tvOS focus cadence.
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "tvFocusScale",
    )
    // CRITICAL for perf: only attach the render layer (and zIndex) while the
    // element is actually focused or mid-animation. Applying graphicsLayer
    // unconditionally put a permanent layer on EVERY element using this
    // modifier; on a dense surface (a guide with 50-100 cells) that made a
    // low-power Android TV box composite that many layers per frame and lagged
    // D-pad navigation. Gating on `focused || scale != 1f` means at most the
    // one focused element (plus the one animating back) ever owns a layer.
    return if (focused || scale != 1f) {
        this
            .zIndex(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    } else {
        this
    }
}

/**
 * Lets a focused [androidx.compose.material3.Slider] be escaped with the
 * D-pad on Android TV.
 *
 * User report (v0.1.6, Amlogic S905X4): "When I press the down button on
 * my remote [on a focused slider], it doesn't go to the other options
 * below." A Compose Slider claims LEFT/RIGHT to change its value, but it
 * does not reliably hand UP/DOWN back to the focus system on a leanback
 * remote, so the user gets stuck on the slider with no way down.
 *
 * Apply this to the Slider's `modifier`. `onPreviewKeyEvent` fires on the
 * way DOWN the tree -- before the Slider's own key handling -- so we can
 * intercept UP/DOWN, explicitly move focus, and consume them, while
 * LEFT/RIGHT (and everything else) fall through to the Slider so value
 * adjustment is unchanged. No-op on touch devices, which never deliver
 * these key events, so it's safe to apply without an `isTv` guard.
 */
@Composable
fun Modifier.dpadFocusEscape(): Modifier {
    val focusManager = LocalFocusManager.current
    return this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}

/**
 * Deadband [androidx.compose.foundation.gestures.BringIntoViewSpec] for TV
 * form screens whose text fields sit under the floating leanback IME.
 *
 * With the keyboard open, Compose Foundation's keep-cursor-visible logic
 * pins the focused field's cursor rect at the IME's top edge. The required
 * correction lands on a fractional pixel, the scrollable rounds it, and the
 * 1px correction re-fires every frame in the opposite direction: the whole
 * form visibly "jiggles" up and down (measured on the Streamer: the form
 * column flips +/-1px continuously while the title and keyboard stay
 * pixel-static). The GH #1 fix (TV-only SOFT_INPUT_ADJUST_PAN) removed the
 * window-resize channel but not this scroll-container loop.
 *
 * The deadband swallows corrections under 2px, breaking the loop; real
 * scrolls (row-to-row focus moves) are far larger. Provide it around the
 * form's scroll container on TV:
 *
 * ```
 * CompositionLocalProvider(LocalBringIntoViewSpec provides TvImeNoJitterBringIntoViewSpec) {
 *     LazyColumn(...) { ... }
 * }
 * ```
 */
@kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
object TvImeNoJitterBringIntoViewSpec : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        // Already fully visible: no scroll.
        if (offset >= 0f && offset + size <= containerSize) return 0f
        // Minimal nudge (default behavior)...
        val distance = if (offset < 0f) offset else offset + size - containerSize
        // ...unless it is a sub-2px correction, which is the IME boundary
        // oscillation, not a real scroll.
        return if (kotlin.math.abs(distance) < 2f) 0f else distance
    }
}
