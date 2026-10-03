package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.scale.subtext
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.WarningAmber
import com.aeriotv.android.ui.scale.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.core.debug.DebugLogger
import com.aeriotv.android.ui.settings.settingsFormWidth
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import com.aeriotv.android.ui.settings.SettingsPickerOption
import com.aeriotv.android.ui.settings.SettingsPickerRow
import com.aeriotv.android.ui.settings.SettingsSubPageHost
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.settings.settingsRowCard
import com.aeriotv.android.ui.settings.settingsTvFocusableRow
import com.aeriotv.android.ui.settings.SettingsRowContainer
import com.aeriotv.android.ui.settings.SettingsToggleAffordance
import com.aeriotv.android.ui.settings.settingsFootnoteStyle
import com.aeriotv.android.ui.settings.settingsRowTitleStyle
import com.aeriotv.android.ui.theme.textAccent
import com.aeriotv.android.ui.settings.SettingsToggleRow
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset

/**
 * Settings -> Developer. Mirrors iOS DeveloperSettingsView (lines 9-444):
 *
 *  - Build / Device facts at the top (always-on, read-only).
 *  - Logging section: Debug Logging master toggle with iOS-style confirm
 *    dialogs on enable / disable, so the user can read what file-logging
 *    actually does before flipping it on.
 *  - Log File section (appears once logging has been on or the file
 *    already exists): file size readout, View Log File, Share Log File,
 *    Clear Log File. View opens an in-app viewer; Share hands the file
 *    off via FileProvider so the user can attach it to a GitHub Issue.
 *  - What's Captured: documentation rows so the user knows exactly what
 *    flipping the toggle on starts persisting.
 *
 * Mirrors iOS section copy verbatim where the meaning carries cleanly
 * across platforms ("Logs include network requests, playback events..."
 * etc.). Android-specific lines call out filesDir-based storage instead
 * of iOS's "On My iPhone › AerioTV" Files-app shorthand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperSettingsScreen(
    onBack: () -> Unit,
    onOpenLogViewer: () -> Unit = {},
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val debugLogger = remember {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            com.aeriotv.android.core.debug.DebugLoggerEntryPoint::class.java,
        )
        entry.debugLogger()
    }
    val loggingEnabled by settingsVm.debugLoggingEnabled.collectAsStateWithLifecycle(initialValue = false)
    val castForceHevc by settingsVm.castForceHevcTranscode.collectAsStateWithLifecycle(initialValue = false)
    val castDownProfile by settingsVm.castTranscodeDownProfile.collectAsStateWithLifecycle(initialValue = "720p60")

    val isTv = rememberIsTvDevice()
    var pendingEnable by remember { mutableStateOf(false) }
    var pendingDisable by remember { mutableStateOf(false) }
    var pendingClear by remember { mutableStateOf(false) }
    var showQrShare by remember { mutableStateOf(false) }

    // Poll the file size while we're on this screen so the displayed value
    // tracks live writes. 1 Hz is plenty for a multi-MB file; iOS does the
    // same in refreshLogSize via .task().
    var sizeBytes by remember { mutableLongStateOf(debugLogger.totalLogSizeBytes()) }
    LaunchedEffect(Unit) {
        while (true) {
            sizeBytes = debugLogger.totalLogSizeBytes()
            delay(1000L)
        }
    }
    val logFileExists = sizeBytes > 0L || debugLogger.logFile().exists()

    SettingsSubPageHost {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "Developer", onBack = onBack)

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
        LazyColumn(
            // Phase B1 bug fix: this was the only settings screen missing the
            // readable-width cap, so on a tablet or TV its rows stretched the
            // full window while every sibling screen capped. Matches the
            // Network/DVR/Playlist pattern.
            modifier = Modifier.settingsFormWidth().fillMaxSize(),
            // 104dp bottom clears the MainScaffold NavigationBar so the
            // Build section (version code, "What's Captured" list) isn't
            // clipped.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = LocalTabBarBottomInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item("logging") {
                LoggingSection(
                    enabled = loggingEnabled,
                    onRequestEnable = { pendingEnable = true },
                    onRequestDisable = { pendingDisable = true },
                )
            }
            // Section order follows Apple per platform. Phone: Logging, What's
            // Captured, Cast, Log File. TV: Logging, Log File, What's Captured,
            // and no Cast section at all (casting is a phone-sender feature).
            // Apple's Playback Engine section sits after What's Captured on
            // iOS and after Logging on tvOS; Android has no engine choice yet.
            if (!isTv) {
                item("captured") { WhatsCapturedSection() }
                item("cast") {
                    CastTestSection(
                        forceTranscode = castForceHevc,
                        onForceTranscodeChange = settingsVm::setCastForceHevcTranscode,
                        downProfile = castDownProfile,
                        onDownProfileChange = settingsVm::setCastTranscodeDownProfile,
                    )
                }
            }
            // Apple shows the file rows as soon as logging is on, not only once
            // the first write lands, so the user sees the rows the moment they
            // enable it.
            if (loggingEnabled || logFileExists) {
                item("log-file") {
                    LogFileSection(
                        sizeBytes = sizeBytes,
                        isTv = isTv,
                        onView = onOpenLogViewer,
                        // ACTION_SEND has no useful targets on Google TV, so
                        // TV serves the file over LAN behind a QR code (the
                        // tvOS LogShareServer port). Phones keep the chooser.
                        onShare = { if (isTv) showQrShare = true else shareLogFile(context, debugLogger) },
                        onClear = { pendingClear = true },
                    )
                }
            }
            if (isTv) item("captured") { WhatsCapturedSection() }

            item("build") { BuildInfoSection() }
        }
        }
    }
    }

    if (pendingEnable) {
        AlertDialog(
            onDismissRequest = { pendingEnable = false },
            title = { Text("Enable Debug Logging?") },
            text = {
                Text(
                    "AerioTV will write detailed diagnostic logs to a file in the app's " +
                        "private storage.\n\nLogs include network requests, playback events, " +
                        "and error details. They never leave the device unless you share the " +
                        "file from this screen.\n\nLogging has a minor impact on performance " +
                        "and storage. You can disable it at any time.",
                )
            },
            confirmButton = {
                SettingsDialogTextButton(
                    label = "Enable Logging",
                    onClick = {
                        pendingEnable = false
                        settingsVm.setDebugLoggingEnabled(true)
                    },
                )
            },
            dismissButton = {
                SettingsDialogTextButton(label = "Cancel", onClick = { pendingEnable = false })
            },
        )
    }

    if (pendingDisable) {
        AlertDialog(
            onDismissRequest = { pendingDisable = false },
            title = { Text("Disable Debug Logging?") },
            text = {
                Text("The existing log file will be kept. You can share or clear it at any time.")
            },
            confirmButton = {
                SettingsDialogTextButton(
                    label = "Disable",
                    onClick = {
                        pendingDisable = false
                        settingsVm.setDebugLoggingEnabled(false)
                    },
                    destructive = true,
                )
            },
            dismissButton = {
                SettingsDialogTextButton(label = "Keep Logging", onClick = { pendingDisable = false })
            },
        )
    }

    if (pendingClear) {
        AlertDialog(
            onDismissRequest = { pendingClear = false },
            title = { Text("Delete All Logs?") },
            text = {
                Text(
                    "This permanently deletes the current log and any rotated " +
                        "archives. This cannot be undone.",
                )
            },
            confirmButton = {
                SettingsDialogTextButton(
                    label = "Delete All Logs",
                    onClick = {
                        pendingClear = false
                        debugLogger.deleteAllLogs()
                    },
                    destructive = true,
                )
            },
            dismissButton = {
                SettingsDialogTextButton(label = "Cancel", onClick = { pendingClear = false })
            },
        )
    }

    if (showQrShare) {
        TvLogShareDialog(
            file = debugLogger.logFile(),
            onDismiss = { showQrShare = false },
        )
    }
}

@Composable
private fun LoggingSection(
    enabled: Boolean,
    onRequestEnable: () -> Unit,
    onRequestDisable: () -> Unit,
) {
    SettingsSection(
        header = "Logging",
        // tvOS has no footer under Debug Logging.
        footer = if (rememberIsTvDevice()) null else "When enabled, detailed logs are written to a file in the app's private " +
            "storage. Logs include network requests, playback events, EPG activity, errors, " +
            "and app lifecycle events. No personally identifiable information is collected.",
    ) {
        DevToggleRow(
            icon = if (enabled) Icons.Filled.BugReport else Icons.Outlined.BugReport,
            title = "Debug Logging",
            subtitle = if (enabled) "Active: writing to aerio_debug_logs.txt"
            else "Off: no data is collected",
            checked = enabled,
            // Route through the confirm dialogs instead of flipping directly.
            onCheckedChange = { value -> if (value) onRequestEnable() else onRequestDisable() },
        )
    }
}

/**
 * Cast video transcode test switches (iOS DeveloperSettingsView parity,
 * 2026-09-26): re-encode any H.264 source on the phone, HEVC when the
 * receiver presents it, else the H.264 profile picked below (720p at the
 * source rate, or the source size at half rate).
 */
