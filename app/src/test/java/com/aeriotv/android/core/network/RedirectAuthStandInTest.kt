package com.aeriotv.android.core.network

import com.aeriotv.android.core.playback.PlaybackHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The sign-in redirect rule measured against a local stand-in. Two host names,
 * dispatcharr.test and provider.test, resolve to 127.0.0.1 in the clients'
 * DNS. One loopback server answers plain HTTP and TLS on the same port (it
 * peeks the first byte), so an https-to-http or http-to-https hop is measured
 * without a port change; a second server is the "other port". Every request
 * is recorded as its Host, method, body size, and whether each sign-in header
 * was present. Never a value. Fake credentials only; nothing leaves the
 * machine.
 *
 * Every measurement prints one "[MEASURE]" line (Gradle keeps them in the test
 * report). The "before" lines are the libraries without the rule.
 */
class RedirectAuthStandInTest {

    /** One request as the stand-in saw it. Never a header or body value. */
    data class Seen(val hostPort: String, val method: String, val present: Map<String, Boolean>, val bodyBytes: Int) {
        val none get() = present.values.none { it }
        val all get() = present.values.all { it }
        override fun toString() = "host=$hostPort $method " +
            RedirectAuthRule.HEADERS.joinToString(" ") { "$it=${if (present[it] == true) "present" else "absent"}" } +
            (if (method == "GET" || method == "HEAD") "" else " body=${bodyBytes}B")
    }

    private val seen = ConcurrentHashMap<String, Seen>()
    private val ruleLog = CopyOnWriteArrayList<String>()

    private lateinit var main: StandIn
    private lateinit var other: StandIn

