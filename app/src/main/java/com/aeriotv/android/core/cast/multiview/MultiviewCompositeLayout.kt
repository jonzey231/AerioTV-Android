package com.aeriotv.android.core.cast.multiview

import com.aeriotv.android.feature.multiview.MultiviewGridMath
import com.aeriotv.android.feature.multiview.MultiviewLayoutMode
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** An integer pixel rect in the composite frame, top-left origin. */
data class CompositeRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
    fun inset(px: Int): CompositeRect =
        CompositeRect(left + px, top + px, (width - 2 * px).coerceAtLeast(0), (height - 2 * px).coerceAtLeast(0))
}

/**
 * The user's Multiview look (Settings > Player > Multiview), the same values
 * the local grid reads (MultiviewScreen): audio focus indicator, padding
 * between tiles, tile corners, channel logos with corner and size.
 */
data class CompositeStyle(
    /** "centerIcon", "grayPersistent" or "themeFading" (AppPreferences). */
    val focusStyle: String = "centerIcon",
    val padding: Boolean = true,
    val rounded: Boolean = false,
    val showLogos: Boolean = false,
    /** "top_left", "top_right", "bottom_left" or "bottom_right". */
    val logoPosition: String = "top_left",
    /** Logo height as a percent of the picture height, 5 to 25. */
    val logoSizePercent: Int = 10,
)

/** Where a channel logo lands: its backdrop and the logo inside it. */
data class CompositeLogoPlacement(val backdrop: CompositeRect, val logo: CompositeRect)

/**
 * Geometry of the phone-composited Multiview cast (Logan 2026-10-06): one
 * 1280x720 frame holding 2 to 4 tiles in the SAME shapes the local Multiview
 * draws for that count ([MultiviewGridMath], Default layout, landscape).
 * Round 3 (2026-10-07): the look follows [CompositeStyle] exactly as the
 * local grid draws it, with local dp mapped at [DP] frame pixels per dp
 * (the frame reads as an 853x480 dp screen). Pure: the GL compositor and
 * the phone preview's tap-to-focus both read it, and the JUnit tests pin
 * the numbers.
 */
object MultiviewCompositeLayout {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FPS = 30
    const val MIN_TILES = 2
    const val MAX_TILES = 4
    /** Frame pixels per local dp. */
    const val DP = 1.5f
    /** Local tile padding (4 dp per side when Padding Between Tiles is on). */
    val PAD_PX: Int = (4 * DP).roundToInt()
    /** Local rounded tile corner (8 dp). */
    val CORNER_PX: Float = 8 * DP
    /** Local audio-focus border (2 dp, Gray Outline and Accent Outline). */
    val FOCUS_BORDER_PX: Float = 2 * DP
    /** Local speaker icon (48 dp) for the Speaker Icon style. */
    val ICON_PX: Int = (48 * DP).roundToInt()

    /** The fading indicator holds this long after a focus change... */
    const val FOCUS_HOLD_NANOS = 2_000_000_000L
    /** ...then fades out over this long. */
    const val FOCUS_FADE_NANOS = 500_000_000L

    /** Whether [count] staged channels can START a composite cast (2 to 4). */
    fun canCast(count: Int): Boolean = count in MIN_TILES..MAX_TILES

    /**
     * Whether a RUNNING composite can draw [count] tiles (Apple 6136daf round
     * 9): 1 to 4. A session removed down to one tile keeps the composite
     * running with that tile full frame, so the receiver never reloads; only
     * a start needs [MIN_TILES].
     */
    fun canComposite(count: Int): Boolean = count in 1..MAX_TILES

    /** One tile left: drawn full frame like a single-channel cast (no
     *  padding, corner clip, logo or focus indicator). */
    fun isSingle(count: Int): Boolean = count == 1

