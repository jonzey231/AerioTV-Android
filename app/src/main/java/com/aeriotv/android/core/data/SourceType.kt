package com.aeriotv.android.core.data

/**
 * Where the channel list comes from. Mirrors iOS ServerType (Aerio/Models/Models.swift:111+)
 * with the same three categories. Each implies a different fetch + auth flow:
 *
 *  - [M3uUrl]: user pastes a raw M3U/M3U8 URL plus an optional XMLTV URL.
 *    No auth, no derivation, the URLs go to the fetcher as-is.
 *  - [DispatcharrApiKey]: user supplies a server base URL and an admin API key
 *    from Dispatcharr's Users -> Edit -> API & XC tab. M3U/EPG are derived
 *    as `${base}/output/m3u` and `${base}/output/epg` with `X-API-Key` header.
 *  - [DispatcharrUserPass]: server base URL + admin username/password. Login
 *    flow exchanges them for a JWT pair, then runs API calls with Bearer auth.
 *    Wired in Phase 4b.
 *  - [XtreamCodes]: server base URL + Xtream username/password. Live channels
 *    come from `/get.php?type=m3u_plus&username=&password=` (parsed by
 *    M3UParser); EPG from `/xmltv.php` (or a user-supplied XMLTV override).
 *    The full fetch/EPG/refresh pipeline lives in PlaylistRepository.
 */
enum class SourceType(val displayName: String, val isImplemented: Boolean) {
    M3uUrl("M3U URL", true),
    DispatcharrApiKey("Dispatcharr (API Key)", true),
    DispatcharrUserPass("Dispatcharr (Username & Password)", true),
    XtreamCodes("Xtream Codes", true),
    ;

    /** True for source types that carry VOD (movies + series). Mirrors iOS
     *  ServerType.supportsVOD: Dispatcharr (any auth) and Xtream Codes have
     *  movie/series APIs; raw M3U is live-only. The On Demand toggle in
     *  ConfigureSourceScreen / EditPlaylistScreen surfaces only when this
     *  is true (no point asking M3U users whether to fetch VOD they don't
     *  have). Also used by OnDemandViewModel to short-circuit the probe
     *  for M3U playlists. */
    val supportsVOD: Boolean
        get() = this == DispatcharrApiKey || this == DispatcharrUserPass || this == XtreamCodes
}
