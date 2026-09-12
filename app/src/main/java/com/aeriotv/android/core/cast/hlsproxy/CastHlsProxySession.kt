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
        const val READY_MIN_SEGMENTS = 2

        /** Bound on the wait for [READY_MEDIA_TICKS]: nine seconds of media plus
         *  provider join latency; past this the channel is declared
         *  uncastable and the user told (the sender quotes this number in
         *  the "did not send any data" message, so the two never drift). */
        const val READY_TIMEOUT_MS = 25_000L

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
            .build()
    }

    private val server = CastHlsProxyServer(log = { msg -> debugLog(context, TAG, msg) })

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
        val playlistUrl: String,
        /** "AAC", "AC-3", "E-AC-3", "none" or "" when the PMT never
         *  arrived before the first segments (never observed in the
         *  field, but the log line must not lie). */
        val audioCodec: String,
        /** True when the output-profile URL failed and the plain feed
         *  carried the session instead. */
        val usedFallback: Boolean,
    )

    /**
     * Point the proxy at [rawTsUrl] (the SAME URL + headers the local
     * player would use, plus `?output_profile=<id>` when the sender
     * resolved Dispatcharr's AAC profile) and suspend until the playlist
     * has [READY_MEDIA_TICKS] of media.
     *
     * [fallbackUrl] is the same channel WITHOUT the output_profile
     * parameter. When it is non-null the first connection fails fast on
     * an HTTP error instead of burning the ready deadline on five
     * backoff retries, and the session is restarted ONCE on the plain
     * feed: a broken or mis-seeded server profile must not cost the user
     * the channel. [onNotice] carries the user-facing explanation of
     * that fallback.
     *
     * [allowAc3Passthrough] is the receiver's AC-3 capability, decided by
     * the sender from the Cast device: false refuses an AC-3 mux by name
     * rather than sending a stream the receiver cannot decode (the phone
     * never transcodes cast audio).
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
        fallbackUrl: String? = null,
        allowAc3Passthrough: Boolean = false,
        onNotice: ((String) -> Unit)? = null,
    ): Started = kotlinx.coroutines.withContext(Dispatchers.IO) {
        // The sender calls from its Main scope; the socket bind and the
        // address walk below are not Main-thread work.
        val retryUrl = fallbackUrl?.takeIf { it != rawTsUrl }
        try {
            Started(
                playlistUrl = startChannelBlocking(rawTsUrl, headers, allowAc3Passthrough, retryUrl != null),
                audioCodec = audioCodec,
                usedFallback = false,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Supersession (a later channel flip) or a real timeout; the
            // timeout arm below is the only one that retries.
            if (retryUrl == null || e !is TimeoutCancellationException) throw e
            onNotice?.invoke(
                "Dispatcharr did not send any data for this channel within " +
                    "${READY_TIMEOUT_MS / 1000} seconds. Trying the original audio.",
            )
            debugLogWarn(context, TAG, "output profile sent no data in time; retrying without it")
            Started(
                playlistUrl = startChannelBlocking(retryUrl, headers, allowAc3Passthrough, false),
                audioCodec = audioCodec,
                usedFallback = true,
            )
        } catch (e: IngestHttpException) {
            if (retryUrl == null) throw e
            onNotice?.invoke(
                "Dispatcharr could not start the AAC output profile for this " +
                    "channel (HTTP ${e.code}). Trying the original audio.",
            )
            debugLogWarn(context, TAG, "output profile failed http=${e.code}; retrying without it")
            Started(
                playlistUrl = startChannelBlocking(retryUrl, headers, allowAc3Passthrough, false),
                audioCodec = audioCodec,
                usedFallback = true,
            )
        }
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
        // Load the MASTER playlist: its CLOSED-CAPTIONS=NONE keeps Shaka's
        // Mp4CeaParser away from our muxed segments (fatal Error 3000
        // otherwise; see masterPlaylistText).
        return "http://$lanIp:$port/master.m3u8"
    }

    /** Full teardown: ingest, ring, server socket, foreground service.
     *  Called when the cast session ends (or a start fails). */
    fun stop() {
        val hadSession = activeUrl != null || server.isRunning
        activeUrl = null
        stopIngest()
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

                    override fun onInitSegment(data: ByteArray) {
                        server.setInitSegment(currentGen, data)
                        debugLog(context, TAG, "init segment ready gen=$currentGen (${data.size} B)")
                    }

                    override fun onSegmentComposition(videoSamples: Int, audioSamples: Int) {
                        this.videoSamples = videoSamples
                        this.audioSamples = audioSamples
                    }

                    override fun onMediaSegment(data: ByteArray, durationTicks: Long) {
                        server.addSegment(currentGen, data, durationTicks)
                        segmentsLogged++
                        if (segmentsLogged == 1) {
                            // The FIRST segment of a generation is the one
                            // the receiver starts on, so its shape is what
                            // a one-second IDLE/ERROR has to be read from.
                            val seconds = durationTicks / TsToFmp4Remuxer.TICKS_PER_SECOND.toDouble()
                            debugLog(
                                context, TAG,
                                "first segment gen=$currentGen seq=0 " +
                                    "dur=${"%.2f".format(seconds)}s ${data.size} B " +
                                    "video=$videoSamples audio=$audioSamples samples",
                            )
                        }
                        rollupBytes += data.size
                        rollupTicks += durationTicks
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
                try {
                    val req = Request.Builder().url(url).apply {
                        headers.forEach { (k, v) -> header(k, v) }
                    }.build()
                    val call = client.newCall(req)
                    ingestCall = call
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            debugLogWarn(context, TAG, "ingest connect failed http=${resp.code}")
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
                        val buf = ByteArray(64 * 1024)
                        while (currentCoroutineContext().isActive) {
                            val n = src.read(buf)
                            if (n < 0) break
                            if (n > 0) remuxer.feed(buf, 0, n)
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
     * `output_profile` is kept (it is a server-side profile id, not a
     * secret) because it is the one query parameter the cast audio path is
     * diagnosed by; every other parameter is dropped.
     */
    private fun sanitize(url: String): String {
        val base = url.substringBefore('?')
        val query = url.substringAfter('?', "")
        val profile = query.split('&')
            .firstOrNull { it.startsWith("output_profile=") }
        val host = runCatching {
            val u = java.net.URI(base)
            val port = if (u.port > 0) ":${u.port}" else ""
            "${u.scheme}://${u.host}$port${u.path}"
        }.getOrDefault(base)
        return if (profile != null) "$host?$profile" else host
    }
}
