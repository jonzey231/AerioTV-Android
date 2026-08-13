package com.aeriotv.android.core.cast.hlsproxy

import java.io.ByteArrayOutputStream

/**
 * Cast HLS proxy P1: a channel whose codecs the pure remux cannot serve.
 * The web/styled Cast receiver is a Chromium page; MSE there can decode
 * H.264 + AAC only (no HEVC/MPEG-2 video, no AC-3/E-AC-3/MP2 audio on
 * legacy dongles), and this proxy never re-encodes, so anything else is
 * refused up front with the codec name for the user-facing message. A
 * later phase adds an audio transcode for the AC-3 case.
 */
class UnsupportedCodecException(val codecName: String) :
    Exception("Cast HLS proxy cannot remux $codecName (no re-encode in P1)")

/**
 * MPEG-TS to fragmented-MP4 (CMAF) remuxer for the phone-local cast HLS
 * proxy (GH #33 web-receiver rework). Pure remux, no re-encode: H.264
 * video (Annex B in PES, converted to length-prefixed avc1 samples) and
 * ADTS AAC audio (headers stripped, config carried in esds).
 *
 * Why this exists: the Styled Media Receiver stutters every 10-15 s on a
 * progressive live fMP4 URL because a progressive stream has no manifest
 * clock to pace it. Serving the SAME elementary streams as sliding-window
 * live HLS with fMP4 segments gives Chromium's HLS stack a target
 * duration and a live edge to steer by, which is the pattern VLC / Web
 * Video Cast / IPTV Extreme all ship.
 *
 * Output contract:
 *  - [Listener.onInitSegment] fires once, as soon as SPS/PPS (and the
 *    AAC config when the PMT declares audio) have been seen: ftyp + moov
 *    with one video and (optionally) one audio track, timescale 90000 on
 *    both so PES 90 kHz timestamps ride through untouched.
 *  - [Listener.onMediaSegment] fires per segment: one moof + mdat pair,
 *    cut ONLY on video keyframe boundaries, targeting
 *    [targetSegmentTicks] (about 3 s). baseMediaDecodeTime is the
 *    segment's first DTS rebased to the session start, carried through
 *    the 33-bit PTS wraparound by a per-track unwrapper.
 *
 * Threading: single-caller. [feed] is invoked from the ingest thread
 * only; no internal locking.
 *
 * TS parsing idioms (0x47 triple-sync scan, packet-boundary carry,
 * adaptation-field walking) follow TimeshiftBufferStore, the parser that
 * survived the GH #51/#55/#65 field campaigns. Kept separate because
 * this one demuxes down to elementary streams while the timeshift buffer
 * deliberately stores the mux untouched.
 */
