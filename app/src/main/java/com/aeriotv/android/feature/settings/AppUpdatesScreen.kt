package com.aeriotv.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.core.update.UpdateState
import com.aeriotv.android.feature.update.UpdateViewModel
import com.aeriotv.android.ui.adaptive.adaptiveFormWidth

/**
 * Settings > App Updates (github flavor only; the row that opens this screen
 * is hidden when the updater is disabled). Manual check + the full
 * download/install state, mirroring the launch prompt's actions. Manual
 * checks bypass the 12h auto-check throttle and ignore a skipped version.
 */
@Composable
fun AppUpdatesScreen(
    onBack: () -> Unit,
    viewModel: UpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "App Updates", onBack = onBack)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .adaptiveFormWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettingsSection(
                    header = "This device",
                    footer = "Updates on this channel come from the project's GitHub " +
                        "releases. Installing keeps your channels, settings, and " +
                        "recordings; AerioTV closes during the install and you reopen " +
                        "it from your home screen.",
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "AerioTV ${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = "Channel: GitHub releases",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.manualCheck() },
                            enabled = state !is UpdateState.Downloading &&
                                state !is UpdateState.Installing,
                        ) { Text("Check for updates") }
                    }
                }

                when (val s = state) {
                    is UpdateState.UpToDate -> StatusCard("You're on the latest version.")
                    is UpdateState.Available -> ActionCard(
                        title = "AerioTV ${s.info.versionName} is available",
                        body = s.info.notes.ifBlank { "A new release is ready to download." },
                        actionLabel = "Download (${s.info.apkSizeBytes / (1024 * 1024)} MB)",
                        onAction = { viewModel.download() },
                    )
                    is UpdateState.Downloading -> Column {
                        StatusCard("Downloading AerioTV ${s.info.versionName}...")
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { s.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is UpdateState.Verifying -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Verifying download...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is UpdateState.ReadyToInstall -> ActionCard(
                        title = "AerioTV ${s.info.versionName} is ready to install",
                        body = "Your data is kept. AerioTV will close to install; reopen " +
                            "it from your home screen.",
                        actionLabel = "Install",
                        onAction = { viewModel.install() },
                    )
                    is UpdateState.AwaitingInstallPermission -> ActionCard(
                        title = "One-time permission needed",
                        body = "Allow AerioTV to install updates in the Settings screen " +
                            "that opens, then come back and tap Install.",
                        actionLabel = "Open Settings",
                        onAction = { viewModel.install() },
                    )
                    is UpdateState.Installing -> StatusCard(
                        "Confirm the update in the Android dialog. AerioTV will close to " +
                            "install.",
                    )
                    is UpdateState.Error -> ActionCard(
                        title = "Update problem",
                        body = s.message,
                        actionLabel = if (s.info != null) "Try again" else "Check again",
                        onAction = {
                            if (s.info != null) viewModel.download() else viewModel.manualCheck()
                        },
                        secondaryLabel = "Dismiss",
                        onSecondary = { viewModel.dismissError() },
                    )
                    UpdateState.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun StatusCard(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun ActionCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    SettingsSection(header = "Update") {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (secondaryLabel != null && onSecondary != null) {
                    TextButton(onClick = onSecondary) { Text(secondaryLabel) }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
