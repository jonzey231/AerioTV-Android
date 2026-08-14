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
