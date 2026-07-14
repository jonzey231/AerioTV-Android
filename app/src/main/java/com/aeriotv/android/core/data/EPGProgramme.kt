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

    // --- EPG program metadata badges (guide cell / list / info sheet) ---
    // Sources differ (see the per-source matrix): XMLTV (M3U + Xtream xmltv.php)
    // can carry all of these; Dispatcharr's /api/epg/grid/ supplies
    // new/live/premiere/finale/season/episode/sub_title but NOT repeat (no
    // previously-shown field), and never a content rating.

    /** Episode sub-title (the episode name), from `<sub-title>` / `sub_title`. */
    val subTitle: String? = null,
    /** 1-based season number, from `<episode-num>` / Dispatcharr `season`. */
    val season: Int? = null,
    /** 1-based episode number, from `<episode-num>` / Dispatcharr `episode`. */
    val episode: Int? = null,
    /** New episode (XMLTV `<new/>` marker / Dispatcharr `is_new`). */
    val isNew: Boolean = false,
    /**
     * The FEED's live-broadcast flag (XMLTV `<live/>` / Dispatcharr `is_live`).
     * Distinct from the clock-derived "airing now" the guide uses for tinting.
     */
    val isLiveBroadcast: Boolean = false,
    /** Season/series premiere (`<premiere>` / `is_premiere`). */
    val isPremiere: Boolean = false,
    /** Season/series finale (Dispatcharr `is_finale`; no standard XMLTV tag). */
    val isFinale: Boolean = false,
    /** Repeat/re-run (XMLTV `<previously-shown>`; not available from Dispatcharr). */
    val isRepeat: Boolean = false,
)
