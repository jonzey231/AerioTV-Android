package com.aeriotv.android.core.security

import okhttp3.OkHttpClient
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** A client certificate (mTLS) read from a PKCS#12 file: its key and chain. */
class ClientCertificate(val key: PrivateKey, val chain: Array<X509Certificate>) {
    /** The leaf's common name (or its whole subject when it has none), for the UI. */
    val subject: String
        get() = chain[0].subjectX500Principal.name.split(',')
            .firstOrNull { it.trim().startsWith("CN=") }?.substringAfter("CN=")
            ?: chain[0].subjectX500Principal.name
    val notAfter: Date get() = chain[0].notAfter
}

/**
 * Client certificates (mutual TLS) for servers behind a proxy that asks for
 * one: Pangolin / Traefik (RequireAndVerifyClientCert), nginx, Caddy.
 *
 * One process-wide [SSLContext] whose key manager hands a certificate out per
 * HOST: only the server it was added for ever sees it, and TLS only asks the
 * client at all when the server sends a CertificateRequest, so a LAN URL
 * without the proxy, TMDB or a logo CDN never get one. Trust is the platform
 * default (the same trust manager OkHttp picks on its own, so the network
 * security config still applies).
 *
 * Every OkHttp client that can reach a playlist's server applies
 * [clientCertificates]; HttpURLConnection (Media3's DefaultHttpDataSource,
 * the LAN probe) gets it through [installDefault]. The certificates
 * themselves live in [ClientCertificateStore], which sets [lookup].
 */
object ClientCertificates {

    /** Host (lowercased, no port) -> its certificate. Set by [ClientCertificateStore]. */
    @Volatile var lookup: (String) -> ClientCertificate? = { null }

    val trustManager: X509TrustManager by lazy {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS")
            .apply { init(arrayOf(HostKeyManager), arrayOf(trustManager), null) }
            .socketFactory
    }

    /** The host a certificate is kept under: lowercased, no port; a URL typed without a scheme counts. */
    fun hostOf(url: String): String? = runCatching {
        val u = url.trim()
        java.net.URI(if ("://" in u) u else "https://$u").host?.lowercase()
    }.getOrNull()

    /** HttpURLConnection's default factory (Media3 DefaultHttpDataSource has no setter of its own). */
    fun installDefault() {
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(socketFactory)
    }

    /**
     * Reads a PKCS#12 file. Throws with a user-facing message when the
     * password is wrong or the file holds no key.
     */
    fun parse(p12: ByteArray, password: String): ClientCertificate {
        val ks = KeyStore.getInstance("PKCS12")
        try {
            ks.load(p12.inputStream(), password.toCharArray())
        } catch (t: Throwable) {
            throw IllegalArgumentException("Couldn't open the certificate. Check the password.", t)
        }
        val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
            ?: throw IllegalArgumentException("The file holds no private key.")
        val key = ks.getKey(alias, password.toCharArray()) as PrivateKey
        val chain = ks.getCertificateChain(alias).map { it as X509Certificate }.toTypedArray()
        return ClientCertificate(key, chain)
    }

    /**
     * User-facing copy for a failure caused by the proxy's certificate check,
     * or null when [t] is something else. Matches BoringSSL's alert names in
     * the exception chain: a TLS 1.3 server sends its alert after the
     * handshake, so it surfaces on the first read, not as a handshake error.
     * Whether [host] has a certificate tells "asks for one" from "refused this
     * one" where a TLS 1.2 server answers both with bad_certificate.
     */
    fun describe(t: Throwable, host: String?): String? {
        val text = generateSequence(t) { it.cause }.take(8).joinToString(" ") { it.message.orEmpty() }
        val required = "CERTIFICATE_REQUIRED" in text
        val refused = REFUSED_ALERTS.any { it in text }
        val hasCertificate = host?.lowercase()?.let(lookup) != null
        return when {
            required || (refused && !hasCertificate) ->
                "The server asks for a client certificate (mTLS). Add one under Client Certificate."
            refused ->
                "The server didn't accept the client certificate (unknown CA, expired or revoked)."
            else -> null
        }
    }

    private val REFUSED_ALERTS = listOf(
        "ALERT_BAD_CERTIFICATE", "ALERT_UNKNOWN_CA", "ALERT_CERTIFICATE_EXPIRED",
        "ALERT_CERTIFICATE_REVOKED", "ALERT_CERTIFICATE_UNKNOWN", "ALERT_ACCESS_DENIED",
    )

    /**
     * Alias = the peer host. The server's key types and CA list are not
     * checked: the user added this certificate for this server, and proxies
     * (Traefik without a clientAuth CA list) often send no issuers at all.
     */
    internal object HostKeyManager : X509ExtendedKeyManager() {
        private fun aliasFor(host: String?): String? =
            host?.lowercase()?.takeIf { lookup(it) != null }

        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
            aliasFor((socket as? SSLSocket)?.handshakeSession?.peerHost)

        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
            aliasFor(engine?.peerHost)

        override fun getPrivateKey(alias: String?): PrivateKey? = alias?.let(lookup)?.key
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = alias?.let(lookup)?.chain
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }
}

/** Offers the matching client certificate to a playlist's server (see [ClientCertificates]). */
fun OkHttpClient.Builder.clientCertificates(): OkHttpClient.Builder =
    sslSocketFactory(ClientCertificates.socketFactory, ClientCertificates.trustManager)
