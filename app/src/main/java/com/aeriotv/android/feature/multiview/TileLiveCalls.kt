package com.aeriotv.android.feature.multiview

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private const val TAG = "TileLiveCalls"

/**
 * Live tile connections that can be CLOSED when a tile swaps channel, re-primes
 * or is released. ExoPlayer cancels the loader on a new source / release, but
 * the loading thread sits in a blocking socket read that the cancel flag does
 * not break, so Dispatcharr kept the old channel's connection open until the
 * next burst (~10 s) or the read timeout. Same discipline as the live holder
 * (AerioExoPlayerHolder LiveCallTracker): every live source gets its own
 * tracked Call.Factory, and a superseded source's Calls are cancelled once
 * the player has let go of its loaders. VOD/DVR tiles are not tracked (their
 * loaders are not parked in a read, and they keep the file://-capable path).
 */
@OptIn(UnstableApi::class)
internal class TileLiveCalls {

    /** Call.Factory for ONE live media source; remembers its recent Calls. A
     *  Call created after [cancelAll] (a late retry of the dying source) is
     *  cancelled on creation. */
    class Tracker(private val client: OkHttpClient) : Call.Factory {
        private val calls = ArrayDeque<Call>()
        private var cancelled = false

        override fun newCall(request: Request): Call {
            val call = client.newCall(request)
            synchronized(this) {
                if (cancelled) {
                    call.cancel()
                } else {
                    // One connection at a time per source; a short history is enough.
                    calls.addLast(call)
                    while (calls.size > 4) calls.removeFirst()
                }
            }
            return call
        }

        fun cancelAll() {
            val snapshot = synchronized(this) {
                cancelled = true
                calls.toList().also { calls.clear() }
            }
            snapshot.forEach { it.cancel() }
        }
    }

    /** Live sources built for this tile whose connections are not retired. */
    private val trackers = CopyOnWriteArrayList<Tracker>()

    /** A fresh tracked OkHttp factory for ONE live source. [timeoutMs] is used
     *  for both connect and read, matching the HttpURLConnection factories it
     *  replaces. The User-Agent header (if any) wins over [fallbackUserAgent]. */
    fun newFactory(
        headers: Map<String, String>,
        fallbackUserAgent: String,
        timeoutMs: Int,
    ): DataSource.Factory {
        val tracker = Tracker(clientFor(timeoutMs)).also { trackers.add(it) }
        val headerUa = headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
        val factory = OkHttpDataSource.Factory(tracker)
            .setUserAgent(safeUserAgent(headerUa ?: fallbackUserAgent, fallbackUserAgent))
        val nonUa = safeHeaders(headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        if (nonUa.isNotEmpty()) factory.setDefaultRequestProperties(nonUa)
        return factory
    }

    /** Detach every tracker. Take BEFORE building the next source so the new
     *  one is never in the set that gets cancelled. */
    fun take(): List<Tracker> {
        val stale = trackers.toList()
        trackers.removeAll(stale.toSet())
        return stale
    }

    /** Swap [player]'s source: take the stale trackers, set the source built
     *  by [build] (which may call [newFactory]), then retire the stale Calls on
     *  the playback looper, after the queued setMediaSource has cancelled the
     *  loader, so the read failure reports as a canceled load and never
     *  reaches onPlayerError / the tile retry ladder. */
    fun setSource(player: ExoPlayer, build: () -> MediaSource) {
        val stale = take()
        player.setMediaSource(build())
        if (stale.isNotEmpty()) {
            android.os.Handler(player.playbackLooper).post { stale.forEach { it.cancelAll() } }
        }
    }

    /** Stop [player] (keeping it for a later Retry) and close its live
     *  connections on the playback looper, after the stop has let go of the
     *  loaders. */
    fun stop(player: ExoPlayer) {
        val stale = take()
        player.stop()
        if (stale.isNotEmpty()) {
            android.os.Handler(player.playbackLooper).post { stale.forEach { it.cancelAll() } }
        }
    }

    /** Release [player] and close its live connections. release() blocks
     *  until the playback thread let go of the loaders, so the stale Calls
     *  are cancelled directly afterwards. */
    fun release(player: ExoPlayer) {
        val stale = take()
        try {
            player.release()
        } finally {
            stale.forEach { it.cancelAll() }
        }
    }

    private companion object {
        /** Shared per timeout so every tile reuses one pool / dispatcher. */
        private val clients = ConcurrentHashMap<Int, OkHttpClient>()
        private val baseClient: OkHttpClient by lazy { OkHttpClient() }

        fun clientFor(timeoutMs: Int): OkHttpClient = clients.getOrPut(timeoutMs) {
            baseClient.newBuilder()
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                // Mirrors setAllowCrossProtocolRedirects(true).
                .followRedirects(true)
                .followSslRedirects(true)
                // One TCP connection PER TILE (Logan 2026-10-10, Streamer
                // freeze after Move Tile). The Dispatcharr host negotiates
                // HTTP/2, so this shared client multiplexed every live tile
                // onto ONE socket with one connection flow-control window:
                // a stall or loss on that socket stalled every tile at once,
                // and the swap's two cancelled streams sat on the same window
                // the surviving tiles read from (Blue Jackets' loader went
                // silent with no error). HttpURLConnection, which these
                // factories replaced in 6ed34e5e, was HTTP/1.1, one socket
                // per tile; keep that isolation.
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .build()
        }

        /** GH #32 parity with the live holder: okhttp3 throws on header chars
         *  HttpURLConnection tolerated. Drop illegal names, strip illegal value
         *  chars (tab or 0x20..0x7e are legal). Never log a value. */
        fun safeHeaders(headers: Map<String, String>): Map<String, String> {
            if (headers.isEmpty()) return headers
            val out = LinkedHashMap<String, String>(headers.size)
            for ((name, value) in headers) {
                if (name.isEmpty() || !name.all { it.code in 0x21..0x7e }) {
                    Log.w(TAG, "GH#32: dropped tile request header with illegal name (len=${name.length})")
                    continue
                }
                val safe = safeValue(value)
                if (safe !== value) Log.w(TAG, "GH#32: stripped illegal char(s) from tile '$name' header value")
                out[name] = safe
            }
            return out
        }

        fun safeUserAgent(ua: String, fallback: String): String =
            safeValue(ua).ifBlank { safeValue(fallback) }

        fun safeValue(value: String): String {
            if (value.all { it == '\t' || it.code in 0x20..0x7e }) return value
            return buildString(value.length) {
                for (c in value) if (c == '\t' || c.code in 0x20..0x7e) append(c)
            }
        }
    }
}
