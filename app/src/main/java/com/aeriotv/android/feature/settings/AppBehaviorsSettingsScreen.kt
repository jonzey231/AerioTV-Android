package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.adaptive.adaptiveFormWidth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.feature.main.AppTab
import com.aeriotv.android.ui.tv.dpadFocusEscape
import kotlin.math.abs
import kotlin.math.roundToInt
import com.aeriotv.android.ui.tv.TvKeyboardOnOkHost
import com.aeriotv.android.ui.tv.tvFormFieldInput

/**
 * App Behaviors sub-screen. Mirrors iOS AppBehaviorsSettingsView.swift:
 * launch behaviour toggles + channel-flip gesture toggle. Adds a Default Tab
 * picker which iOS keeps in a different surface but lives here for parity with
 * the @AppStorage("defaultTab") key (architecture spec section C).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBehaviorsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val skipLoadingScreen by viewModel.skipLoadingScreen.collectAsStateWithLifecycle(initialValue = false)
    val appleTVChannelFlip by viewModel.appleTVChannelFlip.collectAsStateWithLifecycle(initialValue = true)
    val autoRecoverFrozenStreams by viewModel.autoRecoverFrozenStreams.collectAsStateWithLifecycle(initialValue = true)
    val autoResumeLastChannel by viewModel.autoResumeLastChannel.collectAsStateWithLifecycle(initialValue = false)
    val defaultTab by viewModel.defaultTab.collectAsStateWithLifecycle(initialValue = "")
    val defaultLiveTVView by viewModel.defaultLiveTVView.collectAsStateWithLifecycle(initialValue = "")
    val programPostersTmdb by viewModel.programPostersTmdbEnabled.collectAsStateWithLifecycle(initialValue = false)
    val audioPassthrough by viewModel.audioPassthroughEnabled.collectAsStateWithLifecycle(initialValue = false)
    val savedTmdbKey by viewModel.tmdbApiKey.collectAsStateWithLifecycle(initialValue = "")
    val tmdbKeyState by viewModel.tmdbKeyTestState.collectAsStateWithLifecycle()

    val isTv = rememberIsTvDevice()
    TvKeyboardOnOkHost {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "App Behaviors", onBack = onBack)

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            modifier = Modifier
                .adaptiveFormWidth()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    // 104dp bottom clears the MainScaffold NavigationBar
                    // so the last section stays reachable on short displays.
                    bottom = 104.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsSection(
                header = "Launch",
                footer = "Skip loading screen may cause brief stutter while data hydrates. Resume last channel re-opens the player on launch if the saved channel still exists in your playlist.",
            ) {
                SettingsToggleRow(
                    title = "Skip loading screen",
                    subtitle = "Land on Live TV instantly; data hydrates in the background",
                    checked = skipLoadingScreen,
                    onCheckedChange = viewModel::setSkipLoadingScreen,
                )
                SettingsToggleRow(
                    title = "Resume last channel",
                    subtitle = "Auto-start the last-played channel on launch.",
                    checked = autoResumeLastChannel,
                    onCheckedChange = viewModel::setAutoResumeLastChannel,
                )
            }

            SettingsSection(
                header = "Default Tab",
                footer = "The tab shown when the app first launches.",
            ) {
                AppTab.entries.forEach { tab ->
                    val selected = (defaultTab.isEmpty() && tab == AppTab.LiveTV) ||
                        defaultTab == tab.name
                    SettingsSelectionRow(
                        label = tab.label,
                        selected = selected,
                        onClick = { viewModel.setDefaultTab(tab.name) },
                    )
                }
            }

            SettingsSection(
                header = "Default Live TV View",
                footer = "Which layout Live TV opens in. Automatic uses the List on " +
                    "phones and the Guide on TV and larger tablets. You can still " +
                    "switch anytime with the List / Guide button; that switch lasts " +
                    "for the current session and does not change this default.",
            ) {
                val current = defaultLiveTVView.lowercase()
                DEFAULT_LIVE_TV_VIEW_OPTIONS.forEach { (value, label) ->
                    SettingsSelectionRow(
                        label = label,
                        selected = current == value,
                        onClick = { viewModel.setDefaultLiveTVView(value) },
                    )
                }
            }

            // Live Rewind (task #145 P2): full surface. Storage location
            // (USB / network targets) arrives in P3.
            val liveRewindEnabled by viewModel.liveRewindEnabled.collectAsStateWithLifecycle(initialValue = false)
            val liveRewindDepth by viewModel.liveRewindDepthMinutes.collectAsStateWithLifecycle(initialValue = 30)
            SettingsSection(
                header = "Live Rewind",
                footer = "Buffers the channel you are watching so you can pause and " +
                    "rewind live TV. Uses device storage while you watch; buffered " +
                    "video is removed automatically.",
            ) {
                SettingsToggleRow(
                    title = "Pause & rewind live TV",
                    subtitle = "Buffer fullscreen live playback on this device",
                    checked = liveRewindEnabled,
                    onCheckedChange = viewModel::setLiveRewindEnabled,
                )
            }
            if (liveRewindEnabled) {
                // Reworked 2026-07-11 (user directive, round 2): ONE
                // slider - how far back the user can rewind. Retention
                // as a user concept is gone; buffered video is deleted
                // about an hour after the session ends (fixed, internal:
                // TimeshiftController.FIXED_RETENTION_MS), and the
                // free-space floor in TimeshiftBufferStore stays as the
                // seatbelt. Storage estimates scale with the depth
                // choice since that bounds live disk usage.
                SettingsSection(
                    header = "Keep Available",
                    footer = "How far back you can rewind the channel you are " +
                        "watching. Buffered video is removed about an hour " +
                        "after you leave a channel. " +
                        depthEstimateText(liveRewindDepth),
                ) {
                    SteppedSliderRow(
                        label = "Rewind up to",
                        values = REWIND_DEPTH_MINUTES,
                        selected = liveRewindDepth,
                        format = ::formatDepthMinutes,
                        onSelect = viewModel::setLiveRewindDepthMinutes,
                    )
                }
            }

            SettingsSection(
                header = "Channel Flip Gesture",
                // tvOS / Android TV flip channels with D-pad up/down, not a
                // swipe, so the "accidental swipes" caution is meaningless on
                // a remote (user request: drop the note on TV). Phones keep it.
                footer = if (isTv) null
                else "Turn off if accidental swipes during playback flip channels by mistake.",
            ) {
                SettingsToggleRow(
                    title = "Up / Down channel change",
                    subtitle = if (isTv)
                        "While the player chrome is visible, press up for the next channel and down for the previous. Live single-stream playback only."
                    else
                        "While the player chrome is visible, swipe up for the next channel and down for the previous. Live single-stream playback only.",
                    checked = appleTVChannelFlip,
                    onCheckedChange = viewModel::setAppleTVChannelFlip,
                )
            }

            SettingsSection(
                header = "Stream Recovery",
                footer = "If a live stream stops sending video, the player reloads it to recover. Turn this off if live channels restart or stutter during commercial breaks; a brief freeze may show instead. Applies to the next channel you tune.",
            ) {
                SettingsToggleRow(
                    title = "Auto-Recover Frozen Streams",
                    subtitle = "Reload a live stream that stops sending video. Off keeps the stream as-is through commercial-break stutters.",
                    checked = autoRecoverFrozenStreams,
                    onCheckedChange = viewModel::setAutoRecoverFrozenStreams,
                )
            }

            SettingsSection(
                header = "Audio",
                footer = "Passthrough sends Dolby audio as a bitstream for your TV or receiver to decode. Some TVs decode it late, which shows up as voices out of sync with lips on live TV. Off, AerioTV decodes audio itself and stays in sync. Takes effect on the next playback.",
            ) {
                SettingsToggleRow(
                    title = "Dolby passthrough",
                    subtitle = "Bitstream AC3 to your TV or receiver. Leave off if lip sync drifts.",
                    checked = audioPassthrough,
                    onCheckedChange = viewModel::setAudioPassthroughEnabled,
                )
            }

            SettingsSection(
                header = "Program Posters",
                footer = "Show posters in the Program Info panel and fill in missing artwork on On Demand detail screens, looked up on TMDB with your own free API key (themoviedb.org). Off by default. The key syncs across your devices via Google Drive (kept in your private app data).",
            ) {
                SettingsToggleRow(
                    title = "TMDB poster fallback",
                    subtitle = "When a poster is missing, look it up on TMDB. Needs the free API key below.",
                    checked = programPostersTmdb,
                    onCheckedChange = viewModel::setProgramPostersTmdbEnabled,
                )
                if (programPostersTmdb) {
                    var keyDraft by remember(savedTmdbKey) { mutableStateOf(savedTmdbKey) }
                    var keyVisible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = {
                            keyDraft = it
                            viewModel.resetTmdbKeyTestState()
                        },
                        label = { Text("TMDB API key (v3) or read token (v4)") },
                        singleLine = true,
                        visualTransformation = if (keyVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(
                                onClick = { keyVisible = !keyVisible },
                                modifier = Modifier.dpadFocusRing(CircleShape),
                            ) {
                                Icon(
                                    imageVector = if (keyVisible) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                    contentDescription = if (keyVisible) "Hide key" else "Show key",
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .tvFormFieldInput(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.testTmdbKey(keyDraft) },
                            enabled = keyDraft.isNotBlank() &&
                                tmdbKeyState != SettingsViewModel.TmdbKeyTestState.Testing,
                            modifier = Modifier.dpadFocusRing(RoundedCornerShape(50)),
                        ) { Text("Test") }
                        TextButton(
                            onClick = { viewModel.saveTmdbKey(keyDraft) },
                            enabled = keyDraft.isNotBlank() || savedTmdbKey.isNotBlank(),
                            modifier = Modifier.dpadFocusRing(RoundedCornerShape(50)),
                        ) { Text("Save") }
                        Spacer(Modifier.weight(1f))
                        val (statusText, statusColor) = when (tmdbKeyState) {
                            SettingsViewModel.TmdbKeyTestState.Testing ->
                                "Checking..." to MaterialTheme.colorScheme.onSurfaceVariant
                            SettingsViewModel.TmdbKeyTestState.Valid ->
                                "Valid key" to androidx.compose.ui.graphics.Color(0xFF4CAF50)
                            SettingsViewModel.TmdbKeyTestState.Invalid ->
                                "Invalid key" to MaterialTheme.colorScheme.error
                            SettingsViewModel.TmdbKeyTestState.Saved ->
                                "Saved" to androidx.compose.ui.graphics.Color(0xFF4CAF50)
                            SettingsViewModel.TmdbKeyTestState.Idle ->
                                "" to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        if (statusText.isNotEmpty()) {
                            Text(
                                statusText,
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor,
                            )
                        }
                    }
                }
            }

        }
        }
    }
    }
}

/** Default Live TV View choices. Empty string = "Automatic" (form-factor
 *  default: List on compact phones, Guide on tablets / TV). "list" / "guide"
 *  are explicit overrides. Written to the same [defaultLiveTVView] pref the
 *  Live TV screen reads; the in-screen List / Guide button is session-only and
 *  never writes here. */