class TsToFmp4Remuxer(
    private val listener: Listener,
    private val targetSegmentTicks: Long = 3 * TICKS_PER_SECOND,
) {
    interface Listener {
        fun onInitSegment(data: ByteArray)

        /** [durationTicks] is the segment's video span in 90 kHz ticks. */
        fun onMediaSegment(data: ByteArray, durationTicks: Long)
    }

    companion object {
        const val TICKS_PER_SECOND = 90_000L
        private const val TS_PACKET = 188
        private const val PTS_WRAP = 1L shl 33

        private const val VIDEO_TRACK_ID = 1
        private const val AUDIO_TRACK_ID = 2

        /** ISO 13818-1 stream_type values this remux understands. */
        private const val STREAM_TYPE_H264 = 0x1B
        private const val STREAM_TYPE_AAC_ADTS = 0x0F

        /** Names for the refusal message; anything not listed reports the
         *  raw stream_type. */
        private val STREAM_TYPE_NAMES = mapOf(
            0x01 to "MPEG-1 video",
            0x02 to "MPEG-2 video",
            0x10 to "MPEG-4 Part 2 video",
            0x24 to "HEVC video",
            0x42 to "AVS video",
            0xEA to "VC-1 video",
            0x03 to "MP3 audio",
            0x04 to "MP2 audio",
            0x11 to "AAC-LATM audio",
            0x81 to "AC-3 audio",
            0x87 to "E-AC-3 audio",
            0x82 to "DTS audio",
            0x8A to "DTS audio",
        )
        private val VIDEO_STREAM_TYPES = setOf(0x01, 0x02, 0x10, 0x1B, 0x24, 0x42, 0xEA)
        private val AUDIO_STREAM_TYPES = setOf(0x03, 0x04, 0x0F, 0x11, 0x81, 0x87, 0x82, 0x8A)
    }

    // ---- TS layer state ----

    /** Packet-boundary carry, same discipline as TimeshiftWriter: the
     *  ingest hands arbitrary chunk sizes and Dispatcharr joins clients
     *  mid-packet, so bytes are re-aligned on a verified triple 0x47
     *  before anything downstream sees them. */
    private var carry = ByteArray(0)
    private var needResync = true

    private var pmtPid = -1
    private var videoPid = -1
    private var audioPid = -1
    /** PMT parsed; [audioPid] < 0 after this means a video-only mux. */
    private var pmtSeen = false

    private val videoPes = PesAssembler { payload, pts, dts -> onVideoAccessUnit(payload, pts, dts) }
    private val audioPes = PesAssembler { payload, pts, _ -> onAudioPes(payload, pts) }

    // ---- codec config ----

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var aacObjectType = 0
    private var aacFreqIndex = -1
    private var aacChannelConfig = 0
    private var initSent = false

    // ---- timeline ----

    private val videoClock = PtsUnwrapper()
    private val audioClock = PtsUnwrapper()
    /** First queued video DTS; every tfdt is relative to this so the
     *  receiver's timeline starts near zero. */
    private var timelineBase = -1L

    // ---- pending segment ----

    private class VideoSample(val data: ByteArray, val dts: Long, val pts: Long, val keyframe: Boolean)
    private class AudioSample(val data: ByteArray, val pts: Long)

    private val videoQueue = ArrayList<VideoSample>()
    private val audioQueue = ArrayList<AudioSample>()
    /** Raw AAC frame ticks: 1024 samples at the ADTS sample rate. */
    private var audioFrameTicks = 0L
    /** ADTS frames can straddle PES packet boundaries; carry the tail. */
    private var adtsCarry = ByteArray(0)
    private var lastVideoDuration = 3_000L // ~30 fps fallback for the very first delta

    /** Feed raw TS bytes off the wire. Throws [UnsupportedCodecException]
     *  as soon as the PMT declares a codec the remux cannot carry. */
    fun feed(data: ByteArray, offset: Int, length: Int) {
        var merged = if (carry.isEmpty()) data.copyOfRange(offset, offset + length) else carry + data.copyOfRange(offset, offset + length)
        if (needResync) {
            val sync = findSync(merged)
            if (sync < 0) {
                carry = merged.takeLast(TS_PACKET * 2 + 1).toByteArray()
                return
            }
            merged = merged.copyOfRange(sync, merged.size)
            needResync = false
        }
        val whole = (merged.size / TS_PACKET) * TS_PACKET
        carry = if (whole < merged.size) merged.copyOfRange(whole, merged.size) else ByteArray(0)
        var p = 0
        while (p < whole) {
            if (merged[p] != 0x47.toByte()) {
                // Lost sync mid-stream (provider glitch): rescan from here.
                needResync = true
                carry = ByteArray(0)
                val resync = findSync(merged.copyOfRange(p, whole))
                if (resync < 0) return
                p += resync
                needResync = false
                continue
            }
            parsePacket(merged, p)
            p += TS_PACKET
        }
    }

    // ---- TS packet / PSI parsing ----

    private fun parsePacket(buf: ByteArray, off: Int) {
        val transportError = buf[off + 1].toInt() and 0x80 != 0
        if (transportError) return
        val pusi = buf[off + 1].toInt() and 0x40 != 0
        val pid = ((buf[off + 1].toInt() and 0x1F) shl 8) or (buf[off + 2].toInt() and 0xFF)
        val scrambled = buf[off + 3].toInt() and 0xC0 != 0
        if (scrambled) return
        val afc = (buf[off + 3].toInt() shr 4) and 0x03
        if (afc == 0 || afc == 2) return // no payload
        var payloadStart = off + 4
        if (afc == 3) {
            val afLen = buf[off + 4].toInt() and 0xFF
            payloadStart += 1 + afLen
            if (payloadStart >= off + TS_PACKET) return
        }
        val payloadLen = off + TS_PACKET - payloadStart
        when {
            pid == 0 -> parsePat(buf, payloadStart, payloadLen, pusi)
            pid == pmtPid && !pmtSeen -> parsePmt(buf, payloadStart, payloadLen, pusi)
            pid == videoPid -> videoPes.feed(buf, payloadStart, payloadLen, pusi)
            pid == audioPid -> audioPes.feed(buf, payloadStart, payloadLen, pusi)
        }
    }

    private fun parsePat(buf: ByteArray, start: Int, len: Int, pusi: Boolean) {
        if (pmtPid >= 0 || !pusi || len < 13) return
        val p = start + 1 + (buf[start].toInt() and 0xFF) // pointer_field
        if (buf[p].toInt() != 0x00) return // table_id PAT
        val sectionLen = ((buf[p + 1].toInt() and 0x0F) shl 8) or (buf[p + 2].toInt() and 0xFF)
        // Program loop: 8 bytes of fixed header after table_id/len, then
        // 4-byte entries, 4-byte CRC at the end. First non-zero program wins;
        // Dispatcharr and XC panels serve single-program transport streams.
        var q = p + 8
        val end = (p + 3 + sectionLen - 4).coerceAtMost(start + len)
        while (q + 3 < end) {
            val program = ((buf[q].toInt() and 0xFF) shl 8) or (buf[q + 1].toInt() and 0xFF)
            val mapPid = ((buf[q + 2].toInt() and 0x1F) shl 8) or (buf[q + 3].toInt() and 0xFF)
            if (program != 0) {
                pmtPid = mapPid
                return
            }
            q += 4
        }
    }

    private fun parsePmt(buf: ByteArray, start: Int, len: Int, pusi: Boolean) {
        if (!pusi || len < 17) return
        val p = start + 1 + (buf[start].toInt() and 0xFF) // pointer_field
        if (buf[p].toInt() != 0x02) return // table_id PMT
        val sectionLen = ((buf[p + 1].toInt() and 0x0F) shl 8) or (buf[p + 2].toInt() and 0xFF)
        val sectionEnd = (p + 3 + sectionLen - 4).coerceAtMost(start + len) // minus CRC
        val programInfoLen = ((buf[p + 10].toInt() and 0x0F) shl 8) or (buf[p + 11].toInt() and 0xFF)
        var q = p + 12 + programInfoLen
        var video = -1
        var videoType = -1
        var audio = -1
        var audioType = -1
        while (q + 4 < sectionEnd) {
            val streamType = buf[q].toInt() and 0xFF
            val esPid = ((buf[q + 1].toInt() and 0x1F) shl 8) or (buf[q + 2].toInt() and 0xFF)
            val esInfoLen = ((buf[q + 3].toInt() and 0x0F) shl 8) or (buf[q + 4].toInt() and 0xFF)
            if (video < 0 && streamType in VIDEO_STREAM_TYPES) {
                video = esPid; videoType = streamType
            }
            if (audio < 0 && streamType in AUDIO_STREAM_TYPES) {
                audio = esPid; audioType = streamType
            }
            q += 5 + esInfoLen
        }
        // Refuse before any media flows: the ingest surfaces this as the
        // user-visible cast failure with the codec name.
        if (video >= 0 && videoType != STREAM_TYPE_H264) {
            throw UnsupportedCodecException(STREAM_TYPE_NAMES[videoType] ?: "video stream_type 0x%02X".format(videoType))
        }
        if (audio >= 0 && audioType != STREAM_TYPE_AAC_ADTS) {
            throw UnsupportedCodecException(STREAM_TYPE_NAMES[audioType] ?: "audio stream_type 0x%02X".format(audioType))
        }
        if (video < 0) throw UnsupportedCodecException("no video stream in PMT")
        videoPid = video
        audioPid = audio // may stay -1: video-only mux is fine
        pmtSeen = true
    }

    // ---- PES layer ----

    /** Accumulates one PES packet per payload_unit_start and hands the
     *  complete elementary payload plus its PTS/DTS (90 kHz, 33-bit) up. */
    private class PesAssembler(
        private val onComplete: (payload: ByteArray, pts: Long, dts: Long) -> Unit,
    ) {
        private val buf = ByteArrayOutputStream(64 * 1024)
        private var collecting = false

        fun feed(data: ByteArray, start: Int, len: Int, pusi: Boolean) {
            if (pusi) {
                flush()
                collecting = true
            }
            if (collecting) buf.write(data, start, len)
        }

        fun flush() {
            if (!collecting || buf.size() == 0) { buf.reset(); return }
            val pes = buf.toByteArray()
            buf.reset()
            collecting = false
            if (pes.size < 9 || pes[0].toInt() != 0 || pes[1].toInt() != 0 || pes[2].toInt() != 1) return
            val flags = pes[7].toInt() and 0xC0
            val headerLen = pes[8].toInt() and 0xFF
            val payloadOff = 9 + headerLen
            if (payloadOff >= pes.size) return
            var pts = -1L
            var dts = -1L
            if (flags and 0x80 != 0 && headerLen >= 5) {
                pts = readTimestamp(pes, 9)
                dts = if (flags and 0x40 != 0 && headerLen >= 10) readTimestamp(pes, 14) else pts
            }
            if (pts < 0) return // unstamped PES is useless to the segmenter
            onComplete(pes.copyOfRange(payloadOff, pes.size), pts, dts)
        }

        private fun readTimestamp(b: ByteArray, off: Int): Long =
            ((b[off].toLong() and 0x0E) shl 29) or
                ((b[off + 1].toLong() and 0xFF) shl 22) or
                ((b[off + 2].toLong() and 0xFE) shl 14) or
                ((b[off + 3].toLong() and 0xFF) shl 7) or
                ((b[off + 4].toLong() and 0xFE) shr 1)
    }

    /** 33-bit 90 kHz to monotonic 64-bit. A backwards jump larger than
     *  half the wrap range is a wraparound, not a rewind. */
    private class PtsUnwrapper {
        private var last33 = -1L
        private var epoch = 0L

        fun unwrap(ts33: Long): Long {
            if (last33 >= 0) {
                val delta = ts33 - last33
                if (delta < -(PTS_WRAP / 2)) epoch += PTS_WRAP
                else if (delta > PTS_WRAP / 2 && epoch > 0) epoch -= PTS_WRAP
            }
            last33 = ts33
            return epoch + ts33
        }
    }

    // ---- video path ----

    private fun onVideoAccessUnit(payload: ByteArray, pts33: Long, dts33: Long) {
        // One PES with PUSI per access unit is the broadcast norm; split
        // Annex B, harvest parameter sets, convert to 4-byte-length AVCC.
        val nals = splitAnnexB(payload)
        if (nals.isEmpty()) return
        var keyframe = false
        for (nal in nals) {
            when (nal[0].toInt() and 0x1F) {
                5 -> keyframe = true
                7 -> if (sps == null) sps = nal
                8 -> if (pps == null) pps = nal
            }
        }
        maybeEmitInit()
        if (!initSent) return
        // Segments must open on a keyframe: drop leading non-IDR units at
        // stream start (mid-GOP join) instead of shipping undecodable refs.
        if (videoQueue.isEmpty() && timelineBase < 0 && !keyframe) return

        val dts = videoClock.unwrap(dts33)
        val pts = unwrapPtsAgainstDts(pts33, dts)
        if (timelineBase < 0) timelineBase = dts

        if (keyframe && videoQueue.isNotEmpty() && dts - videoQueue.first().dts >= targetSegmentTicks) {
            finalizeSegment(cutDts = dts)
        }
        // AVCC conversion: length-prefixed NALs, parameter sets kept
        // in-band (a mid-stream resolution change then stays decodable).
        val sampleSize = nals.sumOf { 4 + it.size }
        val sample = ByteArray(sampleSize)
        var w = 0
        for (nal in nals) {
            writeU32(sample, w, nal.size); w += 4
            System.arraycopy(nal, 0, sample, w, nal.size); w += nal.size
        }
        videoQueue.add(VideoSample(sample, dts, pts, keyframe))
    }

    /** PTS shares DTS's wrap epoch; unwrap it relative to the unwrapped
     *  DTS instead of running a second independent epoch counter (PTS can
     *  legitimately sit slightly across the wrap point from DTS). */
    private fun unwrapPtsAgainstDts(pts33: Long, dts64: Long): Long {
        val base = dts64 - (dts64 % PTS_WRAP)
        var pts = base + pts33
        if (pts < dts64 - PTS_WRAP / 2) pts += PTS_WRAP
        if (pts > dts64 + PTS_WRAP / 2) pts -= PTS_WRAP
        return pts
    }

    private fun splitAnnexB(payload: ByteArray): List<ByteArray> {
        val nals = ArrayList<ByteArray>(8)
        var i = 0
        var nalStart = -1
        val n = payload.size
        while (i + 2 < n) {
            if (payload[i].toInt() == 0 && payload[i + 1].toInt() == 0 && payload[i + 2].toInt() == 1) {
                if (nalStart >= 0) {
                    var end = i
                    if (end > nalStart && payload[end - 1].toInt() == 0) end-- // 4-byte start code
                    if (end > nalStart) nals.add(payload.copyOfRange(nalStart, end))
                }
                nalStart = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (nalStart in 0 until n) nals.add(payload.copyOfRange(nalStart, n))
        return nals
    }

    // ---- audio path ----

    private fun onAudioPes(payload: ByteArray, pts33: Long) {
        val data = if (adtsCarry.isEmpty()) payload else adtsCarry + payload
        adtsCarry = ByteArray(0)
        var p = 0
        var framePts = -1L
        while (p + 7 <= data.size) {
            if (data[p].toInt() and 0xFF != 0xFF || data[p + 1].toInt() and 0xF0 != 0xF0) {
                p++ // scan to syncword (junk between frames happens on splices)
                continue
            }
            val protectionAbsent = data[p + 1].toInt() and 0x01 != 0
            val profile = (data[p + 2].toInt() shr 6) and 0x03
            val freqIndex = (data[p + 2].toInt() shr 2) and 0x0F
            val chanConfig = ((data[p + 2].toInt() and 0x01) shl 2) or ((data[p + 3].toInt() shr 6) and 0x03)
            val frameLen = ((data[p + 3].toInt() and 0x03) shl 11) or
                ((data[p + 4].toInt() and 0xFF) shl 3) or
                ((data[p + 5].toInt() shr 5) and 0x07)
            if (frameLen < 7 || p + frameLen > data.size) break // partial frame: carry
            val headerLen = if (protectionAbsent) 7 else 9
            if (aacFreqIndex < 0) {
                aacObjectType = profile + 1 // ADTS profile is MPEG-4 audioObjectType - 1
                aacFreqIndex = freqIndex
                aacChannelConfig = chanConfig
                val rate = ADTS_SAMPLE_RATES.getOrElse(freqIndex) { 48_000 }
                audioFrameTicks = 1024L * TICKS_PER_SECOND / rate
                maybeEmitInit()
            }
            if (initSent && frameLen > headerLen) {
                // First frame of the PES rides the PES PTS; followers step
                // by the fixed 1024-sample frame duration. Re-anchoring on
                // every PES keeps drift bounded to one PES worth of frames.
                if (framePts < 0) framePts = audioClock.unwrap(pts33)
                if (timelineBase >= 0) {
                    audioQueue.add(AudioSample(data.copyOfRange(p + headerLen, p + frameLen), framePts))
                }
                framePts += audioFrameTicks
            }
            p += frameLen
        }
        if (p < data.size) adtsCarry = data.copyOfRange(p, data.size)
    }

    // ---- segmenter ----

    private fun maybeEmitInit() {
        if (initSent) return
        if (!pmtSeen || sps == null || pps == null) return
        if (audioPid >= 0 && aacFreqIndex < 0) return
        listener.onInitSegment(buildInitSegment())
        initSent = true
    }

    private fun finalizeSegment(cutDts: Long) {
        if (videoQueue.isEmpty()) return
        val segStart = videoQueue.first().dts
        // Video sample durations come from successor DTS deltas; the last
        // sample's successor is the keyframe that triggered the cut.
        val durations = LongArray(videoQueue.size)
        for (i in videoQueue.indices) {
            val next = if (i + 1 < videoQueue.size) videoQueue[i + 1].dts else cutDts
            var d = next - videoQueue[i].dts
            if (d <= 0) d = lastVideoDuration
            durations[i] = d
            lastVideoDuration = d
        }
        // Audio that belongs to this video span; the rest stays queued.
        val segAudio = ArrayList<AudioSample>(audioQueue.size)
        val keepAudio = ArrayList<AudioSample>(8)
        for (a in audioQueue) {
            if (a.pts < cutDts) segAudio.add(a) else keepAudio.add(a)
        }
        val segment = buildMediaSegment(videoQueue, durations, segAudio)
        val durationTicks = cutDts - segStart
        videoQueue.clear()
        audioQueue.clear()
        audioQueue.addAll(keepAudio)
        listener.onMediaSegment(segment, durationTicks)
    }

    // ---- fMP4 writing ----

    private fun buildInitSegment(): ByteArray {
        val hasAudio = audioPid >= 0
        val dims = runCatching { parseSpsDimensions(sps!!) }.getOrNull() ?: Pair(1280, 720)
        val out = ByteArrayOutputStream(1024)
        out.write(box("ftyp", bytes("iso5"), u32(0), bytes("iso5"), bytes("iso6"), bytes("mp41")))
        val traks = ArrayList<ByteArray>()
        traks.add(videoTrak(dims.first, dims.second))
        if (hasAudio) traks.add(audioTrak())
        val trexes = ArrayList<ByteArray>()
        trexes.add(trex(VIDEO_TRACK_ID))
        if (hasAudio) trexes.add(trex(AUDIO_TRACK_ID))
        val moov = box(
            "moov",
            mvhd(nextTrackId = if (hasAudio) 3 else 2),
            *traks.toTypedArray(),
            box("mvex", *trexes.toTypedArray()),
        )
        out.write(moov)
        return out.toByteArray()
    }

    private fun buildMediaSegment(
        video: List<VideoSample>,
        videoDurations: LongArray,
        audio: List<AudioSample>,
    ): ByteArray {
        val hasAudio = audio.isNotEmpty()
        val videoBytes = video.sumOf { it.data.size }
        val audioBytes = audio.sumOf { it.data.size }

        // trun data_offset is from moof start; build the moof once with
        // placeholder offsets to learn its size, then rebuild with real
        // ones (sizes are offset-independent). One sequence number per
        // emitted segment, not per build pass.
        sequenceNumber++
        var moof = buildMoof(video, videoDurations, audio, videoDataOffset = 0, audioDataOffset = 0)
        val moofSize = moof.size
        moof = buildMoof(
            video, videoDurations, audio,
            videoDataOffset = moofSize + 8,
            audioDataOffset = moofSize + 8 + videoBytes,
        )
        val out = ByteArrayOutputStream(moof.size + 8 + videoBytes + audioBytes)
        out.write(moof)
        out.write(u32(8 + videoBytes + audioBytes))
        out.write(bytes("mdat"))
        for (s in video) out.write(s.data)
        if (hasAudio) for (a in audio) out.write(a.data)
        return out.toByteArray()
    }

    private var sequenceNumber = 0

    private fun buildMoof(
        video: List<VideoSample>,
        videoDurations: LongArray,
        audio: List<AudioSample>,
        videoDataOffset: Int,
        audioDataOffset: Int,
    ): ByteArray {
        val mfhd = fullBox("mfhd", 0, 0, u32(sequenceNumber))
        val videoTraf = box(
            "traf",
            // default-base-is-moof so data_offset is moof-relative (CMAF).
            fullBox("tfhd", 0, 0x020000, u32(VIDEO_TRACK_ID)),
            fullBox("tfdt", 1, 0, u64(video.first().dts - timelineBase)),
            videoTrun(video, videoDurations, videoDataOffset),
        )
        val trafs = ArrayList<ByteArray>()
        trafs.add(videoTraf)
        if (audio.isNotEmpty()) {
            trafs.add(
                box(
                    "traf",
                    fullBox("tfhd", 0, 0x020000, u32(AUDIO_TRACK_ID)),
                    fullBox("tfdt", 1, 0, u64((audio.first().pts - timelineBase).coerceAtLeast(0))),
                    audioTrun(audio, audioDataOffset),
                ),
            )
        }
        return box("moof", mfhd, *trafs.toTypedArray())
    }

    private fun videoTrun(video: List<VideoSample>, durations: LongArray, dataOffset: Int): ByteArray {
        // flags: data-offset | sample-duration | sample-size | sample-flags |
        // sample-composition-time-offset; version 1 for signed cts.
        val body = ByteArrayOutputStream(16 + video.size * 16)
        body.write(u32(video.size))
        body.write(u32(dataOffset))
        for (i in video.indices) {
            val s = video[i]
            body.write(u32(durations[i].toInt()))
            body.write(u32(s.data.size))
            body.write(u32(if (s.keyframe) 0x02000000 else 0x01010000))
            body.write(u32((s.pts - s.dts).toInt()))
        }
        return fullBox("trun", 1, 0x000F01, body.toByteArray())
    }

    private fun audioTrun(audio: List<AudioSample>, dataOffset: Int): ByteArray {
        // Fixed per-frame duration; flags: data-offset | duration | size.
        val body = ByteArrayOutputStream(16 + audio.size * 8)
        body.write(u32(audio.size))
        body.write(u32(dataOffset))
        for (a in audio) {
            body.write(u32(audioFrameTicks.toInt()))
            body.write(u32(a.data.size))
        }
        return fullBox("trun", 0, 0x000301, body.toByteArray())
    }

    // ---- moov internals ----

    private fun mvhd(nextTrackId: Int): ByteArray = fullBox(
        "mvhd", 0, 0,
        u32(0), u32(0), // creation, modification
        u32(TICKS_PER_SECOND.toInt()), u32(0), // timescale, duration (live: 0)
        u32(0x00010000), u16(0x0100), u16(0), u32(0), u32(0), // rate, volume, reserved
        matrix(),
        ByteArray(24), // pre_defined
        u32(nextTrackId),
    )

    private fun matrix(): ByteArray {
        val out = ByteArrayOutputStream(36)
        intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000).forEach { out.write(u32(it)) }
        return out.toByteArray()
    }

    private fun videoTrak(width: Int, height: Int): ByteArray {
        val avcC = run {
            val s = sps!!
            val p = pps!!
            val out = ByteArrayOutputStream(16 + s.size + p.size)
            out.write(1) // configurationVersion
            out.write(s[1].toInt() and 0xFF) // AVCProfileIndication
            out.write(s[2].toInt() and 0xFF) // profile_compatibility
            out.write(s[3].toInt() and 0xFF) // AVCLevelIndication
            out.write(0xFF) // 4-byte NAL lengths
            out.write(0xE1) // 1 SPS
            out.write(u16(s.size)); out.write(s)
            out.write(1) // 1 PPS
            out.write(u16(p.size)); out.write(p)
            box("avcC", out.toByteArray())
        }
        val avc1 = run {
            val body = ByteArrayOutputStream(96)
            body.write(ByteArray(6)); body.write(u16(1)) // reserved, data_reference_index
            body.write(ByteArray(16)) // pre_defined/reserved
            body.write(u16(width)); body.write(u16(height))
            body.write(u32(0x00480000)); body.write(u32(0x00480000)) // 72 dpi
            body.write(u32(0)); body.write(u16(1)) // reserved, frame_count
            body.write(ByteArray(32)) // compressorname
            body.write(u16(0x0018)); body.write(u16(0xFFFF)) // depth, pre_defined
            body.write(avcC)
            box("avc1", body.toByteArray())
        }
        return trak(
            trackId = VIDEO_TRACK_ID,
            width = width, height = height,
            volume = 0,
            handler = "vide", handlerName = "VideoHandler",
            mediaHeader = fullBox("vmhd", 0, 1, u16(0), u16(0), u16(0), u16(0)),
            sampleEntry = avc1,
        )
    }

    private fun audioTrak(): ByteArray {
        val sampleRate = ADTS_SAMPLE_RATES.getOrElse(aacFreqIndex) { 48_000 }
        val asc = byteArrayOf(
            ((aacObjectType shl 3) or (aacFreqIndex shr 1)).toByte(),
            (((aacFreqIndex and 1) shl 7) or (aacChannelConfig shl 3)).toByte(),
        )
        val esds = run {
            // ES_Descriptor(3) > DecoderConfig(4) > DecoderSpecificInfo(5) + SLConfig(6).
            val dsi = byteArrayOf(0x05, asc.size.toByte()) + asc
            val dcd = ByteArrayOutputStream(32).apply {
                write(0x04)
                write(13 + dsi.size)
                write(0x40) // objectTypeIndication: MPEG-4 AAC
                write(0x15) // streamType audio, upStream 0, reserved 1
                write(ByteArray(3)) // bufferSizeDB
                write(u32(0)); write(u32(0)) // maxBitrate, avgBitrate (unknown)
                write(dsi)
            }.toByteArray()
            val slc = byteArrayOf(0x06, 0x01, 0x02)
            val es = ByteArrayOutputStream(48).apply {
                write(0x03)
                write(3 + dcd.size + slc.size)
                write(u16(AUDIO_TRACK_ID)) // ES_ID
                write(0) // flags
                write(dcd)
                write(slc)
            }.toByteArray()
            fullBox("esds", 0, 0, es)
        }
        val mp4a = run {
            val body = ByteArrayOutputStream(64)
            body.write(ByteArray(6)); body.write(u16(1)) // reserved, data_reference_index
            body.write(ByteArray(8)) // reserved
            body.write(u16(aacChannelConfig.coerceAtLeast(1))); body.write(u16(16)) // channels, samplesize
            body.write(u32(0)) // pre_defined/reserved
            body.write(u32(sampleRate shl 16)) // 16.16 sample rate
            body.write(esds)
            box("mp4a", body.toByteArray())
        }
        return trak(
            trackId = AUDIO_TRACK_ID,
            width = 0, height = 0,
            volume = 0x0100,
            handler = "soun", handlerName = "SoundHandler",
            mediaHeader = fullBox("smhd", 0, 0, u16(0), u16(0)),
            sampleEntry = mp4a,
        )
    }

    private fun trak(
        trackId: Int,
        width: Int,
        height: Int,
        volume: Int,
        handler: String,
        handlerName: String,
        mediaHeader: ByteArray,
        sampleEntry: ByteArray,
    ): ByteArray {
        val tkhd = fullBox(
            "tkhd", 0, 7, // enabled | in movie | in preview
            u32(0), u32(0), u32(trackId), u32(0), u32(0), // times, id, reserved, duration
            u32(0), u32(0), // reserved
            u16(0), u16(0), u16(volume), u16(0), // layer, alt group, volume, reserved
            matrix(),
            u32(width shl 16), u32(height shl 16),
        )
        val mdhd = fullBox(
            "mdhd", 0, 0,
            u32(0), u32(0), u32(TICKS_PER_SECOND.toInt()), u32(0),
            u16(0x55C4), u16(0), // language "und"
        )
        val hdlr = fullBox(
            "hdlr", 0, 0,
            u32(0), bytes(handler), ByteArray(12),
            handlerName.toByteArray(Charsets.US_ASCII), ByteArray(1),
        )
        val dinf = box("dinf", fullBox("dref", 0, 0, u32(1), fullBox("url ", 0, 1)))
        val stbl = box(
            "stbl",
            fullBox("stsd", 0, 0, u32(1), sampleEntry),
            fullBox("stts", 0, 0, u32(0)),
            fullBox("stsc", 0, 0, u32(0)),
            fullBox("stsz", 0, 0, u32(0), u32(0)),
            fullBox("stco", 0, 0, u32(0)),
        )
        val minf = box("minf", mediaHeader, dinf, stbl)
        val mdia = box("mdia", mdhd, hdlr, minf)
        return box("trak", tkhd, mdia)
    }

    private fun trex(trackId: Int): ByteArray = fullBox(
        "trex", 0, 0,
        u32(trackId), u32(1), u32(0), u32(0), u32(0x00010000),
    )

    // ---- box plumbing ----

    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val size = 8 + payload.sumOf { it.size }
        val out = ByteArrayOutputStream(size)
        out.write(u32(size))
        out.write(bytes(type))
        payload.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun fullBox(type: String, version: Int, flags: Int, vararg payload: ByteArray): ByteArray {
        val header = byteArrayOf(
            version.toByte(),
            ((flags shr 16) and 0xFF).toByte(),
            ((flags shr 8) and 0xFF).toByte(),
            (flags and 0xFF).toByte(),
        )
        return box(type, header, *payload)
    }

    private fun bytes(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    private fun u16(v: Int): ByteArray = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun u32(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    private fun u64(v: Long): ByteArray = u32((v ushr 32).toInt()) + u32(v.toInt())

    private fun writeU32(dst: ByteArray, off: Int, v: Int) {
        dst[off] = ((v ushr 24) and 0xFF).toByte()
        dst[off + 1] = ((v shr 16) and 0xFF).toByte()
        dst[off + 2] = ((v shr 8) and 0xFF).toByte()
        dst[off + 3] = (v and 0xFF).toByte()
    }

    private fun findSync(buf: ByteArray): Int {
        var i = 0
        val limit = buf.size - 2 * TS_PACKET - 1
        while (i <= limit) {
            if (buf[i] == 0x47.toByte() && buf[i + TS_PACKET] == 0x47.toByte() &&
                buf[i + 2 * TS_PACKET] == 0x47.toByte()
            ) {
                return i
            }
            i++
        }
        return -1
    }

    // ---- SPS dimensions (best effort; tkhd/avc1 sizing only, decoders
    // read the SPS itself from avcC) ----

    private fun parseSpsDimensions(spsNal: ByteArray): Pair<Int, Int> {
        // Strip emulation prevention bytes, skip the NAL header byte.
        val rbsp = ByteArrayOutputStream(spsNal.size)
        var i = 1
        while (i < spsNal.size) {
            if (i + 2 < spsNal.size && spsNal[i].toInt() == 0 && spsNal[i + 1].toInt() == 0 &&
                spsNal[i + 2].toInt() == 3
            ) {
                rbsp.write(0); rbsp.write(0); i += 3
            } else {
                rbsp.write(spsNal[i].toInt()); i++
            }
        }
        val r = BitReader(rbsp.toByteArray())
        val profileIdc = r.bits(8)
        r.bits(16) // constraints + level
        r.ue() // seq_parameter_set_id
        var chromaFormat = 1
        if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134)) {
            chromaFormat = r.ue()
            if (chromaFormat == 3) r.bits(1)
            r.ue(); r.ue(); r.bits(1) // bit depths, qpprime
            if (r.bits(1) == 1) { // seq_scaling_matrix_present
                val lists = if (chromaFormat == 3) 12 else 8
                for (l in 0 until lists) {
                    if (r.bits(1) == 1) skipScalingList(r, if (l < 6) 16 else 64)
                }
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.bits(1); r.se(); r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        r.ue(); r.bits(1) // max_num_ref_frames, gaps_allowed
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.bits(1)
        if (frameMbsOnly == 0) r.bits(1)
        r.bits(1) // direct_8x8
        var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
        if (r.bits(1) == 1) {
            cropL = r.ue(); cropR = r.ue(); cropT = r.ue(); cropB = r.ue()
        }
        val cropUnitX = if (chromaFormat == 0) 1 else 2
        val cropUnitY = (if (chromaFormat <= 1) 2 else 1) * (2 - frameMbsOnly)
        val width = widthMbs * 16 - (cropL + cropR) * cropUnitX
        val height = heightMapUnits * 16 * (2 - frameMbsOnly) - (cropT + cropB) * cropUnitY
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) error("implausible")
        return Pair(width, height)
    }

    private fun skipScalingList(r: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (j in 0 until size) {
            if (nextScale != 0) nextScale = (lastScale + r.se() + 256) % 256
            if (nextScale != 0) lastScale = nextScale
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var pos = 0
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = data[pos ushr 3].toInt() and 0xFF
                v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }
        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0 && zeros < 32) zeros++
            return (1 shl zeros) - 1 + if (zeros > 0) bits(zeros) else 0
        }
        fun se(): Int {
            val k = ue()
            return if (k % 2 == 0) -(k / 2) else (k + 1) / 2
        }
    }
}

private val ADTS_SAMPLE_RATES = intArrayOf(
    96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050,
    16_000, 12_000, 11_025, 8_000, 7_350,
)
