package com.aeriotv.android.core.data

/**
 * Single XMLTV `<programme>` entry. Mirrors iOS ParsedEPGProgram
 * (Aerio/Networking/PlaylistParsers.swift around line 263).
 *
 * Times are Unix epoch milliseconds (UTC) for cross-timezone consistency.
 * [channelId] matches an M3UChannel.tvgID for the join.
 */
data class EPGProgramme(
    val channelId: String,
    val title: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
    val category: String,
    /**
     * Dispatcharr's int program id, when the source is the
     * `/api/epg/grid/` endpoint. Drives lazy category enrichment via
     * `getProgramDetail`. Null for XMLTV-parsed programmes (no integer id)
     * and for Dispatcharr's "Dummy EPG" string-id entries.
     */
    val dispatcharrProgramId: Int? = null,
    /**
     * True for a synthesized "channel name" row shown when a channel has no real
     * EPG (Dispatcharr dummy-EPG parity). The guide pins the title so it stays
     * visible while scrolling and hides the (meaningless) time range.
     */
    val isPlaceholder: Boolean = false,
)
