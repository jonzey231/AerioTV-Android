package com.aeriotv.android.feature.dvr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * Edit a scheduled Dispatcharr server recording. Mirrors iOS Edit Recording
 * sheet: title + description text fields, pre-roll + post-roll radio rows
 * adjusted relative to the original scheduled window. The original program
 * window is treated as immutable here — pre/post-roll deltas adjust outward
 * from those anchors.
 *
 * Constraint: this surface is reachable only for [DvrViewModel.Source.Server]
 * + [DvrViewModel.Recording.Status.Scheduled] rows, gated by the long-press
 * menu in DvrTabContent. Local recordings are not editable in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditRecordingSheet(
    recording: DvrViewModel.Recording,
    onDismiss: () -> Unit,
    onSave: (startMillis: Long, endMillis: Long, title: String, description: String) -> Unit,
) {
    var title by remember(recording.id) { mutableStateOf(recording.title) }
    var description by remember(recording.id) { mutableStateOf(recording.description) }
    // Pre/post-roll are stored as deltas in minutes from the existing window.
    // 0 = leave the start/end untouched; positive = expand outward.
    var preRoll by remember(recording.id) { mutableIntStateOf(0) }
    var postRoll by remember(recording.id) { mutableIntStateOf(0) }

    val newStart = recording.startMillis - preRoll * 60_000L
    val newEnd = recording.endMillis + postRoll * 60_000L
    val canSave = newEnd > newStart && title.trim().isNotEmpty()
    val timeFmt = com.aeriotv.android.core.ui.ClockFormat.short()

    com.aeriotv.android.ui.FormFactorModal(
        onDismiss = onDismiss,
        tvWidthFraction = 0.7f,
        tvMaxHeight = 620.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Edit Recording",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                ),
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions(),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))

            SectionHeader("Extra Pre-Roll")
            DeltaRollRow(selected = preRoll, onSelect = { preRoll = it })

            SectionHeader("Extra Post-Roll")
            DeltaRollRow(selected = postRoll, onSelect = { postRoll = it })

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Will record",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${timeFmt.format(Date(newStart))} – ${timeFmt.format(Date(newEnd))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(
                    onClick = { if (canSave) onSave(newStart, newEnd, title.trim(), description.trim()) },
                    enabled = canSave,
                ) {
                    Text(
                        text = "Save",
                        color = if (canSave) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun DeltaRollRow(selected: Int, onSelect: (Int) -> Unit) {
    Column {
        DELTA_OPTIONS.forEachIndexed { idx, mins ->
            val label = if (mins == 0) "No change" else "+$mins min"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(mins) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = mins == selected,
                    onClick = { onSelect(mins) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private val DELTA_OPTIONS: List<Int> = listOf(0, 5, 10, 15, 30, 60)
