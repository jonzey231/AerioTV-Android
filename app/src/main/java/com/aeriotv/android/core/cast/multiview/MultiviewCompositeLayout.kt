package com.aeriotv.android.core.cast.multiview

import com.aeriotv.android.feature.multiview.MultiviewGridMath
import com.aeriotv.android.feature.multiview.MultiviewLayoutMode
import kotlin.math.roundToInt

/** An integer pixel rect in the composite frame, top-left origin. */
data class CompositeRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
    fun inset(px: Int): CompositeRect =
        CompositeRect(left + px, top + px, (width - 2 * px).coerceAtLeast(0), (height - 2 * px).coerceAtLeast(0))
}

/**
 * Geometry of the phone-composited Multiview cast (Logan 2026-10-06): one
 * 1280x720 frame holding 2 to 4 tiles in the SAME shapes the local Multiview
 * draws for that count ([MultiviewGridMath], Default layout, landscape), with
 * a small gap, a thin border per tile and a thicker highlight on the
 * audio-focused tile. Pure: the GL compositor and the phone preview's
 * tap-to-focus both read it, and the JUnit tests pin the numbers.
 */
object MultiviewCompositeLayout {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FPS = 30
    const val MIN_TILES = 2
    const val MAX_TILES = 4
    /** Gap between tiles, px of the 1280x720 frame. */
    const val GAP = 4
    /** Border of an unfocused tile. */
    const val BORDER = 2
    /** Border of the audio-focused tile. */
    const val FOCUS_BORDER = 4

    /** Whether [count] staged channels can be composited and cast. */
    fun canCast(count: Int): Boolean = count in MIN_TILES..MAX_TILES

    /** Tile cells for [count] tiles in a [width] x [height] frame. */
    fun tileRects(count: Int, width: Int = WIDTH, height: Int = HEIGHT, gap: Int = GAP): List<CompositeRect> {
        if (!canCast(count)) return emptyList()
        return MultiviewGridMath.rects(
            MultiviewLayoutMode.Auto, count, width.toFloat(), height.toFloat(), gap.toFloat(),
        ).map { r ->
            val l = r.left.roundToInt()
            val t = r.top.roundToInt()
            CompositeRect(l, t, r.right.roundToInt() - l, r.bottom.roundToInt() - t)
        }
    }

    /** The picture area inside a cell: the cell minus its border. */
    fun pictureArea(cell: CompositeRect, focused: Boolean): CompositeRect =
        cell.inset(if (focused) FOCUS_BORDER else BORDER)

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

    /** Tile index under a point in frame coordinates, or -1 (a gap). */
    fun hitTest(x: Float, y: Float, rects: List<CompositeRect>): Int = rects.indexOfFirst { it.contains(x, y) }

    /** Tile index under a tap on a preview of [viewWidth] x [viewHeight]
     *  showing the whole frame. */
    fun hitTestView(x: Float, y: Float, viewWidth: Float, viewHeight: Float, count: Int): Int {
        if (viewWidth <= 0f || viewHeight <= 0f) return -1
        return hitTest(x * WIDTH / viewWidth, y * HEIGHT / viewHeight, tileRects(count))
    }

    /** Cast Stream Info lines for a composite (shared wording with Apple). */
    val STREAM_INFO_LINES: List<String> = listOf(
        "Multiview composite ${WIDTH}x$HEIGHT@$FPS",
        "Container: MPEG-TS to fMP4",
        "Audio: AAC-LC stereo 48 kHz",
    )

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
