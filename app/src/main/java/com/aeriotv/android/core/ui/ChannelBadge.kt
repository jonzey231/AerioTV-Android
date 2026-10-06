package com.aeriotv.android.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.toBitmap

/**
 * ONE channel badge, shared by the Live TV list rows and the guide channel
 * rail (Logan 2026-09-16), so the two surfaces can never disagree about where
 * the logo, the channel number and the channel name sit or what shape the
 * artwork is.
 *
 * The badge is a FIXED vertical order with DEDICATED positions:
 *
 *     logo    (takes every pixel the two text lines do not)
 *     name    (single line, ellipsized)
 *     number  (single line, never ellipsized)
 *
 * A hidden line contributes ZERO height, so turning the number off gives its
 * height to the logo rather than leaving a gap.
 *
 * The list row renders the badge with `showName = false`: it keeps the channel
 * name in its own text column beside the badge. The guide rail has no text
 * column, so it renders the name here.
 *
 * The guide rail draws into a Canvas for scroll performance and therefore
 * cannot host this composable. Both surfaces instead share the pure geometry
 * in [channelBadgeLayout], [fitArtwork] and [artworkRadiusPx] below, so the
 * slot rect, the number rect, the name rect, the fitted image rect and the
 * corner rule are identical by construction rather than by review.
 *
 * NEVER use BoxWithConstraints (or any other SubcomposeLayout) in here: the
 * list row measures with IntrinsicSize.Min and a SubcomposeLayout throws under
 * intrinsic measurement. The badge measures itself with onSizeChanged.
 */

/** A rect in pixels. Pure data so the Canvas rail and Compose can share it. */
data class ArtworkRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
}

/**
 * The whole badge, resolved. Any of [number] / [name] is null when that line
 * is hidden; [logo] is null when the logo is hidden or there is no room left.
 */
data class ChannelBadgeGeometry(
    val logo: ArtworkRect?,
    val number: ArtworkRect?,
    val name: ArtworkRect?,
)

/**
 * The FITTED bounds of an image inside a slot: the whole image, aspect
 * preserved, never cropped, centered. Returns the slot itself when the image
 * aspect is not known yet, so nothing pops on load.
 */
fun fitArtwork(
    slotWidth: Float,
    slotHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
): ArtworkRect {
    if (slotWidth <= 0f || slotHeight <= 0f) return ArtworkRect(0f, 0f, 0f, 0f)
    if (imageWidth <= 0f || imageHeight <= 0f) return ArtworkRect(0f, 0f, slotWidth, slotHeight)
    val scale = minOf(slotWidth / imageWidth, slotHeight / imageHeight)
    val w = imageWidth * scale
    val h = imageHeight * scale
    return ArtworkRect((slotWidth - w) / 2f, (slotHeight - h) / 2f, w, h)
}

/**
 * THE rounding rule, for every artwork surface in the app.
 *
 * Rounding off: square, always.
 *
 * Rounding on: round only a TILE - opaque artwork that reaches its own corners
 * (see [ArtworkTile]). A FLOATING logo, transparent around its edges, stays
 * square, because rounding it only nicks the glyph.
 *
 * The radius is the CONTAINER's radius, capped at 25 percent of the fitted
 * image's shorter side so a small tile cannot come out as a lozenge.
 */
fun artworkRadiusPx(
    fitted: ArtworkRect,
    containerRadiusPx: Float,
    rounded: Boolean,
    isTile: Boolean,
): Float {
    if (!rounded || !isTile || containerRadiusPx <= 0f || fitted.isEmpty) return 0f
    return minOf(containerRadiusPx, 0.25f * minOf(fitted.width, fitted.height))
}

/**
 * Lay the badge out. Pure: no composition, no density, pixels in and out.
 *
 * Vertical order is logo, name, number. [numberHeight] / [nameHeight] are 0
 * when that line is hidden, and a hidden line also drops its gap. The logo
 * claims everything left over between the insets, optionally clamped by
 * [maxLogoWidth] / [maxLogoHeight] (the guide rail's stock 36x24 box), and the
 * resulting block is centered vertically in the slot.
 */
