package com.aeriotv.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.category.CustomCategoryEntry
import com.aeriotv.android.core.category.ProgramCategory
import com.aeriotv.android.core.network.TMDBService
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.ui.theme.AppTheme
import com.aeriotv.android.ui.theme.AppearanceMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.aeriotv.android.core.data.db.entity.canRecordToServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings sub-screens share this ViewModel for read/write access to the
 * DataStore-backed preferences. Keeps each sub-screen stateless and lets
 * AerioTVTheme observe `selectedTheme` from MainActivity at the same time.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val tmdb: TMDBService,
    private val playlistRepository: com.aeriotv.android.core.data.repository.PlaylistRepository,
) : ViewModel() {

    // Appearance
    val selectedTheme: Flow<AppTheme> = prefs.selectedTheme
    fun setSelectedTheme(theme: AppTheme) {
        viewModelScope.launch { prefs.setSelectedTheme(theme) }
    }

    // Appearance mode (Dark / Light / System), orthogonal to selectedTheme.
    val appearanceMode: Flow<AppearanceMode> = prefs.appearanceMode
    fun setAppearanceMode(mode: AppearanceMode) {
        viewModelScope.launch { prefs.setAppearanceMode(mode) }
    }

    val displayScaleMovies: Flow<Float> = prefs.displayScaleMovies
    fun setDisplayScaleMovies(value: Float) {
        viewModelScope.launch { prefs.setDisplayScaleMovies(value) }
    }

    /** App-wide Text Size (Appearance > Text Size). */
    val textScale: Flow<Float> = prefs.textScale
    fun setTextScale(value: Float) {
        viewModelScope.launch { prefs.setTextScale(value) }
    }

    /** Subtext Size (Appearance > Subtext Size). */
    val subtextScale: Flow<Float> = prefs.subtextScale
    fun setSubtextScale(value: Float) {
        viewModelScope.launch { prefs.setSubtextScale(value) }
    }

    /** Text Contrast (Appearance > Text Contrast). */
    val textContrast: Flow<Float> = prefs.textContrast
    fun setTextContrast(value: Float) {
        viewModelScope.launch { prefs.setTextContrast(value) }
    }

    val useCustomAccent: Flow<Boolean> = prefs.useCustomAccent
    fun setUseCustomAccent(value: Boolean) {
        viewModelScope.launch { prefs.setUseCustomAccent(value) }
    }

    val showChannelLogos: StateFlow<Boolean> = prefs.showChannelLogos
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun setShowChannelLogos(value: Boolean) {
        viewModelScope.launch { prefs.setShowChannelLogos(value) }
    }

    val showChannelNumbers: StateFlow<Boolean> = prefs.showChannelNumbers
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val showChannelNames: StateFlow<Boolean> = prefs.showChannelNames
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun setShowChannelNames(value: Boolean) {
        viewModelScope.launch { prefs.setShowChannelNames(value) }
    }
    val showProgramSubtitles: StateFlow<Boolean> = prefs.showProgramSubtitles
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val timeFormat: StateFlow<String> = prefs.timeFormat
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")
    fun setTimeFormat(value: String) {
        viewModelScope.launch { prefs.setTimeFormat(value) }
    }
    fun setShowProgramSubtitles(value: Boolean) {
        viewModelScope.launch { prefs.setShowProgramSubtitles(value) }
    }

    /** Appearance > Rounded corners in List view (default ON). */
    val roundedArtwork: StateFlow<Boolean> = prefs.roundedArtwork
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun setRoundedArtwork(value: Boolean) {
        viewModelScope.launch { prefs.setRoundedArtwork(value) }
    }

    /** Appearance > Rounded corners in Guide view (default OFF). */
    val roundedArtworkGuide: StateFlow<Boolean> = prefs.roundedArtworkGuide
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setRoundedArtworkGuide(value: Boolean) {
        viewModelScope.launch { prefs.setRoundedArtworkGuide(value) }
    }
    fun setShowChannelNumbers(value: Boolean) {
        viewModelScope.launch { prefs.setShowChannelNumbers(value) }
    }

    // EPG program badges. Per-device-type: the Settings screen passes the
    // current device's isTv so the right value is read/written and synced.
    fun showEpgBadges(isTv: Boolean): Flow<Boolean> = prefs.showEpgBadges(isTv)
    val hiddenEpgBadges: StateFlow<Set<String>> = prefs.hiddenEpgBadges
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    fun setBadgeHidden(label: String, hidden: Boolean) {
        viewModelScope.launch { prefs.setBadgeHidden(label, hidden) }
    }
    fun setShowEpgBadges(isTv: Boolean, value: Boolean) {
        viewModelScope.launch { prefs.setShowEpgBadges(isTv, value) }
    }

    /**
     * Video scale: "fit" (default), "fill" (crop to fill, aspect kept) or
     * "stretch" (aspect ignored).
     */
    val videoScaleMode: Flow<String> = prefs.videoScaleMode
    fun setVideoScaleMode(value: String) {
        viewModelScope.launch { prefs.setVideoScaleMode(value) }
    }

    /** Player options row: Fit -> Fill -> Stretch -> Fit. */
    fun cycleVideoScaleMode(current: String) {
        setVideoScaleMode(
            com.aeriotv.android.feature.player.nextVideoScaleMode(current),
        )
    }

    val customAccentHex: Flow<String> = prefs.customAccentHex
    fun setCustomAccentHex(value: String) {
        viewModelScope.launch { prefs.setCustomAccentHex(value) }
    }

    val displayScaleLiveTV: StateFlow<Float> = prefs.displayScaleLiveTV
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)
    fun setDisplayScaleLiveTV(value: Float) {
        viewModelScope.launch { prefs.setDisplayScaleLiveTV(value) }
    }

    /** EPG guide timeline zoom (iOS guideScale). Written by pinch + the discrete selector. */
    val guideScale: StateFlow<Float> = prefs.guideScale
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)
    fun setGuideScale(value: Float) {
        viewModelScope.launch { prefs.setGuideScale(value) }
    }

    val hiddenGroups: StateFlow<Set<String>> = prefs.hiddenGroups
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    fun setHiddenGroups(groups: Set<String>) {
        viewModelScope.launch { prefs.setHiddenGroups(groups) }
    }

    // Live TV group ordering (Manage Groups reorder). groupSortMode is one of
    // Default / Alphabetical / Manual; groupOrder is the manual order list.
    val groupOrder: StateFlow<List<String>> = prefs.groupOrder
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun setGroupOrder(order: List<String>) {
        viewModelScope.launch { prefs.setGroupOrder(order) }
    }
    val groupSortMode: StateFlow<String> = prefs.groupSortMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Default")
    fun setGroupSortMode(mode: String) {
        viewModelScope.launch { prefs.setGroupSortMode(mode) }
    }

    // ---- GH #81: Default Group (App Behaviors) -------------------------------
    // The group Live TV opens on, per playlist. An empty token leaves the
    // restore-last-selection path (901e6885) in charge, which the picker shows
    // as All Channels because that is where a first launch lands.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val activePlaylistId: StateFlow<String?> = playlistRepository.observeActiveId()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * True when the active playlist can record on its server (Dispatcharr
     * Direct Connect with DVR manage access). Settings > DVR shows Recording
     * Destination only then; M3U and Xtream have no server DVR.
     */
    val activeCanRecordToServer: Flow<Boolean> = playlistRepository.observeAll()
        .map { rows ->
            rows.firstOrNull { it.isActive }?.canRecordToServer() == true
        }

    // The Default Group PICKER (options and setter) moved to Live TV's Manage
    // Groups sheet (Logan 2026-09-17); PlaylistViewModel owns the write. The
    // token is still exposed read-only for surfaces that have SettingsViewModel
    // but no PlaylistViewModel, such as the player's channel-list overlay,
    // which marks the default group with a pin (Apple ChannelListView.swift).
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val defaultGroupToken: StateFlow<String> = activePlaylistId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) kotlinx.coroutines.flow.flowOf("")
            else prefs.defaultGroupToken(id)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Whether the synthetic "Recently Watched" group is shown, per playlist
     * (Logan 2026-09-14). Unchecked in Manage Groups by default.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val recentGroupVisible: StateFlow<Boolean> = activePlaylistId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) kotlinx.coroutines.flow.flowOf(false)
            else prefs.recentGroupVisible(id)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Set (or clear, with the same token again) the group Live TV opens on.
     * Mirrors PlaylistViewModel.setDefaultGroup exactly, including the
     * Recently Watched visibility side effect, for surfaces that hold a
     * SettingsViewModel but no PlaylistViewModel (the player's channel-list
     * overlay, where a long press on a group row sets the default).
     */
    fun setDefaultGroupToken(token: String) {
        viewModelScope.launch {
            val id = activePlaylistId.value
            if (id.isNullOrBlank()) return@launch
            val current = prefs.defaultGroupTokenOnce(id)
            val next = if (current == token) "" else token
            prefs.setDefaultGroupToken(id, next)
            if (next == com.aeriotv.android.feature.playlist.PlaylistViewModel.RECENT_GROUP) {
                prefs.setRecentGroupVisible(id, true)
            }
        }
    }

    fun setRecentGroupVisible(visible: Boolean) {
        val id = activePlaylistId.value
        if (id.isNullOrBlank()) return
        viewModelScope.launch { prefs.setRecentGroupVisible(id, visible) }
    }

    // VOD group filters (iOS MoviesView hiddenMovieGroups / TVShowsView
    // hiddenSeriesGroups). Surfaced via ManageGroupsSheet from the On Demand
    // tab; applied in MoviesSubScreen / SeriesSubScreen to filter the lists.
    /** DVR Filter: channels hidden from the recordings library (all platforms, 2026-09-10). */
    val hiddenDvrChannels: Flow<Set<String>> = prefs.hiddenDvrChannels
    fun setHiddenDvrChannels(channels: Set<String>) {
        viewModelScope.launch { prefs.setHiddenDvrChannels(channels) }
    }
    val hiddenMovieGroups: Flow<Set<String>> = prefs.hiddenMovieGroups
    fun setHiddenMovieGroups(groups: Set<String>) {
        viewModelScope.launch { prefs.setHiddenMovieGroups(groups) }
    }
    val hiddenSeriesGroups: Flow<Set<String>> = prefs.hiddenSeriesGroups
    fun setHiddenSeriesGroups(groups: Set<String>) {
        viewModelScope.launch { prefs.setHiddenSeriesGroups(groups) }
    }


    // App Behaviors
    val liveRewindEnabled: Flow<Boolean> = prefs.liveRewindEnabled
    fun setLiveRewindEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setLiveRewindEnabled(value) }
    }

    val liveRewindDepthMinutes: Flow<Int> = prefs.liveRewindDepthMinutes
    fun setLiveRewindDepthMinutes(value: Int) {
        viewModelScope.launch { prefs.setLiveRewindDepthMinutes(value) }
    }

    val liveRewindKeepRecent: Flow<Boolean> = prefs.liveRewindKeepRecent
    fun setLiveRewindKeepRecent(value: Boolean) {
        viewModelScope.launch { prefs.setLiveRewindKeepRecent(value) }
    }

    val liveRewindKeepCount: Flow<Int> = prefs.liveRewindKeepCount
    fun setLiveRewindKeepCount(value: Int) {
        viewModelScope.launch { prefs.setLiveRewindKeepCount(value) }
    }

    // Skip Intervals (Logan 2026-09-14): global skip back / forward seconds.
    val skipBackSeconds: Flow<Int> = prefs.skipBackSeconds
    fun setSkipBackSeconds(value: Int) {
        viewModelScope.launch { prefs.setSkipBackSeconds(value) }
    }

    val skipForwardSeconds: Flow<Int> = prefs.skipForwardSeconds
    fun setSkipForwardSeconds(value: Int) {
        viewModelScope.launch { prefs.setSkipForwardSeconds(value) }
    }

    val liveRewindRetentionHours: Flow<Int> = prefs.liveRewindRetentionHours
    fun setLiveRewindRetentionHours(value: Int) {
        viewModelScope.launch { prefs.setLiveRewindRetentionHours(value) }
    }

    val liveRewindBudgetGB: Flow<Int> = prefs.liveRewindBudgetGB
    fun setLiveRewindBudgetGB(value: Int) {
        viewModelScope.launch { prefs.setLiveRewindBudgetGB(value) }
    }

    val skipLoadingScreen: StateFlow<Boolean> = prefs.skipLoadingScreen
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setSkipLoadingScreen(value: Boolean) {
        viewModelScope.launch { prefs.setSkipLoadingScreen(value) }
    }

    /** Auto-Rotate (Logan 2026-08-07): follow device orientation, default ON. */
    val autoRotate: Flow<Boolean> = prefs.autoRotate
    fun setAutoRotate(value: Boolean) {
        viewModelScope.launch { prefs.setAutoRotate(value) }
    }

    val appleTVChannelFlip: Flow<Boolean> = prefs.appleTVChannelFlip

    /** Remote Control initiative: decoded button map (tolerant of unknown
     *  slots/actions; defaults when unset). */
    val remoteControlMap: StateFlow<com.aeriotv.android.core.remote.RemoteControlMap> =
        prefs.effectiveRemoteControlMap
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.aeriotv.android.core.remote.RemoteControlMap.DEFAULT)
    /** Remote hint strip on Live TV and in the players. Default ON. */
    val showRemoteHints: StateFlow<Boolean> = prefs.showRemoteHints
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun setShowRemoteHints(value: Boolean) {
        viewModelScope.launch { prefs.setShowRemoteHints(value) }
    }

    /** TV guide group-selector style: "pills" (top row) or "sidebar". */
    /** Read-only mirror of the Drive sync master switch, for the Settings root
     *  row value. Writing still belongs to SyncSettingsViewModel. */
    val syncMasterEnabled: kotlinx.coroutines.flow.Flow<Boolean> = prefs.syncMasterEnabled

    val guideGroupSelector: StateFlow<String> = prefs.guideGroupSelector
        .stateIn(viewModelScope, SharingStarted.Eagerly, "pills")
    fun setGuideGroupSelector(mode: String) {
        viewModelScope.launch { prefs.setGuideGroupSelector(mode) }
    }
    /** TV guide sidebar layout: "overlay" (default, scrim) or "shift" (grid narrows). */
    val guideSidebarLayout: StateFlow<String> = prefs.guideSidebarLayout
        .stateIn(viewModelScope, SharingStarted.Eagerly, "overlay")
    fun setGuideSidebarLayout(mode: String) {
        viewModelScope.launch { prefs.setGuideSidebarLayout(mode) }
    }
    /** Phone / tablet Live TV group selector: "sidebar" (default drawer) or "pills". */
    val phoneGroupSelector: StateFlow<String> = prefs.phoneGroupSelector
        .stateIn(viewModelScope, SharingStarted.Eagerly, "sidebar")
    fun setPhoneGroupSelector(mode: String) {
        viewModelScope.launch { prefs.setPhoneGroupSelector(mode) }
    }

    /** Live TV tune target: false = fullscreen (default), true = corner mini. */
    val guideTuneInMini: StateFlow<Boolean> = prefs.guideTuneInMini
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setGuideTuneInMini(value: Boolean) {
        viewModelScope.launch { prefs.setGuideTuneInMini(value) }
    }

    /** GH #38: refresh rate requested at app startup ("off"/"50"/"59.94"/"60"). */
    val startupRefreshRate: Flow<String> = prefs.startupRefreshRate
    fun setStartupRefreshRate(value: String) {
        viewModelScope.launch { prefs.setStartupRefreshRate(value) }
    }

    val moviesSortOrder: Flow<String> = prefs.moviesSortOrder
    fun setMoviesSortOrder(value: String) { viewModelScope.launch { prefs.setMoviesSortOrder(value) } }
    val seriesSortOrder: Flow<String> = prefs.seriesSortOrder
    fun setSeriesSortOrder(value: String) { viewModelScope.launch { prefs.setSeriesSortOrder(value) } }
    val dvrSortOrder: Flow<String> = prefs.dvrSortOrder
    fun setDvrSortOrder(value: String) { viewModelScope.launch { prefs.setDvrSortOrder(value) } }

    val vodLibraryRefreshHours: Flow<Int> = prefs.vodLibraryRefreshHours
    fun setVodLibraryRefreshHours(hours: Int) {
        viewModelScope.launch { prefs.setVodLibraryRefreshHours(hours) }
    }

    /** GH #40: opt-in output-resolution matching on TV boxes. */
    val matchContentResolution: Flow<Boolean> = prefs.matchContentResolution
    fun setMatchContentResolution(value: Boolean) {
        viewModelScope.launch { prefs.setMatchContentResolution(value) }
    }

    fun setRemoteControlMap(map: com.aeriotv.android.core.remote.RemoteControlMap) {
        viewModelScope.launch { prefs.setRemoteControlMap(map.toJson()) }
    }
    fun setAppleTVChannelFlip(value: Boolean) {
        viewModelScope.launch { prefs.setAppleTVChannelFlip(value) }
    }

    /** In-Player Gestures (phone and tablet). All default off. */
    val playerBrightnessGesture: Flow<Boolean> = prefs.playerBrightnessGesture
    fun setPlayerBrightnessGesture(value: Boolean) {
        viewModelScope.launch { prefs.setPlayerBrightnessGesture(value) }
    }

    val playerVolumeGesture: Flow<Boolean> = prefs.playerVolumeGesture
    fun setPlayerVolumeGesture(value: Boolean) {
        viewModelScope.launch { prefs.setPlayerVolumeGesture(value) }
    }

    val playerBrightnessEdge: Flow<String> = prefs.playerBrightnessEdge
    fun setPlayerBrightnessEdge(value: String) {
        viewModelScope.launch { prefs.setPlayerBrightnessEdge(value) }
    }

    // iOS appBehaviorsAutoRecoverFrozenStreams (#37). Default true; device-local.
    val autoRecoverFrozenStreams: Flow<Boolean> = prefs.autoRecoverFrozenStreams
    fun setAutoRecoverFrozenStreams(value: Boolean) {
        viewModelScope.launch { prefs.setAutoRecoverFrozenStreams(value) }
    }

    // Player Info Card elements (App Behaviors, Apple parity). All default true;
    // they gate ONLY the in-player program info card.
    val playerCardShowChannelLogo: Flow<Boolean> = prefs.playerCardShowChannelLogo
    fun setPlayerCardShowChannelLogo(value: Boolean) {
        viewModelScope.launch { prefs.setPlayerCardShowChannelLogo(value) }
    }

    val playerCardShowChannelName: Flow<Boolean> = prefs.playerCardShowChannelName
    fun setPlayerCardShowChannelName(value: Boolean) {
        viewModelScope.launch { prefs.setPlayerCardShowChannelName(value) }
    }

    val playerCardShowProgramName: Flow<Boolean> = prefs.playerCardShowProgramName
    fun setPlayerCardShowProgramName(value: Boolean) {
        viewModelScope.launch { prefs.setPlayerCardShowProgramName(value) }
    }

    val playerCardShowProgramTime: Flow<Boolean> = prefs.playerCardShowProgramTime
    fun setPlayerCardShowProgramTime(value: Boolean) {
        viewModelScope.launch { prefs.setPlayerCardShowProgramTime(value) }
    }

    val playerCardShowProgramSubtitle: Flow<Boolean> = prefs.playerCardShowProgramSubtitle
    fun setPlayerCardShowProgramSubtitle(value: Boolean) {
        viewModelScope.launch { prefs.setPlayerCardShowProgramSubtitle(value) }
    }

    val playerCardShowProgramDescription: Flow<Boolean> = prefs.playerCardShowProgramDescription
    fun setPlayerCardShowProgramDescription(value: Boolean) {
        viewModelScope.launch { prefs.setPlayerCardShowProgramDescription(value) }
    }

    // TMDB program posters (opt-in, off by default). Key is device-local.
    val programPostersTmdbEnabled: Flow<Boolean> = prefs.programPostersTmdbEnabled
    fun setProgramPostersTmdbEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setProgramPostersTmdbEnabled(value) }
    }

    // Surround sound passthrough (device-specific; off = in-app decode, fixes lip sync
    // on TVs that decode the bitstream late).
    val audioPassthroughEnabled: Flow<Boolean> = prefs.audioPassthroughEnabled
    fun setAudioPassthroughEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setAudioPassthroughEnabled(value) }
    }

    val tmdbApiKey: Flow<String> = prefs.tmdbApiKey

    enum class TmdbKeyTestState { Idle, Testing, Valid, Invalid, Saved }
    private val _tmdbKeyTestState = MutableStateFlow(TmdbKeyTestState.Idle)
    val tmdbKeyTestState: StateFlow<TmdbKeyTestState> = _tmdbKeyTestState.asStateFlow()

    /** Reset the status label (called on each keystroke in the field). */
    fun resetTmdbKeyTestState() { _tmdbKeyTestState.value = TmdbKeyTestState.Idle }

    /** Validate the draft key against TMDB /configuration (no save). */
    fun testTmdbKey(draft: String) {
        viewModelScope.launch {
            _tmdbKeyTestState.value = TmdbKeyTestState.Testing
            val ok = tmdb.validateKey(draft)
            _tmdbKeyTestState.value = if (ok) TmdbKeyTestState.Valid else TmdbKeyTestState.Invalid
        }
    }

    /** Persist the draft key (device-local) and confirm with a Saved status. */
    fun saveTmdbKey(draft: String) {
        viewModelScope.launch {
            prefs.setTmdbApiKey(draft)
            // Misses cached under the previous key must not survive a key
            // change; positives re-resolve cheaply on next lookup.
            tmdb.clearCache()
            _tmdbKeyTestState.value = TmdbKeyTestState.Saved
        }
    }

    // Developer
    val debugLoggingEnabled: Flow<Boolean> = prefs.debugLoggingEnabled
    fun setDebugLoggingEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setDebugLoggingEnabled(value) }
    }

    val castForceHevcTranscode: Flow<Boolean> = prefs.castForceHevcTranscode
    fun setCastForceHevcTranscode(value: Boolean) {
        viewModelScope.launch { prefs.setCastForceHevcTranscode(value) }
    }

    val castTranscodeDownProfile: Flow<String> = prefs.castTranscodeDownProfile
    fun setCastTranscodeDownProfile(value: String) {
        viewModelScope.launch { prefs.setCastTranscodeDownProfile(value) }
    }

    val autoResumeLastChannel: Flow<Boolean> = prefs.autoResumeLastChannel
    fun setAutoResumeLastChannel(value: Boolean) {
        viewModelScope.launch { prefs.setAutoResumeLastChannel(value) }
    }

    val lastWatchedChannelId: Flow<String> = prefs.lastWatchedChannelId
    fun setLastWatchedChannelId(value: String) {
        viewModelScope.launch { prefs.setLastWatchedChannelId(value) }
    }

    /** LRU recent channel ids (most-recent first). Powers the AddToMultiview "Recent" section. */
    val recentChannelIds: Flow<List<String>> = prefs.recentChannelIds
    fun recordRecentChannel(channelId: String) {
        viewModelScope.launch { prefs.recordRecentChannel(channelId) }
    }

    val defaultTab: StateFlow<String> = prefs.defaultTab
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    fun setDefaultTab(value: String) {
        viewModelScope.launch { prefs.setDefaultTab(value) }
    }

    // Live TV view-mode persistence (Phase 5 hand-off - migrated from
    // rememberSaveable in LiveTVViewMode.kt to DataStore in Phase 8b).
    val defaultLiveTVView: StateFlow<String> = prefs.defaultLiveTVView
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    fun setDefaultLiveTVView(value: String) {
        viewModelScope.launch {
            // Per-device preference (NOT Drive-synced): the correct default is
            // form-factor specific, so a synced value would let one device clobber
            // another (a phone's List overriding a TV's Guide). Matches iOS
            // @AppStorage. Persists locally only; no Drive push.
            prefs.setDefaultLiveTVView(value)
        }
    }

    /** TV Live TV layout (tvOS parity 2026-09-10): "basic" or "preview". Per device. */
    val liveTvLayout: StateFlow<String> = prefs.liveTvLayout
        .stateIn(viewModelScope, SharingStarted.Eagerly, "basic")
    fun setLiveTvLayout(value: String) {
        viewModelScope.launch { prefs.setLiveTvLayout(value) }
    }

    // Network (Phase 8c)
    val networkTimeoutSecs: Flow<Double> = prefs.networkTimeoutSecs
    fun setNetworkTimeoutSecs(value: Double) {
        viewModelScope.launch { prefs.setNetworkTimeoutSecs(value) }
    }

    val maxRetries: Flow<Int> = prefs.maxRetries
    fun setMaxRetries(value: Int) {
        viewModelScope.launch { prefs.setMaxRetries(value) }
    }

    val streamBufferSize: Flow<String> = prefs.streamBufferSize
    fun setStreamBufferSize(value: String) {
        viewModelScope.launch { prefs.setStreamBufferSize(value) }
    }

    // Logan 2026-09-11: the Guide Window preference is retired. The guide
    // window comes from the playlist's Guide Days setting
    // (PlaylistEntity.epgRetentionDays) in both directions, so there is no
    // epgWindowHours state or setter here any more. AppPreferences still
    // exposes the key read-only for migration.

    // Audit task #48: master toggle for the periodic PlaylistRefreshWorker.
    // The Application collects this Flow and registers/cancels the unique
    // periodic work whenever the user flips it.
    val backgroundRefreshEnabled: Flow<Boolean> = prefs.backgroundRefreshEnabled
    fun setBackgroundRefreshEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setBackgroundRefreshEnabled(value) }
    }

    // GuideStore audit P1 #7: how often the periodic refresh fires.
    // Default 360 minutes (6h) matches the prior hardcoded interval.
    val backgroundRefreshIntervalMins: Flow<Int> = prefs.backgroundRefreshIntervalMins
    fun setBackgroundRefreshIntervalMins(value: Int) {
        viewModelScope.launch { prefs.setBackgroundRefreshIntervalMins(value) }
    }
    val backgroundRefreshType: Flow<String> = prefs.backgroundRefreshType
    fun setBackgroundRefreshType(value: String) {
        viewModelScope.launch { prefs.setBackgroundRefreshType(value) }
    }
    val backgroundRefreshHour: Flow<Int> = prefs.backgroundRefreshHour
    val backgroundRefreshMinute: Flow<Int> = prefs.backgroundRefreshMinute
    fun setBackgroundRefreshTime(hour: Int, minute: Int) {
        viewModelScope.launch { prefs.setBackgroundRefreshTime(hour, minute) }
    }

    // Multiview (Phase 11c)
    val multiviewAudioFocusStyle: Flow<String> = prefs.multiviewAudioFocusStyle
    fun setMultiviewAudioFocusStyle(value: String) {
        viewModelScope.launch { prefs.setMultiviewAudioFocusStyle(value) }
    }

    val multiviewTilePadding: Flow<Boolean> = prefs.multiviewTilePadding
    fun setMultiviewTilePadding(value: Boolean) {
        viewModelScope.launch { prefs.setMultiviewTilePadding(value) }
    }

    val multiviewShowLogos: Flow<Boolean> = prefs.multiviewShowLogos
    fun setMultiviewShowLogos(value: Boolean) {
        viewModelScope.launch { prefs.setMultiviewShowLogos(value) }
    }
    val multiviewLogoPosition: Flow<String> = prefs.multiviewLogoPosition
    fun setMultiviewLogoPosition(value: String) {
        viewModelScope.launch { prefs.setMultiviewLogoPosition(value) }
    }
    val multiviewLogoSize: Flow<Int> = prefs.multiviewLogoSize
    fun setMultiviewLogoSize(value: Int) {
        viewModelScope.launch { prefs.setMultiviewLogoSize(value) }
    }

    val multiviewTileCornersRounded: Flow<Boolean> = prefs.multiviewTileCornersRounded
    fun setMultiviewTileCornersRounded(value: Boolean) {
        viewModelScope.launch { prefs.setMultiviewTileCornersRounded(value) }
    }

    val multiviewLayoutMode: Flow<String> = prefs.multiviewLayoutMode
    fun setMultiviewLayoutMode(value: String) {
        viewModelScope.launch { prefs.setMultiviewLayoutMode(value) }
    }

    val multiviewPerfWarningSuppressed: Flow<Boolean> = prefs.multiviewPerfWarningSuppressed
    fun setMultiviewPerfWarningSuppressed(value: Boolean) {
        viewModelScope.launch { prefs.setMultiviewPerfWarningSuppressed(value) }
    }

    // DVR (Phase 9b-3)
    val dvrMaxLocalStorageMB: Flow<Int> = prefs.dvrMaxLocalStorageMB
    fun setDvrMaxLocalStorageMB(value: Int) {
        viewModelScope.launch { prefs.setDvrMaxLocalStorageMB(value) }
    }

    /** Task #50: the record sheet's pre-seeded destination ("server"/"local"). */
    val dvrDefaultDestination: Flow<String> = prefs.dvrDefaultDestination
    fun setDvrDefaultDestination(value: String) {
        viewModelScope.launch { prefs.setDvrDefaultDestination(value) }
    }

    val dvrDefaultPreRollMins: Flow<Int> = prefs.dvrDefaultPreRollMins
    fun setDvrDefaultPreRollMins(value: Int) {
        viewModelScope.launch { prefs.setDvrDefaultPreRollMins(value) }
    }

    val dvrDefaultPostRollMins: Flow<Int> = prefs.dvrDefaultPostRollMins
    fun setDvrDefaultPostRollMins(value: Int) {
        viewModelScope.launch { prefs.setDvrDefaultPostRollMins(value) }
    }

    val dvrCustomFolderUri: Flow<String> = prefs.dvrCustomFolderUri
    fun setDvrCustomFolderUri(value: String) {
        viewModelScope.launch { prefs.setDvrCustomFolderUri(value) }
    }

    val dvrKeepAwakeDuringRecording: Flow<Boolean> = prefs.dvrKeepAwakeDuringRecording
    fun setDvrKeepAwakeDuringRecording(value: Boolean) {
        viewModelScope.launch { prefs.setDvrKeepAwakeDuringRecording(value) }
    }

    // Category Palette (Phase 15)
    val categoryPalette: Flow<CategoryPaletteState> = prefs.categoryPalette
    fun setCategoryColorsEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setCategoryColorsEnabled(value) }
    }
    fun setCategoryBucketHex(bucket: ProgramCategory, hex: String?) {
        viewModelScope.launch { prefs.setCategoryBucketHex(bucket, hex) }
    }
    fun setCategoryBucketEnabled(bucket: ProgramCategory, enabled: Boolean) {
        viewModelScope.launch { prefs.setCategoryBucketEnabled(bucket, enabled) }
    }
    fun resetCategoryPalette() {
        viewModelScope.launch { prefs.resetCategoryPalette() }
    }
    fun setCustomCategories(list: List<CustomCategoryEntry>) {
        viewModelScope.launch { prefs.setCustomCategories(list) }
    }
}
