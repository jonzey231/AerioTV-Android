package com.aeriotv.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android TV Settings rail and pane geometry, Apple 08c1be8 halved (1 tvOS pt = 0.5 dp). */
class TvRailGeometryTest {
    private val m = TvSettingsMetrics

    @Test fun twelveRowsFitAboveTheBottomSafeEdge() {
        val end = m.railRestingTop.value + 12 * m.railRowHeight.value + 11 * m.railRowGap.value
        assertEquals(504.5f, end, 0.001f)
        assertTrue(end <= 540f - 30f)
        // A 34 dp pitch (tvOS 68).
        assertEquals(34f, m.railRowHeight.value + m.railRowGap.value, 0.001f)
    }

    /**
     * Apple's 11 rows fit at Text Size 130%; so do Android TV's. At 130% the
     * platform's nonlinear font scaling draws the 13 sp label at 17.2 dp (its
     * table maps 12 sp to 15.6 and 14 sp to 18.8) and the 9 sp subtitle at
     * 11.7 dp (8 to 10.4, 10 to 13), line heights keep their ratio to the font
     * size, and Compose rounds each line box UP to whole pixels (2 px per dp).
     * Measured on the TV emulator: the 11th row ends at y 509 dp (Apple TV:
     * 509 as well).
     */
    @Test fun elevenRowsFitAtTextSize130() {
        fun lerp(x: Float, x0: Float, x1: Float, y0: Float, y1: Float) = y0 + (x - x0) / (x1 - x0) * (y1 - y0)
        val title = m.railTitleSize.value
        val sub = m.railSubtitleSize.value
        val titleDp = lerp(title, 12f, 14f, 15.6f, 18.8f)
        val subDp = lerp(sub, 8f, 10f, 10.4f, 13f)
        fun linePx(lineSp: Float, sizeSp: Float, sizeDp: Float) =
            kotlin.math.ceil(lineSp / sizeSp * sizeDp * 2f - 1e-3f)
        val rowPx = linePx(m.railTitleLineHeight.value, title, titleDp) +
            m.railTextSpacing.value * 2f +
            linePx(m.railSubtitleLineHeight.value, sub, subDp)
        val rowDp = maxOf(rowPx / 2f, m.railRowHeight.value)
        val end = m.railRestingTop.value + 11 * rowDp + 10 * m.railRowGap.value
        assertEquals(509f, end, 0.001f)
        assertTrue(end <= 540f - 30f)
        // The default size is unchanged: the label's line box is still 31 px.
        assertEquals(31f, kotlin.math.ceil(m.railTitleLineHeight.value * 2f), 0.001f)
    }

    @Test fun railAndPaneLandWhereAppleTvDraws() {
        // Row cards from x 70 to 246.75 (tvOS 140 to 493.5) in a 255 dp column.
        assertEquals(70f, m.railRowStart.value, 0.001f)
        assertEquals(255f, m.railColumnWidth.value, 0.001f)
        assertEquals(9f, m.railRowPaddingH.value, 0.001f)
        // Pane cards from x 311.5 to 863.25 (tvOS 623 to 1727, Apple TV's one
        // pane container, a22268e): pages pad their cards 16 dp.
        assertEquals(311.5f, m.railColumnWidth.value + m.paneInsetStart.value + 16f, 0.001f)
        assertEquals(863.25f, 960f - m.paneInsetEnd.value - 16.5f, 0.001f)
        // Rows, eyebrows, and footers sit 10 dp inside the card (tvOS 20).
        assertEquals(10f, m.paneRowPaddingH.value, 0.001f)
    }
}
