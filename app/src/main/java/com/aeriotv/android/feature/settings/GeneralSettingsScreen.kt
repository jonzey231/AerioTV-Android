package com.aeriotv.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.feature.main.AppTab
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset
import com.aeriotv.android.ui.adaptive.rememberViewport
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsPickerOption
import com.aeriotv.android.ui.settings.SettingsPickerRow
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.settings.SettingsSubPageHost
import com.aeriotv.android.ui.settings.SettingsToggleRow
import com.aeriotv.android.ui.settings.SettingsRowContainer
import com.aeriotv.android.ui.settings.SettingsSliderRow
import com.aeriotv.android.ui.settings.settingsRowTitleStyle
import com.aeriotv.android.ui.settings.settingsRowValueStyle
import com.aeriotv.android.ui.settings.dpadFocusRing
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.settingsFormWidth
import com.aeriotv.android.ui.settings.settingsRowCard
import com.aeriotv.android.ui.theme.textAccent

/**
 * Settings > General. How the app starts, how often it refreshes in the
 * background, and the network request budget.
 *
 * Settings phase 1 regroup: Startup came from App Behaviors (Default Tab,
 * Launch, Orientation), Refresh and Network from the retired Network page.
 * Keys, control types and copy are carried over unchanged.
 */
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val isTv = rememberIsTvDevice()
    // Apple branches Startup and Auto-Rotate copy on the iPad idiom. The
    // physical diagonal is the closest Android analog: it does not flip with
    // orientation the way width classes do.
    val isTablet = rememberViewport().diagonalInches >= 8f
    val defaultTab by viewModel.defaultTab.collectAsStateWithLifecycle(initialValue = "")
    val skipLoadingScreen by viewModel.skipLoadingScreen.collectAsStateWithLifecycle(initialValue = false)
    val autoResumeLastChannel by viewModel.autoResumeLastChannel.collectAsStateWithLifecycle(initialValue = false)
    val backgroundRefreshEnabled by viewModel.backgroundRefreshEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val backgroundRefreshIntervalMins by viewModel.backgroundRefreshIntervalMins
        .collectAsStateWithLifecycle(initialValue = 360)
    val backgroundRefreshType by viewModel.backgroundRefreshType
        .collectAsStateWithLifecycle(initialValue = "interval")
    val backgroundRefreshHour by viewModel.backgroundRefreshHour.collectAsStateWithLifecycle(initialValue = 8)
    val backgroundRefreshMinute by viewModel.backgroundRefreshMinute.collectAsStateWithLifecycle(initialValue = 0)
    val context = androidx.compose.ui.platform.LocalContext.current
    val timeoutSecs by viewModel.networkTimeoutSecs.collectAsStateWithLifecycle(initialValue = 15.0)
    val maxRetries by viewModel.maxRetries.collectAsStateWithLifecycle(initialValue = 3)

    SettingsSubPageHost {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "General", onBack = onBack)

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .settingsFormWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = LocalTabBarBottomInset.current,
                    ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // MARK: Startup
                //
                // Apple phase 1 parity: Default Tab, the two launch toggles and
                // Auto-rotate are ONE section with one combined footer.
                SettingsSection(
                    header = "Startup",
                    // Apple parity (Phase 3): tvOS carries only the picker's
                    // footer, and the inline TV picker cannot hold one, so it
                    // lands here. iOS moved that line onto the pushed picker
                    // page and keeps two short paragraphs in the section.
                    footer = when {
                        isTv -> "The tab shown when the app first launches."
                        isTablet ->
                            "Skipping the loading screen may cause brief UI stutter while data loads. " +
                                "Resume picks up the last channel you watched in the corner mini-player; " +
                                "press Play/Pause to expand.\n\n" +
                                "The player's fullscreen button can still rotate into landscape either way."
                        else ->
                            "Skipping the loading screen may cause brief UI stutter while data loads.\n\n" +
                                "The player's fullscreen button can still rotate into landscape either way."
                    },
                ) {
                    // Search is a TV-only nav tab and not a sensible launch tab;
                    // on phones it does not exist at all. On Demand and Favorites
                    // are no longer tabs on any form factor (media center 2026-09-10).
                    val tabs = AppTab.entries.filter {
                        it != AppTab.Search && it != AppTab.OnDemand && it != AppTab.Favorites
                    }
                    SettingsPickerRow(
                        title = "Default Landing Tab",
                        options = tabs.map { SettingsPickerOption(it.name, it.label) },
                        // Nothing stored means Live TV, which is where a first
                        // launch lands; keep that reading as an explicit pick.
                        selected = if (defaultTab.isEmpty()) AppTab.LiveTV.name else defaultTab,
                        onSelect = { viewModel.setDefaultTab(it) },
                        footer = "The tab shown when the app first launches.",
                    )
                    SettingsToggleRow(
                        title = "Skip Loading Screen",
                        subtitle = "Land on Live TV instantly; data hydrates in the background",
                        checked = skipLoadingScreen,
                        onCheckedChange = viewModel::setSkipLoadingScreen,
                    )
                    SettingsToggleRow(
                        title = "Resume Last Channel",
                        subtitle = "Auto-start the last-played channel in the corner mini-player on launch",
                        checked = autoResumeLastChannel,
                        onCheckedChange = viewModel::setAutoResumeLastChannel,
                    )
                    // Auto-Rotate (Logan 2026-08-07, iOS twin): phones/tablets
                    // only - TVs have no rotation. Default ON; when off
                    // MainActivity locks the activity to its current orientation.
                    if (!isTv) {
                        val autoRotate by viewModel.autoRotate
                            .collectAsStateWithLifecycle(initialValue = true)
                        SettingsToggleRow(
                            title = "Auto-Rotate",
                            // Apple's per-idiom wording, word for word.
                            subtitle = if (isTablet) {
                                "Follow the device orientation. When off, AerioTV stays in its current orientation"
                            } else {
                                "Follow the device orientation. When off, AerioTV stays portrait"
                            },
                            checked = autoRotate,
                            onCheckedChange = viewModel::setAutoRotate,
                        )
                    }
                }

                // MARK: Refresh
                //
                // Apple parity: toggle title, icon and subtitle, and the
                // footer that states the current schedule when on. Apple's
                // "iOS may delay or skip" clause is platform-specific; the
                // Android equivalent is WorkManager's Wi-Fi + battery
                // constraints, which is what this build actually applies.
                SettingsSection(
                    header = "Refresh",
                    footer = if (backgroundRefreshEnabled) {
                        (if (backgroundRefreshType == "time") {
                            "Refresh daily at ${timeLabel(backgroundRefreshHour, backgroundRefreshMinute)}. "
                        } else {
                            "Refresh every ${intervalLabel(backgroundRefreshIntervalMins)}. "
                        }) +
                            "Android runs background refreshes on Wi-Fi while the battery isn't low, and may delay them to preserve battery."
                    } else {
                        "Automatically refresh channel lists and guide data while the app is in the background."
                    },
                ) {
                    SettingsToggleRow(
                        title = "Background Refresh",
                        subtitle = "Update EPG & playlists automatically",
                        leadingIcon = Icons.Filled.Refresh,
                        // Apple tiles this icon on every platform.
                        tiledIcon = true,
                        checked = backgroundRefreshEnabled,
                        onCheckedChange = viewModel::setBackgroundRefreshEnabled,
                    )
                    if (backgroundRefreshEnabled) {
                        // Apple's Schedule picker: Interval repeats on a
                        // timer, Time of Day runs once a day at a set time.
                        SettingsPickerRow(
                            title = "Schedule",
                            options = listOf(
                                SettingsPickerOption("interval", "Interval", "Repeat on a timer"),
                                SettingsPickerOption("time", "Time of Day", "Once a day at a set time"),
                            ),
                            selected = backgroundRefreshType,
                            onSelect = viewModel::setBackgroundRefreshType,
                            inlineTitle = true,
                            footer = "Interval refreshes on a repeating timer. Time of Day refreshes once a day at the time you pick.",
                        )
                        if (backgroundRefreshType == "time") {
                            // Apple's "Refresh At" time picker. The platform
                            // TimePickerDialog works with touch and the D-pad.
                            SettingsRowContainer(onClick = {
                                android.app.TimePickerDialog(
                                    context,
                                    { _, h, m -> viewModel.setBackgroundRefreshTime(h, m) },
                                    backgroundRefreshHour,
                                    backgroundRefreshMinute,
                                    android.text.format.DateFormat.is24HourFormat(context),
                                ).show()
                            }) {
                                Text(
                                    text = "Refresh At",
                                    style = settingsRowTitleStyle(),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = timeLabel(backgroundRefreshHour, backgroundRefreshMinute),
                                    style = settingsRowValueStyle(),
                                    color = MaterialTheme.colorScheme.textAccent,
                                )
                            }
                        } else {
                            SettingsPickerRow(
                                title = "Interval",
                                options = BG_REFRESH_INTERVAL_OPTIONS.map {
                                    SettingsPickerOption(it.mins, it.label)
                                },
                                selected = backgroundRefreshIntervalMins,
                                onSelect = { viewModel.setBackgroundRefreshIntervalMins(it) },
                                inlineTitle = true,
                                footer = "How often AerioTV asks for fresh channel lists and guide data.",
                            )
                        }
                    }
                }

                // MARK: Network
                //
                // tvOS Network (s_10) presents Request Timeout as a selection list
                // (5/10/15/30/60 seconds), not a slider: cleaner with a remote.
                SettingsSection(
                    header = "Network",
                    footer = "Adjust timeouts if you have a slow or unstable connection.",
                ) {
                    // Apple iOS: a 5-60 s slider in 5 s steps reading "15s".
                    val timeoutIdx = TIMEOUT_STOPS.indices
                        .minByOrNull { kotlin.math.abs(TIMEOUT_STOPS[it] - timeoutSecs.toInt()) } ?: 0
                    SettingsSliderRow(
                        label = "Request Timeout",
                        valueText = "${TIMEOUT_STOPS[timeoutIdx]}s",
                        index = timeoutIdx,
                        lastIndex = TIMEOUT_STOPS.lastIndex,
                        onIndexChange = { viewModel.setNetworkTimeoutSecs(TIMEOUT_STOPS[it].toDouble()) },
                        dimValue = true,
                    )
                }

                // Max Retries stays a stepper (no tvOS equivalent), on a resting card.
                SettingsSection(
                    header = "",
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
                            text = "Max Retries",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { if (maxRetries > 0) viewModel.setMaxRetries(maxRetries - 1) },
                            enabled = maxRetries > 0,
                            modifier = Modifier.dpadFocusRing(CircleShape),
                        ) {
                            Icon(Icons.Filled.Remove, contentDescription = "Decrease")
                        }
                        Text(
                            text = maxRetries.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.textAccent,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(
                            onClick = { if (maxRetries < 10) viewModel.setMaxRetries(maxRetries + 1) },
                            enabled = maxRetries < 10,
                            modifier = Modifier.dpadFocusRing(CircleShape),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Increase")
                        }
                    }
                }
            }
        }
    }
    }
}

