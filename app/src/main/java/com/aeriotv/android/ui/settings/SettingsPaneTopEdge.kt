package com.aeriotv.android.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity

/**
 * Apple TV's Settings pane top edge (TVSettingsPane): once a rail page has
 * scrolled, nothing of it draws above the rail's resting top
 * ([TvSettingsMetrics.railRestingTop]), and it fades out over
 * [TvSettingsMetrics.paneFadeHeight] under that line, easing in over 0.2 s.
 * At rest nothing is cut or faded. Before this a scrolled page was cut hard
 * at its own top, about 12 dp above the rail's first row.
 *
 * Goes on a rail page's scroll container (on a Column, before
 * verticalScroll). Android TV's split pane only; phones, tablets, and pushed
 * pages are untouched.
 */
@Composable
fun Modifier.settingsPaneTopEdge(state: ScrollableState): Modifier {
    if (!rememberIsTvDevice() || !LocalSettingsInPane.current) return this
    val scrolled by remember(state) { derivedStateOf { state.canScrollBackward } }
    val fade by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = tween(200),
        label = "settingsPaneTopFade",
    )
    var topInWindow by remember { mutableFloatStateOf(Float.NaN) }
    val density = LocalDensity.current
    val linePx = with(density) { TvSettingsMetrics.railRestingTop.toPx() }
    val fadePx = with(density) { TvSettingsMetrics.paneFadeHeight.toPx() }
    return this
        .onGloballyPositioned { topInWindow = it.positionInWindow().y }
        .graphicsLayer {
            compositingStrategy = if (scrolled) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }
        .drawWithContent {
            val top = topInWindow
            if (!scrolled || top.isNaN()) {
                drawContent()
                return@drawWithContent
            }
            val line = (linePx - top).coerceAtLeast(0f)
            clipRect(top = line) { this@drawWithContent.drawContent() }
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 1f - fade),
                    1f to Color.Black,
                    startY = line,
                    endY = line + fadePx,
                ),
                topLeft = Offset(0f, line),
                size = Size(size.width, fadePx),
                blendMode = BlendMode.DstIn,
            )
        }
}
