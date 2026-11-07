package com.aeriotv.android.ui.settings

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure color math for the Theme Color / Accent Color sheet (parity with the
 * Apple color sheet's Grid, Spectrum, and Sliders tabs). Colors are packed
 * 0xRRGGBB ints so this compiles and tests on the plain JVM.
 */
object ColorPickerMath {
    const val GRID_COLUMNS = 12
    const val GRID_ROWS = 10

    /** Hue of each grid column, matching the order of the Apple grid. */
    private val GRID_HUES = floatArrayOf(196f, 218f, 252f, 280f, 340f, 6f, 14f, 28f, 40f, 52f, 66f, 92f)

    /**
     * Strips anything that is not a hex digit, uppercases, and caps at six,
     * the same rule the Hex Color field applies on Apple.
     */
    fun cleanHexInput(raw: String): String =
        raw.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)

    /** Parses exactly six hex digits (optional leading #) or returns null. */
    fun parseHex(raw: String): Int? {
        val s = raw.trim().removePrefix("#")
        if (s.length != 6 || s.any { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F') }) return null
        return s.toInt(16)
    }

    fun hex(rgb: Int): String = "%06X".format(rgb and 0xFFFFFF)

    fun rgb(r: Int, g: Int, b: Int): Int =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF

    /** HSL (h 0..360, s and l 0..1) to packed RGB. */
    fun hsl(h: Float, s: Float, l: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun q(v: Float) = ((v + m) * 255f).roundToInt()
        return rgb(q(r), q(g), q(b))
    }

    /** Packed RGB to HSL (h 0..360, s and l 0..1). */
    fun toHsl(c: Int): FloatArray {
        val r = red(c) / 255f; val g = green(c) / 255f; val b = blue(c) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val l = (max + min) / 2f
        val d = max - min
        if (d == 0f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
    }

    /**
     * The 12 by 10 swatch grid, row-major: the top row runs white to black in
     * greys, then nine rows of hues from dark (top) to light (bottom).
     */
    fun gridColors(): List<List<Int>> {
        val rows = ArrayList<List<Int>>(GRID_ROWS)
        rows += (0 until GRID_COLUMNS).map { i ->
            val v = (255f * (1f - i / (GRID_COLUMNS - 1f))).roundToInt()
            rgb(v, v, v)
        }
        for (row in 0 until GRID_ROWS - 1) {
            val l = 0.12f + row * (0.92f - 0.12f) / (GRID_ROWS - 2)
            rows += GRID_HUES.map { h -> hsl(h, 0.88f, l) }
        }
        return rows
    }

    /**
     * Spectrum panel: hue runs top to bottom, lightness runs white (left)
     * through the full hue (middle) to black (right).
     */
    fun spectrumColor(x: Float, y: Float): Int =
        hsl(y.coerceIn(0f, 1f) * 360f, 1f, 1f - x.coerceIn(0f, 1f))

    /** Marker position (x, y in 0..1) of [c] on the spectrum panel. */
    fun spectrumPosition(c: Int): Pair<Float, Float> {
        val (h, _, l) = toHsl(c).let { Triple(it[0], it[1], it[2]) }
        return (1f - l) to (h / 360f)
    }

    /** Quick colors shown before any saved ones: black, blue, green, yellow, red. */
    val QUICK_COLORS = listOf(0x000000, 0x0A84FF, 0x30D158, 0xFFD60A, 0xFF453A)
}
