package com.aeriotv.android.feature.player

import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import com.aeriotv.android.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.core.pip.PipState
import com.aeriotv.android.core.pip.findActivity
import com.aeriotv.android.feature.livetv.RecordProgramSheet
import com.aeriotv.android.feature.miniplayer.MiniPlayerViewModel
import com.aeriotv.android.feature.multiview.AddToMultiviewSheet
import com.aeriotv.android.feature.playlist.nowPlaying
import com.aeriotv.android.feature.settings.SettingsViewModel
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val TAG = "PlayerScreen"
private const val AUTO_HIDE_MS = 4_000L
private const val SWIPE_THRESHOLD_PX = 120f

/**
 * Live-stream player screen. Hosts the MPV view + chrome overlay. Tap toggles
 * chrome. While chrome is visible, vertical swipe flips to the next/previous
 * channel without leaving the screen, mirroring iOS PlayerView.swift line 686
 * (`appleTVChannelFlip`).
 */
@Composable
fun PlayerScreen(
    channels: List<M3UChannel>,
    initialChannelId: String,
    isLive: Boolean = true,
    httpHeaders: Map<String, String> = emptyMap(),
    epgByChannel: Map<String, List<EPGProgramme>> = emptyMap(),
    onClose: () -> Unit = {},
    onLaunchMultiview: () -> Unit = {},
    onLoadChannelStreams: suspend (Int) -> List<StreamOption> = { emptyList() },
    onSwitchChannelStream: suspend (String, Int) -> String? = { _, _ -> null },
    onLoadCurrentStreamId: suspend (String) -> Int? = { null },
    onLoadCurrentStreamUrl: suspend (String) -> String? = { null },
    /** LAN/WAN verdict-flip signal (LAN URL key) for mid-stream re-tune. */
    onVerdictFlips: kotlinx.coroutines.flow.SharedFlow<String> =
        kotlinx.coroutines.flow.MutableSharedFlow(),
    /** Rebuild this channel's live proxy URL from the current LAN/WAN base. */
    onRebuildLiveUrl: suspend (String) -> String? = { null },
) {
    // Hold the screen awake while the fullscreen player is mounted. Without
    // this the system screen-timeout fires mid-stream after its idle window
    // (Samsung defaults to 30s in dim mode) and the user has to wake the
    // phone to keep watching. Mirrors iOS IdleTimerRefCount.increment() on
    // playback start (MPVPlayerView.swift line 4422). The DisposableEffect
    // inside KeepScreenOnWhilePlaying cleans the flag up automatically when
    // PlayerScreen leaves composition -- mini-player promotion + back-out
    // both trigger the dispose path naturally.
    KeepScreenOnWhilePlaying()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val miniPlayerVm: MiniPlayerViewModel = hiltViewModel()
    val appleTVChannelFlip by settingsVm.appleTVChannelFlip.collectAsStateWithLifecycle(initialValue = true)
    val streamBufferSize by settingsVm.streamBufferSize.collectAsStateWithLifecycle(initialValue = "default")
    val aspectMode by settingsVm.playerAspectMode.collectAsStateWithLifecycle(initialValue = "fit")
    val playerEntry = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PlayerScreenEntryPoint::class.java,
        )
    }
    val exoHolder = remember { playerEntry.exoPlayerHolder() }
    val exoWindowState = remember { playerEntry.exoWindowState() }

    // Channel-flip state. The MPV view stays alive across flips; only the
    // current channel index changes and we call playFile again with the new URL.
    // -1 when the id is not in the list yet (hydration race, or an event
    // channel the playlist refresh re-keyed). NEVER coerce a miss to 0: that
    // silently played channels[0] (the user report: picking World Cup #5200
    // played channel #1). getOrNull(-1) renders the loading state instead,
    // and the remember(channels) below re-resolves when the list lands.
    val initialIndex = remember(channels, initialChannelId) {
        channels.indexOfFirst { it.id == initialChannelId }
    }
    var currentIndex by remember(channels) { mutableIntStateOf(initialIndex) }
    val currentChannel = channels.getOrNull(currentIndex)
    // Focus target that holds D-pad focus during fullscreen playback (chrome
    // hidden) so the remote's up/down reaches the channel-flip handler on TV.
    val playbackFocus = remember { FocusRequester() }
    val nowProgramme by remember(epgByChannel, currentChannel) {
        derivedStateOf { currentChannel?.let { epgByChannel[it.guideMatchKey]?.nowPlaying() } }
    }

    // Persist last-watched channel for the App Behaviors > Resume Last Channel
    // toggle. Writes whenever the user flips to a new channel; AerioTVNavHost
    // reads this once on cold boot to decide whether to auto-launch into the
    // player. Also seeds the mini-player session so a system back can promote
    // it without losing channel context.
    LaunchedEffect(currentChannel?.id) {
        currentChannel?.let { ch ->
            if (ch.id.isNotBlank()) {
                settingsVm.setLastWatchedChannelId(ch.id)
                // Feed the LRU recents list (AddToMultiview "Recent" section,
                // iOS RecentChannelsStore parity).
                settingsVm.recordRecentChannel(ch.id)
            }
            miniPlayerVm.setCurrentChannel(ch)
        }
    }

    // Mount-time hook: if we're resuming from the mini-player the background
    // PlaybackService is still running with the notification surfaced and MPV
    // in audio-only mode. Stop the service (we're foreground again) and flip
    // video output back on.
    //
    // Phase 165: also request the PersistentMpvWindow into Fullscreen mode
    // so the SurfaceView (mounted at MainActivity root) fills the screen
    // beneath our chrome overlays.
    LaunchedEffect(Unit) {
        // Apply Dispatcharr / Xtream auth headers + custom User-Agent
        // before the first setMediaSource so the DataSource picks them
        // up. Replays on every mount so reentering the player after a
        // settings change (e.g. swapping API key) takes effect on the
        // next channel tap.
        exoHolder.httpHeaders = httpHeaders
        exoWindowState.requestFullscreen()
        // LAN/WAN terminal-error failover hook (iOS PlayerSession.failoverRetryCurrent):
        // on a terminal player error the holder asks this to re-probe LAN/WAN and
        // hand back a fresh /proxy/ts/stream/<uuid> URL instead of replaying a
        // possibly dead-host lastPlayUrl. Only Dispatcharr-live channels qualify.
        exoHolder.onTerminalErrorRebuildUrl = {
            currentChannel?.id?.takeIf { it.startsWith("disp:") }
                ?.let { onRebuildLiveUrl(it.removePrefix("disp:")) }
        }
        // Bring up the MediaSessionService so the session is alive
        // before the first frame. Idempotent -- if it's already
        // running this is a no-op.
        com.aeriotv.android.core.playback.AerioMediaPlaybackService
            .startBackground(context)
    }

    // Clear the LAN/WAN failover hook when leaving the player so a backgrounded /
    // Auto session never re-tunes through this screen's closed-over state.
    DisposableEffect(Unit) {
        onDispose { exoHolder.onTerminalErrorRebuildUrl = null }
    }

    // Channel-switch / first-mount setMediaItem: when the held Exo
    // player is on a different channel than the user just selected,
    // swap streams via setMediaSource. The PlayerView at MainActivity
    // root holds the surface across this so no reattach is required.
    LaunchedEffect(currentChannel?.id) {
        val channelId = currentChannel?.id ?: return@LaunchedEffect
        val ch = currentChannel ?: return@LaunchedEffect
        val url = ch.url
        if (url.isBlank()) return@LaunchedEffect
        if (exoHolder.currentChannelId != channelId) {
            Log.i(TAG, "Channel switch on Exo persistent player -> $url")
            // Refresh headers each switch -- some Dispatcharr deployments
            // rotate the API key per stream.
            exoHolder.httpHeaders = httpHeaders
            // Pass title / subtitle / logo to the MediaItem so
            // MediaSessionService renders the right notification +
            // lock-screen art (task #64). The mediaMetadata fields
            // flow through Player.currentMediaItem.mediaMetadata to
            // the session.
            val artworkUri = ch.tvgLogo.takeIf { it.isNotBlank() }
                ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            exoHolder.playUrl(
                url = url,
                title = ch.name,
                subtitle = nowProgramme?.title.orEmpty(),
                artworkUri = artworkUri,
            )
            exoHolder.currentChannelId = channelId
        }
    }

    // System back intercept. Two flavours:
    //   - Phone: promote to the bottom MiniPlayerRow above the nav, kill
    //     video output (vid=no -> mpv folds vo=null) to free the GPU, and
    //     start the foreground PlaybackService so audio survives the
    //     activity going to background. The held MPV stays running.
    //   - TV: keep video enabled (the TvMiniPlayerOverlay shows the live
    //     stream in a top-right window, tvOS PlayerSession parity). Toggling
    //     vid off here would force vo=null and the mini would be black even
    //     after re-attaching the surface. PlaybackService isn't needed
    //     either because the app stays foregrounded behind the mini.
    val isTvForm = (
        context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_TYPE_MASK
        ) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

    // GH #7 (True Android Fullscreen): on phone/tablet, hide the status + nav
    // bars in LANDSCAPE so playback is genuinely edge-to-edge instead of
    // letterboxed under the system chrome the rest of the app draws (we run
    // enableEdgeToEdge app-wide). In PORTRAIT we keep the status bar so the top
    // control banner never slides under a camera cutout: in portrait the OS
    // always reserves the cutout area with the status bar, which is reliable on
    // every device, whereas the DisplayCutout inset is not always exposed to
    // apps (e.g. the Samsung Z Fold cover screen reports none). The banners also
    // pad by statusBars union displayCutout. Keyed on orientation so a rotation
    // re-applies the right mode; bars restored on dispose. TV has no system bars.
    if (!isTvForm) {
        val activity = context.findActivity()
        val isLandscape = LocalConfiguration.current.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        DisposableEffect(activity, isLandscape) {
            val window = activity?.window
            val controller = window?.let {
                androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
            }
            controller?.apply {
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (isLandscape) {
                    hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                } else {
                    show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose {
                controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // UHD judder fix (seamless content frame-rate matching) now lives in
    // PersistentExoWindow, which owns the SurfaceView whose Surface the
    // refresh-rate match is requested on. The old window-level
    // preferredDisplayModeId pin lived HERE but was a NON-seamless HDMI
    // re-handshake that destroyed the SurfaceView mid-stream and blacked out
    // the video on TV boxes (GOTCHA 23) -- replaced with the seamless
    // Surface.setFrameRate path.

    // Chrome auto-starts visible on phone (user can immediately reach
    // controls + close) but hidden on TV (the user just pressed a
    // channel and wants to watch -- 1st Back press surfaces chrome,
    // tvOS-style). See BackHandler below for the three-press TV flow.
    // Declared HERE (above BackHandler) so the BackHandler closure
    // can read + mutate it.
    var chromeVisible by remember { mutableStateOf(!isTvForm) }
    // Last user interaction timestamp. Bumped on D-pad presses while
    // chrome is visible so the auto-hide timer re-arms instead of firing
    // mid-traversal. Phase 172.
    var lastInteractionAt by remember { mutableStateOf(0L) }
    androidx.activity.compose.BackHandler {
        if (isTvForm) {
            // tvOS-style 3-press back flow (Archie spec 2026-05-28):
            //   1st Back from fullscreen, chrome hidden  -> show chrome
            //   2nd Back (chrome now visible)            -> exit to mini
            //   3rd Back (mini Active, see TvMiniPlayerOverlay)  -> close mini
            //
            // Phase 165 persistent-SurfaceView mini path: flip the
            // root-level PersistentMpvWindow into Mini mode (210x118
            // top-right), promote MiniPlayerSession to Active so the
            // hint chip renders. The SurfaceView never reparents -- only
            // its size + position. No reload, no ANR, no fresh-handle
            // race. The stream just keeps playing.
            if (!chromeVisible) {
                chromeVisible = true
            } else {
                exoWindowState.requestMini()
                miniPlayerVm.showMiniPlayer()
                onClose()
            }
        } else {
            // Phone Back: promote to bottom-bar audio-only mini chip,
            // hide the persistent video window, keep playback going
            // via the AerioMediaPlaybackService. The MediaItem metadata
            // already carries title / subtitle / logo (set in the
            // channel-switch LaunchedEffect above) so the service's
            // notification renders correctly the moment we
            // foreground it.
            miniPlayerVm.showMiniPlayer()
            currentChannel?.let { _ ->
                exoWindowState.hide()
                com.aeriotv.android.core.playback.AerioMediaPlaybackService
                    .startBackground(context)
            }
            onClose()
        }
    }

    // Chrome + ad-hoc sub-modal state.
    // chromeVisible declared above (before the BackHandler).

    // Launch-hint: the info pill appears briefly when the user just opened
    // a channel, then auto-hides. Independent of chromeVisible so the
    // initial "what am I watching" hint doesn't drag the rest of the
    // chrome (close button, control bar, dim scrim) with it. After the
    // first auto-hide the pill follows chromeVisible (i.e. the Back-press
    // path surfaces it alongside the full chrome).
    var launchHintActive by remember { mutableStateOf(true) }
    LaunchedEffect(currentChannel?.id) {
        // Re-arm whenever the user channel-flips to a new id.
        launchHintActive = true
        kotlinx.coroutines.delay(AUTO_HIDE_MS)
        launchHintActive = false
    }
    val pillVisible = chromeVisible || launchHintActive
    var audioOnly by remember { mutableStateOf(false) }
    var recordTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var streamInfo by remember { mutableStateOf<StreamInfoSnapshot?>(null) }
    var subtitles by remember { mutableStateOf<SubtitlesState?>(null) }
    var audioTracks by remember { mutableStateOf<AudioTracksState?>(null) }
    var switchStream by remember { mutableStateOf<SwitchStreamState?>(null) }
    // Marked-current stream id for the Switch Stream sheet. We don't cheaply
    // know the proxy's active stream, so track the last one the user switched
    // to (reset on channel change) to radio-mark it on re-open.
    var switchedStreamId by remember(currentChannel?.id) { mutableStateOf<Int?>(null) }

    // Follow EXTERNAL upstream switches we didn't initiate: a stream changed from
    // the Dispatcharr WebUI Stats page, OR Dispatcharr's automatic server-side
    // failover when the playing stream dies. Both keep our /proxy/ts/stream
    // connection open and only mutate the channel's metadata.url (surfaced by
    // /proxy/ts/status), so the deep live buffer absorbs the splice and ExoPlayer
    // never self-flushes. Poll status.url while steadily playing a Dispatcharr
    // channel in the foreground; on a confirmed divergence re-prime (keepalive-held)
    // onto the new stream. Gated on reachedSteadyPlayback so it can never overlap
    // the cold-start no-data watchdog, and parked during a manual switch / any
    // in-flight re-prime. repeatOnLifecycle(RESUMED) pauses it when backgrounded/PiP.
    val followLifecycleOwner = LocalLifecycleOwner.current
    val isDispatcharrLive = currentChannel?.dispatcharrChannelId != null &&
        currentChannel?.id?.startsWith("disp:") == true
    LaunchedEffect(currentChannel?.id, isDispatcharrLive) {
        if (!isDispatcharrLive) return@LaunchedEffect
        val ch = currentChannel ?: return@LaunchedEffect
        val uuid = ch.id.removePrefix("disp:")
        val proxyUrl = ch.url
        followLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var baseline: String? = null     // last-known status.url for this channel/foreground session
            var backoffMs = 4_000L
            while (isActive) {
                delay(backoffMs)
                if (currentChannel?.id != ch.id) break
                // a manual switch or any re-prime owns the player -> park, re-seed after
                if (switchStream != null || exoHolder.isReprimeInFlight) { baseline = null; continue }
                // only while steady: mutually exclusive with the cold-start no-data watchdog
                if (!exoHolder.reachedSteadyPlayback.value) { baseline = null; continue }
                // OFF the main thread: the GET + JSON parse + auth-retry must never run on
                // Main or it drops frames every tick (a periodic judder with no rebuffer).
                val statusUrl = withContext(Dispatchers.IO) { runCatching { onLoadCurrentStreamUrl(uuid) }.getOrNull() }
                if (statusUrl.isNullOrBlank()) {     // 404 / stopped / transient: back off, never treat as divergence
                    backoffMs = (backoffMs + 4_000L).coerceAtMost(12_000L)
                    continue
                }
                backoffMs = 4_000L
                if (baseline == null) { baseline = statusUrl; continue }   // seed
                if (statusUrl != baseline) {
                    // confirm with one re-read so a momentary mid-switch blip can't trip us
                    val confirm = withContext(Dispatchers.IO) { runCatching { onLoadCurrentStreamUrl(uuid) }.getOrNull() }
                    if (confirm != statusUrl) continue
                    if (switchStream != null || exoHolder.isReprimeInFlight ||
                        !exoHolder.reachedSteadyPlayback.value || currentChannel?.id != ch.id) continue
                    android.util.Log.w(
                        "DispatcharrSwitch",
                        "[FOLLOW] external switch ch=${ch.id} re-priming onto $statusUrl",
                    )
                    val ran = exoHolder.reprimeWithKeepalive(
                        url = proxyUrl,
                        title = ch.name,
                        subtitle = nowProgramme?.title.orEmpty(),
                        artworkUri = ch.tvgLogo.takeIf { it.isNotBlank() }
                            ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() },
                    )
                    if (ran) baseline = statusUrl    // adopt new baseline only after a real re-prime
                }
            }
        }
    }

    // Mid-stream LAN/WAN re-tune failover (iOS PlayerSession.retuneCurrentToActiveURL,
    // commit e6ca1d207). When the reachability probe flips a verdict while a
    // Dispatcharr-live channel is playing (the leaving-home-WiFi / WiFi-drop
    // case), rebuild the /proxy/ts/stream/<uuid> URL from the now-reachable base
    // and re-prime onto it (keepalive-held) instead of freezing on the dead host
    // and waiting for the watchdog to replay the stale lastPlayUrl. Scoped to the
    // RESUMED player; parked during a manual switch or any in-flight re-prime.
    LaunchedEffect(currentChannel?.id, isDispatcharrLive) {
        if (!isDispatcharrLive) return@LaunchedEffect
        val ch = currentChannel ?: return@LaunchedEffect
        val uuid = ch.id.removePrefix("disp:")
        followLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            onVerdictFlips.collect {
                if (currentChannel?.id != ch.id) return@collect
                if (switchStream != null || exoHolder.isReprimeInFlight) return@collect
                val newUrl = withContext(Dispatchers.IO) {
                    runCatching { onRebuildLiveUrl(uuid) }.getOrNull()
                } ?: return@collect
                // Only re-prime when the base actually changed; a no-op flip
                // (same host) costs nothing (mirrors iOS "primary != current" guard).
                if (newUrl == exoHolder.currentPlayUrl) return@collect
                if (currentChannel?.id != ch.id || switchStream != null ||
                    exoHolder.isReprimeInFlight) return@collect
                Log.w(TAG, "[RETUNE] LAN/WAN flip -> re-priming ch=${ch.id} onto $newUrl")
                exoHolder.reprimeWithKeepalive(
                    url = newUrl,
                    title = ch.name,
                    subtitle = nowProgramme?.title.orEmpty(),
                    artworkUri = ch.tvgLogo.takeIf { it.isNotBlank() }
                        ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() },
                    bypassCooldown = true,
                )
            }
        }
    }
    var playbackSpeedSheet by remember { mutableStateOf<Float?>(null) }
    var multiviewPickerOpen by remember { mutableStateOf(false) }
    // True while the chrome's Options menu or Sleep sheet is open; pauses the
    // auto-hide timer so the chrome does not fade mid-interaction.
    var chromeMenuOpen by remember { mutableStateOf(false) }

    // Sleep timer: stores the wall-clock millis at which the player should close.
    var sleepEndsAt by remember { mutableStateOf<Long?>(null) }
    var sleepRemainingMillis by remember { mutableStateOf<Long?>(null) }

    // Phase 165: mpvView is now derived from mpvHolder.view (single
    // persistent View at root). Kept as a local val inside the chrome
    // Box below for parity with the old factory-captured reference.

    // Publish playback state for the activity's leave-the-app handling: video
    // (not audio-only) auto-enters PiP; audio-only instead keeps a background
    // media notification (no PiP). Cleared when the player leaves composition.
    DisposableEffect(audioOnly, currentChannel?.id, nowProgramme?.title) {
        PipState.nowPlayingTitle = currentChannel?.name ?: "AerioTV"
        PipState.nowPlayingSubtitle = nowProgramme?.title.orEmpty()
        PipState.nowPlayingLogo = currentChannel?.tvgLogo?.takeIf { it.isNotBlank() }
        PipState.videoPlaybackActive.value = !audioOnly
        PipState.audioPlaybackActive.value = audioOnly
        onDispose {
            PipState.videoPlaybackActive.value = false
            PipState.audioPlaybackActive.value = false
        }
    }

    val streamUrl = currentChannel?.url.orEmpty()

    // Dead-upstream net: the holder flips this true when a freshly-tuned live
    // stream produced no data even after a reconnect (AerioExoPlayerHolder
    // no-data watchdog). Drives the "Channel unavailable" overlay below instead
    // of an endless black screen. A channel flip / re-tap clears it.
    val streamUnavailable by exoHolder.streamUnavailable.collectAsStateWithLifecycle()

    // Returning to the foreground player must always restore video unless the
    // user explicitly chose Audio Only. A media-session controller (or the old
    // car-audio path) could have disabled the video track while we were
    // backgrounded; without this the user came back to a black screen with
    // sound and the Audio Only toggle's state did not match the real track
    // (user report). audioOnly stays the single source of truth.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, audioOnly) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exoHolder.setVideoTrackEnabled(!audioOnly)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The video PlayerView is mounted at MainActivity root via
    // PersistentExoWindow (state-driven Fullscreen / Mini / Hidden).
    // Our chrome (controls, tap-target, dim, sheets) renders ABOVE
    // it automatically because the surface uses Android's default
    // SurfaceView z-order (window UI layer above the dedicated
    // surface). Box below is the chrome canvas -- transparent
    // background so the video shows through.
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Phase 172: bump lastInteractionAt on every key event while
            // chrome is visible so the auto-hide timer keeps re-arming.
            // onPreviewKeyEvent returns false -> doesn't consume; the
            // event still reaches the chrome buttons below.
            .onPreviewKeyEvent { event ->
                if (chromeVisible) {
                    lastInteractionAt = android.os.SystemClock.uptimeMillis()
                }
                // Android TV: D-pad up/down flips channels during fullscreen
                // playback (tvOS Siri Remote parity). Matches the touch swipe
                // (up = next). Gated to chrome-hidden so up/down still moves
                // focus through the chrome controls while it is showing.
                if (isTvForm && appleTVChannelFlip && !chromeVisible &&
                    channels.size >= 2 && event.type == KeyEventType.KeyDown
                ) {
                    val delta = when (event.key) {
                        Key.DirectionUp -> 1
                        Key.DirectionDown -> -1
                        else -> 0
                    }
                    if (delta != 0 && currentIndex >= 0 && channels.isNotEmpty()) {
                        val next = (currentIndex + delta).coerceIn(0, channels.lastIndex)
                        if (next != currentIndex) {
                            currentIndex = next
                            chromeVisible = true
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                false
            },
    ) {

        // Transparent tap-target above the video to toggle chrome. Vertical drag
        // on the same layer (while chrome is visible) flips to next/prev channel.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(playbackFocus)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { chromeVisible = !chromeVisible }
                .pointerInput(channels.size, chromeVisible, appleTVChannelFlip) {
                    if (!chromeVisible || !appleTVChannelFlip || channels.size < 2) return@pointerInput
                    var totalDy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { totalDy = 0f },
                        onDragEnd = {
                            val abs = abs(totalDy)
                            if (abs > SWIPE_THRESHOLD_PX && currentIndex >= 0 && channels.isNotEmpty()) {
                                val direction = if (totalDy < 0f) +1 else -1
                                val next = (currentIndex + direction)
                                    .coerceIn(0, channels.lastIndex)
                                if (next != currentIndex) {
                                    currentIndex = next
                                    chromeVisible = true
                                }
                            }
                            totalDy = 0f
                        },
                        onVerticalDrag = { _, dy -> totalDy += dy },
                    )
                },
        )

        // Dead-upstream net: the holder's no-data watchdog reconnected once and
        // still got zero bytes, so it flagged the channel unavailable + stopped.
        // Show a clear message instead of an endless black screen. No focusable
        // surface (no background pointer handler) so the chrome toggle + D-pad
        // channel flip below still work; a flip / re-tap clears the flag.
        if (streamUnavailable) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Channel unavailable",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Text(
                    text = "This stream isn't responding right now. Press Back to exit, " +
                        "or use the D-pad up/down to change channels.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                )
            }
        }

        PlayerChromeOverlay(
            channel = currentChannel,
            nowProgramme = nowProgramme,
            chromeVisible = chromeVisible,
            pillVisible = pillVisible,
            isTv = isTvForm,
            // Explicit X tap = user is done with this channel; clear the mini-player
            // session, destroy the held MPV instance, and stop the background
            // PlaybackService so the notification disappears. System back keeps
            // the session + service alive instead (handled by BackHandler above).
            // Phase 172: stop() rather than destroy() so the persistent
            // SurfaceView mounted at MainActivity root stays alive --
            // the next channel tap plays instantly on the existing
            // handle. destroy() set holder.view=null and the next
            // LaunchedEffect(currentChannel.id) couldn't find a view to
            // playFile on, leaving subsequent channels permanently
            // stuck.
            onClose = {
                miniPlayerVm.dismiss()
                exoWindowState.hide()
                exoHolder.stop()
                com.aeriotv.android.core.playback.AerioMediaPlaybackService
                    .stop(context)
                onClose()
            },
            onAddToMultiview = { multiviewPickerOpen = true },
            onShowRecord = { target -> recordTarget = target },
            onShowStreamInfo = {
                streamInfo = exoHolder.player?.captureStreamInfo() ?: StreamInfoSnapshot(
                    videoLines = listOf("(player not ready)"),
                    audioLines = emptyList(),
                    cacheLines = emptyList(),
                    syncLines = emptyList(),
                )
            },
            onShowSwitchStream = {
                val ch = currentChannel ?: return@PlayerChromeOverlay
                val chPk = ch.dispatcharrChannelId ?: return@PlayerChromeOverlay
                val uuid = ch.id.removePrefix("disp:")
                scope.launch {
                    val streams = onLoadChannelStreams(chPk)
                    // Prefer the in-session selection for the radio mark: after an
                    // event-apply switch the server leaves /proxy/ts/status's stream_id
                    // stale (it only refreshes url), so switchedStreamId is the truthful
                    // "what we last switched to". Fall back to status stream_id when we
                    // haven't switched anything this session (correct on first read).
                    val current = onLoadCurrentStreamId(uuid)
                    switchStream = SwitchStreamState(
                        streams = streams,
                        currentStreamId = switchedStreamId ?: current,
                    )
                }
            },
            onShowSubtitles = {
                val player = exoHolder.player ?: return@PlayerChromeOverlay
                subtitles = SubtitlesState(
                    tracks = player.readSubtitleTracks(),
                    currentSid = player.readCurrentSid(),
                )
            },
            onShowAudioTracks = {
                val player = exoHolder.player ?: return@PlayerChromeOverlay
                audioTracks = AudioTracksState(
                    tracks = player.readAudioTracks(),
                    currentAid = player.readCurrentAid(),
                )
            },
            onShowPlaybackSpeed = {
                val player = exoHolder.player ?: return@PlayerChromeOverlay
                playbackSpeedSheet = player.readSpeed()
            },
            aspectModeLabel = when (aspectMode) {
                "zoom" -> "Zoom"
                "fill" -> "Fill"
                else -> "Fit"
            },
            onCycleAspect = { settingsVm.cyclePlayerAspectMode(aspectMode) },
            onToggleAudioOnly = {
                audioOnly = !audioOnly
                val player = exoHolder.player
                if (audioOnly) {
                    // Disable the video track on the current selection.
                    // The audio renderer keeps running -- this is the
                    // Media3 equivalent of libmpv `vid=no` without the
                    // need to reload the stream when toggling back.
                    player?.trackSelectionParameters = player?.trackSelectionParameters
                        ?.buildUpon()
                        ?.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                        ?.build() ?: return@PlayerChromeOverlay
                } else {
                    player?.trackSelectionParameters = player?.trackSelectionParameters
                        ?.buildUpon()
                        ?.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                        ?.build() ?: return@PlayerChromeOverlay
                }
            },
            audioOnly = audioOnly,
            onSetSleepMinutes = { minutes ->
                sleepEndsAt = if (minutes == 0) null else System.currentTimeMillis() + minutes * 60_000L
            },
            sleepRemainingMillis = sleepRemainingMillis,
            onInteractingChange = { chromeMenuOpen = it },
        )
    }

    // Auto-hide chrome after AUTO_HIDE_MS of inactivity. Phase 172:
    // re-arms whenever the user interacts (lastInteractionAt advances),
    // so D-pad navigation through the chrome buttons resets the timer
    // instead of letting it fire mid-traversal. The key is
    // lastInteractionAt -- changing that restarts the LaunchedEffect.
    // While a menu or sheet is open, pause the auto-hide so the chrome (and the
    // open panel) stay put while the user interacts. tvOS keeps the Options
    // panel up until it is dismissed.
    val interactionLocked = chromeMenuOpen || recordTarget != null || streamInfo != null ||
        subtitles != null || audioTracks != null || playbackSpeedSheet != null ||
        switchStream != null || multiviewPickerOpen
    LaunchedEffect(chromeVisible, lastInteractionAt, interactionLocked) {
        if (chromeVisible && !interactionLocked) {
            delay(AUTO_HIDE_MS)
            chromeVisible = false
        }
    }

    // On TV, when the chrome hides (fullscreen video), pull D-pad focus to the
    // playback surface so the remote's up/down reaches the channel-flip handler
    // instead of being swallowed by a stale focus target.
    LaunchedEffect(chromeVisible, isTvForm) {
        if (isTvForm && !chromeVisible) {
            delay(100)
            runCatching { playbackFocus.requestFocus() }
        }
    }

    LaunchedEffect(sleepEndsAt) {
        val target = sleepEndsAt
        if (target == null) {
            sleepRemainingMillis = null
            return@LaunchedEffect
        }
        while (true) {
            val remaining = target - System.currentTimeMillis()
            if (remaining <= 0L) {
                sleepRemainingMillis = null
                sleepEndsAt = null
                onClose()
                break
            }
            sleepRemainingMillis = remaining
            delay(1_000L)
        }
    }

    recordTarget?.let { target ->
        RecordProgramSheet(
            target = target,
            onDismiss = { recordTarget = null },
        )
    }
    if (multiviewPickerOpen) {
        val multiviewStore = com.aeriotv.android.feature.multiview.rememberMultiviewStoreHandle()
        // Snapshot the pre-open staged set so Cancel restores it EXACTLY
        // (protects any Guide-staged selection from being wiped). Captured at
        // composition time, i.e. BEFORE the seed LaunchedEffect below runs.
        val mvSnapshot = remember { multiviewStore.selected.value }
        val mvSnapshotFocus = remember { multiviewStore.audioFocusedIndex.value }
        // Seed the now-playing channel as Tile 1 (index 0 = audio focus),
        // preserving any already-staged tiles behind it. Keyed on Unit so it
        // runs once per open, never re-clobbering the user's picks.
        LaunchedEffect(Unit) { currentChannel?.let { multiviewStore.seedCurrent(it) } }
        AddToMultiviewSheet(
            currentChannel = currentChannel,
            multiviewStore = multiviewStore,
            onLaunch = {
                multiviewPickerOpen = false
                // Stop the single-stream player BEFORE navigating so only the
                // multiview tiles produce audio (no double-audio). Same teardown
                // as the proven X-close path; onLaunchMultiview() is LAST because
                // it pops PLAYER (this very composable) off the back stack.
                miniPlayerVm.dismiss()
                exoWindowState.hide()
                exoHolder.stop()
                com.aeriotv.android.core.playback.AerioMediaPlaybackService
                    .stop(context.applicationContext)
                onLaunchMultiview()
            },
            onCancel = {
                multiviewPickerOpen = false
                // Restore the exact pre-open selection (NOT clear()), so a
                // Guide-staged set survives an opened-then-cancelled picker.
                multiviewStore.restore(mvSnapshot, mvSnapshotFocus)
            },
            // BACK / scrim / swipe KEEPS whatever the user just toggled (mirrors
            // MultiviewScreen's re-entrant picker). Only the explicit "Cancel"
            // text button (onCancel) reverts to the pre-open snapshot. Without
            // this, onDismiss defaulted to onCancel and BACK silently discarded
            // the picks the user added while watching.
            onDismiss = { multiviewPickerOpen = false },
        )
    }
    streamInfo?.let { snapshot ->
        StreamInfoSheet(
            snapshot = snapshot,
            onDismiss = { streamInfo = null },
        )
    }
    subtitles?.let { state ->
        SubtitlesSheet(
            tracks = state.tracks,
            currentTrackId = state.currentSid,
            onSelect = { sid ->
                exoHolder.player?.selectSubtitleTrack(sid)
                subtitles = null
            },
            onDismiss = { subtitles = null },
        )
    }
    audioTracks?.let { state ->
        AudioTracksSheet(
            tracks = state.tracks,
            currentTrackId = state.currentAid,
            onSelect = { aid ->
                exoHolder.player?.selectAudioTrack(aid)
                audioTracks = null
            },
            onDismiss = { audioTracks = null },
        )
    }
    switchStream?.let { state ->
        SwitchStreamSheet(
            streams = state.streams,
            currentStreamId = state.currentStreamId,
            onSelect = { id ->
                val ch = currentChannel
                switchStream = null
                if (ch != null) {
                    switchedStreamId = id
                    val uuid = ch.id.removePrefix("disp:")
                    val proxyUrl = ch.url
                    scope.launch {
                        // --- Why this dance (all verified against Dispatcharr source) ---
                        // change_stream applies the switch to the LIVE session, usually
                        // ASYNCHRONOUSLY (owner:false -> Redis event, applied by the owner
                        // worker). The owner swaps the upstream IN PLACE on the running
                        // stream_manager -- a mid-stream TS discontinuity, no EOF -- and the
                        // deep live buffer (ca07882) absorbs the splice so ExoPlayer's
                        // ProgressiveMediaSource never flushes and won't follow it. To make
                        // it follow we must re-prepare (flush) the same proxy URL. BUT a
                        // bare re-prepare drops our only TCP connection, and with the
                        // server default channel_shutdown_delay=0 that fires stop_channel,
                        // which DELETES channel_stream:{id} and makes the reconnect cold-
                        // resolve to the channel's DEFAULT (first-ordered) stream -- worse
                        // than doing nothing. So:
                        //   1. Confirm the switch actually landed: poll /status until
                        //      status.url == the change_stream url. We gate on URL, never
                        //      stream_id (the event-apply path refreshes metadata.url but
                        //      leaves stream_id stale 20+s, so stream_id false-negatives).
                        //   2. Hold a SECOND AllowAny GET to the same /proxy/ts/stream URL
                        //      open across the re-prime so the channel never drops to 0
                        //      clients -> stream_manager survives -> the reconnect re-attaches
                        //      to the already-switched session instead of cold-resolving.
                        //      Best-effort: if the keepalive can't connect we re-prime anyway.
                        val newUrl = runCatching { onSwitchChannelStream(uuid, id) }.getOrNull()
                        if (newUrl.isNullOrBlank()) {
                            Toast.makeText(context, "Stream switch failed", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        // confirm: poll status.url == target within budgetMs (strict equality)
                        suspend fun confirm(target: String, budgetMs: Long): Boolean {
                            val end = android.os.SystemClock.elapsedRealtime() + budgetMs
                            while (android.os.SystemClock.elapsedRealtime() < end) {
                                if (currentChannel?.id != ch.id || switchedStreamId != id) return false
                                if (onLoadCurrentStreamUrl(uuid) == target) return true
                                delay(150)
                            }
                            return false
                        }
                        var targetUrl = newUrl
                        var confirmed = confirm(newUrl, 6_000L)
                        if (!confirmed) {
                            // Safe retry: re-issue once (may now hit the owner:true direct
                            // path), brief re-confirm. Never re-prime blind on timeout.
                            val u2 = runCatching { onSwitchChannelStream(uuid, id) }.getOrNull()
                            if (!u2.isNullOrBlank()) { targetUrl = u2; confirmed = confirm(u2, 3_000L) }
                        }
                        if (currentChannel?.id != ch.id || switchedStreamId != id) return@launch
                        if (!confirmed) {
                            Toast.makeText(
                                context,
                                "Stream switch not confirmed; staying on current feed",
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@launch
                        }

                        Toast.makeText(context, "Switching stream...", Toast.LENGTH_SHORT).show()
                        // Re-prime onto the switched upstream with a keepalive held across the
                        // flush (see AerioExoPlayerHolder.reprimeWithKeepalive). bypassCooldown:
                        // a user-initiated switch always re-primes, even if an auto-reload or the
                        // follow-poller fired within the shared cooldown window.
                        exoHolder.reprimeWithKeepalive(
                            url = proxyUrl,
                            title = ch.name,
                            subtitle = nowProgramme?.title.orEmpty(),
                            artworkUri = ch.tvgLogo.takeIf { it.isNotBlank() }
                                ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() },
                            bypassCooldown = true,
                        )
                    }
                }
            },
            onDismiss = { switchStream = null },
        )
    }
    playbackSpeedSheet?.let { current ->
        PlaybackSpeedSheet(
            currentSpeed = current,
            onSelect = { speed ->
                exoHolder.player?.applySpeed(speed)
                playbackSpeedSheet = null
            },
            onDismiss = { playbackSpeedSheet = null },
        )
    }

    DisposableEffect(Unit) {
        onDispose { /* AndroidView.onRelease handles native cleanup. */ }
    }
}

private data class SubtitlesState(
    val tracks: List<SubtitleTrack>,
    val currentSid: Int?,
)

private data class AudioTracksState(
    val tracks: List<AudioTrack>,
    val currentAid: Int?,
)

private data class SwitchStreamState(
    val streams: List<StreamOption>,
    val currentStreamId: Int?,
)

/**
 * EntryPoint accessor so this Composable can grab the holder + window
 * state singletons without routing through hiltViewModel (which would
 * create them per-instance).
 *
 * The capture-stream-info / read-tracks / read-speed extension
 * functions that used to live here moved to ExoPlayerReaders.kt
 * (task #66). All chrome callbacks now read from / write to the
 * ExoPlayer directly via those extensions.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlayerScreenEntryPoint {
    fun exoPlayerHolder(): com.aeriotv.android.core.playback.AerioExoPlayerHolder
    fun exoWindowState(): ExoWindowState
}
