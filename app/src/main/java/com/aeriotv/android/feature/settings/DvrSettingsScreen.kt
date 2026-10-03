package com.aeriotv.android.feature.settings

import androidx.compose.material.icons.filled.Bolt
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.aeriotv.android.ui.settings.SettingsActionRow
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.feature.dvr.DvrViewModel
import com.aeriotv.android.ui.settings.SettingsSlider
import com.aeriotv.android.ui.settings.SettingsPickerOption
import com.aeriotv.android.ui.settings.SettingsPickerRow
import com.aeriotv.android.ui.settings.settingsFormWidth
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.settings.SettingsToggleRow
import com.aeriotv.android.ui.settings.dpadFocusRing
import com.aeriotv.android.ui.settings.dpadFocusWash
import com.aeriotv.android.ui.settings.SettingsIntStepperRow
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.settingsRowCard
import com.aeriotv.android.ui.tv.dpadFocusEscape
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset

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
    val isTv = rememberIsTvDevice()
    val scope = rememberCoroutineScope()
    var customBuffer by remember { mutableStateOf<CustomBuffer?>(null) }
    var showClearConfirmation by remember { mutableStateOf(false) }

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
    val canRecordToServer by settingsVm.activeCanRecordToServer
        .collectAsStateWithLifecycle(initialValue = false)
    val usedBytes = dvrState.recordings
        .filter { it.source == DvrViewModel.Source.Local }
        .sumOf { it.fileSizeBytes }
    val usedFraction = if (capMB > 0)
        (usedBytes.toDouble() / (capMB.toDouble() * 1024.0 * 1024.0)).toFloat().coerceIn(0f, 1f)
    else 0f

    customBuffer?.let { which ->
        val current = if (which == CustomBuffer.Pre) preRoll else postRoll
        CustomBufferDialog(
            title = if (which == CustomBuffer.Pre) "Custom Pre-Roll" else "Custom Post-Roll",
            // Apple seeds the stepper with the current value, or 5 when None.
            initial = if (current > 0) current else 5,
            onConfirm = { mins ->
                if (which == CustomBuffer.Pre) settingsVm.setDvrDefaultPreRollMins(mins)
                else settingsVm.setDvrDefaultPostRollMins(mins)
                customBuffer = null
            },
            onDismiss = { customBuffer = null },
        )
    }
    if (showClearConfirmation) {
        // Apple DVRSettingsView parity: finished recordings by default; the
        // in-flight on-device capture only when the user picks the second
        // button. Local only: server rows are Dispatcharr's to manage.
        val inProgressCount = if (dvrState.isLocalRecordingActive) 1 else 0
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Delete All Recordings?") },
            text = {
                Text(
                    "Finished recordings will be deleted." +
                        if (inProgressCount > 0) {
                            " $inProgressCount in-progress recordings can also be stopped and deleted."
                        } else "",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    SettingsDialogTextButton(
                        label = "Delete Finished",
                        destructive = true,
                        onClick = {
                            showClearConfirmation = false
                            scope.launch { dvrVm.deleteAllLocalRecordings(includeInProgress = false) }
                        },
                    )
                    if (inProgressCount > 0) {
                        SettingsDialogTextButton(
                            label = "Delete All Including In-Progress",
                            destructive = true,
                            onClick = {
                                showClearConfirmation = false
                                scope.launch { dvrVm.deleteAllLocalRecordings(includeInProgress = true) }
                            },
                        )
                    }
                    SettingsDialogTextButton(label = "Cancel", onClick = { showClearConfirmation = false })
                }
            },
        )
    }

    com.aeriotv.android.ui.settings.SettingsSubPageHost {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "DVR", onBack = onBack)

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
        LazyColumn(
            modifier = Modifier.settingsFormWidth().fillMaxSize(),
            // Bottom padding clears the MainScaffold NavigationBar (~80dp)
            // so the final card (Output Folder + its footer) isn't clipped.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = LocalTabBarBottomInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Section order is Apple's: Default Recording Buffers, Recording
            // Destination, Local Storage, Storage Location, Behavior, Danger Zone.
            item {
                SettingsSection(
                    header = "Default Recording Buffers",
                    footer = "Applied to new recordings by default. Sports events often run past their scheduled time.",
                ) {
                    // TV: ONE stepper row per buffer driven by D-pad Left/Right,
                    // as Apple's tvOS page does; the TV has no Custom row
                    // because typing a number on a remote is worse than a stop.
                    // Touch: Apple's iOS picker page plus a "Custom…" row under
                    // each picker for off-stop values.
                    if (isTv) {
                        SettingsIntStepperRow(
                            title = "Start Early (Pre-Roll)",
                            options = ROLL_OPTIONS,
                            value = preRoll,
                            onValueChange = settingsVm::setDvrDefaultPreRollMins,
                            format = ::formatRoll,
                        )
                        SettingsIntStepperRow(
                            title = "End Late (Post-Roll)",
                            options = ROLL_OPTIONS,
                            value = postRoll,
                            onValueChange = settingsVm::setDvrDefaultPostRollMins,
                            format = ::formatRoll,
                        )
                    } else {
                        SettingsPickerRow(
                            title = "Start Early (Pre-Roll)",
                            options = bufferOptions(preRoll),
                            selected = preRoll,
                            onSelect = settingsVm::setDvrDefaultPreRollMins,
                        )
                        SettingsActionRow(
                            label = "Custom…",
                            leadingIcon = Icons.Filled.Tune,
                            onClick = { customBuffer = CustomBuffer.Pre },
                        )
                        SettingsPickerRow(
                            title = "End Late (Post-Roll)",
                            options = bufferOptions(postRoll),
                            selected = postRoll,
                            onSelect = settingsVm::setDvrDefaultPostRollMins,
                        )
                        SettingsActionRow(
                            label = "Custom…",
                            leadingIcon = Icons.Filled.Tune,
                            onClick = { customBuffer = CustomBuffer.Post },
                        )
                    }
                }
            }

            // Only for a playlist with server-side DVR (Dispatcharr with DVR
            // manage access); M3U and Xtream record on this device only.
            if (canRecordToServer) item {
                // Task #50 (iOS parity): where new recordings go by default.
                // The record sheet still shows its Destination toggle for
                // server-capable accounts; this only pre-selects it.
                val defaultDestination by settingsVm.dvrDefaultDestination
                    .collectAsStateWithLifecycle(initialValue = "server")
                SettingsSection(
                    header = "Recording Destination",
                    // Apple TV prints no footer under Default Destination.
                    footer = if (isTv) null else "Server-side recordings are recommended: they continue even when AerioTV is closed.",
                ) {
                    SettingsPickerRow(
                        title = "Default Destination",
                        options = listOf(
                            SettingsPickerOption("server", "Dispatcharr Server", "Keeps recording even when AerioTV is closed"),
                            SettingsPickerOption("local", "This Device", "Requires AerioTV to remain open"),
                        ),
                        selected = if (defaultDestination == "local") "local" else "server",
                        onSelect = settingsVm::setDvrDefaultDestination,
                        tvChoiceSheet = true,
                    )
                }
            }

            item {
                // No footer: Apple's iOS Local Storage section has none.
                // Apple TV adds a storage disclosure under the card.
                Card(
                    header = "Local Storage",
                    footer = if (isTv) "Recordings are stored on this Android TV. If the system runs critically low on space, Android may remove stored app data, including recordings. For must-keep recordings, use a Dispatcharr server destination." else null,
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
                                text = formatGb(capMB),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.textAccent,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        // 1 GB - 200 GB, step 1 GB: Apple's range. The shared
                        // Settings slider owns the D-pad escape on TV.
                        SettingsSlider(
                            value = capMB.toFloat(),
                            onValueChange = { settingsVm.setDvrMaxLocalStorageMB(it.toInt()) },
                            valueRange = 1024f..204800f,
                            steps = 198,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Used",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${formatBytes(context, usedBytes)} of ${formatGb(capMB)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // Apple's usage colors: green, yellow from 80%, red from 95%.
                        val usageColor = when {
                            usedFraction >= 0.95f -> androidx.compose.ui.graphics.Color(0xFFFF3B30)
                            usedFraction >= 0.80f -> androidx.compose.ui.graphics.Color(0xFFFFCC00)
                            else -> androidx.compose.ui.graphics.Color(0xFF34C759)
                        }
                        LinearProgressIndicator(
                            progress = { usedFraction },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = usageColor,
                            trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                        if (usedFraction >= 0.80f) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color(0xFFFFCC00),
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    text = "Storage is running low. Future recordings may not complete if the limit is reached.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    header = "Storage Location",
                    footer = "Local recordings save to your Downloads folder (in an AerioTV subfolder) by default, so you can find them in any file manager. Choose Folder picks a custom location via the Storage Access Framework, retained across reboots.",
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "Currently saving to:",
                            style = MaterialTheme.typography.bodySmall.subtext(),
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
                            TextButton(
                                onClick = { folderPicker.launch(null) },
                                modifier = Modifier.dpadFocusRing(RoundedCornerShape(50)),
                            ) {
                                Text(
                                    text = "Choose Folder",
                                    color = MaterialTheme.colorScheme.textAccent,
                                )
                            }
                            if (customFolderUri.isNotBlank()) {
                                Spacer(Modifier.size(8.dp))
                                TextButton(
                                    onClick = {
                                        val toRelease = customFolderUri
                                        runCatching {
                                            context.contentResolver.releasePersistableUriPermission(
                                                Uri.parse(toRelease),
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                            )
                                        }
                                        settingsVm.setDvrCustomFolderUri("")
                                    },
                                    modifier = Modifier.dpadFocusRing(
                                        RoundedCornerShape(50),
                                        washTint = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
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
                // Apple's footer says the SCREEN stays on; Android holds a CPU
                // wake lock instead (LocalRecordingService), so the footer
                // keeps describing what Android actually does.
                SettingsSection(
                    header = "Behavior",
                    footer = if (isTv) null else "Holds a CPU wake lock while a local recording is downloading so Doze can't stall it. Server-side recordings are unaffected (they run on Dispatcharr). Leave on unless you're debugging battery drain.",
                ) {
                    SettingsToggleRow(
                        title = if (isTv) "Keep Device Awake" else "Keep Device Awake During Recording",
                        subtitle = if (isTv) "Prevents sleep during local recording" else null,
                        // Apple TV: bolt.fill.
                        leadingIcon = if (isTv) androidx.compose.material.icons.Icons.Filled.Bolt else null,
                        checked = keepAwake,
                        onCheckedChange = settingsVm::setDvrKeepAwakeDuringRecording,
                    )
                }
            }

            item {
                SettingsSection(
                    header = "Danger Zone",
                    footer = if (isTv) null else "Deletes every recording saved on this device. Server recordings on Dispatcharr are not affected.",
                ) {
                    SettingsActionRow(
                        label = "Delete All Local Recordings",
                        leadingIcon = Icons.Filled.Delete,
                        destructive = true,
                        onClick = { showClearConfirmation = true },
                    )
                }
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

/** Apple's compact stepper spelling: the value sits between two keys. */
private fun formatRoll(mins: Int): String = if (mins == 0) "None" else "$mins min"

/**
 * Apple's picker-page choices: long form ("5 minutes"), with a custom stored
 * value appended so the collapsed row never reads blank for, say, 20 minutes.
 */
private fun bufferOptions(current: Int): List<SettingsPickerOption<Int>> {
    val options = ROLL_OPTIONS.map { SettingsPickerOption(it, if (it == 0) "None" else "$it minutes") }
    return if (current in ROLL_OPTIONS) options else options + SettingsPickerOption(current, "$current minutes")
}

private enum class CustomBuffer { Pre, Post }

/** Apple's Custom Pre-Roll / Post-Roll sheet: one 1...120 minute stepper. */
@Composable
private fun CustomBufferDialog(
    title: String,
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial.coerceIn(1, 120)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$value minutes", modifier = Modifier.weight(1f))
                IconButton(onClick = { if (value > 1) value-- }, enabled = value > 1) {
                    Icon(Icons.Filled.Remove, contentDescription = "Less")
                }
                IconButton(onClick = { if (value < 120) value++ }, enabled = value < 120) {
                    Icon(Icons.Filled.Add, contentDescription = "More")
                }
            }
        },
        confirmButton = { SettingsDialogTextButton(label = "Done", onClick = { onConfirm(value) }) },
        dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = onDismiss) },
    )
}

/** Apple formatGB: whole gigabytes. */
private fun formatGb(mb: Int): String = String.format(java.util.Locale.US, "%.0f GB", mb / 1024.0)

/** Apple formatBytes: "0 KB" for an empty library rather than "0 B". */
private fun formatBytes(context: android.content.Context, bytes: Long): String =
    if (bytes <= 0) "0 KB" else android.text.format.Formatter.formatShortFileSize(context, bytes)

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
