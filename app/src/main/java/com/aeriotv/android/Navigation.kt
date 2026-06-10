package com.aeriotv.android

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.entity.canRecordToServer
import com.aeriotv.android.ui.LocalCanRecordToServer
import com.aeriotv.android.core.pip.findActivity
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.feature.main.MainScaffold
import com.aeriotv.android.feature.onboarding.ChooseSourceTypeScreen
import com.aeriotv.android.feature.onboarding.ConfigureSourceScreen
import com.aeriotv.android.feature.onboarding.SettingUpScreen
import com.aeriotv.android.feature.onboarding.WelcomeScreen
import com.aeriotv.android.feature.multiview.MultiviewScreen
import com.aeriotv.android.feature.ondemand.OnDemandViewModel
import com.aeriotv.android.feature.ondemand.SeriesDetailScreen
import com.aeriotv.android.feature.main.MainScaffoldEntryPoint
import com.aeriotv.android.feature.miniplayer.MiniPlayerSession
import com.aeriotv.android.feature.miniplayer.MiniPlayerViewModel
import com.aeriotv.android.feature.miniplayer.TvMiniPlayerOverlay
import com.aeriotv.android.feature.player.PlayerScreen
import com.aeriotv.android.feature.player.VODPlayerScreen
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.update.UpdateGate
import com.aeriotv.android.feature.whatsnew.WhatsNewGate
import com.aeriotv.android.feature.reminders.ReminderBannerHost
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * EntryPoint accessor so the bootstrap composable can read DataStore-backed
 * preferences without forcing every screen to depend on AppPreferences via a
 * hiltViewModel. Used once at cold-boot to decide whether to auto-resume the
 * last-played channel.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NavEntryPoint {
    fun appPreferences(): AppPreferences
}

object Routes {
    const val PLAYLIST_GRAPH = "playlist_graph"
    const val BOOTSTRAP = "bootstrap"
    const val WELCOME = "welcome"
    const val CHOOSE_TYPE = "choose_type"
    const val CONFIGURE = "configure/{type}"
    const val MAIN = "main"
    const val PLAYER = "player/{channelId}"
    const val VOD_PLAYER = "vod_player/{movieUuid}"
    const val MOVIE_DETAIL = "movie_detail/{movieUuid}"
    const val SERIES_DETAIL = "series_detail/{seriesId}"
    const val VOD_EPISODE_PLAYER = "vod_episode_player/{episodeUuid}"
    const val MULTIVIEW = "multiview"
    const val RECORDING_PLAYER = "recording_player/{playbackUrl}/{title}"

    fun configure(type: SourceType) = "configure/${type.name}"
    fun player(channelId: String) = "player/${Uri.encode(channelId)}"
    fun vodPlayer(movieUuid: String) = "vod_player/${Uri.encode(movieUuid)}"
    fun movieDetail(movieUuid: String) = "movie_detail/${Uri.encode(movieUuid)}"
    fun seriesDetail(seriesId: Int) = "series_detail/$seriesId"
    fun vodEpisodePlayer(episodeUuid: String) = "vod_episode_player/${Uri.encode(episodeUuid)}"
    fun recordingPlayer(playbackUrl: String, title: String) =
        "recording_player/${Uri.encode(playbackUrl)}/${Uri.encode(title)}"
}

