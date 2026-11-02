package com.aeriotv.android.feature.player

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import com.aeriotv.android.core.cast.CastControl
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.toInfoTarget
import com.aeriotv.android.feature.cast.CastRemoteSheet
import com.aeriotv.android.feature.main.MainScaffoldEntryPoint
import com.aeriotv.android.feature.main.floatingNavChrome
import com.aeriotv.android.feature.miniplayer.CastMiniController
import com.aeriotv.android.feature.multiview.RADIO_SUBTITLE
import com.aeriotv.android.feature.multiview.RADIO_BUFFERING
import com.aeriotv.android.feature.multiview.RADIO_UNAVAILABLE
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

private const val TAG = "AerioTV"

/**
 * Radio Mode card and remote sheet (phone and tablet, Apple parity): the cast
 * card itself ([CastMiniController] in the cast card's frame) and the full
 * [CastRemoteSheet], driven by the local holder instead of a receiver.
 *
 * Seeking: the Live Rewind buffer keeps rolling while Radio Mode owns the
 * player (RadioMode.switchTo starts it, PlayerScreen's dispose leaves it),
 * so Back / Forward, the scrubber and Go Live work on it exactly as the cast
 * sheet does with a seekable receiver. With Pause and Rewind Live TV off
 * there is no buffer and they keep the dimmed no-window state Cast shows.
 * Options are Apple's Radio set: Switch Stream, Record Current Program,
 * Audio Track, Stream Info (from the local player).
 */
