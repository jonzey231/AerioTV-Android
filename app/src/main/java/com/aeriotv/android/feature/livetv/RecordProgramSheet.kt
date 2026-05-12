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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

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
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val isLive = remember(target) {
        val now = System.currentTimeMillis()
        target.startMillis <= now
    }

    var preRoll by remember { mutableStateOf(0) }
    var postRoll by remember { mutableStateOf(0) }
    var destinationServer by remember { mutableStateOf(true) }
    var removeCommercials by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
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
                            Toast.makeText(
                                context,
                                "Local recording lands with Phase 9b.",
                                Toast.LENGTH_SHORT,
                            ).show()
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
