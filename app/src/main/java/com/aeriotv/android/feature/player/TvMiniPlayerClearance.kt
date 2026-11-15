package com.aeriotv.android.feature.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Keeps content out from under the TV corner mini player, the Apple TV
 * TVMiniPlayerClearance twin: when this element's top sits above the mini's
 * bottom and its trailing edge reaches past the mini's leading edge (less
 * [gap]), the trailing edge is pulled in to stop [gap] short of the mini.
 * Device pass 2026-11-14: the mini covered the right end of the Android TV
 * Search field. The bounds are read BEFORE the padding is applied, so the
 * element's own inset never feeds back into the check. Phones and tablets
 * never publish a frame ([MiniPlayerChrome.frameLeftPx] stays 0), so this is
 * a no-op there.
 */
fun Modifier.tvMiniPlayerClearance(gap: Dp = 12.dp): Modifier = composed {
    val left by MiniPlayerChrome.frameLeftPx.collectAsStateWithLifecycle()
    val bottom by MiniPlayerChrome.frameBottomPx.collectAsStateWithLifecycle()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val gapPx = with(density) { gap.toPx() }
    val covered = left > 0f && bottom > 0f && bounds != Rect.Zero &&
        bounds.top < bottom && bounds.right > left - gapPx
    val inset by animateDpAsState(
        targetValue = if (covered) with(density) { (bounds.right - (left - gapPx)).toDp() } else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "tvMiniClearance",
    )
    this
        .onGloballyPositioned { bounds = it.boundsInRoot() }
        .padding(end = inset)
}
