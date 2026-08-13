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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

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
    private companion object {
        const val TAG = "CAST-HLS"

        /** loadMedia is gated on this many segments so the receiver's
         *  first playlist fetch always has something playable. */
        const val READY_SEGMENTS = 2

        /** Bound on the wait for [READY_SEGMENTS]: two 3 s segments plus
         *  provider join latency; past this the channel is declared
         *  uncastable and the user told. */
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

    /**
     * Point the proxy at [rawTsUrl] (the SAME URL + headers the local
     * player would use) and suspend until the playlist has
     * [READY_SEGMENTS] segments. Returns the playlist URL to hand to
     * MediaInfo.contentUrl.
     *
     * Throws [UnsupportedCodecException] for a mux the pure remux
     * refuses, [IllegalStateException] when the phone has no Wi-Fi LAN
     * address (a Chromecast cannot fetch from a cellular interface), and
     * kotlinx.coroutines.TimeoutCancellationException when segments never
     * materialize.
     */
    suspend fun startChannel(
        rawTsUrl: String,
        headers: Map<String, String>,
    ): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        // The sender calls from its Main scope; the socket bind and the
        // address walk below are not Main-thread work.
        startChannelBlocking(rawTsUrl, headers)
    }

    private suspend fun startChannelBlocking(rawTsUrl: String, headers: Map<String, String>): String {
        // The Chromecast fetches over the LAN; 127.0.0.1 would only ever
        // work for the phone itself.
        val lanIp = wifiLanAddress()
            ?: throw IllegalStateException("phone has no Wi-Fi LAN address to serve the cast proxy on")
        val port = server.start()
        val isChannelChange = activeUrl != null
        stopIngest()
        activeUrl = rawTsUrl
        sessionError.value = null
        // Channel change wipes the ring (the old channel must never be
        // served again) and flags the discontinuity between channels.
        val gen = server.beginGeneration(clearRing = true)
        debugLog(
            context, TAG,
            "server on $lanIp:$port; ${if (isChannelChange) "channel change" else "session start"} " +
                "gen=$gen ingest=${sanitize(rawTsUrl)}",
        )
        // The proxy must outlive the app's foreground time: casting users
        // pocket the phone. See CastHlsProxyService - the FGS is the only
        // thing keeping this process (and therefore the receiver's video)
        // alive once the activity stops.
        CastHlsProxyService.start(context)
        startIngest(rawTsUrl, headers, gen)
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
        return "http://$lanIp:$port/live.m3u8"
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

    private fun startIngest(url: String, headers: Map<String, String>, gen: Int) {
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
                })
                try {
                    val req = Request.Builder().url(url).apply {
                        headers.forEach { (k, v) -> header(k, v) }
                    }.build()
                    val call = client.newCall(req)
                    ingestCall = call
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            debugLogWarn(context, TAG, "ingest connect failed http=${resp.code}")
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
                    // Terminal by design: P1 never re-encodes. Surfaced to
                    // the sender's ready wait as the cast failure.
                    debugLogWarn(context, TAG, "unsupported codec, refusing to cast: ${e.codecName}")
                    sessionError.value = e
                    return@launch
                } catch (t: Throwable) {
                    if (currentCoroutineContext().isActive) {
                        debugLogWarn(context, TAG, "ingest stream error: $t")
                    }
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
                currentGen = server.beginGeneration(clearRing = false)
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
