package com.aeriotv.android.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// CUSTOM THEME DERIVATION (shared formula; Apple Design/ThemeManager.swift
// CustomThemePalette implements the same steps, keep the two identical so both
// platforms land on the same hex values):
//
// 1. Parse the base color "RRGGBB" and convert it to HSL: h in degrees
//    [0, 360), s and l in [0, 1]. Let cap(x) = min(s, x).
// 2. Dark rendition:
//      background   = HSL(h, cap(0.55), 0.10)
//      card / sheet = HSL(h, cap(0.50), 0.15)
//      text primary = HSL(h, cap(0.30), 0.94)
// 3. Light rendition:
//      background   = HSL(h, cap(0.45), 0.965)
//      card / sheet = #FFFFFF (as every preset)
//      text ink     = HSL(h, cap(0.40), 0.10)
//    Secondary text, tertiary text, and dividers keep the preset rules
//    (theme tint or ink at the preset opacities), so they follow these values.
// 4. Matching accent: hue ha = (h - 30) mod 360 (the step from AerioTV's
//    navy ground to its cyan accent); saturation sa = s when s < 0.08 (a gray
//    base keeps a gray accent), otherwise clamp(s, 0.65, 0.90).
//      dark accent    = HSL(ha, sa, L), L = 0.60 + 0.02 * k for the smallest
//                       k in 0...15 whose WCAG contrast on the dark
//                       background is at least 4.5
//      dark secondary = HSL(ha, sa, L - 0.12)
//      light accent   = HSL(ha, sa, L), L = 0.40 - 0.02 * k for the smallest
//                       k in 0...12 whose WCAG contrast on the light
//                       background is at least 4.5
//      light secondary= HSL(ha, sa, L - 0.08)
// 5. HSL to RGB uses the standard chroma formula; each channel is
//    floor(v * 255 + 0.5). Contrast is measured on those 8-bit values.
// Mode (Light, Dark, System) and True Black Background apply on top exactly
// as for presets (Theme.kt). Custom Accent Color, when on, replaces the
// matching accent for interactive elements in both modes; secondary text,
// tertiary text, dividers, borders, and section headers keep the matching
// accent (Theme.kt themeTint). Dark surfaces sit at 10 and 15 percent
// lightness (measured 2026-11-06 on the Apple TV: 7 and 12 read as near
// black for every hue; True Black still overrides to pure black).
//
// Reference vectors (Apple and Android must agree):
//   0A1628 -> dark bg 0B1728, card 132239, text EBEFF4, accent 57CEDB / 2BBACA,
//             light bg F2F5FA, ink 0F1724, accent 1B747E / 14555D
//   FF0000 -> dark bg 280B0B, card 391313, accent F53D99, light accent C20A66
data class CustomThemePalette(
    val darkBackground: Int,
    val darkCard: Int,
    val darkText: Int,
    val lightBackground: Int,
    val lightInk: Int,
    val darkAccent: Int,
    val darkAccentSecondary: Int,
    val lightAccent: Int,
    val lightAccentSecondary: Int,
) {
    companion object {
        /** AerioTV navy, so a first pick of Custom looks close to the default. */
        const val DEFAULT_HEX = "0A1628"

        /** "RRGGBB" uppercased, a leading "#" dropped; null unless six hex digits. */
        fun clean(raw: String?): String? {
            val s = raw?.trim()?.removePrefix("#") ?: return null
            if (s.length != 6 || !s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            return s.uppercase()
        }

        fun derive(hex: String?): CustomThemePalette {
            val v = (clean(hex) ?: DEFAULT_HEX).toInt(16)
            val (h, s, _) = rgbToHsl((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
            fun cap(x: Double) = min(s, x)
            val darkBg = hsl(h, cap(0.55), 0.10)
            val lightBg = hsl(h, cap(0.45), 0.965)
            val ha = if (h >= 30) h - 30 else h + 330
            val sa = if (s < 0.08) s else min(0.90, max(0.65, s))
            var dl = 0.60
            for (k in 0..15) {
                dl = 0.60 + 0.02 * k
                if (contrast(hsl(ha, sa, dl), darkBg) >= 4.5) break
            }
            var ll = 0.40
            for (k in 0..12) {
                ll = 0.40 - 0.02 * k
                if (contrast(hsl(ha, sa, ll), lightBg) >= 4.5) break
            }
            return CustomThemePalette(
                darkBackground = darkBg,
                darkCard = hsl(h, cap(0.50), 0.15),
                darkText = hsl(h, cap(0.30), 0.94),
                lightBackground = lightBg,
                lightInk = hsl(h, cap(0.40), 0.10),
                darkAccent = hsl(ha, sa, dl),
                darkAccentSecondary = hsl(ha, sa, max(0.0, dl - 0.12)),
                lightAccent = hsl(ha, sa, ll),
                lightAccentSecondary = hsl(ha, sa, max(0.0, ll - 0.08)),
            )
        }

        fun rgbToHsl(r8: Int, g8: Int, b8: Int): Triple<Double, Double, Double> {
            val r = r8 / 255.0; val g = g8 / 255.0; val b = b8 / 255.0
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val l = (mx + mn) / 2
            val d = mx - mn
            if (d == 0.0) return Triple(0.0, 0.0, l)
            val s = d / (1 - abs(2 * l - 1))
            var h = when (mx) {
                r -> ((g - b) / d) % 6
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            }
            h *= 60
            if (h < 0) h += 360
            return Triple(h, min(1.0, s), l)
        }

        /** HSL to packed 0xRRGGBB. */
        fun hsl(h: Double, s: Double, l: Double): Int {
            val c = (1 - abs(2 * l - 1)) * s
            val hp = h / 60
            val x = c * (1 - abs(hp % 2 - 1))
            val m = l - c / 2
            val (r1, g1, b1) = when {
                hp < 1 -> Triple(c, x, 0.0)
                hp < 2 -> Triple(x, c, 0.0)
                hp < 3 -> Triple(0.0, c, x)
                hp < 4 -> Triple(0.0, x, c)
                hp < 5 -> Triple(x, 0.0, c)
                else -> Triple(c, 0.0, x)
            }
            fun q(v: Double) = floor((v + m) * 255 + 0.5).toInt().coerceIn(0, 255)
            return (q(r1) shl 16) or (q(g1) shl 8) or q(b1)
        }

        fun luminance(c: Int): Double {
            fun ch(v: Int): Double {
                val x = v / 255.0
                return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        }

        fun contrast(a: Int, b: Int): Double {
            val la = luminance(a); val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        fun hexString(c: Int): String = "%06X".format(c and 0xFFFFFF)
    }
}

internal fun rgbColor(c: Int): Color = Color(0xFF000000.toInt() or c)

/**
 * The live Custom theme palette. Snapshot state, so every composable that
 * reads an [AppTheme.Custom] color recomposes when the base color changes.
 * Written from the composition root (MainActivity) when the synced
 * customThemeHex preference emits.
 */
object CustomThemeState {
    var hex: String by mutableStateOf(CustomThemePalette.DEFAULT_HEX)
        private set
    var palette: CustomThemePalette by mutableStateOf(CustomThemePalette.derive(CustomThemePalette.DEFAULT_HEX))
        private set

    fun setBaseHex(raw: String?) {
        val clean = CustomThemePalette.clean(raw) ?: CustomThemePalette.DEFAULT_HEX
        if (clean == hex) return
        hex = clean
        palette = CustomThemePalette.derive(clean)
    }
}
