package com.aeriotv.android.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * VOD stream requests sign in with X-API-Key only, never an Authorization
 * header (2026-11-13, the same change as the Apple app).
 *
 * Dispatcharr's VOD proxy keeps the Authorization header of the request that
 * creates a VOD session and sends it to the provider on every upstream fetch:
 * apps/proxy/vod_proxy/multi_worker_connection_manager.py forwards
 * "authorization", "referer", "origin" and "accept" from the client (main
 * lines 1277 to 1282, dev lines 1374 to 1379). It accepts X-API-Key first and
 * forwards it nowhere, so the key goes there and nothing else does. Live,
 * catch-up, recordings and the API keep both headers.
 *
 * The OkHttp side is RedirectAuthRule's per-hop interceptor, so a VOD
 * session's 301 to its session URL is covered too; [headersFor] is the same
 * rule for a header map built for a known URL.
 */
object VodSignIn {

    /** The Xtream Codes VOD stream shape, /movie or /series then user, password and file. */
    private val XTREAM_VOD = Regex("(^|/)(movie|series)/[^/]+/[^/]+/[^/]+$")

    /**
     * A VOD stream path: Dispatcharr's VOD proxy (/proxy/vod/...), or the
     * Xtream Codes movie and series stream paths Dispatcharr also serves. The
     * API's /api/vod/... paths are not streams.
     */
    fun isVodPath(encodedPath: String): Boolean =
        encodedPath.contains("/proxy/vod/") || XTREAM_VOD.containsMatchIn(encodedPath)

    fun isVodUrl(url: String): Boolean = url.trim().toHttpUrlOrNull()?.let { isVodPath(it.encodedPath) } == true

    /** The key in `Authorization: ApiKey <key>`, or null (a Bearer token is not an API key). */
    fun apiKeyOf(authorization: String?): String? {
        val parts = authorization?.trim()?.split(Regex("\\s+"), limit = 2) ?: return null
        if (parts.size != 2 || !parts[0].equals("ApiKey", ignoreCase = true)) return null
        return parts[1].trim().takeIf { it.isNotEmpty() }
    }

    /**
     * [headers] as a request to [url] sends them: for a VOD stream, the key as
     * X-API-Key (taken from `Authorization: ApiKey` when that is all there is)
     * and no Authorization header; any other request as it is.
     */
    fun headersFor(url: String, headers: Map<String, String>): Map<String, String> {
        if (!isVodUrl(url)) return headers
        val auth = headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) } ?: return headers
        val out = LinkedHashMap(headers.filterKeys { !it.equals("Authorization", ignoreCase = true) })
        if (out.keys.none { it.equals("X-API-Key", ignoreCase = true) }) apiKeyOf(auth.value)?.let { out["X-API-Key"] = it }
        return out
    }
}
