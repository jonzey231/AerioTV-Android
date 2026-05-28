package com.aeriotv.android.feature.dvr

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.dao.LocalRecordingDao
import com.aeriotv.android.core.data.db.entity.LocalRecordingEntity
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.network.DispatcharrAuthBroker
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.DispatcharrRecording
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the DVR tab's state and the recording-creation entry point. Phase 9a
 * is Dispatcharr-server only; Phase 9b adds local recording via a foreground
 * service.
 *
 * Loads recordings from `/api/channels/recordings/` on demand. The shape from
 * the server feeds [Recording] which the DVR tab filters by status.
 */
@HiltViewModel
class DvrViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val dispatcharrClient: DispatcharrClient,
    private val dispatcharrAuth: DispatcharrAuthBroker,
    private val localRecordingDao: LocalRecordingDao,
) : ViewModel() {

    enum class Filter { Scheduled, Recording, Completed }
    enum class Source { Server, Local }

    data class Recording(
        val id: String,
        val source: Source,
        val title: String,
        val description: String,
        val startMillis: Long,
        val endMillis: Long,
        val status: Status,
        val fileSizeBytes: Long,
        /**
         * Playable URL for completed recordings. `file://...` for local DVR
         * captures (audit #43); a server URL for Dispatcharr server-side
         * recordings will follow in a separate phase. `null` while the
         * recording is still in-progress / scheduled / failed.
         */
        val playbackUrl: String? = null,
    ) {
        enum class Status { Scheduled, Recording, Completed, Failed, Stopped, Unknown }
    }

    data class UiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val recordings: List<Recording> = emptyList(),
        val filter: Filter = Filter.Scheduled,
        /** True when the active playlist is NOT Dispatcharr-backed (no DVR available). */
        val unsupportedSource: Boolean = false,
    ) {
        val scheduledCount: Int get() = recordings.count { it.status == Recording.Status.Scheduled }
        val recordingCount: Int get() = recordings.count { it.status == Recording.Status.Recording }
        val completedCount: Int get() = recordings.count {
            it.status == Recording.Status.Completed ||
                    it.status == Recording.Status.Stopped ||
                    it.status == Recording.Status.Failed
        }
        val visible: List<Recording> get() = when (filter) {
            Filter.Scheduled -> recordings.filter { it.status == Recording.Status.Scheduled }
            Filter.Recording -> recordings.filter { it.status == Recording.Status.Recording }
            Filter.Completed -> recordings.filter {
                it.status == Recording.Status.Completed ||
                        it.status == Recording.Status.Stopped ||
                        it.status == Recording.Status.Failed
            }
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            localRecordingDao.observeAll().collect { rows ->
                val mapped = rows.map { it.toRecording() }
                _state.update { st ->
                    val merged = (st.recordings.filter { it.source == Source.Server } + mapped)
                        .sortedBy { it.startMillis }
                    st.copy(recordings = merged)
                }
            }
        }
    }

    fun setFilter(filter: Filter) {
        _state.update { it.copy(filter = filter) }
    }

    fun refresh() {
        viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist()
            val sourceType = playlist?.sourceType?.let { SourceType.entries.firstOrNull { st -> st.name == it } }
            val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
                    sourceType == SourceType.DispatcharrUserPass
            if (playlist == null || !isDispatcharr || playlist.apiKey.isNullOrBlank()) {
                _state.update { it.copy(unsupportedSource = true, recordings = emptyList(), isLoading = false, error = null) }
                return@launch
            }
            _state.update { it.copy(isLoading = true, error = null, unsupportedSource = false) }
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.listRecordings(playlist.urlString, key)
                }
            }.fold(
                onSuccess = { remote ->
                    _state.update { st ->
                        val server = remote.map { it.toRecording() }
                        val local = st.recordings.filter { it.source == Source.Local }
                        st.copy(
                            isLoading = false,
                            recordings = (server + local).sortedBy { it.startMillis },
                            error = null,
                        )
                    }
                },
                onFailure = { t ->
                    Log.w(TAG, "listRecordings failed", t)
                    _state.update { it.copy(isLoading = false, error = t.message ?: t::class.simpleName) }
                },
            )
        }
    }

    /**
     * Deletes a recording. Server rows route through DispatcharrClient.deleteRecording
     * (Dispatcharr removes the file from disk for completed rows and just unschedules
     * scheduled rows). Local rows delete the .ts file from getExternalFilesDir
     * AND drop the Room row so it disappears from the DVR tab immediately.
     */
    suspend fun deleteRecording(recording: Recording): Result<Unit> {
        return runCatching {
            when (recording.source) {
                Source.Server -> {
                    val playlist = playlistRepository.activePlaylist()
                        ?: throw IllegalStateException("No playlist loaded.")
                    if (playlist.apiKey.isNullOrBlank()) {
                        throw IllegalStateException("Active source is not Dispatcharr-backed.")
                    }
                    val intId = recording.id.removePrefix("server-").toIntOrNull()
                        ?: throw IllegalStateException("Invalid server recording id: ${recording.id}")
                    dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                        dispatcharrClient.deleteRecording(playlist.urlString, key, intId)
                    }
                    refresh()
                }
                Source.Local -> {
                    val rowId = recording.id.removePrefix("local-").toLongOrNull()
                        ?: throw IllegalStateException("Invalid local recording id: ${recording.id}")
                    val rows = localRecordingDao.observeAll().first()
                    val match = rows.firstOrNull { it.id == rowId }
                    if (match != null) {
                        runCatching { java.io.File(match.filePath).delete() }
                        localRecordingDao.delete(rowId)
                    }
                }
            }
        }
    }

    /**
     * Schedules a Dispatcharr-server recording for the given channel + program.
     * Caller passes pre-rolled times (start - preRollMin, end + postRollMin
     * already applied). Returns a Result that the caller can surface via toast.
     */
    suspend fun scheduleServerRecording(
        channelDispatcharrId: Int,
        startMillis: Long,
        endMillis: Long,
        title: String,
        description: String,
        comskip: Boolean,
    ): Result<DispatcharrRecording> {
        val playlist = playlistRepository.activePlaylist()
            ?: return Result.failure(IllegalStateException("No playlist loaded."))
        if (playlist.apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Active source is not Dispatcharr-backed."))
        }
        return runCatching {
            val result = dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.createRecording(
                    baseUrl = playlist.urlString,
                    apiKey = key,
                    channelId = channelDispatcharrId,
                    startIso = startMillis.toIsoUtc(),
                    endIso = endMillis.toIsoUtc(),
                    title = title,
                    description = description,
                    comskip = comskip,
                )
            }
            refresh()
            result
        }
    }

    /**
     * Edits an already-scheduled Dispatcharr server recording. Mirrors
     * scheduleServerRecording's signature minus the channel (the channel id
     * can't change for an existing row). Caller passes pre-rolled times.
     * Only valid for Source.Server recordings — local recordings (which are
     * downloads, not scheduled tasks) cannot be edited in place.
     */
    suspend fun editServerRecording(
        recordingId: Int,
        startMillis: Long,
        endMillis: Long,
        title: String,
        description: String,
    ): Result<DispatcharrRecording> {
        val playlist = playlistRepository.activePlaylist()
            ?: return Result.failure(IllegalStateException("No playlist loaded."))
        if (playlist.apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Active source is not Dispatcharr-backed."))
        }
        return runCatching {
            val result = dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.updateRecording(
                    baseUrl = playlist.urlString,
                    apiKey = key,
                    recordingId = recordingId,
                    startIso = startMillis.toIsoUtc(),
                    endIso = endMillis.toIsoUtc(),
                    title = title,
                    description = description,
                )
            }
            refresh()
            result
        }
    }

    /**
     * Kick off server-side commercial detection / removal on a completed
     * recording. Mirrors iOS contextMenu "Remove Commercials" (MyRecordingsView
     * line 305-309). The server handles idempotency so repeated taps are
     * safe — refresh() afterwards picks up any status change Dispatcharr
     * surfaces via custom_properties.
     */
    suspend fun applyComskip(recording: Recording): Result<Unit> {
        if (recording.source != Source.Server) {
            return Result.failure(IllegalStateException("Remove Commercials is server-only."))
        }
        val playlist = playlistRepository.activePlaylist()
            ?: return Result.failure(IllegalStateException("No playlist loaded."))
        if (playlist.apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Active source is not Dispatcharr-backed."))
        }
        val intId = recording.id.removePrefix("server-").toIntOrNull()
            ?: return Result.failure(IllegalStateException("Invalid recording id."))
        return runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.applyComskip(playlist.urlString, key, intId)
            }
            refresh()
        }
    }

    /**
     * Stop an in-progress server recording early. The partial file stays on
     * disk — caller pairs with deleteRecording when the partial isn't wanted.
     * Mirrors iOS contextMenu "Stop Recording" (MyRecordingsView line 332-336).
     */
    suspend fun stopRecording(recording: Recording): Result<Unit> {
        if (recording.source != Source.Server) {
            return Result.failure(IllegalStateException("Stop Recording is server-only."))
        }
        val playlist = playlistRepository.activePlaylist()
            ?: return Result.failure(IllegalStateException("No playlist loaded."))
        if (playlist.apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Active source is not Dispatcharr-backed."))
        }
        val intId = recording.id.removePrefix("server-").toIntOrNull()
            ?: return Result.failure(IllegalStateException("Invalid recording id."))
        return runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.stopRecording(playlist.urlString, key, intId)
            }
            refresh()
        }
    }

    private companion object {
        const val TAG = "DvrViewModel"
    }
}

