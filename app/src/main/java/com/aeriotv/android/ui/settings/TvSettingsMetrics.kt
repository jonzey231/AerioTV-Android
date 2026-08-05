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

    // MARK: Rail row (TVSettingsSplitView.railRow)
    /** tvOS 26, .medium. */
    val railTitleSize = 13.sp
    /** tvOS 18. */
    val railSubtitleSize = 9.sp
    /** Glyph point size. tvOS 24. */
    val railIconGlyph = 12.dp
    /** Icon column width. tvOS 34 (a bare glyph, NOT a filled tile). */
    val railIconWidth = 17.dp
    /** Icon-to-text gap. tvOS 14. */
    val railIconGap = 7.dp
    /** Title-to-subtitle gap. tvOS 2. */
    val railTextSpacing = 1.dp

    // MARK: Line height
    //
    // Material defaults lineHeight to ~1.5x the font size where SwiftUI uses
    // ~1.2x, which inflates every stacked title+subtitle row independently of
    // the font size. These restore the SwiftUI ratio.
    val railTitleLineHeight = 16.sp
    val railSubtitleLineHeight = 11.sp
}
