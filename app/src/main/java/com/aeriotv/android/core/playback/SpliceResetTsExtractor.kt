package com.aeriotv.android.core.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/**
 * Shared between the extractor layers of ONE live raw-TS source so a
 * server-side stream switch on the kept connection can be handled as a fresh
 * tune without a new GET.
 *
 * Any layer may [request] a reset with a reason (pts, pmt, resync); the
 * innermost [SpliceResetTsExtractor] performs it at its next read on the
 * loader thread, then calls [onReset] (the rebaser arms one shared base) and
 * [onDetected] (the player holder, which hops to the main thread).
 */
class SpliceControl(
    /** Loader thread. Called after the inner TsExtractor was replaced. */
    @Volatile var onDetected: ((String) -> Unit)? = null,
) {
    @Volatile internal var pendingReason: String? = null
    @Volatile internal var onReset: ((String) -> Unit)? = null

    /**
     * Loader thread. The PMT changed the video codec TYPE (H.264 to HEVC or
     * the reverse; stream types). Never handled in place: the extractor stops
     * delivering samples and the holder runs the sequential rebuild
     * (see [CodecChangeRule]).
     */
    @Volatile var onCodecChange: ((oldType: Int, newType: Int) -> Unit)? = null

    /** Set once a codec type change was seen: no more bytes reach the old
     *  readers until the player replaces this source. */
    @Volatile internal var frozen = false

    fun request(reason: String) {
        if (pendingReason == null) pendingReason = reason
    }
}

/**
 * The innermost live TS extractor. Wraps a TsExtractor built by [factory] and
 * replaces it with a brand new one when [SpliceControl] asks.
 *
 * Why (Streamer 2026-11-01 18:47:21): Dispatcharr switched the channel's
 * source on the SAME connection. TsExtractor in MODE_SINGLE_PMT parses the
 * first PMT only (it drops the PMT reader once the tracks are built,
 * TsExtractor.java:834 in media3 1.4.1), so a switch that changes the PMT PID
 * or the elementary PIDs leaves the old readers on PIDs that no longer carry
 * the stream: bytes keep arriving ([FEED]) while the buffer never grows, and
 * any reader that now sees foreign bytes (the AC-3 reader resyncing ~100 times)
 * feeds garbage downstream. A fresh TsExtractor re-reads PAT and PMT and starts
 * a fresh TimestampAdjuster, so every track comes back on ONE new base.
 *
 * The fresh extractor's tracks are mapped by type onto the tracks the player
 * already prepared (ProgressiveMediaPeriod discards ids that appear after
 * endTracks), and repeated endTracks / seekMap calls are swallowed.
 *
 * Detection here: a PAT whose PMT PID changed, or a PMT whose elementary
 * stream set (type and PID) changed, CRC checked (reason=pmt); and a burst of
 * AC-3 resyncs (reason=resync) reported by the reader through [factory].
 */
