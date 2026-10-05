package com.aeriotv.android.core.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/**
 * Lets an audio-only live TS start when its PMT still declares a video stream
 * that never carries a picture (GH jonzey231/AerioTV#90, radio channels).
 *
 * Why this exists: TsExtractor (MODE_SINGLE_PMT) creates one track per PMT
 * stream and calls endTracks() right after the PMT. ProgressiveMediaPeriod
 * then refuses to finish preparing until EVERY track has an upstream format,
 * and the H.264 / HEVC readers only output a format once they have seen SPS
 * and PPS. A declared video PID that sends nothing therefore holds the player
 * in BUFFERING forever while audio bytes keep arriving: the endless
 * "Received N MB" spinner.
 *
 * The guard defers registering each video track with the real output until
 * its format arrives (buffering the bytes the reader writes before that, so
 * the first access unit is replayed intact), and defers endTracks() until all
 * declared video tracks have a format. If audio has advanced
 * [VIDEO_GRACE_US] of media time and a declared video track still has no
 * format, that track is dropped for this connection and endTracks() goes
 * through: the player prepares as audio-only. A stream whose video shows up
 * normally is unaffected (its format lands before any meaningful audio time).
 */
@UnstableApi
class AudioOnlyTrackGuard(
    private val inner: Extractor,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : Extractor by inner {

    companion object {
        private const val TAG = "AerioExoPlayer"

        /** Audio media time a declared video track may stay formatless. */
        const val VIDEO_GRACE_US = 3_000_000L

        /** Pre-format bytes kept per pending video track (the tail only:
         *  the first sample is always the most recent bytes). */
        private const val PENDING_TAIL_BYTES = 4 * 1024 * 1024
    }

    private var guardOutput: GuardOutput? = null

    override fun init(output: ExtractorOutput) {
        val g = GuardOutput(output, log)
        guardOutput = g
        inner.init(g)
    }

    override fun seek(position: Long, timeUs: Long) {
        guardOutput?.onSeek()
        inner.seek(position, timeUs)
    }

    private class GuardOutput(
        private val real: ExtractorOutput,
        private val log: (String) -> Unit,
    ) : ExtractorOutput {
        private val tracks = HashMap<Int, TrackOutput>()
        private val pending = LinkedHashMap<Int, PendingVideoTrack>()
        private var endTracksRequested = false
        private var endTracksForwarded = false
        private var firstAudioUs = C.TIME_UNSET

        override fun track(id: Int, type: Int): TrackOutput {
            tracks[id]?.let { return it }
            val t: TrackOutput = when (type) {
                C.TRACK_TYPE_VIDEO -> {
                    if (endTracksForwarded) {
                        real.track(id, type)
                    } else {
                        PendingVideoTrack(id, type).also { pending[id] = it }
                    }
                }
                C.TRACK_TYPE_AUDIO -> AudioWatchTrack(real.track(id, type))
                else -> real.track(id, type)
            }
            tracks[id] = t
            return t
        }

        override fun endTracks() {
            endTracksRequested = true
            maybeForwardEndTracks()
        }

        override fun seekMap(seekMap: SeekMap) = real.seekMap(seekMap)

        fun onSeek() {
            pending.values.forEach { it.clearTail() }
            firstAudioUs = C.TIME_UNSET
        }

        private fun maybeForwardEndTracks() {
            if (endTracksForwarded || !endTracksRequested || pending.isNotEmpty()) return
            endTracksForwarded = true
            real.endTracks()
        }

        private fun onAudioSample(timeUs: Long) {
            if (endTracksForwarded || pending.isEmpty() || timeUs == C.TIME_UNSET) return
            val first = firstAudioUs
            if (first == C.TIME_UNSET || timeUs < first) {
                firstAudioUs = timeUs
                return
            }
            if (timeUs - first < VIDEO_GRACE_US || !endTracksRequested) return
            log(
                "[AUDIO-ONLY] detected: no video track (declared video PID sent no " +
                    "picture in ${(timeUs - first) / 1000}ms of audio; dropping " +
                    "${pending.size} video track(s) for this connection)",
            )
            pending.values.forEach { it.drop() }
            pending.clear()
            maybeForwardEndTracks()
        }

        private inner class AudioWatchTrack(
            private val delegate: TrackOutput,
        ) : TrackOutput by delegate {
            override fun sampleMetadata(
                timeUs: Long,
                flags: Int,
                size: Int,
                offset: Int,
                cryptoData: TrackOutput.CryptoData?,
            ) {
                delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                onAudioSample(timeUs)
            }
        }

        /** A video track not yet registered with the real output. */
        private inner class PendingVideoTrack(
            private val id: Int,
            private val type: Int,
        ) : TrackOutput {
            private var target: TrackOutput? = null
            private var dropped = false
            private var tail = ByteArray(0)
            private var tailLen = 0
            private val scratch = ByteArray(16 * 1024)

            fun drop() {
                dropped = true
                clearTail()
            }

            fun clearTail() {
                tail = ByteArray(0)
                tailLen = 0
            }

            private fun append(src: ByteArray, off: Int, len: Int) {
                if (len <= 0) return
                if (len >= PENDING_TAIL_BYTES) {
                    if (tail.size < PENDING_TAIL_BYTES) tail = ByteArray(PENDING_TAIL_BYTES)
                    System.arraycopy(src, off + len - PENDING_TAIL_BYTES, tail, 0, PENDING_TAIL_BYTES)
                    tailLen = PENDING_TAIL_BYTES
                    return
                }
                val needed = tailLen + len
                if (needed > PENDING_TAIL_BYTES) {
                    val discard = needed - PENDING_TAIL_BYTES
                    System.arraycopy(tail, discard, tail, 0, tailLen - discard)
                    tailLen -= discard
                }
                if (tail.size < tailLen + len) {
                    val grown = ByteArray(minOf(PENDING_TAIL_BYTES, maxOf(tail.size * 2, tailLen + len, 64 * 1024)))
                    System.arraycopy(tail, 0, grown, 0, tailLen)
                    tail = grown
                }
                System.arraycopy(src, off, tail, tailLen, len)
                tailLen += len
            }

            override fun format(format: Format) {
                target?.let { it.format(format); return }
                if (dropped) return
                if (endTracksForwarded) {
                    // Too late to add a track to a prepared period.
                    dropped = true
                    clearTail()
                    return
                }
                val t = real.track(id, type)
                target = t
                t.format(format)
                if (tailLen > 0) t.sampleData(ParsableByteArray(tail.copyOf(tailLen)), tailLen)
                clearTail()
                pending.remove(id)
                maybeForwardEndTracks()
            }

            override fun sampleData(
                input: DataReader,
                length: Int,
                allowEndOfInput: Boolean,
                sampleDataPart: Int,
            ): Int {
                target?.let { return it.sampleData(input, length, allowEndOfInput, sampleDataPart) }
                val n = input.read(scratch, 0, minOf(length, scratch.size))
                if (n == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                    throw java.io.EOFException()
                }
                if (!dropped) append(scratch, 0, n)
                return n
            }

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                target?.let { it.sampleData(data, length, sampleDataPart); return }
                if (dropped) {
                    data.skipBytes(length)
                    return
                }
                append(data.data, data.position, length)
                data.skipBytes(length)
            }

            override fun sampleMetadata(
                timeUs: Long,
                flags: Int,
                size: Int,
                offset: Int,
                cryptoData: TrackOutput.CryptoData?,
            ) {
                target?.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            }
        }
    }
}
