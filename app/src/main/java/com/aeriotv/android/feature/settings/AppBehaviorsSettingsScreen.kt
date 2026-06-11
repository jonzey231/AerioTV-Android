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
    val autoResumeLastChannel by viewModel.autoResumeLastChannel.collectAsStateWithLifecycle(initialValue = false)
    val defaultTab by viewModel.defaultTab.collectAsStateWithLifecycle(initialValue = "")
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
                    // so the Default Tab list at the bottom stays
                    // reachable on short displays.
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

            SettingsSection(
                header = "Default Tab",
                footer = "Which tab the app lands on after launch. Live TV is the iOS default.",
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
        }
        }
    }
    }
}