    /** dispatcharr.test and provider.test (any *.test) resolve to the loopback stand-in. */
    private val dns = Dns { host ->
        if (host.endsWith(".test")) listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)))
        else Dns.SYSTEM.lookup(host)
    }

    @Before fun setUp() {
        RedirectAuthRule.log = { ruleLog += it }
        main = StandIn(tls?.server)
        other = StandIn(tls?.server)
    }

    @After fun tearDown() {
        main.close()
        other.close()
    }

    // ---- the stand-in ----

    private class Req(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
    private class Resp(val code: Int, val location: String? = null)

    private inner class StandIn(private val tlsContext: SSLContext?) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        private val threads = Executors.newCachedThreadPool()

        init {
            threads.execute {
                while (!socket.isClosed) {
                    val s = try { socket.accept() } catch (_: IOException) { break }
                    threads.execute {
                        runCatching { serve(s) }
                        runCatching { s.close() }
                    }
                }
            }
        }

        private fun serve(raw: Socket) {
            raw.soTimeout = 10_000
            val rawIn = raw.getInputStream()
            val first = rawIn.read()
            if (first < 0) return
            val peeked = ByteArrayInputStream(byteArrayOf(first.toByte()))
            // 0x16 opens a TLS handshake: layer TLS over the socket, handing it the peeked byte.
            val secure = first == 0x16 && tlsContext != null
            val (input, output) = if (secure) {
                // SSLSocketFactory.createSocket(Socket, InputStream consumed, boolean):
                // a JDK method the android.jar these tests compile against lacks.
                val ssl = javax.net.ssl.SSLSocketFactory::class.java
                    .getMethod("createSocket", Socket::class.java, InputStream::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(tlsContext!!.socketFactory, raw, peeked, true) as SSLSocket
                ssl.useClientMode = false
                ssl.inputStream to ssl.outputStream
            } else {
                SequenceInputStream(peeked, rawIn) to raw.getOutputStream()
            }
            val req = readRequest(BufferedInputStream(input)) ?: return
            seen[req.path] = Seen(
                hostPort = req.headers["host"].orEmpty(),
                method = req.method,
                present = RedirectAuthRule.HEADERS.associateWith { req.headers.containsKey(it.lowercase()) },
                bodyBytes = req.body.size,
            )
            respond(output, route(req, if (secure) "https" else "http", port))
        }

        override fun close() {
            socket.close()
            threads.shutdownNow()
        }
    }

    private fun route(req: Req, scheme: String, port: Int): Resp {
        val here = "$scheme://dispatcharr.test:$port"
        val provider = "$scheme://provider.test:$port"
        return when (req.path) {
            "/same" -> Resp(302, "$here/landing/same")
            "/cross" -> Resp(302, "$provider/landing/cross")
            "/other-port" -> Resp(302, "$scheme://dispatcharr.test:${other.port}/landing/other-port")
            "/away-and-back" -> Resp(302, "$provider/bounce")
            "/bounce" -> Resp(302, "$here/landing/back")
            "/down" -> Resp(302, "http://dispatcharr.test:$port/landing/down")
            "/up" -> Resp(302, "https://dispatcharr.test:$port/landing/up")
            "/post-same-307" -> Resp(307, "$here/landing/post-same-307")
            "/post-cross-307" -> Resp(307, "$provider/landing/post-cross-307")
            "/post-cross-308" -> Resp(308, "$provider/landing/post-cross-308")
            "/post-cross-302" -> Resp(302, "$provider/landing/post-cross-302")
            else -> when {
                // /code/<n>: that redirect code to another host.
                req.path.startsWith("/code/") -> req.path.removePrefix("/code/").toInt()
                    .let { code -> Resp(code, "$provider/landing/code-$code") }
                req.path.startsWith("/landing/") -> Resp(200)
                else -> Resp(404)
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun readRequest(input: InputStream): Req? {
        val parts = (readLine(input) ?: return null).split(' ')
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var n = 0
        while (n < length) {
            val r = input.read(body, n, length - n)
            if (r < 0) break
            n += r
        }
        return Req(parts[0], parts[1].substringBefore('?'), headers, body.copyOf(n))
    }

    private fun respond(out: OutputStream, r: Resp) {
        val reason = when (r.code) {
            200 -> "OK"; 301 -> "Moved Permanently"; 302 -> "Found"; 303 -> "See Other"
            307 -> "Temporary Redirect"; 308 -> "Permanent Redirect"; else -> "Not Found"
        }
        val body = if (r.code == 200) "ok".toByteArray() else ByteArray(0)
        val head = StringBuilder("HTTP/1.1 ${r.code} $reason\r\n")
        r.location?.let { head.append("Location: $it\r\n") }
        head.append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    // ---- clients ----

    private fun url(path: String, scheme: String = "http") = "$scheme://dispatcharr.test:${main.port}$path"

    private fun OkHttpClient.Builder.standIn(): OkHttpClient.Builder = dns(dns).apply {
        tls?.let { sslSocketFactory(it.client.socketFactory, it.trust) }
    }

    private fun okHttp(rule: Boolean): OkHttpClient =
        OkHttpClient.Builder().standIn().let { if (rule) RedirectAuthRule.install(it) else it }.build()

    private fun Request.Builder.signedIn(): Request.Builder = header("X-API-Key", KEY)
        .header("Authorization", "ApiKey $KEY")
        .header("Cookie", COOKIE)
        .header("X-Emby-Authorization", EMBY)

    /** GET with all four sign-in headers; the final status. */
    private fun OkHttpClient.get(url: String): Int =
        newCall(Request.Builder().url(url).signedIn().build()).execute().use { it.body.string(); it.code }

    /** POST with a body and all four sign-in headers; the final status. */
    private fun OkHttpClient.post(url: String): Int =
        newCall(Request.Builder().url(url).signedIn().post(PAYLOAD.toRequestBody("text/plain".toMediaType())).build())
            .execute().use { it.body.string(); it.code }

    private fun ktor(rule: Boolean) = HttpClient(OkHttp) {
        engine {
            config {
                dns(dns)
                tls?.let { sslSocketFactory(it.client.socketFactory, it.trust) }
            }
        }
        if (rule) install(RedirectAuthRule.Ktor)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.signedIn() {
        header("X-API-Key", KEY)
        header("Authorization", "ApiKey $KEY")
        header("Cookie", COOKIE)
        header("X-Emby-Authorization", EMBY)
    }

    private fun HttpClient.getStatus(url: String): Int = runBlocking {
        get(url) { signedIn() }.also { it.bodyAsText() }.status.value
    }

    private fun HttpClient.postStatus(url: String): Int = runBlocking {
        post(url) { signedIn(); setBody(PAYLOAD) }.also { it.bodyAsText() }.status.value
    }

    private fun measure(label: String, path: String): Seen {
        val s = seen[path]
        println("[MEASURE] $label: ${s ?: "no request reached $path"}")
        assertNotNull("a request reached $path", s)
        return s!!
    }

    private fun measureNothing(label: String, status: Int, path: String) {
        val s = seen[path]
        println("[MEASURE] $label: status=$status, request reached the target=${s != null}")
        assertNull("nothing reached $path", s)
    }

    private fun needsTls() = assumeTrue("keytool is needed for the https stand-in", tls != null)

    // ---- before: the libraries alone ----

    @Test fun beforeOkHttpAlone() {
        needsTls()
        val c = okHttp(rule = false)
        c.get(url("/cross")); measure("before, OkHttp 5.3.2 alone, GET 302 to another host", "/landing/cross")
        c.get(url("/other-port")); measure("before, OkHttp 5.3.2 alone, GET 302 to another port", "/landing/other-port")
        c.get(url("/down", "https")); measure("before, OkHttp 5.3.2 alone, GET 302 https to http, same host and port", "/landing/down")
        c.post(url("/post-cross-307")); measure("before, OkHttp 5.3.2 alone, POST 307 to another host", "/landing/post-cross-307")
        c.post(url("/post-cross-308")); measure("before, OkHttp 5.3.2 alone, POST 308 to another host", "/landing/post-cross-308")
    }

    @Test fun beforeKtorAlone() {
        ktor(rule = false).use { c ->
            c.getStatus(url("/cross"))
            c.getStatus(url("/other-port"))
            val status = c.postStatus(url("/post-cross-307"))
            measure("before, Ktor 3.4.3 HttpRedirect alone, GET 302 to another host", "/landing/cross")
            measure("before, Ktor 3.4.3 HttpRedirect alone, GET 302 to another port", "/landing/other-port")
            println("[MEASURE] before, Ktor 3.4.3 HttpRedirect alone, POST 307 to another host: status=$status, " +
                "request reached the target=${seen.containsKey("/landing/post-cross-307")}")
        }
    }

    // ---- OkHttp with the rule (live player, tiles, Cast ingest, Live Rewind, recordings, images, Drive) ----

    @Test fun okHttpAnotherHostDropsAllFour() {
        okHttp(rule = true).get(url("/cross"))
        val s = measure("OkHttp + rule, GET 302 to another host", "/landing/cross")
        assertTrue(s.hostPort.startsWith("provider.test:"))
        assertTrue(s.none)
        assertTrue(ruleLog.any { it.contains("cross-host") })
        assertFalse("the log never carries a value", ruleLog.any { it.contains("not-real") })
    }

    @Test fun everyRedirectCodeToAnotherHostDropsAllFour() {
        val ok = okHttp(rule = true)
        ktor(rule = true).use { kt ->
            for (code in listOf(301, 302, 303, 307, 308)) {
                ok.get(url("/code/$code"))
                assertTrue(measure("OkHttp + rule, GET $code to another host", "/landing/code-$code").none)
                seen.clear()
                kt.getStatus(url("/code/$code"))
                assertTrue(measure("Ktor + rule, GET $code to another host", "/landing/code-$code").none)
                seen.clear()
            }
        }
    }

    @Test fun okHttpSameHostAndPortKeepsAllFour() {
        okHttp(rule = true).get(url("/same"))
        assertTrue(measure("OkHttp + rule, GET 302 same host and port", "/landing/same").all)
        assertTrue(ruleLog.isEmpty())
    }

    @Test fun okHttpAnotherPortDropsAllFour() {
        okHttp(rule = true).get(url("/other-port"))
        val s = measure("OkHttp + rule, GET 302 same host, another port", "/landing/other-port")
        assertEquals("dispatcharr.test:${other.port}", s.hostPort)
        assertTrue(s.none)
        assertTrue(ruleLog.any { it.contains("cross-port") })
    }

    @Test fun okHttpChainThatLeavesAndComesBackNeverGetsThemBack() {
        okHttp(rule = true).get(url("/away-and-back"))
        assertTrue(measure("OkHttp + rule, GET 302 to another host (hop 1)", "/bounce").none)
        val back = measure("OkHttp + rule, then 302 back to the original host and port (hop 2)", "/landing/back")
        assertEquals("dispatcharr.test:${main.port}", back.hostPort)
        assertTrue(back.none)
    }

    @Test fun okHttpDowngradeDropsAllFourAndUpgradeOnTheSamePortKeepsThem() {
        needsTls()
        val c = okHttp(rule = true)
        c.get(url("/down", "https"))
        c.get(url("/up"))
        val down = measure("OkHttp + rule, GET 302 https to http, same host and port", "/landing/down")
        val up = measure("OkHttp + rule, GET 302 http to https, same host and port", "/landing/up")
        assertTrue(down.none)
        assertTrue(ruleLog.any { it.contains("https-to-http") })
        assertTrue(up.all)
    }

    @Test fun okHttpBodyIsNeverSentToAHopThatDropsTheSignIn() {
        val c = okHttp(rule = true)
        val s307 = c.post(url("/post-cross-307"))
        val s308 = c.post(url("/post-cross-308"))
        measureNothing("OkHttp + rule, POST 307 to another host", s307, "/landing/post-cross-307")
        measureNothing("OkHttp + rule, POST 308 to another host", s308, "/landing/post-cross-308")
        assertEquals(307, s307)
        assertEquals(308, s308)
        assertTrue(ruleLog.any { it.contains("not followed") })
        // A 307 on the same host and port still follows, body and sign-in kept.
        c.post(url("/post-same-307"))
        val same = measure("OkHttp + rule, POST 307 same host and port", "/landing/post-same-307")
        assertTrue(same.all)
        assertEquals(PAYLOAD.length, same.bodyBytes)
        // A 302 turns the POST into a GET with no body: followed, sign-in dropped.
        c.post(url("/post-cross-302"))
        val get = measure("OkHttp + rule, POST 302 to another host (becomes GET)", "/landing/post-cross-302")
        assertEquals("GET", get.method)
        assertTrue(get.none)
    }

    @Test fun okHttpRetryByAnOuterInterceptorStartsSignedInAgain() {
        // StreamGate retries an open once with a re-issued key; that retry is a
        // new attempt and must carry the headers to the server again.
        val retrying = Interceptor { chain ->
            chain.proceed(chain.request()).close()
            chain.proceed(chain.request().newBuilder().url(url("/landing/retry")).build())
        }
        OkHttpClient.Builder().standIn().addInterceptor(retrying).let(RedirectAuthRule::install).build()
            .get(url("/cross"))
        assertTrue(measure("OkHttp + rule, first attempt 302 to another host", "/landing/cross").none)
        assertTrue(measure("OkHttp + rule, retry to the original host", "/landing/retry").all)
    }

    @Test fun playbackClientFollowsTheRule() {
        // The client behind every Media3 source that was DefaultHttpDataSource
        // (on demand, catch-up, recordings, Multiview, Cast composite).
        val c = PlaybackHttp.client(30_000, 30_000).newBuilder().standIn().build()
        c.get(url("/cross"))
        c.get(url("/same"))
        c.get(url("/other-port"))
        assertTrue(measure("PlaybackHttp client, GET 302 to another host", "/landing/cross").none)
        assertTrue(measure("PlaybackHttp client, GET 302 same host and port", "/landing/same").all)
        assertTrue(measure("PlaybackHttp client, GET 302 same host, another port", "/landing/other-port").none)
    }

    // ---- Ktor with the rule (Dispatcharr API) ----

    @Test fun ktorFollowsTheRule() {
        ktor(rule = true).use { c ->
            c.getStatus(url("/cross"))
            c.getStatus(url("/same"))
            c.getStatus(url("/other-port"))
            c.getStatus(url("/away-and-back"))
            val post = c.postStatus(url("/post-cross-307"))
            assertTrue(measure("Ktor + rule, GET 302 to another host", "/landing/cross").none)
            assertTrue(measure("Ktor + rule, GET 302 same host and port", "/landing/same").all)
            assertTrue(measure("Ktor + rule, GET 302 same host, another port", "/landing/other-port").none)
            assertTrue(measure("Ktor + rule, GET 302 to another host (hop 1)", "/bounce").none)
            assertTrue(measure("Ktor + rule, then 302 back to the original host and port (hop 2)", "/landing/back").none)
            measureNothing("Ktor + rule, POST 307 to another host", post, "/landing/post-cross-307")
            assertEquals(307, post)
        }
    }

    @Test fun ktorRefusesTheDowngrade() {
        needsTls()
        ktor(rule = true).use { c ->
            val status = c.getStatus(url("/down", "https"))
            c.getStatus(url("/up"))
            measureNothing("Ktor + rule, GET 302 https to http, same host and port", status, "/landing/down")
            assertEquals(302, status)
            assertTrue(measure("Ktor + rule, GET 302 http to https, same host and port", "/landing/up").all)
        }
    }

    private class Tls(val server: SSLContext, val client: SSLContext, val trust: X509TrustManager)

    private companion object {
        /** Fake values: the stand-in records presence only and nothing logs a value. */
        const val KEY = "test-key-not-real"
        const val COOKIE = "sessionid=test-not-real"
        const val EMBY = "MediaBrowser Token=\"test-not-real\""
        const val PAYLOAD = "payload-not-real"

        /** A throwaway self-signed certificate for both test hosts, made once with the JDK's keytool. */
        val tls: Tls? by lazy {
            runCatching {
                val keytool = File(System.getProperty("java.home"), "bin/keytool")
                if (!keytool.canExecute()) return@runCatching null
                val dir = kotlin.io.path.createTempDirectory("redirect-auth-standin").toFile().apply { deleteOnExit() }
                val store = File(dir, "standin.p12").apply { deleteOnExit() }
                val pass = UUID.randomUUID().toString()
                val proc = ProcessBuilder(
                    keytool.path, "-genkeypair", "-alias", "standin", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "2", "-dname", "CN=dispatcharr.test",
                    "-ext", "SAN=dns:dispatcharr.test,dns:provider.test",
                    "-keystore", store.path, "-storetype", "PKCS12", "-storepass", pass, "-keypass", pass,
                ).redirectErrorStream(true).start()
                proc.inputStream.readBytes()
                if (!proc.waitFor(60, TimeUnit.SECONDS) || proc.exitValue() != 0) return@runCatching null
                val keys = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, pass.toCharArray()) } }
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                    .apply { init(keys, pass.toCharArray()) }
                val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    setCertificateEntry("standin", keys.getCertificate("standin"))
                }
                val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    .apply { init(trusted) }.trustManagers.filterIsInstance<X509TrustManager>().first()
                Tls(
                    server = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) },
                    client = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) },
                    trust = trust,
                )
            }.getOrNull()
        }
    }
}
