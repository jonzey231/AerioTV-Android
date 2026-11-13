package com.aeriotv.android.core.data

import java.text.NumberFormat
import java.util.Locale

/**
 * Settings > DVR > Maximum (Apple DVRStorageCapRules, 6660eef). The cap is
 * stored as whole MB (`dvr_max_local_storage_mb`, device-local, never
 * synced). Steps are 0.25 GB (owner 2026-11-13), 256 MB each, so a value
 * saved as whole GB (a multiple of 1024) is already on a step: nothing to
 * migrate. Range 1 to 200 GB. Pure, so the unit test pins it.
 */
object DvrStorageCapRules {
    const val STEP_MB = 256
    const val MIN_MB = 1024
    const val MAX_MB = 200 * 1024

    fun clamped(mb: Int): Int = mb.coerceIn(MIN_MB, MAX_MB)

    /** The slider's value in GB, snapped to the nearest 0.25 GB in range, as MB. */
    fun snappedMB(gb: Double): Int {
        if (!gb.isFinite()) return MIN_MB
        return clamped(Math.round(gb * 1024 / STEP_MB).toInt() * STEP_MB)
    }

    /**
     * "10 GB", "10.25 GB", "10.5 GB": no trailing zeros, the locale's
     * decimal separator ("10,25 GB" in German), no grouping, "GB" after a
     * space.
     */
    fun label(mb: Int, locale: Locale = Locale.getDefault()): String {
        val f = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
            isGroupingUsed = false
        }
        return f.format(mb / 1024.0) + " GB"
    }

    /**
     * Android TV, a held Left or Right: the step of the n-th adjustment (0 is
     * the press itself): 0.25 GB for the press and the first 8 repeats, then
     * 1 GB for 12, then 5 GB.
     */
    fun holdStepMB(n: Int): Int = if (n <= 8) STEP_MB else if (n <= 20) 1024 else 5 * 1024

    /** Android TV: the first repeat follows the press after this long, then one every [REPEAT_MS]. */
    const val HOLD_DELAY_MS = 400L
    const val REPEAT_MS = 100L

    /**
     * One adjustment by [step] MB, landing on a multiple of [step] (a 1 GB
     * step from 10.25 GB goes to 11 GB, down to 10 GB), clamped to range.
     */
    fun stepped(mb: Int, step: Int, up: Boolean): Int {
        if (step <= 0) return clamped(mb)
        val next = if (up) (mb / step + 1) * step else ((mb - 1).coerceAtLeast(0) / step) * step
        return clamped(next)
    }
}