@UnstableApi
class SpliceResetTsExtractor(
    private val factory: (onResync: () -> Unit) -> Extractor,
    private val control: SpliceControl,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) : Extractor {

    companion object {
        private const val TAG = "AerioExoPlayer"
        /** Resyncs within [RESYNC_WINDOW_MS] that count as a splice burst. */
        const val RESYNC_BURST = 3
        const val RESYNC_WINDOW_MS = 2_000L
        /** Never reset more often than this. */
        const val MIN_RESET_INTERVAL_MS = 5_000L
        /** Bytes discarded per read while frozen. */
        const val FROZEN_SKIP_BYTES = 188 * 64
    }

    private val resyncTimes = ArrayDeque<Long>()
    private var current: Extractor = factory(::onResync)
    private var output: RemapOutput? = null
    private var lastResetAtMs = 0L
    private val sniffer = TsTableSniffer(
        onChange = { why -> control.request("pmt"); log("[SWITCH] TS tables changed on the kept connection ($why)") },
        onCodecChange = { old, new -> onCodecTypeChange(old, new) },
    )

    private fun onCodecTypeChange(old: Int, new: Int) {
        if (control.frozen) return
        log(
            "[SWITCH] TS video type changed on the kept connection " +
                "(${CodecChangeRule.familyOfStreamType(old)} -> ${CodecChangeRule.familyOfStreamType(new)}); " +
                "no in-place swap",
        )
        control.frozen = true
        control.pendingReason = null
        control.onCodecChange?.invoke(old, new)
    }

    /** Test seam. */
    internal var resets = 0
        private set

    private fun onResync() {
        val n = nowMs()
        resyncTimes.addLast(n)
        while (resyncTimes.isNotEmpty() && n - resyncTimes.first() > RESYNC_WINDOW_MS) resyncTimes.removeFirst()
        if (resyncTimes.size >= RESYNC_BURST) {
            resyncTimes.clear()
            control.request("resync")
        }
    }

    override fun sniff(input: ExtractorInput): Boolean = current.sniff(input)

    override fun init(output: ExtractorOutput) {
        val o = RemapOutput(output)
        this.output = o
        current.init(o)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (control.frozen) {
            // A codec type change: the old readers must never see the new
            // codec's bytes, and no fresh extractor is swapped in. Discard
            // until the holder's sequential rebuild replaces the source.
            val n = input.skip(FROZEN_SKIP_BYTES)
            return if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) Extractor.RESULT_END_OF_INPUT
            else Extractor.RESULT_CONTINUE
        }
        control.pendingReason?.let { reason ->
            control.pendingReason = null
            val n = nowMs()
            if (lastResetAtMs == 0L || n - lastResetAtMs >= MIN_RESET_INTERVAL_MS) {
                lastResetAtMs = n
                reset(reason)
            }
        }
        return current.read(TappedInput(input, sniffer), seekPosition)
    }

    private fun reset(reason: String) {
        val o = output ?: return
        resets++
        log("[SWITCH] server-side switch detected (reason=$reason); re-priming")
        o.newGeneration()
        val fresh = factory(::onResync)
        fresh.init(o)
        current.release()
        current = fresh
        resyncTimes.clear()
        control.onReset?.invoke(reason)
        control.onDetected?.invoke(reason)
    }

    override fun seek(position: Long, timeUs: Long) {
        // A real seek is a re-open with a fresh timeline; tables are re-learned.
        control.pendingReason = null
        sniffer.reset()
        current.seek(position, timeUs)
    }

    override fun release() = current.release()

    private class TappedInput(
        private val input: ExtractorInput,
        private val sniffer: TsTableSniffer,
    ) : ExtractorInput by input {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = input.read(buffer, offset, length)
            if (n > 0) sniffer.feed(buffer, offset, n)
            return n
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val ok = input.readFully(target, offset, length, allowEndOfInput)
            if (ok) sniffer.feed(target, offset, length)
            return ok
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            input.readFully(target, offset, length)
            sniffer.feed(target, offset, length)
        }
    }

    /** Maps a fresh TsExtractor's tracks onto the tracks already prepared. */
    internal class RemapOutput(private val down: ExtractorOutput) : ExtractorOutput {
        private val outputs = HashMap<Int, TrackOutput>()
        private val idsByType = LinkedHashMap<Int, MutableList<Int>>()
        private val genMap = HashMap<Int, Int>()
        private var generation = 0
        private var ended = false
        private var seekMapSent = false

        fun newGeneration() {
            generation++
            genMap.clear()
        }

        override fun track(id: Int, type: Int): TrackOutput {
            genMap[id]?.let { return outputs.getValue(it) }
            if (generation > 0) {
                val reuse = idsByType[type]?.firstOrNull { it !in genMap.values }
                if (reuse != null) {
                    genMap[id] = reuse
                    return outputs.getValue(reuse)
                }
                if (ended) return DiscardingTrackOutput()
            }
            val t = down.track(id, type)
            outputs[id] = t
            idsByType.getOrPut(type) { ArrayList() }.add(id)
            genMap[id] = id
            return t
        }

        override fun endTracks() {
            if (ended) return
            ended = true
            down.endTracks()
        }

        override fun seekMap(seekMap: SeekMap) {
            if (seekMapSent) return
            seekMapSent = true
            down.seekMap(seekMap)
        }
    }
}

