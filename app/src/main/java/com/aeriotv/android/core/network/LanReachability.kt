package com.aeriotv.android.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides whether a playlist's LAN URL is usable by ASKING THE SERVER instead
 * of guessing from the WiFi SSID. The old SSID approach needed fine-location
 * permission, location services on, AND a per-device list of saved home
 * networks (never synced, so every fresh install silently fell back to WAN,
 * the user report behind this class). It also could not work at all on
 * Ethernet-connected TV boxes, and never matched when reaching home over VPN.
 *
 * A reachability probe has none of those problems: HEAD the LAN base URL with
 * a short timeout. ANY HTTP response (including 401/404/405) proves the server
 * is reachable at that address; only connect failures and timeouts mean WAN.
 *
 * Verdicts are cached per URL and refreshed:
 *  - on demand, the first time a URL is routed after process start,
 *  - whenever the default network changes (ConnectivityManager callback),
 *  - explicitly after a playlist edit ([refresh]).
 */
@Singleton
class LanReachability @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val verdicts = ConcurrentHashMap<String, Boolean>()
    private val probeMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reprobeJob: Job? = null

    // Captured at construction so probe() can read the active transport for the
    // cellular fast path. registerDefaultNetworkCallback + getNetworkCapabilities
    // need NO permission (ACCESS_NETWORK_STATE is not required for either).
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    // Emits the LAN URL key whose verdict just CHANGED VALUE (LAN<->WAN), so the
    // player can re-tune a live Dispatcharr stream the instant the route flips
    // (leaving-home-WiFi case). iOS analog: TVLANProbe.record(...) calling
    // PlayerSession.retuneCurrentToActiveURL() on a flip (commit e6ca1d207).
    private val _verdictFlips = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val verdictFlips: SharedFlow<String> = _verdictFlips.asSharedFlow()

    init {
        // Default-network callback: fires on WiFi<->cellular switches, VPN
        // up/down, and Ethernet plug events. Registration needs no permission.
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onNetworkChanged()
                override fun onLost(network: Network) = onNetworkChanged()
            })
        }.onFailure { Log.w(TAG, "Network callback registration failed", it) }
    }

    /**
     * Cached verdict for [lanUrl], probing on first use. Suspends up to
     * [PROBE_TIMEOUT_MS] only when no verdict is cached yet (first routing
     * decision after launch or after a network change).
     */
    suspend fun isReachable(lanUrl: String): Boolean {
        val key = lanUrl.trimEnd('/')
        verdicts[key]?.let { return it }
        return refresh(key)
    }

    /** Force a fresh probe of [lanUrl] (playlist edits, network changes). */
    suspend fun refresh(lanUrl: String): Boolean {
        val key = lanUrl.trimEnd('/')
        // One probe at a time per process: concurrent first-routing calls for
        // the same URL serialise here so it is one extra cheap HEAD at worst.
        probeMutex.withLock {
            val prior = verdicts[key]
            // Log BEFORE the probe: a probe wedged in DNS resolution (which
            // HttpURLConnection's connect/read timeouts do NOT bound) used to
            // hang here invisibly -- while holding probeMutex, stalling every
            // other routing decision in the process. The hard cap below
            // resumes us even if the probe thread stays stuck; a timed-out
            // probe routes WAN, same as a failed one.
            Log.i(TAG, "LAN probe starting for $key")
            val reachable = withTimeoutOrNull(PROBE_HARD_CAP_MS) { probe(key) } ?: false
            verdicts[key] = reachable
            Log.i(TAG, "LAN probe $key -> ${if (reachable) "reachable (using LAN)" else "unreachable (using WAN)"}")
            // Emit only on a genuine LAN<->WAN flip vs the previous KNOWN value
            // (prior != null). A first-ever probe (prior == null) is the initial
            // route resolution, not a flip, so it must not re-tune.
            if (prior != null && prior != reachable) {
                Log.i(TAG, "LAN verdict flipped for $key: $prior -> $reachable")
                _verdictFlips.tryEmit(key)
            }
            return reachable
        }
    }

    private fun onNetworkChanged() {
        val known = verdicts.keys.toList()
        if (known.isEmpty()) return
        // Do NOT clear here: refresh() needs the prior verdict in-place to detect
        // a LAN<->WAN flip. refresh() overwrites each key with the fresh result
        // and emits verdictFlips on a change. A short debounce lets routing
        // tables settle after onAvailable.
        reprobeJob?.cancel()
        reprobeJob = scope.launch {
            delay(500)
            known.forEach { url -> runCatching { refresh(url) } }
        }
    }

    private suspend fun probe(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        // Cellular fast path (iOS commit e6ca1d207): if the active default
        // network is cellular-only (no WiFi / Ethernet transport), every LAN
        // URL (a private-subnet address) is unreachable by definition. Skip the
        // HEAD so leaving home re-tunes to WAN near-instantly instead of eating
        // the full PROBE_TIMEOUT_MS against a dead host. A TV box on Ethernet/WiFi
        // (the common case) never trips this and falls through to the HEAD.
        val caps = runCatching {
            connectivityManager?.let { cm -> cm.getNetworkCapabilities(cm.activeNetwork) }
        }.getOrNull()
        if (caps != null &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            Log.i(TAG, "LAN probe $baseUrl -> cellular-only transport, skipping HEAD (WAN)")
            return@withContext false
        }
        try {
            val conn = URL(baseUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            try {
                // Reaching this line at all means TCP + HTTP succeeded; the
                // status code (401, 404, 405...) is irrelevant to routing.
                conn.responseCode
                true
            } finally {
                conn.disconnect()
            }
        } catch (t: Exception) {
            false
        }
    }

    private companion object {
        const val TAG = "LanReachability"
        // A LAN server answers in single-digit milliseconds; 1.5s is a wide
        // margin that still keeps the worst-case first-route delay short.
        const val PROBE_TIMEOUT_MS = 1_500

        // Coroutine-level ceiling on one probe() call. Covers what the
        // HttpURLConnection timeouts don't: DNS resolution of a hostname LAN
        // URL, which can block far beyond connect+read on a broken resolver.
        const val PROBE_HARD_CAP_MS = 5_000L
    }
}
