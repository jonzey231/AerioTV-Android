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

    private fun clear() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "aeriotv_casting"
        const val NOTIF_ID = 0xC5
        const val REQ_CODE = 0xC5
    }
}
