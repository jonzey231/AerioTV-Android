package com.aeriotv.android.core.cast.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewCompositeLayoutTest {

    private fun r(l: Int, t: Int, w: Int, h: Int) = CompositeRect(l, t, w, h)

    @Test
    fun `two tiles side by side, full height, 4 px gap`() {
        assertEquals(
            listOf(r(0, 0, 638, 720), r(642, 0, 638, 720)),
            MultiviewCompositeLayout.tileRects(2),
        )
    }

    @Test
    fun `three tiles big left, two stacked right`() {
        assertEquals(
            listOf(r(0, 0, 851, 720), r(855, 0, 425, 358), r(855, 362, 425, 358)),
            MultiviewCompositeLayout.tileRects(3),
        )
    }

    @Test
    fun `four tiles in a 2x2`() {
        assertEquals(
            listOf(r(0, 0, 638, 358), r(642, 0, 638, 358), r(0, 362, 638, 358), r(642, 362, 638, 358)),
            MultiviewCompositeLayout.tileRects(4),
        )
    }

    @Test
    fun `every layout stays inside 1280x720 without overlap`() {
        for (n in 2..4) {
            val rects = MultiviewCompositeLayout.tileRects(n)
            assertEquals(n, rects.size)
            rects.forEach {
                assertTrue(it.left >= 0 && it.top >= 0 && it.right <= 1280 && it.bottom <= 720)
            }
            for (i in rects.indices) for (j in i + 1 until rects.size) {
                val a = rects[i]
                val b = rects[j]
                val overlap = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
                assertFalse("tiles $i and $j overlap at n=$n", overlap)
            }
        }
    }

    @Test
    fun `cast cap is 2 to 4 tiles`() {
        assertFalse(MultiviewCompositeLayout.canCast(1))
        assertTrue(MultiviewCompositeLayout.canCast(2))
        assertTrue(MultiviewCompositeLayout.canCast(4))
        assertFalse(MultiviewCompositeLayout.canCast(5))
        assertTrue(MultiviewCompositeLayout.tileRects(5).isEmpty())
    }

    @Test
    fun `16x9 video letterboxes into a 2-up cell`() {
        val area = MultiviewCompositeLayout.pictureArea(r(0, 0, 638, 720), focused = false)
        assertEquals(r(2, 2, 634, 716), area)
        // 634 / (16/9) = 356.6 -> 357 rows, centered vertically.
        assertEquals(r(2, 181, 634, 357), MultiviewCompositeLayout.letterbox(area, 1920, 1080))
    }

    @Test
    fun `4x3 video pillarboxes into a 2x2 cell and the focus border is thicker`() {
        val area = MultiviewCompositeLayout.pictureArea(r(0, 0, 638, 358), focused = true)
        assertEquals(r(4, 4, 630, 350), area)
        // 350 * 4/3 = 466.7 -> 467 wide, centered horizontally.
        assertEquals(r(85, 4, 467, 350), MultiviewCompositeLayout.letterbox(area, 720, 540))
        // Anamorphic 720x576 at 64:45 pixels is 16:9: 350 * 16/9 = 622 wide.
        assertEquals(r(8, 4, 622, 350), MultiviewCompositeLayout.letterbox(area, 720, 576, 64f / 45f))
    }

    @Test
    fun `hit test maps preview taps to tiles and gaps to none`() {
        // A 640x360 preview is the frame at half scale.
        assertEquals(0, MultiviewCompositeLayout.hitTestView(100f, 100f, 640f, 360f, 4))
        assertEquals(3, MultiviewCompositeLayout.hitTestView(600f, 300f, 640f, 360f, 4))
        assertEquals(-1, MultiviewCompositeLayout.hitTestView(320f, 100f, 640f, 360f, 4)) // the gap
        assertEquals(1, MultiviewCompositeLayout.hitTestView(500f, 50f, 640f, 360f, 3))
        assertEquals(2, MultiviewCompositeLayout.hitTestView(500f, 300f, 640f, 360f, 3))
    }

    @Test
    fun `swap exchanges two positions and the compositor slots follow`() {
        val order = MultiviewCompositeLayout.swapOrder(listOf(0, 1, 2, 3), 0, 3)
        assertEquals(listOf(3, 1, 2, 0), order)
        // Tile 3 now draws in cell 0 and tile 0 in cell 3.
        assertEquals(listOf(3, 1, 2, 0), MultiviewCompositeLayout.slotsFor(order).toList())
        val again = MultiviewCompositeLayout.swapOrder(order, 1, 3)
        assertEquals(listOf(3, 0, 2, 1), again)
        assertEquals(listOf(1, 3, 2, 0), MultiviewCompositeLayout.slotsFor(again).toList())
        assertEquals(order, MultiviewCompositeLayout.swapOrder(order, 2, 2))
        assertEquals(order, MultiviewCompositeLayout.swapOrder(order, 0, 9))
    }

    @Test
    fun `stream info names the composite, container and audio`() {
        assertEquals(
            listOf("Multiview composite 1280x720@30", "Container: MPEG-TS to fMP4", "Audio: AAC-LC stereo 48 kHz"),
            MultiviewCompositeLayout.STREAM_INFO_LINES,
        )
    }

    @Test
    fun `borders are 2 px gray, 4 px focus, 4 px gaps`() {
        assertEquals(2, MultiviewCompositeLayout.BORDER)
        assertEquals(4, MultiviewCompositeLayout.FOCUS_BORDER)
        assertEquals(4, MultiviewCompositeLayout.GAP)
    }
}
