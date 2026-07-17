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
    /** Service names already given their one failed-resolve retry. */
    private val retriedResolves = mutableSetOf<String>()

    // Refcount so nested owners (the cast button keeps discovery alive to decide
    // whether to SHOW itself; the chooser also starts it while open) don't tear
    // each other down -- discovery stops only when the last owner releases
    // (review 2026-07-15).
    private var starts = 0

    /**
     * Self-heal (2026-07-17 Fold field test): an NSD browse can go silently
     * deaf -- after a TV's advert dropped and came back, no onServiceFound
     * ever arrived even though `dns-sd -B` saw the re-registration on the
     * wire. While discovery is wanted and the device list is EMPTY, restart
     * the browse every 30s; a healthy-but-quiet browse restarts harmlessly
     * (no UI flicker, the list is already empty).
     */
    private val healHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val healTick = object : Runnable {
        override fun run() {
            heal()
            healHandler.postDelayed(this, 30_000)
        }
    }

    @Synchronized
    private fun heal() {
        if (starts <= 0 || _devices.value.isNotEmpty()) return
        Log.i(TAG, "browse quiet + list empty -> restarting NSD browse")
        discoveryListener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        discoveryListener = null
        beginBrowse()
    }

    /**
     * Force a fresh query sweep NOW. The Fold's WiFi delivers only the
     * initial-sweep answers of a browse; unsolicited mid-browse announcements
     * (a TV that came up later) never arrive, so a long-running browse goes
     * stale-by-omission (2026-07-17: ATV registered at 10:06, Mac saw it,
     * Fold's 9:53 browse never did). Called when the Control-a-TV picker
     * opens -- a browse restart re-queries immediately. Known entries are
     * kept (re-found services refresh; a truly-gone one dies on connect).
     */
    @Synchronized
    fun refresh() {
        if (starts <= 0) return
        discoveryListener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        discoveryListener = null
        beginBrowse()
    }

    @Synchronized
    fun start() {
        if (starts++ > 0) return
        nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        acquireMulticastLock()
        _devices.value = emptyList()
        beginBrowse()
        healHandler.removeCallbacks(healTick)
        healHandler.postDelayed(healTick, 30_000)
    }

    private fun beginBrowse() {
        val mgr = nsd ?: return
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
        healHandler.removeCallbacks(healTick)
        discoveryListener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        discoveryListener = null
        resolveQueue.clear()
        resolving = false
        retriedResolves.clear()
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
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                // One delayed retry per service: a resolve raced against a
                // just-(re)registered advert loses the device FOREVER
                // otherwise (announcements stop and nothing re-triggers
                // onServiceFound).
                val key = info.serviceName.orEmpty()
                if (retriedResolves.add(key)) {
                    healHandler.postDelayed({ enqueueResolve(info) }, 2_000)
                }
                onResolveDone()
            }
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
