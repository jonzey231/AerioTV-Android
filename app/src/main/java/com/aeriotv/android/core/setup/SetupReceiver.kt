package com.aeriotv.android.core.setup

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * TV side of "Set up from your phone" (docs/DeviceSetupHandoff.md). Runs an
 * embedded Ktor CIO server (the same stack as the companion host, no new HTTP
 * library) on an ephemeral port, advertises `_aeriotv-setup._tcp`, and accepts
 * exactly one sealed payload per code. Lives only while the setup screen is
 * open; [stop] tears down the server and the advert.
 *
 * Separate from the companion `_aeriotv._tcp` host on purpose: the companion
 * remote pairs to control playback, this hands over a playlist once.
 */
class SetupReceiver(
    private val context: Context,
    /** Called on the server's coroutine with a verified payload. */
    private val onPayload: (SetupProtocol.Payload) -> Unit,
) {
    data class Offer(val code: String, val link: String, val deviceName: String, val ip: String, val port: Int, val fingerprint: String)

    private val _offer = MutableStateFlow<Offer?>(null)
    val offer: StateFlow<Offer?> = _offer.asStateFlow()

    @Volatile private var keys: SetupCrypto.KeyPair = SetupCrypto.newKeyPair()
    @Volatile private var code: String = SetupCrypto.newCode()
    @Volatile private var failures = 0
    @Volatile private var used = false
    private var server: EmbeddedServer<*, *>? = null
    private var port = 0
    private var ip: String? = null
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** Returns false when the TV has no LAN address or the bind failed. */
    suspend fun start(): Boolean {
        val lan = lanIpv4() ?: return false.also { Log.w(TAG, "[SETUP] no LAN IPv4; cannot offer setup") }
        ip = lan
        val srv = embeddedServer(CIO, port = 0, host = "0.0.0.0") {
            routing {
                post(SetupProtocol.PATH) { handle(call) }
            }
        }
        runCatching { srv.start(wait = false) }.onFailure { Log.w(TAG, "[SETUP] server start failed", it); return false }
        server = srv
        port = runCatching { srv.engine.resolvedConnectors().first().port }.getOrElse { stop(); return false }
        Log.i(TAG, "[SETUP] listening on $lan:$port")
        publish()
        return true
    }

    fun stop() {
        unregister()
        runCatching { server?.stop(200L, 500L) }
        server = null
        multicastLock?.let { l -> runCatching { if (l.isHeld) l.release() } }
        multicastLock = null
        _offer.value = null
    }

    /** New code and key pair (after 3 failures, or a manual refresh). */
    fun regenerate() {
        keys = SetupCrypto.newKeyPair()
        code = SetupCrypto.newCode()
        failures = 0
        used = false
        publish()
    }

    private fun publish() {
        val lan = ip ?: return
        val name = deviceName()
        _offer.value = Offer(code, SetupProtocol.buildLink(lan, port, code, keys.publicKey, name), name, lan, port, SetupCrypto.keyFingerprint(keys.publicKey))
        register(name)
    }

    private suspend fun handle(call: ApplicationCall) {
        // Browsers always send Origin on a cross-site POST; the AerioTV
        // sender never does (same hardening as the companion socket).
        if (call.request.headers["Origin"] != null || used) {
            call.respondText("""{"ok":false,"error":"forbidden"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
            return
        }
        val len = call.request.headers["Content-Length"]?.toLongOrNull() ?: 0L
        if (len > MAX_BODY) {
            call.respondText("""{"ok":false,"error":"too_large"}""", ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
            return
        }
        val body = runCatching { call.receiveText() }.getOrDefault("")
        when (val r = synchronized(this) { if (used) null else SetupProtocol.openOnTv(body, keys, code) }) {
            null -> call.respondText("""{"ok":false,"error":"forbidden"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
            is SetupProtocol.OpenResult.Ok -> {
                used = true
                Log.i(TAG, "[SETUP] payload accepted (${r.payload.playlist.type}, ${r.payload.profiles.size} profiles, sync=${r.payload.sync?.enabled == true})")
                call.respondText("""{"ok":true}""", ContentType.Application.Json, HttpStatusCode.OK)
                unregister()
                onPayload(r.payload)
            }
            SetupProtocol.OpenResult.WrongCode -> {
                failures++
                Log.w(TAG, "[SETUP] wrong code ($failures of ${SetupProtocol.MAX_FAILURES})")
                call.respondText("""{"ok":false,"error":"wrong_code"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
                if (failures >= SetupProtocol.MAX_FAILURES) regenerate()
            }
            SetupProtocol.OpenResult.BadRequest ->
                call.respondText("""{"ok":false,"error":"bad_request"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        }
    }

    private fun register(name: String) {
        unregister()
        val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = mgr
        if (multicastLock?.isHeld != true) {
            multicastLock = runCatching {
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.createMulticastLock("aeriotv-setup")?.apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SetupProtocol.SERVICE_TYPE
            setPort(port)
            runCatching { setAttribute(SetupProtocol.TXT_VERSION, SetupProtocol.VERSION.toString()) }
            runCatching { setAttribute(SetupProtocol.TXT_NAME, name.take(60)) }
            runCatching { setAttribute(SetupProtocol.TXT_PK, SetupCrypto.b64(keys.publicKey)) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i(TAG, "[SETUP] NSD registered ${s.serviceName}") }
            override fun onRegistrationFailed(s: NsdServiceInfo, err: Int) { Log.w(TAG, "[SETUP] NSD register failed $err") }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, err: Int) {}
        }
        regListener = l
        runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "[SETUP] NSD register threw", it); regListener = null }
    }

    private fun unregister() {
        regListener?.let { l -> runCatching { nsd?.unregisterService(l) } }
        regListener = null
    }

    private fun deviceName(): String =
        runCatching { Settings.Global.getString(context.contentResolver, "device_name") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android TV"

    private fun lanIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }.getOrDefault(false) }
            .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private companion object {
        const val TAG = "AerioSetup"
        const val MAX_BODY = 256L * 1024L
    }
}