@Composable
fun RadioTransportCard(
    nowProgramme: (M3UChannel) -> EPGProgramme?,
    loadChannelStreams: suspend (Int) -> List<StreamOption>,
    loadCurrentStreamId: suspend (String) -> Int?,
    switchChannelStream: suspend (String, Int) -> Unit,
    recheckSwitchStreamAllowed: suspend (String) -> Boolean,
    isDispatcharrAdmin: Boolean,
    modifier: Modifier = Modifier,
    tabletWidth: Dp? = null,
    /** The caller's row gives the frame (phone, Control a TV button beside it). */
    inRow: Boolean = false,
) {
    val dvrCanRecord = com.aeriotv.android.ui.LocalDvrAccess.current == "manage"
    val context = LocalContext.current
    val radio by RadioMode.state.collectAsStateWithLifecycle()
    val session = radio ?: return
    val channel = session.channel
    val holder = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, MainScaffoldEntryPoint::class.java)
            .exoPlayerHolder()
    }
    // Follow the holder's live instance so a rebuilt player keeps the card's
    // Play/Pause in step with the real paused state.
    val player by holder.playerInstance.collectAsStateWithLifecycle()
    val tsController = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, MainScaffoldEntryPoint::class.java)
            .timeshiftController()
    }
    val tsState by tsController.state.collectAsStateWithLifecycle()
    // The holder's dead-stream flag (the player overlay's "Channel
    // Unavailable" card): the card, the sheet and the notification follow it
    // instead of claiming playback, and Radio retries on the player's 5 s
    // cadence with a Retry Now button in the sheet.
    val unavailable by holder.streamUnavailable.collectAsStateWithLifecycle()
    val lastErrorText by holder.lastErrorText.collectAsStateWithLifecycle()
    var retrySerial by remember { mutableIntStateOf(0) }
    // True from a retry until the stream is READY again: the retry clears
    // the holder flag at once, and "Playing" must not return before audio.
    // Holder-owned so every surface (card, sheet, notification) clears at
    // once when the player reaches READY and plays after a retry.
    val failedId by holder.failedChannel.collectAsStateWithLifecycle()
    val retrying = failedId != null && failedId == channel.id
    var retryCountdown by remember { mutableIntStateOf(0) }
    val retryNow: () -> Unit = {
        Log.i(TAG, "[RADIO] retry ${channel.name}")
        retryCountdown = 0
        retrySerial += 1
        holder.retryUnavailable()
    }
    if (unavailable) {
        LaunchedEffect(retrySerial, channel.id) {
            Log.i(TAG, "[RADIO] channel unavailable ${channel.name}")
            var remaining = 5
            while (remaining > 0) {
                retryCountdown = remaining
                kotlinx.coroutines.delay(1_000)
                remaining -= 1
            }
            retryNow()
        }
    }
    val failed = unavailable || retrying
    var wasFailed by remember(channel.id) { mutableStateOf(false) }
    LaunchedEffect(failed) {
        if (failed) wasFailed = true
        else if (wasFailed) { wasFailed = false; Log.i(TAG, "[RADIO] recovered ${channel.name}") }
    }
    val holding by holder.audioOnlyHolding.collectAsStateWithLifecycle()
    val statusText = when {
        failed -> RADIO_UNAVAILABLE
        holding -> RADIO_BUFFERING
        else -> RADIO_SUBTITLE
    }
    // 1 Hz playhead for the scrubber while the sheet is open.
    var wallNow by remember { mutableStateOf(0L) }
    var playing by remember(player) { mutableStateOf(player?.playWhenReady == true) }
    var tracksTick by remember { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { tracksTick++ }
        }
        player?.addListener(l)
        onDispose { player?.removeListener(l) }
    }
    var sheetOpen by remember { mutableStateOf(false) }
    var recordTarget by remember { mutableStateOf<com.aeriotv.android.core.data.ProgramInfoTarget?>(null) }
    var switchStreams by remember { mutableStateOf<List<StreamOption>?>(null) }
    var switchCurrentId by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val togglePlay: () -> Unit = { RadioMode.togglePlayPause(context) }
    val logo = channel.tvgLogo.takeIf { it.isNotBlank() }
    val programme = nowProgramme(channel)

    Box(
        modifier = if (tabletWidth != null) {
            modifier.width(tabletWidth).floatingNavChrome(RoundedCornerShape(percent = 50))
        } else if (inRow) {
            modifier.floatingNavChrome(RoundedCornerShape(percent = 50))
        } else {
            modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp)
                .floatingNavChrome(RoundedCornerShape(percent = 50))
        },
    ) {
        CastMiniController(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            horizontalPadding = if (tabletWidth != null) 18.dp else 12.dp,
            title = channel.name,
            deviceName = null,
            artUri = logo,
            isPlaying = (playing || holding) && !failed,
            subtitle = statusText,
            transportIcon = Icons.Filled.Radio,
            stopDescription = "Stop",
            onTap = { Log.i(TAG, "[RADIO] card tap"); sheetOpen = true },
            onTogglePlayPause = if (failed) retryNow else togglePlay,
            onStop = { RadioMode.stop(context, "card") },
        )
    }

    if (sheetOpen) {
        LaunchedEffect(Unit) {
            while (true) {
                wallNow = RadioMode.positionWallMs(context)
                kotlinx.coroutines.delay(1_000L)
            }
        }
        @Suppress("UNUSED_EXPRESSION") tracksTick
        val audio = remember(tracksTick, player) {
            val out = mutableListOf<CastControl.Track>()
            player?.currentTracks?.groups?.forEachIndexed { gi, g ->
                if (g.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
                for (ti in 0 until g.length) {
                    val f = g.getTrackFormat(ti)
                    val label = f.label ?: f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage }
                        ?: "Track ${out.size + 1}"
                    out += CastControl.Track("$gi:$ti", label, g.isTrackSelected(ti))
                }
            }
            out
        }
        val canSwitchStream = isDispatcharrAdmin && channel.dispatcharrChannelId != null &&
            channel.id.startsWith("disp:")
        CastRemoteSheet(
            deviceName = null,
            channelTitle = channel.name,
            programmeTitle = programme?.title,
            logoUrl = logo,
            programmeStartMs = programme?.startMillis ?: 0L,
            programmeEndMs = programme?.endMillis ?: 0L,
            remoteState = CastControl.RemoteState(audio = audio, audioOnly = true),
            isPlaying = (playing || holding) && !failed,
            position = CastControl.PositionSnapshot(
                canSeek = tsState.buffering && tsState.headWallMs > tsState.tailWallMs,
                isLive = !tsState.timeshifting,
                positionWallMs = if (wallNow > 0) wallNow else tsState.headWallMs,
                windowStartMs = tsState.tailWallMs,
                windowEndMs = tsState.headWallMs,
                isPlaying = playing || holding,
            ),
            radioOptions = true,
            streamInfoRows = radioStreamInfoRows(holder),
            transportIcon = Icons.Filled.Radio,
            statusLineOverride = statusText,
            topContent = if (failed) {
                {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    ) {
                        if (!lastErrorText.isNullOrBlank()) {
                            androidx.compose.material3.Text(
                                text = lastErrorText.orEmpty(),
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                        androidx.compose.material3.Text(
                            text = if (retryCountdown > 0) "Retrying in ${retryCountdown}s" else "Reconnecting…",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        )
                        androidx.compose.material3.Button(onClick = retryNow) {
                            androidx.compose.material3.Text("Retry Now")
                        }
                    }
                }
            } else null,
            stopLabel = "Stop",
            canChangeChannel = true,
            showInlineSkip = true,
            inlineSkipEnabled = tsState.buffering,
            allowWebScrub = false,
            canSwitchStream = canSwitchStream,
            // Inert until the stream plays again; Retry Now is the action.
            playPauseEnabled = !failed,
            onTogglePlayPause = togglePlay,
            onChannelUp = { RadioMode.step(context, 1) },
            onChannelDown = { RadioMode.step(context, -1) },
            onStopCasting = { sheetOpen = false; RadioMode.stop(context, "sheet") },
            onSwitchToVideo = { sheetOpen = false; RadioMode.requestReturn(context) },
            onSetAudioTrack = { id ->
                val p = holder.player
                val (gi, ti) = id.split(':').map { it.toInt() }
                val g = p?.currentTracks?.groups?.getOrNull(gi)
                if (p != null && g != null) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, ti))
                        .build()
                }
            },
            onSetTextTrack = {},
            onSetSpeed = {},
            onSetAspect = {},
            onSwitchStream = {
                val chPk = channel.dispatcharrChannelId
                if (chPk != null) {
                    scope.launch {
                        // Same as the player: the permission re-read runs
                        // alongside the list loads; a denial closes the sheet.
                        val allowed = async { recheckSwitchStreamAllowed("Switch Stream open (Radio)") }
                        launch {
                            if (!allowed.await()) {
                                switchStreams = null
                                android.widget.Toast.makeText(context, SWITCH_DENIED_MESSAGE, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                        val current = async { loadCurrentStreamId(channel.id.removePrefix("disp:")) }
                        val streams = loadChannelStreams(chPk)
                        switchCurrentId = current.await()
                        if (allowed.isCompleted && !allowed.await()) return@launch
                        switchStreams = streams
                    }
                }
            },
            recordProgramTitle = programme?.title,
            onRecordCurrentProgram = programme?.takeIf { dvrCanRecord }?.let { pr -> { recordTarget = pr.toInfoTarget(channel.name, channel.dispatcharrChannelId) } },
            onSeekBy = { delta -> RadioMode.seekToWall(context, RadioMode.positionWallMs(context) + delta) },
            onSeekToWall = { target -> RadioMode.seekToWall(context, target) },
            onGoLive = { RadioMode.goLive(context) },
            onSleepMinutes = {},
            onDismiss = { sheetOpen = false },
        )
    }

    recordTarget?.let { target ->
        com.aeriotv.android.feature.livetv.RecordProgramSheet(target = target, onDismiss = { recordTarget = null })
    }

    switchStreams?.let { streams ->
        SwitchStreamSheet(
            streams = streams,
            currentStreamId = switchCurrentId,
            onSelect = { streamId ->
                switchStreams = null
                val uuid = channel.id.removePrefix("disp:")
                val previousId = switchCurrentId
                switchCurrentId = streamId
                scope.launch {
                    val error = runCatching { switchChannelStream(uuid, streamId) }.exceptionOrNull() ?: return@launch
                    if (switchCurrentId == streamId) switchCurrentId = previousId
                    android.widget.Toast.makeText(
                        context,
                        switchStreamFailureMessage("switch the stream", SWITCH_DENIED_MESSAGE, error),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    if ((error as? com.aeriotv.android.core.network.DispatcharrHttpFailure)?.status == 403) {
                        recheckSwitchStreamAllowed("403 on change_stream (Radio)")
                    }
                }
            },
            onDismiss = { switchStreams = null },
            footerText = "Audio keeps playing while the stream switches.",
        )
    }
}

/** Radio Stream Info (Apple): MODE, VIA, SOURCE, FORMAT, VIDEO (not decoded),
 *  AUDIO, RATE, BUFFER, HEALTH, all from the local player. No TV row. */
private fun radioStreamInfoRows(
    holder: com.aeriotv.android.core.playback.AerioExoPlayerHolder,
): List<Pair<String, String>> {
    val p = holder.player
    val url = holder.currentPlayUrl
    val host = url?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() } ?: "-"
    // The video track is disabled in Radio Mode, so read its format from
    // the track groups rather than the (null) active video format.
    val video = p?.currentTracks?.groups?.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.let { g ->
        if (g.length > 0) g.getTrackFormat(0) else null
    }
    val audio = p?.audioFormat
    fun codec(mime: String?) = when (mime) {
        androidx.media3.common.MimeTypes.VIDEO_H264 -> "H.264"
        androidx.media3.common.MimeTypes.VIDEO_H265 -> "HEVC"
        androidx.media3.common.MimeTypes.AUDIO_AC3 -> "AC-3"
        androidx.media3.common.MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
        androidx.media3.common.MimeTypes.AUDIO_AAC -> "AAC"
        androidx.media3.common.MimeTypes.AUDIO_MPEG, androidx.media3.common.MimeTypes.AUDIO_MPEG_L2 -> "MPEG audio"
        null -> "-"
        else -> mime.substringAfter('/')
    }
    val videoLine = video?.let { "${codec(it.sampleMimeType)} ${it.width}x${it.height} (not decoded)" } ?: "none"
    val audioLine = audio?.let { "${codec(it.sampleMimeType)} ${it.channelCount}ch ${it.sampleRate} Hz" } ?: "-"
    val bitrate = listOfNotNull(video?.bitrate?.takeIf { it > 0 }, audio?.bitrate?.takeIf { it > 0 }).sum()
    val health = when (p?.playbackState) {
        androidx.media3.common.Player.STATE_READY -> if (p.playWhenReady) "Playing" else "Paused"
        androidx.media3.common.Player.STATE_BUFFERING -> "Buffering"
        androidx.media3.common.Player.STATE_ENDED -> "Ended"
        else -> "Idle"
    }
    return listOf(
        "MODE" to "Radio Mode",
        "VIA" to "ExoPlayer (local)",
        "SOURCE" to host,
        "FORMAT" to (if (url != null && holder.canBufferLiveRewind(url)) "MPEG-TS" else (video?.containerMimeType ?: audio?.containerMimeType ?: "-")),
        "VIDEO" to videoLine,
        "AUDIO" to audioLine,
        "RATE" to (if (bitrate > 0) "${bitrate / 1000} kbps" else "measuring"),
        "BUFFER" to "${(p?.totalBufferedDuration ?: 0L) / 1000} s ahead",
        "HEALTH" to health,
    )
}
