package com.aeriotv.android.feature.player

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.toInfoTarget
import com.aeriotv.android.core.pip.PipState
import com.aeriotv.android.core.pip.enterPip16x9
import com.aeriotv.android.core.pip.findActivity
import com.aeriotv.android.core.pip.supportsPip
import com.aeriotv.android.feature.livetv.RecordProgramSheet
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Player chrome overlay matching iOS canon (PlaybackChromeOverlay.swift).
 *
 *   X-close              ⋯-more  +-add
 *   ┌──────────────────────────────────────┐
 *   │ # · logo · Channel name                │
 *   │           Programme title              │
 *   │           Time range · duration        │
 *   └──────────────────────────────────────┘
 *                  ━━━━━━━━━━━━━━━━━━
 *           Programme title    N min remaining
 *
 * Fades in/out on screen tap; auto-hides after 4s of no interaction. While a
 * menu or sheet is open, the auto-hide pauses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerChromeOverlay(
    channel: M3UChannel?,
    nowProgramme: EPGProgramme?,
    chromeVisible: Boolean,
    onClose: () -> Unit,
    onAddToMultiview: () -> Unit,
    onShowRecord: (ProgramInfoTarget) -> Unit,
    onShowStreamInfo: () -> Unit,
    onShowSubtitles: () -> Unit,
    onToggleAudioOnly: () -> Unit,
    audioOnly: Boolean,
    onSetSleepMinutes: (Int) -> Unit,
    sleepRemainingMillis: Long?,
) {
    var moreOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val inPip by PipState.inPictureInPicture
    val pipAvailable = remember { context.supportsPip() }

    AnimatedVisibility(
        visible = chromeVisible && !inPip,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Top scrim — gradient-like dark fade so the buttons read against the video.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .align(Alignment.TopCenter),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .align(Alignment.BottomCenter),
            )

            // Top row: X close (left), ⋯ more + + add (right). statusBarsPadding
            // keeps the circle buttons below the camera notch / status bar since
            // the player runs under edge-to-edge with no Scaffold to inset it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "Close",
                    onClick = onClose,
                )
                Spacer(Modifier.weight(1f))
                Box {
                    CircleIconButton(
                        icon = Icons.Filled.MoreHoriz,
                        contentDescription = "More",
                        onClick = { moreOpen = true },
                    )
                    PlayerMoreMenu(
                        expanded = moreOpen,
                        onDismiss = { moreOpen = false },
                        // Record row gated on iOS's `canRecord` equivalent:
                        // a now-playing program AND a Dispatcharr-managed
                        // channel (server-side scheduling is Dispatcharr-only).
                        // Matches the channel long-press menu's gating.
                        canRecord = nowProgramme != null && channel?.dispatcharrChannelId != null,
                        audioOnly = audioOnly,
                        sleepActive = sleepRemainingMillis != null,
                        onSubtitles = {
                            moreOpen = false
                            onShowSubtitles()
                        },
                        onRecord = {
                            moreOpen = false
                            val target = nowProgramme?.toInfoTarget(channel?.name.orEmpty(), channel?.dispatcharrChannelId)
                                ?: channel?.let {
                                    val now = System.currentTimeMillis()
                                    ProgramInfoTarget(
                                        channelName = it.name,
                                        title = "${it.name} live recording",
                                        startMillis = now,
                                        endMillis = now + 3_600_000L,
                                        description = "",
                                        category = "",
                                        channelDispatcharrId = it.dispatcharrChannelId,
                                    )
                                }
                            target?.let(onShowRecord)
                        },
                        onSleepTimer = {
                            moreOpen = false
                            sleepOpen = true
                        },
                        onStreamInfo = {
                            moreOpen = false
                            onShowStreamInfo()
                        },
                        onAudioOnly = {
                            moreOpen = false
                            onToggleAudioOnly()
                        },
                    )
                }
                if (pipAvailable) {
                    Spacer(Modifier.width(8.dp))
                    CircleIconButton(
                        icon = Icons.Filled.PictureInPicture,
                        contentDescription = "Picture in picture",
                        onClick = { context.findActivity()?.enterPip16x9() },
                    )
                }
                Spacer(Modifier.width(8.dp))
                CircleIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = "Add to Multiview",
                    onClick = onAddToMultiview,
                )
            }

            // Info card just below the top-row. Stacks the same status-bar inset
            // so it slides down with the buttons when the system bar is taller.
            channel?.let {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 84.dp, start = 16.dp, end = 16.dp),
                ) {
                    InfoCard(
                        channel = it,
                        programme = nowProgramme,
                        sleepRemainingMillis = sleepRemainingMillis,
                    )
                }
            }

            // Bottom progress + remaining row. navigationBarsPadding keeps the
            // copy clear of the system gesture handle.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 24.dp),
            ) {
                nowProgramme?.let { prog ->
                    EpgProgress(programme = prog)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = prog.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatRemaining(prog),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                } ?: run {
                    // No EPG: just show the channel name centered.
                    Text(
                        text = channel?.name ?: "AerioTV",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }

    if (sleepOpen) {
        SleepTimerSheet(
            current = sleepRemainingMillis,
            onSelect = { minutes ->
                sleepOpen = false
                onSetSleepMinutes(minutes)
            },
            onDismiss = { sleepOpen = false },
        )
    }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun PlayerMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    canRecord: Boolean,
    audioOnly: Boolean,
    sleepActive: Boolean,
    onSubtitles: () -> Unit,
    onRecord: () -> Unit,
    onSleepTimer: () -> Unit,
    onStreamInfo: () -> Unit,
    onAudioOnly: () -> Unit,
) {
    // Each row uses a leading icon for scannability, mirroring iOS's
    // SwiftUI `Label(text, systemImage:)` pattern in PlayerView.swift
    // line 2098+. Material 3 DropdownMenuItem natively supports the
    // leadingIcon slot, so the visual treatment lines up without a
    // custom row wrapper.
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.ClosedCaption,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = { Text("Subtitles") },
            onClick = onSubtitles,
        )
        if (canRecord) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.FiberManualRecord,
                        contentDescription = null,
                        tint = Color(0xFFFF4757),
                    )
                },
                text = { Text("Record Current Program") },
                onClick = onRecord,
            )
        }
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = if (sleepActive)
                        Icons.Filled.Bedtime
                    else
                        Icons.Outlined.Bedtime,
                    contentDescription = null,
                    tint = if (sleepActive)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(if (sleepActive) "Sleep Timer (active)" else "Sleep Timer")
            },
            onClick = onSleepTimer,
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = { Text("Stream Info") },
            onClick = onStreamInfo,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = if (audioOnly)
                        Icons.Filled.MusicNote
                    else
                        Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = if (audioOnly)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                // iOS toggles the label to "Show Video" when in Audio
                // Only mode so the action describes what tapping does,
                // not what state it shows. Port that.
                Text(if (audioOnly) "Show Video" else "Audio Only")
            },
            onClick = onAudioOnly,
        )
    }
}