fun channelBadgeLayout(
    slotWidth: Float,
    slotHeight: Float,
    showLogo: Boolean,
    numberHeight: Float,
    nameHeight: Float,
    topInset: Float = 0f,
    bottomInset: Float = 0f,
    logoGap: Float = 0f,
    lineGap: Float = 0f,
    maxLogoWidth: Float = Float.MAX_VALUE,
    maxLogoHeight: Float = Float.MAX_VALUE,
    logoAspect: Float = 0f,
    widthDrivenLogo: Boolean = false,
    numberColumnWidth: Float = 0f,
    numberColumnGap: Float = 0f,
    /**
     * Settings > Live TV > Logo Size multiplier ([liveTvLogoScale]). Scales
     * the logo box resolved above around its center. Growth is capped at the
     * column width and the height the text lines leave, so a fixed cell
     * (the guide rail) never overflows.
     */
    logoScale: Float = 1f,
    /** Smallest logo side, in px, when [logoScale] shrinks (2.5% and 5%). */
    logoFloor: Float = 0f,
): ChannelBadgeGeometry {
    if (slotWidth <= 0f || slotHeight <= 0f) return ChannelBadgeGeometry(null, null, null)
    val hasNumber = numberHeight > 0f
    val hasName = nameHeight > 0f

    // TV FORM FACTOR (Logan 2026-09-16): a TV rail is WIDE and SHORT, so
    // stacking three things vertically is far too cramped. On TV the channel
    // NUMBER gets its own fixed-width column on the LEFT, vertically centered,
    // and the logo (with the name under it, where the surface shows a name)
    // takes the whole column to its right. Numbers off and the left column
    // disappears, so the logo column is the full width.
    //
    // Phone and tablet keep the stacked order - logo, number, name - which is
    // the layout Logan approved there.
    val leftColumn = if (hasNumber && numberColumnWidth > 0f) numberColumnWidth else 0f
    val leftGap = if (leftColumn > 0f) numberColumnGap else 0f
    val columnLeft = leftColumn + leftGap
    val columnWidth = (slotWidth - columnLeft).coerceAtLeast(0f)

    // Only the lines that live IN the logo column cost the logo any height.
    val stackedNumber = if (leftColumn > 0f) 0f else numberHeight
    val textHeight = stackedNumber + nameHeight +
        (if (stackedNumber > 0f && hasName) lineGap else 0f)
    val gap = if (showLogo && textHeight > 0f) logoGap else 0f

    val available = slotHeight - topInset - bottomInset
    // Height left for the logo once the text lines have taken theirs.
    val roomForLogo = (available - textHeight - gap).coerceAtLeast(0f)
    var logoHeight = if (showLogo) roomForLogo else 0f
    var logoWidth = if (showLogo) columnWidth else 0f
    if (showLogo && widthDrivenLogo && logoAspect > 0f) {
        // WIDTH-DRIVEN (the Live TV list rows, Logan 2026-09-16): the logo box
        // is the full column WIDTH by width/aspect, so every logo of the same
        // aspect comes out the same size no matter how many lines of text the
        // row beside it has. The row height only CAPS the box: a taller box
        // than the room left shrinks to the height instead, which is what
        // keeps a very tall portrait image inside the row.
        val wanted = columnWidth / logoAspect
        logoHeight = minOf(wanted, roomForLogo)
        logoWidth = minOf(columnWidth, logoHeight * logoAspect)
    }
    if (showLogo) {
        logoHeight = minOf(logoHeight, maxLogoHeight)
        logoWidth = minOf(logoWidth, maxLogoWidth)
    }
    if (showLogo && logoScale != 1f) {
        val baseW = logoWidth
        val baseH = logoHeight
        logoWidth = baseW * logoScale
        logoHeight = baseH * logoScale
        if (logoScale > 1f) {
            logoWidth = minOf(logoWidth, maxOf(baseW, columnWidth))
            logoHeight = minOf(logoHeight, maxOf(baseH, roomForLogo))
        } else {
            // Below 10% (down to 2.5%): a legible floor, never above stock.
            val floor = logoFloor
            logoWidth = minOf(baseW, maxOf(logoWidth, floor))
            logoHeight = minOf(baseH, maxOf(logoHeight, floor))
        }
    }
    val blockHeight = logoHeight + gap + textHeight
    var y = topInset + ((available - blockHeight) / 2f).coerceAtLeast(0f)

    val logo = if (showLogo && logoHeight > 0f) {
        ArtworkRect(columnLeft + (columnWidth - logoWidth) / 2f, y, logoWidth, logoHeight).also {
            y += logoHeight + gap
        }
    } else {
        null
    }
    // ORDER (Logan 2026-09-16): logo, NAME, NUMBER. The name reads first
    // under the logo and the number trails it. The Live TV list rows are
    // unaffected: they hide the name here and keep it in their own text
    // column, so their stack is still logo then number.
    val name = if (hasName) {
        ArtworkRect(columnLeft, y, columnWidth, nameHeight)
            .also { y += nameHeight + if (stackedNumber > 0f) lineGap else 0f }
    } else {
        null
    }
    val number = when {
        !hasNumber -> null
        // TV: its own column on the left, vertically centered in the slot.
        leftColumn > 0f -> ArtworkRect(0f, (slotHeight - numberHeight) / 2f, leftColumn, numberHeight)
        // Phone / tablet: stacked under the logo and the name.
        else -> ArtworkRect(columnLeft, y, columnWidth, numberHeight)
    }
    return ChannelBadgeGeometry(logo, number, name)
}