@Composable
private fun CastTestSection(
    forceTranscode: Boolean,
    onForceTranscodeChange: (Boolean) -> Unit,
    downProfile: String,
    onDownProfileChange: (String) -> Unit,
) {
    SettingsSection(header = "Cast") {
        SettingsToggleRow(
            title = "Transcode Video to HEVC (Test)",
            subtitle = "Re-encode every H.264 channel; H.264 profile below when the receiver has no HEVC",
            checked = forceTranscode,
            onCheckedChange = onForceTranscodeChange,
        )
        // Phone only (the section is hidden on TV), where the picker pushes
        // its choices from a row inside this card.
        SettingsPickerRow(
            title = "H.264 Transcode Profile",
            subtitle = "720p60 keeps motion; 1080p30 keeps resolution",
            options = listOf(
                SettingsPickerOption("720p60", "720p60"),
                SettingsPickerOption("1080p30", "1080p30"),
            ),
            selected = if (downProfile == "1080p30") "1080p30" else "720p60",
            onSelect = onDownProfileChange,
        )
    }
}

@Composable
private fun LogFileSection(
    sizeBytes: Long,
    isTv: Boolean,
    onView: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    SettingsSection(
        header = "Log File",
        footer = "Logs rotate automatically when the file exceeds 10 MB. The previous log is " +
            "preserved as aerio_debug_logs_archive.txt.",
    ) {
        DevLogSizeRow(sizeText = formatBytes(sizeBytes))
        DevActionRow(
            icon = Icons.Outlined.Article,
            label = "View Log File",
            subtitle = "Scroll through entries in the app",
            onClick = onView,
        )
        DevActionRow(
            icon = Icons.Filled.Share,
            label = "Share Log File",
            subtitle = if (isTv) "Scan a QR code with your phone" else "Email, Messages, Drive, etc.",
            onClick = onShare,
        )
        DevActionRow(
            icon = Icons.Filled.Delete,
            label = "Delete All Logs",
            subtitle = "Removes the current log and rotated archives",
            onClick = onClear,
            destructive = true,
        )
    }
}

