package com.aeriotv.android.core.setup

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/**
 * Pure JVM math for the user QR setup request (docs/DeviceSetupHandoff.md,
 * "Copy a setup from a user QR"). Apple computes the same values byte for byte.
 */
object UserQrCrypto {
    private const val UQ_PREFIX = "aeriotv-userqr-v1:"

    /** TXT `uq`: lowercase hex of the first 8 bytes of SHA-256(prefix + api_key). */
    fun uq(apiKey: String): String =
        MessageDigest.getInstance("SHA-256").digest((UQ_PREFIX + apiKey).toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Standard base64 of HMAC-SHA256(key = api_key, message = link). */
    fun proof(apiKey: String, link: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(link.toByteArray(Charsets.UTF_8)))
    }

    fun verify(apiKey: String, link: String, proof: String): Boolean =
        MessageDigest.isEqual(proof(apiKey, link).toByteArray(Charsets.UTF_8), proof.toByteArray(Charsets.UTF_8))

    @Serializable
    data class Request(val v: Int = 1, val link: String, val proof: String)
}

/**
 * Showing device ("source"): while Show User QR Code is open, advertises
 * `_aeriotv-setup._tcp` with role=source and the code's `uq`, and serves
 * POST /user-qr. A valid request waits for Send or Don't send on this screen.
 */
class UserQrSource(
    private val context: Context,
    private val apiKey: String,
    /** False when the active profile may not send a setup (Child). */
    private val canSend: () -> Boolean,
    /** Builds the full payload and sends it to the scanner's receiver. */
    private val send: suspend (SetupProtocol.Link) -> SetupSender.SendResult,
) {
    sealed interface State {
        data object Idle : State
        data class Confirm(val name: String) : State
        data class Sending(val name: String) : State
        data class Sent(val name: String) : State
        data class Failed(val name: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var server: EmbeddedServer<*, *>? = null
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var decision: CompletableDeferred<Boolean>? = null
    private var busy = false

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (server != null) return@withContext true
        setupLanIpv4() ?: return@withContext false
        val srv = embeddedServer(CIO, configure = {
            connector { port = 0; host = "0.0.0.0" }
            // The reply waits for Send or Don't send (up to 120 s).
            connectionIdleTimeoutSeconds = 200
        }) {
            routing { route("{...}") { handle { handle(call) } } }
        }
        runCatching { srv.start(wait = false) }.onFailure { Log.w(TAG, "[SETUP] user QR listener failed"); return@withContext false }
        server = srv
        val port = runCatching { srv.engine.resolvedConnectors().first().port }.getOrElse { stop(); return@withContext false }
        register(port)
        true
    }

    fun stop() {
        decision?.complete(false)
        regListener?.let { l -> runCatching { nsd?.unregisterService(l) } }
        regListener = null
        val srv = server
        server = null
        Thread { runCatching { srv?.stop(1_000L, 2_000L) } }.start()
        multicastLock?.let { l -> runCatching { if (l.isHeld) l.release() } }
        multicastLock = null
    }

    /** Send (true) or Don't send (false) on the confirm card. */
    fun answer(send: Boolean) { decision?.complete(send) }

    fun dismissResult() {
        if (_state.value is State.Sent || _state.value is State.Failed) _state.value = State.Idle
    }

    private suspend fun reply(call: ApplicationCall, status: HttpStatusCode, error: String?) {
        runCatching { call.response.header("Connection", "close") }
        val body = if (error == null) """{"ok":true}""" else """{"ok":false,"error":"$error"}"""
        call.respondText(body, ContentType.Application.Json, status)
    }

    private suspend fun handle(call: ApplicationCall) {
        if (call.request.headers["Origin"] != null) return reply(call, HttpStatusCode.Forbidden, "forbidden")
        if (call.request.httpMethod != HttpMethod.Post || call.request.path() != SetupProtocol.USER_QR_PATH) {
            return reply(call, HttpStatusCode.NotFound, "bad_request")
        }
        val len = call.request.headers["Content-Length"]?.toLongOrNull() ?: 0L
        if (len > MAX_BODY) return reply(call, HttpStatusCode.PayloadTooLarge, "too_large")
        val body = runCatching { call.receiveText() }.getOrDefault("")
        if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY) return reply(call, HttpStatusCode.PayloadTooLarge, "too_large")
        val req = runCatching { SetupProtocol.json.decodeFromString(UserQrCrypto.Request.serializer(), body) }.getOrNull()
        val link = req?.takeIf { it.v == 1 }?.let { SetupProtocol.parseLink(it.link) }
        if (req == null || link == null) return reply(call, HttpStatusCode.BadRequest, "bad_request")
        if (!UserQrCrypto.verify(apiKey, req.link, req.proof)) return reply(call, HttpStatusCode.Forbidden, "forbidden")
        val d = synchronized(this) {
            if (busy) null else { busy = true; CompletableDeferred<Boolean>().also { decision = it } }
        } ?: return reply(call, HttpStatusCode.Conflict, "busy")
        try {
            if (!canSend()) return reply(call, HttpStatusCode.Forbidden, "not_allowed")
            val name = link.name.ifBlank { "AerioTV" }
            Log.i(TAG, "[SETUP] user QR request; waiting for Send")
            _state.value = State.Confirm(name)
            val go = withTimeoutOrNull(DECIDE_MS) { d.await() } == true
            if (!go) {
                if (_state.value is State.Confirm) _state.value = State.Idle
                return reply(call, HttpStatusCode.Forbidden, "declined")
            }
            _state.value = State.Sending(name)
            val r = runCatching { send(link) }.getOrElse { SetupSender.SendResult.Failed(UNREACHABLE) }
            Log.i(TAG, "[SETUP] user QR send -> ${r.javaClass.simpleName}")
            if (r == SetupSender.SendResult.Ok) {
                _state.value = State.Sent(name)
                reply(call, HttpStatusCode.OK, null)
            } else {
                _state.value = State.Failed(name, sendErrorMessage(r))
                reply(call, HttpStatusCode.BadGateway, "send_failed")
            }
        } finally {
            synchronized(this) { busy = false; decision = null }
        }
    }

    private fun register(port: Int) {
        val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = mgr
        multicastLock = runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("aeriotv-userqr")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        val name = setupDeviceName(context)
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SetupProtocol.SERVICE_TYPE
            setPort(port)
            runCatching { setAttribute(SetupProtocol.TXT_VERSION, SetupProtocol.VERSION.toString()) }
            runCatching { setAttribute(SetupProtocol.TXT_NAME, name.take(60)) }
            runCatching { setAttribute(SetupProtocol.TXT_KIND, setupDeviceKind(context)) }
            runCatching { setAttribute(SetupProtocol.TXT_ROLE, SetupProtocol.ROLE_SOURCE) }
            runCatching { setAttribute(SetupProtocol.TXT_USER_QR, UserQrCrypto.uq(apiKey)) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i(TAG, "[SETUP] user QR advert registered") }
            override fun onRegistrationFailed(s: NsdServiceInfo, err: Int) { Log.w(TAG, "[SETUP] user QR advert failed $err") }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, err: Int) {}
        }
        regListener = l
        runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { regListener = null }
    }

    companion object {
        private const val TAG = "AerioSetup"
        private const val MAX_BODY = 8L * 1024L
        private const val DECIDE_MS = 120_000L
        const val UNREACHABLE = "The device could not be reached. Check that both devices are on the same network."

        /** The banner flow's send error wording. */
        fun sendErrorMessage(r: SetupSender.SendResult): String = when (r) {
            SetupSender.SendResult.Ok -> ""
            SetupSender.SendResult.WrongCode -> "The device did not accept that code. Check the code on the device and try again."
            SetupSender.SendResult.Refused -> "The device is no longer waiting. Start setup on the device again."
            is SetupSender.SendResult.Failed -> r.message
        }
    }
}

