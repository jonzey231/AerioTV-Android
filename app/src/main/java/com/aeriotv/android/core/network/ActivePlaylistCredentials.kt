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

    /** Hostnames (lowercased, no port) extracted from [prefixes]. Lets the
     *  SSRF gate treat the user's own server as trusted even when it lives on
     *  a private/LAN IP (192.168.x.x Dispatcharr is the common case). */
    @Volatile private var hosts: Set<String> = emptySet()

    fun set(prefixes: List<String>, apiKey: String?) {
        val cleaned = prefixes
            .map { it.trimEnd('/') }
            .filter { it.isNotBlank() }
        this.prefixes = cleaned
        this.hosts = cleaned
            .mapNotNull { android.net.Uri.parse(it).host?.lowercase() }
            .toSet()
        this.key = apiKey?.takeIf { it.isNotBlank() }
    }

    fun clear() {
        prefixes = emptyList()
        hosts = emptySet()
        key = null
    }

    /** True when [host] (lowercased, no port) is one of the active server's
     *  own hosts, so the SSRF gate trusts it even on a private/LAN IP. */
    fun isActiveServerHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return hosts.contains(h)
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
        // The server's own host and effective port, no https-to-http (the
        // shared sign-in rule), and under the server URL's path. A logo URL
        // like ${base}/api/channels/logos/42/cache/ matches ${base}; a
        // third-party tvg-logo URL doesn't and is ignored. This replaced a
        // plain startsWith, which also matched https://server.example.net
        // for https://server.example, 192.168.1.100 for 192.168.1.10, and
        // another port when the base had none.
        return if (RedirectAuthRule.isServerUrl(url, prefs, withinPath = true)) k else null
    }
}
