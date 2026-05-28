package com.aeriotv.android.core.network

import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped, thread-safe cache of the active playlist's API key + the
 * URL prefix(es) we should attach it to. Read synchronously by OkHttp
 * interceptors (Coil's image fetcher) where suspending DAO calls would
 * block the network thread.
 *
 * Audit task #54: protected Dispatcharr logos / posters need
 * `X-API-Key: <key>` (and the equivalent `Authorization: ApiKey <key>`)
 * on every request, mirroring the headers DispatcharrClient sends for
 * JSON API calls. Without this, Coil renders the broken-image
 * placeholder for every channel logo on a Dispatcharr server that has
 * its logo endpoint behind auth.
 *
 * Updated by [com.aeriotv.android.core.data.repository.PlaylistRepository]
 * any time the active playlist changes (save, refresh, switch). Cleared
 * when the user deletes the active playlist or signs out of the source.
 */
@Singleton
class ActivePlaylistCredentials @Inject constructor() {

    /**
     * URL prefixes that match the active Dispatcharr (or other auth-gated)
     * source. Stored without trailing slashes for cheap startsWith() checks.
     * Typically contains the canonical baseUrl + the optional lanUrl from
     * the playlist row, so the cache survives a LAN/WAN switch (Phase 97).
     */
    @Volatile private var prefixes: List<String> = emptyList()
    @Volatile private var key: String? = null

    fun set(prefixes: List<String>, apiKey: String?) {
        this.prefixes = prefixes
            .map { it.trimEnd('/') }
            .filter { it.isNotBlank() }
        this.key = apiKey?.takeIf { it.isNotBlank() }
    }

    fun clear() {
        prefixes = emptyList()
        key = null
    }

    /**
     * The API key to attach as `X-API-Key` for a given image URL, or
     * `null` when the URL doesn't belong to the active auth-gated source
     * (so we don't accidentally leak the key to a third-party logo CDN).
     */
    fun apiKeyFor(url: String): String? {
        val k = key ?: return null
        val prefs = prefixes
        if (prefs.isEmpty()) return null
        // Conservative match: startsWith on the URL minus its query string.
        // A logo URL like ${base}/api/channels/logos/42/cache/ matches
        // ${base}; a third-party tvg-logo URL doesn't and is ignored.
        val needle = url.substringBefore('?')
        return if (prefs.any { needle.startsWith(it) }) k else null
    }
}
