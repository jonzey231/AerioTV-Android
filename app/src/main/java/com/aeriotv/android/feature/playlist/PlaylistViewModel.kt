package com.aeriotv.android.feature.playlist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.DeepLinkTarget
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.debug.LogSanitizer
import com.aeriotv.android.core.data.bridgeChannelIds
import com.aeriotv.android.core.data.buildChannelEpgKeyBridge
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.repository.ChannelProfileOption
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.debug.MemoryPressureBus
import com.aeriotv.android.core.debug.VodResetBus
import com.aeriotv.android.core.preferences.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-scoped state for the source flow:
 *   Bootstrap -> (existing source? auto-refresh : Onboarding -> add) ->
 *   Main (tabs) -> tap channel -> Player.
 *
 * Onboarding supports multiple source types (M3U URL, Dispatcharr API key, etc.)
 * via a picker. The fields surface conditionally based on [UiState.sourceType].
 */
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val memoryPressureBus: MemoryPressureBus,
    private val vodResetBus: VodResetBus,
    private val appPreferences: AppPreferences,
    private val catchupResolver: com.aeriotv.android.core.playback.CatchupPlaybackResolver,
    // GH #22: playback teardown on playlist switch (see switchToPlaylist).
    private val exoHolder: com.aeriotv.android.core.playback.AerioExoPlayerHolder,
    private val exoWindowState: com.aeriotv.android.feature.player.ExoWindowState,
    private val miniPlayerSession: com.aeriotv.android.feature.miniplayer.MiniPlayerSession,
) : ViewModel() {

    enum class Phase { Bootstrapping, NeedsUrl, ChannelsReady }

    data class UiState(
        val phase: Phase = Phase.Bootstrapping,
        val sourceType: SourceType = SourceType.M3uUrl,
        /** User-supplied display name for the playlist (Phase 30 multi-playlist). */
        val name: String = "",
        /** Generic URL field; M3U URL for [SourceType.M3uUrl], base URL otherwise. */
        val url: String = "",
        /**
         * Optional LAN URL captured during onboarding for Dispatcharr / Xtream.
         * When the device joins one of the user's saved home SSIDs (Network
         * Settings) and this is non-blank, the runtime URL flips to this. The
         * remote URL above stays the canonical "off-network" path.
         */
        val lanUrl: String = "",
        val epgUrl: String = "",
        val apiKey: String = "",
        val username: String = "",
        val password: String = "",
        /** Per-playlist On Demand opt-in (iOS ServerConnection.vodEnabled).
         *  Bound to the "Fetch On Demand from this playlist" toggle in
         *  ConfigureSourceScreen / EditPlaylistScreen. Default true, threaded
         *  through SaveRequest into PlaylistEntity.vodEnabled. */
        val vodEnabled: Boolean = true,
        /** Catch-up guide-history retention draft for the add flow (task
         *  #135); threaded into SaveRequest.epgRetentionDays on submit. */
        val epgRetentionDays: Int = 7,
        val playlist: PlaylistEntity? = null,
        /** Id of the row THIS add-draft created (null until its first successful
         *  Test Connection). Used as the loadAndPersist existingId so a re-test
         *  edits the same draft row instead of duplicating -- WITHOUT reusing the
         *  active [playlist]'s id, which would edit the wrong playlist. Reset by
         *  [startNewSource] when a new add begins. */
        val draftPlaylistId: String? = null,
        val channels: List<M3UChannel> = emptyList(),
        val epgByChannel: Map<String, List<EPGProgramme>> = emptyMap(),
        val isEpgLoading: Boolean = false,
        /** Hours of already-aired guide the grid can scroll back through:
         *  the active playlist's epgRetentionDays * 24 once the deferred
         *  history pass has merged (task #135); 1h before that so cold-launch
         *  paint keeps its cheap narrow window. */
        val epgHistoryHours: Int = 1,
        val searchQuery: String = "",
        val selectedGroup: String = ALL_GROUPS,
        val sortMode: SortMode = SortMode.ByNumber,
        val isLoading: Boolean = false,
        val error: String? = null,
        /**
         * Dispatcharr channel profiles available for the active playlist, shown
         * as scoping options in Edit Playlist. Empty until [loadDispatcharrProfiles]
         * resolves (or for non-Dispatcharr sources / servers with no profiles).
         */
        val availableProfiles: List<ChannelProfileOption> = emptyList(),
        val profilesLoading: Boolean = false,
        /** Per-action feedback for Playlist Detail's rows. Idle outside that
         *  screen; the screen resets them on dispose via
         *  [clearDetailActionStatuses]. */
        val testStatus: ActionStatus = ActionStatus.Idle,
        val playlistRefreshStatus: ActionStatus = ActionStatus.Idle,
        val epgRefreshStatus: ActionStatus = ActionStatus.Idle,
        val refreshAllStatus: ActionStatus = ActionStatus.Idle,
        val lanRefreshStatus: ActionStatus = ActionStatus.Idle,
        /** Which URL [PlaylistRepository.effectiveBaseUrl] resolves to right
         *  now (LAN vs WAN), for the Connection row. Null until probed. */
        val activeRoute: ActiveRoute? = null,
    )

    /** Lifecycle of one Playlist Detail action (Test Connection / Refresh
     *  Playlist / Refresh EPG Data). */
    sealed interface ActionStatus {
        data object Idle : ActionStatus
        data object Running : ActionStatus
        data class Success(val message: String) : ActionStatus
        data class Failure(val message: String) : ActionStatus
    }

    /**
     * "Skip for now" on the Welcome screen: the user chose to enter the app
     * with no playlist. The MAIN route's NeedsUrl bounce-back guard respects
     * this for the rest of the session; without it, Skip navigated to MAIN
     * and was bounced straight back to Welcome within a frame, so the button
     * read as dead. Resets with the process: an unconfigured app still lands
     * on Welcome at next launch.
     */
    var onboardingSkipped: Boolean = false

    data class ActiveRoute(val isLan: Boolean, val url: String)

    companion object {
        const val ALL_GROUPS = "All"
        private const val TAG = "PlaylistViewModel"

        /**
         * How long a disk-cached EPG is treated as fresh before a relaunch also
         * hits the network. Within this window the cache is used as-is (instant,
         * no network); past it the cache is still painted instantly but a
         * background refresh runs. 30 minutes keeps now-playing accurate while
         * making quick app revisits zero-network.
         */
        private const val EPG_CACHE_TTL_MS = 30L * 60L * 1000L

        /**
         * Channel snapshots refresh on a much slower cadence than the EPG (a
         * channel list adds/removes channels far less often than guide data
         * changes), so the cache is treated as fresh for 24 h before a relaunch
         * also hits the network. Within the window the cache paints and we
         * skip the network entirely; outside it the cache still paints
         * instantly but a background refresh runs. Per Archie: loading times
         * shouldn't be an issue unless we're 24 hours removed from the cache.
         */
        private const val CHANNEL_CACHE_TTL_MS = 24L * 60L * 60L * 1000L

        /**
         * iOS GuideStore audit P3 #12: trigger a Rolling Prefetch when the
         * user has scrolled within 4 hours of the latest cached programme's
         * end time. 4h means a guide-scale=0.5 user (12h viewport) trips
         * the prefetch when they've consumed 8 of the 12 visible hours --
         * enough lead time for the fetch to land before they hit the empty
         * tail. Bigger gap = more wasted fetches; smaller gap = visible
         * empty cells before the refresh arrives. 4h is the iOS default
         * (`prefetchTriggerHours = 4`).
         */
        private const val PREFETCH_TRIGGER_DISTANCE_MS = 4L * 60L * 60L * 1000L

        /**
         * Don't re-fire a Rolling Prefetch within this many millis of the
         * previous one. The same coalescing layer (P1 #6) already dedups
         * concurrent fetches that overlap; the cooldown here is for the
         * case where a fetch succeeded but the user keeps scrolling --
         * without it, the cell-onAppear stream of trigger events would
         * keep firing every frame. 60s lines up with the iOS Rolling
         * Prefetch's `prefetchBreakerCooldown` floor.
         */
        private const val PREFETCH_COOLDOWN_MS = 60L * 1000L
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** One-shot signal that a source was successfully configured (added). The
     *  add flow navigates forward on THIS event, not on `phase == ChannelsReady`:
     *  when adding a second playlist `phase` is already ChannelsReady, so a
     *  phase-watcher never re-fires and the add silently succeeds with no advance. */
    private val _sourceConfigured = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sourceConfigured: SharedFlow<Unit> = _sourceConfigured.asSharedFlow()

    /** Pending Live TV guide-jump target (channel guideMatchKey + programme
     *  start millis), emitted when a Search EPG result is tapped while the
     *  app is foregrounded. GuideScreen collects this (when mounted) to
     *  scroll + focus the cell; MainScaffold collects it to switch to the
     *  Live TV tab. extraBufferCapacity=1 + DROP_OLDEST so a tap that lands
     *  before any collector is attached (cold guide) is still replayable via
     *  the DeepLinkTarget.GuideProgram durable path in Navigation.kt. */
    private val _guideJumpRequests =
        kotlinx.coroutines.flow.MutableSharedFlow<DeepLinkTarget.GuideProgram>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    val guideJumpRequests: SharedFlow<DeepLinkTarget.GuideProgram> = _guideJumpRequests.asSharedFlow()

    /** Called when a Search EPG result is tapped: stash + broadcast the
     *  jump, and force the group filter back to All so the target channel is
     *  guaranteed visible (iOS ChannelListView sets selectedGroup='All').
     *  Guide-mode itself is a SettingsViewModel/DataStore pref, flipped by
     *  the collector in MainScaffold (see that file). */
    fun requestGuideJump(channelGuideMatchKey: String, startMillis: Long) {
        _state.update { it.copy(selectedGroup = ALL_GROUPS) }
        _guideJumpRequests.tryEmit(
            DeepLinkTarget.GuideProgram(channelGuideMatchKey, startMillis)
        )
    }

    /** Begin adding a NEW source: clear the draft form + draft id so the add
     *  creates a fresh row (existingId = null) and never edits the active
     *  [UiState.playlist], and so the form can't carry over the active server's
     *  API key (which would win over typed user/pass and re-add the old server).
     *  Deliberately does NOT touch [UiState.playlist] / [UiState.channels] /
     *  [UiState.phase], so cancelling the add leaves the active playlist intact. */
    fun startNewSource(type: SourceType) {
        _state.update {
            it.copy(
                sourceType = type,
                name = "",
                url = "",
                lanUrl = "",
                epgUrl = "",
                apiKey = "",
                username = "",
                password = "",
                vodEnabled = true,
                epgRetentionDays = 7,
                draftPlaylistId = null,
                availableProfiles = emptyList(),
                isLoading = false,
                error = null,
            )
        }
    }

    init {
        bootstrap()
        observeMemoryPressure()
    }

    /**
     * Phase 144 audit task #58: when the system signals critical memory
     * pressure, drop the in-memory `epgByChannel` map. The Room disk cache is
     * untouched, so the next guide open re-paints from cache; the gain is
     * tens of MB of parsed EPGProgramme objects + the groupBy result map
     * that would otherwise wait until the next launch to be GC'd.
     *
     * The Streamer was running at 91% RAM + 100% swap in Phase 142
     * diagnostics; even modest EPG shedding here keeps us out of the
     * low-memory killer queue when other apps want to come up.
     */
    private fun observeMemoryPressure() {
        viewModelScope.launch {
            memoryPressureBus.level.collect { level ->
                if (MemoryPressureBus.isCritical(level)) {
                    val cleared = _state.value.epgByChannel.isNotEmpty()
                    if (cleared) {
                        Log.i(TAG, "onTrimMemory=$level: shedding in-memory EPG map")
                        _state.update { it.copy(epgByChannel = emptyMap()) }
                    }
                }
            }
        }
    }

    private fun bootstrap() {
        viewModelScope.launch {
            val saved = repository.activePlaylist()
            if (saved == null) {
                _state.update { it.copy(phase = Phase.NeedsUrl) }
                return@launch
            }
            val sourceType = SourceType.entries.firstOrNull { it.name == saved.sourceType }
                ?: SourceType.M3uUrl

            // Phase 130: paint the disk-cached channel list IMMEDIATELY so the
            // Live TV rail + cells are never blank on a cold launch (Archie's
            // observation: with caching working, loading times shouldn't be an
            // issue under 24h). The network refresh below runs in parallel and
            // swaps in fresh data when ready.
            val cachedChannels = runCatching { repository.loadCachedChannels(saved.id) }
                .getOrDefault(emptyList())
            val hasChannelCache = cachedChannels.isNotEmpty()
            _state.update {
                it.copy(
                    playlist = saved,
                    // Task #136: the guide's back-scroll span is known from the
                    // playlist row itself, so publish it BEFORE the guide first
                    // composes. Keeping windowStart stable from the start
                    // avoids the whole widen-mid-session scroll-compensation
                    // dance (the history ROWS still merge in lazily).
                    epgHistoryHours = saved.epgRetentionDays.coerceIn(1, 30) * 24,
                    sourceType = sourceType,
                    name = saved.name.orEmpty(),
                    url = saved.urlString,
                    lanUrl = saved.lanUrlString.orEmpty(),
                    epgUrl = saved.epgUrl.orEmpty(),
                    apiKey = saved.apiKey.orEmpty(),
                    username = saved.username.orEmpty(),
                    password = saved.password.orEmpty(),
                    // If we have cached channels, skip straight to ChannelsReady
                    // and don't show the spinner; otherwise stay in pre-bootstrap
                    // phase with the spinner until the first-ever network fetch
                    // lands.
                    phase = if (hasChannelCache) Phase.ChannelsReady else it.phase,
                    channels = cachedChannels,
                    isLoading = !hasChannelCache,
                )
            }
            if (hasChannelCache) {
                Log.i(TAG, "bootstrap: painted ${cachedChannels.size} cached channels")
                // Start the EPG cache-first paint in parallel so the guide
                // cells light up immediately too, instead of waiting on the
                // channel network refresh.
                loadEpgIfConfigured(saved)
            }

            // Freshness gate: within the TTL window, the cached rail is
            // good enough and we skip the channel network round-trip entirely
            // (the EPG cache has its own 30-min TTL).
            val newest = runCatching { repository.newestChannelFetch(saved.id) }.getOrNull()
            val freshChannels = hasChannelCache && newest != null &&
                (System.currentTimeMillis() - newest) < CHANNEL_CACHE_TTL_MS
            if (freshChannels) {
                Log.i(TAG, "bootstrap: channel cache fresh, skipping network refresh")
                return@launch
            }

            Log.i(TAG, "bootstrap: refreshing channels (hadCache=$hasChannelCache)")
            repository.refresh(saved).fold(
                onSuccess = { channels ->
                    _state.update {
                        it.copy(
                            phase = Phase.ChannelsReady,
                            channels = channels,
                            isLoading = false,
                            error = null,
                        )
                    }
                    // First time we have channels, kick the EPG load now (if
                    // we'd already done it above from cache, this is a no-op
                    // for fresh-cache cases and a re-trigger for non-cached
                    // cases - loadEpgIfConfigured is idempotent w.r.t. state).
                    if (!hasChannelCache) loadEpgIfConfigured(saved)
                },
                onFailure = { t ->
                    if (hasChannelCache) {
                        // Keep the cached rail visible; surface a soft log but
                        // don't bounce the user to NeedsUrl - they had a
                        // working playlist yesterday, the server is just
                        // unreachable right now.
                        Log.w(TAG, "channel refresh failed; cached rail still visible", t)
                        _state.update { it.copy(isLoading = false) }
                    } else {
                        _state.update {
                            it.copy(
                                phase = Phase.NeedsUrl,
                                isLoading = false,
                                error = "Failed to refresh saved source: ${LogSanitizer.redact(t.message ?: t::class.simpleName ?: "unknown error")}",
                            )
                        }
                    }
                },
            )
        }
    }

    fun onSourceTypeChange(value: SourceType) {
        _state.update { it.copy(sourceType = value, error = null) }
    }
    fun onNameChange(value: String) {
        _state.update { it.copy(name = value, error = null) }
    }
    fun onUrlChange(value: String) {
        _state.update { it.copy(url = value, error = null) }
    }
    fun onLanUrlChange(value: String) {
        _state.update { it.copy(lanUrl = value, error = null) }
    }
    fun onEpgUrlChange(value: String) {
        _state.update { it.copy(epgUrl = value) }
    }
    fun onApiKeyChange(value: String) {
        _state.update { it.copy(apiKey = value, error = null) }
    }
    fun onUsernameChange(value: String) {
        _state.update { it.copy(username = value) }
    }
    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value) }
    }
    /** Bound to "Fetch On Demand from this playlist" in ConfigureSourceScreen /
     *  EditPlaylistScreen. Threaded into SaveRequest.vodEnabled on submit. */
    fun onVodEnabledChange(value: Boolean) {
        _state.update { it.copy(vodEnabled = value) }
    }
    /** Bound to the "Guide History" picker in ConfigureSourceScreen (task
     *  #135). Threaded into SaveRequest.epgRetentionDays on submit. */
    fun onEpgRetentionDaysChange(value: Int) {
        _state.update { it.copy(epgRetentionDays = value.coerceIn(1, 30)) }
    }
    fun onSearchQueryChange(value: String) {
        _state.update { it.copy(searchQuery = value) }
    }
    fun onGroupSelected(group: String) {
        _state.update { it.copy(selectedGroup = group) }
    }

    fun onSortModeChange(mode: SortMode) {
        _state.update { it.copy(sortMode = mode) }
    }

    /** Pre-fill an M3U URL pair and immediately load. Debug-only path used by --es intent extras. */
    fun loadFromUrl(url: String, epgUrl: String? = null) {
        _state.update {
            it.copy(
                sourceType = SourceType.M3uUrl,
                url = url,
                epgUrl = epgUrl.orEmpty(),
                error = null,
            )
        }
        loadPlaylist()
    }

    /** Debug-only Dispatcharr auto-load to bypass keyboard typing on emulators. */
    fun loadFromDispatcharr(url: String, apiKey: String) {
        _state.update {
            it.copy(
                sourceType = SourceType.DispatcharrApiKey,
                url = url,
                apiKey = apiKey,
                error = null,
            )
        }
        loadPlaylist()
    }

    fun loadPlaylist() {
        val s = _state.value
        if (s.url.trim().isEmpty()) {
            _state.update { it.copy(error = "Enter a URL") }
            return
        }
        // Auto-prepend the scheme so users can type "dispatcharr.example.com"
        // instead of "https://dispatcharr.example.com" -- the bare-host case
        // is the overwhelmingly common typing pattern, and 99% of the field
        // population is dictated by what the user copy-pastes from their
        // server admin who almost always omits the scheme. LAN-shaped hosts
        // (192.168 / 10 / 172.16-31 / *.local) get http:// since home
        // servers usually don't terminate TLS; everything else gets https://.
        val url = normalizeSchemedUrl(s.url)
        if (!s.sourceType.isImplemented) {
            _state.update {
                it.copy(error = "${s.sourceType.displayName} support is coming in a later phase.")
            }
            return
        }
        // Dispatcharr takes EITHER an API key OR a username + password.
        // Derive the effective source type from whichever the user actually
        // supplied, so a stale/mis-set auth toggle can't reject a valid
        // credential (the "2 fields need attention" + "had to use user/pass"
        // bug). Whichever is present wins; API key takes precedence if both.
        val effectiveSourceType: SourceType = when (s.sourceType) {
            SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass -> {
                val hasApiKey = s.apiKey.isNotBlank()
                val hasUserPass = s.username.isNotBlank() && s.password.isNotBlank()
                when {
                    hasApiKey -> SourceType.DispatcharrApiKey
                    hasUserPass -> SourceType.DispatcharrUserPass
                    else -> {
                        _state.update { it.copy(error = "Enter an API key, or a username and password.") }
                        return
                    }
                }
            }
            else -> s.sourceType
        }
        if (s.sourceType == SourceType.XtreamCodes &&
            (s.username.isBlank() || s.password.isBlank())) {
            _state.update { it.copy(error = "Username and password are required") }
            return
        }
        // EPG URL gets the same scheme-normalization as the server URL.
        val epgUrl = s.epgUrl.trim().takeIf { it.isNotEmpty() }?.let { normalizeSchemedUrl(it) }
        // LAN URL too -- typing "192.168.1.50:9191" should land as
        // "http://192.168.1.50:9191" automatically.
        val lanUrl = s.lanUrl.trim().takeIf { it.isNotEmpty() }?.let { normalizeSchemedUrl(it) }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val request = PlaylistRepository.SaveRequest(
                sourceType = effectiveSourceType,
                name = s.name.trim().ifBlank { null },
                url = url,
                lanUrl = lanUrl,
                epgUrl = epgUrl,
                apiKey = s.apiKey.trim().ifBlank { null },
                username = s.username.trim().ifBlank { null },
                password = s.password.ifBlank { null },
                vodEnabled = s.vodEnabled,
                epgRetentionDays = s.epgRetentionDays,
            )
            repository.loadAndPersist(request, existingId = s.draftPlaylistId).fold(
                onSuccess = { (entity, channels) ->
                    _state.update {
                        it.copy(
                            phase = Phase.ChannelsReady,
                            playlist = entity,
                            // Remember THIS draft's row so a re-test edits it
                            // instead of duplicating; never the active playlist.
                            draftPlaylistId = entity.id,
                            channels = channels,
                            isLoading = false,
                            error = if (channels.isEmpty()) "No channels found." else null,
                        )
                    }
                    loadEpgIfConfigured(entity)
                    // One-shot: advance the add flow even when phase was already
                    // ChannelsReady (second-playlist add). Skip on an empty result
                    // so the "No channels found." error stays visible.
                    if (channels.isNotEmpty()) _sourceConfigured.tryEmit(Unit)
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            channels = emptyList(),
                            error = "Failed to load: ${LogSanitizer.redact(t.message ?: t::class.simpleName ?: "unknown error")}",
                        )
                    }
                },
            )
        }
    }

    private fun loadEpgIfConfigured(playlist: PlaylistEntity, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            doLoadEpg(playlist, forceRefresh)
            mergeEpgHistory(playlist)
        }
    }

    /**
     * Catch-up (task #135): deferred second-pass load of ALREADY-AIRED guide
     * rows, kept out of [doLoadEpg]'s critical path so cold-launch paint keeps
     * its cheap now-1h..+window read. The EPG cache retains up to the
     * playlist's epgRetentionDays of ended programmes (see
     * PlaylistRepository.saveEpgToCache); this merges them into
     * [PlaylistUiState.epgByChannel] once the live grid is up, then widens
     * [PlaylistUiState.epgHistoryHours] so the guide grid extends its
     * back-scroll window to match. Dedup by (channel, start) keeps the edge
     * where the fresh feed still overlaps history from doubling cells.
     */
    private suspend fun mergeEpgHistory(playlist: PlaylistEntity) {
        val retentionDays = playlist.epgRetentionDays.coerceIn(1, 30)
        val now = System.currentTimeMillis()
        val fromMillis = now - retentionDays * 24L * 60L * 60L * 1000L
        val toMillis = now - 60L * 60L * 1000L
        val historyRaw = runCatching {
            repository.loadCachedEpg(playlist.id, fromMillis, toMillis)
        }.getOrDefault(emptyList())
        val historyHours = retentionDays * 24
        if (historyRaw.isEmpty()) {
            // Nothing retained yet (fresh install / first refresh); still widen
            // the grid so history starts accumulating visibly from day one.
            _state.update { it.copy(epgHistoryHours = historyHours) }
            return
        }
        val history = bridgeChannelIds(historyRaw, _state.value.channels)
        _state.update { st ->
            val merged = HashMap(st.epgByChannel)
            for ((channelId, rows) in groupByChannel(history)) {
                val existing = merged[channelId]
                merged[channelId] = if (existing.isNullOrEmpty()) {
                    rows
                } else {
                    val seen = existing.asSequence().map { it.startMillis }.toHashSet()
                    val fresh = rows.filter { it.startMillis !in seen }
                    if (fresh.isEmpty()) existing
                    else (fresh + existing).sortedBy { it.startMillis }
                }
            }
            st.copy(epgByChannel = merged, epgHistoryHours = historyHours)
        }
        Log.i(TAG, "mergeEpgHistory: merged ${history.size} past programmes (${retentionDays}d window)")
    }

    /**
     * EPG load body. Returns Result.success(programme count) / Result.failure
     * on a network outcome, or null on the early exits (no EPG configured,
     * cache still fresh) so [refreshEpg] can surface per-action feedback.
     * Every other caller goes through the fire-and-forget
     * [loadEpgIfConfigured] wrapper and ignores the return.
     */
    private suspend fun doLoadEpg(playlist: PlaylistEntity, forceRefresh: Boolean): Result<Int>? {
        val sourceType = SourceType.entries.firstOrNull { it.name == playlist.sourceType }
            ?: SourceType.M3uUrl
        // M3uUrl only loads EPG when user provided an XMLTV URL. Dispatcharr derives one
        // from the base URL automatically.
        val willHaveEpg = when (sourceType) {
            SourceType.M3uUrl -> !playlist.epgUrl.isNullOrBlank()
            SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass -> true
            SourceType.XtreamCodes -> !playlist.username.isNullOrBlank()
        }
        if (!willHaveEpg) {
            Log.i(TAG, "loadEpgIfConfigured: no EPG for sourceType=${playlist.sourceType}")
            return null
        }
        // 1. Paint the disk cache immediately (iOS GuideStore parity) so the
        // guide + now-playing are never blank on relaunch while the network
        // fetch runs. Cache is keyed per source (playlist id).
        // iOS parity (EPGGuideView.swift lines 1395-1414): rewrite each
        // programme's raw `channel="..."` attribute to the canonical key
        // its M3UChannel will look up under, so Dispatcharr `/output/epg`
        // (channel-number keyed) and Dummy EPG feeds (UUID keyed) populate
        // the rail even when `tvgID` is blank. See ChannelEpgKey.kt.
        val channelsForBridge = _state.value.channels
        // iOS GuideStore.loadFromCache predicate (P1 #5): only load
        // programmes whose airing overlaps the user's selected guide
        // window (now-1h .. now+epgWindowHours). On a 7-day, 58K-row
        // cache that drops ~85% of rows before they hit the bridge /
        // dedup / group pipeline, cutting cold-launch CPU + GC by a
        // similar fraction. epgWindowHours = 0 means "All available";
        // fall back to the unwindowed read so the guide still renders
        // the full 7 days for users who picked that option.
        val windowHours = runCatching { appPreferences.epgWindowHours.first() }
            .getOrDefault(24)
        val now = System.currentTimeMillis()
        val cachedRaw = if (windowHours <= 0) {
            runCatching { repository.loadCachedEpg(playlist.id) }
                .getOrDefault(emptyList())
        } else {
            val fromMillis = now - 60L * 60L * 1000L
            val toMillis = now + windowHours.toLong() * 60L * 60L * 1000L
            runCatching {
                repository.loadCachedEpg(playlist.id, fromMillis, toMillis)
            }.getOrDefault(emptyList())
        }
        val cached = bridgeChannelIds(cachedRaw, channelsForBridge)
        val hasCache = cached.isNotEmpty()
        if (hasCache) {
            Log.i(TAG, "loadEpgIfConfigured: painted ${cached.size} cached programmes")
            val groupedCached = withChannelNamePlaceholders(
                groupByChannel(cached), _state.value.channels,
            )
            _state.update { it.copy(epgByChannel = groupedCached, isEpgLoading = false) }
        }
        // 2. Freshness: skip the network entirely when the cache is recent,
        // unless the caller forced a refresh (e.g. Refresh Playlist).
        if (!forceRefresh) {
            val newest = runCatching { repository.newestEpgFetch(playlist.id) }.getOrNull()
            val fresh = newest != null &&
                (System.currentTimeMillis() - newest) < EPG_CACHE_TTL_MS
            if (fresh) {
                Log.i(TAG, "loadEpgIfConfigured: cache fresh, skipping network")
                _state.update { it.copy(isEpgLoading = false) }
                return null
            }
        }
        // 3. Stale / forced / first-ever launch -> network. Only show the
        // spinner when there is nothing cached to display yet.
        Log.i(TAG, "loadEpgIfConfigured: fetching EPG for ${playlist.sourceType} (force=$forceRefresh, hadCache=$hasCache)")
        if (!hasCache) _state.update { it.copy(isEpgLoading = true) }
        // iOS GuideStore audit P3 #13: pass the candidate-key set so the
        // XMLTV parser can drop any programme whose `channel="..."`
        // attribute will never match a M3UChannel before allocating an
        // EPGProgramme. For a 7000-channel guide that the user only has
        // 700 active channels for, this trims ~90% of in-parse allocations
        // -- the same speedup the windowed cache load (P1 #5) gives the
        // downstream pipeline, but applied to fresh network fetches too.
        val knownKeys = buildChannelEpgKeyBridge(_state.value.channels).keys
        return repository.loadEpg(playlist, knownKeys).fold(
            onSuccess = { rawProgrammes ->
                // Channels may have arrived between the cache-paint above
                // and the network fetch; re-read so we bridge against the
                // freshest channel set.
                val programmes = bridgeChannelIds(rawProgrammes, _state.value.channels)
                val grouped = withChannelNamePlaceholders(
                    groupByChannel(programmes), _state.value.channels,
                )
                // Cheap channel count off the already-bucketed map instead of
                // `programmes.map { it.channelId }.toSet().size` which used to
                // allocate a fresh List + a Set over 60K rows on every cold
                // launch just to log the count -- the actual allocation showed
                // up in flame graphs as ~250ms of main-thread time.
                Log.i(TAG, "EPG loaded: ${programmes.size} programmes across ${grouped.size} channels")
                _state.update { it.copy(epgByChannel = grouped, isEpgLoading = false) }
                // Persist the fresh guide so the next launch is instant.
                runCatching { repository.saveEpgToCache(playlist.id, programmes) }
                    .onFailure { Log.w(TAG, "saveEpgToCache failed", it) }
                // iOS parity: fire-and-forget category enrichment for
                // Dispatcharr's bulk grid (which strips the category).
                // Categories tint in progressively as detail responses
                // land; Live TV cards + Guide cells stay interactive
                // throughout. Mirrors EPGGuideView.swift line 848
                // `Task { await self.enrichDispatcharrCategories(...) }`.
                // viewModelScope (not a child of this suspend call) so the
                // enrichment never blocks refreshEpg's completion signal.
                viewModelScope.launch {
                    val enriched = runCatching {
                        repository.enrichNowPlayingCategories(playlist, programmes)
                    }.onFailure { Log.w(TAG, "category enrichment failed", it) }
                        .getOrDefault(programmes)
                    // Only push an update when enrichment actually changed
                    // something; short-circuits the recompose when the source
                    // already had categories baked in (XMLTV path).
                    if (enriched !== programmes) {
                        val groupedEnriched = withChannelNamePlaceholders(
                            groupByChannel(enriched), _state.value.channels,
                        )
                        // MERGE into the current map, never wholesale-replace:
                        // this coroutine races mergeEpgHistory, and a replace
                        // that lands second silently wiped every 7-day history
                        // row (and with epgWindowHours=0 shrank the guide's
                        // content width, clamping the shared scroll - a second
                        // timeline-jump class). Enriched rows win their own
                        // (channel, start) slots; everything else survives.
                        _state.update { st ->
                            val merged = HashMap(st.epgByChannel)
                            for ((channelId, rows) in groupedEnriched) {
                                val existing = merged[channelId]
                                merged[channelId] = if (existing.isNullOrEmpty()) {
                                    rows
                                } else {
                                    val enrichedStarts =
                                        rows.asSequence().map { it.startMillis }.toHashSet()
                                    val keep = existing.filter { it.startMillis !in enrichedStarts }
                                    if (keep.isEmpty()) rows
                                    else (keep + rows).sortedBy { it.startMillis }
                                }
                            }
                            st.copy(epgByChannel = merged)
                        }
                        // Keep the cache enriched too so tints survive a relaunch.
                        runCatching { repository.saveEpgToCache(playlist.id, enriched) }
                        Log.i(TAG, "EPG enriched: categories backfilled for now-playing programmes")
                    }
                }
                Result.success(programmes.size)
            },
            onFailure = { t ->
                Log.w(TAG, "EPG load failed", t)
                // Keep whatever cache we already painted on screen.
                _state.update { it.copy(isEpgLoading = false) }
                Result.failure(t)
            },
        )
    }

    /** Group + time-sort + dedup programmes into the per-channel map the UI consumes. */
    // Grouping hundreds of thousands of programmes by channel + sorting each
    // bucket is O(n log n); run it off the Main dispatcher so a large EPG
    // doesn't stall the UI between fetch and paint.
    //
    // iOS GuideStore parity (EPGGuideView.swift `mergeProgramInto`): collapse
    // duplicate programmes within each channel bucket. Two programmes merge
    // when EITHER (a) they share the same non-blank title and start within 60s
    // of each other -- the Dispatcharr bulk-vs-detail enrichment case where
    // the same airing is fetched twice with slightly different times, OR (b)
    // they overlap by more than 80% of their combined span -- the
    // XMLTV-layered-on-Dispatcharr case where two providers describe the same
    // airing with different boundaries. Merge keeps the LONGER description,
    // the existing non-blank category, the existing-else-new dispatcharrProgramId,
    // and widens the time window to the union so the cell still covers both.
    private suspend fun groupByChannel(programmes: List<EPGProgramme>): Map<String, List<EPGProgramme>> =
        withContext(Dispatchers.Default) {
            programmes.groupBy { it.channelId }
                .mapValues { (_, list) -> dedupSameAiring(list.sortedBy { it.startMillis }) }
        }

    /**
     * Dispatcharr (and tvOS) never show a blank guide row: a channel with no
     * EPG still renders its name as the current programme (Dispatcharr's "dummy
     * EPG"). Mirror that by injecting one channel-name placeholder, keyed by the
     * channel's guideMatchKey, for every channel left with no programmes after
     * matching. Real programmes always win; only empty buckets are filled.
     */
    private fun withChannelNamePlaceholders(
        grouped: Map<String, List<EPGProgramme>>,
        channels: List<M3UChannel>,
    ): Map<String, List<EPGProgramme>> {
        if (channels.isEmpty()) return grouped
        val now = System.currentTimeMillis()
        val start = now - 12 * 3_600_000L
        val end = now + 48 * 3_600_000L
        val out = HashMap(grouped)
        for (ch in channels) {
            val key = ch.guideMatchKey
            if (out[key].isNullOrEmpty()) {
                out[key] = listOf(
                    EPGProgramme(
                        channelId = key,
                        title = ch.name,
                        description = "",
                        startMillis = start,
                        endMillis = end,
                        category = "",
                        isPlaceholder = true,
                    ),
                )
            }
        }
        return out
    }

    /**
     * iOS GuideStore `mergeProgramInto` mirror. Input is a per-channel
     * start-time-sorted list; walks the list once, collapsing a programme
     * into the previously kept one whenever [isSameAiring] reports the
     * two describe the same airing. O(n) per bucket and allocation-light:
     * a single ArrayList is built only when at least one merge happens.
     */
    private fun dedupSameAiring(sorted: List<EPGProgramme>): List<EPGProgramme> {
        if (sorted.size <= 1) return sorted
        // Cheap two-pass: detect first whether ANY pair merges so the
        // common (no-dup) case allocates nothing. Most XMLTV feeds and
        // Xtream xmltv.php fall into this fast path.
        val needsMerge = run {
            var i = 0
            while (i + 1 < sorted.size) {
                if (isSameAiring(sorted[i], sorted[i + 1])) return@run true
                i++
            }
            false
        }
        if (!needsMerge) return sorted
        val out = ArrayList<EPGProgramme>(sorted.size)
        for (next in sorted) {
            val prev = out.lastOrNull()
            if (prev != null && isSameAiring(prev, next)) {
                out[out.lastIndex] = mergeProgrammes(prev, next)
            } else {
                out.add(next)
            }
        }
        return out
    }

    /** Same-airing predicate: 60s start-delta with same non-blank title,
     *  OR > 80% time overlap of the union span. */
    private fun isSameAiring(a: EPGProgramme, b: EPGProgramme): Boolean {
        val titleEq = a.title.isNotBlank() &&
            a.title.trim().equals(b.title.trim(), ignoreCase = false)
        val startDelta = kotlin.math.abs(a.startMillis - b.startMillis)
        if (titleEq && startDelta <= 60_000L) return true
        val overlap = kotlin.math.max(
            0L,
            kotlin.math.min(a.endMillis, b.endMillis) -
                kotlin.math.max(a.startMillis, b.startMillis),
        )
        val span = kotlin.math.max(a.endMillis, b.endMillis) -
            kotlin.math.min(a.startMillis, b.startMillis)
        if (span <= 0L) return false
        return overlap.toDouble() / span.toDouble() > 0.8
    }

    /** Merge per iOS: keep the longer description, the existing non-blank
     *  category, the existing-else-new dispatcharrProgramId, widen the
     *  time window to the union, and keep the first non-blank title. */
    private fun mergeProgrammes(a: EPGProgramme, b: EPGProgramme): EPGProgramme {
        val title = if (a.title.isNotBlank()) a.title else b.title
        val description = if (a.description.length >= b.description.length) a.description else b.description
        val category = a.category.takeIf { it.isNotBlank() } ?: b.category
        val pid = a.dispatcharrProgramId ?: b.dispatcharrProgramId
        val start = kotlin.math.min(a.startMillis, b.startMillis)
        val end = kotlin.math.max(a.endMillis, b.endMillis)
        return a.copy(
            title = title,
            description = description,
            category = category,
            startMillis = start,
            endMillis = end,
            dispatcharrProgramId = pid,
        )
    }

    /**
     * Catch-up (task #133): resolve a past programme on a catch-up-capable
     * channel to a playable timeshift URL, then hand it to [onResult] on the
     * main thread. Resolution is async (the Dispatcharr path may fetch the
     * user's XC credentials and pre-resolve the session redirect; the XC path
     * may fetch the panel timezone) -- all memoized after the first call.
     */
    fun playCatchup(
        channel: com.aeriotv.android.core.data.M3UChannel,
        programme: com.aeriotv.android.core.data.EPGProgramme,
        onResult: (Result<com.aeriotv.android.core.playback.CatchupPlaybackResolver.Playback>) -> Unit,
    ) {
        viewModelScope.launch {
            val active = repository.activePlaylist()
                ?: return@launch onResult(
                    Result.failure(IllegalStateException("No active playlist")),
                )
            onResult(
                catchupResolver.resolve(
                    playlist = active,
                    channel = channel,
                    startMillis = programme.startMillis,
                    endMillis = programme.endMillis,
                ),
            )
        }
    }

    /**
     * Task #149: mint a fresh native catch-up session for a seek re-tune
     * (programmeStart + floored offset). Suspends; returns the new
     * absolute playback URL or null (the player keeps its current
     * window). Only meaningful when the playing URL is a native
     * /proxy/catchup/ session URL.
     */
    suspend fun remintCatchupSession(
        channelUuid: String,
        currentPlaybackUrl: String,
        absStartMillis: Long,
    ): String? {
        val active = repository.activePlaylist() ?: return null
        return catchupResolver.remintNative(
            playlist = active,
            channelUuid = channelUuid,
            currentPlaybackUrl = currentPlaybackUrl,
            absStartMillis = absStartMillis,
        )
    }

    /** Task #149: best-effort revoke of a native catch-up session when
     *  the player closes (frees the server's provider slot early). */
    fun revokeCatchupSession(playbackUrl: String) {
        viewModelScope.launch {
            val active = repository.activePlaylist() ?: return@launch
            catchupResolver.revokeNative(active, playbackUrl)
        }
    }

    /**
     * Re-fetch the active playlist (channels) and follow with EPG. Used by
     * Playlist Detail's "Refresh Playlist" action.
     */
    fun refreshPlaylist() {
        viewModelScope.launch {
            val active = repository.activePlaylist() ?: return@launch
            Log.i(TAG, "refreshPlaylist: re-loading ${active.name}")
            // isLoading stays: the MainScaffold sync pill and the source
            // screens consume it. playlistRefreshStatus is the per-row signal.
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    playlistRefreshStatus = ActionStatus.Running,
                )
            }
            repository.refresh(active).fold(
                onSuccess = { channels ->
                    // Re-read the persisted row so the detail card's Channels /
                    // Last Connected values go live (the repo just stamped them).
                    val updated = repository.activePlaylist()
                    _state.update {
                        it.copy(
                            channels = channels,
                            isLoading = false,
                            playlist = updated ?: it.playlist,
                            playlistRefreshStatus = ActionStatus.Success(
                                "${channels.size} channels loaded",
                            ),
                            error = if (channels.isEmpty()) "No channels found." else null,
                        )
                    }
                    loadEpgIfConfigured(active, forceRefresh = true)
                },
                onFailure = { t ->
                    Log.w(TAG, "refreshPlaylist failed", t)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            playlistRefreshStatus = ActionStatus.Failure(
                                "Refresh failed: ${LogSanitizer.redact(t.message ?: t::class.simpleName ?: "unknown error")}",
                            ),
                            error = "Refresh failed: ${LogSanitizer.redact(t.message ?: t::class.simpleName ?: "unknown error")}",
                        )
                    }
                },
            )
            refreshActiveRoute()
        }
    }

    /**
     * iOS GuideStore audit P3 #12 minimum-viable port: when the user scrolls
     * the Guide horizontally to within [PREFETCH_TRIGGER_DISTANCE_MS] of the
     * latest cached programme's end time, fire a single forced EPG refresh
     * on the active playlist so the grid stays populated as the user moves
     * forward in time.
     *
     * Why the iOS state machine isn't ported verbatim: iOS prefetches
     * per-channel via a windowed `getUpcomingFor(channelUUID, after, before)`
     * call -- the bulk grid endpoint returns -1h..+24h on every request,
     * regardless of when. Android's `loadEpg` already fetches the entire
     * source-side window in one shot (Dispatcharr grid OR full XMLTV) and
     * the post-P1 #5 windowed cache load is what controls memory pressure.
     * The iOS Rolling Prefetch's circuit-breaker / per-channel serial chain
     * therefore has no direct analog: the unit of fetch is the WHOLE
     * playlist, not the per-channel cell. What remains is the trigger -- the
     * scroll edge that says "user is about to run out of guide" -- which we
     * port here.
     *
     * Single-flight is delegated to the existing inFlightLoads coalescing
     * layer (P1 #6) inside PlaylistRepository.loadEpg: two scroll events
     * within the same fetch window share one round-trip. Debounce is local:
     * we ignore calls within [PREFETCH_COOLDOWN_MS] of the previous trigger
     * so a slow horizontal scroll doesn't fire the worker N times.
     */
    fun maybePrefetchUpcoming(visibleWindowEndMs: Long) {
        val state = _state.value
        val cachedEdge = state.epgByChannel.values
            .asSequence()
            .flatten()
            .maxOfOrNull { it.endMillis }
            ?: return
        if (visibleWindowEndMs < cachedEdge - PREFETCH_TRIGGER_DISTANCE_MS) return
        val now = System.currentTimeMillis()
        if (now - lastPrefetchAt < PREFETCH_COOLDOWN_MS) return
        lastPrefetchAt = now
        viewModelScope.launch {
            val active = repository.activePlaylist() ?: return@launch
            Log.i(
                TAG,
                "maybePrefetchUpcoming: scroll reached cached edge, forcing EPG refresh",
            )
            loadEpgIfConfigured(active, forceRefresh = true)
        }
    }

    private var lastPrefetchAt: Long = 0L

    /**
     * Re-fetch the EPG without re-fetching the channel list. iOS GuideStore
     * audit P2 #11: hard reset semantics. Purges the per-playlist EPG
     * cache row set BEFORE forcing a fresh network fetch, so a corrupt /
     * partial cached batch can't bleed back onto the rail if the user is
     * pressing this button precisely because the cache is misbehaving.
     * Cleared in-memory state stays empty until the fresh fetch lands; on
     * fetch failure the user sees a blank guide briefly (acceptable cost
     * for the hard-reset guarantee) until the next bootstrap repaints.
     */
    fun refreshEpg() {
        viewModelScope.launch {
            val active = repository.activePlaylist() ?: return@launch
            _state.update { it.copy(epgRefreshStatus = ActionStatus.Running) }
            runCatching { repository.purgeEpgCache(active.id) }
                .onFailure { Log.w(TAG, "purgeEpgCache failed", it) }
            _state.update { it.copy(epgByChannel = emptyMap()) }
            val outcome = doLoadEpg(active, forceRefresh = true)
            // The purge above dropped retained history too; re-merge whatever
            // survives (nothing on a true clean slate, task #135 semantics:
            // "Refresh EPG Data" is the user's reset-everything hammer).
            mergeEpgHistory(active)
            // Re-read the row so lastEpgRefreshedAt (stamped in Room by the
            // repo) reaches the detail card's "Last refreshed" footer.
            val updated = repository.activePlaylist()
            _state.update {
                it.copy(
                    playlist = updated ?: it.playlist,
                    epgRefreshStatus = when {
                        outcome == null -> ActionStatus.Failure("No EPG source configured")
                        outcome.isSuccess -> ActionStatus.Success(
                            "EPG updated (${outcome.getOrThrow()} programmes)",
                        )
                        else -> ActionStatus.Failure(
                            "EPG refresh failed: ${LogSanitizer.redact(outcome.exceptionOrNull()?.message ?: "unknown error")}",
                        )
                    },
                )
            }
            refreshActiveRoute()
        }
    }

    /**
     * "Refresh Everything" nuclear reset (iOS a039ba71a parity). Strictly
     * more thorough than Refresh Playlist + Refresh EPG Data: it purges the
     * per-playlist EPG cache AND drops the in-memory guide map AND re-fetches
     * channels (so newly-added server channels appear; refresh() also rewrites
     * the channel-snapshot cache) AND force-reloads the guide AND tells
     * OnDemandViewModel to run its existing VOD nuclear reset for the CURRENT
     * (unchanged) active id via [vodResetBus]. Confirmation-gated in the UI,
     * and also auto-invoked by [switchToPlaylist] so every playlist switch
     * gets the same from-scratch reload (maintainer requirement).
     */
    fun refreshEverything() {
        viewModelScope.launch {
            val active = repository.activePlaylist() ?: return@launch
            Log.i(TAG, "refreshEverything: nuking all caches for ${active.name}")
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    refreshAllStatus = ActionStatus.Running,
                )
            }
            // 1. Purge guide cache (disk) + drop in-memory map, like refreshEpg.
            runCatching { repository.purgeEpgCache(active.id) }
                .onFailure { Log.w(TAG, "refreshEverything: purgeEpgCache failed", it) }
            _state.update { it.copy(epgByChannel = emptyMap()) }
            // 2. Signal On Demand to run its nuclear reset (id unchanged, so the
            //    observeActiveId collector won't fire; the bus is the bridge).
            vodResetBus.requestReset()
            // 3. Re-fetch channels (refresh() rewrites the snapshot cache too).
            repository.refresh(active).fold(
                onSuccess = { channels ->
                    val updated = repository.activePlaylist()
                    _state.update {
                        it.copy(
                            channels = channels,
                            isLoading = false,
                            playlist = updated ?: it.playlist,
                            refreshAllStatus = ActionStatus.Success(
                                "${channels.size} channels reloaded",
                            ),
                            error = if (channels.isEmpty()) "No channels found." else null,
                        )
                    }
                    // 4. Force-reload the guide from scratch.
                    loadEpgIfConfigured(active, forceRefresh = true)
                },
                onFailure = { t ->
                    Log.w(TAG, "refreshEverything failed", t)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            refreshAllStatus = ActionStatus.Failure(
                                "Refresh failed: ${LogSanitizer.redact(t.message ?: t::class.simpleName ?: "unknown error")}",
                            ),
                            error = "Refresh failed: ${LogSanitizer.redact(t.message ?: t::class.simpleName ?: "unknown error")}",
                        )
                    }
                },
            )
            refreshActiveRoute()
        }
    }

    /**
     * iOS Issue #24: when the app returns to the foreground after a while,
     * refresh the guide if it has gone stale (cached fetch older than
     * [maxAgeMillis], default 30 min) AND nothing is already loading. EPG only
     * -- it does NOT re-pull channels or VOD. Gated on staleness so ordinary
     * app-switching never refetches. A source with no cached guide yet is left
     * to the normal cold-launch load path (no double-fetch).
     */
    fun refreshEpgIfStale(maxAgeMillis: Long = 30L * 60L * 1000L) {
        viewModelScope.launch {
            val s = _state.value
            if (s.isLoading || s.isEpgLoading) return@launch
            val active = repository.activePlaylist() ?: return@launch
            val newest = repository.newestEpgFetch(active.id) ?: return@launch
            val ageMs = System.currentTimeMillis() - newest
            if (ageMs <= maxAgeMillis) return@launch
            Log.i(TAG, "refreshEpgIfStale: guide is ${ageMs / 60000}min old, refreshing on foreground")
            loadEpgIfConfigured(active, forceRefresh = true)
        }
    }

    /**
     * Apply user edits to the active playlist. Reuses [PlaylistRepository.loadAndPersist]
     * with `existingId` so the row's UUID stays stable. Mirrors iOS Edit Playlist
     * Save action — connection details + auth credentials + EPG URL can change
     * but the source type does not (iOS gates that too via a separate flow).
     */
    /**
     * Load the Dispatcharr channel profiles for the active playlist so the
     * Edit Playlist screen can render the Channel Profile picker. No-op (and
     * clears any stale list) for non-Dispatcharr sources. Failures leave the
     * list empty so the picker just shows "All Channels".
     */
    fun loadDispatcharrProfiles() {
        viewModelScope.launch {
            val active = repository.activePlaylist() ?: return@launch
            val sourceType = SourceType.entries.firstOrNull { it.name == active.sourceType }
                ?: SourceType.M3uUrl
            val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
                sourceType == SourceType.DispatcharrUserPass
            if (!isDispatcharr) {
                _state.update { it.copy(availableProfiles = emptyList(), profilesLoading = false) }
                return@launch
            }
            _state.update { it.copy(profilesLoading = true) }
            val profiles = runCatching { repository.listChannelProfiles(active) }
                .onFailure { Log.w(TAG, "loadDispatcharrProfiles failed", it) }
                .getOrDefault(emptyList())
            _state.update { it.copy(availableProfiles = profiles, profilesLoading = false) }
        }
    }

    fun saveEdits(
        name: String,
        url: String,
        lanUrl: String?,
        epgUrl: String?,
        apiKey: String?,
        username: String?,
        password: String?,
        dispatcharrProfileId: Int?,
        vodEnabled: Boolean = true,
        epgRetentionDays: Int = 7,
    ) {
        viewModelScope.launch {
            val active = repository.activePlaylist() ?: return@launch
            val sourceType = SourceType.entries.firstOrNull { it.name == active.sourceType }
                ?: SourceType.M3uUrl
            _state.update { it.copy(isLoading = true, error = null) }
            val request = PlaylistRepository.SaveRequest(
                sourceType = sourceType,
                name = name.ifBlank { null },
                // Scheme-normalize all three URLs so the user can type
                // bare hostnames in Edit Playlist too. Same rules as
                // loadPlaylist() above.
                url = normalizeSchemedUrl(url),
                lanUrl = lanUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeSchemedUrl(it) },
                epgUrl = epgUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeSchemedUrl(it) },
                apiKey = apiKey?.trim()?.ifBlank { null },
                username = username?.trim()?.ifBlank { null },
                password = password?.ifBlank { null },
                dispatcharrProfileId = dispatcharrProfileId,
                vodEnabled = vodEnabled,
                epgRetentionDays = epgRetentionDays.coerceIn(1, 30),
            )
            repository.loadAndPersist(request, existingId = active.id).fold(
                onSuccess = { (entity, channels) ->
                    _state.update {
                        it.copy(
                            playlist = entity,
                            channels = channels,
                            isLoading = false,
                            error = if (channels.isEmpty()) "No channels found." else null,
                        )
                    }
                    loadEpgIfConfigured(entity)
                },
                onFailure = { t ->
                    Log.w(TAG, "saveEdits failed", t)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = "Save failed: ${LogSanitizer.redact(t.message ?: t::class.simpleName ?: "unknown error")}",
                        )
                    }
                },
            )
        }
    }

    /**
     * Probe the playlist URL. v1 re-runs the channel fetch as a connectivity
     * test (same code path the bootstrap uses), so success means the source
     * still responds with parseable content. Outcome lands in
     * [UiState.testStatus] for the Playlist Detail row.
     */
    fun testConnection() {
        viewModelScope.launch {
            val active = repository.activePlaylist() ?: return@launch
            Log.i(TAG, "testConnection: probing ${active.urlString}")
            _state.update { it.copy(testStatus = ActionStatus.Running) }
            repository.refresh(active).fold(
                onSuccess = { channels ->
                    Log.i(TAG, "testConnection: ok (${channels.size} channels)")
                    _state.update {
                        it.copy(
                            testStatus = ActionStatus.Success(
                                "Connected OK (${channels.size} channels)",
                            ),
                        )
                    }
                },
                onFailure = { t ->
                    Log.w(TAG, "testConnection failed", t)
                    _state.update {
                        it.copy(
                            testStatus = ActionStatus.Failure(
                                "Connection failed: ${LogSanitizer.redact(t.message ?: t::class.simpleName ?: "unknown error")}",
                            ),
                        )
                    }
                },
            )
            // The probe just resolved a URL; keep the Connection row honest.
            refreshActiveRoute()
        }
    }

    /**
     * Re-resolve which URL [PlaylistRepository.effectiveBaseUrl] would use
     * right now (LAN when the reachability probe says the server answers
     * at the LAN URL, WAN
     * otherwise) so Playlist Detail's Connection row reflects reality. Called
     * on screen entry / ON_RESUME and after each detail action; there is no
     * app-wide network callback, matching the NetworkSettingsScreen idiom.
     */
    fun refreshActiveRoute() {
        viewModelScope.launch {
            val pl = _state.value.playlist ?: return@launch
            val url = repository.effectiveBaseUrl(pl)
            val isLan = !pl.lanUrlString.isNullOrBlank() && url == pl.lanUrlString
            _state.update { it.copy(activeRoute = ActiveRoute(isLan = isLan, url = url)) }
        }
    }

    /** Manual "Refresh LAN Detection" action on Playlist Detail: re-probe
     *  which URL answers and report the outcome inline. The same probe
     *  already runs automatically on entry and ON_RESUME; this row exists
     *  for parity with iOS and for users who just changed networks. */
    fun refreshLanDetection() {
        viewModelScope.launch {
            val pl = _state.value.playlist ?: return@launch
            _state.update { it.copy(lanRefreshStatus = ActionStatus.Running) }
            val url = repository.effectiveBaseUrl(pl)
            val isLan = !pl.lanUrlString.isNullOrBlank() && url == pl.lanUrlString
            _state.update {
                it.copy(
                    activeRoute = ActiveRoute(isLan = isLan, url = url),
                    lanRefreshStatus = ActionStatus.Success(
                        if (isLan) "Local server reachable, using local URL" else "Using remote URL",
                    ),
                )
            }
        }
    }

    /** Reset Playlist Detail's per-action feedback. Called when the screen
     *  leaves composition so stale results don't greet the next visit. */
    fun clearDetailActionStatuses() {
        _state.update {
            it.copy(
                testStatus = ActionStatus.Idle,
                playlistRefreshStatus = ActionStatus.Idle,
                epgRefreshStatus = ActionStatus.Idle,
                refreshAllStatus = ActionStatus.Idle,
                lanRefreshStatus = ActionStatus.Idle,
            )
        }
    }

    /**
     * Observe the full set of saved playlists for the multi-playlist
     * switcher in Settings.
     */
    val allPlaylists: Flow<List<PlaylistEntity>> = repository.observeAll()

    /** Make [playlistId] active and load its channels. Mirrors the bootstrap
     * load-and-render flow, but skipping the JWT exchange the first-load does
     * for User+Pass since the apiKey is already cached on the row. */
    fun switchToPlaylist(playlistId: String) {
        viewModelScope.launch {
            // GH #22: stop whatever the OLD source is still playing (phone
            // mini-player / background audio keeps rolling across a switch;
            // TV already stops on leave). Besides being the right product
            // behavior -- the old source is gone -- this clears the holder's
            // currentChannelId latch so the next tune of a same-id channel
            // (same Dispatcharr server on both playlists) always re-primes
            // instead of being skipped as "already playing".
            runCatching { miniPlayerSession.dismiss() }
            runCatching { exoWindowState.hide() }
            runCatching { exoHolder.stop() }
            _state.update { it.copy(isLoading = true, error = null) }
            repository.switchActive(playlistId).fold(
                onSuccess = { (entity, channels) ->
                    _state.update {
                        it.copy(
                            phase = Phase.ChannelsReady,
                            playlist = entity,
                            channels = channels,
                            isLoading = false,
                            error = if (channels.isEmpty()) "No channels found." else null,
                        )
                    }
                    // Maintainer requirement: every playlist switch auto-runs the
                    // full "Refresh Everything" nuclear reset on the now-active
                    // playlist. refreshEverything() purges + force-reloads the
                    // guide and signals the VOD bus, so a switch never paints a
                    // stale cached guide / VOD from a fresh-cache short-circuit.
                    // It operates on repository.activePlaylist(), which is now
                    // `entity` after switchActive() above, so no arg is needed.
                    // (loadEpgIfConfigured is folded into refreshEverything's
                    // forceRefresh guide reload, so we don't call it separately.)
                    refreshEverything()
                },
                onFailure = { t ->
                    Log.w(TAG, "switchToPlaylist failed", t)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = "Switch failed: ${LogSanitizer.redact(t.message ?: t::class.simpleName ?: "unknown error")}",
                        )
                    }
                },
            )
        }
    }

    /**
     * Re-resolve the active playlist from the database and load it. Used by
     * the Welcome screen after a Drive Sync pull lands new rows -- without
     * this, the UI is still parked on Phase.NeedsUrl from the initial cold
     * launch and the LaunchedEffect that watches `state.phase ==
     * ChannelsReady` for the auto-advance never fires.
     *
     * Returns true when an active playlist was found and queued for load;
     * false when the DB is still empty after the restore (Drive AppData was
     * empty on this account, or only non-playlist categories were pulled).
     */
    suspend fun loadActivePlaylistIfAvailable(): Boolean {
        val active = repository.activePlaylist() ?: return false
        // switchToPlaylist already wraps the full "set active + fetch
        // channels + advance phase + kick EPG" pipeline used elsewhere
        // (manual playlist switch in Settings), so we route through it
        // instead of duplicating the state-machine progression here.
        switchToPlaylist(active.id)
        return true
    }

    /** Persist a user-chosen ordering of playlists (top-to-bottom). Used by
     * the Playlists drag-to-reorder UI. */
    fun applyPlaylistOrder(orderedIds: List<String>) {
        viewModelScope.launch { repository.applyPlaylistOrder(orderedIds) }
    }

    /** Delete a saved playlist by id. If the deleted row was the active one,
     * fall back to the most-recent remaining playlist (or NeedsUrl if none). */
    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            val wasActive = repository.activePlaylist()?.id == playlistId
            repository.deletePlaylist(playlistId)
            if (wasActive) {
                val remaining = repository.allOnce()
                val next = remaining.firstOrNull()
                if (next == null) {
                    _state.update {
                        it.copy(phase = Phase.NeedsUrl, playlist = null, channels = emptyList())
                    }
                } else {
                    switchToPlaylist(next.id)
                }
            }
        }
    }

    fun clearPlaylist() {
        viewModelScope.launch {
            repository.clear()
            _state.update { UiState(phase = Phase.NeedsUrl) }
        }
    }

    /**
     * Player "Switch Stream" (Dispatcharr Direct Connect): the active channel's
     * member streams with quality stats. [channelIntPk] is
     * M3UChannel.dispatcharrChannelId. Returns empty on any failure or a
     * non-Dispatcharr source so the sheet simply shows the empty state.
     */
    suspend fun loadChannelStreams(
        channelIntPk: Int,
    ): List<com.aeriotv.android.core.network.DispatcharrChannelStream> =
        runCatching { repository.listDispatcharrChannelStreams(channelIntPk) }
            .getOrDefault(emptyList())

    /** M3U account id -> source name, to label Switch Stream rows with their M3U.
     *  Empty on failure / non-Dispatcharr (the source label is then omitted). */
    suspend fun loadM3uAccountNames(): Map<Int, String> =
        runCatching { repository.dispatcharrM3uAccountNames() }.getOrDefault(emptyMap())

    /**
     * Player "Switch Stream": ask Dispatcharr to switch the channel's active
     * upstream to [streamId] (a Stream pk). [channelUuid] is M3UChannel.id minus
     * the "disp:" prefix. Result so the caller can Toast success/failure.
     */
    /** Switch the upstream; Result carries the resolved upstream URL (re-prime gate). */
    suspend fun switchChannelStream(channelUuid: String, streamId: Int): Result<String?> =
        runCatching { repository.switchDispatcharrStream(channelUuid, streamId) }

    /** Currently-active stream pk for the Switch Stream sheet's radio mark. */
    suspend fun loadCurrentStreamId(channelUuid: String): Int? =
        runCatching { repository.currentDispatcharrStreamId(channelUuid) }.getOrNull()

    /** Currently-active upstream URL (re-prime gate after a switch). */
    suspend fun loadCurrentStreamUrl(channelUuid: String): String? =
        runCatching { repository.currentDispatcharrStreamUrl(channelUuid) }.getOrNull()

    /** LAN/WAN verdict-flip signal for the player's mid-stream re-tune failover. */
    val lanVerdictFlips: kotlinx.coroutines.flow.SharedFlow<String> =
        repository.lanVerdictFlips

    /** Rebuild a Dispatcharr channel's live proxy URL from the current LAN/WAN
     *  base (re-tune after a verdict flip). Null when not applicable. */
    suspend fun rebuildLiveStreamUrl(channelUuid: String): String? =
        runCatching { repository.rebuildLiveStreamUrl(channelUuid) }.getOrNull()

    /** Force a fresh LAN/WAN probe (terminal-error failover path). */
    suspend fun reprobeActiveBase(): String? =
        runCatching { repository.reprobeActiveBase() }.getOrNull()
}

