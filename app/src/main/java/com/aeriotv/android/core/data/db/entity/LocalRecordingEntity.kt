package com.aeriotv.android.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.aeriotv.android.core.profiles.ProfileScope
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per local recording file. Written by [LocalRecordingService] when a
 * capture starts (status "recording") and finished when it ends; a row left
 * recording by a process death is finished at the next launch
 * (InterruptedCapture). Save to Device copies are written when they complete.
 * Mirrors iOS Recording (Models.swift:680)
 * but only the local-destination fields, server-side recordings stay on
 * Dispatcharr and are queried fresh via the API.
 *
 * `playlistId` is the new (DB v9) FK to PlaylistEntity. Mirrors iOS commit
 * 2efd777 (2026-05-10) cascade-delete behaviour: removing a playlist drops
 * its local recording rows. The TS file on disk is NOT auto-deleted by this
 * cascade, file cleanup is the responsibility of the user-facing delete
 * action in DvrTabContent. Nullable + ON DELETE CASCADE so pre-v9 rows
 * survive the migration with null playlistId and never trigger an
 * unintended cascade.
 */
@Entity(
    tableName = "local_recording",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class LocalRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val channelName: String,
    val title: String,
    val filePath: String,
    val startedAt: Long,
    val endedAt: Long,
    val byteSize: Long,
    // "recording" (written when a capture starts) | "completed" | "stopped" |
    // "interrupted" (no capture was left behind it; InterruptedCapture) | "failed"
    val status: String,
    val playlistId: String? = null,
    /**
     * Why a "failed" recording captured nothing, in Apple's wording
     * (RecordingCoordinator failureReason), e.g. "No data was written".
     * Null otherwise (DB v41).
     */
    val failureReason: String? = null,
    /** Profiles (DB v37): "" = default profile (every pre-v37 row). */
    @ColumnInfo(defaultValue = "")
    val profileScope: String = ProfileScope.current(),
)