/** Scanning device side: find the device showing a user QR, and ask it for its setup. */
object UserQrRequest {
    private const val TAG = "AerioSetup"

    data class Found(val name: String, val host: String, val port: Int)

    /** Browses `_aeriotv-setup._tcp` for up to [timeoutMs] for role=source with this key's `uq`. */
    suspend fun find(context: Context, apiKey: String, timeoutMs: Long = 3_000L): Found? {
        val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val want = UserQrCrypto.uq(apiKey)
        val lock = runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("aeriotv-userqr-find")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) { found.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        return try {
            runCatching { mgr.discoverServices(SetupProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { return null }
            withTimeoutOrNull(timeoutMs) {
                var hit: Found? = null
                while (hit == null) hit = resolve(mgr, found.receive(), want)
                hit
            }.also { Log.i(TAG, "[SETUP] user QR source ${if (it != null) "found" else "not found"}") }
        } finally {
            runCatching { mgr.stopServiceDiscovery(l) }
            lock?.let { runCatching { if (it.isHeld) it.release() } }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(mgr: NsdManager, info: NsdServiceInfo, want: String): Found? =
        suspendCancellableCoroutine { cont ->
            val rl = object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val attrs = runCatching { i.attributes }.getOrNull().orEmpty()
                    fun attr(k: String) = attrs[k]?.let { String(it, Charsets.UTF_8) }
                    val host = i.host?.takeIf { it is Inet4Address }?.hostAddress ?: i.host?.hostAddress
                    val ok = attr(SetupProtocol.TXT_ROLE) == SetupProtocol.ROLE_SOURCE &&
                        attr(SetupProtocol.TXT_USER_QR)?.lowercase() == want && host != null
                    val r = if (ok) Found(attr(SetupProtocol.TXT_NAME)?.takeIf { it.isNotBlank() } ?: i.serviceName ?: "AerioTV", host!!, i.port) else null
                    if (cont.isActive) cont.resume(r)
                }
            }
            runCatching { mgr.resolveService(info, rl) }.onFailure { if (cont.isActive) cont.resume(null) }
        }

    /** POST /user-qr. Returns null on success, the reply's error code, or "network". */
    suspend fun request(found: Found, apiKey: String, link: String): String? = withContext(Dispatchers.IO) {
        val req = UserQrCrypto.Request(v = 1, link = link, proof = UserQrCrypto.proof(apiKey, link))
        val body = SetupProtocol.json.encodeToString(UserQrCrypto.Request.serializer(), req).toByteArray(Charsets.UTF_8)
        runCatching {
            val conn = URL("http://${found.host}:${found.port}${SetupProtocol.USER_QR_PATH}").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 150_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Connection", "close")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val text = runCatching { (if (status < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() }.getOrNull().orEmpty()
            conn.disconnect()
            val reply = runCatching { SetupProtocol.json.decodeFromString(SetupProtocol.Reply.serializer(), text) }.getOrNull()
            Log.i(TAG, "[SETUP] user QR request -> HTTP $status ${reply?.error.orEmpty()}")
            if (status == 200 && reply?.ok == true) null else reply?.error ?: "http_$status"
        }.getOrElse {
            Log.w(TAG, "[SETUP] user QR request failed: ${it.javaClass.simpleName}")
            "network"
        }
    }
}
