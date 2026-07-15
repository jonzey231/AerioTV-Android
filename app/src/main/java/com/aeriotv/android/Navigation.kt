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
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.core.data.db.entity.canRecordToServer
import com.aeriotv.android.core.data.db.entity.isDispatcharrAdmin
import com.aeriotv.android.ui.LocalCanRecordToServer
import com.aeriotv.android.ui.LocalIsDispatcharrAdmin
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
    fun castReceiver(): com.aeriotv.android.core.cast.AerioCastReceiverController
}

object Routes {
    const val PLAYLIST_GRAPH = "playlist_graph"
    const val BOOTSTRAP = "bootstrap"
    const val WELCOME = "welcome"
    const val CHOOSE_TYPE = "choose_type"
    const val CONFIGURE = "configure/{type}"
    const val MAIN = "main"
    // Task #148 milestone B: optional cs* args put the LIVE player into
    // catch-up mode on TV (phone keeps the recording player, mirroring
    // iPhone vs the tvOS unified player). csEnd <= csStart = not catch-up.
    const val PLAYER = "player/{channelId}?csUrl={csUrl}&csTitle={csTitle}" +
        "&csStart={csStart}&csEnd={csEnd}&csTz={csTz}&csUuid={csUuid}"
    const val VOD_PLAYER = "vod_player/{movieUuid}"
    const val MOVIE_DETAIL = "movie_detail/{movieUuid}"
    const val SERIES_DETAIL = "series_detail/{seriesId}"
    const val VOD_EPISODE_PLAYER = "vod_episode_player/{episodeUuid}"
    const val MULTIVIEW = "multiview"
    const val SEARCH = "search"
    const val RECORDING_PLAYER = "recording_player/{playbackUrl}/{title}?isDvr={isDvr}&fromStart={fromStart}&csStart={csStart}&csEnd={csEnd}&csTz={csTz}&csUuid={csUuid}"

    fun configure(type: SourceType) = "configure/${type.name}"
    fun player(channelId: String) = "player/${Uri.encode(channelId)}"
    /** Task #148 milestone B: the unified TV path - catch-up plays inside
     *  the live PlayerScreen with its shared d-pad scrub. */
    fun playerCatchup(
        channelId: String,
        playbackUrl: String,
        title: String,
        csStart: Long,
        csEnd: Long,
        csTz: String,
        csUuid: String,
    ) = "player/${Uri.encode(channelId)}" +
        "?csUrl=${Uri.encode(playbackUrl)}&csTitle=${Uri.encode(title)}" +
        "&csStart=$csStart&csEnd=$csEnd&csTz=${Uri.encode(csTz)}&csUuid=${Uri.encode(csUuid)}"
    fun vodPlayer(movieUuid: String) = "vod_player/${Uri.encode(movieUuid)}"
    fun movieDetail(movieUuid: String) = "movie_detail/${Uri.encode(movieUuid)}"
    fun seriesDetail(seriesId: Int) = "series_detail/$seriesId"
    fun vodEpisodePlayer(episodeUuid: String) = "vod_episode_player/${Uri.encode(episodeUuid)}"
    fun recordingPlayer(
        playbackUrl: String,
        title: String,
        isDvr: Boolean = false,
        fromStart: Boolean = false,
        // Catch-up (task #136): programme window + panel timezone enable the
        // player's re-tune scrubbing. csEnd <= csStart (defaults) = not catch-up.
        csStart: Long = 0L,
        csEnd: Long = 0L,
        csTz: String = "",
        // Task #149: Dispatcharr channel uuid, non-blank only for NATIVE
        // catch-up sessions - seeks re-mint a session instead of
        // rebuilding an XC wall-clock URL, and close revokes the session.
        csUuid: String = "",
    ) =
        "recording_player/${Uri.encode(playbackUrl)}/${Uri.encode(title)}" +
            "?isDvr=$isDvr&fromStart=$fromStart&csStart=$csStart&csEnd=$csEnd&csTz=${Uri.encode(csTz)}&csUuid=${Uri.encode(csUuid)}"
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
                // Restore-progress screen state (replaces the old silent
                // wait between sign-in success and the auto-advance to MAIN).
                // While visible it REPLACES WelcomeScreen entirely -- an
                // overlay would leave Welcome's buttons focusable underneath
                // on TV. BACK is swallowed inside the progress screen.
                var restoreOverlayVisible by remember { mutableStateOf(false) }
                var restoreActivatedPlaylist by remember { mutableStateOf(false) }
                val restoreSteps by syncVm.restoreSteps.collectAsStateWithLifecycle()
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
                    // Surface the restore-progress screen for the whole pull
                    // + bootstrap stretch. Clear any stale steps first so a
                    // second sign-in attempt doesn't flash the previous run's
                    // settled rows before pullAllTracked resets the list.
                    syncVm.clearRestoreProgress()
                    restoreActivatedPlaylist = false
                    restoreOverlayVisible = true
                    val pulled = runCatching { syncVm.restoreFromDrive() }
                        .getOrDefault(emptyMap())
                    val playlistsRestored = pulled[com.aeriotv.android.core.sync.SyncCategory.Playlists] == true
                    val activated = if (playlistsRestored) {
                        runCatching { vm.loadActivePlaylistIfAvailable() }.getOrDefault(false)
                    } else false
                    if (activated) {
                        // Keep the progress screen up; its Channels & Guide
                        // line now runs until Phase.ChannelsReady trips the
                        // existing auto-advance to MAIN.
                        restoreActivatedPlaylist = true
                    } else {
                        // Nothing to hydrate -- drop back to Welcome, exactly
                        // where the old silent flow left the user.
                        restoreOverlayVisible = false
                        syncVm.clearRestoreProgress()
                    }
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