/** Find the programme containing `now` for a given channel. */
fun List<EPGProgramme>.nowPlaying(now: Long = System.currentTimeMillis()): EPGProgramme? =
    firstOrNull { it.startMillis <= now && now < it.endMillis }

/**
 * Channel-list sort options. Mirrors iOS sort menu (16:44:33 screenshot):
 * By Number / By Name / Favorites First.
 */
enum class SortMode(val label: String) {
    ByNumber("By Number"),
    ByName("By Name"),
    FavoritesFirst("Favorites First"),
}

/**
 * Auto-prepend an HTTP scheme to a bare host the user typed in onboarding /
 * Edit Playlist. Saves the user from typing `https://` every time they
 * paste / type a Dispatcharr or Xtream host -- "dispatcharr.example.com"
 * becomes "https://dispatcharr.example.com", "192.168.1.50:9191" becomes
 * "http://192.168.1.50:9191".
 *
 * Heuristic for picking http vs https:
 *  - Already has `http://` or `https://` -> leave as-is.
 *  - Host looks LAN-shaped (RFC1918 private IPv4 + `localhost` + `*.local`
 *    mDNS) -> prepend `http://`. Home servers almost never terminate TLS.
 *  - Anything else (public hostname, public IP) -> prepend `https://`.
 *    Modern public IPTV servers all serve TLS by default; the legacy
 *    http-only public host case is rare enough that a user encountering
 *    it can still explicitly type `http://` themselves.
 *
 * Whitespace is trimmed off either end before the scheme check so a
 * trailing newline from a copy-paste doesn't defeat the detection.
 */
