package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.adaptive.adaptiveFormWidth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aeriotv.android.ui.tv.dpadFocusEscape
import com.aeriotv.android.ui.tv.TvKeyboardOnOkHost
import com.aeriotv.android.ui.tv.tvFormFieldInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Network sub-screen. Mirrors iOS NetworkSettingsView (SettingsView.swift:3118+):
 * Request Timeout slider (5..60 step 5), Max Retries stepper (0..10), and a
 * Buffer Size radio list with iOS-canon labels + millisecond detail rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val timeoutSecs by viewModel.networkTimeoutSecs.collectAsStateWithLifecycle(initialValue = 15.0)
    val maxRetries by viewModel.maxRetries.collectAsStateWithLifecycle(initialValue = 3)
    val bufferSize by viewModel.streamBufferSize.collectAsStateWithLifecycle(initialValue = "default")
    val epgWindowHours by viewModel.epgWindowHours.collectAsStateWithLifecycle(initialValue = 24)
    val backgroundRefreshEnabled by viewModel.backgroundRefreshEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val backgroundRefreshIntervalMins by viewModel.backgroundRefreshIntervalMins
        .collectAsStateWithLifecycle(initialValue = 360)

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "Network", onBack = onBack)

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
        // Outer Column is `verticalScroll` so the page actually scrolls past
        // the first viewport — earlier revisions used `fillMaxSize()` here,
        // which let the layout exceed the viewport but blocked drag-scroll.
        Column(
            modifier = Modifier
                .adaptiveFormWidth()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ConnectionSection(
                timeoutSecs = timeoutSecs,
                maxRetries = maxRetries,
                onTimeoutChange = viewModel::setNetworkTimeoutSecs,
                onMaxRetriesChange = viewModel::setMaxRetries,
            )
            BufferSizeSection(
                current = bufferSize,
                onSelect = viewModel::setStreamBufferSize,
            )
            EpgWindowSection(
                currentHours = epgWindowHours,
                onSelect = viewModel::setEpgWindowHours,
            )
            BackgroundRefreshSection(
                enabled = backgroundRefreshEnabled,
                intervalMins = backgroundRefreshIntervalMins,
                onToggle = viewModel::setBackgroundRefreshEnabled,
                onSelectInterval = viewModel::setBackgroundRefreshIntervalMins,
            )
            // 104dp tail spacer clears the MainScaffold NavigationBar
            // (Column uses verticalScroll, not LazyColumn, so we can't
            // express this as contentPadding).
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier.height(104.dp),
            )
        }
        }
    }
}

@Composable
private fun ConnectionSection(
    timeoutSecs: Double,
    maxRetries: Int,
    onTimeoutChange: (Double) -> Unit,
    onMaxRetriesChange: (Int) -> Unit,
) {
    // tvOS Network (s_10) presents Request Timeout as a selection list
    // (5/10/15/30/60 seconds), not a slider -- cleaner to drive with a remote.
    SettingsSection(
        header = "Request Timeout",
        footer = "Adjust timeouts if you have a slow or unstable connection.",
    ) {
        TIMEOUT_OPTIONS.forEach { secs ->
            SettingsSelectionRow(
                label = if (secs == 1) "1 second" else "$secs seconds",
                selected = timeoutSecs.toInt() == secs,
                onClick = { onTimeoutChange(secs.toDouble()) },
            )
        }
    }

    // Max Retries stays a stepper (no tvOS equivalent), now on a resting card.
    SettingsSection(
        header = "Max Retries",
        footer = "Per-request retry budget (0-10).",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .settingsRowCard(focused = false)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Retries",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { if (maxRetries > 0) onMaxRetriesChange(maxRetries - 1) },
                enabled = maxRetries > 0,
                modifier = Modifier.dpadFocusRing(CircleShape),
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease")
            }
            Text(
                text = maxRetries.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            IconButton(
                onClick = { if (maxRetries < 10) onMaxRetriesChange(maxRetries + 1) },
                enabled = maxRetries < 10,
                modifier = Modifier.dpadFocusRing(CircleShape),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase")
            }
        }
    }
}

@Composable
private fun BufferSizeSection(
    current: String,
    onSelect: (String) -> Unit,
) {
    SettingsSection(
        header = "Buffer Size",
        footer = "Controls how much stream data is pre-loaded. Larger buffers reduce stuttering on poor connections but add startup delay.",
    ) {
        BUFFER_OPTIONS.forEach { opt ->
            SettingsSelectionRow(
                label = opt.label,
                subtitle = opt.detail,
                selected = opt.id == current,
                onClick = { onSelect(opt.id) },
            )
        }
    }
}

