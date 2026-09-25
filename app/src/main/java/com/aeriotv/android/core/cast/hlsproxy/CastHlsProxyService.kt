package com.aeriotv.android.core.cast.hlsproxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aeriotv.android.MainActivity
import com.aeriotv.android.R

/**
 * Minimal foreground service scoped to an active cast HLS proxy session
 * (GH #33 web-receiver rework). Nothing else keeps the process alive
 * while casting: CastNotificationController deliberately STOPS the media
 * FGS when a cast connects (the phone is not playing locally, so a
 * mediaPlayback FGS for local playback would be bogus) and its own
 * "Casting to <TV>" chip is a plain notify(), not an FGS. Without this
 * service the OS reaps the process minutes after the user pockets the
 * phone and the receiver's video dies with the ingest.
 *
 * mediaPlayback is the honest service type here: the phone is actively
 * pulling, remuxing, and serving the stream the TV is rendering - the
 * same reasoning LocalRecordingService documents for DVR downloads.
 *
 * Started/stopped exclusively by [CastHlsProxySession]; holds no state.
 */
class CastHlsProxyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /** CPU and Wi-Fi radio stay up while the receiver is being served: the FGS
     *  keeps the process alive and exempt from Doze's network cut, but without a
     *  partial wake lock the CPU can still sleep with the screen off, stalling
     *  the ingest read and the segment server (iOS needed a silent-audio
     *  keepalive for the same reason, 2026-09-21). */
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private val lifecycleObserver = object : androidx.lifecycle.DefaultLifecycleObserver {
        override fun onStop(owner: androidx.lifecycle.LifecycleOwner) = logState("background entry")
        override fun onStart(owner: androidx.lifecycle.LifecycleOwner) = logState("foreground entry")
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> logState("screen off")
                Intent.ACTION_SCREEN_ON -> logState("screen on")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        acquireLocks()
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        runCatching {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this, screenReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        logState("foreground service started")
    }

    override fun onDestroy() {
        running = false
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        runCatching { unregisterReceiver(screenReceiver) }
        releaseLocks()
        com.aeriotv.android.core.debug.debugLog(
            this, CastHlsProxySession.TAG, "[KEEPALIVE] foreground service stopped, locks released",
        )
        super.onDestroy()
    }

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AerioTV:castProxy")
                .apply { setReferenceCounted(false); acquire() }
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AerioTV:castProxy")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    /** One diagnostic line naming everything that keeps the receiver served:
     *  the Android equivalent of the iOS "background entry: keepalive=on
     *  holders=..." line. */
    private fun logState(event: String) {
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val batteryExempt = runCatching { pm?.isIgnoringBatteryOptimizations(packageName) }.getOrNull()
        val idle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { pm?.isDeviceIdleMode }.getOrNull()
        } else {
            null
        }
        com.aeriotv.android.core.debug.debugLog(
            this, CastHlsProxySession.TAG,
            "[KEEPALIVE] $event: fgs=on type=mediaPlayback " +
                "wakeLock=${if (wakeLock?.isHeld == true) "held" else "NOT held"} " +
                "wifiLock=${if (wifiLock?.isHeld == true) "held" else "NOT held"} " +
                "batteryOptimization=${when (batteryExempt) { true -> "exempt"; false -> "optimized"; null -> "?" }} " +
                "deviceIdle=${idle ?: "?"}; " +
                "still running: ingest, remuxer, HTTP server on the LAN (${stats?.invoke() ?: "no stats"})",
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val launchPi = PendingIntent.getActivity(
            this,
            REQ_CODE,
            Intent(this, MainActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Streaming to Cast device")
            .setContentText("AerioTV is relaying this channel to your TV")
            .setContentIntent(launchPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_NOT_STICKY // proxy state lives in the session singleton; no restart value
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    companion object {
        /** Same channel as CastNotificationController's chip so the user
         *  sees one "Casting" group in notification settings. */
        private const val CHANNEL_ID = "aeriotv_casting"
        private const val NOTIF_ID = 0xC6
        private const val REQ_CODE = 0xC6

        /** True between onCreate and onDestroy, for the session's log lines. */
        @Volatile var running: Boolean = false
            private set

        /** Proxy counters for the keepalive log lines, set by the session. */
        @Volatile var stats: (() -> String)? = null

        fun start(context: Context) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, CastHlsProxyService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CastHlsProxyService::class.java)) }
        }
    }
}
