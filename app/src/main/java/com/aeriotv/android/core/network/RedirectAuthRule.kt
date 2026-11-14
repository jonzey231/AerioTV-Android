package com.aeriotv.android.core.network

import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.Url
import io.ktor.http.isSecure
import io.ktor.util.AttributeKey
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Sign-in headers on a redirect: one rule for every client that sends them,
 * the same rule as the Apple app.
 *
 *  1. A hop keeps X-API-Key, Authorization, Cookie, and X-Emby-Authorization
 *     only when it stays on the same host AND the same effective port (the
 *     explicit port, else 80 for http and 443 for https), and the scheme is
 *     not downgraded.
 *  2. An http-to-https upgrade on the same host keeps them when the ports are
 *     the defaults (80 to 443) or the port is unchanged.
 *  3. Every other hop drops all four: another host, another port, or https to
 *     http.
 *  4. Each hop is judged against the previous URL and the original request,
 *     and once a hop has dropped them no later hop of that chain gets them
 *     back, so a chain that leaves the host and comes back never carries them
 *     again.
 *  5. A 307 or 308 that would carry a request body to a hop that drops the
 *     sign-in is not followed: the redirect response comes back instead.
 *
 * Why: a Dispatcharr channel on a Redirect stream profile answers 302 straight
 * to the provider, and the HTTP stacks re-send the request's own headers on
 * every hop. OkHttp 5.3.2 drops only Authorization, and only when host, port,
 * or scheme change (RetryAndFollowUpInterceptor.buildRedirectRequest), and
 * re-sends a request body on 307 and 308. Ktor 3.4.3's HttpRedirect drops only
 * Authorization across authorities. Media3 1.4.1's DefaultHttpDataSource
 * re-applies every request property on every hop. The user's Dispatcharr key
 * reached the provider.
 *
 * The app sets no cookie jar anywhere, so a Cookie header can only come from
 * the request itself.
 */
object RedirectAuthRule {
    const val TAG = "AerioRedirectAuth"

    /** The headers that sign in to a server and never follow a redirect off it. */
    val HEADERS: List<String> = listOf("X-API-Key", "Authorization", "Cookie", "X-Emby-Authorization")

    /** Log sink (tests replace it; android.util.Log is not available on the JVM). */
    @Volatile var log: (String) -> Unit = { android.util.Log.i(TAG, it) }

    fun isSignInHeader(name: String): Boolean = HEADERS.any { it.equals(name, ignoreCase = true) }

