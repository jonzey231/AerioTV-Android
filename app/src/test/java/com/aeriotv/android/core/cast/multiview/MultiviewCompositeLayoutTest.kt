package com.aeriotv.android.core.cast.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewCompositeLayoutTest {

    private fun r(l: Int, t: Int, w: Int, h: Int) = CompositeRect(l, t, w, h)

    @Test
    fun `two tiles side by side, padding is the local 4 dp per side`() {
        // 4 dp at 1.5 px per dp = 6 px each side: 12 px between, 6 px margin.
        assertEquals(
            listOf(r(6, 6, 628, 708), r(646, 6, 628, 708)),
            MultiviewCompositeLayout.tileRects(2),
        )
        assertEquals(
            listOf(r(0, 0, 640, 720), r(640, 0, 640, 720)),
            MultiviewCompositeLayout.tileRects(2, padding = false),
        )
    }

    @Test
    fun `four tiles in a 2x2`() {
        assertEquals(
            listOf(r(0, 0, 640, 360), r(640, 0, 640, 360), r(0, 360, 640, 360), r(640, 360, 640, 360)),
            MultiviewCompositeLayout.tileRects(4, padding = false),
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
    fun `a running session may draw one tile full frame`() {
        assertFalse(MultiviewCompositeLayout.canCast(1))
        assertTrue(MultiviewCompositeLayout.canComposite(1))
        assertFalse(MultiviewCompositeLayout.canComposite(0))
        assertFalse(MultiviewCompositeLayout.canComposite(5))
        val full = listOf(CompositeRect(0, 0, 1280, 720))
        assertEquals(full, MultiviewCompositeLayout.tileRects(1))
        // No padding even with Padding Between Tiles on.
        assertEquals(full, MultiviewCompositeLayout.tileRects(1, padding = true))
        assertEquals(0, MultiviewCompositeLayout.hitTestView(5f, 5f, 100f, 56f, 1))
    }

    @Test
    fun `16x9 video letterboxes into a 2-up cell`() {
        val cell = r(6, 6, 628, 708)
        // 628 / (16/9) = 353.25 -> 353 rows, centered vertically.
        assertEquals(r(6, 183, 628, 353), MultiviewCompositeLayout.letterbox(cell, 1920, 1080))
    }

    @Test
    fun `4x3 and anamorphic video in a 2x2 cell`() {
        val cell = r(0, 0, 640, 360)
        // 360 * 4/3 = 480 wide, centered horizontally.
        assertEquals(r(80, 0, 480, 360), MultiviewCompositeLayout.letterbox(cell, 720, 540))
        // Anamorphic 720x576 at 64:45 pixels is 16:9: fills the cell.
        assertEquals(r(0, 0, 640, 360), MultiviewCompositeLayout.letterbox(cell, 720, 576, 64f / 45f))
    }

    @Test
    fun `hit test maps preview taps to tiles and gaps to none`() {
        // A 640x360 preview is the frame at half scale.
        assertEquals(0, MultiviewCompositeLayout.hitTestView(100f, 100f, 640f, 360f, 4))
        assertEquals(3, MultiviewCompositeLayout.hitTestView(600f, 300f, 640f, 360f, 4))
        assertEquals(-1, MultiviewCompositeLayout.hitTestView(320f, 100f, 640f, 360f, 4)) // the gap
        assertEquals(0, MultiviewCompositeLayout.hitTestView(319f, 100f, 640f, 360f, 4, padding = false))
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
    fun `stream info leads with the source host`() {
        assertEquals(
            listOf("Source: tv.example.net", "Multiview composite 1280x720@30", "Container: MPEG-TS to fMP4", "Audio: AAC-LC stereo 48 kHz"),
            MultiviewCompositeLayout.streamInfoLines(
                MultiviewCompositeLayout.sourceHost(
                    listOf("http://tv.example.net:9191/proxy/ts/stream/1", "http://user:pw@tv.example.net/live/2.ts"),
                ),
            ),
        )
        assertEquals(MultiviewCompositeLayout.STREAM_INFO_LINES, MultiviewCompositeLayout.streamInfoLines(null))
        assertEquals("a.net, [::1]", MultiviewCompositeLayout.sourceHost(listOf("https://a.net/x", "http://[::1]:80/y", "bad")))
    }

    @Test
    fun `fading indicator holds 2 s then fades over 0_5 s`() {
        assertEquals(1f, MultiviewCompositeLayout.fadingAlpha(0L), 0f)
        assertEquals(1f, MultiviewCompositeLayout.fadingAlpha(1_999_000_000L), 0f)
        assertEquals(0.5f, MultiviewCompositeLayout.fadingAlpha(2_250_000_000L), 0.001f)
        assertEquals(0f, MultiviewCompositeLayout.fadingAlpha(2_500_000_000L), 0f)
    }

    @Test
    fun `focus indicator follows the user's style`() {
        val accent = 0xFF3399FF.toInt()
        val fading = CompositeStyle(focusStyle = "themeFading")
        assertEquals(accent, MultiviewCompositeLayout.focusBorderArgb(fading, accent, 0L))
        assertEquals(null, MultiviewCompositeLayout.focusBorderArgb(fading, accent, 3_000_000_000L))
        val gray = CompositeStyle(focusStyle = "grayPersistent")
        assertEquals(0x80FFFFFF.toInt(), MultiviewCompositeLayout.focusBorderArgb(gray, accent, 60_000_000_000L))
        val icon = CompositeStyle(focusStyle = "centerIcon")
        assertEquals(null, MultiviewCompositeLayout.focusBorderArgb(icon, accent, 0L))
        assertEquals(0.85f, MultiviewCompositeLayout.iconAlpha(icon, 0L), 0.001f)
        assertEquals(0f, MultiviewCompositeLayout.iconAlpha(icon, 3_000_000_000L), 0f)
        assertEquals(0f, MultiviewCompositeLayout.iconAlpha(gray, 0L), 0f)
        assertEquals(12f, MultiviewCompositeLayout.cornerRadius(CompositeStyle(rounded = true)), 0f)
        assertEquals(0f, MultiviewCompositeLayout.cornerRadius(CompositeStyle(rounded = false)), 0f)
    }

    @Test
    fun `logo follows the local size and corner math`() {
        val video = r(0, 0, 640, 360)
        // 10 percent: h = 36 - 12 = 24; a 3:1 logo is 72x24 inside a 4 dp (6 px) backdrop, 8 dp (12 px) in.
        val tl = MultiviewCompositeLayout.logoPlacement(video, 3f, CompositeStyle(logoSizePercent = 10))
        assertEquals(r(12, 12, 84, 36), tl.backdrop)
        assertEquals(r(18, 18, 72, 24), tl.logo)
        // Square logo: height 24 * sqrt(3) = 41.6, bottom right.
        val br = MultiviewCompositeLayout.logoPlacement(video, 1f, CompositeStyle(logoPosition = "bottom_right"))
        assertEquals(r(574, 294, 54, 54), br.backdrop)
        // Very wide logo caps at 4h.
        val wide = MultiviewCompositeLayout.logoPlacement(video, 10f, CompositeStyle())
        assertEquals(96, wide.logo.width)
    }

    @Test
    fun `a tap on the center of every drawn tile resolves to that tile, every layout and count`() {
        // The phone preview at a typical portrait sheet width (not 16:9 exact).
        val vw = 1032f
        val vh = 580f
        for (count in 1..MultiviewCompositeLayout.MAX_TILES) {
            for (mode in MultiviewCompositeLayout.layoutOptions(count)) {
                for (padding in listOf(true, false)) {
                    val rects = MultiviewCompositeLayout.tileRects(count, padding, mode = mode)
                    assertEquals(count, rects.size)
                    rects.forEachIndexed { pos, cell ->
                        val x = (cell.left + cell.width / 2f) * vw / MultiviewCompositeLayout.WIDTH
                        val y = (cell.top + cell.height / 2f) * vh / MultiviewCompositeLayout.HEIGHT
                        assertEquals(
                            "count=$count mode=$mode padding=$padding pos=$pos",
                            pos,
                            MultiviewCompositeLayout.hitTestView(x, y, vw, vh, count, padding, mode),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `three-up Default puts the second and third tiles in the right column`() {
        val vw = 1000f
        val vh = 562.5f
        val hit = { x: Float, y: Float -> MultiviewCompositeLayout.hitTestView(x, y, vw, vh, 3) }
        assertEquals(0, hit(300f, 280f))
        assertEquals(1, hit(850f, 120f))
        assertEquals(2, hit(850f, 440f))
    }

    @Test
    fun `tile retries back off to 30 s`() {
        assertEquals(
            listOf(3_000L, 6_000L, 12_000L, 24_000L, 30_000L, 30_000L, 30_000L),
            (1..7).map { MultiviewCompositeLayout.tileRetryDelayMs(it) },
        )
    }
}
