package com.aeriotv.android.feature.movies

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stacked card deck for compact widths (Apple parity: Components.swift
 * PhoneCardDeck). The front card fills the width less a 10 dp margin and a
 * 9 dp peek per side; the next cards sit behind it stepped 9 dp right,
 * scaled 3% and faded 20% per step; nothing parks to the side. A drag
 * left past 60 dp sends the front card to the back of the stack, a drag
 * right brings the back card to the front; the deck wraps around. Dots below when there is more
 * than one card. Only the front card takes taps.
 */
@Composable
fun <T> PhoneCardDeck(
    items: List<T>,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    key: (T) -> Any = { it as Any },
    onCurrentChange: (T) -> Unit = {},
    /** Distance from the deck's left edge to the content margin; the deck is
     *  laid out from the SCREEN edge so an outgoing card slides off past it
     *  instead of being cut at the front card's edge (iPhone parity). */
    leadInset: Dp = 0.dp,
    card: @Composable (item: T, isFront: Boolean) -> Unit,
) {
    if (items.isEmpty()) return
    val count = items.size
    var index by remember(count) { mutableIntStateOf(0) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val margin = 10.dp
    val peek = 9.dp
    LaunchedEffect(index, count) { items.getOrNull(index)?.let(onCurrentChange) }

    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(cardHeight)) {
            val widthPx = with(density) { maxWidth.toPx() }
            val cardW = maxWidth - leadInset - margin * 2 - peek * 2
            val cardWPx = with(density) { cardW.toPx() }
            val commitPx = with(density) { 60.dp.toPx() }
            val p = index - drag.value / cardWPx
            // Positions live in [0, count): the front card at 0, the rest
            // stacked behind it to the right. Nothing parks to the side at rest
            // (Logan 2026-09-09). Only while a drag is in motion does a card go
            // negative: the outgoing front card sliding off to the left (it lands
            // at the back of the stack), or the back card sliding in from the
            // left on a drag to the right.
            fun wrapped(rel: Float): Float {
                var r = rel % count
                if (r < 0f) r += count
                return if (r > count - 1f + 0.0001f) r - count else r
            }
            val gesture = Modifier.pointerInput(count) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        scope.launch { drag.snapTo(drag.value + dx) }
                    },
                    onDragEnd = {
                        val dx = drag.value
                        scope.launch {
                            when {
                                dx < -commitPx && count > 1 -> { index = (index + 1) % count; drag.snapTo(dx + cardWPx) }
                                dx > commitPx && count > 1 -> { index = (index - 1 + count) % count; drag.snapTo(dx - cardWPx) }
                            }
                            drag.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 300f))
                        }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f) } },
                )
            }
            // Clipped at the deck's own edge, which the callers put at the
            // screen edge: an outgoing card slides off past it with no hard
            // line at the content margin (iPhone, Logan 2026-09-09).
            Box(modifier = Modifier.fillMaxWidth().height(cardHeight).then(gesture).clipToBounds()) {
                // Single card: no wrap, no stack; it follows the finger with a
                // dampened drag and springs back (the wrap math flung it off
                // as the outgoing card, which read as the image reloading).
                val single = count == 1
                items.forEachIndexed { i, item ->
                    val rel = if (single) 0f else wrapped(i - p)
                    if (rel <= -1.5f || rel >= 3.5f) return@forEachIndexed
                    val front = abs(rel) < 0.02f
                    val clamped = rel.coerceAtMost(3f)
                    val front0 = leadInset + margin
                    val xDp: Dp = when {
                        single -> front0 + with(density) { (drag.value * 0.35f).toDp() }
                        rel >= 0f -> front0 + peek * clamped
                        else -> front0 - (cardW + front0) * (-rel).coerceAtMost(1f)
                    }
                    val scale = if (rel >= 0f) 1f - clamped * 0.03f else 1f
                    val alpha = if (rel >= 0f) 1f - clamped * 0.2f else 1f
                    Box(
                        modifier = Modifier
                            .width(cardW)
                            .height(cardHeight)
                            .offset { IntOffset(with(density) { xDp.roundToPx() }, 0) }
                            .zIndex(10f - rel)
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale
                                transformOrigin = TransformOrigin(1f, 0.5f)
                            }
                            .alpha(alpha.coerceIn(0f, 1f)),
                    ) {
                        androidx.compose.runtime.key(key(item)) { card(item, front) }
                    }
                }
            }
        }
        if (count > 1) {
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(count) { i ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == index) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
