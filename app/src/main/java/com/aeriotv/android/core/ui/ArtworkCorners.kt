package com.aeriotv.android.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Settings > Appearance > "Rounded corners on logos and artwork".
 *
 * ONE source of truth for the corner rounding applied to channel logos and
 * program artwork. Every surface reads this local rather than carrying its
 * own copy of the preference, so flipping the switch repaints the whole app
 * live (the value is provided once at the composition root in MainActivity).
 *
 * The RULE (Logan, 2026-09-16): when the switch is ON an image is rounded to
 * match the CONTAINER it sits in - the list row card, the guide cell, the
 * in-player info card, the Program Info card. It is never a fixed per-image
 * radius, so a logo and the card around it share one silhouette. An image in
 * a container that is not itself rounded stays square. When the switch is OFF
 * every one of these images is square (0 dp).
 *
 * TWO toggles since 2026-09-16 (Logan), one mechanism:
 *  - "Rounded corners in List view" (default ON, today's behavior) covers the
 *    Live TV list rows and every other card surface on the shared rule: the
 *    in-player info card, the recent channels picker, the mini player, the
 *    cast tile, the multiview picker, Program Info art and the hero banner.
 *  - "Rounded corners in Guide view" (default OFF) covers ONLY the guide rail
 *    logos, at the rail's own small 6 dp radius.
 *
 * Both run through the same corner-alpha tile rule and the same 25 percent
 * cap; they differ only in which surfaces they switch on.
 *
 * Deliberately NOT applied to Movies / TV Shows / DVR poster art: that is a
 * separate surface with its own design.
 */
data class RoundedArtwork(
    /** List rows and every other card surface. Default ON. */
    val list: Boolean = true,
    /** The guide rail logos, and nothing else. Default OFF. */
    val guide: Boolean = false,
    /** The guide's program cells. Default OFF. */
    val programCells: Boolean = false,
)

val LocalRoundedArtwork = staticCompositionLocalOf { RoundedArtwork() }

/**
 * The corner radius an image should use inside a container whose own corner
 * radius is [container]. Returns 0 dp when the user has turned rounding off,
 * and 0 dp for a square container either way.
 */
@Composable
@ReadOnlyComposable
fun artworkCorner(container: Dp): Dp =
    if (LocalRoundedArtwork.current.list) container else 0.dp

/**
 * [artworkCorner] as a Shape, for the common `Modifier.clip(...)` call site.
 */
@Composable
@ReadOnlyComposable
fun artworkShape(container: Dp): Shape = RoundedCornerShape(artworkCorner(container))
