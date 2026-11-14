package com.aeriotv.android.core.network

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.takeFrom
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
 *     http (rule 6 aside).
 *  4. Each hop is judged against the previous URL and the original request,
 *     and once a hop has dropped them no later hop of that chain gets them
 *     back, so a chain that leaves the host and comes back never carries them
 *     again.
 *  5. A 307 or 308 that would carry a request body to a hop that drops the
 *     sign-in is not followed: the redirect response comes back instead.
 *  6. A server behind a TLS proxy it does not trust: when a request carries
 *     the sign-in and its chain so far was all https and still signed in, a
 *     redirect to http on the same host, on port 80 or on the port of the
 *     request that answered, is followed as https on the original request's
 *     port, and keeps the sign-in. Every other downgrade still drops it
 *     ([tlsUpgradeTarget]); a request with no sign-in follows the Location as
 *     given.
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
     * Rule 6, a server behind a TLS proxy it does not trust: Dispatcharr then
     * builds absolute URLs as http on the request's host (core/utils.py
     * build_absolute_uri_with_port takes the forwarded scheme only from a
     * trusted proxy). When the chain so far was all https and still signed in,
     * a redirect from [answered] to [next] that is http on the same host, on
     * port 80 or on [answered]'s port, is followed as https on the original
     * request's port, and keeps the sign-in. Null for every other hop, which
     * the rules above judge as before. The caller supplies "all https and
     * signed in so far".
     */
    fun tlsUpgradeTarget(origin: HttpUrl, answered: HttpUrl, next: HttpUrl): HttpUrl? =
        if (tlsUpgradePort(origin.host, origin.isHttps, origin.port, answered.host, answered.isHttps, answered.port,
                next.scheme, next.host, next.port) != null
        ) next.newBuilder().scheme("https").port(origin.port).build() else null

    /** Rule 6 on parts: the port the hop goes to as https, or null when it is not that hop. */
    fun tlsUpgradePort(
        originHost: String, originHttps: Boolean, originPort: Int,
        answeredHost: String, answeredHttps: Boolean, answeredPort: Int,
        nextScheme: String, nextHost: String, nextPort: Int,
    ): Int? {
        if (!originHttps || !answeredHttps || !nextScheme.equals("http", ignoreCase = true)) return null
        if (nextHost.isEmpty() || !nextHost.equals(answeredHost, ignoreCase = true) ||
            !nextHost.equals(originHost, ignoreCase = true)
        ) return null
        if (nextPort != 80 && nextPort != answeredPort) return null
        return originPort.takeIf { it > 0 }
    }

    /**
     * First-request form of rules 1 to 3 and 6: [url] as it may be requested
     * with the sign-in of the server at one of [serverUrls], or null. An
     * absolute http URL a server behind an untrusted TLS proxy reported for its
     * own host comes back as https on the server URL's port.
     */
    fun serverUrlFor(url: String, serverUrls: Collection<String?>): String? {
        val target = url.trim().toHttpUrlOrNull() ?: return null
        for (raw in serverUrls) {
            val server = raw?.trim()?.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull() ?: continue
            if (keepsSignIn(server, target)) return target.toString()
            tlsUpgradeTarget(server, server, target)?.let { return it.toString() }
        }
        return null
    }

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
        /** Every hop so far was https (rule 6). */
        var allHttps = origin.isHttps
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
            hops.allHttps = hops.allHttps && request.url.isHttps
            hops.signedIn
        }
        val present = HEADERS.filter { request.header(it) != null }
        val vod = VodSignIn.isVodPath(request.url.encodedPath)
        val signed = when {
            !keep && present.isNotEmpty() -> {
                why?.let { log("[REDIRECT] $it hop: dropped ${present.joinToString(", ")}") }
                request.newBuilder().apply { HEADERS.forEach { removeHeader(it) } }.build()
            }
            // Rule 2: a same-host http-to-https upgrade keeps all four, but
            // OkHttp removed Authorization when the scheme changed. Only this
            // attempt's own value goes back, on a hop the rule keeps, and
            // never on a VOD hop.
            keep && !vod && hops.authorization != null && request.header("Authorization") == null ->
                request.newBuilder().header("Authorization", hops.authorization).build()
            else -> request
        }
        // VOD streams sign in with X-API-Key only (VodSignIn): Dispatcharr's
        // VOD proxy forwards the Authorization header to the provider.
        val sent = signed.header("Authorization")?.takeIf { vod }?.let { auth ->
            signed.newBuilder().apply {
                removeHeader("Authorization")
                if (signed.header("X-API-Key") == null) VodSignIn.apiKeyOf(auth)?.let { header("X-API-Key", it) }
            }.build()
        } ?: signed
        val response = chain.proceed(sent)
        // Rule 6: OkHttp follows the Location as given, so an http one on the
        // same host that the rule takes as https is handed back as https.
        if (response.isRedirect && present.isNotEmpty()) {
            val next = response.header("Location")?.let { request.url.resolve(it) }
            val upgraded = next?.let {
                synchronized(hops) {
                    if (hops.signedIn && hops.allHttps) tlsUpgradeTarget(hops.origin, request.url, it) else null
                }
            }
            if (upgraded != null) {
                log("[REDIRECT] https-to-http hop on the same host: followed as https on the request's port")
                return@Interceptor response.newBuilder().header("Location", upgraded.toString()).build()
            }
        }
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

    /**
     * Where a Ktor request's chain started, the hop it was last sent to, the
     * builder that sent it, whether the chain still signs in, and whether
     * every hop so far was https.
     */
    private class KtorHop(
        val origin: Url,
        val url: Url,
        val sentBy: HttpRequestBuilder,
        val signedIn: Boolean,
        val allHttps: Boolean,
    )

    private val KtorHopKey = AttributeKey<KtorHop>("AerioRedirectAuthHop")

    private fun Url.isHttps(): Boolean = protocol.isSecure()

    private fun Url.isHttp(): Boolean = protocol.name.equals("http", true) || protocol.name.equals("https", true)

    /** Rules 1 to 3 for one Ktor hop (Url.port is the effective port); a non-http scheme drops them. */
    private fun keeps(from: Url, to: Url): Boolean =
        from.isHttp() && to.isHttp() &&
            keepsSignIn(from.host, from.isHttps(), from.port, to.host, to.isHttps(), to.port)

    private val ktorRedirects = setOf(301, 302, 303, 307, 308)
    private const val MAX_TLS_UPGRADES = 20

    /**
     * Ktor plugin. Ktor's OkHttp engine follows no redirects (followRedirects
     * false); Ktor's HttpRedirect builds every hop as a copy of the previous
     * hop's builder, headers and attributes included, and sends it through the
     * send pipeline again. So this sees every hop, and a header removed here
     * stays removed on every later hop (rule 4). A builder sent again as it is
     * (no redirect) starts a new chain. HttpRedirect follows only GET and HEAD
     * (checkHttpMethod) and refuses an https-to-http redirect, so no request
     * body is ever re-sent (rule 5); rule 6's hop it never follows, so this
     * follows it, as https, before HttpRedirect sees the answer.
     */
    val Ktor = createClientPlugin("RedirectAuthRule") {
        on(SendingRequest) { request, _ ->
            val url = request.url.build()
            val previous = request.attributes.getOrNull(KtorHopKey)
            val hop = if (previous == null || previous.sentBy === request) {
                KtorHop(url, url, request, signedIn = true, allHttps = url.isHttps())
            } else {
                val fromPrevious = keeps(previous.url, url)
                val signedIn = previous.signedIn && fromPrevious && keeps(previous.origin, url)
                if (!signedIn) {
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
                KtorHop(previous.origin, url, request, signedIn, previous.allHttps && url.isHttps())
            }
            request.attributes.put(KtorHopKey, hop)
        }

        on(Send) { request ->
            var sent = request
            var call = proceed(sent)
            var upgrades = 0
            while (upgrades < MAX_TLS_UPGRADES) {
                val method = sent.method.value
                if (method != "GET" && method != "HEAD") break
                if (call.response.status.value !in ktorRedirects) break
                val hop = sent.attributes.getOrNull(KtorHopKey) ?: break
                if (!hop.signedIn || !hop.allHttps || HEADERS.none { sent.headers.contains(it) }) break
                val location = call.response.headers["Location"] ?: break
                val next = runCatching {
                    URLBuilder(hop.url).apply { parameters.clear() }.takeFrom(location).build()
                }.getOrNull() ?: break
                val port = tlsUpgradePort(
                    hop.origin.host, hop.origin.isHttps(), hop.origin.port,
                    hop.url.host, hop.url.isHttps(), hop.url.port,
                    next.protocol.name, next.host, next.port,
                ) ?: break
                log("[REDIRECT] https-to-http hop on the same host: followed as https on the request's port")
                val upgraded = URLBuilder(next).apply {
                    protocol = URLProtocol.HTTPS
                    this.port = port
                }.build()
                // A copy of the hop that answered, headers and attributes
                // included, on the https URL (HttpRedirect copies the same way).
                val following = HttpRequestBuilder().apply {
                    takeFrom(sent)
                    url.takeFrom(upgraded)
                }
                sent = following
                call = proceed(following)
                upgrades++
            }
            call
        }
    }
}
