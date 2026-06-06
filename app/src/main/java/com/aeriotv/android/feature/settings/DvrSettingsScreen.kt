package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.adaptive.adaptiveFormWidth

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aeriotv.android.ui.tv.dpadFocusEscape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.feature.dvr.DvrViewModel

/**
 * DVR Settings sub-screen. Mirrors iOS DVRSettingsView field-for-field:
 * local-recording storage cap, default pre-roll, default post-roll. Custom
 * folder picker via SAF tree URI is queued for a follow-up cut.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DvrSettingsScreen(
    onBack: () -> Unit,
    settingsVm: SettingsViewModel = hiltViewModel(),
    dvrVm: DvrViewModel = hiltViewModel(),
) {
    val capMB by settingsVm.dvrMaxLocalStorageMB.collectAsStateWithLifecycle(initialValue = 10_240)
    val preRoll by settingsVm.dvrDefaultPreRollMins.collectAsStateWithLifecycle(initialValue = 0)
    val postRoll by settingsVm.dvrDefaultPostRollMins.collectAsStateWithLifecycle(initialValue = 0)
    val customFolderUri by settingsVm.dvrCustomFolderUri.collectAsStateWithLifecycle(initialValue = "")
    val keepAwake by settingsVm.dvrKeepAwakeDuringRecording.collectAsStateWithLifecycle(initialValue = true)
    val context = LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Take persistable RW permission so LocalRecordingService can still
        // write here after a reboot. Without this the URI's grant expires
        // with the activity scope and recordings fail with SecurityException.
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        settingsVm.setDvrCustomFolderUri(uri.toString())
    }
    val dvrState by dvrVm.state.collectAsStateWithLifecycle()
    val usedBytes = dvrState.recordings
        .filter { it.source == DvrViewModel.Source.Local }
        .sumOf { it.fileSizeBytes }
    val usedMB = (usedBytes / (1024L * 1024L)).toInt()
    val usedFraction = if (capMB > 0) (usedMB.toFloat() / capMB.toFloat()).coerceIn(0f, 1f) else 0f

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "DVR Settings", onBack = onBack)

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
        LazyColumn(
            modifier = Modifier.adaptiveFormWidth().fillMaxSize(),
            // Bottom padding clears the MainScaffold NavigationBar (~80dp)
            // so the final card (Output Folder + its footer) isn't clipped.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Card(
                    header = "Local Storage",
                    footer = "Cap applies to local recordings on this device only. Server recordings live on Dispatcharr and are tracked there.",
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Maximum",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatStorage(capMB),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        // 1 GB - 100 GB range, step 1 GB (1024 MB).
                        Slider(
                            value = capMB.toFloat(),
                            onValueChange = { settingsVm.setDvrMaxLocalStorageMB(it.toInt()) },
                            valueRange = 1024f..102400f,
                            steps = 99,
                            // D-pad escape (v0.1.6 report): UP/DOWN move focus
                            // off the slider on Android TV instead of trapping
                            // the user on it.
                            modifier = Modifier.dpadFocusEscape(),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${formatStorage(usedMB)} of ${formatStorage(capMB)} used",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (usedFraction > 0.8f)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${(usedFraction * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (usedFraction > 0.8f)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { usedFraction },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = if (usedFraction > 0.8f)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            drawStopIndicator = {},
                        )
                    }
                }
            }

            item {
                Card(
                    header = "Default Recording Buffers",
                    footer = "Buffers extend new recordings beyond the scheduled window. Existing recordings aren't touched. Useful for sports and live events that run over.",
                ) {
                    Column {
                        BufferRow(
                            label = "Start Early",
                            options = ROLL_OPTIONS,
                            selected = preRoll,
                            onSelect = settingsVm::setDvrDefaultPreRollMins,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        BufferRow(
                            label = "End Late",
                            options = ROLL_OPTIONS,
                            selected = postRoll,
                            onSelect = settingsVm::setDvrDefaultPostRollMins,
                        )
                    }
                }
            }

            item {
                Card(
                    header = "Output Folder",
                    footer = "Local recordings save to your Downloads folder (in an AerioTV subfolder) by default, so you can find them in any file manager. Choose Folder picks a custom location via the Storage Access Framework, retained across reboots.",
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "Currently saving to:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = formatCustomFolderLabel(customFolderUri),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row {
                            TextButton(onClick = { folderPicker.launch(null) }) {
                                Text(
                                    text = "Choose Folder",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (customFolderUri.isNotBlank()) {
                                Spacer(Modifier.size(8.dp))
                                TextButton(onClick = {
                                    val toRelease = customFolderUri
                                    runCatching {
                                        context.contentResolver.releasePersistableUriPermission(
                                            Uri.parse(toRelease),
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                        )
                                    }
                                    settingsVm.setDvrCustomFolderUri("")
                                }) {
                                    Text(
                                        text = "Reset to Default",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SettingsSection(
                    header = "Behavior",
                    footer = "Holds a CPU wake lock while a local recording is downloading so Doze can't stall it. Server-side recordings are unaffected (they run on Dispatcharr). Leave on unless you're debugging battery drain.",
                ) {
                    SettingsToggleRow(
                        title = "Keep device awake during recording",
                        subtitle = "Recommended for long local recordings.",
                        checked = keepAwake,
                        onCheckedChange = settingsVm::setDvrKeepAwakeDuringRecording,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun Card(
    header: String,
    footer: String?,
    content: @Composable () -> Unit,
) {
    // DVR's Local Storage / Buffers / Output Folder are genuinely grouped
    // content (a slider+gauge, two dropdown rows, a folder picker) rather than
    // simple pick-one rows, so they stay as one card per section -- but on the
    // shared tvOS card chrome (faint accent hairline) so they match the rest.
    SettingsSection(header = header, footer = footer) {
        Column(modifier = Modifier.fillMaxWidth().settingsRowCard(focused = false)) {
            content()
        }
    }
}

/**
 * Compact buffer-picker row matching iOS DVR Settings > DEFAULT RECORDING
 * BUFFERS. Shows the label + the current value as a chevron-tagged value;
 * tapping expands a DropdownMenu of the supported minute options. Two
 * BufferRows share one card via stacked layout.
 */
@Composable
private fun BufferRow(
    label: String,
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { menuOpen = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatRoll(selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(6.dp))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            options.forEach { mins ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = mins == selected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(formatRoll(mins))
                        }
                    },
                    onClick = {
                        onSelect(mins)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

private fun formatRoll(mins: Int): String = if (mins == 0) "None" else "$mins min"

private fun formatStorage(mb: Int): String {
    if (mb >= 1024) {
        val gb = mb / 1024.0
        return if (gb >= 10) "${gb.toInt()} GB" else String.format("%.1f GB", gb)
    }
    return "$mb MB"
}

private val ROLL_OPTIONS: List<Int> = listOf(0, 5, 10, 15, 30, 60)

/**
 * Render a SAF tree URI as a human-readable label by extracting the
 * tail of the document path, or fall back to the URI's authority. Blank
 * input → the default Downloads/AerioTV location. Skipping a full
 * DocumentFile lookup here keeps the row cheap to render; names shift to
 * canonical only after the picker callback resolves the URI.
 */
private fun formatCustomFolderLabel(uriString: String): String {
    if (uriString.isBlank()) {
        return "Device Downloads (AerioTV folder)"
    }
    return runCatching {
        val uri = Uri.parse(uriString)
        val raw = uri.lastPathSegment?.substringAfterLast(':') ?: uri.path ?: uriString
        raw.ifBlank { uriString }
    }.getOrElse { uriString }
}
