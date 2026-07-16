package com.aeriotv.android.core.cast.companion

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side mDNS/NSD discovery of open AerioTV TVs (GH #33 companion remote). The
 * TV runs [CompanionHostController] which advertises `_aeriotv._tcp`; this browses
 * for it and resolves each to a reachable host:port. [start]/[stop] are driven by
 * the picker UI so we only scan while the user is choosing a target.
 */
@Singleton
class CompanionDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Tv(val deviceId: String, val name: String, val host: String, val port: Int)

    private companion object { const val TAG = "CompanionDiscover" }

    private val _devices = MutableStateFlow<List<Tv>>(emptyList())
    val devices: StateFlow<List<Tv>> = _devices.asStateFlow()

    private var nsd: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // NsdManager.resolveService handles exactly ONE resolve at a time (a second
    // concurrent call fails with FAILURE_ALREADY_ACTIVE), so serialize them.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    // Refcount so nested owners (the cast button keeps discovery alive to decide
    // whether to SHOW itself; the chooser also starts it while open) don't tear
    // each other down -- discovery stops only when the last owner releases
    // (review 2026-07-15).
    private var starts = 0

    @Synchronized
    fun start() {
        if (starts++ > 0) return
        val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = mgr
        acquireMulticastLock()
        _devices.value = emptyList()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discover start failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) { enqueueResolve(info) }
            override fun onServiceLost(info: NsdServiceInfo) {
                _devices.value = _devices.value.filterNot { it.name == info.serviceName }
            }
        }
        discoveryListener = listener
        runCatching { mgr.discoverServices(CompanionProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    @Synchronized
    fun stop() {
        if (starts > 0) starts--
        if (starts > 0) return // another owner still needs discovery
        discoveryListener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        discoveryListener = null
        resolveQueue.clear()
        resolving = false
        _devices.value = emptyList()
        multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        multicastLock = null
    }

    @Synchronized
    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.add(info)
        pumpResolve()
    }

    @Synchronized
    private fun pumpResolve() {
        if (resolving) return
        val next = resolveQueue.poll() ?: return
        val mgr = nsd ?: return
        resolving = true
        @Suppress("DEPRECATION")
        val rl = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { onResolveDone() }
            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = info.host?.hostAddress
                val id = runCatching {
                    info.attributes[CompanionProtocol.TXT_DEVICE_ID]?.let { String(it) }
                }.getOrNull() ?: info.serviceName ?: host ?: "tv"
                if (host != null) {
                    val tv = Tv(deviceId = id, name = info.serviceName ?: "Android TV", host = host, port = info.port)
                    _devices.value = (_devices.value.filterNot { it.deviceId == tv.deviceId } + tv).sortedBy { it.name.lowercase() }
                }
                onResolveDone()
            }
        }
        @Suppress("DEPRECATION")
        runCatching { mgr.resolveService(next, rl) }.onFailure { onResolveDone() }
    }

    @Synchronized
    private fun onResolveDone() {
        resolving = false
        pumpResolve()
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.createMulticastLock("aeriotv-companion-discover")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }
}