/**
 * Watches PAT and PMT sections in the raw TS bytes (single-packet sections,
 * CRC checked) and reports when the program's PMT PID or its elementary
 * stream set changes. PMT version alone is ignored: identical tables with a new
 * version change nothing TsExtractor already uses.
 */
internal class TsTableSniffer(
    /** The program's video stream TYPE changed (old, new). Reported instead
     *  of [onChange] for that PMT. */
    private val onCodecChange: (Int, Int) -> Unit = { _, _ -> },
    private val onChange: (String) -> Unit,
) {
    private val pkt = ByteArray(188)
    private var have = 0
    private var pmtPid = -1
    private var esSig: String? = null
    /** Survives a PMT PID change, so a new PMT on a new PID is still
     *  compared with the last video type. */
    private var videoType: Int? = null

    fun reset() {
        have = 0
        pmtPid = -1
        esSig = null
        videoType = null
    }

    fun feed(b: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            if (have == 0 && b[i] != 0x47.toByte()) { i++; continue }
            val take = minOf(188 - have, end - i)
            System.arraycopy(b, i, pkt, have, take)
            have += take
            i += take
            if (have == 188) {
                have = 0
                parse()
            }
        }
    }

    private fun u(i: Int) = pkt[i].toInt() and 0xff

    private fun parse() {
        val pusi = u(1) and 0x40 != 0
        if (!pusi) return
        val pid = ((u(1) and 0x1f) shl 8) or u(2)
        if (pid != 0 && pid != pmtPid) return
        val afc = (u(3) shr 4) and 3
        if (afc and 1 == 0) return
        var p = 4
        if (afc and 2 != 0) p += 1 + u(4)
        if (p >= 188) return
        val s = p + 1 + u(p)
        if (s + 3 > 188) return
        val tableId = u(s)
        val total = 3 + (((u(s + 1) and 0x0f) shl 8) or u(s + 2))
        if (total < 12 || s + total > 188) return
        if (crc32Mpeg(pkt, s, total) != 0) return
        if (pid == 0 && tableId == 0) {
            var j = s + 8
            while (j + 4 <= s + total - 4) {
                val prog = (u(j) shl 8) or u(j + 1)
                val ppid = ((u(j + 2) and 0x1f) shl 8) or u(j + 3)
                if (prog != 0) {
                    if (pmtPid != -1 && ppid != pmtPid) {
                        onChange("PMT PID $pmtPid -> $ppid")
                        esSig = null
                    }
                    pmtPid = ppid
                    return
                }
                j += 4
            }
        } else if (pid == pmtPid && tableId == 2) {
            val pil = ((u(s + 10) and 0x0f) shl 8) or u(s + 11)
            var j = s + 12 + pil
            val sb = StringBuilder()
            val streams = ArrayList<Pair<Int, Int>>()
            while (j + 5 <= s + total - 4) {
                val type = u(j)
                val epid = ((u(j + 1) and 0x1f) shl 8) or u(j + 2)
                val eil = ((u(j + 3) and 0x0f) shl 8) or u(j + 4)
                sb.append(type).append(':').append(epid).append(',')
                streams.add(type to epid)
                j += 5 + eil
            }
            val sig = sb.toString()
            val old = esSig
            val newVideo = CodecChangeRule.videoStreamType(streams)
            val oldVideo = videoType
            if (oldVideo != null && newVideo != null && CodecChangeRule.isTypeChange(oldVideo, newVideo)) {
                onCodecChange(oldVideo, newVideo)
            } else if (old != null && old != sig) {
                onChange("streams [$old] -> [$sig]")
            }
            if (newVideo != null) videoType = newVideo
            esSig = sig
        }
    }

    companion object {
        fun crc32Mpeg(b: ByteArray, off: Int, len: Int): Int {
            var crc = -1
            for (k in off until off + len) {
                crc = crc xor ((b[k].toInt() and 0xff) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }
    }
}
