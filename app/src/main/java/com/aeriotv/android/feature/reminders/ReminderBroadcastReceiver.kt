package com.aeriotv.android.feature.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aeriotv.android.MainActivity
import com.aeriotv.android.R
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Fires when an AlarmManager-scheduled reminder hits its trigger time.
 *
 * Foreground intercept (iOS ReminderManager parity): if the app is in the
 * foreground we route the reminder to [ReminderBannerBus] for an in-app
 * banner overlay instead of a system notification. When backgrounded we post
 * the notification as before; tapping it opens the app.
 */
class ReminderBroadcastReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReminderReceiverEntryPoint {
        fun reminderBannerBus(): ReminderBannerBus
    }

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty()
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty()
        val key = intent.getStringExtra(EXTRA_REMINDER_KEY) ?: return

        // Foreground -> in-app banner; background -> system notification.
        val bus = EntryPointAccessors
            .fromApplication(context.applicationContext, ReminderReceiverEntryPoint::class.java)
            .reminderBannerBus()
        if (bus.isForeground) {
            bus.post(ReminderBannerData(channelId = channelId, channelName = channelName, programTitle = title))
            return
        }

        ensureChannel(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingTap = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText("Starting soon on $channelName")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingTap)
            .build()

        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.notify(key.hashCode(), notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Programme reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Heads-up when a programme you set a reminder on is about to start."
            setShowBadge(true)
        }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CHANNEL_NAME = "extra_channel"
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        const val EXTRA_REMINDER_KEY = "extra_key"
        private const val CHANNEL_ID = "aeriotv_reminders"
    }
}