    /**
     * Tile rects for [count] tiles in a [width] x [height] frame: the local
     * grid's cells (zero spacing) inset by the local tile padding when
     * [padding] is on, so neighbors sit 8 dp apart and 4 dp off the edge.
     * One tile (a running session dropped to one) fills the whole frame.
     */
    fun tileRects(
        count: Int,
        padding: Boolean = true,
        width: Int = WIDTH,
        height: Int = HEIGHT,
        mode: MultiviewLayoutMode = MultiviewLayoutMode.Auto,
    ): List<CompositeRect> {
        if (!canComposite(count)) return emptyList()
        if (isSingle(count)) return listOf(CompositeRect(0, 0, width, height))
        val pad = if (padding) PAD_PX else 0
        return MultiviewGridMath.rects(
            effectiveMode(mode, count), count, width.toFloat(), height.toFloat(), 0f,
        ).map { r ->
            val l = r.left.roundToInt()
            val t = r.top.roundToInt()
            CompositeRect(l, t, r.right.roundToInt() - l, r.bottom.roundToInt() - t).inset(pad)
        }
    }

    /** The layouts the composite offers for [count] tiles: the same list
     *  Settings > Player > Multiview offers ([MultiviewLayoutMode.available]),
     *  Default first; just Default when the count has no alternative. */
    fun layoutOptions(count: Int): List<MultiviewLayoutMode> =
        MultiviewLayoutMode.available(count).ifEmpty { listOf(MultiviewLayoutMode.Auto) }

    /** [mode] when it is valid for [count] tiles, otherwise Default (the
     *  local grid's fallback). */
    fun effectiveMode(mode: MultiviewLayoutMode, count: Int): MultiviewLayoutMode =
        if (mode in layoutOptions(count)) mode else MultiviewLayoutMode.Auto

    /** Corner radius of a tile in frame pixels. */
    fun cornerRadius(style: CompositeStyle): Float = if (style.rounded) CORNER_PX else 0f

    /**
     * Opacity of the fading indicator (Accent Outline, Speaker Icon)
     * [sinceFocusNanos] after the last focus change: full for 2 s, then a
     * linear fade to zero over 0.5 s.
     */
    fun fadingAlpha(sinceFocusNanos: Long): Float = when {
        sinceFocusNanos < FOCUS_HOLD_NANOS -> 1f
        sinceFocusNanos >= FOCUS_HOLD_NANOS + FOCUS_FADE_NANOS -> 0f
        else -> 1f - (sinceFocusNanos - FOCUS_HOLD_NANOS).toFloat() / FOCUS_FADE_NANOS
    }

    /** The audio-focus border (ARGB, alpha applied) or null for none. */
    fun focusBorderArgb(style: CompositeStyle, accentArgb: Int, sinceFocusNanos: Long): Int? = when (style.focusStyle) {
        "grayPersistent" -> 0x80FFFFFF.toInt()
        "themeFading" -> {
            val a = fadingAlpha(sinceFocusNanos)
            if (a <= 0f) null else withAlpha(accentArgb, a)
        }
        else -> null
    }

    /** The speaker icon's opacity (Speaker Icon style), 0 when hidden. */
    fun iconAlpha(style: CompositeStyle, sinceFocusNanos: Long): Float =
        if (style.focusStyle == "centerIcon") 0.85f * fadingAlpha(sinceFocusNanos) else 0f

