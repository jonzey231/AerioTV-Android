package com.aeriotv.android.feature.watchprogress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Lightweight snapshot of an upcoming episode, stored as a JSON array in
 * [WatchProgressEntity.upNextQueue]. Captured from the loaded series when an
 * episode starts so Continue Watching can advance to the next episode on
 * finish without re-fetching the series. Mirrors iOS `UpNextEntry`
 * (Aerio/Models/VODModels.swift, Issue #19).
 */
@Serializable
data class UpNextEntry(
    val vodId: String,
    val title: String,
    val posterUrl: String? = null,
    val streamUrl: String? = null,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
)

/**
 * Persistence facade for the VOD player. Reads + writes the watch-progress
 * Room table. Mirrors iOS `WatchProgressManager` (Aerio/Models/VODModels.swift).
 */
@HiltViewModel
class WatchProgressViewModel @Inject constructor(
    private val dao: WatchProgressDao,
    private val playlistDao: PlaylistDao,
) : ViewModel() {

    fun observe(videoId: String): Flow<WatchProgressEntity?> = dao.observe(videoId)

    /**
     * Every episode progress row of one series, newest first. The detail
     * screens use this (not [observeRecent]) so a long-tail series still
     * finds its resume point, matching Apple's keyed lookup.
     */
    fun observeSeriesEpisodes(seriesId: String): Flow<List<WatchProgressEntity>> =
        dao.observeEpisodesForSeries(seriesId)

    /**
     * Mark an episode watched / unwatched from the detail page's long-press
     * menu (Apple VODDetailView.swift:913-925): watched writes a finished
     * row, unwatched deletes the row outright.
     */
    fun setEpisodeWatched(
        videoId: String,
        title: String,
        posterUrl: String?,
        seriesId: String?,
        seasonNumber: Int,
        episodeNumber: Int,
        watched: Boolean,
    ) {
        viewModelScope.launch {
            if (!watched) {
                dao.delete(videoId)
                return@launch
            }
            val now = System.currentTimeMillis()
            val existing = dao.getOnce(videoId)
            val duration = existing?.durationMs?.takeIf { it > 0L } ?: 0L
            dao.upsert(
                (existing ?: WatchProgressEntity(
                    videoId = videoId,
                    title = title,
                    posterUrl = posterUrl,
                    positionMs = 0L,
                    durationMs = 0L,
                    updatedAt = now,
                    playlistId = playlistDao.firstActive()?.id,
                )).copy(
                    title = title,
                    posterUrl = posterUrl ?: existing?.posterUrl,
                    positionMs = duration,
                    durationMs = duration,
                    updatedAt = now,
                    vodType = "episode",
                    seriesId = seriesId ?: existing?.seriesId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    isFinished = true,
                ),
            )
        }
    }

    /**
     * Most-recently-updated rows. Caller filters by videoId-set to match
     * the current Movies / Series cache. iOS calls this "Continue Watching"
     * (project_aeriotv_ios_architecture.md section D); the "5 min from end =
     * completed" heuristic that hides finished items is applied at the UI
     * site, not in the DAO query.
     */
    fun observeRecent(limit: Int = 20): Flow<List<WatchProgressEntity>> = dao.observeRecent(limit)

    suspend fun get(videoId: String): WatchProgressEntity? = dao.getOnce(videoId)

    /**
     * Upsert the current playback position with MERGE semantics: the optional
     * episode metadata (vodType, seriesId, season/episode, streamUrl,
     * upNextQueue) only overwrites when supplied non-null, so the player's
     * periodic position-only saves don't stomp the metadata captured once at
     * play time. Mirrors iOS `WatchProgressManager.save`.
     *
     * When an EPISODE first crosses into finished, the head of its up-next
     * queue is promoted into its own row so Continue Watching advances to the
     * next episode instead of dropping the series (iOS Issue #19). `isFinished`
     * defaults to the "within 5 min of the end" heuristic the UI also uses.
     */
    fun save(
        videoId: String,
        title: String,
        posterUrl: String?,
        positionMs: Long,
        durationMs: Long,
        vodType: String? = null,
        seriesId: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        streamUrl: String? = null,
        isFinished: Boolean? = null,
        upNextQueue: String? = null,
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val finished = isFinished
                ?: (durationMs > 0L && positionMs >= durationMs - FINISHED_THRESHOLD_MS)
            val existing = dao.getOnce(videoId)
            if (existing != null) {
                val wasFinished = existing.isFinished
                val merged = existing.copy(
                    title = title,
                    posterUrl = posterUrl ?: existing.posterUrl,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = now,
                    vodType = vodType ?: existing.vodType,
                    seriesId = seriesId ?: existing.seriesId,
                    seasonNumber = seasonNumber ?: existing.seasonNumber,
                    episodeNumber = episodeNumber ?: existing.episodeNumber,
                    streamUrl = streamUrl ?: existing.streamUrl,
                    isFinished = finished,
                    upNextQueue = upNextQueue ?: existing.upNextQueue,
                    playlistId = existing.playlistId ?: playlistDao.firstActive()?.id,
                )
                dao.upsert(merged)
                if (!wasFinished && finished && merged.vodType == "episode") {
                    advanceUpNext(merged, now)
                }
            } else {
                val row = WatchProgressEntity(
                    videoId = videoId,
                    title = title,
                    posterUrl = posterUrl,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = now,
                    playlistId = playlistDao.firstActive()?.id,
                    vodType = vodType ?: "movie",
                    seriesId = seriesId,
                    seasonNumber = seasonNumber ?: 0,
                    episodeNumber = episodeNumber ?: 0,
                    streamUrl = streamUrl,
                    isFinished = finished,
                    upNextQueue = upNextQueue,
                )
                dao.upsert(row)
                if (finished && row.vodType == "episode") advanceUpNext(row, now)
            }
        }
    }

    /**
     * Promote the next unwatched episode from a just-finished episode's
     * up-next queue into its own row, carrying the remaining queue forward so
     * a binge keeps surfacing the next episode in Continue Watching without
     * re-opening the series. Skips entries already finished (out-of-order
     * viewing). No-op at the end of the queue. Mirrors iOS
     * `WatchProgressManager.advanceUpNext`.
     */
    private suspend fun advanceUpNext(finished: WatchProgressEntity, now: Long) {
        val json = finished.upNextQueue ?: return
        val queue = runCatching { decodeQueue(json) }.getOrNull()?.toMutableList() ?: return
        while (queue.isNotEmpty()) {
            val head = queue.removeAt(0)
            val existingRow = dao.getOnce(head.vodId)
            if (existingRow != null) {
                if (existingRow.isFinished) continue // watched out of order; skip ahead
                dao.upsert(existingRow.copy(upNextQueue = encodeQueue(queue)))
                return
            }
            dao.upsert(
                WatchProgressEntity(
                    videoId = head.vodId,
                    title = head.title,
                    posterUrl = head.posterUrl,
                    positionMs = 0L,
                    durationMs = 0L,
                    updatedAt = now,
                    playlistId = finished.playlistId,
                    vodType = "episode",
                    seriesId = finished.seriesId,
                    seasonNumber = head.seasonNumber,
                    episodeNumber = head.episodeNumber,
                    streamUrl = head.streamUrl,
                    isFinished = false,
                    upNextQueue = encodeQueue(queue),
                ),
            )
            return
        }
    }

    /**
     * Record episode metadata + the up-next queue when an episode starts
     * playing, WITHOUT resetting the resume position (tapping an episode still
     * resumes where you left off, and the player's own periodic saves drive
     * position). Mirrors the metadata stash in iOS `VODDetailView.playEpisode`.
     */
    fun captureEpisodePlay(
        videoId: String,
        title: String,
        posterUrl: String?,
        seriesId: String?,
        seasonNumber: Int,
        episodeNumber: Int,
        streamUrl: String?,
        upNextQueue: String?,
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = dao.getOnce(videoId)
            if (existing != null) {
                dao.upsert(
                    existing.copy(
                        title = title,
                        posterUrl = posterUrl ?: existing.posterUrl,
                        updatedAt = now,
                        vodType = "episode",
                        seriesId = seriesId ?: existing.seriesId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        streamUrl = streamUrl ?: existing.streamUrl,
                        upNextQueue = upNextQueue ?: existing.upNextQueue,
                    ),
                )
            } else {
                dao.upsert(
                    WatchProgressEntity(
                        videoId = videoId,
                        title = title,
                        posterUrl = posterUrl,
                        positionMs = 0L,
                        durationMs = 0L,
                        updatedAt = now,
                        playlistId = playlistDao.firstActive()?.id,
                        vodType = "episode",
                        seriesId = seriesId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        streamUrl = streamUrl,
                        isFinished = false,
                        upNextQueue = upNextQueue,
                    ),
                )
            }
        }
    }

    fun delete(videoId: String) {
        viewModelScope.launch { dao.delete(videoId) }
    }

    companion object {
        private const val FINISHED_THRESHOLD_MS = 5 * 60 * 1000L
        private val json = Json { ignoreUnknownKeys = true }
        private val listSerializer = ListSerializer(UpNextEntry.serializer())

        /** Encode an up-next queue to JSON, or null when empty. */
        fun encodeQueue(queue: List<UpNextEntry>): String? =
            if (queue.isEmpty()) null else json.encodeToString(listSerializer, queue)

        private fun decodeQueue(s: String): List<UpNextEntry> =
            json.decodeFromString(listSerializer, s)
    }
}