/**
 * The shape for art drawn edge to edge in a container of radius [container]
 * (the mini player logo plate, the cast tile, the picker rows, the in-player
 * info card, the program art slot). Same verdict as every other surface: pass
 * the image [model] and a logo already sampled as FLOATING comes back square.
 */
@Composable
fun artworkTileShape(
    container: Dp,
    shorterSide: Dp = Dp.Unspecified,
    model: Any? = null,
    /**
     * Which Appearance toggle governs this surface. Null (the default) means
     * the LIST toggle, which is every card surface. The guide surfaces pass
     * `LocalRoundedArtwork.current.guide` instead, so program art in the guide
     * rounds with the guide logos and is square with them (Logan 2026-09-16).
     */
    rounded: Boolean? = null,
): Shape {
    if (!(rounded ?: LocalRoundedArtwork.current.list)) return RoundedCornerShape(0.dp)
    if (model != null && ArtworkTile.verdict(model) == false) return RoundedCornerShape(0.dp)
    val capped = if (shorterSide == Dp.Unspecified) container else minOf(container, shorterSide * 0.25f)
    return RoundedCornerShape(capped)
}

/**
 * The shape for FITTED art whose drawn bounds are [fitted]. [isTile] is the
 * decoded-image verdict from [ArtworkTile]; unknown (not sampled yet) is
 * treated as floating, so nothing rounds and then un-rounds.
 */
@Composable
fun artworkFittedShape(
    fitted: ArtworkRect,
    container: Dp,
    isTile: Boolean,
): Shape {
    val density = LocalDensity.current
    val radiusPx = with(density) {
        artworkRadiusPx(
            fitted = fitted,
            containerRadiusPx = container.toPx(),
            rounded = LocalRoundedArtwork.current.list,
            isTile = isTile,
        )
    }
    return RoundedCornerShape(with(density) { radiusPx.toDp() })
}

