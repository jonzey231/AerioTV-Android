package com.aeriotv.android.core.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * GH #159: a Dispatcharr channel whose stream profile is Redirect answers the
 * live URL (`/proxy/ts/stream/<uuid>` and the XC `/live/...` route) with an
 * HTTP 302 straight to the provider URL (apps/proxy/live_proxy/views.py
 * `_handle_redirect_stream` -> `HttpResponseRedirect`). What comes back from
 * the provider is whatever it serves: raw MPEG-TS, an HLS playlist, or another
 * redirect. Live tunes are routed to the TS-only extractor by URL shape, so an
 * HLS answer hit the TS extractor and failed with
 * ERROR_CODE_PARSING_CONTAINER_MALFORMED.
 */
object LiveRedirect {
    const val TAG = "AerioLiveSource"

    /** Headers that authenticate to Dispatcharr and must never follow a
     *  redirect to another host. */
    private val AUTH_HEADERS = listOf("X-API-Key", "Authorization", "Cookie")

    enum class Content { TS, HLS, UNKNOWN }

    /** Classifies the first bytes of a body: 0x47 TS sync, `#EXTM3U`, or neither. */
    fun sniff(bytes: ByteArray, length: Int): Content {
        var i = 0
        // UTF-8 BOM and leading whitespace in front of a playlist.
        if (length >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) i = 3
        while (i < length && (bytes[i] == ' '.code.toByte() || bytes[i] == '\r'.code.toByte() ||
                bytes[i] == '\n'.code.toByte() || bytes[i] == '\t'.code.toByte())
        ) i++
        val tag = "#EXTM3U".toByteArray()
        if (length - i >= tag.size && (0 until tag.size).all { bytes[i + it] == tag[it] }) return Content.HLS
        // TS: a sync byte at a packet boundary. Two aligned syncs when there is
        // room, one when the peek is short.
        if (length > 0 && bytes[0] == 0x47.toByte()) {
            return if (length > 188) {
                if (bytes[188] == 0x47.toByte()) Content.TS else Content.UNKNOWN
            } else Content.TS
        }
        return Content.UNKNOWN
    }

    /** "same-host" or "cross-host" for the log line; never the URL itself. */
    fun hostClass(requested: String, landed: String): String {
        val a = runCatching { java.net.URI(requested).host?.lowercase() }.getOrNull()
        val b = runCatching { java.net.URI(landed).host?.lowercase() }.getOrNull()
        return if (a != null && a == b) "same-host" else "cross-host"
    }

    /**
     * OkHttp network interceptor: on every hop after a redirect to a DIFFERENT
     * host than the original request, drop Dispatcharr's auth headers. OkHttp
     * itself only strips Authorization; X-API-Key would ride along to the
     * provider.
     */
    val stripAuthOnCrossHost = okhttp3.Interceptor { chain ->
        val req = chain.request()
        val originalHost = chain.call().request().url.host
        if (!req.url.host.equals(originalHost, ignoreCase = true) &&
            AUTH_HEADERS.any { req.header(it) != null }
        ) {
            val b = req.newBuilder()
            AUTH_HEADERS.forEach { b.removeHeader(it) }
            chain.proceed(b.build())
        } else {
            chain.proceed(req)
        }
    }

    /** Copy of [headers] without the Dispatcharr auth headers (for a source
     *  that talks to the provider directly). */
    fun withoutAuth(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { k -> AUTH_HEADERS.none { it.equals(k, ignoreCase = true) } }

    fun findUpstreamHls(t: Throwable?): UpstreamHlsRedirectException? =
        generateSequence(t) { it.cause }.take(12).firstOrNull { it is UpstreamHlsRedirectException }
            as UpstreamHlsRedirectException?
}

/** A live TS open was redirected to a provider that answered with an HLS
 *  playlist; the holder re-tunes [landedUrl] through HlsMediaSource. */
class UpstreamHlsRedirectException(val entryUrl: String, val landedUrl: String) :
    java.io.IOException("Live URL redirected to an HLS playlist")

/**
 * Wraps the live raw-TS data source. When the open was redirected (the final
 * URI differs from the requested one), peeks the first bytes, logs the
 * outcome, and fails an HLS answer with [UpstreamHlsRedirectException] so it
 * never reaches the TS extractor. Peeked bytes are replayed to the reader, so
 * a TS answer plays exactly as before. A non-redirected open is untouched.
 */
@OptIn(UnstableApi::class)
class LiveRedirectSniff(private val upstream: DataSource) : DataSource {
    private var peek: ByteArray? = null
    private var peekPos = 0
    private var peekLen = 0

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        peek = null
        val len = upstream.open(dataSpec)
        val req = dataSpec.uri.toString()
        val landed = upstream.uri?.toString() ?: return len
        if (landed == req || ForceHls.isSessionUrl(landed)) return len
        val buf = ByteArray(PEEK_BYTES)
        var n = 0
        // A live body can arrive in small chunks; read until there is enough
        // to classify, the stream ends, or the peek fills.
        while (n < MIN_CLASSIFY_BYTES) {
            val r = upstream.read(buf, n, buf.size - n)
            if (r == C.RESULT_END_OF_INPUT) break
            n += r
        }
        val content = LiveRedirect.sniff(buf, n)
        Log.i(
            LiveRedirect.TAG,
            "[LIVE] redirect -> ${LiveRedirect.hostClass(req, landed)} content=${content.name.lowercase()}",
        )
        if (content == LiveRedirect.Content.HLS) {
            try { upstream.close() } catch (_: Exception) {}
            throw UpstreamHlsRedirectException(req, landed)
        }
        peek = buf
        peekPos = 0
        peekLen = n
        return len
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val p = peek
        if (p != null && peekPos < peekLen) {
            val c = minOf(length, peekLen - peekPos)
            System.arraycopy(p, peekPos, buffer, offset, c)
            peekPos += c
            if (peekPos >= peekLen) peek = null
            return c
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): android.net.Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        peek = null
        upstream.close()
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = LiveRedirectSniff(upstream.createDataSource())
    }

    private companion object {
        const val PEEK_BYTES = 1024
        const val MIN_CLASSIFY_BYTES = 376
    }
}