@Composable
fun AerioTVNavHost(
    initialUrl: String? = null,
    initialEpgUrl: String? = null,
    initialApiKey: String? = null,
    deepLinkTarget: DeepLinkTarget? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()

    Box(modifier = Modifier.fillMaxSize()) {
    NavHost(navController = navController, startDestination = Routes.PLAYLIST_GRAPH) {
        navigation(startDestination = Routes.BOOTSTRAP, route = Routes.PLAYLIST_GRAPH) {

            composable(Routes.BOOTSTRAP) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()
                val context = androidx.compose.ui.platform.LocalContext.current

                // Debug-only auto-load. --es url + --es apikey => Dispatcharr API-key flow.
                // --es url + optional --es epg => M3U flow. Gated in MainActivity via
                // BuildConfig.DEBUG so release builds always ignore intent extras.
                LaunchedEffect(initialUrl, initialEpgUrl, initialApiKey) {
                    if (!initialUrl.isNullOrBlank() && state.url.isBlank()) {
                        if (!initialApiKey.isNullOrBlank()) {
                            vm.loadFromDispatcharr(initialUrl, initialApiKey)
                        } else {
                            vm.loadFromUrl(initialUrl, initialEpgUrl)
                        }
                    }
                }

                LaunchedEffect(state.phase) {
                    when (state.phase) {
                        PlaylistViewModel.Phase.Bootstrapping -> Unit
                        PlaylistViewModel.Phase.NeedsUrl -> navController.navigate(Routes.WELCOME) {
                            // popUpTo the graph (NOT the BOOTSTRAP entry
                            // inclusively): if BOOTSTRAP is the last child of
                            // PLAYLIST_GRAPH the inclusive pop collapses the
                            // whole graph, and the subsequent navigate creates
                            // a FRESH PLAYLIST_GRAPH entry -- new VM store, the
                            // PlaylistViewModel inits a second time, and every
                            // disk read + EPG cache paint runs twice (~700ms +
                            // 70MB of GC churn on cold launch). Popping up to
                            // the graph with inclusive=false leaves the graph
                            // entry intact, so the VM scoped to it survives.
                            popUpTo(Routes.PLAYLIST_GRAPH) { inclusive = false }
                        }
                        PlaylistViewModel.Phase.ChannelsReady -> {
                            // Resume Last Channel (App Behaviors). When the toggle is on
                            // and we have a stored channel id that still exists in the
                            // current playlist, navigate straight into the player. Falls
                            // back to MAIN when the toggle is off or the saved channel is
                            // missing (e.g. user switched playlists since last launch).
                            val prefs = dagger.hilt.android.EntryPointAccessors
                                .fromApplication(context.applicationContext, NavEntryPoint::class.java)
                                .appPreferences()
                            val resume = prefs.autoResumeLastChannelOnce()
                            val resumeId = if (resume) prefs.lastWatchedChannelIdOnce() else ""
                            val hasResumeTarget = resumeId.isNotBlank() &&
                                state.channels.any { it.id == resumeId && it.url.isNotBlank() }
                            if (hasResumeTarget) {
                                navController.navigate(Routes.MAIN) {
                                    popUpTo(Routes.PLAYLIST_GRAPH) { inclusive = false }
                                }
                                navController.navigate(Routes.player(resumeId))
                            } else {
                                navController.navigate(Routes.MAIN) {
                                    popUpTo(Routes.PLAYLIST_GRAPH) { inclusive = false }
                                }
                            }
                        }
                    }
                }

                SettingUpScreen(
                    onSkip = {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.PLAYLIST_GRAPH) { inclusive = false }
                        }
                    },
                )
            }

            composable(Routes.WELCOME) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()
                // Sync sub-viewmodel handles the Google sign-in flow here so
                // the Welcome screen stays stateless. Reusing the same VM
                // means a successful sign-in shows up in Settings > Sync
                // without a second sign-in round-trip later.
                val syncVm: com.aeriotv.android.feature.settings.SyncSettingsViewModel = hiltViewModel(parent)
                val syncBuildConfigured = remember { com.aeriotv.android.core.sync.SyncConfig.isConfigured() }
                val context = androidx.compose.ui.platform.LocalContext.current
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                var googleSignInInFlight by remember { mutableStateOf(false) }
                var welcomeNotConfiguredDialog by remember { mutableStateOf(false) }
                // Shared restore closure: pulls all categories from Drive,
                // then asks PlaylistViewModel to re-bootstrap from whatever
                // playlists just landed in the DB. The existing
                // LaunchedEffect that watches `state.phase == ChannelsReady`
                // will trip and navigate to MAIN on its own, so the Welcome
                // screen never has to ask the navController itself. Called
                // from BOTH the immediate Authorized branch and the
                // post-consent launcher result so the flow is identical
                // regardless of whether the user had granted Drive scope
                // on a previous session.
                val tryRestoreAndAdvance: suspend (String?) -> Unit = { signedInEmail ->
                    val pulled = runCatching { syncVm.restoreFromDrive() }
                        .getOrDefault(emptyMap())
                    val playlistsRestored = pulled[com.aeriotv.android.core.sync.SyncCategory.Playlists] == true
                    val activated = if (playlistsRestored) {
                        runCatching { vm.loadActivePlaylistIfAvailable() }.getOrDefault(false)
                    } else false
                    val message = when {
                        activated -> "Signed in as ${signedInEmail.orEmpty()}. Restoring your data..."
                        signedInEmail != null -> "Signed in as $signedInEmail. No playlists in Drive yet -- set one up below."
                        else -> "Signed in. No playlists in Drive yet."
                    }
                    android.widget.Toast.makeText(
                        context,
                        message,
                        if (activated) android.widget.Toast.LENGTH_SHORT else android.widget.Toast.LENGTH_LONG,
                    ).show()
                }

                val consentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
                ) { result ->
                    syncVm.acceptConsentResult(result.data)
                    scope.launch {
                        tryRestoreAndAdvance(null)
                        googleSignInInFlight = false
                    }
                }

                LaunchedEffect(state.phase) {
                    if (state.phase == PlaylistViewModel.Phase.ChannelsReady) {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.PLAYLIST_GRAPH) { inclusive = false }
                        }
                    }
                }

                WelcomeScreen(
                    onConnectServer = { navController.navigate(Routes.CHOOSE_TYPE) },
                    // "Skip for now" is iOS parity. With no playlist saved the channel
                    // list is empty; user can reach Settings -> Change playlist later.
                    onSkip = {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.PLAYLIST_GRAPH) { inclusive = false }
                        }
                    },
                    googleSignInInProgress = googleSignInInFlight,
                    onSignInWithGoogle = {
                        if (!syncBuildConfigured) {
                            welcomeNotConfiguredDialog = true
                            return@WelcomeScreen
                        }
                        val activity = context.findActivity()
                        if (activity == null) {
                            android.widget.Toast.makeText(
                                context,
                                "Sign-in needs a foreground activity. Try again.",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            return@WelcomeScreen
                        }
                        googleSignInInFlight = true
                        scope.launch {
                            val email = syncVm.signInWithGoogle(activity)
                            if (email == null) {
                                googleSignInInFlight = false
                                android.widget.Toast.makeText(
                                    context,
                                    "Sign-in cancelled or failed.",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                return@launch
                            }
                            when (val driveResult = syncVm.requestDriveScope()) {
                                is com.aeriotv.android.core.sync.DriveSyncManager.RequestResult.Authorized -> {
                                    tryRestoreAndAdvance(email)
                                    googleSignInInFlight = false
                                }
                                is com.aeriotv.android.core.sync.DriveSyncManager.RequestResult.NeedsConsent -> {
                                    consentLauncher.launch(
                                        androidx.activity.result.IntentSenderRequest.Builder(driveResult.intentSender).build(),
                                    )
                                }
                                com.aeriotv.android.core.sync.DriveSyncManager.RequestResult.Failed, null -> {
                                    googleSignInInFlight = false
                                    android.widget.Toast.makeText(
                                        context,
                                        "Drive authorization failed.",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    },
                )

                if (welcomeNotConfiguredDialog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { welcomeNotConfiguredDialog = false },
                        title = { androidx.compose.material3.Text("Drive Sync isn't set up yet") },
                        text = {
                            androidx.compose.material3.Text(
                                "This AerioTV build doesn't have a Google Cloud OAuth Web " +
                                    "Client ID baked in. Add GOOGLE_DRIVE_WEB_CLIENT_ID to " +
                                    "local.properties (and register this APK's signing-cert " +
                                    "SHA-1 in the same Cloud project) before Sign in with " +
                                    "Google can load.",
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { welcomeNotConfiguredDialog = false },
                            ) { androidx.compose.material3.Text("Got it") }
                        },
                        containerColor = MaterialTheme.colorScheme.surface,
                    )
                }
            }

            composable(Routes.CHOOSE_TYPE) { entry ->
                ChooseSourceTypeScreen(
                    onBack = { navController.popBackStack() },
                    onChoose = { type ->
                        navController.navigate(Routes.configure(type))
                    },
                )
            }

            composable(
                route = Routes.CONFIGURE,
                arguments = listOf(navArgument("type") { type = NavType.StringType }),
            ) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()
                val typeName = entry.arguments?.getString("type") ?: SourceType.M3uUrl.name
                val resolvedType = runCatching { SourceType.valueOf(typeName) }
                    .getOrElse { SourceType.M3uUrl }

                LaunchedEffect(state.phase) {
                    if (state.phase == PlaylistViewModel.Phase.ChannelsReady) {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.PLAYLIST_GRAPH) { inclusive = false }
                        }
                    }
                }

                ConfigureSourceScreen(
                    sourceType = resolvedType,
                    onBack = { navController.popBackStack() },
                    viewModel = vm,
                )
            }

            composable(Routes.MAIN) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()
                val context = androidx.compose.ui.platform.LocalContext.current

                LaunchedEffect(state.phase) {
                    if (state.phase == PlaylistViewModel.Phase.NeedsUrl) {
                        navController.navigate(Routes.WELCOME) {
                            popUpTo(Routes.MAIN) { inclusive = true }
                        }
                    }
                }

                // Audit task #47: resolve any pending aeriotv:// deep link
                // once channels are ready. We deliberately wait until the
                // playlist has loaded -- launching the player route before
                // the channel exists in state.channels would show a stale
                // / missing card. For Vod, we navigate to movie detail
                // without needing the OnDemand state to be loaded; the
                // detail screen has its own resolver.
                LaunchedEffect(deepLinkTarget, state.channels.size) {
                    val target = deepLinkTarget ?: return@LaunchedEffect
                    when (target) {
                        is DeepLinkTarget.Channel -> {
                            val exists = state.channels.any {
                                it.id == target.channelId && it.url.isNotBlank()
                            }
                            if (exists) {
                                navController.navigate(Routes.player(target.channelId))
                                onDeepLinkConsumed()
                            } else if (state.phase == PlaylistViewModel.Phase.ChannelsReady) {
                                // Channels are loaded but the id isn't here --
                                // playlist might have changed since the deep
                                // link was created. Drop it silently.
                                onDeepLinkConsumed()
                            }
                        }
                        is DeepLinkTarget.Vod -> {
                            navController.navigate(Routes.movieDetail(target.movieUuid))
                            onDeepLinkConsumed()
                        }
                    }
                }

                CompositionLocalProvider(
                    LocalCanRecordToServer provides (state.playlist?.canRecordToServer() ?: false),
                ) {
                MainScaffold(
                    onChannelClick = { channel ->
                        navController.navigate(Routes.player(channel.id))
                    },
                    onMovieClick = { movieUuid ->
                        navController.navigate(Routes.movieDetail(movieUuid))
                    },
                    onSeriesClick = { seriesId ->
                        navController.navigate(Routes.seriesDetail(seriesId))
                    },
                    onEpisodeResume = { videoId ->
                        navController.navigate(Routes.vodEpisodePlayer(videoId))
                    },
                    onPlayRecording = { playbackUrl, title ->
                        navController.navigate(Routes.recordingPlayer(playbackUrl, title))
                    },
                    onLaunchMultiview = {
                        navController.navigate(Routes.MULTIVIEW)
                    },
                    onWatchLive = { dispatcharrChannelId ->
                        // Audit task #50 watch-live: the DVR tab only fires
                        // this on a server-side Recording row whose dispatcharr
                        // channel id maps to a row in the active playlist's
                        // channels (the M3U mapper attaches the int id). When
                        // the lookup misses (e.g. user switched playlists and
                        // recordings refer to channels no longer in the
                        // current source), silently drop.
                        val ch = state.channels.firstOrNull {
                            it.dispatcharrChannelId == dispatcharrChannelId &&
                                it.url.isNotBlank()
                        }
                        if (ch != null) {
                            navController.navigate(Routes.player(ch.id))
                        }
                    },
                    // Cold-launch perf fix (2026-06-05): pass the
                    // PLAYLIST_GRAPH-scoped vm so MainScaffold's default
                    // `hiltViewModel()` doesn't create a SECOND
                    // PlaylistViewModel instance scoped to the MAIN entry.
                    // Two instances each ran init -> bootstrap -> EPG
                    // cache paint + a duplicate xmltv.php fetch, costing
                    // ~700MB of GC churn and ~7 seconds of duplicated
                    // work on cold launch (seen in the method trace).
                    viewModel = vm,
                )
                }
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(navArgument("channelId") { type = NavType.StringType }),
            ) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()
                val channelId = Uri.decode(entry.arguments?.getString("channelId").orEmpty())
                val headers = remember(state.playlist?.apiKey, state.playlist?.sourceType) {
                    val pl = state.playlist
                    val key = pl?.apiKey?.takeIf { it.isNotBlank() }
                    val isDispatcharr = pl?.sourceType == SourceType.DispatcharrApiKey.name ||
                            pl?.sourceType == SourceType.DispatcharrUserPass.name
                    if (isDispatcharr && key != null) {
                        mapOf(
                            "X-API-Key" to key,
                            "Authorization" to "ApiKey $key",
                        )
                    } else emptyMap()
                }
                val playableChannels = state.channels.filter { it.url.isNotBlank() }
                CompositionLocalProvider(
                    LocalCanRecordToServer provides (state.playlist?.canRecordToServer() ?: false),
                ) {
                PlayerScreen(
                    channels = playableChannels,
                    initialChannelId = channelId,
                    isLive = true,
                    httpHeaders = headers,
                    epgByChannel = state.epgByChannel,
                    onClose = { navController.popBackStack() },
                    onLaunchMultiview = {
                        // The now-playing stream is being absorbed into the
                        // multiview grid (PlayerScreen already stopped it), so
                        // pop PLAYER off the stack -- no dead single-stream
                        // route to return to. Back from multiview lands on MAIN.
                        navController.navigate(Routes.MULTIVIEW) {
                            popUpTo(Routes.PLAYER) { inclusive = true }
                        }
                    },
                )
                }
            }

            composable(
                route = Routes.SERIES_DETAIL,
                arguments = listOf(navArgument("seriesId") { type = NavType.IntType }),
            ) { entry ->
                // Scope OnDemandViewModel to the MAIN backstack entry — the
                // detail route is pushed ON TOP of MAIN, so MAIN's entry stays
                // alive underneath, and resolving the VM through that entry
                // gives this screen the SAME instance the On Demand tab
                // populated. Default `hiltViewModel()` here would scope to
                // this nav entry and hand back a fresh empty VM, which is
                // the bug that caused "Series not found" on direct entry.
                val mainEntry = remember(entry) {
                    navController.getBackStackEntry(Routes.MAIN)
                }
                val onDemandVm: OnDemandViewModel = hiltViewModel(mainEntry)
                val seriesId = entry.arguments?.getInt("seriesId") ?: 0
                SeriesDetailScreen(
                    seriesId = seriesId,
                    onBack = { navController.popBackStack() },
                    onEpisodeClick = { episode ->
                        navController.navigate(Routes.vodEpisodePlayer(episode.uuid))
                    },
                    viewModel = onDemandVm,
                )
            }

            composable(
                route = Routes.MOVIE_DETAIL,
                arguments = listOf(navArgument("movieUuid") { type = NavType.StringType }),
            ) { entry ->
                val mainEntry = remember(entry) {
                    navController.getBackStackEntry(Routes.MAIN)
                }
                val onDemandVm: OnDemandViewModel = hiltViewModel(mainEntry)
                val movieUuid = Uri.decode(entry.arguments?.getString("movieUuid").orEmpty())
                com.aeriotv.android.feature.ondemand.MovieDetailScreen(
                    movieUuid = movieUuid,
                    onBack = { navController.popBackStack() },
                    onPlay = { navController.navigate(Routes.vodPlayer(movieUuid)) },
                    viewModel = onDemandVm,
                )
            }

            composable(
                route = Routes.VOD_EPISODE_PLAYER,
                arguments = listOf(navArgument("episodeUuid") { type = NavType.StringType }),
            ) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val playlistVm: PlaylistViewModel = hiltViewModel(parent)
                val playlistState by playlistVm.state.collectAsStateWithLifecycle()
                val onDemandVm: OnDemandViewModel = hiltViewModel()

                val episodeUuid = Uri.decode(entry.arguments?.getString("episodeUuid").orEmpty())

                val apiKey = playlistState.playlist?.apiKey
                val headers = remember(apiKey, playlistState.playlist?.sourceType) {
                    val pl = playlistState.playlist
                    val key = pl?.apiKey?.takeIf { it.isNotBlank() }
                    val isDispatcharr = pl?.sourceType == SourceType.DispatcharrApiKey.name ||
                            pl?.sourceType == SourceType.DispatcharrUserPass.name
                    if (isDispatcharr && key != null) {
                        mapOf(
                            "X-API-Key" to key,
                            "Authorization" to "ApiKey $key",
                        )
                    } else emptyMap()
                }

                // Look up the episode across all cached series for stream-id +
                // title. Cache miss falls back to an untitled play.
                val episode = onDemandVm.state.collectAsStateWithLifecycle().value
                    .episodesBySeries.values
                    .asSequence()
                    .flatten()
                    .firstOrNull { it.uuid == episodeUuid }

                var resolvedUrl by remember(episodeUuid) { mutableStateOf<String?>(null) }
                var resolveError by remember(episodeUuid) { mutableStateOf<String?>(null) }
                LaunchedEffect(episodeUuid) {
                    onDemandVm.resolveEpisodeUrl(episodeUuid, episode?.firstStreamId).fold(
                        onSuccess = { resolvedUrl = it },
                        onFailure = { resolveError = it.message ?: it::class.simpleName },
                    )
                }

                // Series poster fallback for the episode (episodes don't carry
                // their own poster on the wire). Look up which series contains
                // this episode and reuse that show's poster.
                val parentSeriesPoster = onDemandVm.state.collectAsStateWithLifecycle().value
                    .let { st ->
                        val parentSeriesId = st.episodesBySeries.entries
                            .firstOrNull { (_, list) -> list.any { it.uuid == episodeUuid } }
                            ?.key
                        parentSeriesId?.let { id -> st.series.firstOrNull { it.id == id }?.posterUrl }
                    }

                VODPlayerScreen(
                    streamUrl = resolvedUrl.orEmpty(),
                    title = episode?.displayName ?: "Episode",
                    httpHeaders = headers,
                    onClose = { navController.popBackStack() },
                    loadingMessage = resolveError ?: if (resolvedUrl == null) "Loading…" else null,
                    videoId = episodeUuid,
                    posterUrl = parentSeriesPoster,
                )
            }

            composable(Routes.MULTIVIEW) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val playlistVm: PlaylistViewModel = hiltViewModel(parent)
                val playlistState by playlistVm.state.collectAsStateWithLifecycle()
                val headers = remember(playlistState.playlist?.apiKey, playlistState.playlist?.sourceType) {
                    val pl = playlistState.playlist
                    val key = pl?.apiKey?.takeIf { it.isNotBlank() }
                    val isDispatcharr = pl?.sourceType == SourceType.DispatcharrApiKey.name ||
                            pl?.sourceType == SourceType.DispatcharrUserPass.name
                    if (isDispatcharr && key != null) {
                        mapOf(
                            "X-API-Key" to key,
                            "Authorization" to "ApiKey $key",
                        )
                    } else emptyMap()
                }
                MultiviewScreen(
                    onClose = { navController.popBackStack() },
                    httpHeaders = headers,
                )
            }

            composable(
                route = Routes.VOD_PLAYER,
                arguments = listOf(navArgument("movieUuid") { type = NavType.StringType }),
            ) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val playlistVm: PlaylistViewModel = hiltViewModel(parent)
                val playlistState by playlistVm.state.collectAsStateWithLifecycle()
                val onDemandVm: OnDemandViewModel = hiltViewModel()
                val onDemandState by onDemandVm.state.collectAsStateWithLifecycle()

                val movieUuid = Uri.decode(entry.arguments?.getString("movieUuid").orEmpty())
                val movie = onDemandState.movies.firstOrNull { it.uuid == movieUuid }

                val baseUrl = playlistState.playlist?.urlString.orEmpty()
                val apiKey = playlistState.playlist?.apiKey
                val headers = remember(apiKey, playlistState.playlist?.sourceType) {
                    val pl = playlistState.playlist
                    val key = pl?.apiKey?.takeIf { it.isNotBlank() }
                    val isDispatcharr = pl?.sourceType == SourceType.DispatcharrApiKey.name ||
                            pl?.sourceType == SourceType.DispatcharrUserPass.name
                    if (isDispatcharr && key != null) {
                        mapOf(
                            "X-API-Key" to key,
                            "Authorization" to "ApiKey $key",
                        )
                    } else emptyMap()
                }

                var resolvedUrl by remember(movieUuid) { mutableStateOf<String?>(null) }
                var resolveError by remember(movieUuid) { mutableStateOf<String?>(null) }
                LaunchedEffect(movieUuid) {
                    onDemandVm.resolveMovieUrl(movieUuid).fold(
                        onSuccess = { resolvedUrl = it },
                        onFailure = { resolveError = it.message ?: it::class.simpleName },
                    )
                }

                VODPlayerScreen(
                    streamUrl = resolvedUrl.orEmpty(),
                    title = movie?.displayName ?: "On Demand",
                    httpHeaders = headers,
                    onClose = { navController.popBackStack() },
                    loadingMessage = resolveError ?: if (resolvedUrl == null) "Loading…" else null,
                    videoId = movieUuid,
                    posterUrl = movie?.posterUrl,
                )
            }

            // Audit task #43: local DVR recording playback. Takes a raw
            // file:// URL (from LocalRecordingEntity.filePath, encoded in
            // DvrViewModel.Recording.playbackUrl) + a display title.
            // VODPlayerScreen handles the file source natively via mpv;
            // no HTTP headers, no live cache window, no resolveMovieUrl.
            // Server-side recording playback is a follow-up phase.
            composable(
                route = Routes.RECORDING_PLAYER,
                arguments = listOf(
                    navArgument("playbackUrl") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType },
                ),
            ) { entry ->
                val playbackUrl = Uri.decode(entry.arguments?.getString("playbackUrl").orEmpty())
                val title = Uri.decode(entry.arguments?.getString("title").orEmpty())
                // A recording is either a local file:// capture (no auth) or a
                // Dispatcharr server URL that needs the active source's
                // X-API-Key. Resolve the playlist's auth headers like the VOD
                // route and apply them only to remote (http/https) URLs so a
                // file:// recording plays headerless.
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val playlistVm: PlaylistViewModel = hiltViewModel(parent)
                val playlistState by playlistVm.state.collectAsStateWithLifecycle()
                val headers = remember(
                    playlistState.playlist?.apiKey,
                    playlistState.playlist?.sourceType,
                    playbackUrl,
                ) {
                    val pl = playlistState.playlist
                    val key = pl?.apiKey?.takeIf { it.isNotBlank() }
                    val isDispatcharr = pl?.sourceType == SourceType.DispatcharrApiKey.name ||
                            pl?.sourceType == SourceType.DispatcharrUserPass.name
                    val remote = playbackUrl.startsWith("http://", ignoreCase = true) ||
                            playbackUrl.startsWith("https://", ignoreCase = true)
                    if (remote && isDispatcharr && key != null) {
                        mapOf(
                            "X-API-Key" to key,
                            "Authorization" to "ApiKey $key",
                        )
                    } else emptyMap()
                }
                VODPlayerScreen(
                    streamUrl = playbackUrl,
                    title = title.ifBlank { "Recording" },
                    httpHeaders = headers,
                    onClose = { navController.popBackStack() },
                    loadingMessage = null,
                    videoId = playbackUrl,
                    posterUrl = null,
                )
            }
        }
    }
        // In-app reminder banner overlay. Floats over every screen; the bus
        // only surfaces a banner when a reminder fires while foregrounded.
        ReminderBannerHost(
            onOpenChannel = { channelId -> navController.navigate(Routes.player(channelId)) },
            modifier = Modifier.align(Alignment.TopCenter),
        )
        // What's New sheet (Phase 137 / audit #46). Decides internally
        // whether to show based on BuildConfig.VERSION_NAME vs the user's
        // last-dismissed value; first-ever install is seeded silently so
        // the onboarding flow isn't interrupted.
        WhatsNewGate()

        // In-app updater prompt (github flavor; inert no-op on Play). Renders
        // only on the main tabs, after What's New settles, and drives the
        // throttled foreground update check.
        val updateBackStack by navController.currentBackStackEntryAsState()
        UpdateGate(currentRoute = updateBackStack?.destination?.route)

        // Mini-player overlay (Phase 139 / audit #22). The session ViewModel
        // is rooted here so the same instance is visible from PlayerScreen
        // (writes state on its dispose) and from this overlay (reads state
        // and renders). The TV variant draws a top-right video window with a
        // "double-press OK to resume" hint; the phone variant lives inside
        // MainScaffold as a row above the bottom nav.
        val miniPlayerVm: MiniPlayerViewModel = androidx.hilt.navigation.compose.hiltViewModel()
        val miniState by miniPlayerVm.state.collectAsStateWithLifecycle()
        val miniContext = androidx.compose.ui.platform.LocalContext.current
        val miniEntry = remember {
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                miniContext.applicationContext,
                MainScaffoldEntryPoint::class.java,
            )
        }
        val miniExoHolder = remember { miniEntry.exoPlayerHolder() }
        val miniExoWindowState = remember { miniEntry.exoWindowState() }
        // The TV mini is a static chip (Double-press OK to resume).
        // The video continues to be drawn by the activity-lifetime
        // PersistentExoWindow at MainActivity root, which the mini
        // BackHandler dismiss flips into Hidden mode + stop()'s.
        TvMiniPlayerOverlay(
            state = miniState,
            onResume = {
                val resumed = miniPlayerVm.resumeChannel()
                if (resumed != null) {
                    navController.navigate(Routes.player(resumed.id))
                }
            },
            onDismiss = {
                // Mini dismiss: stop the persistent player, hide its
                // window, kill the MediaSessionService. The session +
                // notification go away. Next channel tap goes through
                // a fresh acquireOrCreate path.
                miniPlayerVm.dismiss()
                miniExoWindowState.hide()
                miniExoHolder.stop()
                com.aeriotv.android.core.playback.AerioMediaPlaybackService
                    .stop(miniContext)
            },
        )
        // Double-press D-pad Select event - MainActivity emits into the
        // session's resumeRequests flow; this collects and re-pushes the
        // PLAYER route. Belongs at the NavController scope, hence here.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            miniPlayerVm.session.resumeRequests.collect { channel ->
                android.util.Log.i(
                    "MiniPlayerResume",
                    "NavHost received resume for channel id=${channel.id}; navigating to PLAYER",
                )
                runCatching { navController.navigate(Routes.player(channel.id)) }
                    .onFailure {
                        android.util.Log.e(
                            "MiniPlayerResume",
                            "navController.navigate(PLAYER) threw",
                            it,
                        )
                    }
            }
        }
    }
}

@Composable
private fun BootstrapSplash() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}
