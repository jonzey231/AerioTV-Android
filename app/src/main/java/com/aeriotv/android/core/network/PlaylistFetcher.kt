package com.aeriotv.android.core.network

import com.aeriotv.android.core.debug.LogSanitizer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
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
        return response.readRawBytes()
    }
}
