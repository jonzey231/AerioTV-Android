package com.aeriotv.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorPickerMathTest {
    @Test fun cleanHexInput() {
        assertEquals("2DD4BF", ColorPickerMath.cleanHexInput("#2dd4bf"))
        assertEquals("ABCDEF", ColorPickerMath.cleanHexInput("ab zcd-efff"))
    }

    @Test fun parseHex() {
        assertEquals(0x7C3AED, ColorPickerMath.parseHex("7c3aed"))
        assertEquals(0x7C3AED, ColorPickerMath.parseHex("#7C3AED"))
        assertNull(ColorPickerMath.parseHex("7C3AE"))
        assertNull(ColorPickerMath.parseHex("7C3AEG"))
        assertEquals("0A1628", ColorPickerMath.hex(0x0A1628))
    }

    @Test fun gridShape() {
        val grid = ColorPickerMath.gridColors()
        assertEquals(10, grid.size)
        grid.forEach { assertEquals(12, it.size) }
        assertEquals(0xFFFFFF, grid[0].first())
        assertEquals(0x000000, grid[0].last())
        grid[0].forEach { c -> assertTrue(ColorPickerMath.red(c) == ColorPickerMath.blue(c)) }
        // Hue rows run dark (top) to light (bottom).
        val l = { c: Int -> ColorPickerMath.toHsl(c)[2] }
        assertTrue(l(grid[1][0]) < l(grid[9][0]))
    }

    @Test fun hslRoundTrip() {
        val c = 0x7C3AED
        val (h, s, l) = ColorPickerMath.toHsl(c).let { Triple(it[0], it[1], it[2]) }
        assertEquals(c, ColorPickerMath.hsl(h, s, l))
    }
}
