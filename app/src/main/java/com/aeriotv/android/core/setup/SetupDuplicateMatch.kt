package com.aeriotv.android.core.setup

import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import java.net.URI
import java.util.Locale

/**
 * Finds a playlist on this device that a received setup would duplicate
 * (Logan 2026-11-07: prompt instead of silently adding a second copy; the
 * iOS receiver uses the same rule).
 *
 * A match is the same source family (Dispatcharr API key and Username &
 * Password count as one), the same normalized server URL, and the same
 * account: the Xtream username, the Dispatcharr API key or username, or for
 * M3U the playlist URL itself (query included).
 */
object SetupDuplicateMatch {

    fun find(
        incomingType: SourceType,
        incomingUrl: String,
        apiKey: String?,
        username: String?,
        existing: List<PlaylistEntity>,
    ): PlaylistEntity? = existing.firstOrNull { matches(incomingType, incomingUrl, apiKey, username, it) }

    fun matches(
        incomingType: SourceType,
        incomingUrl: String,
        apiKey: String?,
        username: String?,
        row: PlaylistEntity,
    ): Boolean {
        val rowType = runCatching { SourceType.valueOf(row.sourceType) }.getOrNull() ?: return false
        if (family(rowType) != family(incomingType)) return false
        return when (family(incomingType)) {
            Family.M3u -> {
                val a = normalizeUrl(incomingUrl, keepQuery = true)
                a != null && a == normalizeUrl(row.urlString, keepQuery = true)
            }
            Family.Xtream -> sameServer(incomingUrl, row.urlString) &&
                same(username, row.username, ignoreCase = false)
            Family.Dispatcharr -> sameServer(incomingUrl, row.urlString) &&
                (same(apiKey, row.apiKey, ignoreCase = false) || same(username, row.username, ignoreCase = true))
        }
    }

    private enum class Family { M3u, Xtream, Dispatcharr }

    private fun family(t: SourceType) = when (t) {
        SourceType.M3uUrl -> Family.M3u
        SourceType.XtreamCodes -> Family.Xtream
        SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass -> Family.Dispatcharr
    }

    private fun same(a: String?, b: String?, ignoreCase: Boolean): Boolean {
        val x = a?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val y = b?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return x.equals(y, ignoreCase = ignoreCase)
    }

    private fun sameServer(a: String, b: String): Boolean {
        val x = normalizeUrl(a, keepQuery = false) ?: return false
        return x == normalizeUrl(b, keepQuery = false)
    }

    /**
     * Scheme and host lowercased, default port made explicit, trailing
     * slashes dropped. Null when the string is not a URL with a host.
     */
    fun normalizeUrl(raw: String, keepQuery: Boolean): String? {
        val trimmed = raw.trim().let { if ("://" in it) it else "http://$it" }
        val u = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = u.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (u.port != -1) u.port else when (scheme) { "https" -> 443; "http" -> 80; else -> -1 }
        val path = u.rawPath.orEmpty().trimEnd('/')
        val query = if (keepQuery) u.rawQuery?.let { "?$it" }.orEmpty() else ""
        return "$scheme://$host:$port$path$query"
    }
}
