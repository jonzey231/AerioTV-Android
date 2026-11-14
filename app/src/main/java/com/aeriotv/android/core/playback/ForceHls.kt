package com.aeriotv.android.core.playback

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Developer "Force HLS" switch (Settings > Developer). When on, Dispatcharr
 * Direct Connect live channels (/proxy/ts/stream/<uuid>) are requested with
 * output_format=hls; the server answers 302 to /proxy/hls/<channel>/<client>/
 * index.m3u8 and Media3 plays that playlist natively. Xtream, M3U, catch-up
 * (/timeshift/) and DVR URLs never match the path and are left untouched.
 *
 * [enabled] mirrors AppPreferences.developerForceHls (collected by
 * AerioExoPlayerHolder), so a flip applies on the next tune with no restart.
 */
object ForceHls {
    const val TAG = "AerioTV"
    private const val LIVE_MARKER = "/proxy/ts/stream/"
    private const val PARAM = "output_format=hls"

    @Volatile var enabled: Boolean = false

    /** Hosts (lowercase host:port) whose plain live URL answers with an HLS
     *  playlist (Dispatcharr proxy output set to HLS). Seeded from and
     *  persisted to AppPreferences.serverHlsHosts by AerioExoPlayerHolder. */
    private val serverHlsHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Bumped on every learn/forget so Compose readers (Multiview tiles) re-derive. */
    val serverHlsVersion = androidx.compose.runtime.mutableIntStateOf(0)

    /** Called with the full learned set after it changes (persistence hook). */
    @Volatile var onServerHlsChanged: (Set<String>) -> Unit = {}

    fun hostKey(url: String): String? =
        url.toHttpUrlOrNull()?.let { "${it.host.lowercase()}:${it.port}" }

    fun seedServerHlsHosts(hosts: Set<String>) {
        serverHlsHosts.clear()
        serverHlsHosts.addAll(hosts.map { it.lowercase() })
        if (hosts.isNotEmpty()) Log.i(TAG, "[FORCE-HLS] server HLS hosts seeded: ${hosts.size}")
    }

    fun isServerHlsHost(url: String): Boolean = hostKey(url)?.let { it in serverHlsHosts } == true

    /** The plain live URL [entryUrl] redirected to the HLS session [playlistUrl]. */
    fun noteServerHls(entryUrl: String, playlistUrl: String) {
        val key = hostKey(entryUrl) ?: return
        Log.i(TAG, "[FORCE-HLS] server answers HLS for $key; live tunes use the HLS session path")
        if (serverHlsHosts.add(key)) {
            serverHlsVersion.intValue++
            onServerHlsChanged(serverHlsHosts.toSet())
        }
    }

    private fun forgetServerHls(url: String) {
        val key = hostKey(url) ?: return
        if (serverHlsHosts.remove(key)) {
            Log.i(TAG, "[FORCE-HLS] $key answers TS again; forgetting server HLS")
            serverHlsVersion.intValue++
            onServerHlsChanged(serverHlsHosts.toSet())
        }
    }

    /** Session tokens seen on 3xx answers, keyed by the resolved Location.
     *  Lets the holder end the ghost session the first (redirected) TS open
     *  minted before re-tuning through the HLS path. */
    private val redirectTokens = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** OkHttp NETWORK interceptor: records X-Dispatcharr-Session-Token from redirects. */
    val redirectTokenInterceptor = okhttp3.Interceptor { chain ->
        val resp = chain.proceed(chain.request())
        if (resp.code in 300..399) {
            val token = resp.header(TOKEN_HEADER)
            val loc = resp.header("Location")?.let { resp.request.url.resolve(it)?.toString() }
            if (token != null && loc != null && isSessionUrl(loc)) {
                if (redirectTokens.size > 32) redirectTokens.clear()
                redirectTokens[loc] = token
            }
        }
        resp
    }

    /** Token captured for [playlistUrl] by [redirectTokenInterceptor] (consumed). */
    fun tokenFor(playlistUrl: String): String? = redirectTokens.remove(playlistUrl)

    /** Walks the cause chain for a [ServerHlsRedirectException]. */
    fun findServerHlsRedirect(t: Throwable?): ServerHlsRedirectException? =
        generateSequence(t) { it.cause }.take(12).firstOrNull { it is ServerHlsRedirectException }
            as ServerHlsRedirectException?