    /** [headers] without the sign-in headers, for a request that goes to another host. */
    fun withoutSignIn(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { !isSignInHeader(it) }

    /** Rules 1 to 3 for one hop between http(s) URLs. Ports are effective ports; a missing host or port drops them. */
    fun keepsSignIn(
        fromHost: String, fromHttps: Boolean, fromPort: Int,
        toHost: String, toHttps: Boolean, toPort: Int,
    ): Boolean {
        if (fromHost.isEmpty() || toHost.isEmpty() || fromPort <= 0 || toPort <= 0) return false
        if (!fromHost.equals(toHost, ignoreCase = true)) return false
        if (fromHttps && !toHttps) return false
        if (fromPort == toPort) return true
        return !fromHttps && toHttps && fromPort == 80 && toPort == 443
    }

    /** Rules 1 to 3 for one hop (HttpUrl.port is the effective port). */
    fun keepsSignIn(from: HttpUrl, to: HttpUrl): Boolean =
        keepsSignIn(from.host, from.isHttps, from.port, to.host, to.isHttps, to.port)

    /** String form. A URL that does not parse never keeps them. */
    fun keepsSignIn(from: String, to: String): Boolean {
        val a = from.toHttpUrlOrNull() ?: return false
        val b = to.toHttpUrlOrNull() ?: return false
        return keepsSignIn(a, b)
    }

    /** Rule 4 for the hop to [next] in a chain that started at [origin] and last went to [previous]. */
    fun keepsSignIn(origin: HttpUrl, previous: HttpUrl, next: HttpUrl): Boolean =
        keepsSignIn(previous, next) && keepsSignIn(origin, next)

    /**
     * First-request gate, the same host and port rule: true when a request to
     * [url] may carry the sign-in of the server reached at any of [serverUrls]
     * (a playlist's remote and LAN addresses), as a redirect from that URL
     * would. With [withinPath], [url] must also sit under the server URL's
     * path (a server behind a reverse-proxy subpath).
     */
    fun isServerUrl(url: String, serverUrls: Collection<String?>, withinPath: Boolean = false): Boolean {
        val target = url.trim().toHttpUrlOrNull() ?: return false
        return serverUrls.any { raw ->
            val server = raw?.trim()?.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull() ?: return@any false
            keepsSignIn(server, target) && (!withinPath || target.isUnderPathOf(server))
        }
    }

    private fun HttpUrl.isUnderPathOf(server: HttpUrl): Boolean {
        val base = server.encodedPath.trimEnd('/')
        return base.isEmpty() || encodedPath == base || encodedPath.startsWith("$base/")
    }

    /** Why a hop drops them, for the log (never the URL). */
    private fun reason(fromHost: String, fromHttps: Boolean, toHost: String, toHttps: Boolean): String = when {
        !fromHost.equals(toHost, ignoreCase = true) -> "cross-host"
        fromHttps && !toHttps -> "https-to-http"
        else -> "cross-port"
    }

    private fun reason(origin: HttpUrl, previous: HttpUrl, next: HttpUrl): String {
        val from = if (!keepsSignIn(previous, next)) previous else origin
        return reason(from.host, from.isHttps, next.host, next.isHttps)
    }

    // ---- OkHttp ----

    /**
     * One attempt's redirect chain: where it started, the last hop, whether it
     * still signs in, and the Authorization the attempt was sent with (OkHttp
     * drops that one itself on any scheme or port change, rule 2's upgrades
     * included).
     */
    private class Hops(val origin: HttpUrl, val authorization: String?) {
        var last: HttpUrl = origin
        var signedIn = true
    }

    /**
     * Application interceptor, kept innermost by [install]: a fresh [Hops] per
     * attempt, carried on the request's tags (OkHttp builds every redirect hop
     * from the previous hop's request, tags included). A retry an outer
     * interceptor makes (StreamGate after a 401) is a new attempt and starts
     * signed in again.
     */
    private val stamp = Interceptor { chain ->
        val request = chain.request()
        val hops = Hops(request.url, request.header("Authorization"))
        chain.proceed(request.newBuilder().tag(Hops::class.java, hops).build())
    }

    /**
     * Network interceptor: runs for every hop, including each redirect OkHttp
     * follows on its own. OkHttp re-sends the request's own headers on the
     * next hop no matter what this interceptor sent, so every hop is judged
     * here, and the chain's state lives in [Hops].
     */
    private val strip = Interceptor { chain ->
        val request = chain.request()
        // Built without the stamp: judge every hop against the call's own URL.
        val hops = request.tag(Hops::class.java) ?: Hops(chain.call().request().url, null)
        var why: String? = null
        val keep = synchronized(hops) {
            if (hops.signedIn && !keepsSignIn(hops.origin, hops.last, request.url)) {
                hops.signedIn = false
                why = reason(hops.origin, hops.last, request.url)
            }
            hops.last = request.url
            hops.signedIn
        }
        val present = HEADERS.filter { request.header(it) != null }
        val sent = when {
            !keep && present.isNotEmpty() -> {
                why?.let { log("[REDIRECT] $it hop: dropped ${present.joinToString(", ")}") }
                request.newBuilder().apply { HEADERS.forEach { removeHeader(it) } }.build()
            }
            // Rule 2: a same-host http-to-https upgrade keeps all four, but
            // OkHttp removed Authorization when the scheme changed. Only this
            // attempt's own value goes back, on a hop the rule keeps.
            keep && hops.authorization != null && request.header("Authorization") == null ->
                request.newBuilder().header("Authorization", hops.authorization).build()
            else -> request
        }
        val response = chain.proceed(sent)
        // Rule 5: OkHttp follows a 307 or 308 with the same method and body.
        // Without a Location it returns this response instead of following.
        if ((response.code == 307 || response.code == 308) && request.body != null) {
            val next = response.header("Location")?.let { request.url.resolve(it) }
            if (next != null) {
                val drops = synchronized(hops) { !hops.signedIn || !keepsSignIn(hops.origin, request.url, next) }
                if (drops) {
                    log(
                        "[REDIRECT] ${response.code} with a request body to a " +
                            "${reason(hops.origin, request.url, next)} hop: not followed",
                    )
                    return@Interceptor response.newBuilder().removeHeader("Location").build()
                }
            }
        }
        response
    }

    /**
     * Installs the rule on an OkHttp client that follows redirects. Call it
     * last, right before build(), so the per-attempt stamp is the innermost
     * application interceptor. Safe to call again on a builder derived with
     * newBuilder().
     */
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder.apply {
        interceptors().removeAll { it === stamp }
        interceptors().add(stamp)
        if (networkInterceptors().none { it === strip }) networkInterceptors().add(strip)
    }

    // ---- Ktor ----

    /** Where a Ktor request's chain started, the hop it was last sent to, and the builder that sent it. */
    private class KtorHop(val origin: Url, val url: Url, val sentBy: HttpRequestBuilder)

    private val KtorHopKey = AttributeKey<KtorHop>("AerioRedirectAuthHop")

    private fun Url.isHttps(): Boolean = protocol.isSecure()

    private fun Url.isHttp(): Boolean = protocol.name.equals("http", true) || protocol.name.equals("https", true)

    /** Rules 1 to 3 for one Ktor hop (Url.port is the effective port); a non-http scheme drops them. */
    private fun keeps(from: Url, to: Url): Boolean =
        from.isHttp() && to.isHttp() &&
            keepsSignIn(from.host, from.isHttps(), from.port, to.host, to.isHttps(), to.port)

    /**
     * Ktor plugin. Ktor's OkHttp engine follows no redirects (followRedirects
     * false); Ktor's HttpRedirect builds every hop as a copy of the previous
     * hop's builder, headers and attributes included, and sends it through the
     * send pipeline again. So this sees every hop, and a header removed here
     * stays removed on every later hop (rule 4). A builder sent again as it is
     * (no redirect) starts a new chain. HttpRedirect follows only GET and HEAD
     * (checkHttpMethod) and refuses an https-to-http redirect, so no request
     * body is ever re-sent (rule 5).
     */
    val Ktor = createClientPlugin("RedirectAuthRule") {
        on(SendingRequest) { request, _ ->
            val url = request.url.build()
            val previous = request.attributes.getOrNull(KtorHopKey)
            val origin = if (previous == null || previous.sentBy === request) {
                url
            } else {
                val fromPrevious = keeps(previous.url, url)
                if (!fromPrevious || !keeps(previous.origin, url)) {
                    val present = HEADERS.filter { request.headers.contains(it) }
                    if (present.isNotEmpty()) {
                        HEADERS.forEach { request.headers.remove(it) }
                        val from = if (!fromPrevious) previous.url else previous.origin
                        log(
                            "[REDIRECT] ${reason(from.host, from.isHttps(), url.host, url.isHttps())} hop: " +
                                "dropped ${present.joinToString(", ")}",
                        )
                    }
                }
                previous.origin
            }
            request.attributes.put(KtorHopKey, KtorHop(origin, url, request))
        }
    }
}
