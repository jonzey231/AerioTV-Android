package com.aeriotv.android.core.cast.hlsproxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aeriotv.android.core.debug.debugLog
import com.aeriotv.android.core.debug.debugLogWarn
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

/** An ingest connection that answered with an HTTP error (the
 *  output-profile 503 the sender retries without the parameter). */
class IngestHttpException(val code: Int) :
    Exception("cast ingest failed with HTTP $code")

/** The ingest request was refused with a Dispatcharr connection-limit
 *  signal (see DispatcharrConnectionLimit). Terminal: never reconnected. */
class IngestConnectionLimitException(
    val notice: com.aeriotv.android.core.playback.DispatcharrConnectionLimit.Notice,
) : Exception("cast ingest refused: ${notice.message}")

/**
 * Phone-side cast HLS proxy session (GH #33 web-receiver rework): owns
 * the OkHttp ingest of a live channel's raw MPEG-TS stream, feeds
 * [TsToFmp4Remuxer], and publishes the output through
 * [CastHlsProxyServer] as sliding-window live HLS the Styled Media
 * Receiver can actually pace (the previous progressive fMP4 URL
 * stuttered every 10-15 s for want of a manifest clock).
 *
 * One channel at a time: [startChannel] tears down the previous ingest
 * and (via the server's generation machinery) rolls a playlist
 * discontinuity; the listening socket and URL survive channel flips, so
 * the receiver keeps polling the same playlist.
 *
 * Ingest follows TimeshiftController's fill discipline: a dedicated
 * unbounded IO scope for the blocking read loop, and the in-flight
 * OkHttp call cancelled EXPLICITLY on stop, because a coroutine cancel
 * alone leaves the blocking read holding the provider connection open
 * until the read timeout - fatal on single-connection accounts.
 */
@Singleton
class CastHlsProxySession @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    internal companion object {
        const val TAG = "CAST-HLS"

        /** loadMedia is gated on this much MEDIA DURATION so the receiver
         *  starts a safe distance BEHIND the live edge, and on at least
         *  [READY_MIN_SEGMENTS] segments so a single long segment cannot
         *  satisfy it on its own.
         *
         *  A duration, not a segment count (2026-09-12). Two ~4 s segments
         *  started the receiver 8 s from the edge and the Google TV
         *  Streamer ran the buffer dry 8 s in (02:28:44
         *  BUFFERING_HAVE_ENOUGH, 02:28:52 dry), then re-synced to the live
         *  edge instead of fetching the next segment and stalled on the
         *  skip. A 3-SEGMENT gate fixed that but overshot the other way:
         *  our cuts land on keyframes, and a real broadcast feed's first
         *  three segments were 5.005 s, 4.338 s and 3.170 s, so the gate
         *  held the load for 11.5 s after the ingest connected (iPhone
         *  proxy log 14:22:07.382 init ready, 14:22:18.879 load sent).
         *  Nine seconds of media is three of our 3 s targets, is what the
         *  receiver page also starts behind the edge, and is reached by two
         *  segments on a feed like that. */
        const val READY_MEDIA_TICKS = 9L * TsToFmp4Remuxer.TICKS_PER_SECOND
        /** FOUR segments, not two (2026-09-12, second pass): Shaka sizes
         *  its live seek range from the span of the playlist window it
         *  parses (hls_parser.js determineDuration_ -> getLiveDuration_)
         *  minus the presentation delay the receiver configures (4 s). A
         *  two- or three-segment window leaves a seek range barely wider
         *  than one segment, and the playhead then lives on its edge for
         *  the whole session. Four segments give the receiver a window it
         *  can hold a playhead inside from the first load. */
        const val READY_MIN_SEGMENTS = 4

        /** Bound on the wait for [READY_MEDIA_TICKS]: nine seconds of media plus
         *  provider join latency; past this the channel is declared
         *  uncastable and the user told (the sender quotes this number in
         *  the "did not send any data" message, so the two never drift). */
        const val READY_TIMEOUT_MS = 32_000L