    /** Default live offset target for a forced HLS tune. */
    const val DEFAULT_TARGET_OFFSET_MS = 12_000

    /** Per-channel target/learned live offset, installed by AerioExoPlayerHolder
     *  so Multiview tiles use the same value as the main player. */
    @Volatile var liveOffsetLookup: (String?) -> Int = { DEFAULT_TARGET_OFFSET_MS }

    /** Raises the learned live offset for a channel by a stall's worst feed
     *  gap (same rule as the main player); installed by AerioExoPlayerHolder. */
    @Volatile var learnLiveOffset: (String, Long) -> Unit = { _, _ -> }

    /** The holder's channel id ("disp:<uuid>") for a Dispatcharr live URL. */
    fun channelKeyFor(url: String): String? {
        val i = url.indexOf(LIVE_MARKER, ignoreCase = true)
        if (i < 0) return null
        val uuid = url.substring(i + LIVE_MARKER.length).substringBefore('?').substringBefore('/')
        return if (uuid.isBlank()) null else "disp:$uuid"
    }

    /** True for a Dispatcharr Direct Connect live stream URL. */
    fun isDispatcharrLive(url: String): Boolean = url.contains(LIVE_MARKER, ignoreCase = true)

    /** True when [url] is a Dispatcharr live URL already asking for HLS, or
     *  the token playlist the entry URL redirected to. */
    fun isForcedHlsUrl(url: String): Boolean =
        (isDispatcharrLive(url) && url.contains(PARAM, ignoreCase = true)) || isSessionUrl(url)

    // Channels whose HLS entry turned out not to be HLS (2026-10-18: a
    // Redirect stream profile 302s the entry straight to the provider's raw
    // .ts). Session memory only: the next tune of that channel goes straight
    // to the plain TS path.
    private val notHlsChannels = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Records that [url]'s channel entry is not HLS and logs the fallback. */
    fun markNotHls(url: String, reason: String) {
        val key = channelKeyFor(url) ?: return
        notHlsChannels.add(key)
        Log.w(TAG, "[FORCE-HLS] entry is not HLS ($reason); falling back to TS for this tune")
    }

    fun isNotHls(url: String): Boolean = channelKeyFor(url)?.let { it in notHlsChannels } == true

    /** [url] without output_format=hls (the plain TS live URL). */
    fun stripHls(url: String): String {
        val fragment = url.substringAfter('#', "")
        val base = url.substringBefore('#')
        val path = base.substringBefore('?')
        val query = base.substringAfter('?', "")
            .split('&').filter { it.isNotEmpty() && !it.equals(PARAM, ignoreCase = true) }
            .joinToString("&")
        return path + (if (query.isNotEmpty()) "?$query" else "") + (if (fragment.isNotEmpty()) "#$fragment" else "")
    }

    /** True for the entry URL that still needs its 302 resolved. */
    fun isEntryUrl(url: String): Boolean =
        isDispatcharrLive(url) && url.contains(PARAM, ignoreCase = true)

    /** True for /proxy/hls/<token>/index.m3u8 (the resolved session playlist). */
    fun isSessionUrl(url: String): Boolean =
        url.contains(SESSION_MARKER, ignoreCase = true) && url.contains("index.m3u8", ignoreCase = true)

    /**
     * True when [entryUrl] redirected to a session playlist the player may
     * adopt with the server's sign-in headers: same host, no https-to-http
     * downgrade ([RedirectAuthRule]). A session-shaped URL on another host is
     * a provider's (a Redirect stream profile), never the server's own.
     */
    fun isServerSession(entryUrl: String, landedUrl: String): Boolean =
        isSessionUrl(landedUrl) &&
            com.aeriotv.android.core.network.RedirectAuthRule.keepsSignIn(entryUrl, landedUrl)

    private const val SESSION_MARKER = "/proxy/hls/"
    const val TOKEN_HEADER = "X-Dispatcharr-Session-Token"

    /** A resolved server session: the token playlist and its opaque token. */
    data class Session(val playlistUrl: String, val token: String?)

