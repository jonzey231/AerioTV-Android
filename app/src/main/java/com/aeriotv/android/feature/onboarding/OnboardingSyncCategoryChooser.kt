package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.sync.SyncCategory

/**
 * Shown the moment the user asks to sign into Drive during onboarding, BEFORE
 * sign-in runs and therefore before anything is pulled.
 *
 * Discord (Glitzbr, reported on Apple): he enabled sync on a second TV and a
 * remote button map customised for a different remote came down with the rest.
 * The per-category toggles already existed - but they live in Settings, and by
 * the time you go looking the data has landed. Logan 2026-08-10: ask during
 * setup, while the answer still changes the outcome. Apple's WelcomeView has
 * the same sheet.
 *
 * Everything defaults ON, so the common case is one press. Confirming writes
 * the same `syncCategory.<suffix>` keys the Sync settings screen binds to, so
 * there is one source of truth and the choice is visible and reversible later
 * in the place users expect.
 */
@Composable
fun OnboardingSyncCategoryChooser(
    onConfirm: (Map<SyncCategory, Boolean>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Local until Confirm, so backing out cannot leave a half-applied set.
    val selection = remember {
        mutableStateMapOf<SyncCategory, Boolean>().apply {
            SyncCategory.entries.forEach { put(it, true) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What should come in from Drive?") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "You can change any of this later in Settings, and each " +
                        "device chooses for itself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SyncCategory.entries.forEach { category ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = category.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = category.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = selection[category] ?: true,
                            onCheckedChange = { selection[category] = it },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selection.toMap()) }) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}
