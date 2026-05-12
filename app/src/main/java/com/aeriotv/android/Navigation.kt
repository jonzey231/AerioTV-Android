package com.aeriotv.android

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.feature.main.MainScaffold
import com.aeriotv.android.feature.onboarding.ChooseSourceTypeScreen
import com.aeriotv.android.feature.onboarding.ConfigureSourceScreen
import com.aeriotv.android.feature.onboarding.WelcomeScreen
import com.aeriotv.android.feature.multiview.MultiviewScreen
import com.aeriotv.android.feature.ondemand.OnDemandViewModel
import com.aeriotv.android.feature.ondemand.SeriesDetailScreen
import com.aeriotv.android.feature.player.PlayerScreen
import com.aeriotv.android.feature.player.VODPlayerScreen
import com.aeriotv.android.feature.playlist.PlaylistViewModel

object Routes {
    const val PLAYLIST_GRAPH = "playlist_graph"
    const val BOOTSTRAP = "bootstrap"
    const val WELCOME = "welcome"
    const val CHOOSE_TYPE = "choose_type"
    const val CONFIGURE = "configure/{type}"
    const val MAIN = "main"
    const val PLAYER = "player/{channelId}"
    const val VOD_PLAYER = "vod_player/{movieUuid}"
    const val SERIES_DETAIL = "series_detail/{seriesId}"
    const val VOD_EPISODE_PLAYER = "vod_episode_player/{episodeUuid}"
    const val MULTIVIEW = "multiview"

    fun configure(type: SourceType) = "configure/${type.name}"
    fun player(channelId: String) = "player/${Uri.encode(channelId)}"
    fun vodPlayer(movieUuid: String) = "vod_player/${Uri.encode(movieUuid)}"
    fun seriesDetail(seriesId: Int) = "series_detail/$seriesId"
    fun vodEpisodePlayer(episodeUuid: String) = "vod_episode_player/${Uri.encode(episodeUuid)}"
}

@Composable
fun AerioTVNavHost(
    initialUrl: String? = null,
    initialEpgUrl: String? = null,
    initialApiKey: String? = null,
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.PLAYLIST_GRAPH) {
        navigation(startDestination = Routes.BOOTSTRAP, route = Routes.PLAYLIST_GRAPH) {

            composable(Routes.BOOTSTRAP) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()

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
                            popUpTo(Routes.BOOTSTRAP) { inclusive = true }
                        }
                        PlaylistViewModel.Phase.ChannelsReady -> navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.BOOTSTRAP) { inclusive = true }
                        }
                    }
                }

                BootstrapSplash()
            }

            composable(Routes.WELCOME) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()

                LaunchedEffect(state.phase) {
                    if (state.phase == PlaylistViewModel.Phase.ChannelsReady) {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.WELCOME) { inclusive = true }
                        }
                    }
                }

                WelcomeScreen(
                    onConnectServer = { navController.navigate(Routes.CHOOSE_TYPE) },
                    // "Skip for now" is iOS parity. With no playlist saved the channel
                    // list is empty; user can reach Settings -> Change playlist later.
                    onSkip = {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.WELCOME) { inclusive = true }
                        }
                    },
                )
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
                            popUpTo(Routes.WELCOME) { inclusive = true }
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

                MainScaffold(
                    onChannelClick = { channel ->
                        navController.navigate(Routes.player(channel.id))
                    },
                    onMovieClick = { movieUuid ->
                        navController.navigate(Routes.vodPlayer(movieUuid))
                    },
                    onSeriesClick = { seriesId ->
                        navController.navigate(Routes.seriesDetail(seriesId))
                    },
                )
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
                PlayerScreen(
                    channels = playableChannels,
                    initialChannelId = channelId,
                    isLive = true,
                    httpHeaders = headers,
                    epgByChannel = state.epgByChannel,
                    onClose = { navController.popBackStack() },
                    onLaunchMultiview = {
                        navController.navigate(Routes.MULTIVIEW)
                    },
                )
            }

            composable(
                route = Routes.SERIES_DETAIL,
                arguments = listOf(navArgument("seriesId") { type = NavType.IntType }),
            ) { entry ->
                val seriesId = entry.arguments?.getInt("seriesId") ?: 0
                SeriesDetailScreen(
                    seriesId = seriesId,
                    onBack = { navController.popBackStack() },
                    onEpisodeClick = { episode ->
                        navController.navigate(Routes.vodEpisodePlayer(episode.uuid))
                    },
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
