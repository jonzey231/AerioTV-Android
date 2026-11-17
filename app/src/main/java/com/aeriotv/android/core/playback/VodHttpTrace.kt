package com.aeriotv.android.core.playback

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.aeriotv.android.core.debug.LogSanitizer
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.Response

/**
 * Per-connection HTTP trace for the on-demand player (movies, episodes,
 * recordings), tag AerioTrace, prefix `[VOD-HTTP]`. Diagnostic only: it
 * observes the OkHttp call (an [EventListener], which cannot change a request)
 * and counts bytes in a pass-through [DataSource]; no header, timeout, retry,
 * or buffer is touched.
 *
 * One line per event, never per read:
 * - `req`: connection number, absolute file offset, Range sent, method,
 *   User-Agent product token, Accept-Encoding, whether the player is
 *   reopening after an error or a close, and the masked URL.
 * - `resp`: status, protocol, Content-Length, Content-Range, Accept-Ranges,
 *   Content-Type, Transfer-Encoding / Content-Encoding, redirects, whether a
 *   redirect changed host (hosts never printed), new or reused socket, and
 *   time to headers.
 * - `end`: bytes read on the connection, duration, and how it ended.
 * - `summary` on a playback error: every connection in order.
 * URLs go through [LogSanitizer.maskStreamUrl]: no host, user, password,
 * token, id, or query value.
 */
@OptIn(UnstableApi::class)
class VodHttpTrace {

    private class Conn(
        val seq: Int,
        val position: Long,
        val requestedLength: Long,
        val startMs: Long,
    ) {
        @Volatile var status: Int = 0
        @Volatile var expected: Long = C.LENGTH_UNSET.toLong()
        @Volatile var bytes: Long = 0L
        @Volatile var ended: String? = null
        @Volatile var detail: String = ""
    }

    private val seq = AtomicInteger(0)
    private val records = ArrayDeque<Conn>()
    private val pending = ConcurrentLinkedQueue<Conn>()

    /** OkHttp event listener factory for the VOD client. Read-only hooks. */
    val eventListenerFactory: EventListener.Factory = EventListener.Factory { CallTrace() }

    /** Wrap the player's upstream factory; http(s) opens are traced, local
     *  files pass through untouched. */
    fun wrap(upstream: DataSource.Factory): DataSource.Factory =
        DataSource.Factory { TracedSource(upstream.createDataSource()) }

    /** One line listing every connection so far, logged on a playback error. */
    fun logSummary(reason: String) {
        val list = synchronized(records) { records.toList() }
        if (list.isEmpty()) {
            Log.w(TAG, "[VOD-HTTP] summary on $reason: no HTTP connections")
            return
        }
        // Chunks keep each logcat line well under the 4 KB line cap.
        list.chunked(12).forEachIndexed { idx, chunk ->
            val body = chunk.joinToString(" | ") { c ->
                "#${c.seq} @${c.position} ${if (c.status > 0) c.status else "-"} " +
                    "${c.bytes}B ${if (c.ended == null) "open" else c.detail}"
            }
            val part = if (list.size > 12) " part ${idx + 1}/${(list.size + 11) / 12}" else ""
            Log.w(TAG, "[VOD-HTTP] summary on $reason$part (${list.size} conn): $body")
        }
    }

    // ---- data source side: offsets, bytes, how each connection ended ----

    private fun begin(spec: DataSpec): Conn {
        val c = Conn(seq.incrementAndGet(), spec.position, spec.length, SystemClock.elapsedRealtime())
        synchronized(records) {
            records.addLast(c)
            while (records.size > MAX_RECORDS) records.removeFirst()
        }
        pending.add(c)
        return c
    }

    private fun previousOf(c: Conn): Conn? = synchronized(records) {
        val i = records.indexOf(c)
        if (i > 0) records[i - 1] else null
    }

    private fun end(c: Conn, how: String) {
        if (c.ended != null) return
        c.ended = how.substringBefore(' ')
        c.detail = how.take(90)
        pending.remove(c)
        val dur = SystemClock.elapsedRealtime() - c.startMs
        val exp = if (c.expected >= 0) c.expected.toString() else "unknown"
        Log.i(
            TAG,
            "[VOD-HTTP] end #${c.seq} @${c.position} read=${c.bytes} expected=$exp dur=${dur}ms how=$how",
        )
    }

    private fun describe(t: Throwable): String {
        val msg = t.message?.let { LogSanitizer.maskUrlsIn(it).take(160) } ?: ""
        val cause = t.cause?.let { cause ->
            val cm = cause.message?.let { LogSanitizer.maskUrlsIn(it).take(120) } ?: ""
            " cause=${cause.javaClass.simpleName}: $cm"
        } ?: ""
        return "${t.javaClass.simpleName}: $msg$cause"
    }

