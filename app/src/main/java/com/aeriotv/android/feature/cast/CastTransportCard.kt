package com.aeriotv.android.feature.cast

import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.cast.AerioCastSender
import com.aeriotv.android.core.cast.companion.CompanionRemoteController
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.feature.miniplayer.CastMiniController
import com.aeriotv.android.feature.player.StreamOption
import com.aeriotv.android.feature.player.SwitchStreamSheet
import kotlinx.coroutines.launch

private const val TAG = "CastCard"

/**
 * THE cast card (Logan 2026-09-12): one card above the bottom nav bar for both
 * transports this phone can drive, Google Cast and the AerioTV Remote companion
 * link. It shows the device, what is playing on it with its program, play/pause
 * and stop; tapping it opens [CastRemoteSheet] over the page the user is on
 * instead of taking over the screen.
 *
 * Rules it implements:
 *  - Connecting from the picker stays on the current page; the card simply
 *    appears. With nothing playing yet it reads "Select a Channel", and the
 *    next channel tap casts (the tap handler owns that, see Navigation).
 *  - Google Cast: the X (and Stop casting) on a PLAYING card stops the media
 *    and returns the card to its idle "Select a Channel" state with the session
 *    still connected; the X on the IDLE card ends the session and hides it.
 *    Companion remote: the X stops the TV and hides the card. Playback is
 *    never handed back to the phone.
 *  - Companion only: "Disconnect" in the remote sheet drops the link and hides
 *    the card while the TV keeps playing.
 *  - Only ONE card is possible: the picker keeps the two transports mutually
 *    exclusive, and a live Cast session wins if both somehow exist.
 */
