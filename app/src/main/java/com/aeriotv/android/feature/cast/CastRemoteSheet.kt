package com.aeriotv.android.feature.cast

import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.cast.CastControl
import com.aeriotv.android.feature.player.AudioTrack
import com.aeriotv.android.feature.player.AudioTracksSheet
import com.aeriotv.android.feature.player.PlaybackSpeedSheet
import com.aeriotv.android.feature.player.SubtitleTrack
import com.aeriotv.android.feature.player.SubtitlesSheet
import com.aeriotv.android.core.ui.SkipIntervals
import com.aeriotv.android.core.ui.rememberSkipBackSeconds
import com.aeriotv.android.core.ui.rememberSkipForwardSeconds

/**
 * The phone's remote controls for whatever is playing on another screen
 * (Cast card UX, Logan 2026-09-12): ONE sheet, two transports. It is opened by
 * tapping the cast card above the tab bar and never replaces the page the user
 * is on -- the old full-screen presentation inside the player is gone, along
 * with the "pick a channel" cover.
 *
 * Contents: transport (play/pause), channel up/down for live, live-rewind
 * scrubbing when the other screen reports a buffer, the Options the transport
 * supports (audio track, subtitles, speed, aspect, stream info, Switch Stream on
 * a Dispatcharr channel, sleep timer, and Disconnect on the companion transport)
 * plus stop. Driven by
 * [CastControl.RemoteState] the receiver reports and committed back over the
 * control channel; the pickers are the exact local player sheets so they read
 * identically.
 */
