package com.aeriotv.android.core.network

import android.util.Log
import com.aeriotv.android.core.debug.LogSanitizer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches M3U/M3U8 playlists over HTTP(S). Returns raw bytes so the parser can
 * handle UTF-8 / ISO-8859-1 encoding fallback (iOS PlaylistParsers.swift:96-97).
 *
 * Phase 2 keeps this minimal. Phase 3 will add: Dispatcharr/XC custom headers,
 * User-Agent rotation per server, conditional GET via ETag, retry/backoff.
 */
@Singleton
class PlaylistFetcher @Inject constructor() {

    private val client = HttpClient(OkHttp) {
        installSanitizedLogging()
        engine {
            // OkHttp engine config can be expanded later for proxies, interceptors.
        }
        install(HttpTimeout) {
            // Mirror the iOS URLSession model (StreamingAPIs.swift): a generous
            // TOTAL budget for a large XMLTV EPG / M3U payload (10K+ channel
            // servers run to tens of MB) with a short INACTIVITY timeout so a
            // dead host still fails fast. A 60s TOTAL cap truncated big EPG / M3U
            // downloads mid-stream the same way it did the VOD library.
            requestTimeoutMillis = 300_000  // iOS timeoutIntervalForResource = 300
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000    // idle between packets
        }
    }

    /**
     * Discord (di5cord20 + Matschi, 2026-08-09): a tuliprox Xtream Codes
     * playlist connects, verifies, saves, and then shows ZERO channels, on
     * two different boxes, surviving force-close / cache clear / reboot, while
     * the same credentials load fully elsewhere.
     *
     * tuliprox answers ANY m3u failure with an empty 204. From
     * `backend/src/api/endpoints/m3u_api.rs`:
     *
     *     Err(err) => {
     *         error!("{}", sanitize_sensitive_info(&err.to_string()));
     *         axum::http::StatusCode::NO_CONTENT.into_response()
     *     }
     *
     * Ktor's `HttpStatusCode.isSuccess()` is `value in 200..299`, so 204 sailed
     * through as a successful fetch: we wrote a zero-byte file, parsed zero
     * channels, and stored a perfectly healthy-looking empty playlist. Nothing
     * in the app could tell the user anything, which is why nothing the users
     * tried made any difference.
     *
     * So a 2xx is not enough - the body has to actually contain something.
     * Treat 204, and any empty body on a 2xx, as the failure it is. This is
     * deliberately server-agnostic: it catches every proxy that reports "I
     * could not build your playlist" as a polite empty success, not just this
     * one. A legitimately empty source is not affected, because an empty M3U is
     * still `#EXTM3U` and an empty guide is still a `<tv>` document; zero bytes
     * always means something went wrong upstream.
     */
    private fun emptyBodyError(status: Int, url: String): IllegalStateException =
        IllegalStateException(
            if (status == 204) {
                "The server accepted the request but returned no data (HTTP 204) from " +
                    "${LogSanitizer.redactUrl(url)}. That usually means it could not build " +
                    "the playlist for these credentials: check the username and password, " +
                    "and that this device is allowed to connect."
            } else {
                "The server returned an empty response from ${LogSanitizer.redactUrl(url)}."
            },
        )

    suspend fun fetchBytes(
        url: String,
        userAgent: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ByteArray {
        val response: HttpResponse = client.get(url) {
            if (userAgent != null) header("User-Agent", userAgent)
            for ((k, v) in extraHeaders) header(k, v)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "HTTP ${response.status.value} ${response.status.description} from ${LogSanitizer.redactUrl(url)}",
            )
        }
        if (response.status.value == 204) throw emptyBodyError(204, url)
        return response.readRawBytes().also {
            if (it.isEmpty()) throw emptyBodyError(response.status.value, url)
        }
    }

    /** GH #26: stream a large body straight to [dest] in constant memory.
     *  [fetchBytes] materializes the whole payload as ONE allocation, and a
     *  full XC-panel M3U (or a provider XMLTV guide) runs 100-200MB -- the
     *  exact 155MB allocation that OOM'd a 256MB-heap phone while adding a
     *  playlist. The caller owns (and deletes) the file. */
    /** Raw OkHttp client for [fetchToFile]. Ktor's OkHttp engine pumps the
     *  response body through a producer coroutine on Dispatchers.Default at
     *  normal priority, and simpleperf on the Google TV Streamer (2026-08-31)
     *  showed that pump -- gzip inflate included -- starving four multiview
     *  decoders while EPG feeds downloaded. A synchronous OkHttp call streams
     *  and decompresses on the CALLING thread instead, so a caller on the
     *  background-priority EPG dispatcher does all of this work niced. */
    private val streamingClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        // No call-level total timeout, same reasoning as the ktor config
        // below: a total cap is a hidden minimum-bandwidth requirement.
        .build()

