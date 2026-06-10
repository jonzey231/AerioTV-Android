package com.aeriotv.android.core.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.core.data.db.dao.ReminderDao
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.feature.reminders.ReminderBroadcastReceiver
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires once on the first process start after this app is updated in place
 * (system-delivered MY_PACKAGE_REPLACED; any update channel: adb, Play, or
 * the in-app updater).
 *
 * Two jobs:
 *  1. Re-register programme-reminder alarms. AlarmManager drops every alarm
 *     a package had scheduled when that package is replaced, so without this
 *     reminders silently die on each update. Reminders persist in Room; we
 *     re-schedule each future one. (Mirrors RemindersViewModel.scheduleAlarm;
 *     channelId isn't stored in ReminderEntity so re-registered alarms lose
 *     only the banner's tap-to-tune deep link, not the reminder itself.)
 *  2. In-app-updater bookkeeping: delete the staged APK and clear the pending
 *     marker now that the update landed, and stamp the completed version.
 */
@AndroidEntryPoint
class PackageReplacedReceiver : BroadcastReceiver() {

    @Inject lateinit var reminderDao: ReminderDao
    @Inject lateinit var appPreferences: AppPreferences

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                rescheduleReminders(context.applicationContext)
                cleanUpAfterSelfUpdate(context.applicationContext)
            } catch (t: Throwable) {
                Log.w(TAG, "post-update housekeeping failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun rescheduleReminders(context: Context) {
        val now = System.currentTimeMillis()
        val future = reminderDao.allOnce().filter { it.startMillis > now }
        if (future.isEmpty()) return
        val mgr = context.getSystemService(AlarmManager::class.java) ?: return
        future.forEach { entity ->
            val triggerAt = (entity.startMillis - 5 * 60 * 1000L).coerceAtLeast(now + 1_000L)
            val alarmIntent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
                putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_KEY, entity.reminderKey)
                putExtra(ReminderBroadcastReceiver.EXTRA_TITLE, entity.programTitle)
                putExtra(ReminderBroadcastReceiver.EXTRA_CHANNEL_NAME, entity.channelName)
                putExtra(ReminderBroadcastReceiver.EXTRA_CHANNEL_ID, "")
            }
            val pi = PendingIntent.getBroadcast(
                context, entity.alarmRequestCode, alarmIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            runCatching { mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
                .onFailure { Log.w(TAG, "reschedule ${entity.reminderKey} failed", it) }
        }
        Log.i(TAG, "re-registered ${future.size} reminder alarm(s) after package update")
    }

    private suspend fun cleanUpAfterSelfUpdate(context: Context) {
        val pendingJson = appPreferences.updatePendingJsonOnce()
        if (pendingJson.isNotBlank()) {
            appPreferences.setUpdatePendingJson("")
            appPreferences.setUpdateCompletedVersion(BuildConfig.VERSION_NAME)
        }
        // Staged APKs are spent the moment any update lands; reclaim the space.
        File(context.filesDir, "updates").listFiles()?.forEach { it.delete() }
    }

    private companion object { const val TAG = "PackageReplaced" }
}
