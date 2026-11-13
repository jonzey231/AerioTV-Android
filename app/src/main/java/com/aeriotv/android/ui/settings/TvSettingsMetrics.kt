// TvSettingsMetrics.kt
//
// Android mirror of Apple's Features/Settings/Components/SettingsMetrics.swift
// tvOS ladder, so the two TV apps size their Settings text from ONE agreed set
// of numbers instead of each drifting on its own.
//
// THE CONVERSION, and the trap it exists to close: a 1080p panel gives tvOS a
// 1920x1080 POINT canvas and Android a 960x540 DP one. A tvOS point is
// therefore exactly HALF an Android dp at the same physical size, and every
// value below is its Apple counterpart divided by two. Copying Apple's raw
// numbers across renders everything at double size - that is the bug behind
// Logan's "everything just looks larger on the Streamer" (2026-08-05).
//
// Sizes are sp so the platform accessibility font scale still applies; TVs
// normally sit at 1.0, and the app's TV type scale does not apply on top of
// these because they are already absolute.

package com.aeriotv.android.ui.settings

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** tvOS Settings type + layout ladder, halved for the Android TV canvas. */
object TvSettingsMetrics {
    // MARK: Type ladder (SettingsMetrics.swift)
    /** Page title. tvOS 30. */
    val titleSize = 15.sp
    /** Section header. tvOS 26. */
    val sectionHeaderSize = 13.sp
    /** Row title. tvOS 24. */
    val rowTitleSize = 12.sp
    /** Uppercased eyebrow header and row values. tvOS 22. */
    val eyebrowSize = 11.sp
    /** Row subtitle / footnote. tvOS 20. */
    val footnoteSize = 10.sp
    /** A pane row's subtitle (SettingsRow bodySmall). tvOS 22. */
    val rowSubtitleSize = 11.sp
    /** Title-case card header (SettingsSection .compactCard, title3 semibold). tvOS 48. */
    val sectionTitleSize = 24.sp
    /** Its leading inset. tvOS 12. */
    val sectionTitleLeading = 6.dp
    /** Header-to-card gap. tvOS 12. */
    val sectionTitleGap = 6.dp

    // MARK: Pane row icon tile (SettingsIconTile, tvOS)
    /** tvOS 48. */
    val iconTileBox = 24.dp
    /**
     * The tile glyph's box. tvOS draws a 22 pt semibold SF Symbol: its circle
     * glyphs measure 22 pt (11 dp) across on Apple TV, and a Material circle
     * fills 20/24 of its box, so the box is 13.2. In sp so it follows Text
     * Size, as Apple's scaledFont glyph does (the tile itself stays 24 dp).
     */
    val iconTileGlyph = 13.2.sp
    /** tvOS 10. */
    val iconTileCorner = 5.dp
    /** Tile-to-text gap. tvOS 14. */
    val iconTileGap = 7.dp

    // MARK: Rail row (TVSettingsSplitView.railRow)
    /** tvOS 26, .medium. */
    val railTitleSize = 13.sp
    /** tvOS 18. */
    val railSubtitleSize = 9.sp
    /**
     * The rail glyph's box. tvOS draws a 24 pt semibold SF Symbol, whose
     * circle glyphs (Info, DVR) measure 24 pt (12 dp) across on Apple TV; a
     * Material circle fills 20/24 of its box, so the box is 14.4. In sp so it
     * follows Text Size, as Apple's scaledFont icon does.
     */
    val railIconGlyph = 14.4.sp
    /** Glyph size on the other TV navigation rows (Licenses, profiles). */
    val navIconGlyph = 12.dp
    /** Icon column width. tvOS 34 (a bare glyph, NOT a filled tile). */
    val railIconWidth = 17.dp
    /** Icon-to-text gap. tvOS 14. */
    val railIconGap = 7.dp
    /** Title-to-subtitle gap. tvOS 2. */
    val railTextSpacing = 1.dp

