package com.aeriotv.android.core.cast.hlsproxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Network path readouts for the cast proxy log lines (iOS incident
 * 2026-09-25, Apple 85ef563 / 2ceaaed): which transport a network rides,
 * whether it is metered and validated, what else is up, and Battery Saver.
 * Read-only; nothing here changes routing.
 */
internal object NetworkPathLog {

    /** "wifi", "cellular", "ethernet", "vpn+wifi", ... for [caps]. */
    fun transports(caps: NetworkCapabilities?): String {
        if (caps == null) return "none"
        val names = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        }
        return names.joinToString("+").ifEmpty { "other" }
    }

    fun isLan(caps: NetworkCapabilities?): Boolean =
        caps != null && (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            )

    fun isCellular(caps: NetworkCapabilities?): Boolean =
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

    /** "wifi metered=no validated=yes" for [network]. */
    fun describe(cm: ConnectivityManager, network: Network?): String {
        if (network == null) return "none"
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        return describe(caps)
    }

    fun describe(caps: NetworkCapabilities?): String {
        if (caps == null) return "none"
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return "${transports(caps)} metered=${if (metered) "yes" else "no"} " +
            "validated=${if (validated) "yes" else "no"}"
    }

    /** "net=wifi metered=no validated=yes (available: wifi, cellular)
     *  batterySaver=off": the default network, every network that is up,
     *  and Battery Saver. */
    fun current(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val saver = runCatching { pm?.isPowerSaveMode }.getOrNull()
        val saverText = "batterySaver=${when (saver) { true -> "on"; false -> "off"; null -> "?" }}"
        if (cm == null) return "net=? $saverText"
        val active = runCatching { cm.activeNetwork }.getOrNull()
        @Suppress("DEPRECATION")
        val all = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        val available = all.mapNotNull { n ->
            runCatching { cm.getNetworkCapabilities(n) }.getOrNull()?.let { transports(it) }
        }.distinct().joinToString(", ").ifEmpty { "none" }
        return "net=${describe(cm, active)} (available: $available) $saverText"
    }
}
