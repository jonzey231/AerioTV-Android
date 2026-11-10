// Fold.kt
//
// Settings redesign phase 2: fold awareness, driven entirely by the OS-reported
// FoldingFeature rather than by device names or screen sizes.
//
// A two-pane layout on a foldable must not put a pane boundary anywhere except
// ON the crease. Splitting the window at a fixed 280dp on an unfolded Pixel
// Fold puts the divider a couple of centimeters to the left of the hinge, so
// the detail pane is the one that gets folded in half. Reading the hinge out of
// WindowLayoutInfo lets the sidebar end exactly where the crease begins.
//
// Both hinge orientations are handled, and they mean different things:
//
//  - VERTICAL: the crease runs top to bottom and splits the window LEFT and
//    RIGHT (book posture, and the same device lying flat). The side-by-side
//    Settings layout simply moves its boundary onto the hinge.
//
//  - HORIZONTAL, any state: the crease runs left to right. Settings keeps the
//    full display side by side (Logan's call), so the layout is unchanged and
//    the feature only matters if its hinge physically occludes pixels.
//
// [FoldInfo] is recomputed from every WindowLayoutInfo emission, so posture,
// orientation and bounds changes all reach the layout at runtime.

package com.aeriotv.android.ui.adaptive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/** Which way the crease runs across the window. */
enum class FoldAxis {
    /** Top to bottom: the window is split left and right. */
    VERTICAL,

    /** Left to right: the window is split top and bottom. */
    HORIZONTAL,
}

/**
 * The window's current folding feature, reduced to what a layout needs.
 *
 * [start] and [end] are the crease's near and far edges measured along the axis
 * it divides: x offsets for a [FoldAxis.VERTICAL] fold, y offsets for a
 * [FoldAxis.HORIZONTAL] one. Both are dp from the window's leading / top edge.
 */
data class FoldInfo(
    val axis: FoldAxis,
    /** The feature splits the window into two logically separate areas. */
    val isSeparating: Boolean,
    /**
     * A physical hinge covers pixels here (occlusionType FULL, e.g. Surface
     * Duo). Content placed across it is not merely creased, it is invisible.
     */
    val isOccluding: Boolean,
    val start: Dp,
    val end: Dp,
    /**
     * The device is partly folded (FoldingFeature.State.HALF_OPENED): tabletop
     * or book posture, as opposed to open flat. The half-folded player keys on
     * this together with a [FoldAxis.HORIZONTAL] crease.
     */
    val halfOpened: Boolean = false,
) {
    /** Extent of the crease itself; 0 on a seamless flat fold. */
    val gap: Dp get() = (end - start).coerceAtLeast(0.dp)

    /**
     * Whether the boundary must be left BLANK rather than carrying the usual
     * hairline divider: either the crease has real extent, or a hinge occludes
     * it, in which case a drawn divider would land under the hardware.
     */
    val needsBlankGap: Boolean get() = isOccluding || (gap > 0.dp && isSeparating)
}

/**
 * The window's current folding feature, or null when there is none.
 *
 * Returns null on any device without the extension backing WindowLayoutInfo,
 * which is every non-foldable, so callers fall back to their fixed layout.
 * Recomputed on every emission: fold state, orientation and bounds changes are
 * all picked up at runtime without a configuration change.
 */
@Composable
fun rememberFoldInfo(): FoldInfo? {
    val context = LocalContext.current
    val density = LocalDensity.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // No early return above this point: every remember/produceState below has
    // to be reached on every composition, or the slot table sees a different
    // shape once a fold appears.
    val feature by produceState<FoldingFeature?>(initialValue = null, activity, lifecycleOwner) {
        val host = activity ?: return@produceState
        // STARTED rather than a bare collect: posture updates arriving while
        // the app is backgrounded are stale by the time it returns, and the
        // tracker stops emitting anyway.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            WindowInfoTracker.getOrCreate(host)
                .windowLayoutInfo(host)
                .collect { info ->
                    value = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                }
        }
    }

    val f = feature
    // One line per CHANGE, so a device log can confirm what real hardware
    // reports without turning into a per-frame spam source.
    val signature = f?.let { "${it.orientation}|${it.state}|${it.bounds}|${it.occlusionType}" }
    LaunchedEffect(signature) {
        if (signature == null) return@LaunchedEffect
        Log.i(
            "AerioFold",
            "[FOLD] orientation=${f?.orientation} state=${f?.state} " +
                "bounds=${f?.bounds} occlusion=${f?.occlusionType}",
        )
    }

    if (f == null) return null
    val bounds = f.bounds
    // A degenerate feature (no extent at all) is not a usable boundary.
    if (bounds.height() <= 0 && bounds.width() <= 0) return null
    val vertical = f.orientation == FoldingFeature.Orientation.VERTICAL
    // Cheap enough to build every frame; deliberately NOT remembered, so this
    // function has the same slot shape with and without a fold.
    return with(density) {
        FoldInfo(
            axis = if (vertical) FoldAxis.VERTICAL else FoldAxis.HORIZONTAL,
            isSeparating = f.isSeparating,
            isOccluding = f.occlusionType == FoldingFeature.OcclusionType.FULL,
            start = (if (vertical) bounds.left else bounds.top).toDp(),
            end = (if (vertical) bounds.right else bounds.bottom).toDp(),
            halfOpened = f.state == FoldingFeature.State.HALF_OPENED,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