@Composable
private fun WhatsCapturedSection() {
    DevSectionGroup(
        header = "What's Captured",
        footer = "Logs include only diagnostic context. AerioTV never logs your Dispatcharr " +
            "credentials, watch progress identifiers, or any payload that would identify you.",
    ) {
        CategoryRow(
            icon = Icons.Outlined.NetworkCheck,
            title = "Network",
            detail = "All API requests: URL, method, status code, duration, payload size",
        )
        DevRowDivider()
        CategoryRow(
            icon = Icons.Filled.PlayArrow,
            title = "Playback",
            detail = "Stream URLs loaded, player state transitions, DVR mode, failover attempts",
        )
        DevRowDivider()
        CategoryRow(
            icon = Icons.Filled.CalendarMonth,
            title = "EPG",
            detail = "Current program fetches, upcoming program loads, decode errors",
        )
        DevRowDivider()
        CategoryRow(
            icon = Icons.Outlined.LiveTv,
            title = "Channels",
            detail = "Channel list loads, source type, item counts, timing",
        )
        DevRowDivider()
        CategoryRow(
            icon = Icons.Outlined.OpenInNew,
            title = "Lifecycle",
            detail = "App foreground/background, launch, scene transitions",
        )
        DevRowDivider()
        CategoryRow(
            icon = Icons.Outlined.WarningAmber,
            title = "Errors",
            detail = "Caught exceptions with full context, source file and line number",
        )
        DevRowDivider()
        CategoryRow(
            icon = Icons.Outlined.Speed,
            title = "Performance",
            detail = "Timed operations: parse time, load time, memory at session start",
        )
    }
}

