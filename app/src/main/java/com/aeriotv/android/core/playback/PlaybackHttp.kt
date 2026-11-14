package com.aeriotv.android.core.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.aeriotv.android.core.network.RedirectAuthRule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * The HTTP source for every player path that used Media3's
 * DefaultHttpDataSource: on demand, catch-up, recordings, HLS and DASH in the
 * main player, the on-demand player, Multiview's on-demand and background
 * tiles, and the Cast Multiview composite.
 *
 * DefaultHttpDataSource (Media3 1.4.1) follows redirects itself when
 * cross-protocol redirects are allowed, and puts every request property back
 * on every hop: a Redirect-profile 302 carried the Dispatcharr X-API-Key and
 * Authorization to whatever host the Location named. It has no redirect hook
 * (makeConnection and handleRedirect are private), and with cross-protocol
 * redirects off it hands them to HttpURLConnection, which follows them out of
 * the app's reach. OkHttp follows the same redirects and lets the shared rule
 * ([RedirectAuthRule]) judge every hop.
 */
@OptIn(UnstableApi::class)
object PlaybackHttp {
    private const val TAG = "AerioPlaybackHttp"

    /** The timeouts these paths used (30 s connect and read). */
    const val DEFAULT_TIMEOUT_MS = 30_000

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // setAllowCrossProtocolRedirects(true) on the old source.
            .followRedirects(true)
            .followSslRedirects(true)
            // HttpURLConnection spoke HTTP/1.1, one socket per stream; keep
            // that isolation (see TileLiveCalls on HTTP/2 multiplexing).
            .protocols(listOf(Protocol.HTTP_1_1))
            .let(RedirectAuthRule::install)
            .build()
    }

    /** One client per timeout pair, all sharing [base]'s pool and dispatcher. */
    private val clients = ConcurrentHashMap<Pair<Int, Int>, OkHttpClient>()

    internal fun client(connectTimeoutMs: Int, readTimeoutMs: Int): OkHttpClient =
        clients.getOrPut(connectTimeoutMs to readTimeoutMs) {
            base.newBuilder()
                .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .let(RedirectAuthRule::install)
                .build()
        }

    /**
     * A data source factory sending [headers] (the playlist's stream headers).
     * A User-Agent header wins over [fallbackUserAgent], as it did before.
     */
    fun factory(
        headers: Map<String, String>,
        fallbackUserAgent: String,
        connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
        transferListener: TransferListener? = null,
    ): DataSource.Factory {
        val headerUa = headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
        val factory = OkHttpDataSource.Factory(client(connectTimeoutMs, readTimeoutMs))
            .setUserAgent(safeUserAgent(headerUa ?: fallbackUserAgent, fallbackUserAgent))
        val rest = safeHeaders(headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        if (rest.isNotEmpty()) factory.setDefaultRequestProperties(rest)
        if (transferListener != null) factory.setTransferListener(transferListener)
        return factory
    }

    /** GH #32 parity with the live holder: okhttp3 throws on header characters
     *  HttpURLConnection tolerated. Drop an illegal name, strip illegal value
     *  characters (tab or 0x20..0x7e are legal). Never log a value. */
    private fun safeHeaders(headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return headers
        val out = LinkedHashMap<String, String>(headers.size)
        for ((name, value) in headers) {
            if (name.isEmpty() || !name.all { it.code in 0x21..0x7e }) {
                Log.w(TAG, "GH#32: dropped request header with illegal name (len=${name.length})")
                continue
            }
            val safe = safeValue(value)
            if (safe !== value) Log.w(TAG, "GH#32: stripped illegal char(s) from '$name' header value")
            out[name] = safe
        }
        return out
    }

    private fun safeUserAgent(ua: String, fallback: String): String =
        safeValue(ua).ifBlank { safeValue(fallback) }

    private fun safeValue(value: String): String {
        if (value.all { it == '\t' || it.code in 0x20..0x7e }) return value
        return buildString(value.length) {
            for (c in value) if (c == '\t' || c.code in 0x20..0x7e) append(c)
        }
    }
}
