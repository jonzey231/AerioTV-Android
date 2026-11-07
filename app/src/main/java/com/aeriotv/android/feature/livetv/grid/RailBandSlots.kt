package com.aeriotv.android.feature.livetv.grid

/** Left x of each TV rail band glyph; null when that glyph is not drawn. */
internal data class RailBandSlotLefts(val star: Float?, val radio: Float?, val clock: Float?)

/**
 * TV rail band trailing glyphs, packed from the trailing corner with no
 * reserved empty slots: the clock takes the corner when present, the radio
 * glyph sits one slot before it (or at the corner), and the star packs
 * immediately before whichever of those are present (or at the corner).
 * [cornerLeft] is the left x of the far-trailing slot, [slotW] is icon plus gap.
 */
internal fun railBandSlotLefts(
    cornerLeft: Float,
    slotW: Float,
    favorite: Boolean,
    radio: Boolean,
    catchup: Boolean,
): RailBandSlotLefts {
    var next = cornerLeft
    val clock = if (catchup) next.also { next -= slotW } else null
    val radioLeft = if (radio) next.also { next -= slotW } else null
    val star = if (favorite) next else null
    fun Float?.clamp() = this?.coerceAtLeast(0f)
    return RailBandSlotLefts(star.clamp(), radioLeft.clamp(), clock.clamp())
}
