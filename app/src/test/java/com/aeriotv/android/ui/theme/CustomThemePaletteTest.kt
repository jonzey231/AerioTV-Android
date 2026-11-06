package com.aeriotv.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** Reference vectors computed by the Apple CustomThemePalette; both must agree. */
class CustomThemePaletteTest {
    private fun hexes(p: CustomThemePalette) = listOf(
        p.darkBackground, p.darkCard, p.darkText, p.lightBackground, p.lightInk,
        p.darkAccent, p.darkAccentSecondary, p.lightAccent, p.lightAccentSecondary,
    ).map { CustomThemePalette.hexString(it) }

    @Test fun navyMatchesApple() = assertEquals(
        listOf("0B1728", "132239", "EBEFF4", "F2F5FA", "0F1724", "57CEDB", "2BBACA", "1B747E", "14555D"),
        hexes(CustomThemePalette.derive("0A1628")),
    )

    @Test fun redMatchesApple() = assertEquals(
        listOf("280B0B", "391313", "F4EBEB", "FAF2F2", "240F0F", "F53D99", "E90C7A", "C20A66", "9B0852"),
        hexes(CustomThemePalette.derive("#ff0000")),
    )

    @Test fun grayStaysGray() = assertEquals(
        listOf("1A1A1A", "262626", "F0F0F0", "F6F6F6", "1A1A1A", "999999", "7A7A7A", "666666", "525252"),
        hexes(CustomThemePalette.derive("808080")),
    )

    @Test fun greenMatchesApple() = assertEquals(
        listOf("0B2816", "133921", "EBF4EE", "F2FAF5", "0F2417", "64E151", "3BD124", "258217", "1B6011"),
        hexes(CustomThemePalette.derive("22C55E")),
    )
}
