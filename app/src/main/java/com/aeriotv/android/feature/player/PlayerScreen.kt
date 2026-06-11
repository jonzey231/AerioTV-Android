package com.aeriotv.android.feature.player

import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import com.aeriotv.android.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.delay
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
        // Bring up the MediaSessionService so the session is alive
        // before the first frame. Idempotent -- if it's already
        // running this is a no-op.
        com.aeriotv.android.core.playback.AerioMediaPlaybackService
            .startBackground(context)
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
        multiviewPickerOpen
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
