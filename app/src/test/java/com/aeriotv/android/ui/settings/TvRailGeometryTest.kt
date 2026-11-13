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

    @Test fun railAndPaneLandWhereAppleTvDraws() {
        // Row cards from x 70 to 246.75 (tvOS 140 to 493.5) in a 255 dp column.
        assertEquals(70f, m.railRowStart.value, 0.001f)
        assertEquals(255f, m.railColumnWidth.value, 0.001f)
        assertEquals(9f, m.railRowPaddingH.value, 0.001f)
        // Pane cards from x 307.5 to 867.25 (tvOS 615 to 1734.5): pages pad their cards 16 dp.
        assertEquals(307.5f, m.railColumnWidth.value + m.paneInsetStart.value + 16f, 0.001f)
        assertEquals(867.25f, 960f - m.paneInsetEnd.value - 16.5f, 0.001f)
    }
}
