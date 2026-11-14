package com.aeriotv.android.core.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Stream requests carry a playlist's sign-in only to the playlist's current
 * addresses (2026-11-13, the same rule as the Apple app).
 *
 * A channel row, a saved Multiview layout, a cast session, a recording, or a
 * Live Rewind buffer can hold a stream URL from before the playlist moved to
 * another Server URL or Local URL, and a request to it carried the sign-in to
 * an address the playlist no longer uses. That sign-in is the Dispatcharr API
 * key in the headers, or the Xtream Codes username and password in the path.
 * This gate, the outermost interceptor of every stream client, judges every
 * request that carries one of a saved playlist's:
 *
 *  1. On that playlist's current Server URL or Local URL (the shared host and
 *     port rule, [RedirectAuthRule.isServerUrl]): sent as it is.
 *  2. Otherwise, for the active playlist and a path that is the server's own
 *     ([SERVER_PATHS]): rebuilt on the current address (the Local URL when
 *     its last probe reached it, else the Server URL), carrying the sign-in
 *     the playlist uses there now ([rebuildKey]: a held profile's key is
 *     never it), and refused like any stream open when a held Child's hold
 *     covers that address. The old address gets nothing.
 *  3. Otherwise sent without the Dispatcharr key; an Xtream Codes request,
 *     whose credentials are in the path, is not sent at all.
 *
 * Fed by PlaylistRepository from the saved playlists (deleted ones are left
 * out; PlaylistRevocation refuses those). Never logs a URL or a key.
 */
object StreamAddresses {
    const val TAG = "AerioStreamAddress"

    /** Paths only the playlist's server serves: Dispatcharr's proxy, and the Xtream Codes stream paths. */
    val SERVER_PATHS = listOf("/proxy/", "/live/", "/timeshift/", "/movie/", "/series/")

    /** One saved playlist: its current addresses and the sign-ins its stream requests carry. */
    data class Entry(
        val playlistId: String,
        /** The Server URL and the Local URL as saved. */
        val serverUrl: String,
        val lanUrl: String? = null,
        /** Dispatcharr API keys: the playlist's own and its profiles'. */
        val keys: Set<String> = emptySet(),
        /** Xtream Codes username and password, which its stream URLs carry in the path. */
        val xtreamUser: String? = null,
        val xtreamPass: String? = null,
        /** The playlist the app plays from. */
        val active: Boolean = false,
        /** The playlist's own (default profile) key, as stored. */
        val ownKey: String? = null,
    ) {
        val serverUrls: List<String> get() = listOfNotNull(serverUrl, lanUrl).filter { it.isNotBlank() }

        /** True when a request to [url] carries this playlist's Xtream Codes credentials. */
        fun xtreamIn(url: HttpUrl): Boolean {
            val u = xtreamUser?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val p = xtreamPass?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val s = url.pathSegments
            if ((0 until s.size - 1).any { s[it] == u && s[it + 1] == p }) return true
            return url.queryParameter("username") == u && url.queryParameter("password") == p
        }

        /** True when a request to [url] carrying the Dispatcharr key [key] carries this playlist's sign-in. */
        fun signsIn(url: HttpUrl, key: String?): Boolean = (key != null && key in keys) || xtreamIn(url)
    }

    @Volatile private var entries: List<Entry> = emptyList()
    private val loaded = CountDownLatch(1)

    /** The last LAN probe verdict for a Local URL, null when never probed (LanReachability). */
    @Volatile var lanVerdict: (String) -> Boolean? = { null }

    /** Log sink (tests replace it; android.util.Log is not available on the JVM). */
    @Volatile var log: (String) -> Unit = { android.util.Log.i(TAG, it) }

    /** The saved playlists, deleted ones left out. */
    fun update(entries: List<Entry>) {
        this.entries = entries
        loaded.countDown()
    }

    /** True when [url] is on the active playlist's current Server URL or Local URL (the shared host and port rule). */
    fun isActiveServerUrl(url: String): Boolean =
        entries.any { it.active && RedirectAuthRule.isServerUrl(url, it.serverUrls) }