    // MARK: Rail geometry (Apple 08c1be8 RailMetrics, halved)
    //
    // The rail fits without scrolling, so it never moves: 12 rows (the
    // GitHub build's count with Updates) x 32 + 11 gaps x 2 = 406 dp, from the
    // resting top at y 98.5 to y 504.5, above the 510 dp bottom safe edge.
    // Apple's 11 rows still fit at Text Size 130% (y 509); larger text can
    // overflow, and then the rail scrolls, clipped at its resting top so rows
    // never draw into the tab bar's band.

    /** Row height (minimum: larger text grows it). tvOS 64. */
    val railRowHeight = 32.dp
    /** Gap between rows. tvOS 4. */
    val railRowGap = 2.dp
    /** Horizontal padding inside a row. tvOS 18. */
    val railRowPaddingH = 9.dp
    /** A row's left edge from the screen's: tvOS 140 (80 safe area + 60 rail leading). */
    val railRowStart = 70.dp
    /** The rail's trailing padding. tvOS 16. */
    val railTrailing = 8.dp
    /** The first row's top from the screen's, level with the detail card's top. tvOS y 197. */
    val railRestingTop = 98.5.dp
    /** Room inside the clip for the 2 dp focus ring. tvOS 2. */
    val railRingRoom = 1.dp
    /** The rail column, from the screen's left edge. tvOS 510 (80 safe area + 430). */
    val railColumnWidth = 255.dp

    // MARK: Detail pane (measured on Apple TV, settings-compare/atvu)
    //
    // Apple TV's detail cards run from x 307.5 to 867.25 dp (tvOS 615 to
    // 1734.5). Android TV pages pad their cards 16 dp inside the pane, so the
    // pane itself is inset by the rest.
    val paneInsetStart = 36.5.dp
    val paneInsetEnd = 76.25.dp
    /** The first card's top lands at y 98.5 dp, level with the rail's first row (tvOS y 197). */
    val paneInsetTop = 4.5.dp

    // MARK: Line height
    //
    // Material defaults lineHeight to ~1.5x the font size where SwiftUI uses
    // ~1.2x, which inflates every stacked title+subtitle row independently of
    // the font size. These restore the SwiftUI ratio.
    // The rail label and subtitle take the font's own line height (Roboto's
    // 1.17 em, as SwiftUI uses SF's), so a row's text measures 27 dp at the
    // default size, as on Apple TV (55 pt, 27.5 dp), and 11 rows fit at 130%
    // as they do on Apple TV. Text Size 130% is not a plain 1.3x on Android: the
    // platform's nonlinear font scaling draws the 13 sp label at 17.2 dp,
    // not Apple's linear 16.9, and the line box rounds UP to whole pixels.
    // At 15.5 sp the label's line came to 42 px (36 dp rows, the 11th ending
    // at y 514.5, past the 510 safe edge); at 15.25 sp it is 41 px (35.5 dp
    // rows, the 11th ends at y 509). At the default size both round to the
    // same 31 px, so default text is unchanged.
    val railTitleLineHeight = 15.25.sp
    val railSubtitleLineHeight = 10.5.sp

    // MARK: Overscan
    //
    // The TV title-safe inset every full-screen Settings surface starts at.
    // 48dp of the 960dp canvas is 5%, the standard TV title-safe margin.
    //
    // This lives here rather than privately in a host because full-screen
    // TAKEOVERS have to match the rail's left edge exactly. A Material
    // `TopAppBar` whose navigation icon is hidden (as it is on TV, where the
    // remote BACK replaces the arrow) collapses its title to the default 16dp
    // inset, which lands the title a third of the way inside the overscan
    // margin - measured at ~32px against the rail's 96px on a 1080p Streamer.
    // `CenterAlignedTopAppBar` does not have the problem because its title is
    // centred, which is why Add More Categories looked right and the Add
    // Playlist wizard did not.
    val overscanStart = 48.dp

