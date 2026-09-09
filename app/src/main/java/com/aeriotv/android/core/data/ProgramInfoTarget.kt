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
    /** DVR extras when the sheet opens from a recording (Apple parity). */
    val recording: RecordingFacts? = null,
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

/**
 * Recording facts shown under the program facts (Apple parity,
 * RecordingFacts.rows): recorded date, window, quality, audio, size,
 * bitrate, format, location, status. Rows without data are omitted.
 */
data class RecordingFacts(
    val recordedOnMillis: Long,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val fileSizeBytes: Long,
    val format: String?,
    val location: String,
    val status: String,
    val videoCodec: String? = null,
    val resolution: String? = null,
    val frameRate: Double? = null,
    val videoBitrateKbps: Double? = null,
    val audioCodec: String? = null,
    val audioChannels: String? = null,
) {
    fun rows(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        out += "Recorded" to date.format(java.util.Date(recordedOnMillis))
        out += "Window" to (time.format(java.util.Date(windowStartMillis)) + " to " + time.format(java.util.Date(windowEndMillis)))
        val quality = listOfNotNull(
            resolution?.takeIf { it.isNotBlank() },
            videoCodec?.takeIf { it.isNotBlank() }?.let(::codecName),
            frameRate?.takeIf { it > 0 }?.let { if (it == Math.rint(it)) "%.0f fps".format(it) else "%.2f fps".format(it) },
        )
        if (quality.isNotEmpty()) out += "Quality" to quality.joinToString(" ")
        val audio = listOfNotNull(audioCodec?.takeIf { it.isNotBlank() }?.let(::codecName), audioChannels?.takeIf { it.isNotBlank() })
        if (audio.isNotEmpty()) out += "Audio" to audio.joinToString(" ")
        if (fileSizeBytes > 0) out += "Size" to when {
            fileSizeBytes >= 1_073_741_824L -> "%.2f GB".format(fileSizeBytes / 1_073_741_824.0)
            else -> "%.1f MB".format(fileSizeBytes / 1_048_576.0)
        }
        val seconds = (windowEndMillis - windowStartMillis) / 1000.0
        val mbps = when {
            (videoBitrateKbps ?: 0.0) > 0 -> videoBitrateKbps!! / 1000
            fileSizeBytes > 0 && seconds > 60 -> fileSizeBytes * 8 / seconds / 1_000_000
            else -> null
        }
        mbps?.let { out += "Bitrate" to (if (it >= 10) "%.0f Mbps".format(it) else "%.1f Mbps".format(it)) }
        format?.takeIf { it.isNotBlank() }?.let { out += "Format" to it.uppercase() }
        out += "Location" to location
        out += "Status" to status
        return out
    }

    companion object {
        fun codecName(raw: String): String = when (raw.lowercase()) {
            "h264", "avc" -> "H.264"
            "hevc", "h265" -> "HEVC"
            "mpeg2video" -> "MPEG-2"
            "aac" -> "AAC"
            "ac3" -> "AC-3"
            "eac3" -> "E-AC-3"
            "mp2" -> "MP2"
            else -> raw.uppercase()
        }
    }
}
