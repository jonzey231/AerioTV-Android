package com.aeriotv.android.core.data.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/**
 * GH #147: a resume (app foreground after background) re-pulls the playlist
 * when the last channel refresh is older than the Background Refresh
 * interval. A TV that slept through the scheduled runs keeps the app process
 * alive, so the morning open is a resume, not the cold launch that refreshes.
 * The same conditions as [PlaylistRefreshWorker] apply: an unmetered network
 * and a battery that is not low.
 */
object ResumeRefreshRule {
    /** Daily-at-time schedules run once a day, so a day is their interval. */
    fun intervalMins(type: String, intervalMins: Int): Int = if (type == "time") 24 * 60 else intervalMins

    fun isDue(ageMs: Long, intervalMins: Int): Boolean = ageMs > intervalMins * 60_000L

    /** WorkManager's NetworkType.UNMETERED, read now. */
    fun unmetered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    /** WorkManager's battery-not-low: charging, no battery (TV boxes), or above 15 %. */
    fun batteryNotLow(context: Context): Boolean = runCatching {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        if (!i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) return true
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) return true
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return true
        level * 100 / scale > 15
    }.getOrDefault(true)

    /** "3h12m" style age for the log line. */
    fun formatAge(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60}h${m % 60}m" else "${m}m"
    }
}
