package com.aeriotv.android.feature.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aeriotv.android.core.data.db.dao.ReminderDao
import com.aeriotv.android.core.data.db.entity.ReminderEntity

/**
 * Reminders across a playlist delete (Logan 2026-11-12, Apple the same):
 * "Leave them but make sure they don't appear on that same device without
 * that playlist on it."
 *
 * - A delete never removes a reminder, here or through any sync.
 * - A reminder whose playlist is not on this device is ORPHANED: hidden from
 *   every list and badge (ReminderDao.VISIBLE), its alarm cancelled and
 *   never set again while it stays orphaned.
 * - When a playlist with the reminder's channel is on this device again (the
 *   same playlist re-added, or another with that channel), the reminder is
 *   re-homed to it and shows again; its alarm is set again if the program is
 *   still ahead.
 * - Other devices are unaffected: nothing is written to sync for any of it.
 */
object ReminderVisibility {

    /** Orphans that [channelNames] (a playlist's channels) bring back: those whose channel it has. */
    fun comeBack(orphans: List<ReminderEntity>, channelNames: Set<String>): List<ReminderEntity> {
        if (orphans.isEmpty() || channelNames.isEmpty()) return emptyList()
        val names = channelNames.mapTo(HashSet()) { norm(it) }
        return orphans.filter { norm(it.channelName) in names }
    }

    /** Whether a reminder's alarm is due to be set: the program has not started yet. */
    fun stillAhead(r: ReminderEntity, now: Long): Boolean = r.startMillis > now

    /** When the alarm goes off: five minutes before the start, never in the past. */
    fun triggerAt(r: ReminderEntity, now: Long): Long = (r.startMillis - 5 * 60 * 1000L).coerceAtLeast(now + 1_000L)

    private fun norm(s: String) = s.trim().lowercase()
}

/**
 * The reminder alarm, set and cancelled with the same PendingIntent identity
 * RemindersViewModel uses (request code, receiver class).
 */
object ReminderAlarms {
    private const val TAG = "ReminderAlarms"

    private fun intent(context: Context, r: ReminderEntity, channelId: String = "") =
        Intent(context, ReminderBroadcastReceiver::class.java).apply {
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_KEY, r.reminderKey)
            putExtra(ReminderBroadcastReceiver.EXTRA_TITLE, r.programTitle)
            putExtra(ReminderBroadcastReceiver.EXTRA_CHANNEL_NAME, r.channelName)
            putExtra(ReminderBroadcastReceiver.EXTRA_CHANNEL_ID, channelId)
        }

    /** Set [r]'s alarm (replaces one already set). */
    fun schedule(context: Context, r: ReminderEntity, now: Long = System.currentTimeMillis()) {
        val mgr = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, r.alarmRequestCode, intent(context, r),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching { mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ReminderVisibility.triggerAt(r, now), pi) }
            .onFailure { Log.w(TAG, "schedule failed", it) }
    }

    /** [r]'s alarm is set (its PendingIntent exists; [cancel] removes it). */
    fun isScheduled(context: Context, r: ReminderEntity): Boolean =
        PendingIntent.getBroadcast(
            context, r.alarmRequestCode, Intent(context, ReminderBroadcastReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null

    /** Cancel [r]'s alarm and its PendingIntent, so nothing of it can fire. */
    fun cancel(context: Context, r: ReminderEntity) {
        val mgr = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, r.alarmRequestCode, Intent(context, ReminderBroadcastReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        mgr.cancel(pi)
        pi.cancel()
    }

    /**
     * A playlist is gone from this device: every reminder set on it keeps
     * its row, and its alarm is cancelled. Returns how many.
     */
    suspend fun suspendForPlaylist(context: Context, dao: ReminderDao, playlistId: String): Int {
        val rows = dao.allForPlaylist(playlistId)
        rows.forEach { cancel(context, it) }
        return rows.size
    }

    /**
     * [playlistId]'s channels are on this device ([channelNames]): orphans
     * with one of those channels come back on it, and every reminder of the
     * playlist still ahead whose alarm is not set gets it (the same playlist
     * re-added after a delete; one already set keeps its own, with its
     * tap-to-tune channel). Returns how many came back.
     */
    suspend fun restoreForPlaylist(context: Context, dao: ReminderDao, playlistId: String, channelNames: Set<String>): Int {
        val back = ReminderVisibility.comeBack(dao.orphansOnce(), channelNames)
        back.forEach { dao.rehome(it.reminderKey, it.profileScope, playlistId) }
        val now = System.currentTimeMillis()
        val ahead = dao.allForPlaylist(playlistId).filter { ReminderVisibility.stillAhead(it, now) && !isScheduled(context, it) }
        ahead.forEach { schedule(context, it, now) }
        return back.size
    }
}