private fun DispatcharrRecording.toRecording(): DvrViewModel.Recording {
    val start = parseIsoMillis(startTime) ?: 0L
    val end = parseIsoMillis(endTime) ?: start
    val status = when (this.status?.lowercase()) {
        "scheduled" -> DvrViewModel.Recording.Status.Scheduled
        "recording", "in_progress" -> DvrViewModel.Recording.Status.Recording
        "completed" -> DvrViewModel.Recording.Status.Completed
        "stopped" -> DvrViewModel.Recording.Status.Stopped
        "failed", "error" -> DvrViewModel.Recording.Status.Failed
        else -> DvrViewModel.Recording.Status.Unknown
    }
    return DvrViewModel.Recording(
        id = "server-$id",
        source = DvrViewModel.Source.Server,
        title = title.ifBlank { "Recording $id" },
        description = description,
        startMillis = start,
        endMillis = end,
        status = status,
        fileSizeBytes = fileSize ?: 0L,
    )
}

private fun LocalRecordingEntity.toRecording(): DvrViewModel.Recording {
    val status = when (this.status.lowercase()) {
        "completed" -> DvrViewModel.Recording.Status.Completed
        "stopped" -> DvrViewModel.Recording.Status.Stopped
        "failed", "error" -> DvrViewModel.Recording.Status.Failed
        else -> DvrViewModel.Recording.Status.Unknown
    }
    // file:// URI for libmpv. Path may contain spaces / unicode (the recording
    // service stamps the user-supplied channel name + ISO start time), so
    // URL-encode every segment via android.net.Uri.fromFile. mpv on Android
    // happily resolves file:// against the filesystem when SAF isn't involved.
    val isPlayable = status == DvrViewModel.Recording.Status.Completed ||
        status == DvrViewModel.Recording.Status.Stopped
    val playable = if (isPlayable && filePath.isNotBlank()) {
        android.net.Uri.fromFile(java.io.File(filePath)).toString()
    } else null
    return DvrViewModel.Recording(
        id = "local-$id",
        source = DvrViewModel.Source.Local,
        title = title.ifBlank { channelName },
        description = "",
        startMillis = startedAt,
        endMillis = endedAt,
        status = status,
        fileSizeBytes = byteSize,
        playbackUrl = playable,
    )
}

private val ISO_PARSER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}
private val ISO_PARSER_ALT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}
private val ISO_PARSER_Z = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

internal fun parseIsoMillis(iso: String): Long? {
    if (iso.isBlank()) return null
    return runCatching { ISO_PARSER.parse(iso)?.time }.getOrNull()
        ?: runCatching { ISO_PARSER_ALT.parse(iso)?.time }.getOrNull()
        ?: runCatching { ISO_PARSER_Z.parse(iso)?.time }.getOrNull()
}

private val ISO_EMIT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

internal fun Long.toIsoUtc(): String = ISO_EMIT.format(Date(this))