/**
 * The shared channel badge.
 *
 * @param logoModel      artwork model (Coil), null or blank for no logo.
 * @param numberText     channel number, already formatted.
 * @param nameText       channel name.
 * @param showLogo       Appearance > Show Channel Logos.
 * @param showNumber     Appearance > Show Channel Numbers.
 * @param showName       Appearance > Show Channel Names. The LIST ROW passes
 *                       false: it keeps the name in its own text column.
 * @param slotWidth      the surface supplies the column width.
 * @param slotMaxHeight  the surface supplies the height cap.
 *                       [Dp.Unspecified] = fill the parent's height, which is
 *                       what the list row wants (the text column drives it).
 * @param containerCorner the radius of the CARD/CELL this badge sits in.
 * @param fallbackText   drawn in place of a missing logo (the row's initials).
 */
@Composable
fun ChannelBadge(
    logoModel: Any?,
    numberText: String?,
    nameText: String?,
    showLogo: Boolean,
    showNumber: Boolean,
    showName: Boolean,
    slotWidth: Dp,
    containerCorner: Dp,
    numberStyle: TextStyle,
    numberColor: Color,
    modifier: Modifier = Modifier,
    slotMaxHeight: Dp = Dp.Unspecified,
    nameStyle: TextStyle = numberStyle,
    nameColor: Color = numberColor,
    fallbackText: String? = null,
    fallbackStyle: TextStyle = numberStyle,
    fallbackColor: Color = numberColor,
    /**
     * TV form factor: the channel number moves to its own fixed-width column
     * on the LEFT, vertically centered, and the logo (plus the name, where the
     * surface renders one) takes the column to its right. Phone and tablet
     * keep the stacked layout.
     */
    numberOnLeft: Boolean = false,
    /** Settings > Live TV > Logo Size above the default: a floor the row grows to. */
    slotMinHeight: Dp = Dp.Unspecified,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    val number = numberText?.takeIf { showNumber && it.isNotBlank() }
    val name = nameText?.takeIf { showName && it.isNotBlank() }
    val hasLogo = showLogo

    // Line heights, measured the same way the rail's TextMeasurer measures
    // them, so both surfaces feed channelBadgeLayout identical numbers.
    val numberHeight = number?.let {
        measurer.measure(it, style = numberStyle, maxLines = 1, softWrap = false).size.height.toFloat()
    } ?: 0f
    val nameHeight = name?.let {
        measurer.measure(it, style = nameStyle, maxLines = 1, softWrap = false).size.height.toFloat()
    } ?: 0f

    // The left number column is measured from the WIDEST number the app can
    // show, so the number is single line and is never truncated, and so the
    // logo column starts at the same x on every row.
    // The left number column is measured from THIS row's own number and is
    // left aligned at the slot's edge, so the number sits hard against the
    // start of the cell and the logo and name begin immediately after it
    // (Logan 2026-09-16). Sizing it from the widest number the app can show
    // pushed short numbers into the middle of a wide column and stole that
    // width from the name ("NBC Sports NOW HD" truncated).
    val numberColumnPx = if (numberOnLeft && number != null) {
        measurer.measure(number, style = numberStyle, maxLines = 1, softWrap = false).size.width.toFloat()
    } else {
        0f
    }

    var slotPx by remember { mutableStateOf(IntSize.Zero) }
    var logoAspect by remember(logoModel) { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .width(slotWidth)
            .then(if (slotMinHeight != Dp.Unspecified) Modifier.heightIn(min = slotMinHeight) else Modifier)
            .then(
                if (slotMaxHeight == Dp.Unspecified) Modifier.fillMaxHeight()
                else Modifier.heightIn(max = slotMaxHeight),
            )
            .onSizeChanged { slotPx = it },
    ) {
        if (slotPx.width == 0 || slotPx.height == 0) return@Box
        val geometry = channelBadgeLayout(
            slotWidth = slotPx.width.toFloat(),
            slotHeight = slotPx.height.toFloat(),
            showLogo = hasLogo,
            numberHeight = numberHeight,
            nameHeight = nameHeight,
            logoGap = with(density) { CHANNEL_BADGE_LOGO_GAP.toPx() },
            lineGap = with(density) { CHANNEL_BADGE_LINE_GAP.toPx() },
            numberColumnWidth = numberColumnPx,
            numberColumnGap = with(density) { CHANNEL_BADGE_NUMBER_GAP.toPx() },
            logoAspect = logoAspect,
            // The badge's own surfaces (the Live TV list rows) have rows of
            // differing height, so the logo is sized from the COLUMN WIDTH and
            // only capped by the height. The guide rail, whose rows are all
            // one height, keeps height-driven sizing in its Canvas.
            widthDrivenLogo = true,
        )

        geometry.logo?.let { slot ->
            val fitted = fitArtwork(
                slotWidth = slot.width,
                slotHeight = slot.height,
                imageWidth = if (logoAspect > 0f) logoAspect else 0f,
                imageHeight = if (logoAspect > 0f) 1f else 0f,
            )
            Box(
                modifier = Modifier
                    .offset(slot.left.toDp(density), slot.top.toDp(density))
                    .size(slot.width.toDp(density), slot.height.toDp(density)),
                contentAlignment = Alignment.Center,
            ) {
                if (logoModel != null && logoModel != "") {
                    val shape = artworkFittedShape(
                        fitted = fitted,
                        container = containerCorner,
                        isTile = ArtworkTile.verdict(logoModel) == true,
                    )
                    AsyncImage(
                        model = logoModel,
                        contentDescription = null,
                        // Fit: the whole logo, never cropped, centered.
                        contentScale = ContentScale.Fit,
                        onSuccess = { state ->
                            val w = state.result.image.width.toFloat()
                            val h = state.result.image.height.toFloat()
                            if (w > 0f && h > 0f) logoAspect = w / h
                            // ONE sample per image, cached per model: is this
                            // an opaque tile or a floating logo?
                            ArtworkTile.sample(logoModel, state.result.image.toBitmap())
                        },
                        modifier = if (logoAspect <= 0f) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier
                                .size(fitted.width.toDp(density), fitted.height.toDp(density))
                                .clip(shape)
                        },
                    )
                } else if (fallbackText != null) {
                    androidx.compose.material3.Text(
                        text = fallbackText,
                        style = fallbackStyle,
                        color = fallbackColor,
                        maxLines = 1,
                    )
                }
            }
        }
        geometry.number?.let { rect ->
            androidx.compose.material3.Text(
                text = number.orEmpty(),
                style = numberStyle,
                color = numberColor,
                // NEVER ellipsized: the surface sized this column from the
                // widest number it expects ("1500.5").
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                textAlign = if (numberOnLeft) {
                    androidx.compose.ui.text.style.TextAlign.Start
                } else {
                    androidx.compose.ui.text.style.TextAlign.Center
                },
                modifier = Modifier
                    .offset(rect.left.toDp(density), rect.top.toDp(density))
                    .width(rect.width.toDp(density)),
            )
        }
        geometry.name?.let { rect ->
            androidx.compose.material3.Text(
                text = name.orEmpty(),
                style = nameStyle,
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .offset(rect.left.toDp(density), rect.top.toDp(density))
                    .width(rect.width.toDp(density)),
            )
        }
    }
}

private fun Float.toDp(density: Density): Dp = with(density) { this@toDp.toDp() }

/** Gap between the logo and the text stack under it. */
val CHANNEL_BADGE_LOGO_GAP = 3.dp

/** Gap between the TV number column and the logo column beside it. */
val CHANNEL_BADGE_NUMBER_GAP = 6.dp

/** Gap between the number line and the name line. */
val CHANNEL_BADGE_LINE_GAP = 1.dp

/**
 * The widest channel number the badge sizes its column for: four digits plus
 * the decimal sub-channel form Dispatcharr can emit. A longer number widens
 * its own surface rather than being clipped.
 */
const val CHANNEL_NUMBER_REFERENCE = "1500.5"
