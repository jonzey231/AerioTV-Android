package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.aeriotv.android.core.sync.SetupSourceChoice
import com.aeriotv.android.ui.settings.SettingsAlertDialog
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import com.aeriotv.android.ui.settings.settingsRowCard

/**
 * Rule 4 prompt (phone and TV): "Which setup should this device start from?"
 * Two radio-style cards, D-pad focusable; the more recently updated side is
 * selected when it opens. Continue applies the selection, Cancel backs out.
 */
@Composable
fun SetupSourceDialog(
    drive: SetupSourceChoice.Summary,
    dsync: SetupSourceChoice.Summary,
    busy: Boolean = false,
    onContinue: (SetupSourceChoice.Source) -> Unit,
    onCancel: () -> Unit,
) {
    var selected by remember(drive, dsync) { mutableStateOf(SetupSourceChoice.defaultSelection(drive, dsync)) }
    val now = remember { System.currentTimeMillis() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    SettingsAlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(SetupSourceChoice.TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceCard(
                    title = SetupSourceChoice.DISPATCHARR,
                    summary = dsync,
                    now = now,
                    selected = selected == SetupSourceChoice.Source.Dispatcharr,
                    modifier = if (selected == SetupSourceChoice.Source.Dispatcharr) Modifier.focusRequester(firstFocus) else Modifier,
                    onSelect = { selected = SetupSourceChoice.Source.Dispatcharr },
                )
                SourceCard(
                    title = SetupSourceChoice.GOOGLE_DRIVE,
                    summary = drive,
                    now = now,
                    selected = selected == SetupSourceChoice.Source.Drive,
                    modifier = if (selected == SetupSourceChoice.Source.Drive) Modifier.focusRequester(firstFocus) else Modifier,
                    onSelect = { selected = SetupSourceChoice.Source.Drive },
                )
                Text(
                    SetupSourceChoice.FOOTER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            // Never disabled mid-flow (TV focus would fall away); taps are ignored while busy.
            SettingsDialogTextButton(
                label = SetupSourceChoice.CONTINUE,
                onClick = { if (!busy) onContinue(selected) },
            )
        },
        dismissButton = {
            SettingsDialogTextButton(label = SetupSourceChoice.CANCEL, onClick = { if (!busy) onCancel() })
        },
    )
}

@Composable
private fun SourceCard(
    title: String,
    summary: SetupSourceChoice.Summary,
    now: Long,
    selected: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .settingsRowCard(focused)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.size(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                SetupSourceChoice.updatedLine(summary.updatedAt, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                SetupSourceChoice.countLine(summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
