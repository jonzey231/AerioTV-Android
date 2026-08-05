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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.core.update.UpdateState
import com.aeriotv.android.feature.update.UpdateViewModel
import com.aeriotv.android.ui.adaptive.adaptiveFormWidth
import com.aeriotv.android.ui.settings.SettingsActionRow
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsInfoRow
import com.aeriotv.android.ui.settings.SettingsRowContainer
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset

/**
 * Settings > App Updates (github flavor only; the row that opens this screen
 * is hidden when the updater is disabled). Manual check + the full
 * download/install state, mirroring the launch prompt's actions.
 *
 * Every actionable element is a [SettingsActionRow] (the SettingsRowContainer
 * family), NOT a Material button: those rows are the screen's D-pad focus
 * unit, with the card highlight TV users can see. A plain OutlinedButton here
 * was unreachable/invisible to D-pad focus on the Streamer.
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
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = LocalTabBarBottomInset.current),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettingsSection(
                    header = "This device",
                    footer = "Updates on this channel come from the project's GitHub " +
                        "releases. Installing keeps your channels, settings, and " +
                        "recordings; AerioTV closes during the install and you reopen " +
                        "it from your home screen.",
                ) {
                    SettingsInfoRow(label = "Version", value = BuildConfig.VERSION_NAME)
                    SettingsInfoRow(label = "Channel", value = "GitHub releases")
                    SettingsActionRow(
                        label = "Check for updates",
                        leadingIcon = Icons.Filled.Refresh,
                        onClick = { viewModel.manualCheck() },
                    )
                }

                when (val s = state) {
                    is UpdateState.UpToDate -> StatusText("You're on the latest version.")
                    is UpdateState.Available -> SettingsSection(
                        header = "Update available",
                        footer = s.info.notes.ifBlank { null },
                    ) {
                        SettingsActionRow(
                            label = "Download AerioTV ${s.info.versionName}",
                            subtitle = "${s.info.apkSizeBytes / (1024 * 1024)} MB from GitHub",
                            leadingIcon = Icons.Filled.Download,
                            onClick = { viewModel.download() },
                        )
                    }
                    is UpdateState.Downloading -> Column {
                        StatusText("Downloading AerioTV ${s.info.versionName}... ${s.progressPercent}%")
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { s.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is UpdateState.Verifying -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp))
                        Spacer(Modifier.width(10.dp))
                        StatusText("Verifying download...")
                    }
                    is UpdateState.ReadyToInstall -> SettingsSection(
                        header = "Ready to install",
                        footer = "Your data is kept. AerioTV will close to install; reopen " +
                            "it from your home screen.",
                    ) {
                        SettingsActionRow(
                            label = "Install AerioTV ${s.info.versionName}",
                            leadingIcon = Icons.Filled.SystemUpdate,
                            onClick = { viewModel.install() },
                        )
                    }
                    is UpdateState.AwaitingInstallPermission -> SettingsSection(
                        header = "One-time permission needed",
                        footer = "Allow AerioTV to install updates in the Settings screen, " +
                            "then come back. If you've already allowed it, Install " +
                            "continues right away.",
                    ) {
                        SettingsActionRow(
                            label = "Install",
                            leadingIcon = Icons.Filled.SystemUpdate,
                            onClick = { viewModel.install() },
                        )
                    }
                    is UpdateState.Installing -> StatusText(
                        "Confirm the update in the Android dialog. AerioTV will close to " +
                            "install.",
                    )
                    is UpdateState.Error -> SettingsSection(
                        header = "Update problem",
                        footer = s.message,
                    ) {
                        SettingsActionRow(
                            label = if (s.info != null) "Try again" else "Check again",
                            leadingIcon = Icons.Filled.Refresh,
                            onClick = {
                                if (s.info != null) viewModel.download() else viewModel.manualCheck()
                            },
                        )
                        SettingsActionRow(
                            label = "Dismiss",
                            leadingIcon = Icons.Filled.Close,
                            onClick = { viewModel.dismissError() },
                        )
                    }
                    UpdateState.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
