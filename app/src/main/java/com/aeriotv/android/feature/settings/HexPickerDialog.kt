package com.aeriotv.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.category.ProgramCategory
import com.aeriotv.android.core.category.parseHex

/**
 * Hex-string colour picker dialog for a category palette row. The iOS app
 * uses SwiftUI's `ColorPicker`; we use a 6-char hex text field + live swatch
 * preview + Reset/Save actions. Lightweight and accessible — no third-party
 * HSV wheel dep required for v1 parity.
 *
 * Input validation: 6-char hex (case-insensitive, with or without leading '#').
 * Anything else disables Save without showing an error toast.
 */
@Composable
fun HexPickerDialog(
    bucket: ProgramCategory,
    currentHex: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    var input by remember { mutableStateOf(currentHex.uppercase()) }
    val sanitized = input.trim().removePrefix("#").uppercase()
    val isValid = sanitized.length == 6 && sanitized.all { it in HEX_CHARS }
    val previewColor = if (isValid) parseHex(sanitized) else parseHex(currentHex)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { if (isValid) onSave(sanitized) },
                enabled = isValid && sanitized != currentHex.uppercase(),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        title = { Text("${bucket.displayName} Color") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(previewColor)
                            .border(
                                1.5.dp,
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp),
                            ),
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Default ${bucket.defaultHex}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (isValid) "Preview $sanitized" else "Enter 6-char hex",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { raw ->
                        val cleaned = raw.removePrefix("#").uppercase().filter { it in HEX_CHARS }.take(6)
                        input = cleaned
                    },
                    label = { Text("Hex color (e.g. ${bucket.defaultHex})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val HEX_CHARS: Set<Char> = (('0'..'9') + ('A'..'F')).toSet()
