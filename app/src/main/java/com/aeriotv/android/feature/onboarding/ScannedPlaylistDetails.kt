package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Compact step shown after a Dispatcharr user QR is scanned (camera, Photos,
 * or code file). Lets the user set the playlist name, guide days, and the
 * recording destination before Connect runs the same Test Connection path
 * as the full form. Phone and tablet only. Never shows the API key.
 */
@Composable
internal fun ScannedPlaylistDetails(
    username: String?,
    host: String?,
    name: String,
    onNameChange: (String) -> Unit,
    isLoading: Boolean,
    error: String?,
    guideRow: @Composable () -> Unit,
    dvrRow: @Composable () -> Unit,
    onConnect: () -> Unit,
    onEditAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Playlist details",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        val who = username?.takeIf { it.isNotBlank() }
        val line = when {
            who != null && host != null -> "Scanned $who on $host"
            who != null -> "Scanned $who"
            host != null -> "Scanned a sign-in code for $host"
            else -> "Scanned a sign-in code"
        }
        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        LabeledField(label = "Name") {
            IconTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = "My IPTV Server",
                leading = Icons.Outlined.Sell,
                enabled = !isLoading,
            )
        }
        guideRow()
        dvrRow()

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onConnect,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Connect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        TextButton(
            onClick = onEditAll,
            enabled = !isLoading,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text("Edit all details") }
    }
}
