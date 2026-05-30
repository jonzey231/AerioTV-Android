package com.aeriotv.android.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One place for viewport-adaptive layout rules. Every form-heavy or list-
 * heavy screen calls into here so that scaling stays consistent across
 * phone / tablet / TV without each screen reimplementing its own breakpoints.
 *
 * Breakpoints follow Material 3 window-size-class spirit:
 *   - Compact   width <  600dp   → phones
 *   - Medium    600..839dp        → tablets portrait / unfolded foldables portrait
 *   - Expanded  >= 840dp          → tablets landscape / Android TV / foldables unfolded landscape
 *
 * Plus a height check (`isShort`) for "TV / tablet landscape with low height"
 * — those need two-column layouts so the content isn't crowded by an unused
 * left/right band.
 */
@Composable
@ReadOnlyComposable
fun rememberViewport(): Viewport {
    val config = LocalConfiguration.current
    return Viewport(
        widthDp = config.screenWidthDp,
        heightDp = config.screenHeightDp,
    )
}

data class Viewport(val widthDp: Int, val heightDp: Int) {
    val isCompact: Boolean get() = widthDp < 600
    val isMedium: Boolean get() = widthDp in 600..839
    val isExpanded: Boolean get() = widthDp >= 840
    /** Short = under the iOS Compact-height threshold. Used to decide when a
     * two-column landscape variant is required so the CTA stays above the
     * fold without forcing the user to scroll on a remote. */
    val isShort: Boolean get() = heightDp < 720

    /** Max content width for form-style screens. Phones get the full width;
     * larger viewports cap so a single column of labels + inputs stays
     * readable at 10-foot UX (TV) or 18-inch (tablet). */
    val formMaxWidth: Dp
        get() = when {
            isCompact -> Dp.Unspecified
            isMedium -> 600.dp
            else -> 760.dp
        }

    /** Tighter cap for the onboarding flow (Welcome / Choose Source Type /
     * Configure). The general [formMaxWidth] of 760dp still reads as a
     * stretched, full-bleed field row on a ~960dp-wide TV; onboarding text
     * fields and buttons look better as a narrower centered column at 10-foot
     * UX, closer to the tvOS proportions. Phones stay full width. */
    val onboardingMaxWidth: Dp
        get() = if (isCompact) Dp.Unspecified else 560.dp

    /** Horizontal padding for full-bleed screens that center constrained
     * content. Phones get the existing edge padding; wide viewports get
     * generous side gutters. */
    val gutter: Dp
        get() = when {
            isCompact -> 16.dp
            isMedium -> 24.dp
            else -> 40.dp
        }
}

/**
 * Modifier that caps a child's width to the current viewport's form max
 * (no-op on phones). Apply directly to a LazyColumn / Column whose parent
 * already centers it (or wrap inside [AdaptiveCenteredContent]).
 */
@Composable
fun Modifier.adaptiveFormWidth(): Modifier {
    val vp = rememberViewport()
    return if (vp.formMaxWidth != Dp.Unspecified) this.widthIn(max = vp.formMaxWidth)
    else this
}

/**
 * Wraps form/list content in a max-width column centered horizontally on
 * the screen. On phones it's a no-op (content flows edge-to-edge); on
 * tablet/TV the content is constrained so an OutlinedTextField doesn't
 * stretch to 1900px wide.
 *
 * The inner Box fills the available space inside the cap, so callers can
 * place a LazyColumn / Column inside and it'll size correctly.
 */
@Composable
fun AdaptiveCenteredContent(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val vp = rememberViewport()
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = if (vp.formMaxWidth != Dp.Unspecified)
                Modifier.widthIn(max = vp.formMaxWidth).fillMaxSize()
            else
                Modifier.fillMaxSize(),
            content = content,
        )
    }
}

