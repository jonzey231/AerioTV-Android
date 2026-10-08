package com.aeriotv.android.core.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aeriotv.android.ui.scale.LocalAppTextScale

/**
 * ONE art slot shared by every TV program-art surface (Logan 2026-09-15): the
 * Live TV guide preview banner and the long-press Program Info sheet must show
 * the same artwork at the same size, and nothing may be cropped.
 *
 * Rules, both surfaces:
 *  - HEIGHT-LOCKED, width follows the image: landscape art renders landscape,
 *    portrait art renders portrait. Art keeps its own shape; a portrait poster
 *    is never shrunk to fit inside a landscape box.
 *  - width = height * aspect, clamped between [minWidth] and [maxWidth], so an
 *    ultra-wide banner cannot push the text column off the surface and a very
 *    tall poster still leaves a touchable slot.
 *  - the WHOLE image, never cropped: ContentScale.Fit inside the measured box,
 *    centered, aspect preserved.
 *  - no letterbox bars of its own. The slot is transparent, so any leftover
 *    space is the surrounding surface rather than a white plate.
 *  - never collapses and never jumps: before the aspect is known the slot
 *    reserves the 16:9 width, and the measured aspect is cached per model so
 *    re-selecting a program does not repeat the resize.
 *
 * SIZE (TV only; phone and tablet surfaces do not use this slot).
 *
 * The BASE size is the tvOS ratio halved for the 540 dp TV canvas: 101 dp tall
 * (203 pt of a 1080 pt canvas), 180 dp wide at 16:9. A user vote picked this
 * smaller art over the 135 dp experiment (Logan 2026-09-15), with ONE way to
 * make it bigger: the app-wide Text Size setting (Appearance > Text Size,
 * AppPreferences.textScale). Every dimension below multiplies by that same
 * factor through [LocalAppTextScale], so art and copy grow together and there
 * is no second art-size setting to keep in sync.
 *
 * Because the sizes depend on a CompositionLocal they are @Composable getters,
 * not constants. GuidePreviewBanner derives its own height and text inset from
 * [height], so the banner, the guide rows below it and the mini player's
 * baseline all follow at every scale stop (85% to 150%).
 */
object ProgramArtSlot {
    /** Unscaled slot height: 203 pt of the tvOS 1080 pt canvas, halved. */
    val baseHeight = 101.dp

    /** Unscaled reserved width: [baseHeight] at 16:9. */
    val baseMaxWidth = 180.dp

    /** Unscaled floor, so very tall art still leaves a touchable slot.
     *  Half the height, as tvOS ProgramArtSlotMetrics.minWidth (was 56 dp,
     *  which squared off tall portrait game art a little early). */
    val baseMinWidth = baseHeight * 0.5f

    /** The app-wide Text Size factor. The ONE knob that resizes this slot. */
    val scale: Float
        @Composable get() = LocalAppTextScale.current

    /** Slot height at the user's Text Size. */
    val height: Dp
        @Composable get() = baseHeight * scale

    /** The reserved width, and the widest the slot may get, at Text Size. */
    val maxWidth: Dp
        @Composable get() = baseMaxWidth * scale

    /** Floor for very tall art, at Text Size. */
    val minWidth: Dp
        @Composable get() = baseMinWidth * scale

    /**
     * Legacy fixed corner. Kept only as the DEFAULT for callers that have not
     * declared their container's radius. The rounding a caller actually gets
     * is [ProgramArtSlot]'s `containerCorner` run through
     * [artworkTileShape] (Appearance > Rounded corners on logos and artwork).
     */
    val corner = 6.dp

    /** The reserved footprint, for callers that lay out space themselves. */
    val modifier: Modifier
        @Composable get() = Modifier.width(maxWidth).height(height)

    /**
     * width = height * aspect, clamped to the sane range above, at [scale].
     * Takes the factor explicitly so layout maths can run outside composition.
     */
    fun widthFor(aspect: Float, scale: Float = 1f): Dp =
        (baseHeight.value * scale * aspect).dp
            .coerceIn(baseMinWidth * scale, baseMaxWidth * scale)

    /**
     * Aspects already measured, keyed by the image model. Keeps the slot from
     * popping from the 16:9 default back to poster shape every time the user
     * returns to a program whose art has already been seen this session.
     */
    private val aspects = mutableStateMapOf<Any, Float>()

    internal fun cached(model: Any?): Float? = model?.let { aspects[it] }

    internal fun remember(model: Any?, aspect: Float) {
        if (model != null && aspect > 0f) aspects[model] = aspect
    }
}

/**
 * The program art slot. [model] null (no art, or a lookup still open) draws
 * [fallback] but still occupies the slot, so the layout never collapses.
 */
@Composable
fun ProgramArtSlot(
    model: Any?,
    modifier: Modifier = Modifier,
    onAspect: ((Float) -> Unit)? = null,
    /**
     * Corner radius of the CARD or CELL this slot sits in. The art matches it
     * when the user has rounding on, and is square when they have it off or
     * when the container itself is square (Logan 2026-09-16).
     */
    containerCorner: Dp = ProgramArtSlot.corner,
    /**
     * Which Appearance toggle governs this slot. Null = "Rounded corners in
     * List view" (the Program Info card). The guide's preview/hero banner
     * passes "Rounded corners in Guide view", so the program art there rounds
     * and squares together with the guide rail logos (Logan 2026-09-16).
     */
    rounded: Boolean? = null,
    fallback: @Composable (() -> Unit)? = null,
) {
    // Default to 16:9 until the real aspect lands, so the slot reserves a
    // sensible width instead of collapsing or guessing portrait.
    var aspect by remember(model) {
        mutableStateOf(ProgramArtSlot.cached(model) ?: (16f / 9f))
    }
    val scale = ProgramArtSlot.scale
    val slotHeight = ProgramArtSlot.height
    val target = ProgramArtSlot.widthFor(aspect, scale)
    // Animate rather than pop when art turns out to be a different shape.
    val width by animateDpAsState(targetValue = target, animationSpec = tween(140), label = "artWidth")

    Box(
        modifier = modifier.width(width).height(slotHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                // Fit, never Crop: the whole title card, top included.
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    val w = state.result.image.width.toFloat()
                    val h = state.result.image.height.toFloat()
                    if (w > 0f && h > 0f) {
                        val a = w / h
                        // Hard data for the TV header art reports (Shawneau
                        // 2026-10-07): decoded size and the slot it lands in.
                        android.util.Log.i(
                            "ProgramArt",
                            "[ART] decoded ${w.toInt()}x${h.toInt()} aspect=${"%.2f".format(a)} slot=${ProgramArtSlot.widthFor(a, scale).value.toInt()}x${slotHeight.value.toInt()}dp fit",
                        )
                        aspect = a
                        ProgramArtSlot.remember(model, a)
                        onAspect?.invoke(a)
                    }
                },
                modifier = Modifier
                    .size(width, slotHeight)
                    // The slot is sized TO the art, so the art fills it edge to
                    // edge: the shared tile rule, capped at 25% of the shorter
                    // side, not a per-surface clip.
                    .clip(artworkTileShape(containerCorner, minOf(width, slotHeight), rounded = rounded)),
            )
        } else {
            fallback?.invoke()
        }
    }
}
