package com.aeriotv.android.core.cast.hlsproxy

import java.io.ByteArrayOutputStream

/**
 * A channel the cast HLS proxy cannot serve, with enough detail for the
 * user-facing refusal (see AerioCastSender.describeRefusal).
 *
 * H.264 and HEVC video are carried (passthrough, or the on-phone
 * transcode); any other video codec (MPEG-1/2, MPEG-4 Part 2, VC-1, AVS)
 * is refused up front by name because no path handles it. Audio is
 * never transcoded either (Logan 2026-09-12): AAC always passes through,
 * AC-3 / E-AC-3 passes through only to a receiver that decodes it, and
 * everything else is refused by name so the message can point at
 * Dispatcharr's AAC output profile.
 */
class UnsupportedCodecException(
    val codecName: String,
    /** True for a video-stream refusal, false for audio. */
    val isVideo: Boolean = true,
) : Exception("Cast HLS proxy cannot serve $codecName")

/**
 * MPEG-TS to fragmented-MP4 (CMAF) remuxer for the phone-local cast HLS
 * proxy (GH #33 web-receiver rework). H.264 and HEVC video are
 * passthrough (Annex B in PES, converted to length-prefixed avc1 / hvc1
 * samples) unless the
 * sender's [CastVideoPlan] asks for the on-phone transcode (2026-09-26),
 * in which case the access units go through [CastVideoTranscoder] and the
 * video track becomes hvc1 (HEVC) or a re-encoded avc1 (H.264 High 4.1).
 * Audio is pure passthrough: ADTS AAC (headers stripped, config carried in
 * esds) or, when [allowAc3Passthrough] is set because the receiver
 * decodes it, AC-3 / E-AC-3 syncframes in an ac-3 / ec-3 sample entry
 * with its dac3 / dec3 config box.
 *
 * No audio transcoding: casting a Dispatcharr channel
 * requests the server's built-in "Web Player (AAC Audio)" output profile
 * (one server-side transcode shared by every client that asks for it),
 * so the common AC-3 lineup arrives here as stereo AAC already. The old
 * on-phone MediaCodec / FFmpeg AC-3 to AAC transcode is gone.
 *
 * Why this exists: the Styled Media Receiver stutters every 10-15 s on a
 * progressive live fMP4 URL because a progressive stream has no manifest
 * clock. Serving the SAME elementary streams as sliding-window live HLS
 * with fMP4 segments gives Chromium's HLS stack a target duration and a
 * live edge to steer by, which is the pattern VLC / Web Video Cast /
 * IPTV Extreme all ship.
 *
 * Output contract (DEMUXED only since 2026-09-13; the single muxed
 * rendition and the /master.m3u8 + /live.m3u8 endpoints that served it
 * are gone, because no receiver can declare ac-3 inside a muxed
 * video/mp4 sample entry):
 *  - [Listener.onInitSegments] fires once, as soon as SPS/PPS (and the
 *    audio config when the PMT declares audio) have been seen: a
 *    video-only ftyp + moov and, when the mux carries audio, an
 *    audio-only one, timescale 90000 on both so PES 90 kHz timestamps
 *    ride through untouched.
 *  - [Listener.onMediaSegment] fires per segment with BOTH renditions of
 *    the same cut: one moof + mdat pair each, cut ONLY on video keyframe
 *    boundaries at the same boundary and under the same moof sequence
 *    number, targeting [targetSegmentTicks] (about 3 s).
 *    baseMediaDecodeTime is the segment's first DTS rebased to the
 *    generation start, carried through the 33-bit PTS wraparound by a
 *    per-track unwrapper.
 *
 * Threading: single-caller. [feed] is invoked from the ingest thread
 * only; no internal locking. Transcoder output arrives through
 * [videoDelivery], which the session serializes with [feed] and
 * [release] on one per-remuxer lock.
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
    private val log: (String) -> Unit = {},
    /** Whether AC-3 / E-AC-3 may pass through to this receiver. False
     *  (the safe default) refuses such a mux by name instead of sending
     *  a stream the receiver can only play silently. */
    private val allowAc3Passthrough: Boolean = false,
    /** Decides passthrough vs transcode once the source SPS is known. */
    private val videoPlan: CastVideoPlan = CastVideoPlan.PASSTHROUGH,
    /** Hop into this remuxer's single-caller context for transcoder
     *  output. null (the unit tests) keeps the video path passthrough
     *  whatever the plan says. */
    private val videoDelivery: CastIngestDelivery? = null,
    /** Builds the transcoder (the MediaCodec one in production, a fake in
     *  the unit tests). null keeps the video path passthrough. */
    private val videoTranscoderFactory: CastVideoTranscoderFactory? = null,
) {
    interface Listener {
        /** The init segments of a generation: [video] is a video-only moov,
         *  [audio] an audio-only moov carrying the ac-3 / ec-3 / mp4a
         *  sample entry, null for a video-only mux. Both keep the
         *  mehd/mvhd 24 h declared duration.
         *
         *  Why two renditions and no muxed one: measured on the Google TV
         *  Streamer's Cast runtime,
         *  isTypeSupported("video/mp4; codecs=\"avc1.64002A,ac-3\"") and
         *  isTypeSupported("video/mp4; codecs=\"ac-3\"") are both false,
         *  but isTypeSupported("audio/mp4; codecs=\"ac-3\"") is TRUE, and
         *  Emby's web receiver plays AC-3 through
         *  MediaCodecAudioDecoder from a SEPARATE audio SourceBuffer. One
         *  muxed rendition could only ever declare an AAC codec string,
         *  which is what used to force a server-side AAC output profile;
         *  two renditions let the audio declare what it really is. */
        fun onInitSegments(video: ByteArray, audio: ByteArray?)

        /** One emitted cut, in both renditions: [video] holds the video
         *  traf only, [audio] the audio traf only, cut at exactly the same
         *  boundary and carrying the same moof sequence number. When the
         *  cut span carried no audio frame at all [audio] is still emitted,
         *  as a zero-sample traf, so the two media playlists keep identical
         *  sequence numbering; it is null only for a video-only mux.
         *
         *  [videoDurationTicks] is the segment's video span in 90 kHz
         *  ticks, i.e. the video rendition's EXTINF.
         *  [audioDurationTicks] is the sum of the segment's audio frame
         *  durations, i.e. the audio rendition's own EXTINF. It may differ
         *  from [videoDurationTicks] by less than one audio frame, which
         *  HLS allows, and falls back to the video span when the segment
         *  has no audio. */
        fun onMediaSegment(
            video: ByteArray,
            audio: ByteArray?,
            videoDurationTicks: Long,
            audioDurationTicks: Long,
        )

        /** Sample census of the segment about to be handed to
         *  [onMediaSegment], fired immediately before it. Exists purely
         *  so the session can log what the FIRST segment of a generation
         *  contained: a segment with zero audio samples, or a video-only
         *  one where the PMT promised audio, is the shape a receiver
         *  rejects silently. Default no-op so tests need not care.
         *
         *  The four Doubles are the segment's own TIMELINE, in seconds
         *  relative to [timelineBase] (exactly what lands in the tfdt
         *  boxes, divided by [TICKS_PER_SECOND]). Added 2026-09-12 after
         *  the Google TV Streamer session at 15:10: with Shaka's
         *  sequenceMode=false the playlist timeline (accumulated EXTINF
         *  from 0) and the media timeline (the segments' own tfdt) were
         *  only related by luck, Shaka seeked to 2.439 s before any media
         *  was appended, then relocated to 0.016 s (MediaGapJumped=1) and
         *  auto-paused at -58 ms. Nothing in the logs let us do that
         *  arithmetic, so the proxy now prints it per segment.
         *
         *  [firstVideoPtsSeconds] is the video track's earliest
         *  PRESENTATION time, and max(firstVideoPtsSeconds,
         *  firstAudioPtsSeconds) is where a two-track SourceBuffer's
         *  buffered range actually begins, because Chromium reports the
         *  INTERSECTION of the tracks, not their union. That maximum is
         *  the number a playhead has to be at or after for the receiver
         *  to have data, which is the comparison the 15:10 session needed
         *  and could not make.
         *
         *  [firstAudioPtsSeconds] is -1.0 when the segment has no audio.
         *  [segmentStartSeconds] is this segment's accumulated media start
         *  within the generation (0.0 for the generation's first segment),
         *  i.e. the playlist-side position, so the two timelines can be
         *  compared directly. */
        fun onSegmentComposition(
            videoSamples: Int,
            audioSamples: Int,
            firstVideoDtsSeconds: Double,
            firstVideoPtsSeconds: Double,
            firstAudioPtsSeconds: Double,
            segmentStartSeconds: Double,
        ) {}

        /** The mux's audio codec as soon as the PMT is parsed ("AAC",
         *  "AC-3", "E-AC-3", "none"), for the cast load log line. */
        fun onAudioCodec(name: String) {}

        /** The video path decided for this connection (passthrough or the
         *  transcode spec), fired once at the first complete parameter sets.
         *  The cast card's transcode note is built from it. */
        fun onVideoPath(info: CastVideoPathInfo) {}

        /** Fired once, through the delivery hop, when the on-phone video
         *  transcode fails, before [feed] throws
         *  [CastVideoTranscodeException]. */
        fun onVideoTranscodeFailed(reason: String) {}
    }

    companion object {
        const val TICKS_PER_SECOND = 90_000L

        /** Declared movie/fragment duration: 24 hours in [TICKS_PER_SECOND]. */
        const val DECLARED_DURATION_TICKS = 86_400L * TICKS_PER_SECOND
        private const val TS_PACKET = 188
        private const val PTS_WRAP = 1L shl 33

        private const val VIDEO_TRACK_ID = 1
        private const val AUDIO_TRACK_ID = 2

        /** ISO 13818-1 stream_type values this remux understands. */
        private const val STREAM_TYPE_H264 = 0x1B
        /** ISO 13818-1 HEVC (Annex B byte stream, same PES carriage). */
        private const val STREAM_TYPE_HEVC = 0x24
        /** How long a segment cut may wait for the audio that belongs in
         *  it, in 90 kHz ticks. Provider audio trailed its video by about
         *  170 ms in the measured casts; half a second of video is a
         *  generous bound that still keeps the playlist growing when the
         *  audio PID dies mid-stream. */
        private const val MAX_CUT_HOLD_TICKS = TICKS_PER_SECOND / 2

        /** How far the running audio clock may sit from a PES PTS before
         *  the PES PTS wins. One PES worth of frames: the measured packing
         *  jitter on the live capture was up to four frames, and a real
         *  splice moves the clock far more than eight. */
        private const val MAX_AUDIO_ANCHOR_DRIFT_FRAMES = 8L

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

        /** nal_unit_types kept out of the samples. HEVC passthrough (hvc1):
         *  VPS, SPS, PPS, AUD. The transcode input additionally drops filler
         *  (H.264 12, HEVC 38); its parameter sets reach the decoder through
         *  its format instead. */
        private val HEVC_PASSTHROUGH_DROP = setOf(32, 33, 34, 35)
        private val HEVC_TRANSCODE_DROP = setOf(32, 33, 34, 35, 38)
        private val H264_TRANSCODE_DROP = setOf(7, 8, 9, 12)

        /** stream_types that can pass through as AC-3 / E-AC-3 when the
         *  receiver decodes them. MPEG audio is deliberately absent: no
         *  Cast receiver decodes it and the phone no longer transcodes. */
        private val AC3_PASSTHROUGH_SOURCES = mapOf(
            0x81 to CastAudioFramer.SourceCodec.AC3,
            0x87 to CastAudioFramer.SourceCodec.EAC3,
        )
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

    /** Source video codec, from the PMT stream_type. */
    private var videoCodec = CastVideoOutputSpec.Codec.H264
    private val isHevc: Boolean get() = videoCodec == CastVideoOutputSpec.Codec.HEVC

    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var aacObjectType = 0
    private var aacFreqIndex = -1
    private var aacChannelConfig = 0

    /** Latched once so a sanitized ADTS config is explained a single time
     *  instead of per frame for the life of the connection. */
    private var adtsConfigSanitized = false

    /** Latched once so the PCE strip is announced a single time instead
     *  of for every frame of the connection. */
    private var aacPceLogged = false
    private var initSent = false

    // ---- video path (passthrough or transcode) ----

    private enum class VideoMode { UNDECIDED, PASSTHROUGH, TRANSCODE }
    private var videoMode = VideoMode.UNDECIDED
    private var videoTranscoder: CastVideoTranscoding? = null

    /** The transcoder's output format: gates the init segment in transcode
     *  mode and supplies the sample entry. */
    private class TranscodedFormat(
        val codec: CastVideoOutputSpec.Codec,
        val config: ByteArray,
        val width: Int,
        val height: Int,
    )
    private var transcodedFormat: TranscodedFormat? = null

    /** Parameter sets in force for the transcoder input (the latest in-band
     *  ones; [sps] / [pps] latch the first). */
    private var transcodeVps = ByteArray(0)
    private var transcodeSps = ByteArray(0)
    private var transcodePps = ByteArray(0)

    /** Last transcoded sample PTS; output must be strictly increasing. */
    private var lastTranscodedPts = -1L
    private var transcodeFailure: String? = null
    private var released = false

    /** "HEVC passthrough" / "H.264 -> HEVC 1920x1080@59.94". */
    var videoPathDescription: String? = null
        private set

    /** True once the video track is re-encoded (the master then declares
     *  the output codec verbatim, no level relabel). */
    val videoIsTranscoded: Boolean get() = videoMode == VideoMode.TRANSCODE

    // ---- AC-3 / E-AC-3 passthrough ----

    /** Non-null when the PMT's audio is AC-3/E-AC-3 and the receiver
     *  decodes it; null keeps the ADTS AAC passthrough path. */
    private var audioSource: CastAudioFramer.SourceCodec? = null

    /** First syncframe's bitstream fields: gates the init segment the way
     *  the ADTS header does on the AAC path, and supplies the dac3 / dec3
     *  sample-entry config. */
    private var ac3Config: CastAudioFramer.EsFrameInfo? = null
    private var ac3Logged = false

    // ---- timeline ----

    private val videoClock = PtsUnwrapper()
    private val audioClock = PtsUnwrapper()
    /** First queued video DTS; every tfdt is relative to this so the
     *  receiver's timeline starts near zero. */
    private var timelineBase = -1L

    /** First queued video PRESENTATION time (dts + composition offset).
     *
     *  Audio is gated on this, not on [timelineBase]. The Cast receiver
     *  appends our segments in MSE 'sequence' AppendMode (Shaka's
     *  HLS default; Chromium logs the warning on every load), and in that
     *  mode Chromium ignores tfdt and anchors the whole append on the
     *  PRESENTATION timestamp of the first coded frame it sees, which is
     *  our first video sample. Any audio sitting between timelineBase and
     *  that presentation time therefore lands BEFORE zero and Chromium
     *  throws it away, logging (Google TV Streamer, 2026-09-12 02:28:43):
     *
     *    Dropping audio frame (DTS -24000us PTS -24000us,-2667us) that is
     *      outside append window [0us, ...]
     *    Truncating audio buffer which overlaps append window start.
     *      PTS -2667us frame_end_timestamp 18666us append_window_start 0us
     *
     *  The truncated frame is then the one that fails to decode
     *  ("Failed to send audio packet for decoding ... timestamp=0", then
     *  "audio decoder fallback after initial decode error"), costing the
     *  load a decoder swap and a reseek on both senders. Measured on the
     *  dumped segments: audio started 56 ms before the first video
     *  presentation time. Gating on the presentation time removes the
     *  whole sequence. */
    private var timelineBasePts = -1L

    /** Media duration already EMITTED by this remuxer, in 90 kHz ticks,
     *  i.e. the accumulated media start of the next segment. This remuxer
     *  instance lives for exactly one ingest connection (one playlist
     *  generation), so it starts at 0 and is the playlist-side position of
     *  each segment: the number [Listener.onSegmentComposition] reports as
     *  segmentStartSeconds, to be compared against the segment's own tfdt
     *  timeline. */
    private var emittedTicks = 0L

    // ---- pending segment ----

    /** Which track a built init or media segment carries. The two
     *  demuxed renditions are the only shape served (see
     *  [Listener.onInitSegments] for why). */
    private enum class Rendition { VIDEO_ONLY, AUDIO_ONLY }

    private class VideoSample(val data: ByteArray, val dts: Long, val pts: Long, val keyframe: Boolean)
    private class AudioSample(val data: ByteArray, val pts: Long, val durationTicks: Long)

    private val videoQueue = ArrayList<VideoSample>()

    /** Video samples of the NEXT segment, held while the cut waits for
     *  audio, plus the cut they are waiting behind (-1 = no cut pending).
     *
     *  Provider audio trails its video in the mux, so when the keyframe
     *  that cuts a segment is demuxed the last ~170 ms of that segment's
     *  audio has not been parsed yet. Cutting there shipped the segment
     *  short, and the late frames then opened the NEXT segment with an
     *  audio tfdt BELOW that segment's own start: a backwards audio
     *  append, which Chromium's splicer trims or drops outright. That is
     *  the per-segment shortfall measured on the Google TV Streamer
     *  (4.00 s segments carrying 179 frames where 187.5 belong) and the
     *  audio DEMUXER_UNDERFLOW that came with it.
     *
     *  So the cut waits until the audio queue has reached it, holding the
     *  new segment's video aside in the meantime. The wait is bounded by
     *  [MAX_CUT_HOLD_TICKS] so a video-only or audio-starved stream still
     *  emits segments on time. */
    private val heldVideo = ArrayList<VideoSample>()
    private var pendingCutDts = -1L
    private val audioQueue = ArrayList<AudioSample>()
    /** Raw AAC frame ticks: 1024 samples at the ADTS sample rate. */
    private var audioFrameTicks = 0L
    /** ADTS frames can straddle PES packet boundaries; carry the tail.
     *  The carry is NEVER cleared at a PES, segment or generation
     *  boundary: it is the front bytes of a real frame, and dropping them
     *  is one lost frame (21.33 ms) of audio. */
    private var adtsCarry = ByteArray(0)

    /** Presentation time the NEXT audio frame is expected at, 90 kHz,
     *  i.e. the running audio clock. The PES PTS alone cannot stamp the
     *  frames of a PES whose payload begins with a CARRIED partial frame:
     *  a PES PTS describes the first access unit that COMMENCES in that
     *  payload, so the carried frame (which commenced in the previous
     *  PES) would be stamped one frame LATE and every follower with it,
     *  which is a 21.33 ms hole in the audio timeline at every straddling
     *  PES. Measured on the Google TV Streamer 2026-09-13: audio
     *  DEMUXER_UNDERFLOW and the video renderer holding frames (60 fps
     *  decoded, about 47 fps presented) while the media clock ratio read
     *  1.0000. So the running clock stamps the frames and the PES PTS is
     *  only an anchor, re-taken when the two disagree by more than one
     *  frame (a splice, a provider discontinuity, or the first frame). */
    private var audioRunPts = -1L

    /** Stamp one audio frame: continue [audioRunPts], and re-anchor to
     *  the PES PTS only when the two have drifted further apart than
     *  [MAX_AUDIO_ANCHOR_DRIFT_FRAMES] frames, which no packing jitter
     *  explains and a splice or a provider discontinuity does.
     *
     *  The running clock is the authority because a frame's duration is
     *  definitional (1024 samples at the declared rate) while a live
     *  provider's PES PTS cadence is not. Measured on the real ESPNU
     *  capture (Dispatcharr AAC output profile, 2026-09-13): every audio
     *  PES is stamped exactly 9600 ticks after the one before it, five
     *  frames' worth, yet about one PES in six carries SIX frames. Trusting
     *  each PES PTS therefore re-stamped a frame that had already been
     *  emitted, once per such PES, and the segment census wandered 2 to 3
     *  frames either side of what the segment's own duration calls for. */
    private fun stampAudioFrame(pesAnchor: Long, frameTicks: Long): Long {
        val tolerance = if (frameTicks > 0) frameTicks * MAX_AUDIO_ANCHOR_DRIFT_FRAMES else 0L
        if (audioRunPts < 0 || kotlin.math.abs(pesAnchor - audioRunPts) > tolerance) {
            audioRunPts = pesAnchor
        }
        return audioRunPts
    }
    private var lastVideoDuration = 3_000L // ~30 fps fallback for the very first delta

    /** Feed raw TS bytes off the wire. Throws [UnsupportedCodecException]
     *  as soon as the PMT declares a codec the remux cannot carry. */
    fun feed(data: ByteArray, offset: Int, length: Int) {
        transcodeFailure?.let { throw CastVideoTranscodeException(it) }
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
        if (video >= 0 && videoType != STREAM_TYPE_H264 && videoType != STREAM_TYPE_HEVC) {
            throw UnsupportedCodecException(STREAM_TYPE_NAMES[videoType] ?: "video stream_type 0x%02X".format(videoType))
        }
        if (videoType == STREAM_TYPE_HEVC) videoCodec = CastVideoOutputSpec.Codec.HEVC
        if (audio >= 0 && audioType != STREAM_TYPE_AAC_ADTS) {
            // AC-3 / E-AC-3 passes through UNTOUCHED, but only to a
            // receiver that decodes it (the sender decides from the Cast
            // device model). Everything else refuses by name, and the
            // message points the user at Dispatcharr's AAC output
            // profile: the phone never transcodes cast audio.
            val passthrough = AC3_PASSTHROUGH_SOURCES[audioType]
            if (passthrough == null || !allowAc3Passthrough) {
                throw UnsupportedCodecException(
                    codecName = STREAM_TYPE_NAMES[audioType]
                        ?: "audio stream_type 0x%02X".format(audioType),
                    isVideo = false,
                )
            }
            audioSource = passthrough
        }
        if (video < 0) throw UnsupportedCodecException("no video stream in PMT")
        videoPid = video
        audioPid = audio // may stay -1: video-only mux is fine
        pmtSeen = true
        listener.onAudioCodec(
            when {
                audio < 0 -> "none"
                audioSource != null -> audioSource!!.displayName
                else -> "AAC"
            },
        )
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
            if (isHevc) {
                when (nalType(nal)) {
                    in 16..23 -> keyframe = true // IRAP: BLA, IDR, CRA
                    32 -> {
                        if (vps == null) vps = nal
                        transcodeVps = nal
                    }
                    33 -> {
                        if (sps == null) sps = nal
                        transcodeSps = nal
                    }
                    34 -> {
                        if (pps == null) pps = nal
                        transcodePps = nal
                    }
                }
            } else {
                when (nalType(nal)) {
                    5 -> keyframe = true
                    7 -> {
                        if (sps == null) sps = nal
                        transcodeSps = nal
                    }
                    8 -> {
                        if (pps == null) pps = nal
                        transcodePps = nal
                    }
                }
            }
        }
        if (videoMode == VideoMode.UNDECIDED) {
            if (sps == null || pps == null || (isHevc && vps == null)) return
            decideVideoMode()
            if (videoMode == VideoMode.UNDECIDED) return
        }
        if (videoMode == VideoMode.TRANSCODE) {
            onTranscodeSourceAccessUnit(nals, pts33, dts33, keyframe)
            return
        }
        maybeEmitInit()
        if (!initSent) return
        // Segments must open on a keyframe: drop leading non-IDR units at
        // stream start (mid-GOP join) instead of shipping undecodable refs.
        if (videoQueue.isEmpty() && timelineBase < 0 && !keyframe) return

        val dts = videoClock.unwrap(dts33)
        val pts = unwrapPtsAgainstDts(pts33, dts)
        if (timelineBase < 0) {
            timelineBase = dts
            // The composition offset of the first sample is what the
            // receiver anchors on; see [timelineBasePts].
            timelineBasePts = pts
        }

        // AVCC conversion: length-prefixed NALs, parameter sets kept
        // in-band (a mid-stream resolution change then stays decodable).
        // HEVC is hvc1: the hvcC in the sample entry is the only copy of
        // VPS / SPS / PPS, so they and the AUD come out of the samples, the
        // same shape the HEVC transcode output has always had.
        val dropping = if (isHevc) HEVC_PASSTHROUGH_DROP else emptySet()
        enqueueVideoSample(VideoSample(lengthPrefixed(nals, dropping), dts, pts, keyframe))
    }

    /** Queue one video sample for the segmenter (passthrough and transcode
     *  paths alike). The cut rule: the first keyframe at or after
     *  [targetSegmentTicks] of the segment's first DTS. */
    private fun enqueueVideoSample(queued: VideoSample) {
        if (pendingCutDts < 0 && queued.keyframe && videoQueue.isNotEmpty() &&
            queued.dts - videoQueue.first().dts >= targetSegmentTicks
        ) {
            pendingCutDts = queued.dts
        }
        if (pendingCutDts >= 0) {
            heldVideo.add(queued)
            maybeCut(queued.dts)
            return
        }
        videoQueue.add(queued)
    }

    /** nal_unit_type in the source codec's header layout. */
    private fun nalType(nal: ByteArray): Int =
        if (isHevc) (nal[0].toInt() shr 1) and 0x3F else nal[0].toInt() and 0x1F

    /** 4-byte-length NAL units, skipping the given nal_unit_types. */
    private fun lengthPrefixed(nals: List<ByteArray>, dropping: Set<Int>): ByteArray {
        val kept = if (dropping.isEmpty()) nals else nals.filter { nalType(it) !in dropping }
        val sample = ByteArray(kept.sumOf { 4 + it.size })
        var w = 0
        for (nal in kept) {
            writeU32(sample, w, nal.size); w += 4
            System.arraycopy(nal, 0, sample, w, nal.size); w += nal.size
        }
        return sample
    }

    // ---- video transcode ----

    /** Runs once, at the first access unit that completes the parameter
     *  sets (SPS + PPS, plus VPS for HEVC): the sender's plan against the
     *  source's SPS. Logs the plan line and reports the path to the
     *  listener for the cast card. */
    private fun decideVideoMode() {
        val srcName = CastVideoPlan.codecName(videoCodec)
        val info = sps?.let {
            runCatching {
                if (isHevc) CastSpsParser.parseHevcStreamInfo(it) else CastSpsParser.parseSpsInfo(it)
            }.getOrNull()
        }
        if (info == null) {
            if (isHevc) {
                // Without a readable SPS there is no hvcC to declare, so
                // HEVC cannot even pass through; wait for the next one.
                if (!hevcSpsUnreadableLogged) {
                    hevcSpsUnreadableLogged = true
                    log("[Cast] video plan: HEVC SPS unreadable, waiting for the next parameter sets")
                }
                vps = null; sps = null; pps = null
                return
            }
            videoMode = VideoMode.PASSTHROUGH
            videoPathDescription = "H.264 passthrough"
            log("[Cast] video plan: source SPS unreadable -> passthrough")
            return
        }
        var decision = videoPlan.decide(info)
        if (decision.output != null && (videoDelivery == null || videoTranscoderFactory == null)) {
            decision = CastVideoDecision(null, "no transcode delivery queue")
        }
        log(videoPlan.logLine(info, decision))
        val spec = decision.output
        val deliver = videoDelivery
        val factory = videoTranscoderFactory
        if (spec == null || deliver == null || factory == null) {
            videoMode = VideoMode.PASSTHROUGH
            videoPathDescription = "$srcName passthrough"
            listener.onVideoPath(CastVideoPathInfo(info, null, decision.reason))
            return
        }
        videoMode = VideoMode.TRANSCODE
        val codecName = CastVideoPlan.codecName(spec.codec)
        val fps = spec.outputFps(info.fps)?.let { "@" + CastVideoPlan.fpsLabel(it) } ?: ""
        videoPathDescription = "$srcName${if (info.isHdr) " HDR" else ""} -> $codecName ${spec.width}x${spec.height}$fps" +
            if (info.isHdr) (if (spec.hdr) " HDR" else " SDR (tone mapped)") else ""
        listener.onVideoPath(CastVideoPathInfo(info, spec, decision.reason, decision.unsupported, decision.forced))
        val sink = CastVideoTranscodeSink(
            onFormat = { codec, config, width, height -> onTranscodedFormat(codec, config, width, height) },
            onSample = { data, pts, key -> onTranscodedSample(data, pts, key) },
            onFailure = { reason -> onTranscodeFailed(reason) },
        )
        videoTranscoder = factory(info, spec, targetSegmentTicks, sink, deliver)
    }
    private var hevcSpsUnreadableLogged = false

    /** Source access unit on the transcode path. The timeline is anchored
     *  here, on the SOURCE IDR, not when the first encoded frame comes
     *  back: output PTS are the source PTS, so the first encoded frame IS
     *  this IDR's presentation time, and anchoring now lets the audio that
     *  is demuxed while the encoder warms up queue instead of being gated. */
    private fun onTranscodeSourceAccessUnit(nals: List<ByteArray>, pts33: Long, dts33: Long, keyframe: Boolean) {
        val transcoder = videoTranscoder ?: return
        if (released) return
        // The audio config has to be known before anything is anchored, so
        // the init segment can go out with the encoder's first frame.
        if (timelineBase < 0 && !(keyframe && pmtSeen && audioConfigReady)) return
        val dts = videoClock.unwrap(dts33)
        val pts = unwrapPtsAgainstDts(pts33, dts)
        if (timelineBase < 0) {
            // Encoded samples carry DTS == PTS, so the video tfdt base is
            // the IDR's presentation time.
            timelineBase = pts
            timelineBasePts = pts
        }
        // Parameter sets travel separately (the decoder format), AUD and
        // filler mean nothing to the decoder.
        val sample = lengthPrefixed(nals, if (isHevc) HEVC_TRANSCODE_DROP else H264_TRANSCODE_DROP)
        if (sample.isEmpty()) return
        val parameterSets = if (isHevc) {
            listOf(transcodeVps, transcodeSps, transcodePps)
        } else {
            listOf(transcodeSps, transcodePps)
        }
        transcoder.feed(sample, pts, dts, keyframe, parameterSets)
    }

    private fun onTranscodedFormat(codec: CastVideoOutputSpec.Codec, config: ByteArray, width: Int, height: Int) {
        if (released || transcodedFormat != null) return
        transcodedFormat = TranscodedFormat(codec, config, width, height)
        maybeEmitInit()
    }

    private fun onTranscodedSample(data: ByteArray, pts: Long, keyframe: Boolean) {
        if (released || !initSent || transcodeFailure != null) return
        // Monotonic output, and every segment (the first one included)
        // opens on an encoder IDR.
        if (pts <= lastTranscodedPts || pts < timelineBase) return
        if (videoQueue.isEmpty() && heldVideo.isEmpty() && emittedTicks == 0L && !keyframe) return
        lastTranscodedPts = pts
        enqueueVideoSample(VideoSample(data, pts, pts, keyframe))
    }

    private fun onTranscodeFailed(reason: String) {
        if (released || transcodeFailure != null) return
        transcodeFailure = reason
        listener.onVideoTranscodeFailed(reason)
    }

    /** Take the pending cut once the audio queue has caught up past it,
     *  or once the hold has run longer than [MAX_CUT_HOLD_TICKS] of video.
     *  See [heldVideo] for why the cut waits at all. */
    private fun maybeCut(latestDts: Long) {
        val cut = pendingCutDts
        if (cut < 0) return
        val audioEnd = audioQueue.lastOrNull()?.let { it.pts + it.durationTicks } ?: Long.MAX_VALUE
        val audioReady = audioPid < 0 || audioEnd >= cut
        if (!audioReady && latestDts - cut < MAX_CUT_HOLD_TICKS) return
        pendingCutDts = -1L
        finalizeSegment(cutDts = cut)
        videoQueue.addAll(heldVideo)
        heldVideo.clear()
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
        val source = audioSource
        if (source == null) onAdtsAudioPes(payload, pts33) else onAc3AudioPes(source, payload, pts33)
    }

    /**
     * AC-3 / E-AC-3 passthrough: frame the elementary stream (syncframes;
     * [adtsCarry] doubles as the generic audio carry), stamp each frame's
     * PTS (first frame of the PES rides the PES PTS, followers step by
     * the frame's own duration, mirroring the ADTS path), and queue the
     * syncframe itself as an fMP4 audio sample. Nothing is decoded or
     * re-encoded; the ac-3 / ec-3 sample entry tells the receiver what it
     * is holding.
     */
    private fun onAc3AudioPes(
        source: CastAudioFramer.SourceCodec,
        payload: ByteArray,
        pts33: Long,
    ) {
        val data = if (adtsCarry.isEmpty()) payload else adtsCarry + payload
        adtsCarry = ByteArray(0)
        val pesAnchor = audioClock.unwrap(pts33)
        var p = 0
        while (p < data.size) {
            val info = CastAudioFramer.parseFrameHeader(source, data, p)
            if (info == null || info.frameLength <= 0) {
                if (data.size - p < 8) break // possibly a truncated header: carry it
                p++ // scan to syncword (junk between frames happens on splices)
                continue
            }
            val next = p + info.frameLength
            if (next > data.size) break // partial frame: carry
            // Reject a false sync inside frame data: the next frame must
            // start on a syncword when it is already in the buffer.
            if (next + 1 < data.size && !CastAudioFramer.looksLikeSync(source, data, next)) {
                p++
                continue
            }
            if (ac3Config == null) {
                ac3Config = info
                audioFrameTicks = info.samplesPerFrame.toLong() * TICKS_PER_SECOND / info.sampleRate
                maybeEmitInit()
            }
            if (!ac3Logged) {
                log(
                    "audio passthrough: ${source.displayName} ${info.channels}ch " +
                        "${info.sampleRate}Hz ${info.bitrateKbps}kbps (no transcode)",
                )
                ac3Logged = true
            }
            val durationTicks = info.samplesPerFrame.toLong() * TICKS_PER_SECOND / info.sampleRate
            val framePts = stampAudioFrame(pesAnchor, durationTicks)
            audioRunPts = framePts + durationTicks
            if (audioQueueOpen) {
                queueAudio(data.copyOfRange(p, next), framePts, durationTicks)
            }
            p = next
        }
        if (p < data.size) adtsCarry = data.copyOfRange(p, data.size)
    }

    private fun onAdtsAudioPes(payload: ByteArray, pts33: Long) {
        val data = if (adtsCarry.isEmpty()) payload else adtsCarry + payload
        adtsCarry = ByteArray(0)
        val pesAnchor = audioClock.unwrap(pts33)
        var p = 0
        while (p + 7 <= data.size) {
            if (data[p].toInt() and 0xFF != 0xFF || data[p + 1].toInt() and 0xF0 != 0xF0) {
                p++ // scan to syncword (junk between frames happens on splices)
                continue
            }
            // Layer must be 00 in ADTS. A nonzero layer is the cheapest
            // proof that these two bytes are audio payload that merely
            // looks like 0xFFFx, not a real header.
            if ((data[p + 1].toInt() shr 1) and 0x03 != 0) {
                p++
                continue
            }
            val protectionAbsent = data[p + 1].toInt() and 0x01 != 0
            val profile = (data[p + 2].toInt() shr 6) and 0x03
            val freqIndex = (data[p + 2].toInt() shr 2) and 0x0F
            val chanConfig = ((data[p + 2].toInt() and 0x01) shl 2) or ((data[p + 3].toInt() shr 6) and 0x03)
            val frameLen = ((data[p + 3].toInt() and 0x03) shl 11) or
                ((data[p + 4].toInt() and 0xFF) shl 3) or
                ((data[p + 5].toInt() shr 5) and 0x07)
            val headerLen = if (protectionAbsent) 7 else 9
            if (frameLen <= headerLen) {
                p++ // a real frame always carries payload after its header
                continue
            }
            if (p + frameLen > data.size) break // partial frame: carry
            // False sync inside frame data: the next frame must itself
            // start on a syncword when it is already in the buffer. The
            // AC-3 path has always done this; the ADTS path did not, and
            // a single false 0xFFFx hit was enough to latch the codec
            // config below from random payload bytes forever.
            if (p + frameLen + 1 < data.size &&
                (
                    data[p + frameLen].toInt() and 0xFF != 0xFF ||
                        data[p + frameLen + 1].toInt() and 0xF0 != 0xF0
                    )
            ) {
                p++
                continue
            }
            // channel_configuration 0 means the layout lives in a
            // program_config_element at the start of the raw_data_block,
            // which is exactly what Dispatcharr's AAC output profile
            // emits (ffmpeg `-c:a aac -ac 2`, "Using a PCE to encode
            // channel layout"). Two things then break downstream: an
            // AudioSpecificConfig cannot express config 0 at all, and the
            // Google TV Streamer's C2SoftAacDec refuses every frame that
            // still carries the PCE (measured: 5388 lines of "error
            // 0x0005, substituting silence" in one session). Both are
            // fixed losslessly here, per frame: derive the real channel
            // count from the element for the ASC, and drop the element
            // off the front of the block. No transcode, no re-encode.
            var payloadStart = p + headerLen
            var effectiveChanConfig = chanConfig
            if (chanConfig == 0) {
                val pce = CastAudioFramer.parseAacPce(data, p + headerLen, p + frameLen)
                if (pce != null) {
                    // The PCE ends byte-aligned relative to the block
                    // start (parseAacPce refuses to report one that does
                    // not), so the elements after it begin on a byte
                    // boundary and the rest of the block copies over
                    // verbatim: no bit shifting, and the block's existing
                    // id_syn_ele 7 terminator plus its byte alignment
                    // still terminate the shortened block correctly.
                    // A channel_configuration implies the element types,
                    // their order AND their instance tags, not just a
                    // channel count, so the PCE can only be dropped when
                    // its element list is exactly the one some entry of
                    // Table 1.19 implies. Deriving the config from the
                    // channel count alone was measured to destroy the
                    // audio outright: ffmpeg's "5.1(side)" PCE declares
                    // CPE(0), SCE(0), SCE(1), CPE(1) and no LFE element,
                    // and those frames presented as config 6 are rejected
                    // frame for frame by the Google TV Streamer's
                    // C2SoftAacDec (decoderErr 0x0005, 4360 times in one
                    // session) and by ffmpeg's own aac and aac_fixed
                    // decoders ("channel element 1.0 is not allocated",
                    // 189 of 189 frames).
                    val implied = pce.impliedChannelConfig
                    if (implied == 0) {
                        // Nothing lossless is left. Reordering the
                        // elements into Table 1.19 order is not an option:
                        // they are bit-packed, so re-serializing them
                        // needs a full AAC syntax parser to find each
                        // element's bit length, and ffmpeg's layout has no
                        // LFE element to reorder in the first place.
                        // Emitting the PCE inside the ASC instead (legal
                        // per 14496-3 1.6.2.1 when channelConfiguration is
                        // 0) does not help either: Chromium's
                        // SkipDecoderGASpecificConfig does
                        // RCHECK(channel_config_ != 0) before it would
                        // ever read a PCE (media/formats/mp4/aac.cc, main
                        // as of 2026-09-12), so a config-0 ASC fails the
                        // whole append with or without the element. Refuse
                        // by name so the failure points at the profile
                        // instead of casting silence.
                        throw UnsupportedCodecException(
                            codecName = "AAC with a ${pce.channels}-channel program_config_element " +
                                "layout that no channel_configuration describes",
                            isVideo = false,
                        )
                    }
                    payloadStart = p + headerLen + pce.lengthBytes
                    effectiveChanConfig = implied
                    if (!aacPceLogged) {
                        aacPceLogged = true
                        log("AAC PCE stripped: layout ${pce.channels} ch matches config $implied")
                    }
                }
            }
            if (payloadStart >= p + frameLen) {
                // A frame that is nothing but a PCE carries no audio.
                p += frameLen
                continue
            }
            if (aacFreqIndex < 0) {
                // The esds ASC is built from these three fields, and
                // Chromium's MP4StreamParser VALIDATES it (AAC::Parse,
                // then SkipDecoderGASpecificConfig) while ffprobe never
                // looks at it. Sanitize ONCE here so the ASC, the mp4a
                // sample entry and [audioFrameTicks] all come from the
                // same legal config; see [sanitizeAacConfig] for which
                // raw values cannot survive into an ASC and why.
                val safe = sanitizeAacConfig(
                    objectType = profile + 1, // ADTS profile is audioObjectType - 1
                    freqIndex = freqIndex,
                    chanConfig = effectiveChanConfig,
                )
                aacObjectType = safe.objectType
                aacFreqIndex = safe.freqIndex
                aacChannelConfig = safe.chanConfig
                audioFrameTicks = 1024L * TICKS_PER_SECOND / ADTS_SAMPLE_RATES[safe.freqIndex]
                maybeEmitInit()
            }
            if (frameLen > headerLen) {
                // The running audio clock stamps every frame, carried
                // frames included; the PES PTS only re-anchors it when
                // the two disagree by more than one frame. See
                // [audioRunPts] for what stamping the carried frame with
                // the new PES PTS cost on the device.
                val framePts = stampAudioFrame(pesAnchor, audioFrameTicks)
                audioRunPts = framePts + audioFrameTicks
                // Frames BEFORE the first video PRESENTATION time are
                // dropped, not clamped (2026-09-12 ffprobe run): audio
                // commonly leads the first kept video keyframe by tens of
                // ms, and the unsigned tfdt cannot express a negative
                // start. See [timelineBasePts] for why the gate is the
                // presentation time and not the decode time. The old
                // coerceAtLeast(0) in buildMoof pretended such a segment
                // started at 0, which shifted its whole audio track
                // forward by that lead and made it OVERLAP the next
                // segment's honest tfdt by the same amount (measured: 185
                // samples spanning 355200 ticks declared from 0, while
                // the next segment's audio tfdt was 348000). Chromium
                // gets a backwards audio append one segment in, which is
                // the IDLE/ERROR a second after the first playlist fetch.
                if (audioQueueOpen) {
                    queueAudio(data.copyOfRange(payloadStart, p + frameLen), framePts, audioFrameTicks)
                }
            }
            p += frameLen
        }
        if (p < data.size) adtsCarry = data.copyOfRange(p, data.size)
    }

    /** Queue one audio frame if it belongs on this generation's timeline.
     *
     *  The gate is the first video PRESENTATION time, not [timelineBase]:
     *  a frame below it is dropped outright, because Chromium drops and
     *  truncates audio that lands before a sequence-mode append's anchor
     *  and the truncated frame then costs the load a decoder swap (see
     *  [timelineBasePts] for the measured log). That leaves a generation's
     *  audio starting up to one frame after its video, which is the
     *  irreducible part of the splice seam: the rest of it, the whole 100+
     *  ms, was the missing tail that [flushGenerationTail] now emits. */
    private fun queueAudio(data: ByteArray, framePts: Long, durationTicks: Long) {
        if (!audioQueueOpen) return
        if (framePts < timelineBasePts) return
        audioQueue.add(AudioSample(data, framePts, durationTicks))
    }

    // ---- segmenter ----

    /** Audio config gate: the first ADTS header on the AAC path, the first
     *  syncframe header on the AC-3 / E-AC-3 path. */
    private val audioConfigReady: Boolean
        get() = when {
            audioPid < 0 -> true
            audioSource != null -> ac3Config != null
            else -> aacFreqIndex >= 0
        }

    /** Audio may queue once video anchored the timeline. Passthrough video
     *  anchors only after the init is out; transcoded video anchors on the
     *  source IDR while the encoder is still warming up, and the audio
     *  demuxed meanwhile belongs to the first segment. */
    private val audioQueueOpen: Boolean
        get() = timelineBasePts >= 0 && (initSent || videoMode == VideoMode.TRANSCODE)

    private fun maybeEmitInit() {
        if (initSent || !pmtSeen) return
        when (videoMode) {
            VideoMode.UNDECIDED -> return
            VideoMode.PASSTHROUGH -> if (sps == null || pps == null || (isHevc && vps == null)) return
            VideoMode.TRANSCODE -> if (transcodedFormat == null) return
        }
        if (!audioConfigReady) return
        listener.onInitSegments(
            video = buildInitSegment(Rendition.VIDEO_ONLY),
            audio = if (audioPid >= 0) buildInitSegment(Rendition.AUDIO_ONLY) else null,
        )
        initSent = true
    }

    /** Per-connection teardown hook the session calls once per ingest
     *  connection, before the next generation begins. Nothing to free
     *  since the audio transcode went away (everything here is plain
     *  Kotlin state, dropped with the instance), but the pending TAIL
     *  must still be emitted: see [flushGenerationTail]. */
    fun release() {
        flushGenerationTail()
        // Frames still inside the video transcoder are the outgoing
        // channel's last fraction of a second; they are dropped.
        videoTranscoder?.release()
        videoTranscoder = null
        released = true
    }

    /** Emit this generation's pending tail as one last segment, with both
     *  tracks ending together.
     *
     *  Measured at two channel changes (session22.txt 17:23:50 and
     *  17:25:14): the receiver reported a 122-123 ms hole in its buffered
     *  range at every splice, then flapped between seeking and BUFFERING
     *  and gap-jumped. Provider audio trails its video in the mux, so when
     *  the cut keyframe arrived the audio for the last ~120 ms of the
     *  outgoing segment had not been demuxed yet; it stayed queued for a
     *  segment that the channel change then threw away. Generation 7's
     *  last segment therefore declared 4.004 s of EXTINF while its audio
     *  covered only 3.901 s (gen 7 seg 10: t=40.04, audio 39.927 plus 188
     *  frames of 21.33 ms = 43.938 against a playlist end of 44.044), and
     *  Shaka placed generation 8 at the PLAYLIST position, 103 ms past
     *  where the audio actually stopped. Chromium reports a two-track
     *  SourceBuffer as the INTERSECTION of its tracks, so that shortfall
     *  plus the new generation's own start offset is the hole.
     *
     *  The fix is to declare only what both tracks carry: trim the video
     *  tail back to the audio end, emit the held audio with it, and let
     *  the segment's EXTINF be that common end. The next generation then
     *  starts where this one really stopped. */
    private fun flushGenerationTail() {
        // A pending cut takes effect first: the held samples belong to a
        // segment of their own, and the audio that the cut was waiting
        // for has either arrived by now or never will.
        if (pendingCutDts >= 0) {
            pendingCutDts = -1L
            finalizeSegment(cutDts = heldVideo.firstOrNull()?.dts ?: (videoQueue.lastOrNull()?.dts ?: 0L) + lastVideoDuration)
            videoQueue.addAll(heldVideo)
            heldVideo.clear()
        }
        if (!initSent || videoQueue.isEmpty()) return
        val videoEnd = videoQueue.last().dts + lastVideoDuration
        val audioEnd = audioQueue.lastOrNull()?.let { it.pts + it.durationTicks } ?: -1L
        var cut = videoEnd
        if (audioEnd in 0 until videoEnd) {
            // Video samples that begin at or after the audio end carry no
            // audio at all; dropping them is what keeps the declared
            // duration honest for BOTH tracks. One sample always stays so
            // the segment still opens on its keyframe.
            while (videoQueue.size > 1 && videoQueue.last().dts >= audioEnd) {
                videoQueue.removeAt(videoQueue.size - 1)
            }
            cut = maxOf(audioEnd, videoQueue.last().dts + 1)
        }
        val ticks = TICKS_PER_SECOND.toDouble()
        log(
            "splice tail: video end ${"%.3f".format((videoEnd - timelineBase) / ticks)} " +
                "audio end ${"%.3f".format(if (audioEnd < 0) -1.0 else (audioEnd - timelineBase) / ticks)} " +
                "trimmed ${"%.1f".format((videoEnd - cut) * 1000.0 / ticks)} ms",
        )
        finalizeSegment(cutDts = cut)
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
        // One sequence number per emitted CUT, shared by both renditions
        // of it: the demuxed playlists must number identically.
        sequenceNumber++
        val videoSegment = buildMediaSegment(videoQueue, durations, segAudio, Rendition.VIDEO_ONLY)
        val audioSegment = if (audioPid >= 0) {
            buildMediaSegment(videoQueue, durations, segAudio, Rendition.AUDIO_ONLY)
        } else {
            null
        }
        val durationTicks = cutDts - segStart
        // The audio rendition's EXTINF is what its frames actually cover;
        // a segment with no audio frame borrows the video span so the two
        // playlists stay aligned entry for entry.
        val audioDurationTicks = if (segAudio.isEmpty()) {
            durationTicks
        } else {
            segAudio.sumOf { it.durationTicks.coerceAtLeast(1L) }
        }
        // Timeline census for the proxy log (2026-09-12): the tfdt values
        // this segment will carry, plus where the PLAYLIST says it starts.
        // Reported before onMediaSegment so the session can log one line
        // per segment; emittedTicks is read before it is advanced, so the
        // generation's first segment reports 0.0.
        val ticks = TICKS_PER_SECOND.toDouble()
        listener.onSegmentComposition(
            videoSamples = videoQueue.size,
            audioSamples = segAudio.size,
            firstVideoDtsSeconds = (videoQueue.first().dts - timelineBase) / ticks,
            firstVideoPtsSeconds = (videoQueue.first().pts - timelineBase) / ticks,
            firstAudioPtsSeconds = segAudio.firstOrNull()
                ?.let { (it.pts - timelineBase) / ticks } ?: -1.0,
            segmentStartSeconds = emittedTicks / ticks,
        )
        // Audio census for the device log (2026-09-13): aexp is how many
        // frames this segment's own duration calls for, so a shortfall is
        // visible in the log without arithmetic. Logged only when the
        // segment misses by more than two frames: one is the unavoidable
        // boundary quantization and the generation's first segment also
        // drops the audio below the first video presentation time.
        // Anything larger is audio the receiver will underflow on.
        if (audioFrameTicks > 0 && audioPid >= 0) {
            val expected = durationTicks.toDouble() / audioFrameTicks
            if (kotlin.math.abs(expected - segAudio.size) > 2.0) {
                log(
                    "audio census: audio=${segAudio.size} aexp=${"%.1f".format(expected)} " +
                        "shortfall=${"%.1f".format(expected - segAudio.size)} frames",
                )
            }
        }
        emittedTicks += durationTicks
        videoQueue.clear()
        audioQueue.clear()
        audioQueue.addAll(keepAudio)
        listener.onMediaSegment(videoSegment, audioSegment, durationTicks, audioDurationTicks)
    }

    // ---- fMP4 writing ----

    private fun buildInitSegment(rendition: Rendition): ByteArray {
        val hasVideo = rendition == Rendition.VIDEO_ONLY
        val hasAudio = audioPid >= 0 && rendition == Rendition.AUDIO_ONLY
        val out = ByteArrayOutputStream(1024)
        out.write(box("ftyp", bytes("iso5"), u32(0), bytes("iso5"), bytes("iso6"), bytes("mp41")))
        val traks = ArrayList<ByteArray>()
        if (hasVideo) {
            val format = transcodedFormat
            if (format != null) {
                val hevc = format.codec == CastVideoOutputSpec.Codec.HEVC
                traks.add(
                    videoTrak(
                        format.width, format.height,
                        entryType = if (hevc) "hvc1" else "avc1",
                        configType = if (hevc) "hvcC" else "avcC",
                        config = format.config,
                    ),
                )
            } else if (isHevc) {
                val info = runCatching { CastSpsParser.parseHevcStreamInfo(sps!!) }.getOrNull()
                val hvcC = CastVideoCodecConfig.buildHvcc(vps!!, sps!!, pps!!)
                    ?: error("HEVC parameter sets do not form an hvcC")
                traks.add(
                    videoTrak(
                        info?.width ?: 1920, info?.height ?: 1080,
                        entryType = "hvc1", configType = "hvcC", config = hvcC,
                    ),
                )
            } else {
                val dims = runCatching { parseSpsDimensions(sps!!) }.getOrNull() ?: Pair(1280, 720)
                traks.add(
                    videoTrak(
                        dims.first, dims.second, entryType = "avc1", configType = "avcC",
                        config = CastVideoCodecConfig.avcCPayload(sps!!, pps!!),
                    ),
                )
            }
        }
        if (hasAudio) traks.add(audioTrak())
        val trexes = ArrayList<ByteArray>()
        if (hasVideo) trexes.add(trex(VIDEO_TRACK_ID))
        // sample_depends_on = 2 (independent) with sample_is_non_sync
        // clear: every audio frame IS a sync sample, the same flag value
        // videoTrun writes for a keyframe.
        if (hasAudio) trexes.add(trex(AUDIO_TRACK_ID, defaultSampleFlags = 0x02000000))
        // Track IDs never change between renditions, so an audio-only moov
        // still declares track 2 and nextTrackId 3; the tfhd in every
        // rendition's traf then names the same track it always did.
        val moov = box(
            "moov",
            mvhd(nextTrackId = if (hasAudio) 3 else 2),
            *traks.toTypedArray(),
            box("mvex", mehd(), *trexes.toTypedArray()),
        )
        out.write(moov)
        log("${logPrefix(rendition)}init: mehd 24h, liveness recorded")
        return out.toByteArray()
    }

    /** Rendition prefix for the per-init and per-segment log lines, so a
     *  demuxed cast can be read back from one log. */
    private fun logPrefix(rendition: Rendition): String = when (rendition) {
        Rendition.VIDEO_ONLY -> "video "
        Rendition.AUDIO_ONLY -> "audio "
    }

    private fun buildMediaSegment(
        video: List<VideoSample>,
        videoDurations: LongArray,
        audio: List<AudioSample>,
        rendition: Rendition,
    ): ByteArray {
        val wantVideo = rendition == Rendition.VIDEO_ONLY
        val wantAudio = rendition == Rendition.AUDIO_ONLY
        val videoBytes = if (wantVideo) video.sumOf { it.data.size } else 0
        val audioBytes = if (wantAudio) audio.sumOf { it.data.size } else 0

        // trun data_offset is from moof start; build the moof once with
        // placeholder offsets to learn its size, then rebuild with real
        // ones (sizes are offset-independent). The sequence number is
        // claimed once per cut by finalizeSegment, not per build pass and
        // not per rendition, so both renditions of one cut agree.
        var moof = buildMoof(video, videoDurations, audio, rendition, videoDataOffset = 0, audioDataOffset = 0)
        val moofSize = moof.size
        moof = buildMoof(
            video, videoDurations, audio, rendition,
            videoDataOffset = moofSize + 8,
            audioDataOffset = moofSize + 8 + videoBytes,
        )
        val out = ByteArrayOutputStream(moof.size + 8 + videoBytes + audioBytes)
        out.write(moof)
        out.write(u32(8 + videoBytes + audioBytes))
        out.write(bytes("mdat"))
        if (wantVideo) for (s in video) out.write(s.data)
        if (wantAudio) for (a in audio) out.write(a.data)
        return out.toByteArray()
    }

    private var sequenceNumber = 0

    private fun buildMoof(
        video: List<VideoSample>,
        videoDurations: LongArray,
        audio: List<AudioSample>,
        rendition: Rendition,
        videoDataOffset: Int,
        audioDataOffset: Int,
    ): ByteArray {
        val mfhd = fullBox("mfhd", 0, 0, u32(sequenceNumber))
        val trafs = ArrayList<ByteArray>()
        if (rendition == Rendition.VIDEO_ONLY) {
            trafs.add(
                box(
                    "traf",
                    // default-base-is-moof so data_offset is moof-relative (CMAF).
                    fullBox("tfhd", 0, 0x020000, u32(VIDEO_TRACK_ID)),
                    fullBox("tfdt", 1, 0, u64(video.first().dts - timelineBase)),
                    videoTrun(video, videoDurations, videoDataOffset),
                ),
            )
        }
        // The audio traf is emitted even when the cut span carried no
        // frame (a zero-sample trun), so the audio playlist has an entry at
        // every sequence number the video one has.
        if (rendition == Rendition.AUDIO_ONLY) {
            trafs.add(
                box(
                    "traf",
                    // flags 0x020000 default-base-is-moof, 0x000020
                    // default-sample-flags. The audio trun carries no
                    // per-sample flags, so without the tfhd default the
                    // receiver's parser does not treat our AAC frames as
                    // random access points and logs, once per frame
                    // (Google TV Streamer, 2026-09-12 14:22:20.237):
                    //
                    //   Bytestream with audio frame PTS 22666us and DTS
                    //   22666us indicated the frame is not a random access
                    //   point (key frame). All audio frames are expected
                    //   to be key frames for the current audio codec.
                    //
                    // The first packet after the load's seek is then a
                    // non-key packet, which is the "Failed to send audio
                    // packet for decoding" that costs every load a
                    // FFmpegAudioDecoder -> MediaCodecAudioDecoder swap
                    // and a reseek. 0x02000000 is sample_depends_on = 2
                    // ("does not depend on others") with
                    // sample_is_non_sync_sample clear, which is the truth
                    // for every AAC and AC-3 frame.
                    fullBox("tfhd", 0, 0x020020, u32(AUDIO_TRACK_ID), u32(0x02000000)),
                    // No clamp: samples earlier than timelineBasePts are
                    // dropped at queue time (see queueAudio), and
                    // timelineBasePts >= timelineBase, so this is always
                    // >= 0 and always the truth. Clamping here is what
                    // overlapped consecutive segments' audio.
                    // Falls back to the segment's video start when there
                    // is no audio frame to take it from, which is the only
                    // honest timestamp for an empty audio fragment.
                    fullBox(
                        "tfdt", 1, 0,
                        u64((audio.firstOrNull()?.pts ?: video.first().dts) - timelineBase),
                    ),
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
        // flags: data-offset | sample-duration | sample-size.
        val body = ByteArrayOutputStream(16 + audio.size * 8)
        body.write(u32(audio.size))
        body.write(u32(dataOffset))
        for (a in audio) {
            // Per-sample duration: AAC is a fixed 1024-sample frame but
            // E-AC-3 frames carry 1 to 6 blocks, so the sample's own
            // duration is the only coherent value for tfdt continuity.
            body.write(u32(a.durationTicks.coerceAtLeast(1L).toInt()))
            body.write(u32(a.data.size))
        }
        return fullBox("trun", 0, 0x000301, body.toByteArray())
    }

    // ---- moov internals ----

    /** A declared duration (here and in mehd) is what keeps Chromium out of
     *  low-delay rendering: media/formats/mp4/mp4_stream_parser.cc reads
     *  liveness as kRecorded when mvex/mehd fragment_duration > 0, or when
     *  mvhd duration is neither 0 nor the all-ones "unknown" sentinel, and
     *  kLive otherwise. kLive makes video_renderer_impl.cc pin
     *  min_buffered_frames_ to 1 with no underflow growth, which presented
     *  only ~46 of 60 frames on the Google TV Streamer. Version 1 so the
     *  24 hour duration fits: 86400 * 90000 ticks overflows 32 bits.
     */
    private fun mvhd(nextTrackId: Int): ByteArray = fullBox(
        "mvhd", 1, 0,
        u64(0), u64(0), // creation, modification
        u32(TICKS_PER_SECOND.toInt()), u64(DECLARED_DURATION_TICKS), // timescale, duration
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

    /** Video trak with an avc1 + avcC (passthrough, or re-encoded H.264)
     *  or hvc1 + hvcC (HEVC transcode) sample entry. hvc1, not hev1: the
     *  encoder output keeps its parameter sets out of the samples, so the
     *  record in the sample entry is the only copy, which is what hvc1
     *  means. */
    private fun videoTrak(width: Int, height: Int, entryType: String, configType: String, config: ByteArray): ByteArray {
        val configBox = box(configType, config)
        val avc1 = run {
            val body = ByteArrayOutputStream(96)
            body.write(ByteArray(6)); body.write(u16(1)) // reserved, data_reference_index
            body.write(ByteArray(16)) // pre_defined/reserved
            body.write(u16(width)); body.write(u16(height))
            body.write(u32(0x00480000)); body.write(u32(0x00480000)) // 72 dpi
            body.write(u32(0)); body.write(u16(1)) // reserved, frame_count
            body.write(ByteArray(32)) // compressorname
            body.write(u16(0x0018)); body.write(u16(0xFFFF)) // depth, pre_defined
            body.write(configBox)
            box(entryType, body.toByteArray())
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
        val ac3 = ac3Config
        val sampleEntry = if (ac3 != null) ac3SampleEntry(ac3) else aacSampleEntry()
        val volume = 0x0100
        return trak(
            trackId = AUDIO_TRACK_ID,
            width = 0, height = 0,
            volume = volume,
            handler = "soun", handlerName = "SoundHandler",
            mediaHeader = fullBox("smhd", 0, 0, u16(0), u16(0)),
            sampleEntry = sampleEntry,
        )
    }

    /**
     * The AAC config as it will appear in the AudioSpecificConfig, with
     * every raw ADTS value a two-byte ASC cannot carry replaced by the
     * closest legal one. [channels] is the real channel COUNT for the
     * mp4a sample entry, which is not the same number as
     * [chanConfig] above 6 (ISO 14496-3 Table 1.19: config 7 is 8
     * channels).
     */
    private class SafeAacConfig(
        val objectType: Int,
        val freqIndex: Int,
        val chanConfig: Int,
        val channels: Int,
    )

    /**
     * Clamp a raw ADTS header triple into something Chromium's AAC parser
     * accepts, logging once what was changed.
     *
     * Measured against a real Chromium MediaSource on 2026-09-12: appending
     * an init whose ASC breaks any of these rules fails the WHOLE append
     * ("Append: stream parsing failed. Data size=1116", pipeline_error 16),
     * and ffprobe reads the same file without complaint.
     *
     *  - channel_configuration 0 means the layout lives in a Program
     *    Config Element. Chromium's SkipDecoderGASpecificConfig requires a
     *    nonzero channel_config, so a 0 fails outright. Dispatcharr's
     *    ffmpeg AAC encoder emits exactly this whenever the AC-3 source
     *    layout is outside Table 1.19 (2.1, 3.1, 6.1, 7.0): it logs "Using
     *    a PCE to encode channel layout". Stereo is the honest default
     *    because the web receiver downmixes anyway.
     *  - sampling_frequency_index 13 and 14 are reserved, so Chromium
     *    resolves a 0 Hz rate and the decoder config is invalid; index 15
     *    means a 24-bit explicit rate follows, which a two-byte ASC has no
     *    room for, so Chromium reads off the end.
     *  - audioObjectType outside 1..4 is refused by AAC::Parse unless it
     *    is one of the HE/xHE signals we never synthesize.
     *
     * The old code latched the raw values and papered over them ONLY in
     * the sample entry, with getOrElse { 48_000 } and coerceAtLeast(1), so
     * the mp4a box advertised a plausible 48 kHz stereo track wrapped
     * around an unparseable esds. One sanitized config for both is the fix.
     */
    private fun sanitizeAacConfig(objectType: Int, freqIndex: Int, chanConfig: Int): SafeAacConfig {
        val safeObjectType = if (objectType in 1..4) objectType else 2 // AAC-LC
        val safeFreqIndex = if (freqIndex in ADTS_SAMPLE_RATES.indices) freqIndex else 3 // 48 kHz
        val safeChanConfig = if (chanConfig in 1..7) chanConfig else 2 // stereo
        if (!adtsConfigSanitized &&
            (safeObjectType != objectType || safeFreqIndex != freqIndex || safeChanConfig != chanConfig)
        ) {
            adtsConfigSanitized = true
            log(
                "ADTS audio config sanitized for the receiver: " +
                    "audioObjectType $objectType -> $safeObjectType, " +
                    "sampling_frequency_index $freqIndex -> $safeFreqIndex, " +
                    "channel_configuration $chanConfig -> $safeChanConfig " +
                    "(an AudioSpecificConfig cannot carry the original and Chromium " +
                    "rejects the whole init segment when it tries)",
            )
        }
        return SafeAacConfig(
            objectType = safeObjectType,
            freqIndex = safeFreqIndex,
            chanConfig = safeChanConfig,
            channels = AAC_CHANNEL_COUNTS[safeChanConfig],
        )
    }

    /** mp4a + esds for ADTS AAC passthrough (config from the first ADTS
     *  header; the frames themselves go in raw, headers stripped). */
    private fun aacSampleEntry(): ByteArray {
        // Both the mp4a AudioSampleEntry fields and the esds ASC come
        // from the SAME latched indices, which onAdtsAudioPes has already
        // validated as expressible in a two-byte ASC. They used to be
        // derived independently, with getOrElse { 48_000 } and
        // coerceAtLeast(1) sanitizing only the sample entry, so a bad
        // index produced a plausible-looking mp4a box wrapped around an
        // esds Chromium refuses to parse. Keeping one source means the
        // two can never disagree, and Chromium compares them.
        val sampleRate = ADTS_SAMPLE_RATES[aacFreqIndex]
        val channels = AAC_CHANNEL_COUNTS[aacChannelConfig]
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
        return box("mp4a", audioSampleEntryBody(channels, sampleRate, esds))
    }

    /**
     * ac-3 / ec-3 sample entry for AC-3 / E-AC-3 passthrough, carrying the
     * AC3SpecificBox (dac3) or EC3SpecificBox (dec3) built from the first
     * syncframe's bitstream fields (ETSI TS 102 366 Annex F). Without this
     * box Chromium's MSE rejects the init segment outright, which is the
     * whole reason the old code transcoded instead of passing through.
     */
    private fun ac3SampleEntry(info: CastAudioFramer.EsFrameInfo): ByteArray {
        val isEac3 = audioSource == CastAudioFramer.SourceCodec.EAC3
        val config = if (isEac3) dec3Box(info) else dac3Box(info)
        val type = if (isEac3) "ec-3" else "ac-3"
        return box(type, audioSampleEntryBody(info.channels, info.sampleRate, config))
    }

    /** The 28-byte AudioSampleEntry preamble every audio sample entry
     *  shares, followed by the codec configuration box. */
    private fun audioSampleEntryBody(channels: Int, sampleRate: Int, configBox: ByteArray): ByteArray {
        val body = ByteArrayOutputStream(64)
        body.write(ByteArray(6)); body.write(u16(1)) // reserved, data_reference_index
        body.write(ByteArray(8)) // reserved
        body.write(u16(channels.coerceIn(1, 8))); body.write(u16(16)) // channels, samplesize
        body.write(u32(0)) // pre_defined/reserved
        // AudioSampleEntry version 0 holds the rate as unsigned 16.16, so
        // the integer part cannot exceed 65535 and `rate shl 16` overflows
        // a 32-bit value above that (96000 shl 16 wrapped to 30464 Hz).
        // Clamp instead of wrapping; Chromium takes the real rate from the
        // ASC / dac3 config box beside this field anyway.
        body.write(u32((sampleRate.coerceAtMost(65_535) shl 16))) // 16.16 sample rate
        body.write(configBox)
        return body.toByteArray()
    }

    /** dac3: fscod(2) bsid(5) bsmod(3) acmod(3) lfeon(1) bit_rate_code(5)
     *  reserved(5) = exactly three bytes. */
    private fun dac3Box(info: CastAudioFramer.EsFrameInfo): ByteArray {
        val w = BitWriter()
        w.write(info.fscod, 2)
        w.write(info.bsid, 5)
        w.write(info.bsmod, 3)
        w.write(info.acmod, 3)
        w.write(info.lfeon, 1)
        w.write(ac3BitRateCode(info.bitrateKbps), 5)
        w.write(0, 5) // reserved
        return box("dac3", w.bytes())
    }

    /** dec3 for ONE independent substream with no dependent substreams:
     *  data_rate(13) num_ind_sub(3) then fscod(2) bsid(5) reserved(1)
     *  asvc(1) bsmod(3) acmod(3) lfeon(1) reserved(3) num_dep_sub(4)
     *  reserved(1) = five bytes. A multi-substream Atmos feed is still
     *  described by its first substream, which is the decodable core. */
    private fun dec3Box(info: CastAudioFramer.EsFrameInfo): ByteArray {
        val w = BitWriter()
        w.write(info.bitrateKbps.coerceIn(0, 8191), 13) // data_rate, kbit/s
        w.write(0, 3) // num_ind_sub - 1
        w.write(info.fscod, 2)
        w.write(info.bsid, 5)
        w.write(0, 1) // reserved
        w.write(0, 1) // asvc
        w.write(info.bsmod, 3)
        w.write(info.acmod, 3)
        w.write(info.lfeon, 1)
        w.write(0, 3) // reserved
        w.write(0, 4) // num_dep_sub
        w.write(0, 1) // reserved (no chan_loc without dependent substreams)
        return box("dec3", w.bytes())
    }

    /** Nearest A/52 bit_rate_code for [kbps] (table 5.18 order). */
    private fun ac3BitRateCode(kbps: Int): Int {
        val index = AC3_BIT_RATE_CODES.indexOfFirst { it >= kbps }
        return if (index < 0) AC3_BIT_RATE_CODES.size - 1 else index
    }

    /** MSB-first bit packer for the tiny codec-config boxes above. */
    private class BitWriter {
        private val out = ByteArrayOutputStream(8)
        private var current = 0
        private var bitsUsed = 0

        fun write(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                current = (current shl 1) or ((value shr i) and 1)
                bitsUsed++
                if (bitsUsed == 8) {
                    out.write(current)
                    current = 0
                    bitsUsed = 0
                }
            }
        }

        fun bytes(): ByteArray {
            if (bitsUsed > 0) {
                out.write(current shl (8 - bitsUsed))
                current = 0
                bitsUsed = 0
            }
            return out.toByteArray()
        }
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

    /**
     * [defaultSampleFlags] matters only for a track whose trun omits
     * per-sample flags, which is exactly the audio track ([audioTrun]
     * writes duration and size only). The old shared 0x00010000 default
     * sets sample_is_non_sync_sample on EVERY audio sample, so the audio
     * track advertised no random access point at all and a Chromium MSE
     * append had nothing to start from (caught by ffprobe-backed test
     * 2026-09-12). Video keeps that default because [videoTrun] overrides
     * the flags per sample anyway.
     */
    private fun mehd(): ByteArray = fullBox("mehd", 1, 0, u64(DECLARED_DURATION_TICKS))

    private fun trex(trackId: Int, defaultSampleFlags: Int = 0x00010000): ByteArray = fullBox(
        "trex", 0, 0,
        u32(trackId), u32(1), u32(0), u32(0), u32(defaultSampleFlags),
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

    // ---- SPS dimensions (tkhd/avc1 sizing; the full parse lives in
    // CastSpsParser, shared with the video plan) ----

    private fun parseSpsDimensions(spsNal: ByteArray): Pair<Int, Int> {
        val info = CastSpsParser.parseSpsInfo(spsNal)
        return Pair(info.width, info.height)
    }
}

/** A/52 table 5.18 bit rates in kbit/s, indexed by bit_rate_code. */
private val AC3_BIT_RATE_CODES = intArrayOf(
    32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640,
)

/**
 * Channel COUNT per ISO 14496-3 Table 1.19 channel_configuration, indexed
 * by the configuration value. Config 7 is 7.1, which is EIGHT channels,
 * so the count is not interchangeable with the configuration number. Slot
 * 0 holds 2 because a sanitized config never stays 0.
 */
private val AAC_CHANNEL_COUNTS = intArrayOf(2, 1, 2, 3, 4, 5, 6, 8)

/**
 * The inverse of [AAC_CHANNEL_COUNTS]: the channel_configuration that
 * declares [count] channels, or 0 when Table 1.19 has no entry for that
 * count (7 channels is the only gap, since config 7 is 7.1). 0 is then
 * handed to the config sanitizer, which substitutes stereo.
 */
private val ADTS_SAMPLE_RATES = intArrayOf(
    96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050,
    16_000, 12_000, 11_025, 8_000, 7_350,
)
