package com.aeriotv.android.ui.tv

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/**
 * Shared state for the keyboard-on-OK gate: [armed] is true only after the
 * user pressed OK on a text field, and flips false whenever D-pad focus
 * moves away. See [TvKeyboardOnOkHost].
 */
class TvKeyboardGateState {
    var armed by androidx.compose.runtime.mutableStateOf(false)
}

val LocalTvKeyboardGate =
    androidx.compose.runtime.staticCompositionLocalOf<TvKeyboardGateState?> { null }

/**
 * TV form keyboard gate: the on-screen keyboard opens only when the user
 * presses OK on a text field, not the moment a field gains D-pad focus.
 *
 * Stock Compose text fields start a platform text-input session (which shows
 * the IME) on focus gain, so walking DOWN through a form popped the keyboard
 * at every field. This wraps the form in [InterceptPlatformTextInput] and
 * blocks the session until the gate is armed by an OK press (see
 * [tvFormFieldInput]). The IME's own Next/Done actions keep the gate armed,
 * so the type-Next-type flow still works; a D-pad move disarms it.
 *
 * No-op on touch devices: phones keep keyboard-on-focus, which is correct
 * there because focusing a field IS the tap.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvKeyboardOnOkHost(content: @Composable () -> Unit) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isTv = (configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    if (!isTv) {
        content()
        return
    }
    val gate = androidx.compose.runtime.remember { TvKeyboardGateState() }
    val armed = gate.armed
    androidx.compose.ui.platform.InterceptPlatformTextInput(
        // remember(armed): a NEW interceptor instance per armed flip, which
        // restarts any active input session under the new policy. That is
        // what actually shows (armed) or suppresses (disarmed) the IME.
        interceptor = androidx.compose.runtime.remember(armed) {
            androidx.compose.ui.platform.PlatformTextInputInterceptor { request, nextHandler ->
                if (armed) {
                    nextHandler.startInputMethod(request)
                } else {
                    kotlinx.coroutines.awaitCancellation()
                }
            }
        },
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalTvKeyboardGate provides gate,
            content = content,
        )
    }
}

/**
 * Form text-field input behavior for TV, used INSIDE a [TvKeyboardOnOkHost]:
 *
 *  - OK (D-pad center / Enter) arms the gate and shows the keyboard.
 *  - D-pad UP/DOWN disarms the gate and moves focus (single-line fields have
 *    no use for vertical cursor movement), so walking the form never pops
 *    the keyboard. This subsumes [dpadFocusEscape], which it falls back to
 *    when no host is present.
 *
 * No-op on touch devices (these key events never fire).
 */
@Composable
fun Modifier.tvFormFieldInput(): Modifier {
    val gate = LocalTvKeyboardGate.current ?: return this.dpadFocusEscape()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    return this.onPreviewKeyEvent { event ->
        val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter ||
            event.key == Key.NumPadEnter
        when {
            event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
                gate.armed = false
                focusManager.moveFocus(FocusDirection.Down)
            }
            event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                gate.armed = false
                focusManager.moveFocus(FocusDirection.Up)
            }
            // Swallow the down-press so the field's own key handling never
            // sees a half-click; act on the release.
            event.type == KeyEventType.KeyDown && isOk -> true
            event.type == KeyEventType.KeyUp && isOk -> {
                gate.armed = true
                keyboard?.show()
                true
            }
            else -> false
        }
    }
}
