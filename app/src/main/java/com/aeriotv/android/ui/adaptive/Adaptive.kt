package com.aeriotv.android.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
fun rememberViewport(): Viewport {
    val config = LocalConfiguration.current
    // Memoize against width/height only: an IME-driven LocalConfiguration tick
    // (Android TV soft keyboard) must NOT churn a fresh Viewport and re-measure
    // every form/list that reads this (contributes to GH #1).
    return remember(config.screenWidthDp, config.screenHeightDp) {
        Viewport(
            widthDp = config.screenWidthDp,
            heightDp = config.screenHeightDp,
        )
    }
}

data class Viewport(val widthDp: Int, val heightDp: Int) {
    val isCompact: Boolean get() = widthDp < 600
    val isMedium: Boolean get() = widthDp in 600..839
    val isExpanded: Boolean get() = widthDp >= 840
    /** Short = under the iOS Compact-height threshold. Used to decide when a
     * two-column landscape variant is required so the CTA stays above the
     * fold without forcing the user to scroll on a remote. */
    val isShort: Boolean get() = heightDp < 720

    /**
     * Whether a two-pane Settings host (sidebar + detail) fits here.
     *
     * Settings redesign B1/B3: expanded width alone is not enough. A landscape
     * PHONE reports roughly 997x450dp, which is "expanded" by width but far too
     * short for a sidebar plus a form; it must stay stacked. The height floor
     * keeps those out while admitting real tablets.
     */
    val isTwoPaneEligible: Boolean get() = isExpanded && heightDp >= 480

    /** Max content width for form-style screens. Phones get the full width;
     * larger viewports cap so a single column of labels + inputs stays
     * readable at 10-foot UX (TV) or 18-inch (tablet). Numbers measured
     * against actual screen widths on real devices: the Streamer reports
     * `screenWidthDp = 960` (1920px @ density 320), so the previous 760dp
     * cap was 80% of the screen and the form looked edge-to-edge stretched.
     * 700dp on the Streamer leaves ~130dp gutters on each side -- the
     * tvOS EditServerPage proportions the user asked us to match. */
    val formMaxWidth: Dp
        get() = when {
            isCompact -> Dp.Unspecified
            isMedium -> 600.dp
            else -> 700.dp
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

    /** Readable line-length cap for a detail synopsis on an UNFOLDED foldable.
     * On the Z Fold 5 inner panel (screenWidthDp ~690, Medium) a full-width
     * synopsis runs 100+ chars per line; 560dp keeps it to a comfortable ~70.
     * [Dp.Unspecified] elsewhere so it is a no-op on Compact (folded phone,
     * unchanged) and Expanded (tablet-landscape / Android TV, own tier). Apply
     * directly to the synopsis Text via `Modifier.widthIn(max = ...)` -- a
     * Text's own widthIn clamps its own measurement, which the enclosing
     * Column's constraint does not always do (a full-width detail Column
     * measured a long Text wider than its own placed width). */
    val readableMaxWidth: Dp
        get() = if (isMedium) 560.dp else Dp.Unspecified
}

/**
 * Modifier that caps a child's width to the current viewport's form max
 * AND fills the remaining width so the child can be centered by a Box
 * parent. Phones get full width (no cap, just fillMaxWidth). Wider
 * viewports cap at [Viewport.formMaxWidth]; pair with a centering parent
 * (a `Box(contentAlignment = Alignment.TopCenter)`, or use
 * [AdaptiveCenteredContent]) to actually center the constrained column.
 * The historical behaviour of this modifier was widthIn-only, which on
 * non-Box parents (e.g. a plain Column) left the form glued to the
 * leading edge -- exactly the "stretched mobile" look the user reported
 * for EditPlaylistScreen on TV.
 */
@Composable
fun Modifier.adaptiveFormWidth(): Modifier {
    val vp = rememberViewport()
    return if (vp.formMaxWidth != Dp.Unspecified)
        this.widthIn(max = vp.formMaxWidth)
    else this
}

// `AdaptiveCenteredContent` lived here until the Settings redesign (plan B4:
// "retires the hand-rolled centering Boxes and the unused
// AdaptiveCenteredContent"). It had no call sites. Settings surfaces cap and
// centre with `SettingsPaneContent`, which measures the PANE rather than the
// window, so a two-pane host no longer sizes its form against the whole
// display.


// MARK: - Tab bar placement

/**
 * Bottom padding a scrolling tab surface must reserve so its last row clears
 * the floating tab pill.
 *
 * The pill OVERLAYS content (iOS 26 parity), so every tab screen has to hold
 * space for it itself. On tablets the pill moves to the TOP and sits in normal
 * flow, so that reserve becomes dead space at the end of every list -- hence a
 * provided value rather than the 104.dp that used to be typed into each screen.
 */
val LocalTabBarBottomInset = androidx.compose.runtime.compositionLocalOf { 104.dp }

/**
 * Whether the main tab bar belongs at the TOP of the window.
 *
 * Tablets: yes. Material's adaptive guidance specifies bottom navigation for
 * COMPACT widths only and moves it off the bottom edge at expanded ones, the
 * bottom pill collides with the expanded-width Settings sidebar, and iPad
 * already puts its tab bar on top -- so tablets match across both platforms.
 * Phones keep the bottom pill; TV has its own 10-foot top bar already.
 */
val Viewport.prefersTopTabBar: Boolean get() = isTwoPaneEligible

/**
 * Size multiplier for the tablet top tab bar, 1.0 at the iPad 12.9-inch
 * reference width (1024pt).
 *
 * Matching iPad's ABSOLUTE point sizes would render visibly small here: an
 * Android tablet reports more dp across a similar physical width (the Pixel
 * Tablet is 1280dp over roughly the same span the iPad covers in 1024pt), so
 * a 38dp bar would be a smaller fraction of the screen than iPad's 38pt one.
 * Scaling by width keeps the bar the same PROPORTION of the display it is on
 * iPad, which is what makes an 8-inch and a 13-inch tablet both look right.
 * Clamped so the extremes stay sane.
 */
val Viewport.topTabBarScale: Float get() = (widthDp / 1024f).coerceIn(0.9f, 1.35f)
