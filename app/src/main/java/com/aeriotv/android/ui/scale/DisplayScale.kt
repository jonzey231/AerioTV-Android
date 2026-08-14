package com.aeriotv.android.ui.scale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * The raw display-scale multiplier, for the few places that must scale dp
 * GEOMETRY (poster grid cells, shelf card widths, the alpha-jump rail) and
 * not just text. Font scaling alone leaves posters the same size at 175%,
 * which reads as "the slider does nothing" on a TV grid; Apple scales the
 * card geometry, so Android must too (Movies & TV B3). Guide-style screens
 * that already multiply their own row geometry keep doing that instead.
 */
val LocalDisplayScale = compositionLocalOf { 1.0f }

/**
 * Apply a user-chosen display-scale multiplier to a Compose subtree by
 * overriding [LocalDensity]. Multiplying `fontScale` (not `density`) keeps
 * pixel-based things (image sizes, surface dimensions) untouched and only
 * grows/shrinks SP-derived measurements — mirroring iOS dynamicTypeSize.
 *
 * Source of truth is [AppPreferences.displayScaleMovies] / `displayScaleLiveTV`,
 * set via the Appearance > Display Scale sliders (0.85 .. 1.25).
 *
 * If the user already has Android-system "Display size" / "Font size" set
 * non-default, this multiplies on top — so a user with system 1.15 and app
 * scale 1.10 ends up at ~1.265 effective scale. That stacking matches iOS
 * where Dynamic Type also scales independently.
 */
@Composable
fun WithDisplayScale(scale: Float, content: @Composable () -> Unit) {
    val outer = LocalDensity.current
    val scaled = remember(outer, scale) {
        Density(density = outer.density, fontScale = outer.fontScale * scale.coerceIn(0.5f, 1.5f))
    }
    CompositionLocalProvider(
        LocalDensity provides scaled,
        LocalDisplayScale provides scale.coerceIn(0.5f, 1.5f),
    ) {
        content()
    }
}