                // Backstop: if the restored playlist's channel load FAILS
                // (switchToPlaylist surfaces state.error and never reaches
                // ChannelsReady), don't trap the user on a progress screen
                // whose BACK is disabled -- fall back to Welcome, matching
                // where the old silent flow stranded them.
                LaunchedEffect(restoreActivatedPlaylist, state.error, state.phase) {
                    if (restoreOverlayVisible && restoreActivatedPlaylist &&
                        state.phase != PlaylistViewModel.Phase.ChannelsReady &&
                        state.error != null
                    ) {
                        restoreOverlayVisible = false
                        restoreActivatedPlaylist = false
                        syncVm.clearRestoreProgress()
                    }
                }

                if (restoreOverlayVisible) {
                    com.aeriotv.android.feature.onboarding.OnboardingSyncProgressScreen(
                        steps = restoreSteps,
                        channelsState = when {
                            !restoreActivatedPlaylist ->
                                com.aeriotv.android.core.sync.DriveSyncManager.RestoreStepState.Pending
                            state.phase == PlaylistViewModel.Phase.ChannelsReady ->
                                com.aeriotv.android.core.sync.DriveSyncManager.RestoreStepState.Done
                            else ->
                                com.aeriotv.android.core.sync.DriveSyncManager.RestoreStepState.Running
                        },
                    )
                } else WelcomeScreen(
                    onConnectServer = { navController.navigate(Routes.CHOOSE_TYPE) },
                    // "Skip for now" is iOS parity. With no playlist saved the channel
                    // list is empty; user can reach Settings -> Change playlist later.
                    // The flag stops MAIN's NeedsUrl guard from bouncing right back.
                    onSkip = {
                        vm.onboardingSkipped = true
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
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                ChooseSourceTypeScreen(
                    onBack = { navController.popBackStack() },
                    onChoose = { type ->
                        // Start a FRESH draft so the add creates a NEW row and
                        // never edits the active playlist (and so the form can't
                        // carry over the active server's API key).
                        vm.startNewSource(type)
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

                LaunchedEffect(Unit) {
                    // Navigate on a one-shot "configured" EVENT, not on
                    // phase == ChannelsReady: when adding a second playlist the
                    // phase is already ChannelsReady, so a phase-watcher never
                    // re-fires and the add silently succeeds with no advance.
                    vm.sourceConfigured.collect {
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
                    // Skipped onboarding stays in the (empty) app; see
                    // PlaylistViewModel.onboardingSkipped.
                    if (state.phase == PlaylistViewModel.Phase.NeedsUrl && !vm.onboardingSkipped) {
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
                val castReceiverForFlip = remember {
                    dagger.hilt.android.EntryPointAccessors.fromApplication(
                        context.applicationContext, NavEntryPoint::class.java,
                    ).castReceiver()
                }
                LaunchedEffect(deepLinkTarget, state.channels.size) {
                    val target = deepLinkTarget ?: return@LaunchedEffect
                    when (target) {
                        is DeepLinkTarget.Channel -> {
                            val exists = state.channels.any {
                                it.id == target.channelId && it.url.isNotBlank()
                            }
                            if (exists) {
                                // GH #33: a cast channel-flip arrives as a channel
                                // deep link. If this TV is already on the live
                                // player AND acting as a cast receiver, re-tune it
                                // in place (PlayerScreen observes castChannelRequest)
                                // instead of navigating a new player route, which
                                // won't re-prime the persistent surface (device-
                                // verified 2026-07-15). The first cast (on the guide)
                                // still navigates normally.
                                val onPlayer = navController.currentDestination
                                    ?.route?.startsWith("player/") == true
                                if (onPlayer && castReceiverForFlip.isReceivingCast()) {
                                    castReceiverForFlip.requestCastChannel(target.channelId)
                                } else {
                                    navController.navigate(Routes.player(target.channelId))
                                }
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
                        is DeepLinkTarget.GuideProgram -> {
                            // Resolve the channel by guideMatchKey (the search
                            // result carries that key, NOT M3UChannel.id).
                            val exists = state.channels.any {
                                it.guideMatchKey == target.channelId && it.url.isNotBlank()
                            }
                            if (exists) {
                                // Re-emit through the VM so MainScaffold (tab
                                // switch + force guide mode) and GuideScreen
                                // (scroll + focus) both react. selectedGroup is
                                // reset to All inside requestGuideJump.
                                vm.requestGuideJump(target.channelId, target.startMillis)
                                onDeepLinkConsumed()
                            } else if (state.phase == PlaylistViewModel.Phase.ChannelsReady) {
                                onDeepLinkConsumed() // channels loaded, key gone => drop
                            }
                            // else: channels still loading; leave target pending
                            // for the next (deepLinkTarget, channels.size) pass.
                        }
                    }
                }

                // Task #148 milestone B: routing decision for catch-up
                // (TV = unified live player, phone = recording player).
                val isTvDevice = com.aeriotv.android.feature.settings.rememberIsTvDevice()
                CompositionLocalProvider(
                    LocalCanRecordToServer provides (state.playlist?.canRecordToServer() ?: false),
                ) {
                MainScaffold(
                    onChannelClick = { channel ->
                        // launchSingleTop: a rapid double-tap (e.g. of the
                        // Now-Casting mini controller, GH #33) can't stack two
                        // identical player destinations on the back stack.
                        navController.navigate(Routes.player(channel.id)) {
                            launchSingleTop = true
                        }
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
                    // #9: resume an in-progress movie from Continue Watching by
                    // opening its detail (which offers the Resume button).
                    onResumeMovie = { videoId ->
                        navController.navigate(Routes.movieDetail(videoId))
                    },
                    onPlayRecording = { playbackUrl, title ->
                        navController.navigate(Routes.recordingPlayer(playbackUrl, title))
                    },
                    onPlayCatchup = { catchupChannelId, playbackUrl, title, progStart, progEnd, panelTz, channelUuid ->
                        // Task #148 milestone B: TV plays catch-up INSIDE the
                        // live PlayerScreen (unified transport + shared d-pad
                        // scrub, tvOS parity); phone keeps the seekable
                        // recording player (iPhone parity).
                        if (isTvDevice) {
                            navController.navigate(
                                Routes.playerCatchup(
                                    catchupChannelId, playbackUrl, title,
                                    csStart = progStart, csEnd = progEnd,
                                    csTz = panelTz, csUuid = channelUuid,
                                ),
                            )
                        } else {
                            navController.navigate(
                                Routes.recordingPlayer(
                                    playbackUrl, title,
                                    csStart = progStart, csEnd = progEnd, csTz = panelTz,
                                    csUuid = channelUuid,
                                ),
                            )
                        }
                    },
                    onLaunchMultiview = {
                        // Tile spin-up takes seconds on real hardware, inviting
                        // a second OK press; singleTop keeps a double-activation
                        // from stacking two MULTIVIEW entries (first BACK would
                        // then pop the duplicate and "do nothing").
                        navController.navigate(Routes.MULTIVIEW) {
                            launchSingleTop = true
                        }
                    },
                    onWatchLive = { recordingUrl, recTitle, recIsDvr ->
                        // Audit #50 / iOS v1.6.22: watch the in-progress server
                        // recording at the LIVE EDGE via the recording player
                        // (X-API-Key headers). Was wrongly opening the live
                        // channel via Routes.player(ch.id).
                        if (recordingUrl.isNotBlank()) {
                            navController.navigate(
                                Routes.recordingPlayer(recordingUrl, recTitle, isDvr = recIsDvr, fromStart = false)
                            )
                        }
                    },
                    onWatchFromBeginning = { recordingUrl, recTitle, recIsDvr ->
                        // iOS Issue #29 'Watch from Beginning': same URL, start
                        // at window start instead of the live edge.
                        if (recordingUrl.isNotBlank()) {
                            navController.navigate(
                                Routes.recordingPlayer(recordingUrl, recTitle, isDvr = recIsDvr, fromStart = true)
                            )
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
                    onOpenSearch = { navController.navigate(Routes.SEARCH) },
                )
                }
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(
                    navArgument("channelId") { type = NavType.StringType },
                    // Task #148 milestone B: optional catch-up context (TV).
                    navArgument("csUrl") { type = NavType.StringType; defaultValue = "" },
                    navArgument("csTitle") { type = NavType.StringType; defaultValue = "" },
                    navArgument("csStart") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("csEnd") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("csTz") { type = NavType.StringType; defaultValue = "" },
                    navArgument("csUuid") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()
                val channelId = Uri.decode(entry.arguments?.getString("channelId").orEmpty())
                // Task #148 milestone B: catch-up context (blank/0 = live).
                val csUrl = Uri.decode(entry.arguments?.getString("csUrl").orEmpty())
                val csTitle = Uri.decode(entry.arguments?.getString("csTitle").orEmpty())
                val csStart = entry.arguments?.getLong("csStart") ?: 0L
                val csEnd = entry.arguments?.getLong("csEnd") ?: 0L
                val csTz = Uri.decode(entry.arguments?.getString("csTz").orEmpty())
                val csUuid = Uri.decode(entry.arguments?.getString("csUuid").orEmpty())
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
                // UNFILTERED on purpose: dropping blank-url channels here made the
                // id lookup miss for event channels whose stream is not assigned
                // yet, and the old coerce-to-0 then played channels[0]. PlayerScreen
                // resolves by id against the same list the guide shows; its playUrl
                // effect already no-ops on a blank url.
                val playableChannels = state.channels
                CompositionLocalProvider(
                    LocalCanRecordToServer provides (state.playlist?.canRecordToServer() ?: false),
                    LocalIsDispatcharrAdmin provides (state.playlist?.isDispatcharrAdmin() ?: false),
                ) {
                PlayerScreen(
                    channels = playableChannels,
                    initialChannelId = channelId,
                    isLive = true,
                    httpHeaders = headers,
                    epgByChannel = state.epgByChannel,
                    catchupUrl = csUrl,
                    catchupTitle = csTitle,
                    catchupStartMillis = csStart,
                    catchupEndMillis = csEnd,
                    catchupTz = csTz,
                    catchupChannelUuid = csUuid,
                    onRemintCatchup = { uuid, currentUrl, absStartMillis ->
                        vm.remintCatchupSession(uuid, currentUrl, absStartMillis)
                    },
                    onRevokeCatchup = { url -> vm.revokeCatchupSession(url) },
                    onClose = { navController.popBackStack() },
                    onLaunchMultiview = {
                        // The now-playing stream is being absorbed into the
                        // multiview grid (PlayerScreen already stopped it), so
                        // pop PLAYER off the stack -- no dead single-stream
                        // route to return to. Back from multiview lands on MAIN.
                        navController.navigate(Routes.MULTIVIEW) {
                            popUpTo(Routes.PLAYER) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    // Player Options > Switch Stream (Dispatcharr Direct Connect):
                    // list the channel's member streams + their quality, then ask
                    // Dispatcharr to switch the active upstream.
                    onLoadChannelStreams = { channelIntPk ->
                        // Resolve each stream's m3u_account id -> source name so the
                        // sheet shows which M3U each alternate comes from (most users
                        // keep several). One small accounts fetch per sheet open.
                        val m3uNames = vm.loadM3uAccountNames()
                        vm.loadChannelStreams(channelIntPk).map { s ->
                            com.aeriotv.android.feature.player.StreamOption(
                                id = s.id,
                                name = s.name.orEmpty(),
                                resolution = s.resolution,
                                fps = s.sourceFps,
                                bitrateKbps = s.outputBitrateKbps,
                                videoCodec = s.videoCodec,
                                audioCodec = s.audioCodec,
                                sourceName = s.m3uAccount?.let { m3uNames[it] },
                            )
                        }
                    },
                    onSwitchChannelStream = { channelUuid, streamId ->
                        vm.switchChannelStream(channelUuid, streamId).getOrThrow()
                    },
                    onLoadCurrentStreamId = { channelUuid ->
                        vm.loadCurrentStreamId(channelUuid)
                    },
                    onLoadCurrentStreamUrl = { channelUuid ->
                        vm.loadCurrentStreamUrl(channelUuid)
                    },
                    onVerdictFlips = vm.lanVerdictFlips,
                    onRebuildLiveUrl = { channelUuid -> vm.rebuildLiveStreamUrl(channelUuid) },
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
                    // Known For tile in the cast bio dialog: a PLAIN push (no
                    // popUpTo / singleTop) so remote BACK pops the new detail
                    // entry and lands back on this title.
                    onOpenMovie = { uuid -> navController.navigate(Routes.movieDetail(uuid)) },
                    onOpenSeries = { id -> navController.navigate(Routes.seriesDetail(id)) },
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
                    // Same plain Known For push as SERIES_DETAIL above.
                    onOpenMovie = { uuid -> navController.navigate(Routes.movieDetail(uuid)) },
                    onOpenSeries = { id -> navController.navigate(Routes.seriesDetail(id)) },
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
                // Guide-banner entry skips PlayerScreen's launch teardown
                // (PlayerScreen.kt onLaunch), leaving the persistent mini
                // window + its decoder running on top of the tile grid.
                // Dismiss the mini here with the same teardown order.
                val mvMiniPlayerVm: MiniPlayerViewModel = hiltViewModel()
                val mvContext = androidx.compose.ui.platform.LocalContext.current
                LaunchedEffect(Unit) {
                    val mvEntry = dagger.hilt.android.EntryPointAccessors.fromApplication(
                        mvContext.applicationContext,
                        MainScaffoldEntryPoint::class.java,
                    )
                    mvMiniPlayerVm.dismiss()
                    mvEntry.exoWindowState().hide()
                    mvEntry.exoPlayerHolder().stop()
                    com.aeriotv.android.core.playback.AerioMediaPlaybackService
                        .stop(mvContext.applicationContext)
                }
                MultiviewScreen(
                    onClose = { navController.popBackStack() },
                    httpHeaders = headers,
                    // Pass the PLAYLIST_GRAPH-scoped VM so the re-entrant
                    // "Add streams" picker reuses this single instance.
                    playlistVm = playlistVm,
                )
            }

            // Global Search (parity task #41). Resolve the PLAYLIST_GRAPH-scoped
            // PlaylistViewModel so an EPG result reuses the same requestGuideJump
            // SharedFlow that MainScaffold + GuideScreen already collect (switch
            // to Live TV + guide mode + scroll/focus the cell); movie/series
            // results reuse the existing detail routes.
            composable(Routes.SEARCH) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val playlistVm: PlaylistViewModel = hiltViewModel(parent)
                com.aeriotv.android.feature.search.SearchScreen(
                    onBack = { navController.popBackStack() },
                    onEpgResult = { channelKey, startMillis ->
                        playlistVm.requestGuideJump(channelKey, startMillis)
                        navController.popBackStack()
                    },
                    onMovieClick = { uuid -> navController.navigate(Routes.movieDetail(uuid)) },
                    onSeriesClick = { id -> navController.navigate(Routes.seriesDetail(id)) },
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
                    navArgument("isDvr") { type = NavType.BoolType; defaultValue = false },
                    navArgument("fromStart") { type = NavType.BoolType; defaultValue = false },
                    navArgument("csStart") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("csEnd") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("csTz") { type = NavType.StringType; defaultValue = "" },
                    navArgument("csUuid") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val playbackUrl = Uri.decode(entry.arguments?.getString("playbackUrl").orEmpty())
                val title = Uri.decode(entry.arguments?.getString("title").orEmpty())
                val isDvr = entry.arguments?.getBoolean("isDvr") ?: false
                val fromStart = entry.arguments?.getBoolean("fromStart") ?: false
                // A recording / catch-up playback must silence any LIVE session
                // first: entering from the guide's Watch action (or DVR tab)
                // skips PlayerScreen's launch teardown, so the persistent live
                // window + mini-player decoder would keep streaming (and
                // sounding) underneath. Same teardown order as the MULTIVIEW
                // route above.
                val rpMiniPlayerVm: MiniPlayerViewModel = hiltViewModel()
                val rpContext = androidx.compose.ui.platform.LocalContext.current
                LaunchedEffect(Unit) {
                    val rpEntry = dagger.hilt.android.EntryPointAccessors.fromApplication(
                        rpContext.applicationContext,
                        MainScaffoldEntryPoint::class.java,
                    )
                    rpMiniPlayerVm.dismiss()
                    rpEntry.exoWindowState().hide()
                    rpEntry.exoPlayerHolder().stop()
                    com.aeriotv.android.core.playback.AerioMediaPlaybackService
                        .stop(rpContext.applicationContext)
                }
                val csStart = entry.arguments?.getLong("csStart") ?: 0L
                val csEnd = entry.arguments?.getLong("csEnd") ?: 0L
                val csTz = Uri.decode(entry.arguments?.getString("csTz").orEmpty())
                val csUuid = Uri.decode(entry.arguments?.getString("csUuid").orEmpty())
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
                    catchupStartMillis = csStart,
                    catchupEndMillis = csEnd,
                    catchupTz = csTz,
                    catchupChannelUuid = csUuid,
                    onRemintCatchup = { uuid, currentUrl, absStartMillis ->
                        playlistVm.remintCatchupSession(uuid, currentUrl, absStartMillis)
                    },
                    onRevokeCatchup = { url -> playlistVm.revokeCatchupSession(url) },
                    onClose = { navController.popBackStack() },
                    loadingMessage = null,
                    videoId = playbackUrl,
                    posterUrl = null,
                    isDvr = isDvr,
                    startAtLiveEdge = isDvr && !fromStart,
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

        // Live Rewind one-time feature prompt (task #145). Renders only on
        // the main tabs after What's New settles, so prompts never stack.
        com.aeriotv.android.feature.whatsnew.LiveRewindPromptGate(
            currentRoute = updateBackStack?.destination?.route,
        )

        // Mini-player overlay (Phase 139 / audit #22). The session ViewModel
        // is rooted here so the same instance is visible from PlayerScreen
        // (writes state on its dispose) and from this overlay (reads state
        // and renders). The TV variant draws a top-right video window with a
        // "hold Back to resume" hint; the phone variant lives inside
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
        // tvOS-parity mini Back: SINGLE Back expands to fullscreen, DOUBLE
        // Back jumps the guide to the top channel (mini keeps playing). There
        // is no Back-to-close (tvOS only stops via the player's X); the video
        // is drawn by the activity-lifetime PersistentExoWindow at root.
        TvMiniPlayerOverlay(
            state = miniState,
            onResume = {
                val resumed = miniPlayerVm.resumeChannel()
                if (resumed != null) {
                    navController.navigate(Routes.player(resumed.id))
                }
            },
            onJumpToTop = {
                // Mini stays Active; the guide (composed under the mini on the
                // Live TV tab) scrolls + focuses its top channel.
                miniPlayerVm.session.requestGuideTop()
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
