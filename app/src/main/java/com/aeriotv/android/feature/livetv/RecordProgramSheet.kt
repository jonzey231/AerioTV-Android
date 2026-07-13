package com.aeriotv.android.feature.livetv

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.feature.dvr.DvrViewModel
import com.aeriotv.android.feature.dvr.LocalRecordingService
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.settings.SettingsViewModel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Bottom-sheet form to schedule a programme recording. Mirrors iOS
 * `RecordProgramSheet` (RecordProgramSheet.swift) and the canon spec
 * (project_aeriotv_ios_canon.md:191).
 *
 * UI is complete; the actual schedule call is a Phase 9 (DVR) deliverable.
 * Tapping "Record" emits a Toast informing the user that DVR is en route,
 * matching the established stub pattern for not-yet-wired backends.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordProgramSheet(
    target: ProgramInfoTarget,
    onDismiss: () -> Unit,
    dvrViewModel: DvrViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isLive = remember(target) {
        val now = System.currentTimeMillis()
        target.startMillis <= now
    }

    // Pull the user's default pre/post-roll so the radios pre-select correctly.
    val defaultPreRoll by settingsViewModel.dvrDefaultPreRollMins.collectAsStateWithLifecycle(initialValue = 0)
    val defaultPostRoll by settingsViewModel.dvrDefaultPostRollMins.collectAsStateWithLifecycle(initialValue = 0)
    val storageCapMB by settingsViewModel.dvrMaxLocalStorageMB.collectAsStateWithLifecycle(initialValue = 10_240)
    val dvrState by dvrViewModel.state.collectAsStateWithLifecycle()

    // iOS parity: derive recording capability from the ACTIVE playlist. A
    // non-admin Dispatcharr account (or any non-Dispatcharr source) can only
    // record to this device, so the destination toggle is hidden and the
    // record defaults to local. Mirrors iOS RecordProgramSheet.swift.
    //
    // Sourced through DvrViewModel (which resolves playlistRepository
    // .activePlaylist() in its init) and read REACTIVELY off dvrState. The prior
    // code read playlistViewModel.state.value.playlist NON-reactively at
    // composition: on a freshly-created PlaylistViewModel whose active-playlist
    // load had not finished, .playlist was null, so canRecordToServer computed
    // false ONCE and the server destination toggle stayed hidden for admins for
    // the whole life of the sheet (a .value read never triggers recomposition).
    // A fresh DvrViewModel always reloads activePlaylist() in init, and dvrState
    // is collected reactively, so the toggle now appears when refresh() lands and
    // destinationServer re-seeds via remember(canRecordToServer) below.
    val canRecordToServer = dvrState.canRecordToServer
    val isDispatcharr = dvrState.isDispatcharrDirect

    var preRoll by remember(defaultPreRoll) { mutableStateOf(defaultPreRoll) }
    var postRoll by remember(defaultPostRoll) { mutableStateOf(defaultPostRoll) }
    // Seed the destination from capability; re-seed if it resolves after the
    // first composition. Non-admin seeds false and the toggle is hidden, so it
    // can never flip back to server.
    var destinationServer by remember(canRecordToServer) { mutableStateOf(canRecordToServer) }
    var removeCommercials by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    com.aeriotv.android.ui.FormFactorModal(
        onDismiss = onDismiss,
        tvWidthFraction = 0.7f,
        tvMaxHeight = 620.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // Header row: Cancel / title / Record. Record is the destructive-accent
            // colour iOS uses (LIVE_RED) to signal it commits a scheduled action.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (isLive) "Record from Now" else "Record Program",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = !submitting,
                    onClick = {
                        val dispatcharrId = target.channelDispatcharrId
                        if (!destinationServer) {
                            // Local recording — check the storage cap before
                            // committing. usedBytes comes from the existing
                            // local rows in DvrViewModel state.
                            val usedBytes = dvrState.recordings
                                .filter { it.source == DvrViewModel.Source.Local }
                                .sumOf { it.fileSizeBytes }
                            val usedMB = (usedBytes / (1024L * 1024L)).toInt()
                            if (usedMB >= storageCapMB) {
                                Toast.makeText(
                                    context,
                                    "Local storage cap reached. Free space or raise the cap in Settings -> DVR.",
                                    Toast.LENGTH_LONG,
                                ).show()
                                onDismiss()
                                return@TextButton
                            }
                            val playlistState = playlistViewModel.state.value
                            val channel = playlistState.channels.firstOrNull {
                                it.name == target.channelName
                            }
                            val streamUrl = channel?.url
                            val apiKey = playlistState.playlist?.apiKey
                            if (streamUrl.isNullOrBlank()) {
                                Toast.makeText(
                                    context,
                                    "Couldn't locate stream URL for ${target.channelName}.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                val durationMs = (target.endMillis + postRoll * 60_000L) -
                                        System.currentTimeMillis()
                                LocalRecordingService.start(
                                    context = context,
                                    streamUrl = streamUrl,
                                    title = target.title.ifBlank { target.channelName },
                                    channelName = target.channelName,
                                    apiKey = apiKey.orEmpty(),
                                    durationMs = durationMs.coerceAtLeast(60_000L),
                                )
                                Toast.makeText(
                                    context,
                                    "Recording started locally.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            onDismiss()
                            return@TextButton
                        }
                        if (dispatcharrId == null) {
                            Toast.makeText(
                                context,
                                "Recording requires a Dispatcharr playlist.",
                                Toast.LENGTH_SHORT,
                            ).show()
                            onDismiss()
                            return@TextButton
                        }
                        submitting = true
                        val effectiveStart = target.startMillis - preRoll * 60_000L
                        val effectiveEnd = target.endMillis + postRoll * 60_000L
                        scope.launch {
                            val result = dvrViewModel.scheduleServerRecording(
                                channelDispatcharrId = dispatcharrId,
                                startMillis = effectiveStart,
                                endMillis = effectiveEnd,
                                title = target.title,
                                description = target.description,
                                comskip = removeCommercials,
                            )
                            submitting = false
                            val msg = result.fold(
                                onSuccess = { "Scheduled: ${target.title.ifBlank { "recording" }}" },
                                onFailure = { t -> "Schedule failed: ${t.message ?: t::class.simpleName}" },
                            )
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    },
                ) {
                    Text(
                        text = if (submitting) "Scheduling…" else "Record",
                        color = LIVE_RED,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            Spacer(Modifier.height(12.dp))

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                LabeledValue("Program", target.title.ifBlank { "Untitled" })
                LabeledValue("Channel", target.channelName)
                LabeledValue("Time", formatTimeRange(target))

                Spacer(Modifier.height(18.dp))
                SectionLabel("Start Early")
                Spacer(Modifier.height(6.dp))
                if (isLive) {
                    Text(
                        text = "Pre-roll unavailable (program already started).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MinuteRadioFlow(
                        options = ROLL_OPTIONS,
                        selected = preRoll,
                        onSelect = { preRoll = it },
                    )
                }

                Spacer(Modifier.height(18.dp))
                SectionLabel("End Late")
                Spacer(Modifier.height(6.dp))
                MinuteRadioFlow(
                    options = ROLL_OPTIONS,
                    selected = postRoll,
                    onSelect = { postRoll = it },
                )

                // Destination toggle only for admins. A non-admin Dispatcharr
                // account (or any non-Dispatcharr source) can only record to
                // this device, so the server option is hidden and the record is
                // forced local (destinationServer seeded false above).
                if (canRecordToServer) {
                    Spacer(Modifier.height(18.dp))
                    SectionLabel("Destination")
                    Spacer(Modifier.height(6.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = destinationServer,
                            onClick = { destinationServer = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("Dispatcharr server") }
                        SegmentedButton(
                            selected = !destinationServer,
                            onClick = { destinationServer = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("This device") }
                    }
                }

                // iOS parity: a Dispatcharr non-admin live recording lands on
                // this device. Surface an orange callout explaining why the
                // server option isn't available. Mirrors iOS .orange
                // "Saving to this device" hint.
                if (isDispatcharr && !canRecordToServer) {
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Storage,
                            contentDescription = null,
                            tint = Color(0xFFFF9500), // iOS .orange
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Saving to this device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Recording to the Dispatcharr server requires a Dispatcharr admin account.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Comskip is server-side only; hide it entirely when the account
                // can't record to the server (iOS parity).
                if (canRecordToServer) {
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Remove commercials (Comskip)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Detect and remove ad breaks after recording. Processed server-side.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = removeCommercials,
                            onCheckedChange = { removeCommercials = it },
                            enabled = destinationServer,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinuteRadioFlow(
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { mins ->
            val label = if (mins == 0) "None" else "$mins min"
            Row(
                modifier = Modifier
                    .selectable(
                        selected = mins == selected,
                        onClick = { onSelect(mins) },
                    )
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = mins == selected,
                    onClick = null,
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
    }
}

private fun formatTimeRange(target: ProgramInfoTarget): String {
    val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    return "${dateFormat.format(Date(target.startMillis))} at " +
            "${timeFormat.format(Date(target.startMillis))} – " +
            timeFormat.format(Date(target.endMillis))
}

private val ROLL_OPTIONS = listOf(0, 5, 10, 15, 30, 60)
private val LIVE_RED = Color(0xFFFF4757)
