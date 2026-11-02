package com.aeriotv.android.core.playback

import android.util.Log
import android.util.SparseArray
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.Ac3Reader
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * A TsExtractor, built exactly as DefaultExtractorsFactory builds it in
 * MODE_SINGLE_PMT, whose AC-3 / E-AC-3 reader survives corrupt syncframe
 * headers.
 *
 * media3 1.4.1 Ac3Util indexes its bitrate and frame-size tables with the
 * header's frmsizecod unchecked, so a damaged header (sync word present,
 * frmsizecod above 37) throws ArrayIndexOutOfBoundsException out of
 * Ac3Reader.consume. That fails the whole load ("Unexpected exception
 * loading stream", ERROR_CODE_IO_UNSPECIFIED) and the tile or player goes
 * through a full reconnect. Measured on the Nothing Phone 2026-10-11 16:13:53
 * and 16:14:52 (composite tile ESPNU HD: "length=19; index=30" in
 * Ac3Reader), while a capture of the same stream at that time showed AC-3
 * "invalid bitstream id" / "invalid frame size" errors in ffmpeg. Here the
 * reader drops back to sync search instead, losing only the damaged frame.
 */
@OptIn(UnstableApi::class)
fun safeTsExtractor(onResync: (() -> Unit)? = null): TsExtractor = TsExtractor(
    TsExtractor.MODE_SINGLE_PMT,
    /* extractorFlags = */ 0,
    DefaultSubtitleParserFactory(),
    TimestampAdjuster(0),
    SafeTsPayloadReaderFactory(DefaultTsPayloadReaderFactory(0), onResync),
    TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
)

@OptIn(UnstableApi::class)
private class SafeTsPayloadReaderFactory(
    private val inner: DefaultTsPayloadReaderFactory,
    private val onResync: (() -> Unit)?,
) : TsPayloadReader.Factory {
    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = inner.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? =
        when (streamType) {
            TsExtractor.TS_STREAM_TYPE_AC3, TsExtractor.TS_STREAM_TYPE_E_AC3 ->
                PesReader(ResyncingReader(Ac3Reader(esInfo.language, esInfo.roleFlags), "AC-3", onResync))
            else -> inner.createPayloadReader(streamType, esInfo)
        }
}

@OptIn(UnstableApi::class)
private class ResyncingReader(
    private val inner: ElementaryStreamReader,
    private val name: String,
    private val onResync: (() -> Unit)?,
) : ElementaryStreamReader by inner {
    private var drops = 0
    private var guard: Ac3FrameGuardOutput? = null

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        inner.createTracks(
            object : ExtractorOutput by extractorOutput {
                override fun track(id: Int, type: Int): TrackOutput =
                    Ac3FrameGuardOutput(extractorOutput.track(id, type)).also { guard = it }
            },
            idGenerator,
        )
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        guard?.onPesStart()
        inner.packetStarted(pesTimeUs, flags)
    }

    override fun consume(data: ParsableByteArray) {
        try {
            inner.consume(data)
        } catch (e: RuntimeException) {
            resync(e)
            data.setPosition(data.limit())
        }
    }

    override fun packetFinished(isEndOfInput: Boolean) {
        try {
            inner.packetFinished(isEndOfInput)
        } catch (e: RuntimeException) {
            resync(e)
        }
    }

    private fun resync(e: RuntimeException) {
        drops++
        if (drops <= 5 || drops % 100 == 0) {
            Log.w("AerioPlayer", "[TS-AUDIO] corrupt $name header dropped, resyncing (${e.javaClass.simpleName}: ${e.message}) drops=$drops")
        }
        guard?.onPesStart()
        onResync?.invoke()
        inner.seek()
    }
}

/**
 * Sits between Ac3Reader and its TrackOutput and drops syncframes whose header
 * is plainly not part of the stream (Streamer 2026-11-01 18:47, after a
 * Dispatcharr server-side stream switch: about 100 damaged headers in 30 s).
 *
 * Ac3Reader trusts every 0x0B77 it finds. A damaged AC-3 header with fscod 3
 * yields sampleRate Format.NO_VALUE (-1), and Ac3Reader then computes
 * sampleDurationUs = 1e6 * 1536 / -1 = -1,536,000,000 us, so the NEXT frames of
 * that PES go out 1536 s in the past until the next PES PTS resets the clock:
 * the "audio jump -1536000ms" / "+3072265ms" rebases. A damaged header whose
 * bsid reads above 10 is parsed as E-AC-3 and switched the audio renderer to
 * c2.dolby.eac3.decoder on an AC-3 track.
 *
 * Rule: the first valid format (mime set, sample rate > 0) is locked. A frame
 * with an invalid format, or a format that differs from the locked one, is
 * dropped, and so is the rest of its PES (its clock is already corrupt). A
 * different valid format that persists for [RELOCK_FRAMES] consecutive frames
 * is a real change (for example stereo to 5.1 at a program boundary) and
 * becomes the new lock.
 */
@OptIn(UnstableApi::class)
internal class Ac3FrameGuardOutput(
    private val inner: TrackOutput,
    private val log: (String) -> Unit = { Log.i("AerioPlayer", it) },
) : TrackOutput {
    companion object {
        const val RELOCK_FRAMES = 20
    }

    private var locked: Format? = null
    private var candidate: Format? = null
    private var candidateFrames = 0
    private var taintedUntilPes = false
    private val scratch = ByteArray(4096)
    var droppedFrames = 0
        private set

    private fun valid(f: Format?): Boolean = f != null && f.sampleMimeType != null && f.sampleRate > 0

    private fun same(a: Format, b: Format): Boolean =
        a.sampleMimeType == b.sampleMimeType && a.sampleRate == b.sampleRate && a.channelCount == b.channelCount

    /** Forward the current frame? Decided at format/PES boundaries only. */
    private fun forwarding(): Boolean {
        val c = candidate ?: return false
        val l = locked ?: return false
        return !taintedUntilPes && same(c, l)
    }

    fun onPesStart() {
        taintedUntilPes = false
    }

    override fun format(format: Format) {
        candidate = format
        if (!valid(format)) {
            taintedUntilPes = true
            return
        }
        val l = locked
        if (l == null) {
            locked = format
            candidateFrames = 0
            inner.format(format)
            return
        }
        if (same(format, l)) {
            candidateFrames = 0
        } else {
            taintedUntilPes = true
        }
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (forwarding()) return inner.sampleData(input, length, allowEndOfInput, sampleDataPart)
        val n = input.read(scratch, 0, minOf(length, scratch.size))
        if (n == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw java.io.EOFException()
        }
        return n
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
        sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (forwarding()) inner.sampleData(data, length, sampleDataPart) else data.skipBytes(length)
    }

    override fun sampleData(data: ParsableByteArray, length: Int) =
        sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (forwarding()) {
            inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        droppedFrames++
        val c = candidate
        val l = locked
        if (c != null && l != null && valid(c) && !same(c, l)) {
            candidateFrames++
            if (candidateFrames >= RELOCK_FRAMES) {
                log("[TS-AUDIO] audio format changed to ${c.sampleMimeType} ${c.channelCount}ch ${c.sampleRate}Hz; following it")
                locked = c
                candidateFrames = 0
                inner.format(c)
            }
        } else {
            candidateFrames = 0
        }
    }
}