internal fun normalizeSchemedUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return trimmed
    // Case-insensitive existing-scheme check covers "HTTP://..." pastes too.
    val lower = trimmed.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
        return trimmed
    }
    // Strip any user-supplied leading scheme-ish prefix that's NOT a real
    // scheme (e.g. "//example.com" protocol-relative) before deciding.
    val hostPart = trimmed.removePrefix("//")
    // Pull out the host (drop port + path) for the LAN-shape check. The
    // resulting prefix decision applies to the FULL trimmed input, not
    // just the host -- we want to keep the user's port/path intact.
    val hostOnly = hostPart.substringBefore('/').substringBefore(':')
    val isLan = when {
        hostOnly.equals("localhost", ignoreCase = true) -> true
        hostOnly.endsWith(".local", ignoreCase = true) -> true
        hostOnly.startsWith("192.168.") -> true
        hostOnly.startsWith("10.") -> true
        // 172.16.0.0/12 -> 172.16. through 172.31.
        hostOnly.startsWith("172.") -> {
            val secondOctet = hostOnly.removePrefix("172.").substringBefore('.').toIntOrNull()
            secondOctet != null && secondOctet in 16..31
        }
        else -> false
    }
    val scheme = if (isLan) "http://" else "https://"
    return "$scheme$hostPart"
}
