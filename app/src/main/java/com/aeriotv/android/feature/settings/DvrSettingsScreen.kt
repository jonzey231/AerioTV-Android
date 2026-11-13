package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.settings.settingsPaneTopEdge
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import com.aeriotv.android.ui.settings.settingsRowTitleStyle
import com.aeriotv.android.ui.settings.settingsRowTitleWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Bolt
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
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
        com.aeriotv.android.ui.settings.AlertPrompt(
            onDismissRequest = { showClearConfirmation = false },
            title = "Delete All Recordings?",
            message = "Finished recordings will be deleted." +
                if (inProgressCount > 0) {
                    " $inProgressCount in-progress recordings can also be stopped and deleted."
                } else "",
            actions = listOfNotNull(
                com.aeriotv.android.ui.settings.AlertAction("Delete Finished", com.aeriotv.android.ui.settings.AlertRole.Destructive) {
                    showClearConfirmation = false
                    scope.launch { dvrVm.deleteAllLocalRecordings(includeInProgress = false) }
                },
                if (inProgressCount > 0) {
                    com.aeriotv.android.ui.settings.AlertAction("Delete All Including In-Progress", com.aeriotv.android.ui.settings.AlertRole.Destructive) {
                        showClearConfirmation = false
                        scope.launch { dvrVm.deleteAllLocalRecordings(includeInProgress = true) }
                    }
                } else null,
                com.aeriotv.android.ui.settings.AlertAction("Cancel", com.aeriotv.android.ui.settings.AlertRole.Cancel) { showClearConfirmation = false },
            ),
        )
    }

    com.aeriotv.android.ui.settings.SettingsSubPageHost {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "DVR", onBack = onBack)

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
        val paneList = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(
            state = paneList,
            modifier = Modifier.settingsFormWidth().fillMaxSize().settingsPaneTopEdge(paneList),
            // Bottom padding clears the MainScaffold NavigationBar (~80dp)
            // so the final card (Output Folder + its footer) isn't clipped.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = LocalTabBarBottomInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(com.aeriotv.android.ui.settings.settingsSectionSpacing(com.aeriotv.android.ui.settings.SettingsCardMetrics.sectionSpacing)),
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

            if (isTv) item {
                // Apple TV's storage card (DVRSettingsView tvStorageCard):
                // Maximum with its Left/Right value control and the Used gauge
                // in the same card, then the storage disclosure.
                SettingsSection(
                    header = "Local Storage",
                    footer = "Recordings are stored on this Android TV. If the system runs critically low on space, Android may remove stored app data, including recordings. For must-keep recordings, use a Dispatcharr server destination.",
                ) {
                    StorageCapCard(
                        capMB = capMB,
                        onCommit = settingsVm::setDvrMaxLocalStorageMB,
                        usedBytes = usedBytes,
                        formatUsed = { formatBytes(context, it) },
                        isTv = true,
                    )
                }
            } else item {
                // No footer: Apple's iOS Local Storage section has none.
                Card(
                    header = "Local Storage",
                    footer = null,
                ) {
                    StorageCapCard(
                        capMB = capMB,
                        onCommit = settingsVm::setDvrMaxLocalStorageMB,
                        usedBytes = usedBytes,
                        formatUsed = { formatBytes(context, it) },
                        isTv = false,
                    )
                }
            }

            item {
                Card(
                    header = "Storage Location",
                    footer = "Local recordings save to your Downloads folder (in an AerioTV subfolder) by default, so you can find them in any file manager. Choose Folder picks a custom location via the Storage Access Framework, retained across reboots.",
                ) {
                    Column(modifier = Modifier.padding(horizontal = com.aeriotv.android.ui.settings.settingsRowInsetH(), vertical = 12.dp)) {
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

/**
 * Settings > DVR > Local Storage, every platform: Maximum and its value
 * control, then Used and its bar, in one card (Apple 6660eef; Logan
 * 2026-11-13: phones and tablets step too, the slider was hard to land on an
 * exact amount). A tap, click, or D-pad press moves 0.25 GB; held, it
 * repeats after 0.4 s every 0.1 s, 0.25 GB for 8 repeats, then 1 GB for 12,
 * then 5 GB (DvrStorageCapRules), 1 to 200 GB.
 *
 * A held control changes the value ten times a second, so the value lives
 * here while it moves: only this card recomposes, and the setting is written
 * once, [STORAGE_COMMIT_DEBOUNCE_MS] after the last change (or when the card
 * leaves), not on every step. The setting is device-local: no sync push.
 */
@Composable
private fun StorageCapCard(
    capMB: Int,
    onCommit: (Int) -> Unit,
    usedBytes: Long,
    formatUsed: (Long) -> String,
    isTv: Boolean,
) {
    // The value while it moves; null when it matches the stored setting.
    var draftMB by remember { mutableStateOf<Int?>(null) }
    val shownMB = draftMB ?: capMB
    val commit by androidx.compose.runtime.rememberUpdatedState(onCommit)
    androidx.compose.runtime.LaunchedEffect(draftMB) {
        val d = draftMB ?: return@LaunchedEffect
        kotlinx.coroutines.delay(STORAGE_COMMIT_DEBOUNCE_MS)
        commit(d)
    }
    // The stored value caught up with the draft: show the setting again.
    androidx.compose.runtime.LaunchedEffect(capMB) { if (draftMB == capMB) draftMB = null }
    val pending by androidx.compose.runtime.rememberUpdatedState(draftMB)
    val stored by androidx.compose.runtime.rememberUpdatedState(capMB)
    // Leaving the page within the debounce still saves the last value.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { pending?.let { if (it != stored) commit(it) } }
    }
    val scope = rememberCoroutineScope()
    val hold = remember { StorageCapHold(scope) }
    hold.current = { draftMB ?: capMB }
    hold.set = { draftMB = it }

    val usedFraction = (usedBytes.toDouble() / (shownMB.toDouble() * 1024.0 * 1024.0)).toFloat().coerceIn(0f, 1f)
    val usedText = "${formatUsed(usedBytes)} of ${com.aeriotv.android.core.data.DvrStorageCapRules.label(shownMB)}"
    // TalkBack: one adjustable control (swipe up or down moves 0.25 GB).
    val adjustable = Modifier.clearAndSetSemantics {
        contentDescription = "Maximum"
        stateDescription = com.aeriotv.android.core.data.DvrStorageCapRules.label(shownMB)
        progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(
            current = shownMB.toFloat(),
            range = STORAGE_MIN_MB.toFloat()..STORAGE_MAX_MB.toFloat(),
            steps = (STORAGE_MAX_MB - STORAGE_MIN_MB) / STORAGE_STEP_MB - 1,
        )
        setProgress { target ->
            val next = com.aeriotv.android.core.data.DvrStorageCapRules.snappedMB(target / 1024.0)
            if (next != shownMB) draftMB = next
            true
        }
    }
    if (isTv) {
        TvStorageCapCard(shownMB, usedText, usedFraction, hold, adjustable)
    } else {
        PhoneStorageCapCard(shownMB, usedText, usedFraction, hold, adjustable)
    }
}

/**
 * The hold-to-repeat engine shared by the phone keys and the TV control:
 * [begin] steps once at once, then after 0.4 s every 0.1 s with the
 * accelerating step, until [end].
 */
private class StorageCapHold(private val scope: kotlinx.coroutines.CoroutineScope) {
    var current: () -> Int = { STORAGE_MIN_MB }
    var set: (Int) -> Unit = {}
    private var job: kotlinx.coroutines.Job? = null

    fun begin(up: Boolean) {
        job?.cancel()
        if (!adjust(up, 0)) return
        job = scope.launch {
            kotlinx.coroutines.delay(com.aeriotv.android.core.data.DvrStorageCapRules.HOLD_DELAY_MS)
            var n = 1
            // Capped (about 40 s) in case a release never arrives.
            while (n < 400 && adjust(up, n)) {
                n++
                kotlinx.coroutines.delay(com.aeriotv.android.core.data.DvrStorageCapRules.REPEAT_MS)
            }
        }
    }

    fun end() {
        job?.cancel()
        job = null
    }

    /** One step; false at the end of the range. */
    private fun adjust(up: Boolean, n: Int): Boolean {
        val now = current()
        val next = com.aeriotv.android.core.data.DvrStorageCapRules.stepped(
            now, com.aeriotv.android.core.data.DvrStorageCapRules.holdStepMB(n), up,
        )
        if (next == now) return false
        set(next)
        return true
    }
}

/**
 * Phones and tablets: "Maximum" with "− 10 GB +", the Used line and bar under
 * it, in the card's row inset. A key is dimmed and inert at its end of the
 * range. Typography as the other Settings value rows (title left, the value
 * in the accent).
 */
@Composable
private fun PhoneStorageCapCard(
    shownMB: Int,
    usedText: String,
    usedFraction: Float,
    hold: StorageCapHold,
    adjustable: Modifier,
) {
    val atMin = shownMB <= STORAGE_MIN_MB
    val atMax = shownMB >= STORAGE_MAX_MB
    Column(modifier = Modifier.padding(horizontal = com.aeriotv.android.ui.settings.settingsRowInsetH(), vertical = 12.dp)) {
        Row(modifier = adjustable, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Maximum",
                style = settingsRowTitleStyle(),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = settingsRowTitleWeight(),
                modifier = Modifier.weight(1f),
            )
            StorageStepKey(Icons.Filled.Remove, enabled = !atMin, onPress = { hold.begin(up = false) }, onRelease = { hold.end() })
            Text(
                text = com.aeriotv.android.core.data.DvrStorageCapRules.label(shownMB),
                style = com.aeriotv.android.ui.settings.settingsRowValueStyle(),
                color = MaterialTheme.colorScheme.textAccent,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.widthIn(min = 76.dp),
            )
            StorageStepKey(Icons.Filled.Add, enabled = !atMax, onPress = { hold.begin(up = true) }, onRelease = { hold.end() })
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Used",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = usedText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { usedFraction },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = storageUsageColor(usedFraction),
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

/** A phone "−" or "+" key: a tap steps once, a hold repeats until release. */
@Composable
private fun StorageStepKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val press by androidx.compose.runtime.rememberUpdatedState(onPress)
    val release by androidx.compose.runtime.rememberUpdatedState(onRelease)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        press()
                        try { tryAwaitRelease() } finally { release() }
                    })
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.textAccent.copy(alpha = if (enabled) 1f else 0.3f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Android TV (Apple's TVStorageCapAdjuster, halved): "Maximum" with the value
 * between a left and a right chevron in a capsule, the one focus stop in the
 * card. Left and Right change it and never move focus out of it (Apple
 * 6660eef: Left had sent focus to the Settings rail); Up and Down still
 * navigate. Held, the step repeats on the card's own clock, not the remote's
 * key repeat; a swipe or a press is one step.
 */
@Composable
private fun TvStorageCapCard(
    shownMB: Int,
    usedText: String,
    usedFraction: Float,
    hold: StorageCapHold,
    adjustable: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val atMin = shownMB <= STORAGE_MIN_MB
    val atMax = shownMB >= STORAGE_MAX_MB
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .settingsRowCard(false)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Maximum",
                style = settingsRowTitleStyle(),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = settingsRowTitleWeight(),
                modifier = Modifier.weight(1f),
            )
            val ink = if (focused) MaterialTheme.colorScheme.textAccent else MaterialTheme.colorScheme.onBackground
            Row(
                modifier = Modifier
                    .then(adjustable)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (!it.isFocused) hold.end()
                    }
                    .onKeyEvent { e ->
                        val left = e.key == androidx.compose.ui.input.key.Key.DirectionLeft
                        val right = e.key == androidx.compose.ui.input.key.Key.DirectionRight
                        if (!left && !right) return@onKeyEvent false
                        when (e.type) {
                            // The remote's own key repeat is ignored: the card keeps the pace.
                            androidx.compose.ui.input.key.KeyEventType.KeyDown ->
                                if (e.nativeKeyEvent.repeatCount == 0) hold.begin(up = right)
                            androidx.compose.ui.input.key.KeyEventType.KeyUp -> hold.end()
                        }
                        // A value row keeps focus on Left and Right, at the ends too.
                        true
                    }
                    .focusable()
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else com.aeriotv.android.ui.settings.settingsCardFill(),
                    )
                    .then(
                        if (focused) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                        else Modifier,
                    )
                    // Material's chevron sits in a wider box than the SF
                    // symbol's frame, so the padding and gaps are trimmed to
                    // land the glyphs where Apple TV draws them (1080p
                    // simulator, 6660eef): 103 px from the value's center,
                    // 28 px inside the capsule's end.
                    .padding(horizontal = 5.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = ink.copy(alpha = if (atMin) 0.25f else 0.8f),
                    modifier = Modifier.size(19.dp),
                )
                Text(
                    text = com.aeriotv.android.core.data.DvrStorageCapRules.label(shownMB),
                    fontSize = 14.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ink,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.widthIn(min = 75.dp),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ink.copy(alpha = if (atMax) 0.25f else 0.8f),
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Used",
                    style = com.aeriotv.android.ui.settings.settingsRowSubtitleStyle().subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = usedText,
                    style = com.aeriotv.android.ui.settings.settingsRowSubtitleStyle().subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(usedFraction).background(storageUsageColor(usedFraction)))
            }
        }
        if (usedFraction >= 0.80f) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(0xFFFFCC00),
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "Storage is running low. Future recordings may not complete if the limit is reached.",
                    style = com.aeriotv.android.ui.settings.settingsRowSubtitleStyle(),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/** Apple's usage colors: green, yellow from 80%, red from 95%. */
private fun storageUsageColor(fraction: Float): androidx.compose.ui.graphics.Color = when {
    fraction >= 0.95f -> androidx.compose.ui.graphics.Color(0xFFFF3B30)
    fraction >= 0.80f -> androidx.compose.ui.graphics.Color(0xFFFFCC00)
    else -> androidx.compose.ui.graphics.Color(0xFF34C759)
}

private const val STORAGE_STEP_MB = com.aeriotv.android.core.data.DvrStorageCapRules.STEP_MB
private const val STORAGE_MIN_MB = com.aeriotv.android.core.data.DvrStorageCapRules.MIN_MB
private const val STORAGE_MAX_MB = com.aeriotv.android.core.data.DvrStorageCapRules.MAX_MB
/** The setting is written this long after the last step, once per adjustment. */
private const val STORAGE_COMMIT_DEBOUNCE_MS = 400L

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
