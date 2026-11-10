// PlaybackHeaders.kt
//
// ONE place that answers "what headers do this playlist's STREAM requests
// carry?" (Logan 2026-09-18).
//
// Before this, the same eight-line recipe -- read the active playlist, check
// the source type is Dispatcharr, take a non-blank apiKey, emit the legacy
// dual `X-API-Key` + `Authorization: ApiKey` pair -- was retyped at every
// playback entry point: each live tune in Navigation.kt, the cast tune, the
// VOD movie and episode routes, catch-up, DVR/recording playback, the
// multiview tiles, the Android Auto browse tree. Adding a header (which is
// exactly what the per-playlist User-Agent needed) meant finding all of them,
// and the per-playlist User-Agent had reached the Dispatcharr REST client only
// -- so Dispatcharr's Stats panel attributed a box's API traffic to it and its
// streams to nobody.
//
// The auth shape here is DELIBERATELY the legacy dual pair, matching what every
// one of those call sites already sent, not [PlaylistEntity.dispatcharrAuthMode]
// (which DispatcharrClient negotiates for REST calls). Nothing about the
// existing headers changes; the only addition is the custom User-Agent.
//
// Apple does the same thing through `ServerConnection.effectiveUserAgent`, which
// PlayerSession bakes into the locked playback header set for AVPlayer, the
// remux loopback proxy, mpv, catch-up and cast alike.

package com.aeriotv.android.core.network

import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.entity.PlaylistEntity

object PlaybackHeaders {

    /**
     * The request headers for a stream served by [playlist].
     *
     * Dispatcharr sources with a stored key get the dual auth pair; every
     * source type gets `User-Agent` when the playlist carries a custom one.
     * Empty for a null playlist, a non-Dispatcharr source with no override, or
     * a Dispatcharr row whose key has not been resolved yet -- the same
     * `emptyMap()` the call sites used to produce.
     */
    fun forPlaylist(playlist: PlaylistEntity?): Map<String, String> {
        if (playlist == null) return emptyMap()
        return buildMap {
            val key = playlist.apiKey?.takeIf { it.isNotBlank() }
            // A key the server already rejected is left off: each stream open
            // would otherwise count against its failed-key throttle.
            if (isDispatcharr(playlist) && key != null && !DispatcharrRequestGate.isKeyRejected(key)) {
                put("X-API-Key", key)
                put("Authorization", "ApiKey $key")
            }
            customUserAgent(playlist)?.let { put("User-Agent", it) }
        }
    }

    /**
     * Same as [forPlaylist], but empty for anything that is not an http(s)
     * URL: a local `file://` DVR capture must play headerless, and several
     * routes play either a local file or a server URL.
     */
    fun forPlaylist(playlist: PlaylistEntity?, url: String): Map<String, String> =
        if (isRemote(url)) forPlaylist(playlist) else emptyMap()

    /** True when [url] is a network stream rather than a local capture. */
    fun isRemote(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)

    /**
     * The playlist's custom User-Agent, or null to leave the caller's own
     * default alone. Same sanitising rule as the REST client's: a header value
     * with a stray newline or a non-Latin-1 character throws inside
     * ktor/okhttp, and a text field can produce either.
     */
    fun customUserAgent(playlist: PlaylistEntity?): String? =
        playlist?.customUserAgent?.trim()
            ?.filter { it.code in 0x20..0x7E }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun isDispatcharr(playlist: PlaylistEntity): Boolean =
        playlist.sourceType == SourceType.DispatcharrApiKey.name ||
            playlist.sourceType == SourceType.DispatcharrUserPass.name
}
