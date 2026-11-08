package com.aeriotv.android.core.data.repository

/**
 * The one per-channel history rule every EPG writer and the catch-up reach
 * prune share (Apple EPGHistoryWindow parity, 2026-11-07).
 *
 * A channel keeps aired rows back to its own catch-up days (24 h when it has
 * none); a key no cached channel claims keeps them back to the widest reach
 * on the playlist (the prune's orphan bound). A writer that skips rows ending
 * before [floor] never writes what the prune deletes again, and the prune
 * deletes exactly the rows ending before [floor].
 *
 * Built only from a channel snapshot that exists: an unknown reach (no
 * channels loaded yet) has no window at all, so nothing is filtered or
 * pruned and history bounds stay at the full retention, never 24 h.
 *
 * Pure: no Room, no clock (EpgDayDiffTest).
 */
internal class EpgHistoryWindow(
    /** Catch-up days per canonical guide key, already clamped to 0..30. */
    val daysByKey: Map<String, Int>,
    val nowMillis: Long,
) {
    /** Widest reach on the playlist, in days, at least 1. */
    val maxReachDays: Int = maxOf(1, daysByKey.values.maxOrNull() ?: 0)

    fun floor(channelId: String): Long {
        val days = daysByKey[channelId]?.let { maxOf(it, 1) } ?: maxReachDays
        return nowMillis - days * DAY_MS
    }

    /** True when the row is inside the window and may be written. */
    fun keeps(channelId: String, endMillis: Long): Boolean = endMillis >= floor(channelId)

    companion object {
        const val DAY_MS = 86_400_000L
    }
}