@Composable
fun CastRemoteSheet(
    deviceName: String?,
    channelTitle: String,
    programmeTitle: String?,
    remoteState: CastControl.RemoteState,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onChannelUp: () -> Unit,
    onChannelDown: () -> Unit,
    /** Ends the session (and hides the card). Never resumes playback locally. */
    onStopCasting: () -> Unit,
    onSetAudioTrack: (String) -> Unit,
    onSetTextTrack: (String?) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetAspect: (CastControl.AspectMode) -> Unit,
    onSetAudioOnly: (Boolean) -> Unit,
    onSwitchStream: () -> Unit,
    onSleepMinutes: (Int) -> Unit,
    /** Sleep Timer row subtitle: "N min remaining" while armed, else "Off". */
    sleepLabel: String = "Off",
    /** Opens the shared Record sheet for the program airing on the cast
     *  channel; null hides the row (no channel or no guide data to record). */
    onRecordCurrentProgram: (() -> Unit)? = null,
    /** Record row subtitle: the program now airing. */
    recordProgramTitle: String? = null,
    onSeekBy: (Long) -> Unit,
    onSeekToWall: (Long) -> Unit,
    onGoLive: () -> Unit,
    onDismiss: () -> Unit,
    position: CastControl.PositionSnapshot,
    canSwitchStream: Boolean,
    /** Channel up/down only apply to a live channel on the other screen. */
    canChangeChannel: Boolean = true,
    onRefreshState: () -> Unit = {},
    /** Cast glyph for Google Cast, TV glyph for the AerioTV Remote transport. */
    transportIcon: ImageVector = Icons.Filled.Cast,
    /** "Casting to" (Cast) vs "Controlling" (LAN companion remote). */
    statusVerb: String = "Casting to",
    /** Non-null while a web-receiver channel flip is in flight: the old media
     *  has been unloaded and the new proxy session is warming up, so the header
     *  reads "Switching to <channel>" until the receiver reports PLAYING. */
    switchingTo: String? = null,
    /** True while the sender holds a receiver stall (iOS incident
     *  2026-09-25): the status line reads "Buffering..." instead. */
    buffering: Boolean = false,
    /** Label for the stop action: "Stop Casting" for Cast (iOS wording),
     *  "Stop" for the companion transport. */
    stopLabel: String = "Stop Casting",
    /** "Change Cast Device" for Cast, "Change Device" for the companion. */
    changeDeviceLabel: String = "Change Cast Device",
    /** Stops the session, closes the sheet, then opens the device picker.
     *  Null hides the button. */
    onChangeDevice: (() -> Unit)? = null,
    /** Google Cast transport (Logan 2026-09-13): the skip back / skip forward 30 s
     *  buttons were only ever drawn for the AerioTV Remote transport, because
     *  the Cast receivers do not report a rewind window on the control channel.
     *  When true they are drawn inline in the transport row instead, between
     *  channel down and channel up. */
    showInlineSkip: Boolean = false,
    /** False when the transport reports no seekable range: the inline skip
     *  buttons are still shown, but dimmed and inert. */
    inlineSkipEnabled: Boolean = true,
    /** Companion transport only (Logan 2026-09-12): drop the AerioTV Remote link
     *  and hide the card while the TV keeps playing. Null for Google Cast, where
     *  there is nothing to leave behind once the session ends. */
    onDisconnect: (() -> Unit)? = null,
    /** Channel logo for the header; the transport glyph stands in when null. */
    logoUrl: String? = null,
    /** Google Cast: the card's "Receiver: ..." stat and transcode note, shown
     *  again at the top of Stream Info. */
    castDetailLines: List<String> = emptyList(),
    /** Current programme's start / end (epoch ms) for the time range and
     *  progress bar; 0 hides both. */
    programmeStartMs: Long = 0L,
    programmeEndMs: Long = 0L,
) {
    var optionsOpen by remember { mutableStateOf(false) }
    var audioOpen by remember { mutableStateOf(false) }
    var subsOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }

    // Pull a fresh snapshot when the sheet appears; each command reply keeps it
    // current thereafter, and re-opening Options re-pulls after a channel change.
    androidx.compose.runtime.LaunchedEffect(Unit) { onRefreshState() }

    com.aeriotv.android.ui.FormFactorModal(onDismiss = onDismiss) {
        // iOS RemoteSessionSheet.playingContent, top to bottom (Logan
        // 2026-09-27, "Cast card doesn't match iOS"): header, program block,
        // Channel Down / Channel Up, Back / Play-Pause / Forward, Options,
        // Stop, then the receiver footnotes. 14 dp between blocks, as iOS's
        // VStack(spacing: 14).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header: channel art (or the transport glyph), channel, then
            // "Casting to <device>" in the accent color.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!logoUrl.isNullOrBlank()) {
                    coil3.compose.AsyncImage(
                        model = logoUrl,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.size(width = 140.dp, height = 72.dp),
                    )
                } else {
                    TransportGlyph(transportIcon)
                }
                Text(
                    text = switchingTo?.let { "Switching to $it" }
                        ?: channelTitle.ifBlank { "Nothing playing" },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (buffering && switchingTo == null) {
                        "Buffering… on ${deviceName ?: "your TV"}"
                    } else {
                        "$statusVerb ${deviceName ?: "your TV"}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.textAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Program block: title left, LIVE pill right, time range under it,
            // then a full-width progress bar (an empty track when unknown,
            // never a full one), as iOS's programBlock.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = programmeTitle.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    LiveBadge()
                }
                val known = programmeEndMs > programmeStartMs && programmeStartMs > 0L
                // Re-read the clock every 30 s so the bar advances while open.
                var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
                androidx.compose.runtime.LaunchedEffect(programmeStartMs, programmeEndMs) {
                    while (true) {
                        nowMs = System.currentTimeMillis()
                        kotlinx.coroutines.delay(30_000L)
                    }
                }
                val progress = if (known) {
                    ((nowMs - programmeStartMs).toFloat() / (programmeEndMs - programmeStartMs))
                        .coerceIn(0f, 1f)
                } else {
                    0f
                }
                if (known) {
                    val clock = com.aeriotv.android.core.ui.ClockFormat.short()
                    Text(
                        text = clock.format(java.util.Date(programmeStartMs)) + " - " +
                            clock.format(java.util.Date(programmeEndMs)),
                        style = MaterialTheme.typography.bodySmall.subtext(),
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                )
            }

            // Skip Intervals setting, read live so a change re-renders.
            val backSeconds = rememberSkipBackSeconds()
            val forwardSeconds = rememberSkipForwardSeconds()
            // Companion live rewind: the receiver reports a rewind buffer via
            // EITHER the getState echo's canSeek or the ~1 Hz position tick.
            // The scrubber (needs the tick's window) and the LIVE pill stay
            // here; the skips are the Back / Forward buttons below, so they
            // are never drawn twice.
            val rewindActive = position.canSeek || remoteState.canSeek
            val atLive = if (position.canSeek) position.isLive else remoteState.isLive
            if (rewindActive) {
                if (position.canSeek) {
                    val span = (position.windowEndMs - position.windowStartMs).coerceAtLeast(1L)
                    var dragFraction by remember { mutableStateOf<Float?>(null) }
                    var pendingSeekWall by remember { mutableStateOf<Long?>(null) }
                    // After release, HOLD the dragged thumb until the receiver's
                    // reported position reaches the seek target, so it doesn't snap
                    // back to the pre-seek position for the ~1s+re-buffer gap. The
                    // convergence effect is re-keyed on each tick to read the fresh
                    // position; a 6s timeout releases the hold if it never converges.
                    androidx.compose.runtime.LaunchedEffect(pendingSeekWall, position.positionWallMs) {
                        val target = pendingSeekWall ?: return@LaunchedEffect
                        if (kotlin.math.abs(position.positionWallMs - target) < 4_000L) {
                            dragFraction = null
                            pendingSeekWall = null
                        }
                    }
                    androidx.compose.runtime.LaunchedEffect(pendingSeekWall) {
                        if (pendingSeekWall == null) return@LaunchedEffect
                        kotlinx.coroutines.delay(6_000L)
                        dragFraction = null
                        pendingSeekWall = null
                    }
                    val liveFraction =
                        ((position.positionWallMs - position.windowStartMs).toFloat() / span).coerceIn(0f, 1f)
                    val shownFraction = dragFraction ?: liveFraction
                    val behindMs = (span - (shownFraction * span).toLong()).coerceAtLeast(0L)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = shownFraction,
                            onValueChange = { dragFraction = it },
                            onValueChangeFinished = {
                                dragFraction?.let { f ->
                                    val target = position.windowStartMs + (f * span).toLong()
                                    onSeekToWall(target)
                                    pendingSeekWall = target
                                }
                            },
                        )
                        Text(
                            text = if (position.isLive && dragFraction == null) {
                                "LIVE"
                            } else {
                                "-${formatBehindLive(behindMs)} behind live"
                            },
                            style = MaterialTheme.typography.labelMedium.subtext(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!atLive) {
                    GoLivePill(onClick = onGoLive)
                }
            }

            // Everything but Stop is inert (and dimmed to 40%) while a flip is
            // warming up, as iOS's connecting mode.
            val enabled = switchingTo == null
            val groupAlpha = if (enabled) 1f else 0.4f
            // Row 1: Channel Down, Channel Up.
            if (canChangeChannel) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.Top,
                ) {
                    LabeledRemoteButton(Icons.Filled.KeyboardArrowDown, "Channel Down", onChannelDown, enabled, groupAlpha)
                    LabeledRemoteButton(Icons.Filled.KeyboardArrowUp, "Channel Up", onChannelUp, enabled, groupAlpha)
                }
            }
            // Row 2: Back, Play / Pause, Forward, in equal fixed-width columns
            // so unequal labels cannot pull the row off center.
            val skipEnabled = enabled && (rewindActive || (showInlineSkip && inlineSkipEnabled))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Top,
            ) {
                LabeledRemoteButton(
                    SkipIntervals.backIcon(backSeconds),
                    "Back ${backSeconds}s",
                    { onSeekBy(-backSeconds * 1_000L) },
                    skipEnabled,
                    groupAlpha,
                )
                PlayPauseButton(isPlaying, onTogglePlayPause, enabled, groupAlpha)
                LabeledRemoteButton(
                    SkipIntervals.forwardIcon(forwardSeconds),
                    "Forward ${forwardSeconds}s",
                    { onSeekBy(forwardSeconds * 1_000L) },
                    skipEnabled,
                    groupAlpha,
                )
            }
            WideButton(
                icon = Icons.AutoMirrored.Filled.List,
                label = "Options",
                contentColor = Color.White,
                background = Color.White.copy(alpha = 0.12f),
                enabled = enabled,
                modifier = Modifier.alpha(groupAlpha),
                onClick = {
                    onRefreshState()
                    optionsOpen = true
                },
            )
            // Between Options and Stop, styled as Options (iOS parity 2026-09-27).
            // Never dimmed by a flip: like Stop, it ends the session.
            if (onChangeDevice != null) {
                WideButton(
                    icon = Icons.Filled.Cast,
                    label = changeDeviceLabel,
                    contentColor = Color.White,
                    background = Color.White.copy(alpha = 0.12f),
                    onClick = onChangeDevice,
                )
            }
            // Red text and stop glyph on translucent red, as iOS.
            WideButton(
                icon = Icons.Filled.Stop,
                label = stopLabel,
                contentColor = STOP_RED,
                background = STOP_RED.copy(alpha = 0.15f),
                onClick = onStopCasting,
            )
            // Under Stop, as on iOS (Logan 2026-09-27: the resolution lines
            // belong in the expanded sheet, not the collapsed card).
            if (castDetailLines.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    castDetailLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    if (optionsOpen) {
        com.aeriotv.android.ui.FormFactorModal(onDismiss = { optionsOpen = false }) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Options",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                // Row order and titles are shared with iOS (Logan 2026-09-27
                // parity ruling); keep both lists identical when editing.
                if (canSwitchStream) {
                    OptionRow(Icons.Filled.SwapHoriz, "Switch Stream", null) {
                        optionsOpen = false
                        onSwitchStream()
                    }
                    Text(
                        text = "Swaps this channel's upstream. The TV keeps playing; the picture follows in a few seconds.",
                        style = MaterialTheme.typography.bodySmall.subtext(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 52.dp, end = 12.dp, bottom = 6.dp),
                    )
                }
                onRecordCurrentProgram?.let { record ->
                    OptionRow(Icons.Filled.FiberManualRecord, "Record Current Program", recordProgramTitle) {
                        optionsOpen = false
                        record()
                    }
                }
                OptionRow(Icons.Outlined.MusicNote, "Audio Track", remoteState.audio.firstOrNull { it.selected }?.label) {
                    optionsOpen = false
                    audioOpen = true
                }
                OptionRow(Icons.Filled.Subtitles, "Subtitles", if (remoteState.textOff) "Off" else remoteState.text.firstOrNull { it.selected }?.label ?: "On") {
                    optionsOpen = false
                    subsOpen = true
                }
                OptionRow(Icons.Filled.Speed, "Playback Speed", speedLabel(remoteState.speed)) {
                    optionsOpen = false
                    speedOpen = true
                }
                OptionRow(Icons.Outlined.AspectRatio, "Video Scale", remoteState.aspect.label) {
                    onSetAspect(remoteState.aspect.next())
                }
                OptionRow(Icons.Filled.Timer, "Sleep Timer", sleepLabel) {
                    optionsOpen = false
                    sleepOpen = true
                }
                OptionRow(Icons.Filled.VideocamOff, "Audio Only", if (remoteState.audioOnly) "On" else "Off") {
                    onSetAudioOnly(!remoteState.audioOnly)
                }
                OptionRow(Icons.Filled.Info, "Stream Info", null) {
                    optionsOpen = false
                    infoOpen = true
                }
                // Companion only: the X above stops the TV, this one just lets go
                // of the remote. Both hide the card.
                onDisconnect?.let { disconnect ->
                    OptionRow(Icons.Filled.LinkOff, "Disconnect", "Leaves the TV playing") {
                        optionsOpen = false
                        disconnect()
                    }
                }
            }
        }
    }

    if (sleepOpen) {
        com.aeriotv.android.ui.FormFactorModal(onDismiss = { sleepOpen = false }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = "Sleep Timer",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                listOf(0 to "Off", 30 to "30 minutes", 60 to "1 hour", 90 to "1.5 hours", 120 to "2 hours")
                    .forEach { (minutes, label) ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSleepMinutes(minutes)
                                    sleepOpen = false
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (infoOpen) {
        com.aeriotv.android.ui.FormFactorModal(onDismiss = { infoOpen = false }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    text = "Stream Info",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                castDetailLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium.subtext(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (castDetailLines.isNotEmpty() && remoteState.streamInfo.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                }
                if (castDetailLines.isEmpty() || remoteState.streamInfo.isNotBlank()) {
                    Text(
                        text = remoteState.streamInfo.ifBlank { "No stream details available" },
                        style = MaterialTheme.typography.bodyMedium.subtext(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    if (audioOpen) {
        AudioTracksSheet(
            tracks = remoteState.audio.map { it.toAudioTrack() },
            currentTrackId = remoteState.audio.firstOrNull { it.selected }?.id?.toIntOrNull(),
            onSelect = { id ->
                onSetAudioTrack(id.toString())
                audioOpen = false
            },
            onDismiss = { audioOpen = false },
        )
    }
    if (subsOpen) {
        SubtitlesSheet(
            tracks = remoteState.text.map { it.toSubtitleTrack() },
            currentTrackId = if (remoteState.textOff) null else remoteState.text.firstOrNull { it.selected }?.id?.toIntOrNull(),
            onSelect = { id ->
                onSetTextTrack(id?.toString())
                subsOpen = false
            },
            onDismiss = { subsOpen = false },
        )
    }
    if (speedOpen) {
        PlaybackSpeedSheet(
            currentSpeed = remoteState.speed,
            onSelect = { s ->
                onSetSpeed(s)
                speedOpen = false
            },
            onDismiss = { speedOpen = false },
        )
    }
}

/**
 * The sheet for a connected Cast session with nothing playing yet (iOS parity,
 * 2026-09-25). The idle card reads "Casting to <device>" / "Select a Channel";
 * tapping it opens this minimal sheet instead of the full remote with dimmed
 * controls and a "Nothing playing" title: there is nothing to pause, skip or
 * flip yet, so the only useful action is picking another device. Ending the
 * session is the card's X.
 */
@Composable
fun CastIdleSheet(
    deviceName: String?,
    onChangeDevice: () -> Unit,
    onDismiss: () -> Unit,
    transportIcon: ImageVector = Icons.Filled.Cast,
    statusText: String = "Connected. Select a channel to start.",
) {
    // iOS RemoteSessionSheet.idleContent: glyph, device name, accent status,
    // one wide "Change Cast Device" button, 14 dp apart.
    com.aeriotv.android.ui.FormFactorModal(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TransportGlyph(transportIcon)
                Text(
                    text = deviceName ?: "your TV",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.textAccent,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            WideButton(
                icon = transportIcon,
                label = "Change Cast Device",
                contentColor = Color.White,
                background = Color.White.copy(alpha = 0.12f),
                onClick = onChangeDevice,
            )
        }
    }
}

private fun CastControl.Track.toAudioTrack(): AudioTrack =
    AudioTrack(id = id.toIntOrNull() ?: id.hashCode(), title = label, lang = "", codec = "", channels = "")

private fun CastControl.Track.toSubtitleTrack(): SubtitleTrack =
    SubtitleTrack(id = id.toIntOrNull() ?: id.hashCode(), title = label, lang = "")

private fun speedLabel(speed: Float): String =
    if (kotlin.math.abs(speed - 1f) < 0.01f) "Normal" else "${speed}x"

/** iOS's system red in dark mode, for Stop and the LIVE pill. */
private val STOP_RED = Color(0xFFFF453A)

/** The shared column width of the transport rows (iOS buttonColumnWidth):
 *  "Forward 60s" is wider than "Back 5s", and unequal columns pulled the
 *  row's center off the sheet's. */
private val REMOTE_COLUMN_WIDTH = 104.dp

/** The transport glyph in a fixed 56 dp box, accent-tinted (iOS transportGlyph). */
@Composable
private fun TransportGlyph(icon: ImageVector) {
    Box(modifier = Modifier.height(56.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
    }
}

/** A 52 dp circle on 12% white with a caption under it (iOS labeledButton). */
@Composable
private fun LabeledRemoteButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    groupAlpha: Float = 1f,
) {
    Column(
        modifier = Modifier
            .width(REMOTE_COLUMN_WIDTH)
            .alpha(if (enabled) 1f else minOf(groupAlpha, 0.4f))
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 1,
        )
    }
}

/** The 72 dp accent Play / Pause with its "Pause" / "Play" caption. */
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    groupAlpha: Float,
) {
    val label = if (isPlaying) "Pause" else "Play"
    Column(
        modifier = Modifier
            .width(REMOTE_COLUMN_WIDTH)
            .alpha(groupAlpha)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                // Black on the accent, as iOS.
                tint = Color.Black,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}

/** Full-width icon + label button, 14 dp corners (iOS wideButton). */
@Composable
private fun WideButton(
    icon: ImageVector,
    label: String,
    contentColor: Color,
    background: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
    }
}

/** Red dot + red LIVE on a red-tinted capsule, as iOS's program block. */
@Composable
private fun LiveBadge() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(STOP_RED.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(STOP_RED))
        Text(
            text = "LIVE",
            color = STOP_RED,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Red "LIVE" pill shown while the cast is rewound; tap returns to the live edge. */
@Composable
private fun GoLivePill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFD32F2F))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
        Text(
            text = "LIVE",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Format a "behind live" duration as M:SS (or H:MM:SS past an hour). */
private fun formatBehindLive(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    title: String,
    value: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            value?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall.subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
