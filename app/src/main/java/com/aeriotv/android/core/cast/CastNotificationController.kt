package com.aeriotv.android.core.cast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aeriotv.android.MainActivity
import com.aeriotv.android.R
import com.aeriotv.android.core.playback.AerioMediaPlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped "Casting to <TV>" notification (GH #33). The media foreground-
 * service notification (AerioMediaPlaybackService) can't cover the cast case: the
 * phone isn't playing locally while casting, so a mediaPlayback FGS isn't valid,
 * and after a force-close the service is dead with nothing to restart it. This
 * standalone (non-FGS) ongoing notification is driven purely by cast STATE, so it
 * re-posts whenever the process is alive and casting -- including right after the
 * app is reopened and Play-services resumes the session. Tapping it returns to
 * the app (the Now-Casting mini controller / remote).
 *
 * Only fires on the SENDER (a phone that initiated a cast); an Android-TV receiver
 * is never a sender, so [AerioCastSender.state] there is never Connected.
 */
@Singleton
class CastNotificationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val castSender: AerioCastSender,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var wasCasting = false

    /** Idempotent; called once from Application.onCreate. */
    fun start() {
        if (started) return
        started = true
        ensureChannel()
        // Cancel any chip left over from a PRIOR process death: this is a plain
        // notify() (not an FGS notification), so it survives an OS kill. If the
        // cast ended while we were dead, no true->false transition will fire to
        // clear it, so clear up front -- the collector re-posts immediately if a
        // session is genuinely still active/resuming.
        clear()
        scope.launch {
            // An involuntary drop (network loss, receiver death) otherwise just
            // makes the ongoing chip vanish -- Kenton 2026-08-18: TV on the idle
            // screen, phone showing nothing. Tell the user what happened with a
            // normal-priority, auto-cancel notification; tapping it reopens the
            // app, where the cast button offers the device again.
            castSender.involuntaryEnd.collect { end -> postDisconnected(end) }
        }
        scope.launch {
            combine(castSender.state, castSender.content) { s, c -> s to c }
                .collect { (state, content) ->
                    val casting = state is AerioCastSender.State.Connected && content != null
                    if (casting) {
                        // Entering a cast: the phone stops local playback, so the
                        // media FGS notification is stale/invalid -- retire it so
                        // this standalone chip is the single casting indicator.
                        if (!wasCasting) runCatching { AerioMediaPlaybackService.stop(context) }
                        post(
                            deviceName = (state as AerioCastSender.State.Connected).deviceName,
                            content = content!!,
                        )
                    } else if (wasCasting) {
                        clear()
                    }
                    wasCasting = casting
                }
        }
    }

    private fun post(deviceName: String?, content: AerioCastSender.Content) {
        val launchPi = PendingIntent.getActivity(
            context,
            REQ_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(content.title.ifBlank { "Now casting" })
            .setContentText(if (!deviceName.isNullOrBlank()) "Casting to $deviceName" else "Casting")
            .setContentIntent(launchPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notif) }
    }

    private fun postDisconnected(end: AerioCastSender.InvoluntaryEnd) {
        val launchPi = PendingIntent.getActivity(
            context,
            REQ_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (!end.deviceName.isNullOrBlank()) {
            "Casting to ${end.deviceName} disconnected"
        } else {
            "Casting disconnected"
        }
        val body = buildString {
            if (!end.contentTitle.isNullOrBlank()) append("${end.contentTitle} stopped. ")
            append("The connection to the TV was lost. Open AerioTV to cast again.")
        }
        val notif = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(launchPi)
            .setAutoCancel(true)
            // Its own channel, not the ongoing chip's. On Android 8+ the CHANNEL
            // importance governs alerting and setPriority is ignored, so posting
            // this to the IMPORTANCE_LOW "Casting" channel would make it silent
            // and buried -- nearly as invisible as the silent disappearance this
            // is meant to fix. A channel's importance is also immutable once
            // created, so it has to be a separate channel, not a bumped one.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ALERT_NOTIF_ID, notif) }
    }

    private fun clear() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
        // Separate channel for the "cast dropped" alert: IMPORTANCE_DEFAULT so it
        // actually makes a sound and sorts normally in the shade. The user can
        // still silence it on its own without losing the ongoing casting chip.
        if (mgr.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "Casting interrupted",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "aeriotv_casting"
        const val ALERT_CHANNEL_ID = "aeriotv_cast_alerts"
        const val NOTIF_ID = 0xC5

        // Notification ids in use across the app, so the next addition does not
        // collide the way this one did: 0xAD/0xAE LocalRecordingService,
        // 0xAF AerioMediaPlaybackService, 0xC5 the ongoing cast chip (above),
        // 0xC6 CastHlsProxyService's FOREGROUND-SERVICE notification.
        //
        // This alert MUST NOT reuse 0xC6. Posting to a foreground service's id
        // does not create a second notification, it overwrites that service's
        // own notification; and when endCleanup() then stops the proxy service,
        // the system reaps id 0xC6 and takes the alert with it. Verified on a
        // Z Fold 5 against a live cast to a Google TV Streamer: the alert was
        // posted and gone within the same teardown, leaving the user with
        // nothing, which is the exact bug this is meant to fix.
        const val ALERT_NOTIF_ID = 0x0C7A
        const val REQ_CODE = 0xC5
    }
}
