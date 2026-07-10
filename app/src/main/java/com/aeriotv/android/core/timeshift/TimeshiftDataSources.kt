package com.aeriotv.android.core.timeshift

import android.net.Uri
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
    private var writer: TimeshiftWriter? = null

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val writerProvider: () -> TimeshiftWriter?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            TeeDataSource(upstreamFactory.createDataSource(), writerProvider)
    }

    override fun open(dataSpec: DataSpec): Long {
        writer = writerProvider()
        // Every open is a new HTTP connection that joins the proxy
        // stream mid-packet; realign before consuming its bytes.
        writer?.markDiscontinuity()
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(buffer, offset, length)
        if (n > 0) writer?.append(buffer, offset, n)
        return n
    }

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() {
        writer = null
        upstream.close()
    }
}

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

    override fun open(dataSpec: DataSpec): Long {
        val w = writerProvider() ?: throw IOException("timeshift buffer not active")
        writer = w
        uri = dataSpec.uri
        val fromWallMs = dataSpec.uri.getQueryParameter("fromWallMs")?.toLongOrNull()
            ?: w.tailWallMs
        segments = w.segments()
        if (segments.isEmpty()) throw IOException("timeshift buffer empty")

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
            val n = raf?.read(buffer, offset, length) ?: -1
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
