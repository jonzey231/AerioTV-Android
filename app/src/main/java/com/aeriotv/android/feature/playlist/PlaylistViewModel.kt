package com.aeriotv.android.feature.playlist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.bridgeChannelIds
import com.aeriotv.android.core.data.buildChannelEpgKeyBridge
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.repository.ChannelProfileOption
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.debug.MemoryPressureBus
import com.aeriotv.android.core.preferences.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val appPreferences: AppPreferences,
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
        val playlist: PlaylistEntity? = null,
        val channels: List<M3UChannel> = emptyList(),
        val epgByChannel: Map<String, List<EPGProgramme>> = emptyMap(),
        val isEpgLoading: Boolean = false,
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
                                error = "Failed to refresh saved source: ${t.message ?: t::class.simpleName}",
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
            )
            repository.loadAndPersist(request, existingId = s.playlist?.id).fold(
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
                    loadEpgIfConfigured(entity)
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            channels = emptyList(),
                            error = "Failed to load: ${t.message ?: t::class.simpleName}",
                        )
                    }
                },
            )
        }
    }

    private fun loadEpgIfConfigured(playlist: PlaylistEntity, forceRefresh: Boolean = false) {
        viewModelScope.launch { doLoadEpg(playlist, forceRefresh) }
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
                        _state.update { it.copy(epgByChannel = groupedEnriched) }
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
                                "Refresh failed: ${t.message ?: t::class.simpleName}",
                            ),
                            error = "Refresh failed: ${t.message ?: t::class.simpleName}",
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
                            "EPG refresh failed: ${outcome.exceptionOrNull()?.message ?: "unknown error"}",
                        )
                    },
                )
            }
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
                            error = "Save failed: ${t.message ?: t::class.simpleName}",
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
                                "Connection failed: ${t.message ?: t::class.simpleName}",
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
     * right now (LAN when on a saved home SSID with a LAN URL set, WAN
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

    /** Reset Playlist Detail's per-action feedback. Called when the screen
     *  leaves composition so stale results don't greet the next visit. */
    fun clearDetailActionStatuses() {
        _state.update {
            it.copy(
                testStatus = ActionStatus.Idle,
                playlistRefreshStatus = ActionStatus.Idle,
                epgRefreshStatus = ActionStatus.Idle,
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
                    loadEpgIfConfigured(entity)
                },
                onFailure = { t ->
                    Log.w(TAG, "switchToPlaylist failed", t)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = "Switch failed: ${t.message ?: t::class.simpleName}",
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
