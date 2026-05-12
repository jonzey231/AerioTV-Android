package com.aeriotv.android.core.data

/**
 * Minimal value carrier for everything `ProgramInfoSheet` needs to render a
 * programme detail. Built from an [EPGProgramme] + channel context at the
 * call site (Guide cell tap, List chevron-expanded row tap, channel long-press
 * "Program Info"). Mirrors iOS `ProgramInfoTarget` (ProgramInfoView.swift:20).
 *
 * Stable [id] keys SwiftUI-style identity for ModalBottomSheet recomposition.
 */
data class ProgramInfoTarget(
    val channelName: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val description: String,
    val category: String,
    /**
     * Dispatcharr's int channel id. Required for server-side recording
     * scheduling on Dispatcharr playlists; null for M3U / Xtream sources
     * (whose Record action toasts a "DVR requires Dispatcharr" message).
     */
    val channelDispatcharrId: Int? = null,
) {
    val id: String get() = "$title-$startMillis-$endMillis"
}

fun EPGProgramme.toInfoTarget(
    channelName: String,
    channelDispatcharrId: Int? = null,
): ProgramInfoTarget =
    ProgramInfoTarget(
        channelName = channelName,
        title = title,
        startMillis = startMillis,
        endMillis = endMillis,
        description = description,
        category = category,
        channelDispatcharrId = channelDispatcharrId,
    )
