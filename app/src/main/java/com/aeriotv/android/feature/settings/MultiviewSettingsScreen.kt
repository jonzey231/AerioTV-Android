package com.aeriotv.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.ui.adaptive.adaptiveFormWidth
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.settings.SettingsSelectionRow
import com.aeriotv.android.ui.settings.SettingsToggleRow
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset

/**
 * Multiview sub-screen. Mirrors iOS MultiviewSettingsView field-for-field:
 * Audio Focus Indicator style picker + Tile Padding toggle + Rounded
 * Corners toggle. Each pref is read by MultiviewScreen at compose time so
 * changes take effect without re-entering multiview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiviewSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val style by viewModel.multiviewAudioFocusStyle.collectAsStateWithLifecycle(initialValue = "centerIcon")
    val padding by viewModel.multiviewTilePadding.collectAsStateWithLifecycle(initialValue = false)
    val rounded by viewModel.multiviewTileCornersRounded.collectAsStateWithLifecycle(initialValue = false)

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "Multiview", onBack = onBack)

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
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
                    // so the Tile Appearance card's footer text stays
                    // visible on short displays.
                    bottom = LocalTabBarBottomInset.current,
                ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsSection(
                header = "Audio Focus Indicator",
                footer = "How the grid shows which tile is unmuted. Center Icon fades with the chrome, Gray Outline stays visible, Accent Outline appears on switch and fades after 5 seconds.",
            ) {
                AUDIO_FOCUS_OPTIONS.forEach { opt ->
                    SettingsSelectionRow(
                        label = opt.label,
                        subtitle = opt.detail,
                        selected = style == opt.id,
                        onClick = { viewModel.setMultiviewAudioFocusStyle(opt.id) },
                    )
                }
            }

            SettingsSection(
                header = "Spacing",
                footer = "Insert a small gap between tiles so each stream stands on its own.",
            ) {
                SettingsToggleRow(
                    title = "Padding Between Tiles",
                    subtitle = "Add a small gap between tiles for visual separation.",
                    checked = padding,
                    onCheckedChange = viewModel::setMultiviewTilePadding,
                )
            }

            // tvOS presents corners as a Square / Rounded selection (s_09)
            // rather than a single toggle; same underlying Boolean.
            SettingsSection(header = "Tile Corners") {
                SettingsSelectionRow(
                    label = "Square",
                    selected = !rounded,
                    onClick = { viewModel.setMultiviewTileCornersRounded(false) },
                )
                SettingsSelectionRow(
                    label = "Rounded",
                    selected = rounded,
                    onClick = { viewModel.setMultiviewTileCornersRounded(true) },
                )
            }
        }
        }
    }
}

private data class AudioFocusOption(val id: String, val label: String, val detail: String)

private val AUDIO_FOCUS_OPTIONS: List<AudioFocusOption> = listOf(
    AudioFocusOption("centerIcon", "Center Icon", "Speaker icon centered on the active tile. Default."),
    AudioFocusOption("grayPersistent", "Gray Outline", "Subtle gray border always around the active tile."),
    AudioFocusOption("themeFading", "Accent Outline (Fading)", "Accent-tinted border that auto-hides after 5 seconds."),
)