@Composable
private fun InfoCard(
    channel: M3UChannel,
    programme: EPGProgramme?,
    sleepRemainingMillis: Long?,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            channel.channelNumber?.let { num ->
                Text(
                    text = num.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(28.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.tvgLogo.isNotBlank()) {
                    AsyncImage(
                        model = channel.tvgLogo,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                } else {
                    Text(
                        text = channel.name.take(2).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (programme != null) {
                    Text(
                        text = programme.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    val timeRange = formatTimeRange(programme)
                    val duration = formatDuration(programme.endMillis - programme.startMillis)
                    Text(
                        text = "$timeRange  ·  $duration",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
            sleepRemainingMillis?.let { remaining ->
                val mins = (remaining / 60_000L).coerceAtLeast(0L)
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = "💤 ${mins}m",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EpgProgress(programme: EPGProgramme) {
    val now = System.currentTimeMillis()
    val total = (programme.endMillis - programme.startMillis).coerceAtLeast(1L)
    val elapsed = (now - programme.startMillis).coerceAtLeast(0L).coerceAtMost(total)
    val progress = (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.White.copy(alpha = 0.18f),
        drawStopIndicator = {},
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    current: Long?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                text = "Sleep Timer",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            SLEEP_OPTIONS.forEach { mins ->
                val label = if (mins == 0) "Off" else "$mins minutes"
                val isActive = (mins == 0 && current == null) ||
                        (mins != 0 && current != null && ((current / 60_000L).toInt() in (mins - 1)..mins))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isActive,
                        onClick = { onSelect(mins) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Stream Info ModalBottomSheet, displayed as a stand-alone modal so the user can
 * read the technical details and dismiss. Reads MPV properties at open time;
 * not live-updating per second since codec/format don't change during playback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamInfoSheet(
    snapshot: StreamInfoSnapshot,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                text = "Stream Info",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            StreamInfoSection("VIDEO", snapshot.videoLines)
            Spacer(Modifier.height(10.dp))
            StreamInfoSection("AUDIO", snapshot.audioLines)
            Spacer(Modifier.height(10.dp))
            StreamInfoSection("CACHE", snapshot.cacheLines)
            Spacer(Modifier.height(10.dp))
            StreamInfoSection(" SYNC", snapshot.syncLines)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StreamInfoSection(label: String, lines: List<String>) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(64.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/**
 * Subtitle tracks ModalBottomSheet. Off + one row per MPV `sid` track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitlesSheet(
    tracks: List<SubtitleTrack>,
    currentTrackId: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                text = "Subtitles",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            SubtitleRow(label = "Off", selected = currentTrackId == null, onClick = { onSelect(null) })
            if (tracks.isEmpty()) {
                Text(
                    text = "No subtitle tracks reported by the stream.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                tracks.forEach { track ->
                    val label = buildString {
                        append(track.title.ifBlank { "Track ${track.id}" })
                        if (track.lang.isNotBlank()) append("  ·  ${track.lang}")
                    }
                    SubtitleRow(
                        label = label,
                        selected = currentTrackId == track.id,
                        onClick = { onSelect(track.id) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SubtitleRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

private fun formatRemaining(programme: EPGProgramme): String {
    val remainingMs = (programme.endMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    val minutes = remainingMs / 60_000L
    if (minutes <= 0L) return "ending"
    if (minutes < 60L) return "$minutes min remaining"
    val hours = minutes / 60L
    val leftover = minutes % 60L
    return if (leftover == 0L) "$hours h remaining" else "$hours h $leftover min remaining"
}

private fun formatTimeRange(programme: EPGProgramme): String {
    val tf = DateFormat.getTimeInstance(DateFormat.SHORT)
    return "${tf.format(Date(programme.startMillis))} – ${tf.format(Date(programme.endMillis))}"
}

private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return ""
    val totalMinutes = ((millis + 30_000L) / 60_000L).toInt()
    if (totalMinutes < 60) return "${totalMinutes}m"
    val hours = totalMinutes / 60
    val mins = totalMinutes % 60
    return if (mins == 0) "${hours}h" else "${hours}h ${mins}m"
}

private val SLEEP_OPTIONS = listOf(0, 30, 60, 90, 120)

// ──────────────────────────────────────────────────────────────────────────
// Models exported for PlayerScreen to populate from MPVPlayerView properties.
// ──────────────────────────────────────────────────────────────────────────

data class SubtitleTrack(
    val id: Int,
    val title: String,
    val lang: String,
)

data class StreamInfoSnapshot(
    val videoLines: List<String>,
    val audioLines: List<String>,
    val cacheLines: List<String>,
    val syncLines: List<String>,
)
