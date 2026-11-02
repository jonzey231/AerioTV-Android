package com.aeriotv.android.core.timeshift

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Live Rewind: tee wrapper around the live HTTP [DataSource]. Forwards
 * every byte to ExoPlayer untouched and mirrors it into the active
 * [TimeshiftWriter] (fire-and-forget; the writer's bounded executor
 * guarantees the mirror can never stall playback).
 *
 * The writer is resolved per [open] via [writerProvider], so one
 * factory instance survives channel changes, LAN/WAN failover
 * re-tunes, and the stall-watchdog re-prime: whichever session is
 * active when the connection (re)opens receives the bytes.
 */
class TeeDataSource(
    private val upstream: DataSource,
    private val writerProvider: () -> TimeshiftWriter?,
) : DataSource {
    /** Last writer this connection appended to; tracked only to detect
     *  a session swap so the replacement realigns. */
    private var lastWriter: TimeshiftWriter? = null

    // GH #32 diagnostics: every live raw-TS connection is teed here, so this is
    // the one choke point that sees live byte flow. A connection that OPENS but
    // never delivers bytes (the Android-16 HttpURLConnection stall) is otherwise
    // silent -- ExoPlayer just sits in BUFFERING and the screen stays black.
    // Logging first-byte timing and a "closed with 0 bytes" warning makes that
    // failure self-evident in the shareable log.
    private var openAtMs = 0L
    private var totalRead = 0L
    private var firstByteLogged = false
    private var openHost: String? = null

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val writerProvider: () -> TimeshiftWriter?,
    ) : DataSource.Factory {
        /** The connection of this factory that is open now, if any. */
        @Volatile var active: TeeDataSource? = null
            internal set

        override fun createDataSource(): DataSource =
            TeeDataSource(upstreamFactory.createDataSource(), writerProvider, this)
    }

    private var owner: Factory? = null

    internal constructor(
        upstream: DataSource,
        writerProvider: () -> TimeshiftWriter?,
        owner: Factory,
    ) : this(upstream, writerProvider) {
        this.owner = owner
    }

    /** Set by [requestHandoff]: [close] passes the open upstream here instead
     *  of closing it, so the Live Rewind buffer keeps filling from the SAME
     *  connection (no second GET to the live URL). */
    @Volatile private var handoff: ((DataSource) -> Unit)? = null
    @Volatile private var isOpen = false

    /**
     * Live Rewind entry: keep this connection alive past the player's
     * source swap. Returns false when it is not open. The player's loader
     * closes the source on the swap; that close hands the upstream to [sink]
     * (on the loader thread) instead of closing the socket.
     */
    fun requestHandoff(sink: (DataSource) -> Unit): Boolean {
        if (!isOpen) return false
        handoff = sink
        return true
    }

    override fun open(dataSpec: DataSpec): Long {
        // Every open is a new HTTP connection that joins the proxy
        // stream mid-packet; realign before consuming its bytes.
        lastWriter = writerProvider()
        lastWriter?.markDiscontinuity()
        openAtMs = SystemClock.elapsedRealtime()
        totalRead = 0L
        firstByteLogged = false
        openHost = dataSpec.uri.host
        headLen = 0
        val hdrNames = dataSpec.httpRequestHeaders.keys.sorted().joinToString(",")
        val authMode = when {
            dataSpec.httpRequestHeaders.keys.any { it.equals("X-API-Key", true) } -> "X-API-Key"
            dataSpec.httpRequestHeaders.entries.any {
                it.key.equals("Authorization", true) && it.value.startsWith("Bearer", true)
            } -> "Bearer"
            else -> "none-in-dataspec"
        }
        Log.i(TAG, "live source open host=$openHost url=${redact(dataSpec.uri.toString())} " +
            "dsHeaders=[$hdrNames] auth=$authMode")
        val len = try {
            upstream.open(dataSpec)
        } catch (e: IOException) {
            Log.w(TAG, "live source open failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        val rh: Map<String, List<String>> = try { upstream.responseHeaders } catch (_: Throwable) { emptyMap() }
        fun h(name: String) = rh.entries.firstOrNull { it.key?.equals(name, true) == true }
            ?.value?.joinToString(";")
        openContentType = h("Content-Type")
        Log.i(TAG, "live source response finalUrl=${redact(upstream.uri?.toString() ?: "")} type=$openContentType len=$len")
        isOpen = true
        owner?.active = this
        return len
    }

    private val head = ByteArray(300)
    private var headLen = 0
    private var openContentType: String? = null

    private fun redact(u: String): String =
        u.replace(Regex("(?i)((?:api_?key|token|password|key)=)[^&]*"), "$1REDACTED")

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // A pending handoff: the player's Loader interrupts this thread when
        // it cancels the load, and an interrupted Okio read fails the
        // response body. Clear the flag so the connection survives the swap
        // (the load still ends on its own cancel flag after this read).
        if (handoff != null) Thread.interrupted()
        val n = upstream.read(buffer, offset, length)
        if (n > 0) {
            if (!firstByteLogged) {
                firstByteLogged = true
                val hex = (0 until minOf(n, 32)).joinToString("") { "%02x".format(buffer[offset + it]) }
                Log.i(TAG, "live source first bytes +${SystemClock.elapsedRealtime() - openAtMs}ms " +
                    "n=$n sync0x47=${buffer[offset] == 0x47.toByte()} hex=$hex")
            }
            if (headLen < head.size) {
                val c = minOf(n, head.size - headLen)
                System.arraycopy(buffer, offset, head, headLen, c)
                headLen += c
            }
            totalRead += n
            // Resolve the writer PER READ, not per open: on a channel
            // change the player opens this connection BEFORE the
            // controller's coroutine has created the new session (and
            // the old captured writer gets closed under us), so an
            // open-time snapshot left the buffer permanently empty on
            // the Streamer ("pause works but nothing else does" field
            // report). A freshly-created writer starts with its resync
            // flag set, so picking it up mid-stream self-realigns; an
            // explicit mark covers writer swaps between reads.
            val w = writerProvider()
            if (w !== lastWriter) {
                lastWriter = w
                w?.markDiscontinuity()
            }
            w?.append(buffer, offset, n)
        }
        return n
    }

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() {
        if (owner?.active === this) owner?.active = null
        val sink = handoff
        if (sink != null && isOpen) {
            handoff = null
            isOpen = false
            lastWriter = null
            Log.i(TAG, "live source handed to the Live Rewind buffer (connection kept)")
            sink(upstream)
            return
        }
        isOpen = false
        if (openAtMs != 0L && totalRead in 1 until 1_048_576L) {
            val textual = (0 until headLen).all { val b = head[it].toInt() and 0xff; b == 9 || b == 10 || b == 13 || b in 32..126 }
            Log.w(TAG, "live source short body total=$totalRead bytes after " +
                "${SystemClock.elapsedRealtime() - openAtMs}ms type=$openContentType" +
                if (textual) " text=\"${String(head, 0, headLen, Charsets.UTF_8).replace("\n", "\\n")}\"" else "")
        }
        if (openAtMs != 0L && totalRead == 0L) {
            Log.w(
                TAG,
                "live source closed after ${SystemClock.elapsedRealtime() - openAtMs}ms " +
                    "with 0 bytes received (host=$openHost) - connection opened but never " +
                    "delivered data (see GH #32)",
            )
        }
        lastWriter = null
        upstream.close()
    }

    companion object {
        private const val TAG = "AerioLiveSource"
    }
}

