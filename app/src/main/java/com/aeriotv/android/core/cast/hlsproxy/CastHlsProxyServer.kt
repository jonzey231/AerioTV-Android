package com.aeriotv.android.core.cast.hlsproxy

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal HTTP/1.1 server for the phone-local cast HLS proxy (GH #33
 * web-receiver rework). Plain ServerSocket, no dependencies: the only
 * client is the Cast device's Chromium page on the same LAN, fetching
 * three resource shapes:
 *
 *   /live.m3u8      sliding-window live playlist (last [WINDOW_SIZE]
 *                   segments, EXT-X-MAP per generation, no ENDLIST)
 *   /init<G>.mp4    fMP4 init segment for ingest generation G
 *   /seg<N>.m4s     CMAF media segment, monotonic sequence N
 *
 * Every response carries `Access-Control-Allow-Origin: *` because the
 * receiver page's origin is Google's, not ours, and Chromium enforces
 * CORS on MSE fetches.
 *
 * Segment store: an in-memory ring of the last [RING_SIZE] segments
 * (about 3 s each; even a 10 Mbps feed stays under ~32 MB). Generations
 * exist because a reconnect or channel change restarts the remuxer: the
 * new ingest gets a fresh init segment and its first segment is flagged
 * as a playlist discontinuity, so the receiver resets its timeline
 * instead of chasing a clock that jumped.
 *
 * One channel at a time: [beginGeneration] with clearRing=true (channel
 * change) empties the ring but keeps the listening socket, so the
 * receiver's next playlist poll simply sees a new discontinuous window
 * at the same URL.
 */
