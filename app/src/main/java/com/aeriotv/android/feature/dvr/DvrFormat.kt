package com.aeriotv.android.feature.dvr

/** DVR text formats, as Apple's DVRFormat (DVRView.swift) writes them. */
internal object DvrFormat {
    /**
     * A recording's length: "45 min", "2 h", "2 h 5 min". Whole minutes,
     * rounded down; under a minute is "1 min", never "0 min"; whole hours
     * drop the "0 min" (Apple DVRFormat.duration, byte for byte).
     */
    fun duration(millis: Long): String {
        val minutes = (millis / 60_000L).toInt()
        if (minutes >= 60) {
            val m = minutes % 60
            return if (m == 0) "${minutes / 60} h" else "${minutes / 60} h $m min"
        }
        return "${maxOf(1, minutes)} min"
    }
}