@Composable
fun CastTransportCard(
    castSender: AerioCastSender,
    companionRemote: CompanionRemoteController,
    channels: List<M3UChannel>,
    /** Current guide programme for a channel, resolved the same way the
     *  channel list rows do; drives the card's program line and the sheet's
     *  time range and progress. */
    nowProgramme: (M3UChannel) -> com.aeriotv.android.core.data.EPGProgramme?,
    /** Cast this channel to the active session (the scaffold's channel tap). */
    onCastChannel: (M3UChannel) -> Unit,
    /** Dispatcharr Switch Stream plumbing (admin-only channels). */
    loadChannelStreams: suspend (Int) -> List<StreamOption>,
    loadCurrentStreamId: suspend (String) -> Int?,
    switchChannelStream: suspend (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val castState by castSender.state.collectAsStateWithLifecycle()
    val castContent by castSender.content.collectAsStateWithLifecycle()
    val castIsPlaying by castSender.isPlaying.collectAsStateWithLifecycle()
    // Web-receiver channel flip in flight: the old media has been unloaded and
    // the new proxy session is warming up, so the card says so rather than
    // keeping the old channel's name on screen (Logan 2026-09-13).
    val castSwitchingTo by castSender.switchingTo.collectAsStateWithLifecycle()
    val companionConn by companionRemote.connection.collectAsStateWithLifecycle()
    val companionIsPlaying by companionRemote.isPlaying.collectAsStateWithLifecycle()
    val companionNowPlaying by companionRemote.nowPlaying.collectAsStateWithLifecycle()
    val companionChannelId by companionRemote.currentChannelId.collectAsStateWithLifecycle()
    val companionDetails by companionRemote.details.collectAsStateWithLifecycle()

    val castDevice = (castState as? AerioCastSender.State.Connected)?.deviceName
    val casting = castState is AerioCastSender.State.Connected
    val companionTv = companionConn as? CompanionRemoteController.Conn.Connected
    // A live Cast session wins when both transports are somehow up.
    val isCompanion = !casting && companionTv != null
    val active = casting || isCompanion
    val deviceName = if (isCompanion) companionTv?.name else castDevice

    var sheetOpen by remember { mutableStateOf(false) }
    /** The Cast device picker, opened from the idle sheet's Change Cast Device. */
    var pickerOpen by remember { mutableStateOf(false) }
    var switchStreams by remember { mutableStateOf<List<StreamOption>?>(null) }
    var switchCurrentId by remember { mutableStateOf<Int?>(null) }
    var sleepEndsAt by remember { mutableStateOf<Long?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // The session ended (stop, disconnect, TV powered off): drop the sheet with
    // the card so neither outlives the transport.
    LaunchedEffect(active) {
        Log.i(TAG, "[Cast] card ${if (active) "show" else "hide"}")
        if (!active) {
            sheetOpen = false
            switchStreams = null
            sleepEndsAt = null
        }
    }
    DisposableEffect(Unit) { onDispose { Log.i(TAG, "[Cast] card hide") } }

    // Sleep timer (the only one the remote transports have): pause whatever is
    // playing on the other screen when it expires. The session stays connected.
    LaunchedEffect(sleepEndsAt) {
        val endsAt = sleepEndsAt ?: return@LaunchedEffect
        while (true) {
            val remaining = endsAt - System.currentTimeMillis()
            if (remaining <= 0L) {
                if (isCompanion) companionRemote.pause() else castSender.pause()
                sleepEndsAt = null
                break
            }
            kotlinx.coroutines.delay(1_000L)
        }
    }

    if (!active) return

    // What the other screen is on, resolved back to a playlist channel so
    // channel up/down and Switch Stream have something to work with.
    val currentChannelId = if (isCompanion) companionChannelId else castContent?.mediaId
    val currentChannel = remember(currentChannelId, channels) {
        val id = currentChannelId ?: return@remember null
        val bare = id.substringAfter(':', id)
        channels.firstOrNull { it.id == id }
            ?: channels.firstOrNull { it.id.substringAfter(':', it.id) == bare }
            // A cast resumed after an app restart only recovers the channel TITLE
            // as mediaId (the receiver's bridged session drops our id).
            ?: channels.firstOrNull { it.name == id }
    }
    // Re-resolved each minute so the programme rolls over on the hour.
    var guideTick by remember { mutableStateOf(0) }
    LaunchedEffect(currentChannel?.id) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            guideTick++
        }
    }
    val castProgramme = remember(currentChannel, guideTick) {
        if (isCompanion) null else currentChannel?.let(nowProgramme)
    }
    val programmeTitle = if (isCompanion) {
        companionDetails?.programmeTitle
    } else {
        castProgramme?.title
    }
    val programmeStartMs = if (isCompanion) companionDetails?.programmeStartMs ?: 0L
        else castProgramme?.startMillis ?: 0L
    val programmeEndMs = if (isCompanion) companionDetails?.programmeEndMs ?: 0L
        else castProgramme?.endMillis ?: 0L
    val logoUrl = if (isCompanion) {
        companionDetails?.logoUrl
    } else {
        castContent?.artUri?.takeIf { it.isNotBlank() }
            ?: currentChannel?.tvgLogo?.takeIf { it.isNotBlank() }
    }
    val title = when {
        isCompanion -> companionDetails?.channelName
            ?: companionNowPlaying.takeIf { it.isNotBlank() }
            ?: ""
        else -> castContent?.title.orEmpty()
    }
    val hasContent = title.isNotBlank()
    val switchingTo = if (isCompanion) null else castSwitchingTo

    fun flipChannel(delta: Int) {
        val idx = channels.indexOfFirst { it.id == currentChannel?.id }
        if (idx < 0) return
        channels.getOrNull((idx + delta).coerceIn(0, channels.lastIndex))
            ?.let(onCastChannel)
    }

    // The card's X: stop playback on the other screen AND close the card, for both
    // transports (Logan 2026-09-12). Google Cast gets that for free because ending
    // the session stops the receiver and the HLS proxy; the companion link would
    // otherwise leave the TV playing, so it is told to stop first and only then
    // disconnected.
    fun endSession() {
        if (isCompanion) {
            Log.i(TAG, "[Remote] X: stop + close")
            companionRemote.stopRemotePlayback()
            companionRemote.disconnect()
        } else if (hasContent || switchingTo != null) {
            // Playing card (or its sheet's Stop casting): stop the media, keep
            // the session, and fall back to the idle card (iOS parity).
            castSender.stopPlayback()
        } else {
            Log.i(TAG, "[Cast] X on idle card: session ended, no local resume")
            castSender.stopCasting()
        }
        sheetOpen = false
    }

    // Companion only: give up the remote without touching the TV, which keeps
    // playing whatever it is on. Hides the card the same way a stop does.
    fun disconnectCompanionOnly() {
        Log.i(TAG, "[Remote] disconnect, TV keeps playing")
        sheetOpen = false
        companionRemote.disconnect()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                RoundedCornerShape(20.dp),
            ),
    ) {
        CastMiniController(
            title = when {
                switchingTo != null -> "Switching to $switchingTo"
                hasContent -> title
                else -> "Casting to ${deviceName ?: "your TV"}"
            },
            deviceName = deviceName,
            artUri = if (isCompanion) companionDetails?.logoUrl else castContent?.artUri,
            isPlaying = if (isCompanion) companionIsPlaying else castIsPlaying,
            // Nothing playing yet: the card says where the next tap lands.
            subtitle = when {
                switchingTo != null -> "Casting to ${deviceName ?: "your TV"}"
                !hasContent -> "Select a Channel"
                isCompanion -> "Controlling ${deviceName ?: "TV"}"
                else -> null
            },
            programmeTitle = programmeTitle,
            transportIcon = if (isCompanion) Icons.Filled.Tv else Icons.Filled.Cast,
            showTransport = hasContent,
            stopDescription = if (isCompanion) "Stop playback" else "Stop casting",
            onTap = {
                Log.i(TAG, "[Cast] card tap")
                sheetOpen = true
            },
            onTogglePlayPause = {
                if (isCompanion) companionRemote.togglePlayPause() else castSender.togglePlayPause()
            },
            onStop = { endSession() },
        )
    }

    // Connected, nothing playing (and no flip warming up): the minimal idle
    // sheet, not the remote with every control dimmed (iOS parity 2026-09-25).
    val idleCast = !isCompanion && !hasContent && switchingTo == null
    if (sheetOpen && idleCast) {
        CastIdleSheet(
            deviceName = deviceName,
            onChangeDevice = {
                Log.i(TAG, "[Cast] idle sheet: change device")
                sheetOpen = false
                pickerOpen = true
            },
            onDismiss = { sheetOpen = false },
        )
    }
    if (pickerOpen) {
        CastRouteChooserDialog(
            sender = castSender,
            companionRemote = companionRemote,
            companionDiscovery = null,
            onDismiss = { pickerOpen = false },
        )
    }

    if (sheetOpen && !idleCast) {
        val remoteState by (if (isCompanion) companionRemote.remoteState else castSender.remoteState)
            .collectAsStateWithLifecycle()
        val remoteIsPlaying by (if (isCompanion) companionRemote.isPlaying else castSender.isPlaying)
            .collectAsStateWithLifecycle()
        val position by (if (isCompanion) companionRemote.position else castSender.position)
            .collectAsStateWithLifecycle()
        // Google Cast only: the skips live in the transport row (the companion
        // transport already draws them above its rewind scrubber).
        val castCanSkip by castSender.canSkip.collectAsStateWithLifecycle()
        val canSwitchStream = currentChannel?.dispatcharrChannelId != null &&
            currentChannel.id.startsWith("disp:")
        CastRemoteSheet(
            deviceName = deviceName,
            channelTitle = title,
            switchingTo = switchingTo,
            programmeTitle = programmeTitle,
            logoUrl = logoUrl,
            programmeStartMs = programmeStartMs,
            programmeEndMs = programmeEndMs,
            remoteState = remoteState,
            isPlaying = remoteIsPlaying,
            position = position,
            transportIcon = if (isCompanion) Icons.Filled.Tv else Icons.Filled.Cast,
            statusVerb = if (isCompanion) "Controlling" else "Casting to",
            stopLabel = if (isCompanion) "Stop" else "Stop casting",
            // Only the companion transport can be dropped while the TV plays on.
            onDisconnect = if (isCompanion) ({ disconnectCompanionOnly() }) else null,
            canChangeChannel = currentChannel != null,
            showInlineSkip = !isCompanion,
            inlineSkipEnabled = castCanSkip || position.canSeek || remoteState.canSeek,
            canSwitchStream = canSwitchStream,
            onTogglePlayPause = {
                if (isCompanion) companionRemote.togglePlayPause() else castSender.togglePlayPause()
            },
            onChannelUp = { flipChannel(1) },
            onChannelDown = { flipChannel(-1) },
            onStopCasting = { endSession() },
            onSetAudioTrack = { id ->
                if (isCompanion) companionRemote.setRemoteAudioTrack(id) else castSender.setRemoteAudioTrack(id)
            },
            onSetTextTrack = { id ->
                if (isCompanion) companionRemote.setRemoteTextTrack(id) else castSender.setRemoteTextTrack(id)
            },
            onSetSpeed = { s ->
                if (isCompanion) companionRemote.setRemoteSpeed(s) else castSender.setRemoteSpeed(s)
            },
            onSetAspect = { mode ->
                if (isCompanion) companionRemote.setRemoteAspect(mode) else castSender.setRemoteAspect(mode)
            },
            onSetAudioOnly = { on ->
                if (isCompanion) companionRemote.setRemoteAudioOnly(on) else castSender.setRemoteAudioOnly(on)
            },
            onSwitchStream = {
                // Server-side change_stream: works the same whether the stream is
                // playing here or on the other screen.
                val ch = currentChannel
                val chPk = ch?.dispatcharrChannelId
                if (ch != null && chPk != null) {
                    val uuid = ch.id.removePrefix("disp:")
                    scope.launch {
                        switchCurrentId = loadCurrentStreamId(uuid)
                        switchStreams = loadChannelStreams(chPk)
                    }
                }
            },
            onSleepMinutes = { minutes ->
                sleepEndsAt = if (minutes == 0) null else System.currentTimeMillis() + minutes * 60_000L
            },
            onSeekBy = { delta ->
                if (isCompanion) companionRemote.seekBy(delta) else castSender.skipBy(delta)
            },
            onSeekToWall = { target ->
                if (isCompanion) companionRemote.seekToWall(target) else castSender.seekToWall(target)
            },
            onGoLive = {
                if (isCompanion) companionRemote.goLiveRemote() else castSender.goLiveRemote()
            },
            onRefreshState = {
                if (isCompanion) companionRemote.requestRemoteState() else castSender.requestRemoteState()
            },
            onDismiss = { sheetOpen = false },
        )
    }

    switchStreams?.let { streams ->
        SwitchStreamSheet(
            streams = streams,
            currentStreamId = switchCurrentId,
            onSelect = { streamId ->
                val uuid = currentChannel?.id?.removePrefix("disp:")
                switchStreams = null
                if (uuid != null) {
                    scope.launch {
                        runCatching { switchChannelStream(uuid, streamId) }
                        switchCurrentId = streamId
                    }
                }
            },
            onDismiss = { switchStreams = null },
        )
    }
}
