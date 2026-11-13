package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ColumnInfo
import com.aeriotv.android.core.profiles.ProfileScope

/**
 * One row per scheduled programme reminder. Mirrors iOS `programReminders`
 * @AppStorage map keyed by "channelName|title|startTs", same composite key
 * lives in [reminderKey] so the Android port preserves uniqueness semantics.
 *
 * The alarm itself is scheduled with AlarmManager at [startMillis] minus a
 * 5-minute pre-roll so users get a heads-up before the programme starts.
 *
 * `playlistId` is the playlist the reminder was set on. A playlist delete
 * keeps its reminders (Logan 2026-11-12: "Leave them but make sure they don't
 * appear on that same device without that playlist on it"), so since DB v40
 * this is no longer a cascading foreign key: a reminder whose playlist is
 * gone is ORPHANED (ReminderVisibility). It is hidden from every list and
 * badge, its alarm is cancelled and never set again, and it comes back
 * (re-homed, re-scheduled if still ahead) when a playlist with its channel
 * is on this device again. Null = a row from before v9, always visible.
 */
@Entity(
    tableName = "reminder",
    primaryKeys = ["reminderKey", "profileScope"],
    indices = [Index("playlistId")],
)
data class ReminderEntity(
    val reminderKey: String,
    val channelName: String,
    val programTitle: String,
    val startMillis: Long,
    val endMillis: Long,
    /** Stable AlarmManager request code derived from `reminderKey.hashCode()`. */
    val alarmRequestCode: Int,
    val playlistId: String? = null,
    /** Profiles (DB v37): "" = default profile (every pre-v37 row). */
    @ColumnInfo(defaultValue = "")
    val profileScope: String = ProfileScope.current(),
)

/** Composite key matching the iOS shape so a reminder's identity is portable. */
fun reminderKey(channelName: String, programTitle: String, startMillis: Long): String =
    "$channelName|$programTitle|$startMillis"