private val DEFAULT_LIVE_TV_VIEW_OPTIONS = listOf(
    "" to "Automatic",
    "list" to "List",
    "guide" to "Guide",
)

/** Keep Available ladder (2026-07-11 rework, user directive round 2:
 *  the user-meaningful knob is HOW FAR BACK you can rewind, not how
 *  long files persist - retention is now a fixed internal 1 hour). */
private val REWIND_DEPTH_MINUTES = listOf(15, 30, 60, 90, 120, 180)

private fun formatDepthMinutes(mins: Int): String = when {
    mins < 60 -> "$mins minutes"
    mins == 60 -> "1 hour"
    mins % 60 == 0 -> "${mins / 60} hours"
    else -> "${mins / 60}h ${mins % 60}m"
}

/**
 * Storage estimate under the Keep Available slider: scales with the
 * depth choice (which bounds live disk usage now that retention is a
 * fixed short window) at typical stream bitrates - HD ~4 Mbps, FHD ~8,
 * UHD ~20 - so the user can pick what fits their streams and disk.
 */
private fun depthEstimateText(mins: Int): String {
    fun gb(mbps: Int): String {
        val v = mbps * 7.5 * mins / 1024.0
        return if (v < 10) String.format("~%.1f GB", v) else "~${v.roundToInt()} GB"
    }
    return "Uses up to ${gb(4)} in HD, ${gb(8)} in FHD, or ${gb(20)} in UHD while you watch."
}

/**
 * Discrete-stop slider row: label left, current value right, a stepped
 * Material slider beneath. TV-safe via [dpadFocusEscape] (the #90
 * lesson: UP/DOWN must move focus off the slider, LEFT/RIGHT adjust).
 */
@Composable
private fun SteppedSliderRow(
    label: String,
    values: List<Int>,
    selected: Int,
    format: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    // Snap legacy/custom persisted values (e.g. 48h from the removed
    // custom dialog) to the nearest ladder stop for display; the pref
    // itself is only rewritten when the user moves the slider.
    val idx = values.indices.minByOrNull { abs(values[it] - selected) } ?: 0
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = format(values[idx]),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = idx.toFloat(),
            onValueChange = { raw ->
                val newIdx = raw.roundToInt().coerceIn(0, values.lastIndex)
                if (values[newIdx] != selected) onSelect(values[newIdx])
            },
            valueRange = 0f..values.lastIndex.toFloat(),
            steps = (values.size - 2).coerceAtLeast(0),
            modifier = Modifier.dpadFocusEscape(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}
