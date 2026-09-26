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
 * the DEMUXED resource shapes:
 *
 *   /demuxed.m3u8   master: EXT-X-MEDIA audio rendition + EXT-X-STREAM-INF
 *   /video.m3u8     video-only media playlist (vinit / vseg)
 *   /audio.m3u8     audio-only media playlist (ainit / aseg)
 *   /vinit<G>.mp4   video-only moov for generation G
 *   /ainit<G>.mp4   audio-only moov for generation G
 *   /vseg<N>.m4s    video traf only, sequence N
 *   /aseg<N>.m4s    audio traf only, sequence N
 *
 * Demuxed and nothing else since 2026-09-13: measured on the Google TV
 * Streamer's Cast runtime,
 * isTypeSupported("video/mp4; codecs=\"avc1.64002A,ac-3\"") is false and
 * so is isTypeSupported("video/mp4; codecs=\"ac-3\""), but
 * isTypeSupported("audio/mp4; codecs=\"ac-3\"") is TRUE. A single muxed
 * rendition could therefore only ever declare AAC, which is what used to
 * force the server-side AAC output profile; a separate audio rendition
 * appended into its own SourceBuffer is how Emby's web receiver reaches
 * MediaCodecAudioDecoder. The old muxed endpoints (/master.m3u8,
 * /live.m3u8, /init<G>.mp4, /seg<N>.m4s) are gone; nothing loaded them.
 *
 * Every response carries `Access-Control-Allow-Origin: *` because the
 * receiver page's origin is Google's, not ours, and Chromium enforces
 * CORS on MSE fetches.
 *
 * Segment store: an in-memory ring of the last [RING_SIZE] segments
 * (about 3 s each; 16 advertised plus a short tail). Generations
 * exist because a reconnect or channel change restarts the remuxer: the
 * new ingest gets a fresh init segment and its first segment is flagged
 * as a playlist discontinuity, so the receiver resets its timeline
 * instead of chasing a clock that jumped.
 *
 * One channel at a time: [beginGeneration] (channel change or ingest
 * reconnect) keeps the listening socket AND the ring, so the receiver's
 * next playlist poll sees a window that still lists the old-generation
 * segments it was promised, then a discontinuity into the new
 * generation at the same URL. Sequence numbers are claimed only at
 * publish time, so a splice can never leave a numbering gap.
 */
