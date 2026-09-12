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

        /** loadMedia is gated on this many segments so the receiver's
         *  first playlist fetch always has something playable. */
        const val READY_SEGMENTS = 2

        /** Bound on the wait for [READY_SEGMENTS]: two 3 s segments plus
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
     * has [READY_SEGMENTS] segments.
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
        try {
            withTimeout(READY_TIMEOUT_MS) {
                // First terminal error wins; otherwise wait for segments.
                kotlinx.coroutines.flow.combine(
                    server.segmentsInGeneration,
                    sessionError,
                ) { count, err -> Pair(count, err) }
                    .first { (count, err) ->
                        err?.let { throw it }
                        count >= READY_SEGMENTS
                    }
            }
        } catch (t: Throwable) {
            // A channel that cannot start must not leave a dead ingest
            // pinning the provider connection or the FGS running.
            if (activeUrl == rawTsUrl) stop()
            throw t
        }
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

                    override fun onInitSegment(data: ByteArray) {
                        server.setInitSegment(currentGen, data)
                        debugLog(context, TAG, "init segment ready gen=$currentGen (${data.size} B)")
                    }

                    override fun onMediaSegment(data: ByteArray, durationTicks: Long) {
                        server.addSegment(currentGen, data, durationTicks)
                        segmentsLogged++
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

    /** Strip credentials/query from a URL for the log (house rule: no
     *  identifiers or secrets in shareable logs). */
    private fun sanitize(url: String): String = url.substringBefore('?').let { base ->
        runCatching {
            val u = java.net.URI(base)
            "${u.scheme}://${u.host}:${if (u.port > 0) u.port else 80}${u.path}"
        }.getOrDefault(base)
    }
}