    private fun withAlpha(argb: Int, alpha: Float): Int {
        val a = ((argb ushr 24) * alpha).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    /**
     * Letterbox (or pillarbox) a [videoWidth] x [videoHeight] picture with
     * [pixelRatio] (pixel width / height) into [area], centered. Unknown
     * video size fills the area.
     */
    fun letterbox(area: CompositeRect, videoWidth: Int, videoHeight: Int, pixelRatio: Float = 1f): CompositeRect {
        if (videoWidth <= 0 || videoHeight <= 0 || area.width <= 0 || area.height <= 0) return area
        val videoAspect = videoWidth * (if (pixelRatio > 0f) pixelRatio else 1f) / videoHeight.toFloat()
        val areaAspect = area.width / area.height.toFloat()
        return if (videoAspect > areaAspect) {
            val h = (area.width / videoAspect).roundToInt().coerceIn(1, area.height)
            CompositeRect(area.left, area.top + (area.height - h) / 2, area.width, h)
        } else {
            val w = (area.height * videoAspect).roundToInt().coerceIn(1, area.width)
            CompositeRect(area.left + (area.width - w) / 2, area.top, w, area.height)
        }
    }

    /**
     * The local TileChannelLogo math in frame pixels: inside the picture
     * [video], 8 dp off the chosen corner, a 4 dp black backdrop around a
     * logo of opaque aspect [logoAspect] (3:1 until known). With
     * h = picture height x size percent minus 8 dp: height =
     * h x clamp(sqrt(3 / a), 1, 2), width = height x a, width capped at 4h and
     * at half the picture width minus 8 dp. No name badge is drawn on the
     * composite, so a top-left logo is never shifted.
     */
    fun logoPlacement(video: CompositeRect, logoAspect: Float, style: CompositeStyle): CompositeLogoPlacement {
        val a = if (logoAspect > 0f) logoAspect else 3f
        val h = (video.height * (style.logoSizePercent.coerceIn(5, 25) / 100f) - 8 * DP).coerceAtLeast(1f)
        var logoH = h * sqrt(3f / a).coerceIn(1f, 2f)
        var logoW = logoH * a
        val maxW = minOf(h * 4f, video.width * 0.5f - 8 * DP)
        if (logoW > maxW) {
            logoW = maxW
            logoH = maxW / a
        }
        val inset = 8 * DP
        val pad = 4 * DP
        val bw = logoW + 2 * pad
        val bh = logoH + 2 * pad
        val right = style.logoPosition == "top_right" || style.logoPosition == "bottom_right"
        val bottom = style.logoPosition == "bottom_left" || style.logoPosition == "bottom_right"
        val bx = if (right) video.right - inset - bw else video.left + inset
        val by = if (bottom) video.bottom - inset - bh else video.top + inset
        val backdrop = CompositeRect(bx.roundToInt(), by.roundToInt(), bw.roundToInt(), bh.roundToInt())
        val logo = CompositeRect((bx + pad).roundToInt(), (by + pad).roundToInt(), logoW.roundToInt(), logoH.roundToInt())
        return CompositeLogoPlacement(backdrop, logo)
    }

    /** Tile index under a point in frame coordinates, or -1 (a gap). */
    fun hitTest(x: Float, y: Float, rects: List<CompositeRect>): Int = rects.indexOfFirst { it.contains(x, y) }

    /** Tile index under a tap on a preview of [viewWidth] x [viewHeight]
     *  showing the whole frame. */
    fun hitTestView(
        x: Float,
        y: Float,
        viewWidth: Float,
        viewHeight: Float,
        count: Int,
        padding: Boolean = true,
        mode: MultiviewLayoutMode = MultiviewLayoutMode.Auto,
    ): Int {
        if (viewWidth <= 0f || viewHeight <= 0f) return -1
        return hitTest(x * WIDTH / viewWidth, y * HEIGHT / viewHeight, tileRects(count, padding, mode = mode))
    }

    /** Cast Stream Info lines for a composite (shared wording with Apple). */
    val STREAM_INFO_LINES: List<String> = listOf(
        "Multiview composite ${WIDTH}x$HEIGHT@$FPS",
        "Container: MPEG-TS to fMP4",
        "Audio: AAC-LC stereo 48 kHz",
    )

    /** Stream Info with the tiles' upstream host first (round 3, Apple's
     *  SOURCE row), then the three composite lines. */
    fun streamInfoLines(sourceHost: String?): List<String> =
        listOfNotNull(sourceHost?.takeIf { it.isNotBlank() }?.let { "Source: $it" }) + STREAM_INFO_LINES

    /** Distinct upstream hosts of [urls], comma separated; null when none. */
    fun sourceHost(urls: List<String>): String? =
        urls.mapNotNull { hostOf(it) }.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ")

    private fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "").ifEmpty { return null }
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return host.ifBlank { null }
    }

    /** [order] (position -> tile index) with positions [a] and [b] swapped;
     *  unchanged when either is out of range or they are equal. */
    fun swapOrder(order: List<Int>, a: Int, b: Int): List<Int> {
        if (a == b || a !in order.indices || b !in order.indices) return order
        return order.toMutableList().also { it[a] = order[b]; it[b] = order[a] }
    }

    /** The compositor's tile -> cell map for [order] (position -> tile). */
    fun slotsFor(order: List<Int>): IntArray {
        val slots = IntArray(order.size)
        order.forEachIndexed { pos, tile -> if (tile in slots.indices) slots[tile] = pos }
        return slots
    }
}