class CastHlsProxyServer(
    private val log: (String) -> Unit,
) {
    companion object {
        /** Segments advertised in the playlist: the whole ring (iOS
         *  incident 2026-09-25, bursty ingest). A receiver is the only
         *  client of this server, so the window is always the receiver's
         *  window; the stated [holdBackSeconds] needs room inside it. */
        internal const val WINDOW_SIZE = 16

        /** Segments retained in memory: the 16-segment window (~40-48 s, at
         *  least the [HOLD_BACK_CEILING_S] hold-back plus room to re-fetch
         *  through a stall) and a 3-segment tail so a receiver that is a
         *  poll behind can still fetch what the previous playlist
         *  advertised. A 16 Mbps feed is ~6 MB per ~3 s cut, ~115 MB
         *  worst case. */
        internal const val RING_SIZE = WINDOW_SIZE + 3

        /** Hold-back floor and ceiling in seconds (iOS 20413d8 formula). */
        internal const val HOLD_BACK_FLOOR_S = 8.0
        internal const val HOLD_BACK_CEILING_S = 20.0

        /** A publish that lands more than this past its own media duration
         *  after the previous one is a starved cut (iOS "starved closure"). */
        private const val STARVED_SLACK_S = 0.6

        /** Nominal segment target (the remuxer cuts on the first keyframe
         *  after ~3 s). */
        private const val TARGET_SEGMENT_S = 3.0

        /** Bound on holding a segment GET that names a sequence the ingest
         *  has not published yet (the receiver racing the live edge);
         *  segments land every ~3 s, so 6 s covers a slow cut without
         *  pinning threads. */
        private const val NEXT_SEGMENT_WAIT_MS = 6_000L

        /** How far past the newest published sequence a fetch may name and
         *  still be held rather than 404ed. A 404 is not a harmless retry
         *  for this receiver: Shaka drops the segment and re-syncs to the
         *  live edge, which SKIPS segments, and a skipped segment in MSE
         *  'sequence' AppendMode leaves a sub-frame-invisible hole in the
         *  buffered range that the video renderer never crosses (measured
         *  in Chromium: 0.147 s, see the review notes). Two segments of
         *  slack costs nothing and removes the trigger. */
        private const val MAX_FUTURE_SEGMENTS = 2

        /** Requests logged verbatim at the start of a session before the
         *  rate limit kicks in (enough to cover master + playlist + init
         *  + the first handful of segments, which is the whole startup
         *  handshake a failed cast has to be diagnosed from). */
        private const val VERBOSE_REQUESTS = 12

        /** After [VERBOSE_REQUESTS], only non-200 responses and every
         *  Nth request are logged, so a long cast does not flood. */
        private const val REQUEST_LOG_EVERY = 50

        private const val MIME_PLAYLIST = "application/vnd.apple.mpegurl"
        private const val MIME_MP4 = "video/mp4"
        private const val MIME_AUDIO_MP4 = "audio/mp4"
        private const val MIME_SEGMENT = "video/iso.segment"
    }

    /** Which rendition of a cut a request names. */
    private enum class Rendition { VIDEO, AUDIO }

    private class SegmentEntry(
        val seq: Int,
        val generation: Int,
        /** Video span of the cut in 90 kHz ticks: the video rendition's
         *  EXTINF. */
        val durationTicks: Long,
        val discontinuity: Boolean,
        /** The two renditions of the same cut, under the one sequence
         *  number. [audioData] is null only for a video-only mux. */
        val videoData: ByteArray,
        val audioData: ByteArray?,
        /** The audio rendition's own EXTINF; within one audio frame of
         *  [durationTicks]. */
        val audioDurationTicks: Long,
    )

    /** Guards the store; also the monitor held segment fetches wait on
     *  (Object, not Any, for wait/notifyAll). */
    private val lock = Object()
    private val ring = ArrayDeque<SegmentEntry>()
    /** False after [stop]; wakes and fails any held segment fetch. */
    private var storeOpen = true
    /** Init segments per generation, evicted with the last ring entry that
     *  references them. */
    private val videoInits = HashMap<Int, ByteArray>()
    private val audioInits = HashMap<Int, ByteArray>()
    private var nextSeq = 0
    private var generation = 0
    /** First segment committed after [beginGeneration] gets the
     *  discontinuity flag (reconnect splice or channel change). */
    private var pendingDiscontinuity = false
    /** EXT-X-DISCONTINUITY-SEQUENCE: count of flagged segments that have
     *  fully rolled out of the ring. */
    private var discontinuitySequence = 0

    // ---- receiver runway (iOS incident 2026-09-25) ----
    //
    // The iOS receiver played a bursty Dispatcharr feed (11-13 two-second
    // silences a minute at 8 Mbps) with too little runway and rebuffered.
    // This server was already unpaced (a segment is listed the moment it is
    // cut); it now keeps a 16-segment ring and window and states a hold-back
    // grown for a bursty ingest and a high bitrate. Guarded by [lock].

    /** Wall time (ms) of the previous publish in the current generation. */
    private var lastPublishWallMs = 0L
    /** Starved cuts (wall ms, gap s) in the last 60 s. */
    private val recentStarvations = ArrayDeque<Pair<Long, Double>>()
    /** Stated hold-back, seconds; monotonic until [stop] (never shrinks
     *  within a session, channel changes included). 0 = not computed yet. */
    @Volatile var holdBackSeconds: Double = 0.0
        private set
    /** Starved cuts since [start], monotonic, for the link line. */
    @Volatile var starvedCutsTotal: Int = 0
        private set

    // ---- link counters (iOS incident 2026-09-25, Apple 85ef563) ----

    /** Response body bytes sent since [start]. */
    val servedBytesTotal = java.util.concurrent.atomic.AtomicLong(0)
    /** Address of the last client served. */
    @Volatile var lastPeer: String? = null
        private set
    /** Newest video sequence the receiver fetched successfully (-1 = none). */
    @Volatile var highestVideoSeq: Int = -1
        private set

    /** Published segments after [seq] (the receiver's newest video fetch)
     *  and their media seconds: the runway the receiver has not pulled yet. */
    fun runwayAfter(seq: Int): Pair<Int, Double> = synchronized(lock) {
        val ahead = ring.filter { it.seq > seq }
        ahead.size to ahead.sumOf { it.durationTicks } / TsToFmp4Remuxer.TICKS_PER_SECOND.toDouble()
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    /** Diagnostic: log the receiver's FIRST playlist fetch loudly; it is
     *  the proof the Cast device reached the phone at all. */
    private val firstPlaylistServed = AtomicBoolean(false)
    /** Diagnostic: dump the master and media playlist TEXT once each, so
     *  a failing cast can be read back from the log without the device. */
    private val demuxedMasterTextLogged = AtomicBoolean(false)
    private val videoPlaylistTextLogged = AtomicBoolean(false)
    private val audioPlaylistTextLogged = AtomicBoolean(false)
    /** Requests served this session, for the log rate limit. */
    private val requestsServed = java.util.concurrent.atomic.AtomicInteger(0)

    private val _segmentsInGeneration = MutableStateFlow(0)
    /** Segments committed since the last [beginGeneration]. */
    val segmentsInGeneration: StateFlow<Int> = _segmentsInGeneration.asStateFlow()

    private val _mediaTicksInGeneration = MutableStateFlow(0L)
    /** MEDIA DURATION committed since the last [beginGeneration], in 90 kHz
     *  ticks. The load gate is a duration, not a segment count: our cuts
     *  land on keyframes, not on the 3 s target, and on a real broadcast
     *  feed the first three segments were 5.005 s, 4.338 s and 3.170 s
     *  (iPhone proxy log, 2026-09-12 14:22:19.361), so a 3-segment gate
     *  made the user wait 11.5 s for 12.5 s of media where 9 s would do.
     *  Logan: "it also takes a while for that single frame to appear". */
    val mediaTicksInGeneration: StateFlow<Long> = _mediaTicksInGeneration.asStateFlow()

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
        synchronized(lock) { storeOpen = true }
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
            videoInits.clear()
            audioInits.clear()
            _segmentsInGeneration.value = 0
            _mediaTicksInGeneration.value = 0
            storeOpen = false
            recentStarvations.clear()
            lastPublishWallMs = 0L
            holdBackSeconds = 0.0
            starvedCutsTotal = 0
            lock.notifyAll()
        }
        firstPlaylistServed.set(false)
        demuxedMasterTextLogged.set(false)
        videoPlaylistTextLogged.set(false)
        audioPlaylistTextLogged.set(false)
        requestsServed.set(0)
        servedBytesTotal.set(0)
        lastPeer = null
        highestVideoSeq = -1
        playlistFetches.set(0)
        segmentFetches.set(0)
    }

    val isRunning: Boolean get() = running.get()

    /** Playlist (.m3u8) and media segment requests since [start]: the
     *  sender's stale-receiver watchdog compares them across a load. */
    val playlistFetches = java.util.concurrent.atomic.AtomicInteger(0)
    val segmentFetches = java.util.concurrent.atomic.AtomicInteger(0)

    /** Requests served since [start], for the keepalive log lines. */
    val requestCount: Int get() = requestsServed.get()

    /** Newest committed sequence number, or -1 with an empty ring. */
    val lastSequence: Int get() = synchronized(lock) { nextSeq - 1 }

    // ---- store (called from the ingest thread) ----

    /** Start a new ingest generation (channel change or same-channel
     *  reconnect). The ring is deliberately NOT cleared: the receiver's
     *  cached playlist still promises the old-generation segments, and
     *  wiping them mid-splice is exactly the 404 -> Shaka 1001 ->
     *  CLIP_ENDED reload this server exists to avoid. Old segments (and
     *  their init) age out of the ring naturally; the discontinuity tag
     *  plus the new EXT-X-MAP cover the timeline and codec change, and
     *  [addSegment]'s generation gate keeps a stale ingest from ever
     *  claiming a sequence number, so numbering stays gap-free. */
    fun beginGeneration(): Int = synchronized(lock) {
        val oldGen = generation
        generation++
        pendingDiscontinuity = ring.isNotEmpty()
        _segmentsInGeneration.value = 0
        _mediaTicksInGeneration.value = 0
        // A reconnect's first cut is not a starved cut of the old feed.
        lastPublishWallMs = 0L
        if (oldGen > 0) {
            log(
                "splice oldGen=$oldGen newGen=$generation " +
                    "lastSeq=${nextSeq - 1} firstNewSeq=$nextSeq",
            )
        }
        generation
    }

    /** Init segments for [gen]. [audio] is null for a video-only mux, in
     *  which case the master carries no audio rendition. */
    fun setInitSegments(gen: Int, video: ByteArray, audio: ByteArray?) = synchronized(lock) {
        videoInits[gen] = video
        if (audio != null) audioInits[gen] = audio else audioInits.remove(gen)
    }

    fun addSegment(
        gen: Int,
        videoData: ByteArray,
        audioData: ByteArray?,
        durationTicks: Long,
        audioDurationTicks: Long = durationTicks,
    ) {
        synchronized(lock) {
            if (gen != generation) return // stale ingest racing a channel change
            val entry = SegmentEntry(
                seq = nextSeq++,
                generation = gen,
                durationTicks = durationTicks,
                discontinuity = pendingDiscontinuity,
                videoData = videoData,
                audioData = audioData,
                audioDurationTicks = audioDurationTicks,
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
                    videoInits.remove(evicted.generation)
                    audioInits.remove(evicted.generation)
                }
            }
            _segmentsInGeneration.value += 1
            _mediaTicksInGeneration.value += durationTicks
            val nowMs = System.currentTimeMillis()
            val durS = durationTicks / TsToFmp4Remuxer.TICKS_PER_SECOND.toDouble()
            if (lastPublishWallMs > 0L) {
                val gap = (nowMs - lastPublishWallMs) / 1000.0
                if (gap > durS + STARVED_SLACK_S) {
                    recentStarvations.addLast(nowMs to gap)
                    starvedCutsTotal++
                }
            }
            lastPublishWallMs = nowMs
            refreshHoldBackLocked(nowMs)
            // Wake any held fetch for the sequence just published.
            lock.notifyAll()
        }
    }

    /**
     * Chooses the stated hold-back (iOS 20413d8, same formula): max(3 x
     * target, 8 s); +4 s when the last 60 s had more than 6 starved cuts, +8 s
     * above 12; at least the worst recent gap plus one target; +4 s above
     * 10 Mbps; capped at 20 s. Only ever grows within a session. Logs when
     * it grows. Caller holds [lock].
     */
    private fun refreshHoldBackLocked(nowMs: Long) {
        while (recentStarvations.isNotEmpty() && nowMs - recentStarvations.first().first > 60_000L) {
            recentStarvations.removeFirst()
        }
        val stalls = recentStarvations.size
        val worst = recentStarvations.maxOfOrNull { it.second } ?: 0.0
        val target = maxOf(TARGET_SEGMENT_S, targetSecondsLocked(ring.takeLast(WINDOW_SIZE)).toDouble())
        val kbps = ringKbpsLocked()
        var hb = maxOf(3 * target, HOLD_BACK_FLOOR_S)
        val why = StringBuilder(
            String.format(
                java.util.Locale.US, "3 x target %.0f s = %.0f s, floor %.0f s",
                target, 3 * target, HOLD_BACK_FLOOR_S,
            ),
        )
        if (stalls > 6) {
            val add = if (stalls > 12) 8.0 else 4.0
            hb += add
            why.append(String.format(java.util.Locale.US, "; bursty ingest %d stalls in 60 s +%.0f s", stalls, add))
        }
        if (worst > 0 && worst + target > hb) {
            hb = worst + target
            why.append(String.format(java.util.Locale.US, "; worst gap %.1f s + target", worst))
        }
        if (kbps > 10_000) {
            hb += 4
            why.append("; $kbps kbps > 10 Mbps +4 s")
        }
        hb = minOf(HOLD_BACK_CEILING_S, hb)
        if (hb <= holdBackSeconds + 0.4) return
        holdBackSeconds = hb
        log(
            String.format(
                java.util.Locale.US,
                "hold-back %.1f s (%s; ceiling %.0f s); playlist unpaced, RAM ring %d segs",
                hb, why, HOLD_BACK_CEILING_S, RING_SIZE,
            ),
        )
    }

    /** Bitrate of the ring (video + audio), kbps. Caller holds [lock]. */
    private fun ringKbpsLocked(): Int {
        val bytes = ring.sumOf { it.videoData.size.toLong() + (it.audioData?.size ?: 0) }
        val secs = ring.sumOf { it.durationTicks } / TsToFmp4Remuxer.TICKS_PER_SECOND.toDouble()
        return if (secs > 0) (bytes * 8 / secs / 1000).toInt() else 0
    }

    private fun targetSecondsLocked(window: List<SegmentEntry>): Int =
        window.maxOfOrNull {
            ceil(
                maxOf(it.durationTicks, it.audioDurationTicks) /
                    TsToFmp4Remuxer.TICKS_PER_SECOND.toDouble(),
            ).toInt()
        }?.coerceAtLeast(1) ?: 4

    /** Init segment for [gen], or null when no longer retained. */
    internal fun videoInitSegment(gen: Int): ByteArray? = synchronized(lock) { videoInits[gen] }

    internal fun audioInitSegment(gen: Int): ByteArray? = synchronized(lock) { audioInits[gen] }

    /**
     * Segment [seq]'s bytes. A fetch naming a sequence the ingest has not
     * published yet, up to [MAX_FUTURE_SEGMENTS] past the newest one, is
     * held up to [timeoutMs] instead of 404ing; anything already evicted
     * from the ring or further in the future fails immediately.
     */
    internal fun awaitSegment(seq: Int, timeoutMs: Long = NEXT_SEGMENT_WAIT_MS): ByteArray? =
        awaitSegment(seq, Rendition.VIDEO, timeoutMs)

    private fun awaitSegment(
        seq: Int,
        rendition: Rendition,
        timeoutMs: Long = NEXT_SEGMENT_WAIT_MS,
    ): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (true) {
                ring.firstOrNull { it.seq == seq }?.let {
                    return when (rendition) {
                        Rendition.VIDEO -> it.videoData
                        Rendition.AUDIO -> it.audioData
                    }
                }
                if (!storeOpen || seq < nextSeq || seq > nextSeq + MAX_FUTURE_SEGMENTS) return null
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                try {
                    lock.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    // ---- playlist ----

    /**
     * DEMUXED master playlist: the URL the sender must load (see
     * CastHlsProxySession.demuxedMasterUrl). Two renditions, one
     * SourceBuffer each, so the audio can declare ac-3 / ec-3 honestly on
     * a receiver that answers isTypeSupported false for any muxed
     * video/mp4 carrying those codecs but TRUE for audio/mp4 with them.
     *
     * The audio codec comes from the AUDIO init's own sample entry and the
     * video codec from the VIDEO init's avcC, so neither can disagree with
     * the bytes. CLOSED-CAPTIONS=NONE exists for exactly one reason: with a
     * media-only playlist Shaka turns on closed-caption detection and runs
     * Mp4CeaParser over every video segment; that parser walks our segments
     * as if the whole mdat were video NALs and dies with
     * BUFFER_READ_OUT_OF_BOUNDS (Shaka Error 3000), killing playback tens
     * of seconds in (device-verified on a Google TV Streamer, reproduced on
     * desktop Shaka 4.9.2 debug with the symbolized stack). NONE disables
     * the detection entirely (HlsParser.getClosedCaptions_).
     */
    /** Whether the receiver answered yes to isTypeSupported for
     *  avc1.64002A (level 4.2). False also covers caps never received. */
    @Volatile private var receiverH264Level42 = false
    @Volatile private var levelCapLogged = false

    /** Set per channel start from the sender's measured receiver caps. */
    fun setReceiverH264Level42(supported: Boolean) {
        receiverH264Level42 = supported
        levelCapLogged = false
    }

    /** A Chromecast Ultra decodes 1080p60 but its MSE answers no to the
     *  level 4.2 string (Shaka 4032 on avc1.64002A, 2026-09-26). When the
     *  receiver did not say yes to 4.2, declare level 4.0 (0x28) for any
     *  higher level, keeping the profile and constraint bytes. */
    private fun capAvcLevel(codec: String): String {
        if (receiverH264Level42 || codec.length != 11 || !codec.startsWith("avc1.")) return codec
        val level = codec.substring(9).toIntOrNull(16) ?: return codec
        if (level <= 0x28) return codec
        val capped = codec.substring(0, 9) + "28"
        if (!levelCapLogged) {
            levelCapLogged = true
            log("[CAST-HLS] master declares $capped (stream is $codec; receiver answered no to level 4.2)")
        }
        return capped
    }

    internal fun demuxedMasterPlaylistText(): String {
        val videoInit = synchronized(lock) { videoInits[generation] }
        val audioInit = synchronized(lock) { audioInits[generation] }
        val videoCodec = videoInit?.let { avcCodecString(it) }?.let { capAvcLevel(it) } ?: "avc1.640028"
        val audioCodec = audioInit?.let { audioCodecString(it) }
        val sb = StringBuilder(320)
        sb.append("#EXTM3U\n")
        if (audioCodec != null) {
            sb.append(
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"Main\"," +
                    "DEFAULT=YES,AUTOSELECT=YES,URI=\"audio.m3u8\"\n",
            )
        }
        sb.append("#EXT-X-STREAM-INF:BANDWIDTH=12000000,CODECS=\"").append(videoCodec)
        if (audioCodec != null) sb.append(',').append(audioCodec)
        sb.append('"')
        if (audioCodec != null) sb.append(",AUDIO=\"aud\"")
        sb.append(",CLOSED-CAPTIONS=NONE\n")
        sb.append("video.m3u8\n")
        return sb.toString()
    }

    /** RFC 6381 audio codec string from the init segment's audio sample
     *  entry: ac-3 / ec-3 for the passthrough paths, mp4a.40.2 for AAC-LC
     *  (the only AAC profile an ADTS IPTV mux carries in practice). */
    private fun audioCodecString(init: ByteArray): String? = when {
        containsBoxType(init, "ac-3") -> "ac-3"
        containsBoxType(init, "ec-3") -> "ec-3"
        containsBoxType(init, "mp4a") -> "mp4a.40.2"
        else -> null
    }

    private fun containsBoxType(data: ByteArray, type: String): Boolean {
        val t = type.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..data.size - 4) {
            for (j in 0 until 4) if (data[i + j] != t[j]) continue@outer
            return true
        }
        return false
    }

    /** avc1.PPCCLL from the avcC box inside an init segment (profile,
     *  constraint flags, level right after the configuration version). */
    private fun avcCodecString(init: ByteArray): String? {
        for (i in 0..init.size - 8) {
            if (init[i] == 'a'.code.toByte() && init[i + 1] == 'v'.code.toByte() &&
                init[i + 2] == 'c'.code.toByte() && init[i + 3] == 'C'.code.toByte() &&
                i + 8 < init.size
            ) {
                val p = init[i + 5].toInt() and 0xFF
                val c = init[i + 6].toInt() and 0xFF
                val l = init[i + 7].toInt() and 0xFF
                return String.format(java.util.Locale.US, "avc1.%02X%02X%02X", p, c, l)
            }
        }
        return null
    }

    /** The video-only media playlist the demuxed master's STREAM-INF
     *  points at. */
    internal fun videoPlaylistText(): String = playlistText(Rendition.VIDEO)

    /** The audio-only media playlist the demuxed master's EXT-X-MEDIA
     *  points at. Identical sequence numbering, target duration and
     *  discontinuity tags to [videoPlaylistText]; only the EXTINF values
     *  differ, by less than one audio frame. */
    internal fun audioPlaylistText(): String = playlistText(Rendition.AUDIO)

    private fun playlistText(rendition: Rendition): String = synchronized(lock) {
        val initPrefix = if (rendition == Rendition.VIDEO) "vinit" else "ainit"
        val segPrefix = if (rendition == Rendition.VIDEO) "vseg" else "aseg"
        val window = ring.takeLast(WINDOW_SIZE)
        val sb = StringBuilder(512)
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:7\n")
        // Deliberately the max over BOTH renditions' spans, so the two
        // demuxed playlists advertise the SAME target duration even though
        // their EXTINF values differ by up to an audio frame.
        val targetSeconds = targetSecondsLocked(window)
        sb.append("#EXT-X-TARGETDURATION:").append(targetSeconds).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(window.firstOrNull()?.seq ?: nextSeq).append('\n')
        if (discontinuitySequence > 0) {
            sb.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discontinuitySequence).append('\n')
        }
        // Stated hold-back (iOS incident 2026-09-25): never deeper than the
        // window minus one target, so the join point stays inside what is
        // advertised. Shaka takes HOLD-BACK as the presentation delay and
        // EXT-X-START as the start offset unless the receiver page configures
        // its own; both are harmless to a player that ignores them.
        if (holdBackSeconds > 0 && window.isNotEmpty()) {
            val room = window.sumOf { it.durationTicks } / TsToFmp4Remuxer.TICKS_PER_SECOND.toDouble() -
                targetSeconds
            val hb = minOf(holdBackSeconds, maxOf(3.0 * targetSeconds, room))
            sb.append(String.format(java.util.Locale.US, "#EXT-X-SERVER-CONTROL:HOLD-BACK=%.3f\n", hb))
            sb.append(String.format(java.util.Locale.US, "#EXT-X-START:TIME-OFFSET=-%.3f,PRECISE=NO\n", hb))
        }
        var lastGen = -1
        for (seg in window) {
            // The tag stays attached to its segment for as long as the
            // segment is in the window; DISCONTINUITY-SEQUENCE above only
            // accounts for flagged segments that have rolled out.
            if (seg.discontinuity) sb.append("#EXT-X-DISCONTINUITY\n")
            // NO EXT-X-PROGRAM-DATE-TIME, deliberately (added 1cc2fcf5,
            // removed the same day). The anchor it was derived from is the
            // wall clock at the moment a segment is PUBLISHED, which is one
            // whole segment later than the media that segment starts with,
            // so every stamp ran a segment ahead of the media. Shaka treats
            // a PDT as the authority for segment POSITIONS
            // (hls_parser.js createSegments_ -> SegmentReference.syncAgainst,
            // plus setInitialProgramDateTime in determineDuration_), so its
            // live window slid a segment past the media in the buffer: the
            // receiver reported seek=[51.368-52.373] with
            // buffered=[46.537-51.593] and a playhead at 51.357, permanently
            // outside its own seek range and permanently BUFFERING. Without
            // the tag Shaka positions segments by accumulated EXTINF from
            // media time 0, which is exactly where our tfdt timestamps put
            // them, and the same playlist reached PLAYING (session10).
            if (seg.generation != lastGen) {
                sb.append("#EXT-X-MAP:URI=\"").append(initPrefix)
                    .append(seg.generation).append(".mp4\"\n")
                lastGen = seg.generation
            }
            val ticks = if (rendition == Rendition.AUDIO) seg.audioDurationTicks else seg.durationTicks
            val seconds = ticks / TsToFmp4Remuxer.TICKS_PER_SECOND.toDouble()
            sb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", seconds)).append(",\n")
            sb.append(segPrefix).append(seg.seq).append(".m4s\n")
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
            // Wait time is only meaningful for a segment fetch that was
            // held at the live edge; it is the single most useful number
            // when the receiver errors out (a long wait means the ingest,
            // not the receiver, is the problem).
            var waitMs = -1L
            when {
                path == "/demuxed.m3u8" -> {
                    val text = demuxedMasterPlaylistText()
                    body = text.toByteArray(Charsets.UTF_8)
                    mime = MIME_PLAYLIST
                    if (demuxedMasterTextLogged.compareAndSet(false, true)) {
                        log("demuxed master playlist: ${escaped(text)}")
                    }
                }
                path == "/video.m3u8" -> {
                    val text = videoPlaylistText()
                    body = text.toByteArray(Charsets.UTF_8)
                    mime = MIME_PLAYLIST
                    if (firstPlaylistServed.compareAndSet(false, true)) {
                        log("receiver fetched the playlist for the first time (${sock.inetAddress?.hostAddress})")
                    }
                    if (videoPlaylistTextLogged.compareAndSet(false, true)) {
                        log("video playlist: ${escaped(text)}")
                    }
                }
                path == "/audio.m3u8" -> {
                    val text = audioPlaylistText()
                    body = text.toByteArray(Charsets.UTF_8)
                    mime = MIME_PLAYLIST
                    if (audioPlaylistTextLogged.compareAndSet(false, true)) {
                        log("audio playlist: ${escaped(text)}")
                    }
                }
                path.startsWith("/vinit") && path.endsWith(".mp4") -> {
                    val gen = path.removePrefix("/vinit").removeSuffix(".mp4").toIntOrNull()
                    body = gen?.let { g -> videoInitSegment(g) }
                    mime = MIME_MP4
                }
                path.startsWith("/ainit") && path.endsWith(".mp4") -> {
                    val gen = path.removePrefix("/ainit").removeSuffix(".mp4").toIntOrNull()
                    body = gen?.let { g -> audioInitSegment(g) }
                    mime = MIME_AUDIO_MP4
                }
                path.startsWith("/vseg") && path.endsWith(".m4s") -> {
                    val seq = path.removePrefix("/vseg").removeSuffix(".m4s").toIntOrNull()
                    val began = System.currentTimeMillis()
                    body = seq?.let { s -> awaitSegment(s, Rendition.VIDEO) }
                    waitMs = System.currentTimeMillis() - began
                    if (seq != null && body != null && seq > highestVideoSeq) highestVideoSeq = seq
                    mime = MIME_SEGMENT
                }
                path.startsWith("/aseg") && path.endsWith(".m4s") -> {
                    val seq = path.removePrefix("/aseg").removeSuffix(".m4s").toIntOrNull()
                    val began = System.currentTimeMillis()
                    body = seq?.let { s -> awaitSegment(s, Rendition.AUDIO) }
                    waitMs = System.currentTimeMillis() - began
                    mime = MIME_SEGMENT
                }
                else -> {
                    body = null
                    mime = "text/plain"
                }
            }
            if (path.endsWith(".m3u8")) playlistFetches.incrementAndGet()
            if (path.endsWith(".m4s")) segmentFetches.incrementAndGet()
            val status = if (body == null) 404 else 200
            logRequest(method, path, status, body?.size ?: 0, waitMs)
            if (method != "HEAD") servedBytesTotal.addAndGet((body?.size ?: 0).toLong())
            lastPeer = sock.inetAddress?.hostAddress
            if (body == null) {
                respond(out, 404, "Not Found", "text/plain", "not found".toByteArray())
            } else {
                respond(out, 200, "OK", mime, if (method == "HEAD") ByteArray(0) else body, body.size)
            }
        }
    }

    /**
     * One line per request: the only record of what the Cast receiver
     * actually asked for and got. Added 2026-09-12 after a cast went
     * IDLE/ERROR one second after the first playlist fetch with no way to
     * tell whether init or the first segment had even been fetched.
     *
     * Rate limit: the first [VERBOSE_REQUESTS] of a session verbatim
     * (master, playlist, init, the opening segments), then only non-200s
     * and every [REQUEST_LOG_EVERY]th request.
     */
    private fun logRequest(method: String, path: String, status: Int, bytes: Int, waitMs: Long) {
        val n = requestsServed.incrementAndGet()
        val verbose = n <= VERBOSE_REQUESTS || status != 200 || n % REQUEST_LOG_EVERY == 0
        if (!verbose) return
        val wait = if (waitMs > 0) " wait=${waitMs}ms" else ""
        log("$method $path $status $bytes B$wait")
    }

    /** Playlist text on ONE log line: newlines escaped so logcat and the
     *  in-app log both keep it as a single readable record. */
    private fun escaped(text: String): String = text.replace("\n", "\\n")

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
