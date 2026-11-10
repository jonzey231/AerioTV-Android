package com.aeriotv.android.core.security

import android.content.Context
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The client certificates (mTLS) the user added, one per server host. Keyed
 * by host rather than stored on the playlist row because the proxy guards the
 * host: a certificate is in place before the first connection test of a new
 * playlist, and every client resolves it from the socket's host alone.
 *
 * Each entry is the original PKCS#12 file plus its password, encrypted with
 * [CredentialCipher] (AndroidKeyStore, non-exportable). A cloud backup restore
 * on another device can't decrypt it, so an undecryptable entry is skipped,
 * never a crash. Parsed lazily on the first lookup, which happens on a
 * network thread.
 */
@Singleton
class ClientCertificateStore @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: CredentialCipher,
) {
    @Serializable
    private data class Stored(val p12: String, val password: String)

    private val prefs = context.getSharedPreferences("aerio_client_certs", Context.MODE_PRIVATE)

    private val all: MutableStateFlow<Map<String, ClientCertificate>> by lazy {
        MutableStateFlow(prefs.all.keys.mapNotNull { host -> read(host)?.let { host to it } }.toMap())
    }

    /** Host -> certificate, for the form's status line. */
    val certificates: StateFlow<Map<String, ClientCertificate>> get() = all.asStateFlow()

    init {
        ClientCertificates.lookup = { host -> all.value[host] }
    }

    /** Checks [p12] with [password] and keeps it for [host]; the failure carries user-facing copy. */
    fun save(host: String, p12: ByteArray, password: String): Result<ClientCertificate> = runCatching {
        val cert = ClientCertificates.parse(p12, password)
        val stored = Stored(Base64.encodeToString(p12, Base64.NO_WRAP), password)
        prefs.edit().putString(host.lowercase(), cipher.encrypt(Json.encodeToString(stored))).apply()
        all.value = all.value + (host.lowercase() to cert)
        cert
    }

    fun remove(host: String) {
        prefs.edit().remove(host.lowercase()).apply()
        all.value = all.value - host.lowercase()
    }

    /** The original file and password, for handing the certificate to another device. */
    fun export(host: String): Pair<ByteArray, String>? =
        stored(host.lowercase())?.let { Base64.decode(it.p12, Base64.NO_WRAP) to it.password }

    private fun read(host: String): ClientCertificate? =
        export(host)?.let { (p12, password) -> runCatching { ClientCertificates.parse(p12, password) }.getOrNull() }

    private fun stored(host: String): Stored? = runCatching {
        Json.decodeFromString<Stored>(cipher.decrypt(prefs.getString(host, null)) ?: return null)
    }.onFailure { Log.w(TAG, "client certificate unreadable; skipped", it) }.getOrNull()

    private companion object {
        const val TAG = "ClientCertificateStore"
    }
}
