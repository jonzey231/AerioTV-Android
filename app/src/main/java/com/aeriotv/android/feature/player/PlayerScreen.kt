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
    val mpvHolder = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PlayerScreenEntryPoint::class.java,
        ).mpvPlayerHolder()
    }

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
    LaunchedEffect(Unit) {
        PlaybackService.stop(context)
        mpvHolder.setVideoEnabled(true)
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
    androidx.activity.compose.BackHandler {
        miniPlayerVm.showMiniPlayer()
        if (isTvForm) {
            // Persistent-MPV handoff (Phase 163 redo): leave the held MPV
            // alive. AndroidView's onRelease below will call
            // mpvHolder.detach() to remove it from the fullscreen frame;
            // TvMiniPlayerOverlay then re-acquires the SAME view via
            // mpvHolder.acquireOrCreate() and re-parents it into its own
            // frame. The stream never reloads -- the user sees the same
            // decoded frames at a smaller size, matching tvOS.
            //
            // Previously this called mpvHolder.destroy(), which forced
            // the mini to spin up a fresh libmpv instance + reload the
            // URL (3-5s buffer gap, and the subsequent mini visits
            // ANR'd because the Streamer's 32-bit memory budget can't
            // sustain rapid create/destroy cycles).
        } else {
            currentChannel?.let { ch ->
                mpvHolder.setVideoEnabled(false)
                PlaybackService.startBackground(
                    context = context,
                    title = ch.name,
                    subtitle = nowProgramme?.title.orEmpty(),
                    logoUrl = ch.tvgLogo.takeIf { it.isNotBlank() },
                )
            }
        }
        onClose()
    }

    // Chrome + ad-hoc sub-modal state.
    var chromeVisible by remember { mutableStateOf(true) }
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

    var mpvView by remember { mutableStateOf<MPVPlayerView?>(null) }

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

    // Player background MUST be black -- not the app's navy app-background --
    // so:
    //   1. The brief gap between AndroidView mounting and the SurfaceView's
    //      first decoded frame reads as a "video loading" black instead of a
    //      navy rectangle floating in the player chrome. iOS uses pure black
    //      here for the same reason.
    //   2. Non-16:9 streams letterbox to black bars (the rest of the player
    //      surface) rather than navy bars, matching every video player on
    //      every platform.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val configDir = ctx.filesDir.path
                val cacheDir = ctx.cacheDir.path
                val cachingMs = com.aeriotv.android.feature.settings.bufferMillisFor(streamBufferSize)
                val fresh = mpvHolder.view == null
                val view = mpvHolder.acquireOrCreate(
                    context = ctx,
                    caFilePath = "$configDir/cacert.pem",
                    cachingMs = cachingMs,
                    isLive = isLive,
                    httpHeaders = httpHeaders,
                    configDir = configDir,
                    cacheDir = cacheDir,
                )
                if (fresh) {
                    // Observer registration MUST land before playFile.
                    // Earlier attempt to reorder (Phase 79) was reverted
                    // alongside the warmup -- on at least one device the
                    // playFile-first ordering correlated with streams
                    // never producing a first frame. Until that's
                    // properly isolated, keep the known-good order:
                    // observers first, loadfile second.
                    view.mpv.addLogObserver(object : MPV.LogObserver {
                        override fun logMessage(prefix: String, level: Int, text: String) {
                            // DEBUG-only: this fires for every libmpv log line (dozens/sec).
                            // On a passively-cooled Android TV that per-line Log.i churn
                            // measurably adds jank (iOS pulled its mpv log level back to
                            // warn/error for the same reason).
                            if (BuildConfig.DEBUG) Log.i(TAG, "[mpv $prefix/L$level] ${text.trimEnd()}")
                        }
                    })
                    view.mpv.addObserver(object : MPV.EventObserver {
                        // Tap-to-first-frame timing. The user taps a channel
                        // -> view.playFile fires -> MPV starts the loadfile
                        // pipeline -> START_FILE event -> FILE_LOADED event
                        // -> first VIDEO_RECONFIG (first decoded frame ready)
                        // -> PLAYBACK_RESTART (first frame presented). The
                        // PLAYBACK_RESTART offset from START_FILE is the
                        // user-visible "wait time" we're optimizing.
                        var loadStartedAt = 0L
                        override fun eventProperty(property: String) {}
                        override fun eventProperty(property: String, value: Long) {}
                        override fun eventProperty(property: String, value: Boolean) {}
                        override fun eventProperty(property: String, value: String) {}
                        override fun eventProperty(property: String, value: Double) {}
                        override fun eventProperty(property: String, value: MPVNode) {}
                        override fun event(eventId: Int, data: MPVNode) {
                            if (eventId == MPVEvents.START_FILE) {
                                loadStartedAt = android.os.SystemClock.elapsedRealtime()
                            }
                            // Per-event logging is DEBUG-only too (VIDEO_RECONFIG can
                            // fire repeatedly on 4K). Native crashes still surface via
                            // logcat tags (AndroidRuntime/DEBUG/SurfaceFlinger) + tombstones.
                            if (!BuildConfig.DEBUG) return
                            val label = when (eventId) {
                                MPVEvents.START_FILE -> "START_FILE"
                                MPVEvents.FILE_LOADED -> "FILE_LOADED"
                                MPVEvents.END_FILE -> "END_FILE"
                                MPVEvents.VIDEO_RECONFIG -> "VIDEO_RECONFIG"
                                MPVEvents.AUDIO_RECONFIG -> "AUDIO_RECONFIG"
                                MPVEvents.PLAYBACK_RESTART -> "PLAYBACK_RESTART"
                                MPVEvents.SEEK -> "SEEK"
                                MPVEvents.SHUTDOWN -> "SHUTDOWN"
                                else -> "event#$eventId"
                            }
                            if (eventId == MPVEvents.PLAYBACK_RESTART && loadStartedAt > 0) {
                                val ms = android.os.SystemClock.elapsedRealtime() - loadStartedAt
                                Log.i(TAG, "mpv $label (tap-to-first-frame: ${ms}ms)")
                            } else {
                                Log.i(TAG, "mpv $label")
                            }
                        }
                    })
                    if (streamUrl.isNotBlank()) {
                        Log.i(TAG, "Loading initial stream: $streamUrl")
                        view.playFile(streamUrl)
                        mpvHolder.currentChannelId = currentChannel?.id
                    }
                } else if (mpvHolder.currentChannelId != currentChannel?.id && streamUrl.isNotBlank()) {
                    // Resuming on a different channel than what's held — swap.
                    view.playFile(streamUrl)
                    mpvHolder.currentChannelId = currentChannel?.id
                }
                mpvView = view
                view
            },
            onRelease = { view ->
                // Detach from this composition's frame but DON'T destroy MPV.
                // Either the user navigated back (BackHandler started the
                // background service and MPVHolder retains MPV) or the user
                // hit X-close (handled separately, which calls mpvHolder.destroy).
                Log.i(TAG, "PlayerScreen composable released; detaching MPV view")
                mpvView = null
                mpvHolder.detach()
            },
        )

        // Channel-flip side effect — when currentIndex changes (e.g. via swipe),
        // hand the new URL to the live MPV instance. Keeping the instance alive
        // avoids the multi-second native-init cost of recreating it per channel.
        LaunchedEffect(currentIndex) {
            val view = mpvView
            val url = currentChannel?.url
            if (view != null && !url.isNullOrBlank() && currentIndex != initialIndex) {
                Log.i(TAG, "Channel flip -> $url")
                view.playFile(url)
            }
        }

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
            // Explicit X tap = user is done with this channel; clear the mini-player
            // session, destroy the held MPV instance, and stop the background
            // PlaybackService so the notification disappears. System back keeps
            // the session + service alive instead (handled by BackHandler above).
            onClose = {
                miniPlayerVm.dismiss()
                mpvHolder.destroy()
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

    LaunchedEffect(chromeVisible) {
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
}