@Composable
private fun SettingsCard(
    header: String,
    footer: String?,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = header.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
        ) {
            content()
        }
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * EPG Window picker. iOS `epgWindowHours` parity (Network settings radio
 * list 6/12/24/36/48/72h + "All available"). The sentinel 0 = All. Drives
 * GuideScreen's horizontal time-strip span.
 */
@Composable
private fun EpgWindowSection(
    currentHours: Int,
    onSelect: (Int) -> Unit,
) {
    SettingsSection(
        header = "EPG Window",
        footer = "How far ahead the TV Guide timeline extends. The guide always shows 1 hour of history. Wider windows need more horizontal scrolling; \"All available\" spans your full loaded guide data.",
    ) {
        EPG_WINDOW_OPTIONS.forEach { opt ->
            SettingsSelectionRow(
                label = opt.label,
                selected = opt.hours == currentHours,
                onClick = { onSelect(opt.hours) },
            )
        }
    }
}

/** tvOS Request Timeout options (s_10): 5/10/15/30/60 seconds. */
private val TIMEOUT_OPTIONS: List<Int> = listOf(5, 10, 15, 30, 60)

/**
 * Audit task #48: master toggle for the periodic background EPG + channel
 * refresh worker. On by default — most users benefit from warm caches.
 * Off for metered/restricted connections or users who only want the app
 * to touch the network when they explicitly open it.
 */
@Composable
private fun BackgroundRefreshSection(
    enabled: Boolean,
    intervalMins: Int,
    onToggle: (Boolean) -> Unit,
    onSelectInterval: (Int) -> Unit,
) {
    // "Pull to refresh" is a touch gesture; on TV point at the menu action instead.
    val isTv = rememberIsTvDevice()
    SettingsSection(
        header = "Background Refresh",
        footer = "Refresh channels + the EPG in the background on Wi-Fi while the battery isn't low, so the guide is current the moment you open the app. " +
            if (isTv) "Off here means data refreshes only when you launch AerioTV or refresh from the playlist menu."
            else "Off here means data refreshes only when you launch AerioTV or pull to refresh.",
    ) {
        SettingsToggleRow(
            title = "Refresh in the background",
            checked = enabled,
            onCheckedChange = onToggle,
        )
        if (enabled) {
            // iOS bgRefreshIntervalMins (audit P1 #7): how often the periodic
            // refresh fires. Hidden when the master toggle is off so the UI
            // doesn't suggest setting frequency on a disabled worker.
            BG_REFRESH_INTERVAL_OPTIONS.forEach { opt ->
                SettingsSelectionRow(
                    label = opt.label,
                    selected = opt.mins == intervalMins,
                    onClick = { onSelectInterval(opt.mins) },
                )
            }
        }
    }
}

private data class BgRefreshIntervalOption(val mins: Int, val label: String)

/** iOS bgRefreshIntervalMins picker options. 360 (6h) is the default;
 *  match the iOS picker so synced preferences round-trip cleanly. */
private val BG_REFRESH_INTERVAL_OPTIONS: List<BgRefreshIntervalOption> = listOf(
    BgRefreshIntervalOption(60, "Every hour"),
    BgRefreshIntervalOption(180, "Every 3 hours"),
    BgRefreshIntervalOption(360, "Every 6 hours"),
    BgRefreshIntervalOption(720, "Every 12 hours"),
    BgRefreshIntervalOption(1440, "Every 24 hours"),
    BgRefreshIntervalOption(2880, "Every 48 hours"),
)

private data class EpgWindowOption(val hours: Int, val label: String)

private val EPG_WINDOW_OPTIONS: List<EpgWindowOption> = listOf(
    EpgWindowOption(6, "6 hours"),
    EpgWindowOption(12, "12 hours"),
    EpgWindowOption(24, "24 hours"),
    EpgWindowOption(36, "36 hours"),
    EpgWindowOption(48, "48 hours"),
    EpgWindowOption(72, "72 hours"),
    EpgWindowOption(0, "All available"),
)

data class BufferOption(val id: String, val label: String, val detail: String, val cachingMs: Int)

internal val BUFFER_OPTIONS: List<BufferOption> = listOf(
    BufferOption("small", "Small", "300 ms - fast, stable networks", 300),
    BufferOption("default", "Default", "1 second - recommended", 1_000),
    BufferOption("large", "Large", "3 seconds - unstable connections", 3_000),
    BufferOption("xlarge", "Extra Large", "8 seconds - very poor networks", 8_000),
)

internal fun bufferMillisFor(id: String): Int =
    BUFFER_OPTIONS.firstOrNull { it.id == id }?.cachingMs ?: 1_000