/**
 * GH #65: thrown by [TimeshiftDataSource.read] when playback reaches a
 * byte position where the writer recorded a forward time skip (splice
 * gap, dropped-chunk resync). Media3 1.4.1's TsExtractor ignores the
 * in-band discontinuity indicator, so reading straight across the skip
 * fed the demuxer a mid-stream PTS jump and the audio sink threw
 * UnexpectedDiscontinuityException ~7 s later. Instead the read REFUSES
 * the boundary; AerioExoPlayerHolder recognizes this error and
 * re-enters the buffer exactly at the gap ([segName]+[byteOffset]),
 * which resets the extractor and flushes the renderers so the jump
 * becomes a fresh timeline.
 *
 * Extends FileNotFoundException DELIBERATELY: Media3's
 * DefaultLoadErrorHandlingPolicy surfaces FNFE to the player
 * immediately (retry delay C.TIME_UNSET) instead of blind-retrying the
 * load with backoff, and a retry would only re-open at the same byte
 * and throw again.
 */
class TimeshiftDiscontinuityException(
    val segName: String,
    val byteOffset: Long,
    val resumeWallMs: Long,
) : java.io.FileNotFoundException("timeshift gap at $segName+$byteOffset")

/**
 * Live Rewind: reads the growing timeshift buffer as one continuous
 * TS stream starting at a wall-clock time.
 *
 * URI shape: `aeriotimeshift://buffer?dir=<sessionDirPath>&fromWallMs=<t>`
 *
 * Behavior mirrors the catch-up model that shipped in #133-#139:
 * - reports [C.LENGTH_UNSET] so the extractor treats the stream as
 *   unbounded (no end probe, no byte-range seeks); scrubbing outside
 *   the demuxer buffer re-opens at a new `fromWallMs` instead
 *   (the exact analog of the timeshift URL rebuild).
 * - at the write head it polls briefly for more data, so playback at
 *   1x rides just behind the live tee. If the writer closes and all
 *   bytes are consumed, it returns end-of-input.
 * - if the ring evicted the segment being read (viewer paused past
 *   the rewind depth), it throws; the controller catches the player
 *   error and bumps playback forward to the buffer tail.
 */
