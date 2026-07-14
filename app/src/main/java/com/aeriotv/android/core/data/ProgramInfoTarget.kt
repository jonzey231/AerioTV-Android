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
    /**
     * Dispatcharr's int program id. Drives the lazy `/api/epg/programs/<id>/`
     * category-enrichment fetch in ProgramInfoSheet — when set, the sheet
     * upgrades [category] from the bulk-grid blank string to the real list
     * the moment the user opens the detail. Null for XMLTV-parsed programs
     * and for Dispatcharr's "Dummy EPG" rows.
     */
    val dispatcharrProgramId: Int? = null,
    // EPG badge metadata carried through to the detail sheet.
    val subTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val isNew: Boolean = false,
    val isLiveBroadcast: Boolean = false,
    val isPremiere: Boolean = false,
    val isFinale: Boolean = false,
    val isRepeat: Boolean = false,
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
        dispatcharrProgramId = dispatcharrProgramId,
        subTitle = subTitle,
        season = season,
        episode = episode,
        isNew = isNew,
        isLiveBroadcast = isLiveBroadcast,
        isPremiere = isPremiere,
        isFinale = isFinale,
        isRepeat = isRepeat,
    )