/** Apple's Request Timeout slider stops: 5 to 60 seconds in 5 s steps. */
private val TIMEOUT_STOPS: List<Int> = (5..60 step 5).toList()

/** Apple's timeLabel: "8:00 AM" style (the Apple app formats en_US). */
private fun timeLabel(hour: Int, minute: Int): String {
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    return "$h12:${minute.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
}

private data class BgRefreshIntervalOption(val mins: Int, val label: String)

/** iOS bgRefreshIntervalMins picker options. 360 (6h) is the default;
 *  match the iOS picker so synced preferences round-trip cleanly. */
// Labels in Apple's "12 Hours" style. The VALUES stay Android's: 360 is the
// stored default and existing users hold 180 or 2880, which Apple's list
// (15 min to 24 h) would leave with no checked row.
private val BG_REFRESH_INTERVAL_OPTIONS: List<BgRefreshIntervalOption> = listOf(
    BgRefreshIntervalOption(60, "1 Hour"),
    BgRefreshIntervalOption(180, "3 Hours"),
    BgRefreshIntervalOption(360, "6 Hours"),
    BgRefreshIntervalOption(720, "12 Hours"),
    BgRefreshIntervalOption(1440, "24 Hours"),
    BgRefreshIntervalOption(2880, "48 Hours"),
)

/** Apple's intervalLabel: lowercase units for the footer sentence. */
private fun intervalLabel(mins: Int): String {
    if (mins < 60) return "$mins minutes"
    val h = mins / 60
    return if (h == 1) "1 hour" else "$h hours"
}