        /** Consecutive failed (re)connects before the ingest gives up.
         *  Backoff 1/2/4/8/8 s; the receiver stalls at the live edge in
         *  the meantime, which is the honest presentation of the outage. */
        const val MAX_CONSECUTIVE_FAILURES = 5

        /** Per-8-segments log rollup cadence. */
        const val LOG_EVERY_SEGMENTS = 8
    }

    /** Long-blocking network reads live off the control path, mirroring
     *  TimeshiftController.fillScope (GH #51: a blocking reader on a
     *  serial scope starves every control task queued behind it). */
    private val ingestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            // Ingest network policy (iOS incident 2026-09-25, Apple 2ceaaed):
            // logging only. The client is not bound to a Network, so the
            // socket rides the default network, cellular included.
            .eventListener(object : okhttp3.EventListener() {
                override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) {
                    logIngestSocket(connection.socket())
                }
            })
            .build()
    }

    // ---- ingest network policy (iOS incident 2026-09-25, Apple 2ceaaed) ----

    private val connectivity: ConnectivityManager? by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    /** Transport the ingest socket rode at its last connect ("wifi", ...). */
    @Volatile private var ingestTransport: String = "?"
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Which Network the ingest socket's local address belongs to. */
    private fun logIngestSocket(socket: java.net.Socket) {
        val cm = connectivity ?: return
        val local = socket.localAddress
        @Suppress("DEPRECATION")
        val network = runCatching {
            cm.allNetworks.firstOrNull { n ->
                cm.getLinkProperties(n)?.linkAddresses?.any { it.address == local } == true
            }
        }.getOrNull()
        val caps = network?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val iface = runCatching { java.net.NetworkInterface.getByInetAddress(local)?.name }.getOrNull() ?: "?"
        ingestTransport = NetworkPathLog.transports(caps)
        debugLog(
            context, TAG,
            "ingest network policy: client unbound (default network, cellular allowed); " +
                "socket on ${NetworkPathLog.describe(caps)} iface=$iface; ${NetworkPathLog.current(context)}",
        )
        if (NetworkPathLog.isCellular(caps)) {
            debugLogWarn(
                context, TAG,
                "ingest is on CELLULAR while the receiver is served over Wi-Fi " +
                    "(phone fell off Wi-Fi or the default network moved)",
            )
        }
    }

    /** Default-network changes while a session is active. */
    private fun startNetworkWatch() {
        val cm = connectivity ?: return
        if (networkCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var last: String? = null
            override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                val now = NetworkPathLog.describe(caps)
                if (now == last) return
                val first = last == null
                last = now
                if (first) return // the connect line already states it
                debugLog(context, TAG, "default network changed: $now (ingest socket on $ingestTransport)")
                if (NetworkPathLog.isCellular(caps) && !NetworkPathLog.isLan(caps)) {
                    debugLogWarn(
                        context, TAG,
                        "default network moved to CELLULAR during a cast; the next ingest " +
                            "(re)connect rides it while the receiver is on Wi-Fi",
                    )
                }
            }

            override fun onLost(network: android.net.Network) {
                last = null
                debugLog(context, TAG, "default network lost (ingest socket on $ingestTransport)")
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { networkCallback = cb }
    }

    private fun stopNetworkWatch() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { connectivity?.unregisterNetworkCallback(cb) }
    }

    private val server = CastHlsProxyServer(log = { msg -> debugLog(context, TAG, msg) })

    init {
        CastHlsProxyService.stats = {
            "requests=${server.requestCount} lastSeq=${server.lastSequence} " +
                "genSegments=${server.segmentsInGeneration.value}"
        }
    }

    // ---- link line (iOS incident 2026-09-25, Apple 85ef563) ----

    /** Ingest bytes read since the process started, for the link line. */
    private val linkIngestBytes = java.util.concurrent.atomic.AtomicLong(0)
    private var linkJob: Job? = null

    /**
     * Receiver readout for the link line, installed by the sender: returns
     * (playhead behind the live edge in seconds or null, player state). Called
     * on the main thread (RemoteMediaClient is main-thread only).
     */
    @Volatile var receiverProbe: (() -> Pair<Double?, String>)? = null

    /** One link line every 10 s while the proxy serves: ingest kbps (avg and
     *  the worst 1 s), 2 s silence stalls, the runway past the receiver's
     *  newest video fetch, what was served to whom, the receiver's playhead
     *  and state, and the stated hold-back. Idempotent. */
    private fun startLinkLog() {
        if (linkJob?.isActive == true) return
        linkJob = ingestScope.launch {
            var lastIngest = linkIngestBytes.get()
            var lastServed = server.servedBytesTotal.get()
            val samples = ArrayList<Double>(10)
            var silentSeconds = 0
            var stalls = 0
            while (currentCoroutineContext().isActive) {
                delay(1_000L)
                val ingest = linkIngestBytes.get()
                val delta = ingest - lastIngest
                lastIngest = ingest
                samples.add(maxOf(0L, delta) * 8 / 1000.0)
                if (delta == 0L) {
                    silentSeconds++
                    if (silentSeconds == 2) stalls++
                } else {
                    silentSeconds = 0
                }
                if (samples.size < 10) continue
                val served = server.servedBytesTotal.get()
                val servedKbps = maxOf(0L, served - lastServed) * 8 / 1000.0 / 10
                lastServed = served
                val (segs, secs) = server.runwayAfter(server.highestVideoSeq)
                val probe = runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.Main) { receiverProbe?.invoke() }
                }.getOrNull()
                val behind = probe?.first?.let { "%.1f s".format(java.util.Locale.US, it) } ?: "?"
                debugLog(
                    context, TAG,
                    String.format(
                        java.util.Locale.US,
                        "link: ingest %.0f kbps avg/%.0f kbps min over 10 s, stalls %d, " +
                            "reservoir %d segs/%.1f s, served %.0f kbps to %s, " +
                            "receiver playhead-behind-edge %s, receiver state %s (hold-back %.1f s)",
                        samples.average(), samples.min(), stalls, segs, secs, servedKbps,
                        server.lastPeer ?: "none", behind, probe?.second ?: "?", server.holdBackSeconds,
                    ),
                )
                samples.clear()
                stalls = 0
            }
        }
    }

    private fun stopLinkLog() {
        linkJob?.cancel()
        linkJob = null
    }

    private var ingestJob: Job? = null
    @Volatile private var ingestCall: okhttp3.Call? = null
    /** Terminal ingest failure (unsupported codec, connect exhaustion),
     *  observed by [startChannel]'s ready wait. */
    private val sessionError = MutableStateFlow<Throwable?>(null)
    @Volatile private var activeUrl: String? = null
    /** Audio codec the remuxer reported for the current ingest, for the
     *  sender's cast load log line. */
    @Volatile private var audioCodec: String = ""

    /**
     * Outcome of a successful [startChannel]: the playlist URL to hand to
     * MediaInfo.contentUrl, plus what the sender needs for its load log
     * line and for telling the user what happened.
     */
    data class Started(
        /** The DEMUXED master playlist (2026-09-13): separate video and
         *  audio renditions, one SourceBuffer each, so the audio can
         *  declare ac-3 / ec-3 honestly on a receiver that answers
         *  isTypeSupported false for any muxed video/mp4 carrying those
         *  codecs but TRUE for audio/mp4 with them. This is the only
         *  playlist the proxy serves and what MediaInfo.contentUrl
         *  carries. */
        val demuxedPlaylistUrl: String,
        /** "AAC", "AC-3", "E-AC-3", "none" or "" when the PMT never
         *  arrived before the first segments (never observed in the
         *  field, but the log line must not lie). */
        val audioCodec: String,
    )

    /**
     * Point the proxy at [rawTsUrl] (the SAME URL + headers the local
     * player would use; as of 2026-09-13 there is no `?output_profile=`
     * variant any more, the cast session always ingests the plain stream)
     * and suspend until the playlist has [READY_MEDIA_TICKS] of media.
     *
     * [allowAc3Passthrough] is the receiver's MEASURED AC-3 capability,
     * passed down by the sender from the receiver's own
     * MediaSource.isTypeSupported('audio/mp4; codecs="ac-3"'): false
     * refuses an AC-3 mux by name rather than sending a stream the
     * receiver cannot decode (the phone never transcodes cast audio, and
     * the server is never asked to).
     *
     * [onNotice] carries a user-facing explanation of a non-fatal ingest
     * event; it is kept for the ingest paths that still report one.
     *
     * Throws [UnsupportedCodecException] for a mux the proxy cannot
     * serve (non-H.264 video, or audio that is neither AAC nor an AC-3
     * family stream this receiver decodes), [IllegalStateException] when
     * the phone has no Wi-Fi LAN address (a Chromecast cannot fetch from
     * a cellular interface), and
     * kotlinx.coroutines.TimeoutCancellationException when segments never
     * materialize.
     */
    suspend fun startChannel(
        rawTsUrl: String,
        headers: Map<String, String>,
        allowAc3Passthrough: Boolean = false,
        h264Level42Supported: Boolean = false,
        @Suppress("UNUSED_PARAMETER") onNotice: ((String) -> Unit)? = null,
    ): Started = kotlinx.coroutines.withContext(Dispatchers.IO) {
        // The sender calls from its Main scope; the socket bind and the
        // address walk below are not Main-thread work.
        server.setReceiverH264Level42(h264Level42Supported)
        Started(
            demuxedPlaylistUrl = startChannelBlocking(rawTsUrl, headers, allowAc3Passthrough, false) +
                "/demuxed.m3u8",
            audioCodec = audioCodec,
        )
    }

    private suspend fun startChannelBlocking(
        rawTsUrl: String,
        headers: Map<String, String>,
        allowAc3Passthrough: Boolean,
        failFastOnHttpError: Boolean,
    ): String {
        // The Chromecast fetches over the LAN; 127.0.0.1 would only ever
        // work for the phone itself.
        val lanIp = wifiLanAddress()
            ?: throw IllegalStateException("phone has no Wi-Fi LAN address to serve the cast proxy on")
        val port = server.start()
        val isChannelChange = activeUrl != null
        stopIngest()
        activeUrl = rawTsUrl
        sessionError.value = null
        audioCodec = ""
        // Channel change keeps the ring: the receiver's cached playlist
        // still promises the old channel's last segments, so they stay
        // fetchable until the ring evicts them, and the new generation
        // splices in behind a discontinuity with no sequence gap.
        val gen = server.beginGeneration()
        debugLog(
            context, TAG,
            "server on $lanIp:$port; ${if (isChannelChange) "channel change" else "session start"} " +
                "gen=$gen ac3Passthrough=$allowAc3Passthrough ingest=${sanitize(rawTsUrl)}",
        )
        // The proxy must outlive the app's foreground time: casting users
        // pocket the phone. See CastHlsProxyService - the FGS is the only
        // thing keeping this process (and therefore the receiver's video)
        // alive once the activity stops.
        CastHlsProxyService.start(context)
        startLinkLog()
        startNetworkWatch()
        startIngest(rawTsUrl, headers, gen, allowAc3Passthrough, failFastOnHttpError)
        val readyWaitBegan = System.currentTimeMillis()
        try {
            withTimeout(READY_TIMEOUT_MS) {
                // First terminal error wins; otherwise wait for segments.
                kotlinx.coroutines.flow.combine(
                    server.segmentsInGeneration,
                    server.mediaTicksInGeneration,
                    sessionError,
                ) { count, ticks, err -> Triple(count, ticks, err) }
                    .first { (count, ticks, err) ->
                        err?.let { throw it }
                        count >= READY_MIN_SEGMENTS && ticks >= READY_MEDIA_TICKS
                    }
            }
        } catch (t: Throwable) {
            // A channel that cannot start must not leave a dead ingest
            // pinning the provider connection or the FGS running.
            if (activeUrl == rawTsUrl) stop()
            throw t
        }
        // How long the gate actually took and what it proceeded with. A
        // wait near the segment cadence (keyframe interval, not the 3 s
        // target) is normal; zero segments here would mean finalizeSegment
        // never ran at all.
        debugLog(
            context, TAG,
            "ready after ${System.currentTimeMillis() - readyWaitBegan}ms with " +
                "${server.segmentsInGeneration.value} segments, " +
                "%.2fs media (gate %.0fs / $READY_MIN_SEGMENTS segments)".format(
                    java.util.Locale.US,
                    server.mediaTicksInGeneration.value.toDouble() /
                        TsToFmp4Remuxer.TICKS_PER_SECOND,
                    READY_MEDIA_TICKS.toDouble() / TsToFmp4Remuxer.TICKS_PER_SECOND,
                ),
        )
        // The proxy's base URL; /demuxed.m3u8 (the two-rendition shape the
        // sender loads) hangs off it. It is a MASTER playlist, never a
        // media one: CLOSED-CAPTIONS=NONE keeps Shaka's Mp4CeaParser away
        // from the video segments (fatal Error 3000 otherwise).
        proxyBaseUrl = "http://$lanIp:$port"
        return proxyBaseUrl
    }

    /** (playlist fetches, segment fetches) since the proxy started, for the
     *  sender's stale-receiver watchdog. */
    fun fetchCounters(): Pair<Int, Int> =
        server.playlistFetches.get() to server.segmentFetches.get()

    /** Base URL of the running proxy ("http://ip:port"), set by
     *  [startChannelBlocking] once the socket is bound and the ready gate
     *  has passed. */
    @Volatile private var proxyBaseUrl: String = ""

    /** Full teardown: ingest, ring, server socket, foreground service.
     *  Called when the cast session ends (or a start fails). */
    fun stop() {
        val hadSession = activeUrl != null || server.isRunning
        activeUrl = null
        stopIngest()
        stopLinkLog()
        stopNetworkWatch()
        server.stop()
        CastHlsProxyService.stop(context)
        if (hadSession) debugLog(context, TAG, "proxy stopped")
    }

    private fun stopIngest() {
        // Cancel the call BEFORE the job: the blocking body read only
        // returns once the socket is torn down (TimeshiftController's
        // fillCall discipline; a plain job cancel held the provider
        // connection open to the 60 s read timeout).
        ingestCall?.cancel()
        ingestCall = null
        ingestJob?.cancel()
        ingestJob = null
    }

    private fun startIngest(
        url: String,
        headers: Map<String, String>,
        gen: Int,
        allowAc3Passthrough: Boolean,
        failFastOnHttpError: Boolean,
    ) {
        var currentGen = gen
        ingestJob = ingestScope.launch {
            var consecutiveFailures = 0
            var connected = false
            // Clean end (Dispatcharr ended this client, e.g. terminate on limit
            // exceeded): keep reconnecting on the shared escalating backoff
            // (now, 5 s, 15 s, 30 s, then 60 s) instead of the tight failure
            // backoff, which used to reset on every successful reconnect and so
            // bounced against another device forever. The ingest only gives up
            // when StreamEndVerifier proves the account is at its stream limit.
            var cleanEndStreak = 0
            var cleanEndReconnectAtMs = 0L
            var connectedAtSec = System.currentTimeMillis() / 1000.0
            while (currentCoroutineContext().isActive) {
                // Fresh remuxer per connection: a TS join lands mid-GOP
                // with an unknown clock phase, so the remuxer realigns
                // (sync scan, wait for SPS/PPS + keyframe) and the server
                // presents the restart as a playlist discontinuity.
                val remuxer = TsToFmp4Remuxer(object : TsToFmp4Remuxer.Listener {
                    private var segmentsLogged = 0
                    private var rollupBytes = 0L
                    private var rollupTicks = 0L
                    /** Census of the segment currently being handed over,
                     *  for the first-segment detail line below. */
                    private var videoSamples = 0
                    private var audioSamples = 0
                    /** Timeline census of the same segment, logged per
                     *  segment (2026-09-12): see the per-segment line in
                     *  onMediaSegment below. */
                    private var segmentStartSeconds = 0.0
                    private var firstVideoDtsSeconds = 0.0
                    private var firstVideoPtsSeconds = 0.0
                    private var firstAudioPtsSeconds = -1.0
                    /** Sequence the server will assign this segment. The
                     *  server claims numbers at publish time and does not
                     *  report them back, so this mirrors it: one counter
                     *  per generation, and this listener is built fresh per
                     *  ingest connection, i.e. per generation. */
                    private var localSeq = 0

                    override fun onInitSegments(video: ByteArray, audio: ByteArray?) {
                        server.setInitSegments(currentGen, video, audio)
                        debugLog(
                            context, TAG,
                            "init ready gen=$currentGen " +
                                "vinit=${video.size} B ainit=${audio?.size ?: 0} B",
                        )
                    }

                    override fun onSegmentComposition(
                        videoSamples: Int,
                        audioSamples: Int,
                        firstVideoDtsSeconds: Double,
                        firstVideoPtsSeconds: Double,
                        firstAudioPtsSeconds: Double,
                        segmentStartSeconds: Double,
                    ) {
                        this.videoSamples = videoSamples
                        this.audioSamples = audioSamples
                        this.firstVideoDtsSeconds = firstVideoDtsSeconds
                        this.firstVideoPtsSeconds = firstVideoPtsSeconds
                        this.firstAudioPtsSeconds = firstAudioPtsSeconds
                        this.segmentStartSeconds = segmentStartSeconds
                    }

                    override fun onMediaSegment(
                        video: ByteArray,
                        audio: ByteArray?,
                        videoDurationTicks: Long,
                        audioDurationTicks: Long,
                    ) {
                        server.addSegment(
                            gen = currentGen,
                            videoData = video,
                            audioData = audio,
                            durationTicks = videoDurationTicks,
                            audioDurationTicks = audioDurationTicks,
                        )
                        segmentsLogged++
                        // EVERY segment's timeline, so the playhead-versus-
                        // buffer arithmetic can be done from the sender log
                        // alone. The 15:10 Google TV Streamer session had to
                        // be guessed at because nothing printed where each
                        // segment sat on the PLAYLIST timeline (t=) versus
                        // its own MEDIA timeline (vdts/vpts/apts), and
                        // buffStart is the one that matters: Chromium reports
                        // a two-track SourceBuffer's buffered range as the
                        // INTERSECTION of the tracks, so the range starts at
                        // max(vpts, apts), not at t=.
                        val segSeconds = videoDurationTicks / TsToFmp4Remuxer.TICKS_PER_SECOND.toDouble()
                        val buffStart = maxOf(firstVideoPtsSeconds, firstAudioPtsSeconds)
                        debugLog(
                            context, TAG,
                            "seg=$localSeq t=${"%.2f".format(segmentStartSeconds)}s " +
                                "dur=${"%.2f".format(segSeconds)}s " +
                                "vdts=${"%.3f".format(firstVideoDtsSeconds)} " +
                                "vpts=${"%.3f".format(firstVideoPtsSeconds)} " +
                                "apts=${"%.3f".format(firstAudioPtsSeconds)} " +
                                "buffStart=${"%.3f".format(buffStart)} " +
                                "video=$videoSamples audio=$audioSamples " +
                                "vseg=${video.size} B aseg=${audio?.size ?: 0} B",
                        )
                        localSeq++
                        if (segmentsLogged == 1) {
                            // The FIRST segment of a generation is the one
                            // the receiver starts on, so its shape is what
                            // a one-second IDLE/ERROR has to be read from.
                            debugLog(
                                context, TAG,
                                "first segment gen=$currentGen seq=0 " +
                                    "dur=${"%.2f".format(segSeconds)}s " +
                                    "vseg=${video.size} B aseg=${audio?.size ?: 0} B " +
                                    "video=$videoSamples audio=$audioSamples samples",
                            )
                        }
                        rollupBytes += video.size + (audio?.size ?: 0)
                        rollupTicks += videoDurationTicks
                        if (segmentsLogged % LOG_EVERY_SEGMENTS == 0) {
                            val seconds = rollupTicks / TsToFmp4Remuxer.TICKS_PER_SECOND.toDouble()
                            val kbps = if (seconds > 0) (rollupBytes * 8 / seconds / 1000).toInt() else 0
                            debugLog(
                                context, TAG,
                                "segments=$segmentsLogged last$LOG_EVERY_SEGMENTS: " +
                                    "avgDur=${"%.2f".format(seconds / LOG_EVERY_SEGMENTS)}s " +
                                    "bytes=$rollupBytes bitrate=${kbps}kbps",
                            )
                            rollupBytes = 0
                            rollupTicks = 0
                        }
                    }
                    override fun onAudioCodec(name: String) {
                        audioCodec = name
                    }
                }, log = { msg -> debugLog(context, TAG, msg) }, allowAc3Passthrough = allowAc3Passthrough)
                var endedCleanly = false
                try {
                    val req = Request.Builder().url(url).apply {
                        headers.forEach { (k, v) -> header(k, v) }
                    }.build()
                    val call = client.newCall(req)
                    ingestCall = call
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            debugLogWarn(context, TAG, "ingest connect failed http=${resp.code}")
                            // A connection-limit refusal is final: reconnecting
                            // would only keep competing for the user's slots.
                            if (resp.code == 429 || resp.code == 503) {
                                val body = runCatching { resp.peekBody(4_096).string() }.getOrDefault("")
                                val notice = com.aeriotv.android.core.playback.DispatcharrConnectionLimit
                                    .fromResponse(resp.code, body)
                                if (notice != null) {
                                    debugLogWarn(context, TAG, "[LIMIT] ingest ${notice.kind}: ${notice.message}")
                                    sessionError.value = IngestConnectionLimitException(notice)
                                    return@launch
                                }
                            }
                            // An output-profile URL that errors (503 when
                            // the server cannot start the profile) must
                            // surface NOW so the caller can retry on the
                            // plain feed, not after 15 s of backoff.
                            if (failFastOnHttpError && !connected) {
                                sessionError.value = IngestHttpException(resp.code)
                                return@launch
                            }
                            return@use
                        }
                        val src = resp.body?.byteStream() ?: return@use
                        if (connected) {
                            debugLog(context, TAG, "ingest reconnected (attempt ${consecutiveFailures + 1})")
                        } else {
                            debugLog(context, TAG, "ingest connected")
                        }
                        connected = true
                        consecutiveFailures = 0
                        connectedAtSec = System.currentTimeMillis() / 1000.0
                        val buf = ByteArray(64 * 1024)
                        while (currentCoroutineContext().isActive) {
                            val n = src.read(buf)
                            if (n < 0) {
                                endedCleanly = true
                                break
                            }
                            if (n > 0) {
                                linkIngestBytes.addAndGet(n.toLong())
                                remuxer.feed(buf, 0, n)
                            }
                        }
                    }
                } catch (e: UnsupportedCodecException) {
                    // Terminal by design: nothing in this path is ever
                    // re-encoded, so audio outside AAC and the AC-3 family
                    // this receiver decodes cannot be served. Surfaced to
                    // the sender's ready wait as the cast failure.
                    debugLogWarn(context, TAG, "unsupported codec, refusing to cast: ${e.codecName}")
                    sessionError.value = e
                    return@launch
                } catch (t: Throwable) {
                    // Socket and parse failures alike: the reconnect
                    // below builds a fresh remuxer rather than killing the
                    // proxy.
                    if (currentCoroutineContext().isActive) {
                        debugLogWarn(context, TAG, "ingest stream error: $t")
                    }
                } finally {
                    remuxer.release()
                }
                if (!currentCoroutineContext().isActive) break
                if (endedCleanly) {
                    val verifier = com.aeriotv.android.core.playback.StreamEndVerifier
                    val now = android.os.SystemClock.elapsedRealtime()
                    val since = now - cleanEndReconnectAtMs
                    cleanEndStreak =
                        if (cleanEndReconnectAtMs > 0L && since < verifier.HEALTHY_RESET_MS) {
                            cleanEndStreak + 1
                        } else {
                            1
                        }
                    val cleanBackoffMs = verifier.backoffMs(cleanEndStreak)
                    debugLogWarn(
                        context, TAG,
                        "[RECOVER] ingest ended cleanly #$cleanEndStreak; reconnecting in ${cleanBackoffMs}ms",
                    )
                    if (cleanEndStreak >= 2) {
                        val verdict = verifier.verify(
                            verifier.channelUuidFromUrl(url),
                            connectedAtSec,
                        )
                        debugLog(
                            context, TAG,
                            "[RECOVER] ingest clean end #$cleanEndStreak session check: " +
                                (if (verdict.stopped) "AT LIMIT" else "not verified") +
                                " (${verdict.detail})",
                        )
                        if (verdict.stopped) {
                            debugLogWarn(
                                context, TAG,
                                "[RECOVER] ingest stream end VERIFIED at the stream limit; stopping ingest",
                            )
                            sessionError.value = IllegalStateException("stream ended by the server")
                            return@launch
                        }
                    }
                    if (cleanBackoffMs > 0L) delay(cleanBackoffMs)
                    if (!currentCoroutineContext().isActive) break
                    cleanEndReconnectAtMs = android.os.SystemClock.elapsedRealtime()
                    currentGen = server.beginGeneration()
                    continue
                }
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    debugLogWarn(
                        context, TAG,
                        "ingest gave up after $consecutiveFailures consecutive failures",
                    )
                    sessionError.value = IllegalStateException("stream unreachable")
                    return@launch
                }
                // Bounded backoff, then a NEW generation: the reconnected
                // stream's clock will not line up with the old one, so the
                // playlist declares the splice instead of hiding it.
                val backoffMs = (1_000L shl (consecutiveFailures - 1)).coerceAtMost(8_000L)
                debugLog(context, TAG, "ingest reconnect in ${backoffMs}ms")
                delay(backoffMs)
                if (!currentCoroutineContext().isActive) break
                currentGen = server.beginGeneration()
            }
        }
    }

    /**
     * The device's Wi-Fi IPv4 address. ConnectivityManager first (the
     * Wi-Fi transport specifically: the ACTIVE network may be cellular
     * while Wi-Fi is still up, and the Chromecast can only reach the
     * Wi-Fi side); NetworkInterface as the fallback for OEMs whose
     * LinkProperties come back empty.
     */
    private fun wifiLanAddress(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            for (network in cm.allNetworks) {
                val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: continue
                val lanLike = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!lanLike) continue
                val props = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: continue
                props.linkAddresses.forEach { la ->
                    val addr = la.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        }
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    /**
     * Strip credentials/query from a URL for the log (house rule: no
     * identifiers or secrets in shareable logs).
     *
     * The port is echoed ONLY when the URL carries one explicitly. The old
     * `if (u.port > 0) u.port else 80` default printed
     * "https://host:80/proxy/ts/stream/..." for a plain https base (Logan's
     * 2026-09-12 log), which reads as a real misconfiguration; https without
     * an explicit port is 443, and either way the log must not invent one.
     *
     * The whole query string is dropped: with the output-profile path gone
     * (2026-09-13) no query parameter carries anything the cast audio path
     * is diagnosed by.
     */
    private fun sanitize(url: String): String {
        val base = url.substringBefore('?')
        return runCatching {
            val u = java.net.URI(base)
            val port = if (u.port > 0) ":${u.port}" else ""
            "${u.scheme}://${u.host}$port${u.path}"
        }.getOrDefault(base)
    }
}