    /** The part of a stream URL that is the server's own, from the first of [SERVER_PATHS] on, with its query. */
    fun serverPath(url: HttpUrl): String? {
        val path = url.encodedPath
        val i = SERVER_PATHS.map { path.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return null
        return path.substring(i) + (url.encodedQuery?.let { "?$it" } ?: "")
    }

    /** What a stream request to [url], carrying the Dispatcharr key [key], becomes. */
    sealed interface Decision {
        data object Send : Decision
        /** Rebuilt on the current address; [key] is the sign-in to carry there (null: none). */
        data class Rebuild(val url: HttpUrl, val key: String?) : Decision
        data object WithoutKey : Decision
        data object Refuse : Decision
    }

    fun decide(url: HttpUrl, key: String?): Decision {
        val owners = entries.filter { it.signsIn(url, key) }
        if (owners.isEmpty()) return Decision.Send
        val asString = url.toString()
        if (owners.any { RedirectAuthRule.isServerUrl(asString, it.serverUrls) }) return Decision.Send
        val active = owners.firstOrNull { it.active }
        val path = serverPath(url)
        if (active != null && path != null) {
            val rebuilt = (currentBase(active).trimEnd('/') + path).toHttpUrlOrNull()
            if (rebuilt != null) return Decision.Rebuild(rebuilt, rebuildKey(active))
        }
        return if (owners.any { it.xtreamIn(url) }) Decision.Refuse else Decision.WithoutKey
    }

    /**
     * The key the playlist signs in with at its current address now, read at
     * request time so a hold that lands at any moment counts: the installed
     * profile's (none for a held Child, ProfileScope.overlayFor), else the
     * playlist's own. A held profile's overlay is never installed, so its key
     * is never this.
     */
    fun rebuildKey(e: Entry): String? {
        val overlay = com.aeriotv.android.core.profiles.ProfileScope.overlayFor(e.playlistId)
        val key = if (overlay != null) overlay.apiKey else e.ownKey
        return key?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun currentBase(e: Entry): String {
        val lan = e.lanUrl?.trim()?.takeIf { it.isNotEmpty() }
        return if (lan != null && lanVerdict(lan) == true) lan else e.serverUrl.trim()
    }

    /** A stream request to an address its playlist no longer uses, with its credentials in the path. */
    class StaleAddressException : java.io.IOException("Stream address no longer used by its playlist")

    val interceptor = Interceptor { chain ->
        // The playlists load from the database at launch; a stream that opens
        // first waits for them (bounded, then judged with what is there).
        if (loaded.count > 0L) {
            runCatching { loaded.await(LOAD_WAIT_MS, TimeUnit.MILLISECONDS) }
        }
        val request = chain.request()
        val key = requestApiKey(request.header("X-API-Key"), request.header("Authorization"))
        when (val d = decide(request.url, key)) {
            Decision.Send -> chain.proceed(request)
            is Decision.Rebuild -> {
                log("[STALE-ADDRESS] stream request to an address its playlist no longer uses: rebuilt on its current address")
                // The current address can be one a held Child's hold covers: refused like any stream open there.
                com.aeriotv.android.core.playback.StreamGate.refuseHeldRoute(d.url.toString())
                val b = request.newBuilder().url(d.url)
                RedirectAuthRule.HEADERS.forEach { b.removeHeader(it) }
                if (key != null && d.key != null) {
                    com.aeriotv.android.core.playback.StreamAuth.authHeaders(d.key, d.url.toString()).forEach { (k, v) -> b.header(k, v) }
                }
                chain.proceed(b.build())
            }
            Decision.WithoutKey -> {
                log("[STALE-ADDRESS] stream request to an address its playlist no longer uses: sent without the sign-in")
                chain.proceed(request.newBuilder().apply { RedirectAuthRule.HEADERS.forEach { removeHeader(it) } }.build())
            }
            Decision.Refuse -> {
                log("[STALE-ADDRESS] stream request to an address its playlist no longer uses: refused (credentials in the URL)")
                throw StaleAddressException()
            }
        }
    }

    /** Puts the gate first on a stream client, so every other interceptor sees the URL it sends. */
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder.apply {
        interceptors().removeAll { it === interceptor }
        interceptors().add(0, interceptor)
    }

    private const val LOAD_WAIT_MS = 3_000L

    /** Tests: forget every playlist. */
    fun resetForTests() {
        entries = emptyList()
        lanVerdict = { null }
        loaded.countDown()
    }
}