    suspend fun fetchToFile(
        url: String,
        dest: File,
        userAgent: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): File {
        fetchToFileInternal(url, dest, userAgent, extraHeaders, validators = null)
        return dest
    }

    /**
     * [fetchToFile] as a conditional GET: sends the validators kept from the
     * last full download and returns null when the server answers 304 Not
     * Modified, so an unchanged upstream EPG feed costs one round trip
     * instead of a download plus a full parse. A server that sends neither
     * ETag nor Last-Modified simply always downloads.
     */
    suspend fun fetchToFileIfChanged(
        url: String,
        dest: File,
        validators: FeedValidators?,
    ): FeedValidators? = fetchToFileInternal(url, dest, null, emptyMap(), validators)

    /** The response validators, or null on 304 (only when [validators] were sent). */
    private fun fetchToFileInternal(
        url: String,
        dest: File,
        userAgent: String?,
        extraHeaders: Map<String, String>,
        validators: FeedValidators?,
    ): FeedValidators? {
        val reqBuilder = okhttp3.Request.Builder().url(url)
        if (userAgent != null) reqBuilder.header("User-Agent", userAgent)
        for ((k, v) in extraHeaders) reqBuilder.header(k, v)
        validators?.etag?.let { reqBuilder.header("If-None-Match", it) }
        validators?.lastModified?.let { reqBuilder.header("If-Modified-Since", it) }
        return streamingClient.newCall(reqBuilder.build()).execute().use { response ->
            if (validators != null && response.code == 304) return@use null
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "HTTP ${response.code} ${response.message} from ${LogSanitizer.redactUrl(url)}",
                )
            }
            if (response.code == 204) throw emptyBodyError(204, url)
            val body = response.body ?: throw emptyBodyError(response.code, url)
            // Fail early and legibly rather than dying with ENOSPC half a
            // gigabyte in (cheap TV box, nearly-full data partition).
            // contentLength is -1 when OkHttp transparently gunzips, which
            // also (correctly) skips the truncation check below -- the
            // decompressed size is unknowable up front.
            val expected = body.contentLength()
            val free = dest.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (expected > 0 && free < expected + SPACE_HEADROOM_BYTES) {
                throw IllegalStateException(
                    "Not enough free space to download this playlist: it needs about " +
                        "${expected / 1_000_000}MB but only ${free / 1_000_000}MB is free. " +
                        "Free up some space and try again.",
                )
            }
            if (expected > LARGE_DOWNLOAD_BYTES) {
                Log.i(TAG, "large download starting: ~${expected / 1_000_000}MB")
            }
            var total = 0L
            var nextMark = PROGRESS_LOG_BYTES
            // Minimum-throughput floor: readTimeout only fails TOTAL silence; a
            // middlebox trickling a few bytes every 25s resets it forever.
            var windowStart = System.currentTimeMillis()
            var windowBytes = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        windowBytes += n
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - windowStart >= THROUGHPUT_WINDOW_MS) {
                            if (windowBytes < THROUGHPUT_FLOOR_BYTES) {
                                throw IllegalStateException(
                                    "Download stalled: only ${windowBytes / 1024}KB arrived in the " +
                                        "last ${THROUGHPUT_WINDOW_MS / 1000}s " +
                                        "(${total / 1_000_000}MB downloaded so far). " +
                                        "The server is barely responding; try again later.",
                                )
                            }
                            windowStart = nowMs
                            windowBytes = 0L
                        }
                        if (total >= nextMark) {
                            Log.i(TAG, "download progress: ${total / 1_000_000}MB")
                            nextMark += PROGRESS_LOG_BYTES
                        }
                    }
                }
            }
            if (dest.length() == 0L) throw emptyBodyError(response.code, url)
            // Content-Length disagreeing with what landed = died mid-stream. A
            // truncated body parses into a legitimate-looking partial playlist.
            if (expected > 0 && total < expected) {
                throw IllegalStateException(
                    "The playlist download ended early (${total / 1_000_000}MB of " +
                        "${expected / 1_000_000}MB). The connection dropped part-way " +
                        "through; please try again.",
                )
            }
            if (expected > LARGE_DOWNLOAD_BYTES) {
                Log.i(TAG, "large download complete: ${total / 1_000_000}MB")
            }
            FeedValidators(response.header("ETag"), response.header("Last-Modified"))
        }
    }

    @Suppress("unused")
    private suspend fun fetchToFileViaKtor(
        url: String,
        dest: File,
        userAgent: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): File = client.prepareGet(url) {
        // NO total-request cap on a streamed download. The global
        // requestTimeoutMillis budgets the ENTIRE body, so it silently becomes
        // a MINIMUM BANDWIDTH requirement: at 300s a 538MB provider playlist
        // (crx.watch - 53.6k live, 194k VOD, 44.7k series) demands ~14.3 Mbit/s
        // sustained for five unbroken minutes or the add dies mid-stream.
        // Logan hit exactly that on 2026-08-10: the same playlist "finally
        // pulled on the third attempt". Raising the number just moves the
        // cliff - it had already been raised 60s -> 300s for this same reason.
        // A total cap is the wrong instrument for a download whose size is set
        // by the provider, not by us.
        //
        // socketTimeoutMillis is the right instrument and still applies: 30s
        // with no bytes arriving still fails a dead or wedged host fast. Bytes
        // still moving means it is working, however slow the user's link is.
        timeout {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = 30_000
        }
        if (userAgent != null) header("User-Agent", userAgent)
        for ((k, v) in extraHeaders) header(k, v)
    }.execute { response ->
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "HTTP ${response.status.value} ${response.status.description} from ${LogSanitizer.redactUrl(url)}",
            )
        }
        // See [emptyBodyError]: a 2xx with nothing in it is a failure the caller
        // must not mistake for an empty playlist.
        if (response.status.value == 204) throw emptyBodyError(204, url)
        // Fail early and legibly rather than dying with ENOSPC half a gigabyte
        // in. Provider playlists are big enough that a cheap TV box with a
        // nearly-full data partition is a real scenario, not a hypothetical.
        val expected = response.contentLength() ?: -1L
        val free = dest.parentFile?.usableSpace ?: Long.MAX_VALUE
        if (expected > 0 && free < expected + SPACE_HEADROOM_BYTES) {
            throw IllegalStateException(
                "Not enough free space to download this playlist: it needs about " +
                    "${expected / 1_000_000}MB but only ${free / 1_000_000}MB is free. " +
                    "Free up some space and try again.",
            )
        }
        if (expected > LARGE_DOWNLOAD_BYTES) {
            Log.i(TAG, "large download starting: ~${expected / 1_000_000}MB")
        }
        var total = 0L
        var nextMark = PROGRESS_LOG_BYTES
        // Minimum-throughput floor. socketTimeoutMillis only fails TOTAL
        // silence; a wedged middlebox trickling a few bytes every 25 seconds
        // resets it forever, and with no total cap that hang is unbounded
        // (538MB at 1KB per 25s is ~160 days). Requiring 256KB of progress
        // per 2-minute window fails those pathological connections in
        // minutes while sitting far below any link a playlist could actually
        // finish over (256KB/120s is ~17 kbit/s).
        var windowStart = System.currentTimeMillis()
        var windowBytes = 0L
        response.bodyAsChannel().toInputStream().use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    windowBytes += n
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - windowStart >= THROUGHPUT_WINDOW_MS) {
                        if (windowBytes < THROUGHPUT_FLOOR_BYTES) {
                            throw IllegalStateException(
                                "Download stalled: only ${windowBytes / 1024}KB arrived in the " +
                                    "last ${THROUGHPUT_WINDOW_MS / 1000}s " +
                                    "(${total / 1_000_000}MB downloaded so far). " +
                                    "The server is barely responding; try again later.",
                            )
                        }
                        windowStart = nowMs
                        windowBytes = 0L
                    }
                    // Coarse progress so "stuck adding a playlist" reports come
                    // with evidence of whether bytes were actually moving,
                    // without logging inside a hot loop.
                    if (total >= nextMark) {
                        Log.i(TAG, "download progress: ${total / 1_000_000}MB")
                        nextMark += PROGRESS_LOG_BYTES
                    }
                }
            }
        }
        if (dest.length() == 0L) throw emptyBodyError(response.status.value, url)
        // A truncated body is worse than a failed one: it parses into a partial
        // playlist that looks perfectly legitimate, so the user silently loses
        // channels with no error anywhere. Content-Length disagreeing with what
        // landed means the connection died mid-stream.
        if (expected > 0 && total < expected) {
            throw IllegalStateException(
                "The playlist download ended early (${total / 1_000_000}MB of " +
                    "${expected / 1_000_000}MB). The connection dropped part-way " +
                    "through; please try again.",
            )
        }
        if (expected > LARGE_DOWNLOAD_BYTES) {
            Log.i(TAG, "large download complete: ${total / 1_000_000}MB")
        }
        dest
    }

    private companion object {
        const val TAG = "PlaylistFetcher"
        /** Only narrate downloads big enough to be worth narrating. */
        const val LARGE_DOWNLOAD_BYTES = 32L * 1_000_000
        const val PROGRESS_LOG_BYTES = 32L * 1_000_000
        /** Slack left on the partition beyond the payload itself. */
        const val SPACE_HEADROOM_BYTES = 64L * 1_000_000
        /** Minimum-throughput floor: see the comment in fetchToFile. */
        const val THROUGHPUT_WINDOW_MS = 120_000L
        const val THROUGHPUT_FLOOR_BYTES = 256L * 1024
    }
}

/** Cache validators from the last full download of a feed (conditional GET). */
data class FeedValidators(val etag: String?, val lastModified: String?)
