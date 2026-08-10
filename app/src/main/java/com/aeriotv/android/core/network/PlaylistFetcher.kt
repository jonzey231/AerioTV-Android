package com.aeriotv.android.core.network

import com.aeriotv.android.core.debug.LogSanitizer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
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
    suspend fun fetchToFile(
        url: String,
        dest: File,
        userAgent: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): File = client.prepareGet(url) {
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
        response.bodyAsChannel().toInputStream().use { input ->
            dest.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        }
        if (dest.length() == 0L) throw emptyBodyError(response.status.value, url)
        dest
    }
}
