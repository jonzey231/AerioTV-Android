package com.aeriotv.android.feature.player

import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
            if (ch.id.isNotBlank()) settingsVm.setLastWatchedChannelId(ch.id)
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

    // System back intercept: promote to the mini-player row above the bottom
    // nav, start the foreground PlaybackService so audio survives the activity
    // backgrounding, and switch MPV to audio-only to free the GPU. The held
    // MPV instance keeps running; AndroidView.onRelease below just detaches
    // the SurfaceView. Tap the mini-player row to resume.
    androidx.activity.compose.BackHandler {
        miniPlayerVm.showMiniPlayer()
        currentChannel?.let { ch ->
            mpvHolder.setVideoEnabled(false)
            PlaybackService.startBackground(
                context = context,
                title = ch.name,
                subtitle = nowProgramme?.title.orEmpty(),
            )
        }
        onClose()
    }

    // Chrome + ad-hoc sub-modal state.
    var chromeVisible by remember { mutableStateOf(true) }
    var audioOnly by remember { mutableStateOf(false) }
    var recordTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var streamInfo by remember { mutableStateOf<StreamInfoSnapshot?>(null) }
    var subtitles by remember { mutableStateOf<SubtitlesState?>(null) }
    var multiviewPickerOpen by remember { mutableStateOf(false) }

    // Sleep timer: stores the wall-clock millis at which the player should close.
    var sleepEndsAt by remember { mutableStateOf<Long?>(null) }
    var sleepRemainingMillis by remember { mutableStateOf<Long?>(null) }

    var mpvView by remember { mutableStateOf<MPVPlayerView?>(null) }

    val streamUrl = currentChannel?.url.orEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
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
                    view.mpv.addLogObserver(object : MPV.LogObserver {
                        override fun logMessage(prefix: String, level: Int, text: String) {
                            Log.i(TAG, "[mpv $prefix/L$level] ${text.trimEnd()}")
                        }
                    })
                    view.mpv.addObserver(object : MPV.EventObserver {
                        override fun eventProperty(property: String) {}
                        override fun eventProperty(property: String, value: Long) {}
                        override fun eventProperty(property: String, value: Boolean) {}
                        override fun eventProperty(property: String, value: String) {}
                        override fun eventProperty(property: String, value: Double) {}
                        override fun eventProperty(property: String, value: MPVNode) {}
                        override fun event(eventId: Int, data: MPVNode) {
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
                            Log.i(TAG, "mpv $label")
                        }
                    })
                    Log.i(TAG, "Loading initial stream: $streamUrl")
                    if (streamUrl.isNotBlank()) {
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
            onToggleAudioOnly = {
                audioOnly = !audioOnly
                mpvView?.mpv?.setPropertyString("vid", if (audioOnly) "no" else "auto")
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

    DisposableEffect(Unit) {
        onDispose { /* AndroidView.onRelease handles native cleanup. */ }
    }
}

private data class SubtitlesState(
    val tracks: List<SubtitleTrack>,
    val currentSid: Int?,
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