    private val resolveClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            // The session resolve is a stream open too (StreamGate).
            .addInterceptor(StreamGate.interceptor)
            .build()
    }

    /**
     * Resolve the entry 302 ONCE (blocking; call on IO). Every GET of the
     * entry URL mints a new server client, and Media3 reloads a media
     * playlist from the URL it was given every target duration, so the
     * player must be handed the token playlist, not the entry URL.
     */
    fun resolveSession(entryUrl: String, headers: Map<String, String>): Session? {
        val req = okhttp3.Request.Builder().url(entryUrl).get().apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        return try {
            resolveClient.newCall(req).execute().use { resp ->
                val location = resp.header("Location")
                val token = resp.header(TOKEN_HEADER)
                if (resp.code in 300..399 && location != null) {
                    val absolute = resp.request.url.resolve(location)?.toString()
                    // The session is played and ended with the server's sign-in
                    // headers, so only one on the server's own host is adopted.
                    if (absolute != null && isServerSession(entryUrl, absolute)) {
                        Log.i(TAG, "[FORCE-HLS] entry 302 -> $absolute token=${token?.take(8) ?: "none"}")
                        Session(absolute, token)
                    } else if (absolute != null && isSessionUrl(absolute)) {
                        // A provider's playlist (Redirect profile): the plain TS
                        // path follows it without the headers (GH #159 sniff).
                        markNotHls(entryUrl, "302 to a session playlist on another host")
                        null
                    } else {
                        Log.w(TAG, "[FORCE-HLS] entry 302 went to a non-HLS target $absolute")
                        if (absolute != null) {
                            val path = absolute.substringBefore('?').lowercase()
                            if (path.endsWith(".ts") || !path.endsWith(".m3u8")) {
                                markNotHls(entryUrl, "302 to non-HLS target ${path.substringAfterLast('/')}")
                            }
                        }
                        null
                    }
                } else {
                    Log.w(TAG, "[FORCE-HLS] entry answered ${resp.code} with no redirect")
                    if (resp.code in 200..299 && !enabled) forgetServerHls(entryUrl)
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[FORCE-HLS] entry request failed: ${e.message}")
            null
        }
    }

    /** DELETE /api/proxy/hls/sessions/<token>/ so the server drops the client now. */
    fun endSession(session: Session, headers: Map<String, String>) {
        val token = session.token ?: run {
            Log.i(TAG, "[FORCE-HLS] stop: no session token captured, server will ghost-reap this client")
            return
        }
        val base = session.playlistUrl.toHttpUrlOrNull() ?: return
        val url = base.newBuilder().encodedPath("/api/proxy/hls/sessions/$token/").query(null).build()
        val req = okhttp3.Request.Builder().url(url).delete().apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        try {
            resolveClient.newCall(req).execute().use { resp ->
                Log.i(TAG, "[FORCE-HLS] session end token=${token.take(8)}... status=${resp.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[FORCE-HLS] session end failed: ${e.message}")
        }
    }


    /**
     * Append output_format=hls to a Dispatcharr live URL when the switch is
     * on. Existing params are kept; the param is never added twice. Any other
     * URL (or switch off) comes back unchanged.
     */
    fun apply(url: String, where: String): String {
        if (!isDispatcharrLive(url)) return url
        if (isNotHls(url)) {
            Log.i(TAG, "[FORCE-HLS] $where: channel entry is not HLS this session; plain TS")
            return if (url.contains(PARAM, ignoreCase = true)) stripHls(url) else url
        }
        if (!enabled && !isServerHlsHost(url)) return url
        if (url.contains("output_format=", ignoreCase = true)) {
            Log.i(TAG, "[FORCE-HLS] $where url already carries output_format, unchanged: $url")
            return url
        }
        val fragment = url.substringAfter('#', "")
        val base = url.substringBefore('#')
        val sep = if (base.contains('?')) "&" else "?"
        val out = base + sep + PARAM + if (fragment.isNotEmpty()) "#$fragment" else ""
        Log.i(TAG, "[FORCE-HLS] $where final url: $out")
        return out
    }
}

/** The plain Dispatcharr live URL answered with an HLS session playlist. */
class ServerHlsRedirectException(val entryUrl: String, val playlistUrl: String) :
    java.io.IOException("Dispatcharr live URL redirected to an HLS playlist")

/**
 * Detects a plain (TS) Dispatcharr live open that the server redirected to its
 * HLS session playlist (proxy output set to HLS). Learns the host and fails
 * the open with [ServerHlsRedirectException] so the player re-tunes through
 * the HLS session path instead of feeding a playlist to the TS extractor.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ServerHlsGuard(
    private val upstream: androidx.media3.datasource.DataSource,
) : androidx.media3.datasource.DataSource by upstream {
    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        val len = upstream.open(dataSpec)
        val req = dataSpec.uri.toString()
        if (ForceHls.isDispatcharrLive(req) && !ForceHls.isForcedHlsUrl(req)) {
            val landed = upstream.uri?.toString()
            // Adopted sessions play with the server's sign-in headers: never
            // one on another host (RedirectAuthRule).
            if (landed != null && ForceHls.isServerSession(req, landed)) {
                ForceHls.noteServerHls(req, landed)
                try { upstream.close() } catch (_: Exception) {}
                throw ServerHlsRedirectException(req, landed)
            }
        }
        return len
    }

    class Factory(
        private val upstream: androidx.media3.datasource.DataSource.Factory,
    ) : androidx.media3.datasource.DataSource.Factory {
        override fun createDataSource(): androidx.media3.datasource.DataSource =
            ServerHlsGuard(upstream.createDataSource())
    }
}

/**
 * Developer Force HLS, Multiview: one server session per tile. Media3 reloads
 * a live playlist from the URI it was handed, so a tile given the entry URL
 * would follow the 302 (and mint a new server client) on every reload. The
 * wrapped factory resolves the entry URL ONCE (on the loader thread, which is
 * off the main thread) and rewrites every later request for it to the token
 * playlist. [end] DELETEs the session when the tile goes away.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ForceHlsTileSession(
    private val label: String,
    private val headers: Map<String, String>,
) {
    private val lock = Any()
    private var entry: String? = null
    private var session: ForceHls.Session? = null

    fun wrap(upstream: androidx.media3.datasource.DataSource.Factory): androidx.media3.datasource.DataSource.Factory =
        androidx.media3.datasource.ResolvingDataSource.Factory(upstream) { spec ->
            val u = spec.uri.toString()
            if (!ForceHls.isEntryUrl(u)) return@Factory spec
            val s = sessionFor(u) ?: return@Factory spec
            spec.withUri(android.net.Uri.parse(s.playlistUrl))
        }

    private fun sessionFor(u: String): ForceHls.Session? {
        var stale: ForceHls.Session? = null
        val resolved = synchronized(lock) {
            if (entry == u && session != null) return session
            if (entry != u) {
                stale = session
                session = null
            }
            val s = ForceHls.resolveSession(u, headers)
            entry = u
            session = s
            if (s != null) {
                Log.i(ForceHls.TAG, "[FORCE-HLS] tile $label session token=${s.token?.take(8) ?: "none"}")
            } else {
                Log.w(ForceHls.TAG, "[FORCE-HLS] tile $label could not resolve the entry redirect; using the entry URL")
            }
            s
        }
        stale?.let { old ->
            Log.i(ForceHls.TAG, "[FORCE-HLS] tile $label channel changed; ending old session")
            ForceHls.endSession(old, headers)
        }
        return resolved
    }

    /** Adopts the session a redirected plain open already minted for [entryUrl]
     *  (the HLS-rewritten entry), ending any previous session off the main thread. */
    fun adopt(entryUrl: String, s: ForceHls.Session) {
        val stale = synchronized(lock) {
            val old = session
            entry = entryUrl
            session = s
            old?.takeIf { it.playlistUrl != s.playlistUrl }
        }
        Log.i(ForceHls.TAG, "[FORCE-HLS] tile $label adopted the redirect's session token=${s.token?.take(8) ?: "none"}")
        stale?.let { old -> Thread({ ForceHls.endSession(old, headers) }, "ForceHlsTileEnd").start() }
    }

    /** Drops the cached session (DELETE off the main thread); the next load re-resolves. */
    fun end(reason: String) {
        val s = synchronized(lock) {
            val cur = session
            session = null
            entry = null
            cur
        } ?: return
        Log.i(ForceHls.TAG, "[FORCE-HLS] tile $label ending session ($reason)")
        Thread({ ForceHls.endSession(s, headers) }, "ForceHlsTileEnd").start()
    }
}

/**
 * Developer Force HLS: warm join + trough-based slow ramp for one player.
 * Dispatcharr's playlist has no PROGRAM-DATE-TIME, so Media3 never steers to
 * the live offset target. At the first READY: if the window already holds
 * target + 6 s (another viewer had the channel up), seek once to the target
 * and stop. Otherwise play at 0.92x (pitch kept) until the 6 s rolling minimum
 * of (window end minus position) reaches the target. Call [tick] about once a
 * second, [reset] on every prime and stop.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ForceHlsCushion(private val label: String) {
    /** 0 = idle, 1 = ramping, 2 = holding (watching for a slip). */
    @Volatile private var state = 0
    private var checkAtMs = 0L
    private var startedAtMs = 0L
    private var rearms = 0
    private val samples = ArrayDeque<Pair<Long, Long>>()

    private fun tag(): String = if (label == "main") "" else "tile $label "

    fun reset(p: androidx.media3.common.Player?, reason: String) {
        if (state == 1 && p != null && p.playbackParameters.speed != 1.0f) {
            p.playbackParameters = androidx.media3.common.PlaybackParameters(1.0f, 1.0f)
            Log.i(ForceHls.TAG, "[FORCE-HLS] ${tag()}cushion reset on $reason, speed 1.0")
        }
        state = 0
        checkAtMs = 0L
        startedAtMs = 0L
        rearms = 0
        samples.clear()
    }

    private fun ramp(p: androidx.media3.common.Player, now: Long) {
        state = 1
        startedAtMs = now
        samples.clear()
        p.playbackParameters = androidx.media3.common.PlaybackParameters(SPEED, 1.0f)
    }

    fun tick(p: androidx.media3.common.Player, forced: Boolean, target: Long, now: Long) {
        if (!forced) {
            if (state == 1) reset(p, "non-forced session")
            return
        }
        if (p.playbackState != androidx.media3.common.Player.STATE_READY && state == 0) return
        val tl = p.currentTimeline
        if (tl.isEmpty) return
        val w = tl.getWindow(p.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
        if (!w.isLive() || w.durationMs == androidx.media3.common.C.TIME_UNSET) return
        val have = (w.durationMs - p.currentPosition).coerceAtLeast(0L)
        if (state == 0 && samples.isEmpty() && w.durationMs >= target + WARM_JOIN_MARGIN_MS) {
            Log.i(ForceHls.TAG, "[FORCE-HLS] ${tag()}warm join seek to edge-$target ms (window ${w.durationMs} ms)")
            p.seekTo(p.currentMediaItemIndex, w.durationMs - target)
            state = 2
            checkAtMs = now + WINDOW_MS
            return
        }
        samples.addLast(now to have)
        while (samples.isNotEmpty() && now - samples.first().first > WINDOW_MS) samples.removeFirst()
        if (now < checkAtMs) return
        checkAtMs = now + CHECK_MS
        val minHave = samples.minOf { it.second }
        val fullWindow = now - samples.first().first >= WINDOW_MS - 1_000L
        when (state) {
            0 -> {
                if (minHave >= target) {
                    if (!fullWindow) return
                    Log.i(ForceHls.TAG, "[FORCE-HLS] ${tag()}cushion reached min $minHave ms (now $have ms), speed 1.0")
                    state = 2
                    return
                }
                ramp(p, now)
                Log.i(
                    ForceHls.TAG,
                    "[FORCE-HLS] ${tag()}building cushion at ${SPEED}x (have min $minHave ms, now $have ms, target $target ms)",
                )
            }
            1 -> {
                // A re-armed ramp ends at target + margin so it holds a cushion.
                val endAt = if (rearms > 0) target + REARM_MARGIN_MS else target
                if (fullWindow && minHave >= endAt) {
                    p.playbackParameters = androidx.media3.common.PlaybackParameters(1.0f, 1.0f)
                    Log.i(
                        ForceHls.TAG,
                        "[FORCE-HLS] ${tag()}cushion reached min $minHave ms (now $have ms) after " +
                            "${now - startedAtMs} ms, speed 1.0",
                    )
                    state = 2
                }
            }
            2 -> {
                if (!fullWindow || minHave >= target - SLIP_MS || rearms >= MAX_REARMS) return
                rearms++
                Log.i(ForceHls.TAG, "[FORCE-HLS] ${tag()}cushion slipped to min $minHave ms, ramping again ($rearms/$MAX_REARMS)")
                ramp(p, now)
            }
        }
    }

    companion object {
        const val SPEED = 0.92f
        private const val CHECK_MS = 2_000L
        private const val WINDOW_MS = 6_000L
        private const val WARM_JOIN_MARGIN_MS = 6_000L
        private const val SLIP_MS = 2_000L
        private const val REARM_MARGIN_MS = 1_000L
        private const val MAX_REARMS = 5
    }
}