class CastHlsProxyServer(
    private val log: (String) -> Unit,
) {
    companion object {
        /** Segments advertised in the playlist. */
        private const val WINDOW_SIZE = 5

        /** Segments retained in memory; the extra tail past the window
         *  lets a receiver that is a poll behind still fetch what the
         *  previous playlist advertised. */
        private const val RING_SIZE = 8

        private const val MIME_PLAYLIST = "application/vnd.apple.mpegurl"
        private const val MIME_MP4 = "video/mp4"
        private const val MIME_SEGMENT = "video/iso.segment"
    }

    private class SegmentEntry(
        val seq: Int,
        val generation: Int,
        val data: ByteArray,
        val durationTicks: Long,
        val discontinuity: Boolean,
    )

    private val lock = Any()
    private val ring = ArrayDeque<SegmentEntry>()
    private val inits = HashMap<Int, ByteArray>()
    private var nextSeq = 0
    private var generation = 0
    /** First segment committed after [beginGeneration] gets the
     *  discontinuity flag (reconnect splice or channel change). */
    private var pendingDiscontinuity = false
    /** EXT-X-DISCONTINUITY-SEQUENCE: count of flagged segments that have
     *  fully rolled out of the ring. */
    private var discontinuitySequence = 0

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    /** Diagnostic: log the receiver's FIRST playlist fetch loudly; it is
     *  the proof the Cast device reached the phone at all. */
    private val firstPlaylistServed = AtomicBoolean(false)

    private val _segmentsInGeneration = MutableStateFlow(0)
    /** Segments committed since the last [beginGeneration]; the sender
     *  gates loadMedia on this reaching 2. */
    val segmentsInGeneration: StateFlow<Int> = _segmentsInGeneration.asStateFlow()

    @Volatile var boundPort: Int = 0
        private set

    /** Bind and start accepting. Idempotent. Binds the wildcard address
     *  (the URL handed to the receiver carries the Wi-Fi LAN IP; binding
     *  only that IP would break when Android re-ranks interfaces
     *  mid-session). Returns the bound port. */
    fun start(): Int {
        if (running.get()) return boundPort
        val socket = ServerSocket(0, 8, null as InetAddress?)
        serverSocket = socket
        boundPort = socket.localPort
        running.set(true)
        acceptThread = Thread({ acceptLoop(socket) }, "cast-hls-http").apply {
            isDaemon = true
            start()
        }
        return boundPort
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        synchronized(lock) {
            ring.clear()
            inits.clear()
            _segmentsInGeneration.value = 0
        }
        firstPlaylistServed.set(false)
    }

    val isRunning: Boolean get() = running.get()

    // ---- store (called from the ingest thread) ----

    /** Start a new ingest generation. [clearRing] on a channel change
     *  (the old channel's segments must never be served again); false on
     *  a same-channel reconnect, where the old segments remain valid and
     *  the discontinuity tag alone covers the clock jump. */
    fun beginGeneration(clearRing: Boolean): Int = synchronized(lock) {
        generation++
        pendingDiscontinuity = ring.isNotEmpty() || !clearRing
        if (clearRing) {
            // Segments flagged as discontinuities that get wiped here never
            // roll out one by one, so account for them in the sequence now.
            discontinuitySequence += ring.count { it.discontinuity }
            ring.clear()
            inits.keys.retainAll(setOf(generation))
        }
        _segmentsInGeneration.value = 0
        generation
    }

    fun setInitSegment(gen: Int, data: ByteArray) = synchronized(lock) {
        inits[gen] = data
    }

    fun addSegment(gen: Int, data: ByteArray, durationTicks: Long) {
        synchronized(lock) {
            if (gen != generation) return // stale ingest racing a channel change
            val entry = SegmentEntry(
                seq = nextSeq++,
                generation = gen,
                data = data,
                durationTicks = durationTicks,
                discontinuity = pendingDiscontinuity,
            )
            pendingDiscontinuity = false
            ring.addLast(entry)
            while (ring.size > RING_SIZE) {
                val evicted = ring.removeFirst()
                if (evicted.discontinuity) discontinuitySequence++
                // Drop init segments no ring entry references any more.
                if (ring.none { it.generation == evicted.generation } &&
                    evicted.generation != generation
                ) {
                    inits.remove(evicted.generation)
                }
            }
            _segmentsInGeneration.value += 1
        }
    }

    // ---- playlist ----

    private fun playlistText(): String = synchronized(lock) {
        val window = ring.takeLast(WINDOW_SIZE)
        val sb = StringBuilder(512)
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:7\n")
        val targetSeconds = window.maxOfOrNull {
            ceil(it.durationTicks / TsToFmp4Remuxer.TICKS_PER_SECOND.toDouble()).toInt()
        }?.coerceAtLeast(1) ?: 4
        sb.append("#EXT-X-TARGETDURATION:").append(targetSeconds).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(window.firstOrNull()?.seq ?: nextSeq).append('\n')
        if (discontinuitySequence > 0) {
            sb.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discontinuitySequence).append('\n')
        }
        var lastGen = -1
        for (seg in window) {
            // The tag stays attached to its segment for as long as the
            // segment is in the window; DISCONTINUITY-SEQUENCE above only
            // accounts for flagged segments that have rolled out.
            if (seg.discontinuity) sb.append("#EXT-X-DISCONTINUITY\n")
            if (seg.generation != lastGen) {
                sb.append("#EXT-X-MAP:URI=\"init").append(seg.generation).append(".mp4\"\n")
                lastGen = seg.generation
            }
            val seconds = seg.durationTicks / TsToFmp4Remuxer.TICKS_PER_SECOND.toDouble()
            sb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", seconds)).append(",\n")
            sb.append("seg").append(seg.seq).append(".m4s\n")
        }
        // LIVE playlist: no EXT-X-ENDLIST, ever; the advancing
        // MEDIA-SEQUENCE is the manifest clock the progressive URL lacked.
        sb.toString()
    }

    // ---- HTTP ----

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                if (running.get()) log("accept failed: $t")
                break
            }
            // Thread per connection: the receiver holds at most a playlist
            // poll plus one or two segment fetches in flight.
            Thread({ runCatching { serve(client) } }, "cast-hls-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
            val out = sock.getOutputStream()
            val requestLine = reader.readLine() ?: return
            // Drain headers; nothing in them changes the response.
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            if (method == "OPTIONS") {
                respond(out, 204, "No Content", null, ByteArray(0))
                return
            }
            if (method != "GET" && method != "HEAD") {
                respond(out, 405, "Method Not Allowed", null, ByteArray(0))
                return
            }
            val body: ByteArray?
            val mime: String
            when {
                path == "/live.m3u8" -> {
                    body = playlistText().toByteArray(Charsets.UTF_8)
                    mime = MIME_PLAYLIST
                    if (firstPlaylistServed.compareAndSet(false, true)) {
                        log("receiver fetched the playlist for the first time (${sock.inetAddress?.hostAddress})")
                    }
                }
                path.startsWith("/init") && path.endsWith(".mp4") -> {
                    val gen = path.removePrefix("/init").removeSuffix(".mp4").toIntOrNull()
                    body = gen?.let { g -> synchronized(lock) { inits[g] } }
                    mime = MIME_MP4
                }
                path.startsWith("/seg") && path.endsWith(".m4s") -> {
                    val seq = path.removePrefix("/seg").removeSuffix(".m4s").toIntOrNull()
                    body = seq?.let { s -> synchronized(lock) { ring.firstOrNull { it.seq == s }?.data } }
                    mime = MIME_SEGMENT
                }
                else -> {
                    body = null
                    mime = "text/plain"
                }
            }
            if (body == null) {
                respond(out, 404, "Not Found", "text/plain", "not found".toByteArray())
            } else {
                respond(out, 200, "OK", mime, if (method == "HEAD") ByteArray(0) else body, body.size)
            }
        }
    }

    private fun respond(
        out: OutputStream,
        code: Int,
        reason: String,
        contentType: String?,
        body: ByteArray,
        declaredLength: Int = body.size,
    ) {
        val headers = StringBuilder(160)
        headers.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        contentType?.let { headers.append("Content-Type: ").append(it).append("\r\n") }
        headers.append("Content-Length: ").append(declaredLength).append("\r\n")
        headers.append("Access-Control-Allow-Origin: *\r\n")
        headers.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        headers.append("Access-Control-Allow-Headers: *\r\n")
        headers.append("Cache-Control: no-cache\r\n")
        headers.append("Connection: close\r\n")
        headers.append("\r\n")
        out.write(headers.toString().toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }
}
