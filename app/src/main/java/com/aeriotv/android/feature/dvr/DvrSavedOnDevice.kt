package com.aeriotv.android.feature.dvr

/**
 * "Saved on this device" (playlist delete, Logan 2026-11-12): on-device
 * recordings kept when their playlist was deleted with "Delete, Keep
 * Recordings". They belong to no playlist, show in this section under every
 * playlist, and play from their files only.
 */
object DvrSavedOnDevice {
    const val TITLE = "Saved on this device"
    const val HEADER_KEY = "saved-on-device"

    /** Header marker in a mixed row list. */
    object Header

    /** [visible] with the kept recordings last, under [Header] when there are any. */
    fun rows(visible: List<DvrViewModel.Recording>): List<Any> {
        val (saved, rest) = visible.partition { it.savedOnDevice }
        return if (saved.isEmpty()) rest else rest + Header + saved
    }
}
