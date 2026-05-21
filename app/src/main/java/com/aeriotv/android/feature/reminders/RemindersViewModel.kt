package com.aeriotv.android.feature.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.db.dao.ReminderDao
import com.aeriotv.android.core.data.db.entity.ReminderEntity
import com.aeriotv.android.core.data.db.entity.reminderKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Owns the programme-reminder lifecycle. Wraps a Room table for persistence
 * + AlarmManager for scheduling. Mirrors iOS ReminderManager.
 *
 * Schedule policy: alarm fires 5 minutes before the programme's startMillis
 * to give users a heads-up. If the start is already within 5 min (or in the
 * past), the alarm fires at startMillis (or is no-op'd entirely).
 *
 * Uses AlarmManager.set() (inexact) instead of setExactAndAllowWhileIdle so
 * we don't have to gate the feature on the API 31+ SCHEDULE_EXACT_ALARM
 * runtime permission. A few minutes of slack is acceptable for a TV reminder.
 */
@HiltViewModel
class RemindersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ReminderDao,
    private val playlistDao: com.aeriotv.android.core.data.db.dao.PlaylistDao,
) : ViewModel() {

    val all: Flow<List<ReminderEntity>> = dao.observeAll()

    fun observeIsSet(reminderKey: String): Flow<Boolean> = dao.observeIsSet(reminderKey)

    fun setReminder(
        channelName: String,
        programTitle: String,
        startMillis: Long,
        endMillis: Long,
        channelId: String = "",
    ) {
        val key = reminderKey(channelName, programTitle, startMillis)
        val triggerAt = (startMillis - 5 * 60 * 1000L).coerceAtLeast(System.currentTimeMillis() + 1_000L)
        val requestCode = key.hashCode()
        viewModelScope.launch {
            dao.upsert(
                ReminderEntity(
                    reminderKey = key,
                    channelName = channelName,
                    programTitle = programTitle,
                    startMillis = startMillis,
                    endMillis = endMillis,
                    alarmRequestCode = requestCode,
                    playlistId = playlistDao.firstActive()?.id,
                ),
            )
            scheduleAlarm(key, channelName, programTitle, channelId, triggerAt, requestCode)
        }
    }

    fun cancelReminder(reminderKey: String) {
        viewModelScope.launch {
            val existing = dao.getOnce(reminderKey)
            if (existing != null) {
                cancelAlarm(existing.reminderKey, existing.alarmRequestCode)
            }
            dao.delete(reminderKey)
        }
    }

    private fun scheduleAlarm(
        key: String,
        channelName: String,
        programTitle: String,
        channelId: String,
        triggerAt: Long,
        requestCode: Int,
    ) {
        val mgr = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_KEY, key)
            putExtra(ReminderBroadcastReceiver.EXTRA_TITLE, programTitle)
            putExtra(ReminderBroadcastReceiver.EXTRA_CHANNEL_NAME, channelName)
            putExtra(ReminderBroadcastReceiver.EXTRA_CHANNEL_ID, channelId)
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                @Suppress("DEPRECATION")
                mgr.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }.onFailure { Log.w(TAG, "scheduleAlarm failed", it) }
    }

    private fun cancelAlarm(key: String, requestCode: Int) {
        val mgr = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ReminderBroadcastReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mgr.cancel(pi)
    }

    private companion object { const val TAG = "RemindersViewModel" }
}