    /**
     * Extra start padding a Material `TopAppBar` title needs to sit at exactly
     * [overscanStart].
     *
     * Material already applies a 16dp title inset of its own, so padding a
     * takeover title by the full [overscanStart] overshoots the rail's left
     * edge by that much (measured 128px against the rail's 96px on a 1080p
     * Streamer). Subtract it so the two line up.
     */
    val topAppBarTitleOverscanPadding = overscanStart - 16.dp
}

// MARK: - Settings text styles
//
// One place that decides what a Settings string looks like. On TOUCH these
// return the Material style untouched (phone/tablet are frozen canon); on TV
// they swap in the halved tvOS ladder above, with SwiftUI's ~1.2x line-height
// ratio instead of Material's ~1.5x.
//
// Use these instead of MaterialTheme.typography.* on any Settings surface, so
// the next screen inherits the ladder rather than reinventing it.

/** Page / pane title. tvOS 30. */
@androidx.compose.runtime.Composable
fun settingsTitleStyle(): androidx.compose.ui.text.TextStyle =
    androidx.compose.material3.MaterialTheme.typography.titleLarge.tvSized(
        TvSettingsMetrics.titleSize, 18.sp,
    )

/** Uppercased section header / eyebrow. tvOS 22. */
@androidx.compose.runtime.Composable
fun settingsEyebrowStyle(): androidx.compose.ui.text.TextStyle =
    androidx.compose.material3.MaterialTheme.typography.labelMedium.tvSized(
        TvSettingsMetrics.eyebrowSize, 13.sp,
    )

/** Row title. tvOS 24. */
@androidx.compose.runtime.Composable
fun settingsRowTitleStyle(): androidx.compose.ui.text.TextStyle =
    androidx.compose.material3.MaterialTheme.typography.bodyLarge.tvSized(
        TvSettingsMetrics.rowTitleSize, 15.sp,
    )

/** Row value / secondary line. tvOS 22. */
@androidx.compose.runtime.Composable
fun settingsRowValueStyle(): androidx.compose.ui.text.TextStyle =
    androidx.compose.material3.MaterialTheme.typography.bodyMedium.tvSized(
        TvSettingsMetrics.eyebrowSize, 13.sp,
    )

/** A pane row's subtitle. tvOS 22 (SettingsRow bodySmall). */
@androidx.compose.runtime.Composable
fun settingsRowSubtitleStyle(): androidx.compose.ui.text.TextStyle =
    androidx.compose.material3.MaterialTheme.typography.bodySmall.tvSized(
        TvSettingsMetrics.rowSubtitleSize, 13.5.sp,
    )

/** Title-case card header (Apple tvOS .compactCard). Phones keep the eyebrow. */
@androidx.compose.runtime.Composable
fun settingsSectionTitleStyle(): androidx.compose.ui.text.TextStyle =
    androidx.compose.material3.MaterialTheme.typography.titleLarge.tvSized(
        TvSettingsMetrics.sectionTitleSize, 29.sp,
    )

/** A pane row's title weight: regular on TV (tvOS bodyMedium), medium on touch. */
@androidx.compose.runtime.Composable
fun settingsRowTitleWeight(): androidx.compose.ui.text.font.FontWeight =
    if (rememberIsTvDevice()) androidx.compose.ui.text.font.FontWeight.Normal
    else androidx.compose.ui.text.font.FontWeight.Medium

/** Row subtitle / footer / footnote. tvOS 20. */
@androidx.compose.runtime.Composable
fun settingsFootnoteStyle(): androidx.compose.ui.text.TextStyle =
    androidx.compose.material3.MaterialTheme.typography.bodySmall.tvSized(
        TvSettingsMetrics.footnoteSize, 12.sp,
    )

@androidx.compose.runtime.Composable
private fun androidx.compose.ui.text.TextStyle.tvSized(
    size: androidx.compose.ui.unit.TextUnit,
    line: androidx.compose.ui.unit.TextUnit,
): androidx.compose.ui.text.TextStyle =
    if (rememberIsTvDevice()) copy(fontSize = size, lineHeight = line) else this
