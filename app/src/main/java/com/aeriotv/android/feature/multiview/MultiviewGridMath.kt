package com.aeriotv.android.feature.multiview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Selectable multiview layout (iOS `MultiviewLayoutMode`, issue #48). One
 * global, persisted choice for all tile counts; the picker lives in the tile
 * context menu.
 *
 * `key` is the persisted DataStore value (AppPreferences.multiviewLayoutMode);
 * `displayName` is the menu label. Ported 1:1 from
 * `Features/Multiview/MultiviewGridMath.swift`.
 */
enum class MultiviewLayoutMode(val key: String, val displayName: String) {
    /** The built-in default layout for the current count (layout1..layout9). */
    Auto("auto", "Default"),

    /** Balanced equal-size grid, landscape-preferred, last partial row centered. */
    EvenGrid("evenGrid", "Even Grid"),

    /** One large hero tile on the left with the rest stacked on the right. */
    Spotlight("spotlight", "Spotlight"),

    /** 6 only: the 5-tile hero layout with the empty bottom-right cell filled. */
    HeroCorner("heroCorner", "Hero + Corner");

    companion object {
        fun from(key: String?): MultiviewLayoutMode =
            entries.firstOrNull { it.key == key } ?: Auto

        /**
         * iOS `available(forTileCount:)`. The modes worth offering at a given
         * count (duplicates of `.auto` omitted where the default already IS an
         * even grid). Empty for counts with no real choice (<= 1) so the picker
         * hides. Note N=3's `.auto` is already the spotlight shape, so 3 offers
         * only Default + Even Grid (the per-tile Spotlight action still exists).
         */
        fun available(forTileCount: Int): List<MultiviewLayoutMode> = when {
            forTileCount < 2 -> emptyList()
            forTileCount == 2 -> listOf(Auto, Spotlight)
            forTileCount == 3 -> listOf(Auto, EvenGrid)
            forTileCount == 4 -> listOf(Auto, Spotlight)
            forTileCount == 5 -> listOf(Auto, EvenGrid, Spotlight)
            forTileCount == 6 -> listOf(Auto, HeroCorner, Spotlight)
            else -> listOf(Auto, Spotlight) // 7, 8, 9
        }
    }
}

enum class NeighborDirection { Left, Right, Up, Down }

/**
 * Pure geometry port of iOS `MultiviewGridMath`
 * (`Features/Multiview/MultiviewGridMath.swift`): turns
 * (mode, tileCount, containerSize, spacing) into per-tile rects so Android and
 * iOS/tvOS render the same shapes. Works in whatever unit width/height are
 * given -- Dp values for on-screen placement, px for pointer hit-testing.
 *
 * The Android grid supplies its own inter-tile gap via a per-tile `.padding`,
 * so callers pass `spacing = 0` here; the layout SHAPES are identical either
 * way (spacing only shrinks tiles uniformly). The `spacing` parameter is kept
 * for a faithful 1:1 mapping to the Swift.
 */
object MultiviewGridMath {

    private fun rect(x: Float, y: Float, w: Float, h: Float) = Rect(x, y, x + w, y + h)

    /**
     * Resolve rects for an explicit layout MODE. Falls back to the per-count
     * default for any mode that does not apply to the current count.
     */
    fun rects(
        mode: MultiviewLayoutMode,
        count: Int,
        width: Float,
        height: Float,
        spacing: Float = 0f,
    ): List<Rect> = when (mode) {
        MultiviewLayoutMode.Auto -> autoRects(count, width, height, spacing)
        MultiviewLayoutMode.EvenGrid -> evenGridRects(count, width, height, spacing)
        MultiviewLayoutMode.Spotlight -> spotlightRects(count, width, height, spacing)
        MultiviewLayoutMode.HeroCorner ->
            if (count == 6) heroCornerRects(width, height, spacing)
            else autoRects(count, width, height, spacing)
    }

    /**
     * Tile-INDEXED rects with spotlight reconciliation (iOS
     * `MultiviewLayoutView.tileLayout`). Spotlight is active when the Spotlight
     * MODE is chosen OR a per-tile spotlight index is set; the hero tile takes
     * the big rect[0] and the rest fill rect[1..] in tile order, so a toggle
     * animates as a grow/shrink rather than a reshuffle. `spotlightIndex` is the
     * tile position of the per-tile Spotlight selection (null when unset); the
     * hero falls back to the first tile when only the MODE was picked.
     */
    fun resolvedRects(
        mode: MultiviewLayoutMode,
        count: Int,
        spotlightIndex: Int?,
        width: Float,
        height: Float,
        spacing: Float = 0f,
    ): List<Rect> {
        val spotActive =
            (mode == MultiviewLayoutMode.Spotlight || spotlightIndex != null) && count > 1
        if (spotActive) {
            val spot = spotlightRects(count, width, height, spacing)
            if (spot.size == count) {
                // Clamp so exactly one tile claims spot[0]; a stale/out-of-range
                // spotlightIndex would otherwise leave every tile pulling from
                // spot[1..] and run off the end (IndexOutOfBounds).
                val hero = (spotlightIndex ?: 0).takeIf { it in 0 until count } ?: 0
                val out = ArrayList<Rect>(count)
                var nextSmall = 1
                for (i in 0 until count) {
                    out.add(if (i == hero) spot[0] else spot[nextSmall++])
                }
                return out
            }
        }
        return rects(mode, count, width, height, spacing)
    }

    // ── auto: the per-count default table ────────────────────────────────

    private fun autoRects(count: Int, w: Float, h: Float, s: Float): List<Rect> {
        if (count <= 0 || w <= 0f || h <= 0f) return emptyList()
        return when (count.coerceAtMost(9)) {
            1 -> layout1(w, h)
            2 -> layout2(w, h, s)
            3 -> layout3(w, h, s)
            4 -> layout4(w, h, s)
            5 -> layout5(w, h, s)
            6 -> layout6(w, h, s)
            7 -> layout7(w, h, s)
            8 -> layout8(w, h, s)
            9 -> layout9(w, h, s)
            else -> emptyList()
        }
    }

    private fun layout1(w: Float, h: Float) = listOf(rect(0f, 0f, w, h))

    /** Two tiles side-by-side, full height. */
    private fun layout2(w: Float, h: Float, s: Float): List<Rect> {
        val tw = (w - s) / 2
        return listOf(
            rect(0f, 0f, tw, h),
            rect(tw + s, 0f, tw, h),
        )
    }

    /** Big tile left (2/3 width, full height); two small stacked right (1/3, half each). */
    private fun layout3(w: Float, h: Float, s: Float): List<Rect> {
        val bigW = (w - s) * 2 / 3
        val smallW = w - bigW - s
        val smallH = (h - s) / 2
        return listOf(
            rect(0f, 0f, bigW, h),
            rect(bigW + s, 0f, smallW, smallH),
            rect(bigW + s, smallH + s, smallW, smallH),
        )
    }

    /** Classic 2x2. */
    private fun layout4(w: Float, h: Float, s: Float): List<Rect> =
        uniformGrid(cols = 2, rows = 2, w = w, h = h, s = s)

    /**
     * 3x3's top-left 2x2 merged into one big tile, then 4 small tiles fill the
     * right column (2) and the bottom row's left two cells; the (col2,row2)
     * corner stays empty. Order: big -> (2,0) -> (2,1) -> (0,2) -> (1,2).
     */
    private fun layout5(w: Float, h: Float, s: Float): List<Rect> {
        val cellW = (w - 2 * s) / 3
        val cellH = (h - 2 * s) / 3
        val bigW = 2 * cellW + s
        val bigH = 2 * cellH + s
        val col2X = 2 * (cellW + s)
        val row2Y = 2 * (cellH + s)
        return listOf(
            rect(0f, 0f, bigW, bigH),
            rect(col2X, 0f, cellW, cellH),
            rect(col2X, cellH + s, cellW, cellH),
            rect(0f, row2Y, cellW, cellH),
            rect(cellW + s, row2Y, cellW, cellH),
        )
    }

    /** 3 columns x 2 rows. */
    private fun layout6(w: Float, h: Float, s: Float): List<Rect> =
        uniformGrid(cols = 3, rows = 2, w = w, h = h, s = s)

    /** 3x3 with bottom-left + bottom-right empty; centers the 7th tile at (1,2). */
    private fun layout7(w: Float, h: Float, s: Float): List<Rect> {
        val tw = (w - 2 * s) / 3
        val th = (h - 2 * s) / 3
        val rects = ArrayList<Rect>(7)
        for (row in 0 until 2) {
            for (col in 0 until 3) {
                rects.add(rect(col * (tw + s), row * (th + s), tw, th))
            }
        }
        rects.add(rect(tw + s, 2 * (th + s), tw, th))
        return rects
    }

    /** 3x3 with the bottom-center cell (1,2) empty. */
    private fun layout8(w: Float, h: Float, s: Float): List<Rect> {
        val tw = (w - 2 * s) / 3
        val th = (h - 2 * s) / 3
        val positions = listOf(
            0 to 0, 1 to 0, 2 to 0,
            0 to 1, 1 to 1, 2 to 1,
            0 to 2, /* skip (1,2) */ 2 to 2,
        )
        return positions.map { (col, row) ->
            rect(col * (tw + s), row * (th + s), tw, th)
        }
    }

    /** Full 3x3. */
    private fun layout9(w: Float, h: Float, s: Float): List<Rect> =
        uniformGrid(cols = 3, rows = 3, w = w, h = h, s = s)

    /** Uniform cols x rows grid, reading order (row-major, top-left first). */
    private fun uniformGrid(cols: Int, rows: Int, w: Float, h: Float, s: Float): List<Rect> {
        val tw = (w - (cols - 1) * s) / cols
        val th = (h - (rows - 1) * s) / rows
        val rects = ArrayList<Rect>(cols * rows)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                rects.add(rect(col * (tw + s), row * (th + s), tw, th))
            }
        }
        return rects
    }

    // ── evenGrid: balanced equal-size grid, partial last row centered ────

    fun evenGridRects(count: Int, w: Float, h: Float, s: Float): List<Rect> {
        if (count <= 0 || w <= 0f || h <= 0f) return emptyList()
        val n = count.coerceAtMost(9)
        val rows = maxOf(1, sqrt(n.toDouble()).toInt())
        val cols = ceil(n.toDouble() / rows).toInt()
        val tw = (w - (cols - 1) * s) / cols
        val th = (h - (rows - 1) * s) / rows
        val rects = ArrayList<Rect>(n)
        var remaining = n
        for (row in 0 until rows) {
            val inRow = minOf(cols, remaining)
            val rowW = inRow * tw + (inRow - 1) * s
            val xStart = (w - rowW) / 2
            for (i in 0 until inRow) {
                rects.add(rect(xStart + i * (tw + s), row * (th + s), tw, th))
            }
            remaining -= inRow
        }
        return rects
    }

    // ── spotlight: one big tile (2/3) + rest stacked right ───────────────

    fun spotlightRects(count: Int, w: Float, h: Float, s: Float): List<Rect> {
        if (count <= 0 || w <= 0f || h <= 0f) return emptyList()
        val n = count.coerceAtMost(9)
        if (n == 1) return layout1(w, h)
        val bigW = (w - s) * 2 / 3
        val smallW = w - bigW - s
        val smallCount = n - 1
        val smallH = (h - s * (smallCount - 1)) / smallCount
        val rects = ArrayList<Rect>(n)
        rects.add(rect(0f, 0f, bigW, h))
        for (i in 0 until smallCount) {
            val y = (smallH + s) * i
            rects.add(rect(bigW + s, y, smallW, smallH))
        }
        return rects
    }

    // ── heroCorner: layout5 + a 6th tile in the empty bottom-right cell ──

    fun heroCornerRects(w: Float, h: Float, s: Float): List<Rect> {
        if (w <= 0f || h <= 0f) return emptyList()
        val rects = ArrayList(layout5(w, h, s))
        val cellW = (w - 2 * s) / 3
        val cellH = (h - 2 * s) / 3
        val col2X = 2 * (cellW + s)
        val row2Y = 2 * (cellH + s)
        rects.add(rect(col2X, row2Y, cellW, cellH))
        return rects
    }

    // ── D-pad neighbor + hit-testing on arbitrary rects ──────────────────

    /**
     * Nearest physical neighbor of `index` in the given direction, chosen by
     * edge adjacency + perpendicular overlap (iOS `physicalNeighbor`). Unlike
     * iOS -- which always runs this against the `.auto` table -- Android passes
     * the ACTUAL displayed rects (mode + spotlight applied) so D-pad nav always
     * matches what is on screen. Reference rects use spacing 0 so `eps = 1`.
     */
    fun neighbor(
        index: Int,
        rects: List<Rect>,
        direction: NeighborDirection,
        eps: Float = 1f,
    ): Int? {
        if (index !in rects.indices) return null
        val me = rects[index]
        var best: Pair<Int, Float>? = null
        for (i in rects.indices) {
            if (i == index) continue
            val r = rects[i]
            val xOverlap = minOf(me.right, r.right) - maxOf(me.left, r.left)
            val yOverlap = minOf(me.bottom, r.bottom) - maxOf(me.top, r.top)
            val overlapsX = xOverlap > 1
            val overlapsY = yOverlap > 1
            val d: Float = when (direction) {
                NeighborDirection.Left -> {
                    if (!(r.right <= me.left + eps && overlapsY)) continue
                    me.left - r.right
                }
                NeighborDirection.Right -> {
                    if (!(r.left >= me.right - eps && overlapsY)) continue
                    r.left - me.right
                }
                NeighborDirection.Up -> {
                    if (!(r.bottom <= me.top + eps && overlapsX)) continue
                    me.top - r.bottom
                }
                NeighborDirection.Down -> {
                    if (!(r.top >= me.bottom - eps && overlapsX)) continue
                    r.top - me.bottom
                }
            }
            if (best == null || d < best.second) best = i to d
        }
        return best?.first
    }

    /**
     * Tile index whose rect contains `pos`; falls back to the nearest tile
     * center for points in an empty cell (layout5/7/8 leave a hole). Used for
     * the drag-to-reorder drop target.
     */
    fun indexAt(pos: Offset, rects: List<Rect>): Int {
        rects.forEachIndexed { i, r -> if (r.contains(pos)) return i }
        var best = 0
        var bestD = Float.MAX_VALUE
        rects.forEachIndexed { i, r ->
            val dx = r.center.x - pos.x
            val dy = r.center.y - pos.y
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
