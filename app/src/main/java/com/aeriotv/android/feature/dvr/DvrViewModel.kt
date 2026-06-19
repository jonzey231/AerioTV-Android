package com.aeriotv.android.feature.dvr

import android.content.Context
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
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val appContext: Context,
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
        /**
         * Genre/category string from the matched EPG programme (XMLTV
         * <category>, comma/slash/semicolon-separated). Empty when the
         * recording's programme isn't in the cache. Drives the genre pills
         * under the row, tinted by the same CategoryPalette as the guide.
         * Mirrors iOS RecordingRow.epgCategory (MyRecordingsView.swift:715).
         */
        val category: String = "",
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
        /**
         * Dispatcharr channel id this recording is for (audit task #50
         * watch-live). Populated on server recordings from the
         * Dispatcharr API response; null on local recordings (the source
         * channel isn't persisted in LocalRecordingEntity). The DVR tab
         * surfaces a "Watch Live" action when a server recording is
         * Recording (in-progress) and this id is non-null.
         */
        val dispatcharrChannelId: Int? = null,
        /**
         * Server-provided playback URL for an IN-PROGRESS server recording.
         * Mirrors iOS Recording.dispatcharrFileURL (Models.swift 718): the
         * resolved custom_properties.output_file_url/file_url. For
         * Dispatcharr's DVR pipeline this is the growing HLS playlist
         * (.../recordings/<id>/hls/index.m3u8); on older builds it's the raw
         * /file/ partial. Null when the server emits neither. Distinct from
         * playbackUrl, which is only populated for finalized Completed/Stopped
         * rows.
         */
        val inProgressUrl: String? = null,
        /**
         * True when inProgressUrl is an HLS playlist (.m3u8): the player
         * treats the stream as a growing seekable live window (live-edge
         * clamp + LIVE pill + seek-at-EOF). Mirrors iOS
         * `isDVR = rec.isInProgress && urlSource == server-hls`
         * (MyRecordingsView.swift 628). A raw /file/ partial plays as plain
         * VOD (isDvr=false).
         */
        val isDvr: Boolean = false,
    ) {
        enum class Status { Scheduled, Recording, Completed, Failed, Stopped, Unknown }

        private val isTerminal: Boolean
            get() = status == Status.Completed || status == Status.Stopped ||
                status == Status.Failed

        /**
         * Server-side status lags: when AerioTV POSTs a recording for an
         * already-airing program, Dispatcharr returns status=="scheduled"
         * for the first several seconds until its scheduler flips the job to
         * "recording". Treat "now inside [start,end)" as actively recording
         * so the row lands under Recording immediately. Mirrors iOS isAiringNow
         * (MyRecordingsView.swift:84). Terminal states never reclassify.
         */
        fun isAiringNow(now: Long = System.currentTimeMillis()): Boolean =
            !isTerminal && startMillis <= now && now < endMillis

        fun effectiveStatus(now: Long = System.currentTimeMillis()): Status =
            if (isAiringNow(now)) Status.Recording else status
    }

    data class UiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val recordings: List<Recording> = emptyList(),
        val filter: Filter = Filter.Scheduled,
        /** True when the active playlist is NOT Dispatcharr-backed (no DVR available). */
        val unsupportedSource: Boolean = false,
    ) {
        val scheduledCount: Int get() = recordings.count { it.effectiveStatus() == Recording.Status.Scheduled }
        val recordingCount: Int get() = recordings.count { it.effectiveStatus() == Recording.Status.Recording }
        val completedCount: Int get() = recordings.count {
            val s = it.effectiveStatus()
            s == Recording.Status.Completed || s == Recording.Status.Stopped || s == Recording.Status.Failed
        }
        val visible: List<Recording> get() = when (filter) {
            Filter.Scheduled -> recordings.filter { it.effectiveStatus() == Recording.Status.Scheduled }
            Filter.Recording -> recordings.filter { it.effectiveStatus() == Recording.Status.Recording }
            Filter.Completed -> recordings.filter {
                val s = it.effectiveStatus()
                s == Recording.Status.Completed || s == Recording.Status.Stopped || s == Recording.Status.Failed
            }
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Resolved genre/category strings keyed by recording id (e.g.
     * "server-42"). A COMPLETED recording's programme has rolled out of the
     * on-disk EPG window, so its category can only come from a network lookup
     * (`getProgramsByTvgId`). That call is comparatively expensive and the row
     * never changes once finalized, so we memo the result here: each blank-
     * category row is fetched at most once across the 30s refresh loop. A
     * sentinel empty string marks "tried, server had no category" so we don't
     * re-hit the network for a feed that genuinely carries no `<category>`.
     */
    private val categoryCache = mutableMapOf<String, String>()

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
            // Effective base (LAN when on a saved home SSID, else WAN), the
            // same base the stream URLs use. Server recordings whose file_url
            // is a relative path are anchored to this so a phone off the home
            // network gets the reachable address (iOS resolves file_url
            // against server.effectiveBaseURL).
            val base = playlistRepository.effectiveBaseUrl(playlist)
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.listRecordings(playlist.urlString, key)
                }
            }.fold(
                onSuccess = { remote ->
                    val server = remote.map { it.toRecording(base, dispatcharrClient) }
                    // Fill in programme title/description from the cached guide for
                    // rows the server left sparse, so scheduled / ongoing / completed
                    // recordings all show full EPG (suspend, so computed before the
                    // non-suspend state update).
                    val fromCache = hydrateRecordingsFromEpg(playlist.id, server)
                    // Completed recordings whose programme already aged out of the
                    // on-disk EPG window still have a blank category after the cache
                    // pass; resolve those over the network (Streamer feedback #6).
                    val hydrated = hydrateCompletedCategoriesFromNetwork(
                        playlistId = playlist.id,
                        baseUrl = playlist.urlString,
                        recordings = fromCache,
                    )
                    _state.update { st ->
                        val local = st.recordings.filter { it.source == Source.Local }
                        // Authoritative server list wins by id, so an
                        // optimistically-inserted row from scheduleServerRecording
                        // (same "server-$id") is replaced, never duplicated.
                        val serverById = hydrated.associateBy { it.id }
                        st.copy(
                            isLoading = false,
                            recordings = (serverById.values + local).sortedBy { it.startMillis },
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
    /**
     * Audit task #50 (delete-all sub-item): bulk-delete every Completed /
     * Stopped / Failed recording currently visible. Loops through
     * deleteRecording per row so server + local sources are each handled
     * via their respective path (server: API delete + refresh; local: rm
     * the .ts file + drop the row). Aggregates failures rather than
     * short-circuiting, so a single server 401 doesn't strand a dozen
     * local rows.
     */
    suspend fun deleteAllCompleted(): Result<Int> = runCatching {
        // Snapshot the list -- as we delete, the state list flips out from
        // under us; we want to preserve the original work order.
        val targets = _state.value.recordings.filter {
            it.status == Recording.Status.Completed ||
                it.status == Recording.Status.Stopped ||
                it.status == Recording.Status.Failed
        }
        var failures = 0
        for (rec in targets) {
            deleteRecording(rec).onFailure { failures++ }
        }
        if (failures > 0) {
            throw IllegalStateException("Failed to delete $failures of ${targets.size}")
        }
        targets.size
    }

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
                        deleteLocalFile(match.filePath)
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
            // Optimistic insert: the POST already returned the full server row
            // (real id, channel, window). Merge it into state NOW so the
            // "Recording (N)" pill + red row appear immediately, instead of
            // waiting for the second LIST round-trip + EPG hydration in
            // refresh(). effectiveStatus() reclassifies an airing-now row as
            // Recording on its own, so no status guessing. The row uses the
            // same "server-$id" key the refresh-produced row will, so the
            // dedup-by-id guard in refresh() reconciles it in place.
            val base = playlistRepository.effectiveBaseUrl(playlist)
            val optimistic = result.toRecording(base, dispatcharrClient)
            _state.update { st ->
                val without = st.recordings.filterNot { it.id == optimistic.id }
                st.copy(recordings = (without + optimistic).sortedBy { it.startMillis })
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

    /**
     * Save to Device (audit #43): download a finalized server recording to
     * local storage via the foreground [LocalRecordingService], after which it
     * appears in the DVR tab as a Local copy playable offline. Mirrors iOS
     * downloadDispatcharrRecording. Server recordings only; the playbackUrl is
     * the same /file/ URL used for streaming, fetched with the source's
     * X-API-Key.
     */
    suspend fun saveToDevice(recording: Recording): Result<Unit> {
        if (recording.source != Source.Server) {
            return Result.failure(IllegalStateException("Save to Device is for server recordings."))
        }
        val url = recording.playbackUrl
            ?: return Result.failure(IllegalStateException("This recording has no file to download yet."))
        val playlist = playlistRepository.activePlaylist()
            ?: return Result.failure(IllegalStateException("No playlist loaded."))
        val key = playlist.apiKey?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("Active source is not Dispatcharr-backed."))
        return runCatching {
            LocalRecordingService.download(
                context = appContext,
                fileUrl = url,
                title = recording.title,
                channelName = recording.title,
                apiKey = key,
            )
        }
    }

    /**
     * Delete a local recording's bytes, handling the public-Downloads /
     * SAF content:// URI, a file:// URI, and a bare filesystem path (the
     * app-private fallback). Best-effort; the Room row is dropped regardless.
     */
    private fun deleteLocalFile(path: String) {
        runCatching {
            when {
                path.startsWith("content://") ->
                    appContext.contentResolver.delete(android.net.Uri.parse(path), null, null)
                path.startsWith("file://") ->
                    android.net.Uri.parse(path).path?.let { java.io.File(it).delete() }
                else -> java.io.File(path).delete()
            }
        }
    }

    /**
     * Fill in programme title/description for server recordings the server left
     * sparse. Dispatcharr only stamps `custom_properties.program` on some rows
     * (e.g. ones scheduled from its own guide), so bare or AerioTV-created
     * schedules arrive with a generic "Recording N" title and no description.
     * We join each such recording against the disk EPG cache by the channel's
     * tvg-id and the programme whose airing overlaps the recording window, so
     * scheduled / ongoing / completed recordings all show full programme info
     * consistently. Best-effort and offline-safe: rows whose programme isn't in
     * the cache (e.g. past programmes already pruned) are left untouched.
     */
    private suspend fun hydrateRecordingsFromEpg(
        playlistId: String,
        recordings: List<Recording>,
    ): List<Recording> {
        fun needsHydration(r: Recording): Boolean {
            if (r.dispatcharrChannelId == null) return false
            val rawId = r.id.removePrefix("server-")
            val genericTitle = r.title.isBlank() || r.title == "Recording $rawId"
            return genericTitle || r.description.isBlank() || r.category.isBlank()
        }
        val needy = recordings.filter(::needsHydration)
        if (needy.isEmpty()) return recordings
        val tvgByChannelId = playlistRepository.loadCachedChannels(playlistId)
            .mapNotNull { c -> c.dispatcharrChannelId?.let { id -> id to c.tvgID } }
            .filter { it.second.isNotBlank() }
            .toMap()
        if (tvgByChannelId.isEmpty()) return recordings
        val from = needy.minOf { it.startMillis }
        val to = needy.maxOf { it.endMillis }
        if (from <= 0L || to <= from) return recordings
        val epgByChannel = playlistRepository.loadCachedEpg(playlistId, from, to)
            .groupBy { it.channelId }
        if (epgByChannel.isEmpty()) return recordings
        return recordings.map { r ->
            if (!needsHydration(r)) return@map r
            val tvg = tvgByChannelId[r.dispatcharrChannelId] ?: return@map r
            val prog = epgByChannel[tvg]
                ?.maxByOrNull { overlapMillis(it.startMillis, it.endMillis, r.startMillis, r.endMillis) }
                ?.takeIf { overlapMillis(it.startMillis, it.endMillis, r.startMillis, r.endMillis) > 0L }
                ?: return@map r
            val rawId = r.id.removePrefix("server-")
            val genericTitle = r.title.isBlank() || r.title == "Recording $rawId"
            r.copy(
                title = if (genericTitle && prog.title.isNotBlank()) prog.title else r.title,
                description = if (r.description.isBlank()) prog.description else r.description,
                category = if (r.category.isBlank()) prog.category else r.category,
            )
        }
    }

    /**
     * Resolve a genre/category for recordings whose programme has already
     * rolled out of the on-disk EPG window (i.e. COMPLETED rows), which the
     * cache pass [hydrateRecordingsFromEpg] can't reach. Fixes Streamer
     * feedback #6: only the currently-airing recording showed pills because
     * the cache only holds the live now-window; past programmes resolved
     * blank so the pill gate hid them.
     *
     * Source of truth is the Dispatcharr programme list
     * (`/api/epg/programs/?tvg_id=<id>`), which is NOT window-bounded and
     * carries the per-programme `categories` array the bulk grid strips. We
     * list the feed for each needy recording's channel, overlap-match the
     * recording window to the programme, and read its categories.
     *
     * Throttled hard so it doesn't hammer the server every 30s:
     *  - Only blank-category, channel-bearing rows are considered.
     *  - Results (including a "server had none" empty sentinel) are memoised
     *    in [categoryCache] by recording id, so each row is fetched at most
     *    once for the app's lifetime.
     *  - One list call per distinct tvg-id per refresh (de-duplicated), so a
     *    burst of completed rows on the same channel costs a single request.
     * Best-effort and offline-safe: a failed fetch leaves the row blank
     * (pills stay hidden) rather than throwing.
     */
    private suspend fun hydrateCompletedCategoriesFromNetwork(
        playlistId: String,
        baseUrl: String,
        recordings: List<Recording>,
    ): List<Recording> {
        // Apply anything we already resolved on a prior refresh first.
        val seeded = recordings.map { r ->
            val cached = categoryCache[r.id]
            if (r.category.isBlank() && !cached.isNullOrBlank()) r.copy(category = cached) else r
        }
        val needy = seeded.filter {
            it.category.isBlank() &&
                it.dispatcharrChannelId != null &&
                !categoryCache.containsKey(it.id)
        }
        if (needy.isEmpty()) return seeded
        val tvgByChannelId = playlistRepository.loadCachedChannels(playlistId)
            .mapNotNull { c -> c.dispatcharrChannelId?.let { id -> id to c.tvgID } }
            .filter { it.second.isNotBlank() }
            .toMap()
        if (tvgByChannelId.isEmpty()) return seeded
        // One feed fetch per distinct tvg-id this batch needs.
        val tvgIds = needy.mapNotNull { tvgByChannelId[it.dispatcharrChannelId] }.toSet()
        val programsByTvg = HashMap<String, List<DispatcharrProgramEntryLite>>()
        for (tvg in tvgIds) {
            val rows = runCatching {
                dispatcharrAuth.withApiKeyRetry(playlistId) { k ->
                    dispatcharrClient.getProgramsByTvgId(baseUrl, k, tvg)
                }
            }.getOrNull().orEmpty()
            programsByTvg[tvg] = rows.map {
                DispatcharrProgramEntryLite(
                    startMillis = parseIsoMillis(it.startTime) ?: 0L,
                    endMillis = parseIsoMillis(it.endTime) ?: 0L,
                    categories = it.categories.orEmpty(),
                )
            }
        }
        return seeded.map { r ->
            if (r.id !in needy.map { it.id }) return@map r
            val tvg = tvgByChannelId[r.dispatcharrChannelId]
            if (tvg == null) {
                categoryCache[r.id] = ""
                return@map r
            }
            val match = programsByTvg[tvg]
                ?.maxByOrNull { overlapMillis(it.startMillis, it.endMillis, r.startMillis, r.endMillis) }
                ?.takeIf { overlapMillis(it.startMillis, it.endMillis, r.startMillis, r.endMillis) > 0L }
            val resolved = match?.categories
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.joinToString(", ")
                .orEmpty()
            // Memoise even the empty result so a feed without <category> isn't
            // re-fetched every 30s.
            categoryCache[r.id] = resolved
            if (resolved.isNotBlank()) r.copy(category = resolved) else r
        }
    }

    private data class DispatcharrProgramEntryLite(
        val startMillis: Long,
        val endMillis: Long,
        val categories: List<String>,
    )

    private fun overlapMillis(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Long =
        (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0L)

    private companion object {
        const val TAG = "DvrViewModel"
    }
}

private fun DispatcharrRecording.toRecording(
    baseUrl: String,
    client: DispatcharrClient,
): DvrViewModel.Recording {
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
    // Build a playback URL for finalized recordings, mirroring iOS
    // playServerRecording (MyRecordingsView.swift line 559): prefer the
    // server-reported file_url / output_file_url (resolved against the
    // effective base), else fall back to the constructed /file/ endpoint for
    // older Dispatcharr builds. Auth headers (X-API-Key) are applied by the
    // recording-player route, not baked into the URL.
    val playback: String? = when (status) {
        DvrViewModel.Recording.Status.Completed,
        DvrViewModel.Recording.Status.Stopped ->
            resolveRecordingUrl(fileUrl, baseUrl) ?: client.recordingPlaybackUrl(baseUrl, id)
        else -> null
    }
    // In-progress catch-up / watch-live (audit #50, iOS v1.6.22 + #29).
    // Mirror iOS playServerRecording: prefer the server-reported file_url
    // (HLS for in-progress on the new DVR pipeline), else the legacy
    // /file/ partial. Only the .m3u8 case is a true growing DVR window;
    // the raw partial plays as fixed VOD.
    val inProgress: String? = if (status == DvrViewModel.Recording.Status.Recording) {
        resolveRecordingUrl(fileUrl, baseUrl) ?: client.recordingPlaybackUrl(baseUrl, id)
    } else null
    val dvr = inProgress?.contains(".m3u8", ignoreCase = true) == true
    return DvrViewModel.Recording(
        id = "server-$id",
        source = DvrViewModel.Source.Server,
        title = title.ifBlank { "Recording $id" },
        description = description,
        startMillis = start,
        endMillis = end,
        status = status,
        fileSizeBytes = fileSize ?: 0L,
        playbackUrl = playback,
        dispatcharrChannelId = channel,
        inProgressUrl = inProgress,
        isDvr = dvr,
    )
}

/**
 * Resolve a Dispatcharr-reported recording file_url against the active
 * server's effective base URL. Mirrors iOS resolveRecordingURL
 * (MyRecordingsView.swift line 614): an already-absolute value is used as-is;
 * a relative path is anchored to the base. Returns null for a blank input.
 */
private fun resolveRecordingUrl(fileUrl: String?, baseUrl: String): String? {
    val trimmed = fileUrl?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return trimmed
    }
    val base = baseUrl.trimEnd('/')
    if (base.isEmpty()) return null
    val path = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    return "$base$path"
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
        // A recording saved to the public Downloads (MediaStore) or a SAF
        // custom folder is stored as a content:// URI; a path from the
        // app-private dir needs wrapping in file://. ExoPlayer's
        // DefaultDataSource resolves both schemes.
        if (filePath.startsWith("content://") || filePath.startsWith("file://")) filePath
        else android.net.Uri.fromFile(java.io.File(filePath)).toString()
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