@Composable
private fun BuildInfoSection() {
    DevSectionGroup(header = "Build") {
        InfoRow(icon = Icons.Outlined.Build, label = "Application ID", value = BuildConfig.APPLICATION_ID)
        DevRowDivider()
        InfoRow(icon = null, label = "Version", value = BuildConfig.VERSION_NAME)
        DevRowDivider()
        InfoRow(icon = null, label = "Version Code", value = BuildConfig.VERSION_CODE.toString())
        DevRowDivider()
        InfoRow(icon = null, label = "Build Type", value = BuildConfig.BUILD_TYPE)
        DevRowDivider()
        InfoRow(icon = null, label = "Manufacturer", value = android.os.Build.MANUFACTURER)
        DevRowDivider()
        InfoRow(icon = null, label = "Model", value = android.os.Build.MODEL)
        DevRowDivider()
        InfoRow(
            icon = null,
            label = "Android",
            value = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
        )
    }
}

// MARK: - Shared section shell

@Composable
private fun DevSectionGroup(
    header: String,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column {
        com.aeriotv.android.ui.settings.SettingsSectionHeader(header)
        com.aeriotv.android.ui.settings.SettingsCard { content() }
        if (footer != null) com.aeriotv.android.ui.settings.SettingsSectionFooter(footer)
    }
}

@Composable
private fun DevRowDivider() {
    if (com.aeriotv.android.ui.settings.rememberIsTvDevice()) return
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f),
        modifier = Modifier.padding(start = 14.dp),
    )
}

@Composable
private fun InfoRow(icon: ImageVector?, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsTvFocusableRow()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
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
private fun CategoryRow(icon: ImageVector, title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsTvFocusableRow()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Tiled in the secondary accent, as Apple's What's Captured rows are.
        DevIconTile(icon = icon, tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Icon tile rows

/**
 * Apple's DevIconTile: every row on this page leads with a rounded tile at a
 * low tint opacity, and off-state rows sit on the neutral fill instead, so the
 * page reads like the rest of Settings rather than a list of bare glyphs.
 * Sizes are Apple's (36/16/8 on phone, 48/22/10 on tvOS halved for the TV
 * canvas, the same conversion TvSettingsMetrics uses).
 */
@Composable
private fun DevIconTile(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    isActive: Boolean = true,
) {
    val isTv = rememberIsTvDevice()
    val box = if (isTv) 24.dp else 36.dp
    val glyph = if (isTv) 11.dp else 16.dp
    val corner = if (isTv) 5.dp else 8.dp
    Box(
        modifier = Modifier
            .size(box)
            .clip(RoundedCornerShape(corner))
            .background(
                if (isActive) tint.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isActive) tint else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(glyph),
        )
    }
}

/** Toggle row with the icon tile; the tile and subtitle go accent while on, as Apple's do. */
@Composable
private fun DevToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRowContainer(onClick = { onCheckedChange(!checked) }) {
        DevIconTile(icon = icon, isActive = checked)
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = settingsRowTitleStyle(),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = settingsFootnoteStyle(),
                color = if (checked) MaterialTheme.colorScheme.textAccent
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        SettingsToggleAffordance(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Action row with the icon tile; destructive rows tile in the error color. */
@Composable
private fun DevActionRow(
    icon: ImageVector,
    label: String,
    subtitle: String?,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    SettingsRowContainer(onClick = onClick) {
        DevIconTile(icon = icon, tint = tint)
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = settingsRowTitleStyle(),
                color = if (destructive) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = settingsFootnoteStyle().subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Read-only size row; its tile is the neutral off-state one, as on Apple. */
@Composable
private fun DevLogSizeRow(sizeText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .settingsRowCard(focused = false)
            .settingsTvFocusableRow()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DevIconTile(icon = Icons.Outlined.Description, isActive = false)
        Spacer(Modifier.size(14.dp))
        Text(
            text = "Log File Size",
            style = settingsRowTitleStyle(),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = sizeText,
            style = settingsFootnoteStyle().subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun shareLogFile(
    context: android.content.Context,
    debugLogger: DebugLogger,
) {
    val file = debugLogger.logFile()
    if (!file.exists() || file.length() == 0L) {
        android.widget.Toast.makeText(context, "No log file to share yet.", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    }.getOrElse {
        android.widget.Toast.makeText(
            context,
            "Couldn't prepare log for share: ${it.message ?: it::class.simpleName}",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "AerioTV debug logs")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(sendIntent, "Share Log File")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
        .onFailure {
            android.widget.Toast.makeText(
                context,
                "No app available to receive the log file.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "Empty"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.2f MB", mb)
}