class TimeshiftDataSource(
    private val writerProvider: () -> TimeshiftWriter?,
) : DataSource {

    companion object {
        const val SCHEME = "aeriotimeshift"
        fun uri(fromWallMs: Long): Uri =
            Uri.parse("$SCHEME://buffer?fromWallMs=$fromWallMs")

        /** GH #65: open at an exact byte position, used to resume just
         *  PAST a recorded splice gap (a wall-time open interpolates
         *  and could land back BEFORE the gap, re-throwing forever). */
        fun uriAt(segName: String, byteOffset: Long, wallMs: Long): Uri =
            Uri.parse(
                "$SCHEME://buffer?fromWallMs=$wallMs&atSeg=$segName&atOff=$byteOffset",
            )
    }

    class Factory(
        private val writerProvider: () -> TimeshiftWriter?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TimeshiftDataSource(writerProvider)
    }

    private var writer: TimeshiftWriter? = null
    private var segments: List<TimeshiftSegment> = emptyList()
    private var segIndex = 0
    private var raf: RandomAccessFile? = null
    private var uri: Uri? = null
    private var opened = false
    /** GH #65: gap markers at or before this position in [resumeSeg]
     *  were deliberately resumed past; never re-throw for them. */
    private var resumeSeg: String? = null
    private var resumeOff = -1L

    override fun open(dataSpec: DataSpec): Long {
        val w = writerProvider() ?: throw IOException("timeshift buffer not active")
        writer = w
        uri = dataSpec.uri
        val fromWallMs = dataSpec.uri.getQueryParameter("fromWallMs")?.toLongOrNull()
            ?: w.tailWallMs
        segments = w.segments()
        if (segments.isEmpty()) throw IOException("timeshift buffer empty")

        // GH #65: exact-position open (gap hop). Falls through to the
        // wall-time path if the segment was evicted meanwhile.
        val atSeg = dataSpec.uri.getQueryParameter("atSeg")
        val atOff = dataSpec.uri.getQueryParameter("atOff")?.toLongOrNull()
        if (atSeg != null && atOff != null) {
            val idx = segments.indexOfFirst { it.file.name == atSeg }
            if (idx >= 0) {
                resumeSeg = atSeg
                resumeOff = atOff
                raf = openSegmentAt(idx, atOff + dataSpec.position)
                opened = true
                return C.LENGTH_UNSET.toLong()
            }
        }

        // Clamp into the available window, then locate the segment and
        // interpolate the byte offset inside it (TS is near-CBR over a
        // 6s window; landing within a second or two is fine, the
        // demuxer resyncs on the next packet).
        val t = fromWallMs.coerceIn(segments.first().startWallMs, segments.last().endWallMs)
        segIndex = segments.indexOfLast { it.startWallMs <= t }.coerceAtLeast(0)
        val seg = segments[segIndex]
        val span = (seg.endWallMs - seg.startWallMs).coerceAtLeast(1)
        val frac = (t - seg.startWallMs).toDouble() / span
        var byteOffset = (seg.bytes * frac).toLong()
        // Align to a packet boundary so the extractor syncs instantly.
        byteOffset -= byteOffset % TimeshiftBufferStore.TS_PACKET
        // dataSpec.position is a relative skip within our virtual
        // stream (ExoPlayer uses it after internal retries).
        byteOffset += dataSpec.position

        raf = openSegmentAt(segIndex, byteOffset)
        opened = true
        return C.LENGTH_UNSET.toLong()
    }

    private fun openSegmentAt(index: Int, offset: Long): RandomAccessFile {
        var i = index
        var remaining = offset
        while (true) {
            val seg = segments.getOrNull(i) ?: throw IOException("timeshift offset past head")
            if (!seg.file.exists()) throw IOException("timeshift segment evicted")
            val len = seg.file.length()
            if (remaining < len || (i == segments.size - 1)) {
                segIndex = i
                val r = RandomAccessFile(seg.file, "r")
                r.seek(remaining.coerceAtMost(len))
                return r
            }
            remaining -= len
            i++
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) throw IOException("not open")
        val w = writer ?: throw IOException("buffer gone")
        var waits = 0
        while (true) {
            // GH #65: never read ACROSS a recorded time skip. Serve bytes
            // up to the marker, then refuse the boundary itself with a
            // typed error the holder turns into a clean re-open at the
            // gap. Feeding straight through was the AudioSink
            // UnexpectedDiscontinuityException in Destro706's logs.
            var len = length
            val curName = segments.getOrNull(segIndex)?.file?.name
            if (curName != null) {
                val pos = raf?.filePointer ?: 0L
                val gap = w.gaps()
                    .filter {
                        it.segName == curName && it.byteOffset >= pos &&
                            !(it.segName == resumeSeg && it.byteOffset <= resumeOff)
                    }
                    .minByOrNull { it.byteOffset }
                if (gap != null) {
                    if (gap.byteOffset == pos) {
                        throw TimeshiftDiscontinuityException(
                            gap.segName, gap.byteOffset, gap.resumeWallMs,
                        )
                    }
                    len = minOf(length.toLong(), gap.byteOffset - pos).toInt()
                }
            }
            val n = raf?.read(buffer, offset, len) ?: -1
            if (n > 0) return n

            // Current segment exhausted: advance if a newer one exists.
            val fresh = w.segments()
            if (fresh.isNotEmpty()) {
                // Re-resolve our position in the fresh list by file name
                // (the list shifts as the ring evicts old segments).
                val currentName = segments.getOrNull(segIndex)?.file?.name
                val freshIdx = fresh.indexOfFirst { it.file.name == currentName }
                if (freshIdx == -1 && currentName != null) {
                    throw IOException("timeshift segment evicted during read")
                }
                if (freshIdx in 0 until fresh.size - 1) {
                    segments = fresh
                    segIndex = freshIdx + 1
                    raf?.close()
                    raf = RandomAccessFile(segments[segIndex].file, "r")
                    continue
                }
                segments = fresh
            }

            // At the write head: the writer may append more to THIS file
            // (RandomAccessFile sees growth live) or roll a new segment.
            if (w.closed) throw EOFException()
            if (waits++ > 300) throw IOException("timeshift stalled at head")
            try {
                Thread.sleep(100)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException(ie)
            }
        }
    }

    override fun addTransferListener(transferListener: TransferListener) { /* local reads */ }
    override fun getUri(): Uri? = uri
    override fun close() {
        opened = false
        raf?.close()
        raf = null
    }
}
