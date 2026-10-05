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

    /** True for the entry URL that still needs its 302 resolved. */
    fun isEntryUrl(url: String): Boolean =
        isDispatcharrLive(url) && url.contains(PARAM, ignoreCase = true)

    /** True for /proxy/hls/<token>/index.m3u8 (the resolved session playlist). */
    fun isSessionUrl(url: String): Boolean =
        url.contains(SESSION_MARKER, ignoreCase = true) && url.contains("index.m3u8", ignoreCase = true)

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
                    if (absolute != null && isSessionUrl(absolute)) {
                        Log.i(TAG, "[FORCE-HLS] entry 302 -> $absolute token=${token?.take(8) ?: "none"}")
                        Session(absolute, token)
                    } else {
                        Log.w(TAG, "[FORCE-HLS] entry 302 went to a non-HLS target $absolute")
                        null
                    }
                } else {
                    Log.w(TAG, "[FORCE-HLS] entry answered ${resp.code} with no redirect")
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
        if (!enabled || !isDispatcharrLive(url)) return url
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