    private inner class TracedSource(private val inner: DataSource) : DataSource {
        private var conn: Conn? = null

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                conn = null
                return inner.open(dataSpec)
            }
            val c = begin(dataSpec)
            conn = c
            try {
                val len = inner.open(dataSpec)
                c.expected = len
                return len
            } catch (t: Throwable) {
                end(c, "open-failed ${describe(t)}")
                throw t
            } finally {
                pending.remove(c)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val c = conn ?: return inner.read(buffer, offset, length)
            val n = try {
                inner.read(buffer, offset, length)
            } catch (t: Throwable) {
                end(c, "error ${describe(t)}")
                throw t
            }
            if (n > 0) {
                c.bytes += n
            } else if (n == C.RESULT_END_OF_INPUT) {
                val exp = c.expected
                when {
                    exp < 0 -> end(c, "eof (length unknown)")
                    c.bytes >= exp -> end(c, "eof-complete")
                    else -> end(c, "premature-eof expected=$exp got=${c.bytes} short=${exp - c.bytes}")
                }
            }
            return n
        }

        override fun addTransferListener(transferListener: TransferListener) {
            inner.addTransferListener(transferListener)
        }

        override fun getUri(): Uri? = inner.uri

        override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders

        override fun close() {
            val c = conn
            conn = null
            try {
                inner.close()
            } finally {
                // Media3 closes for a seek, a new loadable, or a stop; the next
                // req line's offset says which.
                if (c != null) end(c, "closed-by-player")
            }
        }
    }

    // ---- OkHttp side: what went on the wire and what came back ----

    private fun claim(request: Request): Conn? {
        val start = request.header("Range")
            ?.substringAfter("bytes=", "")
            ?.substringBefore('-')
            ?.toLongOrNull() ?: 0L
        val match = pending.firstOrNull { it.position == start } ?: pending.firstOrNull()
        if (match != null) pending.remove(match)
        return match
    }

    private inner class CallTrace : EventListener() {
        private var conn: Conn? = null
        private var callStartMs = 0L
        private var newSocket = false
        private var redirects = 0
        private var requestLogged = false

        override fun callStart(call: Call) {
            callStartMs = SystemClock.elapsedRealtime()
            conn = claim(call.request())
        }

        override fun connectStart(
            call: Call,
            inetSocketAddress: java.net.InetSocketAddress,
            proxy: java.net.Proxy,
        ) {
            newSocket = true
        }

        override fun requestHeadersEnd(call: Call, request: Request) {
            if (requestLogged) return
            requestLogged = true
            val c = conn
            val id = c?.let { "#${it.seq}" } ?: "#?"
            val prev = c?.let { previousOf(it) }
            val reopen = when {
                prev == null -> "first"
                prev.ended == "error" || prev.ended == "open-failed" || prev.ended == "premature-eof" ->
                    "after-error"
                prev.ended == "closed-by-player" -> "after-close"
                prev.ended == null -> "while-#${prev.seq}-open"
                else -> "after-eof"
            }
            val contiguous = if (prev != null && c != null && c.position == prev.position + prev.bytes) {
                " contiguous"
            } else {
                ""
            }
            val ua = request.header("User-Agent")?.substringBefore(' ')?.take(40) ?: "none"
            Log.i(
                TAG,
                "[VOD-HTTP] req $id @${c?.position ?: "?"} len=${c?.requestedLength?.takeIf { it >= 0 } ?: "open"} " +
                    "${request.method} range=${request.header("Range") ?: "none"} " +
                    "ae=${request.header("Accept-Encoding") ?: "none"} conn-hdr=${request.header("Connection") ?: "none"} " +
                    "ua=$ua reopen=$reopen$contiguous url=${LogSanitizer.maskStreamUrl(call.request().url.toString())}",
            )
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            if (response.code in 300..399 && response.header("Location") != null) {
                redirects++
                return
            }
            val c = conn
            if (c != null) c.status = response.code
            val hostChanged = redirects > 0 && response.request.url.host != call.request().url.host
            val proto = when (response.protocol.toString()) {
                "http/1.0" -> "h1.0"
                "http/1.1" -> "h1"
                "h2", "h2_prior_knowledge" -> "h2"
                else -> response.protocol.toString()
            }
            val extra = buildString {
                response.header("Transfer-Encoding")?.let { append(" te=").append(it) }
                response.header("Content-Encoding")?.let { append(" ce=").append(it) }
                response.header("Connection")?.let { append(" conn-hdr=").append(it) }
            }
            Log.i(
                TAG,
                "[VOD-HTTP] resp ${c?.let { "#${it.seq}" } ?: "#?"} ${response.code} $proto " +
                    "cl=${response.header("Content-Length") ?: "none"} " +
                    "cr=${response.header("Content-Range") ?: "none"} " +
                    "ar=${response.header("Accept-Ranges") ?: "none"} " +
                    "ct=${response.header("Content-Type") ?: "none"}$extra " +
                    "redirects=$redirects${if (hostChanged) " host-changed" else ""} " +
                    "socket=${if (newSocket) "new" else "reused"} " +
                    "headers=${SystemClock.elapsedRealtime() - callStartMs}ms",
            )
        }
    }

    companion object {
        private const val TAG = "AerioTrace"
        private const val MAX_RECORDS = 64

        // The phone mini (PhoneVodMini) hands a running player to a new
        // screen; the trace stays with the player that owns the factory.
        private val byPlayer = WeakHashMap<Player, VodHttpTrace>()

        fun attach(player: Player, trace: VodHttpTrace) {
            synchronized(byPlayer) { if (!byPlayer.containsKey(player)) byPlayer[player] = trace }
        }

        fun forPlayer(player: Player): VodHttpTrace? = synchronized(byPlayer) { byPlayer[player] }
    }
}
