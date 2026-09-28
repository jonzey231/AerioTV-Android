package com.aeriotv.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import com.aeriotv.android.ui.settings.SettingsRowDivider
import kotlin.math.roundToInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.ui.TmdbAttribution
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsPickerOption
import com.aeriotv.android.ui.settings.SettingsPickerRow
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.settings.SettingsSubPageHost
import com.aeriotv.android.ui.settings.SettingsTextField
import com.aeriotv.android.ui.settings.SettingsToggleRow
import com.aeriotv.android.ui.settings.dpadFocusRing
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.settingsFormWidth
import com.aeriotv.android.ui.tv.TvKeyboardOnOkHost

/**
 * Settings > Movies & TV Shows. Library refresh cadence, TMDB poster lookup,
 * and the Movies & Series display scale.
 *
 * Settings phase 1 regroup: the refresh cadence and Program Posters rows came
 * from App Behaviors, the scale row from Appearance. Keys, control types and
 * copy are carried over unchanged, including the TV-only gate on the refresh
 * cadence rows.
 */
@Composable
fun MoviesAndTvShowsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val isTv = rememberIsTvDevice()
    val vodRefreshHours by viewModel.vodLibraryRefreshHours.collectAsStateWithLifecycle(initialValue = 24)
    val programPostersTmdb by viewModel.programPostersTmdbEnabled.collectAsStateWithLifecycle(initialValue = false)
    val savedTmdbKey by viewModel.tmdbApiKey.collectAsStateWithLifecycle(initialValue = "")
    val tmdbKeyState by viewModel.tmdbKeyTestState.collectAsStateWithLifecycle()
    val scaleMovies by viewModel.displayScaleMovies.collectAsStateWithLifecycle(initialValue = 1.0f)

    TvKeyboardOnOkHost {
        SettingsSubPageHost {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsDetailTopBar(title = "Movies & TV Shows", onBack = onBack)

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.settingsFormWidth(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = LocalTabBarBottomInset.current,
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // MARK: Refresh library
                    //
                    // Shown on every form factor (Apple parity): the cadence is
                    // read by the shared OnDemandViewModel. Apple's iOS page is
                    // one picker row with no section header (the row already
                    // says "Refresh Library") and the explanation on the choice
                    // page; its tvOS page keeps the header and shows the choices
                    // inline; the picker itself draws the explanation under them.
                    item("refresh-library") {
                        SettingsSection(
                            header = if (isTv) "Refresh Library" else "",
                        ) {
                            SettingsPickerRow(
                                title = "Refresh Library",
                                options = listOf(
                                    SettingsPickerOption(0, "Every Launch", "Re-sweep the provider library on every launch"),
                                    SettingsPickerOption(24, "Daily", "Open from the saved library; re-sweep the provider once a day"),
                                    SettingsPickerOption(168, "Weekly", "Open from the saved library; re-sweep the provider once a week"),
                                ),
                                selected = vodRefreshHours,
                                onSelect = viewModel::setVodLibraryRefreshHours,
                                footer = VOD_REFRESH_FOOTNOTE,
                                // Apple: arrow.clockwise.
                                leadingIcon = Icons.Filled.Refresh,
                            )
                        }
                    }

                    // MARK: Posters
                    item("posters") {
                        Column {
                            SettingsSection(
                                header = "Posters",
                                // Apple iOS: the footer is always shown. Apple tvOS:
                                // the same text sits under the buttons, only while
                                // the toggle is on (rendered below the card here).
                                footer = if (isTv) null else TMDB_FOOTER_PHONE,
                            ) {
                                SettingsToggleRow(
                                    title = "Fetch Posters from TMDB",
                                    subtitle = "Fill in program artwork your provider doesn't supply, using The Movie Database.",
                                    checked = programPostersTmdb,
                                    onCheckedChange = viewModel::setProgramPostersTmdbEnabled,
                                )
                                if (programPostersTmdb) {
                                    // The key field and buttons are not a row
                                    // helper, so they get the card's divider and
                                    // row inset here; without it they ran to the
                                    // card edge (Apple insets them like any row).
                                    SettingsRowDivider()
                                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    var keyDraft by remember(savedTmdbKey) { mutableStateOf(savedTmdbKey) }
                                    val testing = tmdbKeyState == SettingsViewModel.TmdbKeyTestState.Testing
                                    SettingsTextField(
                                        label = "TMDB API Key",
                                        placeholder = "API Key or Read Access Token",
                                        value = keyDraft,
                                        onValueChange = {
                                            keyDraft = it
                                            viewModel.resetTmdbKeyTestState()
                                        },
                                        secure = true,
                                        secureLabel = "key",
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // Test is the outlined secondary, Save the
                                        // filled primary, as Apple pairs them.
                                        OutlinedButton(
                                            onClick = { viewModel.testTmdbKey(keyDraft) },
                                            // Apple tvOS does not gate Test on an empty
                                            // key: a disabled button cannot take D-pad
                                            // focus. Touch gates it, as Apple iOS does.
                                            enabled = !testing && (isTv || keyDraft.isNotBlank()),
                                            modifier = Modifier.dpadFocusRing(RoundedCornerShape(50)),
                                        ) { Text(if (testing && isTv) "Testing..." else "Test") }
                                        val (statusText, statusColor) = when (tmdbKeyState) {
                                            SettingsViewModel.TmdbKeyTestState.Valid ->
                                                "Valid key" to androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                            SettingsViewModel.TmdbKeyTestState.Invalid ->
                                                "Invalid key" to MaterialTheme.colorScheme.error
                                            SettingsViewModel.TmdbKeyTestState.Saved ->
                                                "Saved" to androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                            // Apple shows no status text while idle or testing.
                                            else -> "" to MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        if (!isTv && statusText.isNotEmpty()) {
                                            Text(statusText, style = MaterialTheme.typography.labelMedium, color = statusColor)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Button(
                                            onClick = { viewModel.saveTmdbKey(keyDraft) },
                                            enabled = !testing,
                                            modifier = Modifier.dpadFocusRing(RoundedCornerShape(50)),
                                        ) { Text("Save") }
                                        if (isTv && statusText.isNotEmpty()) {
                                            Text(statusText, style = MaterialTheme.typography.labelMedium, color = statusColor)
                                        }
                                    }
                                    }
                                }
                            }
                            if (isTv && programPostersTmdb) {
                                com.aeriotv.android.ui.settings.SettingsSectionFooter(TMDB_FOOTER_TV)
                            }
                            // Apple iOS: short attribution inside the footer, always.
                            // Apple tvOS: long attribution below, always.
                            TmdbAttribution(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                long = isTv,
                                isTv = isTv,
                            )
                        }
                    }

                    // MARK: Display Scale
                    settingsCard(
                        header = "Display Scale",
                        footer = "Independent scale for Movies & Series. 100% matches the default; " +
                            "85-150% lets you trade density for readability. Changes apply live, " +
                            "no restart needed.",
                    ) {
                        if (isTv) {
                            // tvOS keeps percentage segments (remote friendly).
                            ScaleSliderRow(
                                label = "Movies & TV Shows",
                                value = scaleMovies,
                                onValueChange = viewModel::setDisplayScaleMovies,
                                segments = MOVIES_SCALE_SEGMENTS,
                            )
                        } else {
                            ScaleAToASliderRow(
                                label = "Movies & TV Shows",
                                value = scaleMovies,
                                onValueChange = viewModel::setDisplayScaleMovies,
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

/** Apple MoviesTVSettingsView.vodRefreshFootnote, word for word. */
private const val VOD_REFRESH_FOOTNOTE =
    "Live TV channels refresh on every launch. Movies and TV Shows open from the saved library and re-sweep the provider on this schedule. Pull down on either tab to refresh right away."

// Apple's footers say iCloud Keychain; Android carries the key in the Sync
// snapshot to the user's own Google Drive app data (AppPreferences sync
// export), so the sentence names that instead. The rest is Apple's text.
private const val TMDB_SYNC_SENTENCE =
    "If Sync is enabled, your key is saved to your Google Drive app data and syncs to your other devices."
private const val TMDB_FOOTER_PHONE = TMDB_SYNC_SENTENCE +
    " Get a free key at themoviedb.org under Settings, then API; paste either the API Key or the Read Access Token. With a key, artwork and details for Movies and TV Shows come from TMDB first and your provider fills any gaps."
private const val TMDB_FOOTER_TV = TMDB_SYNC_SENTENCE +
    " Get a free key at themoviedb.org; paste either the API Key or the Read Access Token. With a key, artwork and details for Movies and TV Shows come from TMDB first and your provider fills any gaps."

/**
 * Apple iOS scaleSliderRow_iOS: title with the percent small and dim at the
 * right, then a small "A", the 85-150% slider in 5% steps, and a large "A".
 */
@Composable
private fun ScaleAToASliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = com.aeriotv.android.ui.settings.settingsRowTitleStyle(),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(value * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = com.aeriotv.android.ui.settings.settingsDimTint(),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("A", fontSize = 12.sp, color = com.aeriotv.android.ui.settings.settingsDimTint())
            com.aeriotv.android.ui.settings.SettingsSlider(
                value = value.coerceIn(0.85f, 1.5f),
                // Snap to Apple's 5% step so the stored value is one of its stops.
                onValueChange = { raw -> onValueChange((raw * 20f).roundToInt() / 20f) },
                valueRange = 0.85f..1.5f,
                steps = 12,
                modifier = Modifier.weight(1f),
            )
            Text("A", fontSize = 16.sp, color = com.aeriotv.android.ui.settings.settingsDimTint())
        }
    }
}
