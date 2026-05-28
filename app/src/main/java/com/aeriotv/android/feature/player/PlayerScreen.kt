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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.playback.MPVPlayerHolder
import com.aeriotv.android.core.pip.PipState
import com.aeriotv.android.core.playback.PlaybackService
import com.aeriotv.android.feature.livetv.RecordProgramSheet
import com.aeriotv.android.feature.miniplayer.MiniPlayerViewModel
import com.aeriotv.android.feature.multiview.AddToMultiviewSheet
import com.aeriotv.android.feature.playlist.nowPlaying
import com.aeriotv.android.feature.settings.SettingsViewModel
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
    val playerEntry = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PlayerScreenEntryPoint::class.java,
        )
    }
    val mpvHolder = remember { playerEntry.mpvPlayerHolder() }
    val mpvWindowState = remember { playerEntry.mpvWindowState() }
    // Media3 migration (task #61). Live TV plays through ExoPlayer. The
    // MPV pair still hangs around for the audio-track / subtitle /
    // playback-speed UI (task #66 ports those readers) -- those calls
    // null-check the View so they degrade to empty lists when MPV
    // is dormant, which it is once we route Live TV through Exo.
    val exoHolder = remember { playerEntry.exoPlayerHolder() }
    val exoWindowState = remember { playerEntry.exoWindowState() }

    // Channel-flip state. The MPV view stays alive across flips; only the
    // current channel index changes and we call playFile again with the new URL.
    val initialIndex = remember(channels, initialChannelId) {
        channels.indexOfFirst { it.id == initialChannelId }.coerceAtLeast(0)
    }
    var currentIndex by remember(channels) { mutableIntStateOf(initialIndex) }
    val currentChannel = channels.getOrNull(currentIndex)
    val nowProgramme by remember(epgByChannel, currentChannel) {
        derivedStateOf { currentChannel?.let { epgByChannel[it.tvgID]?.nowPlaying() } }
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
        PlaybackService.stop(context)
        // Apply Dispatcharr / Xtream auth headers + custom User-Agent
        // before the first setMediaSource so the DataSource picks them
        // up. Replays on every mount so reentering the player after a
        // settings change (e.g. swapping API key) takes effect on the
        // next channel tap.
        exoHolder.httpHeaders = httpHeaders
        // Park the libmpv pair so its surface isn't competing with
        // Exo's for display real estate. The Mpv side is still in the
        // composition tree (task #67 removes it for real); hiding here
        // is enough.
        mpvWindowState.hide()
        exoWindowState.requestFullscreen()
    }

    // Channel-switch / first-mount setMediaItem: when the held Exo
    // player is on a different channel than the user just selected,
    // swap streams via setMediaSource. The PlayerView at MainActivity
    // root holds the surface across this so no reattach is required.
    LaunchedEffect(currentChannel?.id) {
        val channelId = currentChannel?.id ?: return@LaunchedEffect
        val url = currentChannel?.url ?: return@LaunchedEffect
        if (url.isBlank()) return@LaunchedEffect
        if (exoHolder.currentChannelId != channelId) {
            Log.i(TAG, "Channel switch on Exo persistent player -> $url")
            // Refresh headers each switch -- some Dispatcharr deployments
            // rotate the API key per stream.
            exoHolder.httpHeaders = httpHeaders
            exoHolder.playUrl(url)
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
                // Live TV now flips the Exo persistent window into Mini.
                // mpvWindowState stays at Hidden (set in the mount
                // LaunchedEffect) until task #62 ports VOD too.
                exoWindowState.requestMini()
                miniPlayerVm.showMiniPlayer()
                onClose()
            }
        } else {
            // Phone Back: promote to bottom-bar audio-only mini chip,
            // shut the persistent video window, keep playback going via
            // the foreground PlaybackService. Live TV is on Exo so we
            // hide that window; Mpv stays Hidden as it was.
            miniPlayerVm.showMiniPlayer()
            currentChannel?.let { ch ->
                // setVideoEnabled(false) is the libmpv "vid=no" trick.
                // Exo's equivalent (stop rendering video, keep audio):
                // we pause -- the MediaSession + foreground service
                // approach in task #64 will replace this with a
                // proper audio-only renderer setup. For first session,
                // hiding the window is enough; the surface is gone
                // so the GPU stops working.
                exoWindowState.hide()
                mpvWindowState.hide()
                PlaybackService.startBackground(
                    context = context,
                    title = ch.name,
                    subtitle = nowProgramme?.title.orEmpty(),
                    logoUrl = ch.tvgLogo.takeIf { it.isNotBlank() },
                )
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

    // Phase 165: the video SurfaceView is no longer created here. It's
    // mounted at MainActivity root by PersistentMpvWindow and resized via
    // MpvWindowState (requested fullscreen in the LaunchedEffect above).
    // Our chrome (controls, tap-target, dim, sheets) renders ABOVE the
    // root-level video automatically thanks to SurfaceView's default
    // z-order, which composites the surface BELOW the window's main UI
    // layer. The wrapping Box here is the chrome canvas -- transparent
    // background so the persistent video shows through.
    //
    // mpvView is a derived view-into-the-holder so the chrome callbacks
    // (onShowStreamInfo / onShowSubtitles / etc.) keep working.
    val mpvView: MPVPlayerView? = mpvHolder.view
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Phase 172: bump lastInteractionAt on every key event while
            // chrome is visible so the auto-hide timer keeps re-arming.
            // onPreviewKeyEvent returns false -> doesn't consume; the
            // event still reaches the chrome buttons below.
            .onPreviewKeyEvent {
                if (chromeVisible) {
                    lastInteractionAt = android.os.SystemClock.uptimeMillis()
                }
                false
            },
    ) {

        // Transparent tap-target above the video to toggle chrome. Vertical drag
        // on the same layer (while chrome is visible) flips to next/prev channel.
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                            if (abs > SWIPE_THRESHOLD_PX) {
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
                // Live TV is on Exo now; stop the player, hide the
                // PersistentExoWindow. Mpv pair is already hidden by
                // the LaunchedEffect above, but call hide() defensively
                // in case the user hopped here via a not-yet-migrated
                // entry point.
                exoWindowState.hide()
                exoHolder.stop()
                mpvWindowState.hide()
                PlaybackService.stop(context)
                onClose()
            },
            onAddToMultiview = { multiviewPickerOpen = true },
            onShowRecord = { target -> recordTarget = target },
            onShowStreamInfo = {
                streamInfo = mpvView?.captureStreamInfo() ?: StreamInfoSnapshot(
                    videoLines = listOf("(player not ready)"),
                    audioLines = emptyList(),
                    cacheLines = emptyList(),
                    syncLines = emptyList(),
                )
            },
            onShowSubtitles = {
                val view = mpvView ?: return@PlayerChromeOverlay
                subtitles = SubtitlesState(
                    tracks = view.readSubtitleTracks(),
                    currentSid = view.readCurrentSid(),
                )
            },
            onShowAudioTracks = {
                val view = mpvView ?: return@PlayerChromeOverlay
                audioTracks = AudioTracksState(
                    tracks = view.readAudioTracks(),
                    currentAid = view.readCurrentAid(),
                )
            },
            onShowPlaybackSpeed = {
                val view = mpvView ?: return@PlayerChromeOverlay
                playbackSpeedSheet = view.readSpeed()
            },
            onToggleAudioOnly = {
                audioOnly = !audioOnly
                val view = mpvView
                if (audioOnly) {
                    view?.mpv?.setPropertyString("vid", "no")
                } else {
                    // Re-enabling video: on Android, vid=auto alone doesn't bring
                    // the picture back -- libmpv released the video output when vid
                    // went to "no" and doesn't re-bind it to the (still-attached)
                    // SurfaceView. Reloading the current stream in place (the same
                    // path a channel flip uses) re-inits the VO and restores video.
                    view?.mpv?.setPropertyString("vid", "auto")
                    currentChannel?.url?.takeIf { it.isNotBlank() }?.let { view?.playFile(it) }
                }
            },
            audioOnly = audioOnly,
            onSetSleepMinutes = { minutes ->
                sleepEndsAt = if (minutes == 0) null else System.currentTimeMillis() + minutes * 60_000L
            },
            sleepRemainingMillis = sleepRemainingMillis,
        )
    }

    // Auto-hide chrome after AUTO_HIDE_MS of inactivity. Phase 172:
    // re-arms whenever the user interacts (lastInteractionAt advances),
    // so D-pad navigation through the chrome buttons resets the timer
    // instead of letting it fire mid-traversal. The key is
    // lastInteractionAt -- changing that restarts the LaunchedEffect.
    LaunchedEffect(chromeVisible, lastInteractionAt) {
        if (chromeVisible) {
            delay(AUTO_HIDE_MS)
            chromeVisible = false
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
        AddToMultiviewSheet(
            onDismiss = {
                multiviewPickerOpen = false
                onLaunchMultiview()
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
                mpvView?.mpv?.setPropertyString("sid", sid?.toString() ?: "no")
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
                mpvView?.mpv?.setPropertyString("aid", aid.toString())
                audioTracks = null
            },
            onDismiss = { audioTracks = null },
        )
    }
    playbackSpeedSheet?.let { current ->
        PlaybackSpeedSheet(
            currentSpeed = current,
            onSelect = { speed ->
                mpvView?.mpv?.setPropertyString("speed", speed.toString())
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

private fun MPVPlayerView.captureStreamInfo(): StreamInfoSnapshot {
    val m = mpv
    val width = m.getPropertyString("width").orZero()
    val height = m.getPropertyString("height").orZero()
    val fps = m.getPropertyString("estimated-vf-fps").orEmpty()
    val pixFmt = m.getPropertyString("video-params/pixelformat").orEmpty()
    val hwdec = m.getPropertyString("hwdec-current").orEmpty().ifBlank { "no" }
    val videoFmt = m.getPropertyString("video-format").orEmpty()
    val videoCodec = m.getPropertyString("video-codec").orEmpty()

    val audioCodec = m.getPropertyString("audio-codec").orEmpty()
    val audioRate = m.getPropertyString("audio-params/samplerate").orEmpty()
    val audioChannels = m.getPropertyString("audio-params/channels").orEmpty()
    val audioName = m.getPropertyString("audio-codec-name").orEmpty()

    val cacheSecs = m.getPropertyString("demuxer-cache-duration").orEmpty()
    val cacheKbps = m.getPropertyString("cache-speed").orEmpty()

    val avSync = m.getPropertyString("avsync").orEmpty()
    val drops = m.getPropertyString("frame-drop-count").orEmpty()

    val videoLines = buildList {
        if (videoCodec.isNotBlank()) add(videoCodec)
        val dim = "${width}x${height}".takeIf { width.isNotBlank() && height.isNotBlank() }
        listOfNotNull(
            dim,
            fps.takeIf { it.isNotBlank() }?.let { "${it.toDoubleOrNull()?.roundToOneDecimal() ?: it}fps" },
            pixFmt.takeIf { it.isNotBlank() },
        ).joinToString("  ").takeIf { it.isNotBlank() }?.let(::add)
        if (videoFmt.isNotBlank()) add("format $videoFmt")
        add("hwdec: $hwdec")
    }
    val audioLines = buildList {
        if (audioCodec.isNotBlank()) add(audioCodec)
        val tail = listOfNotNull(
            audioRate.takeIf { it.isNotBlank() }?.let { "${it}Hz" },
            audioChannels.takeIf { it.isNotBlank() }?.let { "${it}ch" },
            audioName.takeIf { it.isNotBlank() },
        ).joinToString("  ")
        if (tail.isNotBlank()) add(tail)
    }
    val cacheLines = buildList {
        val secs = cacheSecs.toDoubleOrNull()?.roundToOneDecimal() ?: cacheSecs
        val kbps = cacheKbps.toDoubleOrNull()?.let { (it / 1024).roundToOneDecimal() } ?: cacheKbps
        if (cacheSecs.isNotBlank() || cacheKbps.isNotBlank()) {
            add(listOfNotNull(
                "${secs}s".takeIf { cacheSecs.isNotBlank() },
                "${kbps} kbps".takeIf { cacheKbps.isNotBlank() },
            ).joinToString("  "))
        }
    }
    val syncLines = buildList {
        val asy = avSync.toDoubleOrNull()?.roundToOneDecimal() ?: avSync
        if (avSync.isNotBlank() || drops.isNotBlank()) {
            add(listOfNotNull(
                "${asy}s".takeIf { avSync.isNotBlank() },
                "drops: $drops".takeIf { drops.isNotBlank() },
            ).joinToString("  "))
        }
    }
    return StreamInfoSnapshot(videoLines, audioLines, cacheLines, syncLines)
}

private fun MPVPlayerView.readSubtitleTracks(): List<SubtitleTrack> {
    val m = mpv
    val countStr = m.getPropertyString("track-list/count") ?: return emptyList()
    val count = countStr.toIntOrNull() ?: return emptyList()
    val out = mutableListOf<SubtitleTrack>()
    for (i in 0 until count) {
        val type = m.getPropertyString("track-list/$i/type").orEmpty()
        if (type != "sub") continue
        val id = m.getPropertyString("track-list/$i/id")?.toIntOrNull() ?: continue
        val title = m.getPropertyString("track-list/$i/title").orEmpty()
        val lang = m.getPropertyString("track-list/$i/lang").orEmpty()
        out += SubtitleTrack(id = id, title = title, lang = lang)
    }
    return out
}

private fun MPVPlayerView.readCurrentSid(): Int? {
    val raw = mpv.getPropertyString("sid") ?: return null
    if (raw == "no" || raw == "auto") return null
    return raw.toIntOrNull()
}

/** Sister to [readSubtitleTracks] - audio tracks only. Surfaces codec +
 *  channel layout because audio-track choices on a live stream usually mean
 *  picking between e.g. an AAC stereo English vs an AC3 5.1 spanish rendition. */
private fun MPVPlayerView.readAudioTracks(): List<AudioTrack> {
    val m = mpv
    val countStr = m.getPropertyString("track-list/count") ?: return emptyList()
    val count = countStr.toIntOrNull() ?: return emptyList()
    val out = mutableListOf<AudioTrack>()
    for (i in 0 until count) {
        val type = m.getPropertyString("track-list/$i/type").orEmpty()
        if (type != "audio") continue
        val id = m.getPropertyString("track-list/$i/id")?.toIntOrNull() ?: continue
        val title = m.getPropertyString("track-list/$i/title").orEmpty()
        val lang = m.getPropertyString("track-list/$i/lang").orEmpty()
        val codec = m.getPropertyString("track-list/$i/codec").orEmpty()
        val channels = m.getPropertyString("track-list/$i/demux-channel-count").orEmpty()
            .let { n ->
                when (n.toIntOrNull()) {
                    1 -> "mono"
                    2 -> "stereo"
                    6 -> "5.1"
                    8 -> "7.1"
                    null, 0 -> ""
                    else -> "${n}ch"
                }
            }
        out += AudioTrack(id = id, title = title, lang = lang, codec = codec, channels = channels)
    }
    return out
}

private fun MPVPlayerView.readCurrentAid(): Int? {
    val raw = mpv.getPropertyString("aid") ?: return null
    if (raw == "no" || raw == "auto") return null
    return raw.toIntOrNull()
}

/** mpv `speed` property as a Float. Defaults to 1.0 if the property is
 *  unavailable (e.g. handle not fully attached yet) so the picker shows
 *  Normal as selected rather than no selection. */
private fun MPVPlayerView.readSpeed(): Float =
    mpv.getPropertyString("speed")?.toFloatOrNull() ?: 1.0f

private fun String?.orZero(): String = if (this.isNullOrBlank()) "" else this
private fun Double.roundToOneDecimal(): String = String.format(Locale.US, "%.1f", this)

/**
 * EntryPoint accessor so this Composable can grab the MPV singleton without
 * routing through hiltViewModel (which would create the holder per-instance).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlayerScreenEntryPoint {
    fun mpvPlayerHolder(): MPVPlayerHolder
    fun mpvWindowState(): MpvWindowState
    // Media3 migration (task #61). Live TV reads these; the MPV pair
    // stays for VOD and any legacy paths until tasks #62 / #66.
    fun exoPlayerHolder(): com.aeriotv.android.core.playback.AerioExoPlayerHolder
    fun exoWindowState(): ExoWindowState
}
