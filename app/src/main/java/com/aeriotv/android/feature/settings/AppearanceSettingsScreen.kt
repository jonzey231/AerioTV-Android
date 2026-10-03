package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.category.ProgramCategory
import com.aeriotv.android.core.category.parseHex
import com.aeriotv.android.ui.adaptive.rememberViewport
import com.aeriotv.android.ui.settings.SettingsSliderRow
import com.aeriotv.android.ui.settings.LocalSettingsInPane
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.SettingsToggleAffordance
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import com.aeriotv.android.ui.settings.dpadFocusRing
import com.aeriotv.android.ui.settings.SettingsPickerOption
import com.aeriotv.android.ui.settings.SettingsPickerRow
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.settings.SettingsSubPageHost
import com.aeriotv.android.ui.settings.SettingsTextField
import com.aeriotv.android.ui.settings.settingsFormWidth
import com.aeriotv.android.ui.settings.dpadFocusWash
import com.aeriotv.android.core.preferences.TEXT_SCALE_MAX
import com.aeriotv.android.core.preferences.TEXT_SCALE_MIN
import com.aeriotv.android.ui.theme.AppTheme
import com.aeriotv.android.ui.tv.dpadFocusEscape
import kotlin.math.roundToInt
import com.aeriotv.android.ui.theme.AppearanceMode
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset

/**
 * Appearance sub-screen. Mirrors iOS Settings -> Appearance
 * (project_aeriotv_ios_canon.md "Appearance" section).
 *
 * Theme card -> 6 brand presets + Custom Accent override + a live Preview
 * tile so the user can see how their accent reads on a card without leaving
 * Settings. Display Scale card -> independent Movies & Series / Live TV
 * sliders (85-150%). Category Colors card -> master toggle. Palette card ->
 * default buckets + Add More Categories + Reset.
 *
 * Theme propagation: changes from `viewModel.setSelectedTheme` /
 * `setUseCustomAccent` / `setCustomAccentHex` flow through DataStore into
 * MainActivity's collectAsState bindings, which rebuild AerioTVTheme's
 * Material3 colorScheme. Every surface that reads MaterialTheme.colorScheme
 * (top bars, nav bar, sheets, dialogs, mini-player, splash) re-themes in
 * the same frame - no recreate needed.
 */
/**
 * Minimum PANE width before this screen lays content out two-up (plan B5).
 *
 * One breakpoint for the theme swatches, the category palette and the two
 * display-scale sliders, so the pane reflows once instead of stepping three
 * times as it widens. 560dp is the figure the plan sets for the sliders.
 */
private val TwoUpMinPaneWidth = 560.dp

/**
 * Whether [paneWidth] should lay its content out in two columns.
 *
 * Gated on being IN A PANE rather than on raw width: a phone in landscape is
 * ~891dp, and a width-only test would reflow it and break the frozen phone
 * canon. `LocalSettingsInPane` defaults to false and is only ever set by the
 * two hosts, so a phone can never satisfy this.
 *
 * TV is excluded even though its rail host sets the same flag and its pane
 * clears the threshold. Two columns would add a horizontal D-pad axis inside
 * the pane that competes with the rail boundary; the plan scopes this to
 * tablet, so TV stays single-column.
 */
@Composable
internal fun twoUpInPane(paneWidth: Dp): Boolean =
    LocalSettingsInPane.current &&
        !rememberIsTvDevice() &&
        paneWidth >= TwoUpMinPaneWidth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val currentTheme by viewModel.selectedTheme.collectAsStateWithLifecycle(initialValue = AppTheme.Aerio)
    val appearanceMode by viewModel.appearanceMode.collectAsStateWithLifecycle(initialValue = AppearanceMode.Dark)
    val isTv = rememberIsTvDevice()
    val textScale by viewModel.textScale.collectAsStateWithLifecycle(initialValue = 1.0f)
    val subtextScale by viewModel.subtextScale.collectAsStateWithLifecycle(initialValue = 1.0f)
    val textContrast by viewModel.textContrast.collectAsStateWithLifecycle(initialValue = 0f)
    val useCustomAccent by viewModel.useCustomAccent.collectAsStateWithLifecycle(initialValue = false)
    val customAccentHex by viewModel.customAccentHex.collectAsStateWithLifecycle(initialValue = "")
    val timeFormat by viewModel.timeFormat.collectAsStateWithLifecycle(initialValue = "system")

    var accentPickerOpen by remember { mutableStateOf(false) }

    SettingsSubPageHost {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "Appearance", onBack = onBack)

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.settingsFormWidth(),
                // Bottom padding covers the bottom-nav bar (~80 dp) plus
                // 24 dp breathing room, so the last LazyColumn item ("Reset
                // Colors to Defaults" under the Palette card) isn't clipped
                // behind MainScaffold's NavigationBar. Without this, the
                // user can't scroll past the nav bar to reach the Reset row
                // or the Add More Categories navigator, because the LazyColumn
                // hits its content edge first.
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = LocalTabBarBottomInset.current,
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // COLOR THEME card - the brand presets. Apple splits the old
                // combined "Theme" card into Color Theme / Light and Dark Mode
                // / Accent; Android matches that structure here.
                // tvOS: Color Theme is ONE choice row with the Custom Accent
                // toggle under it in the same section, and no footers.
                if (isTv) item("tv-color-theme") {
                    SettingsSection(header = "Color Theme") {
                        SettingsPickerRow(
                            title = "Color Theme",
                            options = AppTheme.entries.map { SettingsPickerOption(it, it.displayName) },
                            selected = currentTheme,
                            onSelect = viewModel::setSelectedTheme,
                            leadingIcon = Icons.Filled.Brush,
                            tvChoiceSheet = true,
                        )
                        CustomAccentRow(
                            enabled = useCustomAccent,
                            hex = customAccentHex,
                            onToggle = viewModel::setUseCustomAccent,
                            onPick = { accentPickerOpen = true },
                        )
                    }
                } else settingsCard(
                    header = "Color Theme",
                    footer = "Colors used throughout the app.",
                ) {
                    // Plan B5: "theme swatch grid at doubled density" on
                    // tablet. A theme row is a 36dp swatch plus two short
                    // lines, so in a wide pane a single column strands the
                    // checkmark hundreds of dp from the swatch and pushes the
                    // rest of the card below the fold. BoxWithConstraints
                    // measures the PANE, so the sidebar's 320dp is already
                    // excluded. See twoUpInPane for the gating rationale.
                    BoxWithConstraints {
                        val twoColumn = twoUpInPane(maxWidth)
                        Column {
                            if (twoColumn) {
                                AppTheme.entries.chunked(2)
                                    .forEachIndexed { rowIndex, pair ->
                                        if (rowIndex > 0) DividerRow(ThemeRowDividerInset)
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            pair.forEach { theme ->
                                                ThemeRow(
                                                    theme = theme,
                                                    selected = theme == currentTheme,
                                                    onClick = { viewModel.setSelectedTheme(theme) },
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                            // Odd count: hold the last row's
                                            // column so the lone theme keeps
                                            // its width instead of stretching.
                                            if (pair.size == 1) {
                                                Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                            } else {
                                AppTheme.entries.forEachIndexed { index, theme ->
                                    if (index > 0) DividerRow(ThemeRowDividerInset)
                                    ThemeRow(
                                        theme = theme,
                                        selected = theme == currentTheme,
                                        onClick = { viewModel.setSelectedTheme(theme) },
                                    )
                                }
                            }
                        }
                    }
                }

                // LIGHT AND DARK MODE card. Orthogonal to the theme above:
                // this picks surface luminance, the theme picks hue.
                // Selecting the Light THEME does not flip this control.
                item("light-dark-mode") {
                    SettingsSection(
                        header = "Light and Dark Mode",
                        footer = if (isTv) null else "Light or dark surfaces, independent of the color theme.",
                    ) {
                        SettingsPickerRow(
                            title = "Mode",
                            options = APPEARANCE_MODE_ORDER.map { mode ->
                                SettingsPickerOption(
                                    value = mode,
                                    label = appearanceModeLabel(mode),
                                    subtitle = appearanceModeSubtitle(mode),
                                )
                            },
                            selected = appearanceMode,
                            onSelect = viewModel::setAppearanceMode,
                            leadingIcon = Icons.Filled.Contrast,
                            tvChoiceSheet = true,
                        )
                    }
                }

                // ACCENT card - the custom accent override for the theme's
                // preset accent color.
                if (!isTv) item("accent") {
                    SettingsSection(
                        header = "Accent",
                        footer = "Override the theme's accent color with one of your own.",
                    ) {
                        CustomAccentRow(
                            enabled = useCustomAccent,
                            hex = customAccentHex,
                            onToggle = viewModel::setUseCustomAccent,
                            onPick = { accentPickerOpen = true },
                        )
                    }
                }

                // PREVIEW card - shows how the active theme reads on a card,
                // including the accent-tinted title and Now-Playing pill.
                item {
                    PreviewCard(
                        theme = currentTheme,
                        customAccentHex = customAccentHex.takeIf { useCustomAccent },
                    )
                }

                // TEXT SIZE card: one app-wide multiplier on every sp (applied
                // at the composition root). The Display Scale sliders below
                // still multiply on top for their own surfaces.
                if (isTv) item("tv-Text Size") {
                    SettingsSection(header = "Text Size") {
                        TvPercentStepperRow(
                            title = "Text Size",
                            stops = TEXT_SCALE_STOPS,
                            value = textScale,
                            default = 1f,
                            onValueChange = viewModel::setTextScale,
                        )
                    }
                } else settingsCard(
                    header = "Text Size",
                    footer = "Scales all text in AerioTV, on top of your device's font size. Changes apply live.",
                ) {
                    TextSizeSliderRow(
                        label = "Text Size",
                        stops = TEXT_SCALE_STOPS,
                        value = textScale,
                        onValueChange = viewModel::setTextScale,
                    )
                }

                // SUBTEXT SIZE card: extra multiplier for secondary copy only
                // (descriptions, subtitles, metadata), stacking on Text Size.
                if (isTv) item("tv-Subtext Size") {
                    SettingsSection(header = "Subtext Size") {
                        TvPercentStepperRow(
                            title = "Subtext Size",
                            stops = TEXT_SCALE_STOPS,
                            value = subtextScale,
                            default = 1f,
                            onValueChange = viewModel::setSubtextScale,
                        )
                    }
                } else settingsCard(
                    header = "Subtext Size",
                    footer = "Scales only secondary text such as descriptions, program details, and captions, on top of Text Size. Titles and buttons stay the same. Changes apply live.",
                ) {
                    TextSizeSliderRow(
                        label = "Subtext Size",
                        stops = TEXT_SCALE_STOPS,
                        value = subtextScale,
                        onValueChange = viewModel::setSubtextScale,
                    )
                }

                // TEXT CONTRAST card: blends dimmed and accent-tinted text
                // toward plain white (dark) / black (light).
                if (isTv) item("tv-Text Contrast") {
                    SettingsSection(header = "Text Contrast") {
                        TvPercentStepperRow(
                            title = "Text Contrast",
                            stops = TEXT_CONTRAST_STOPS,
                            value = textContrast,
                            default = 0f,
                            onValueChange = viewModel::setTextContrast,
                        )
                    }
                } else settingsCard(
                    header = "Text Contrast",
                    footer = "Makes dimmed and accent-colored text brighter in dark mode and darker in light mode. 0% keeps the theme's look, 100% uses plain white or black text. Changes apply live.",
                ) {
                    TextSizeSliderRow(
                        label = "Text Contrast",
                        stops = TEXT_CONTRAST_STOPS,
                        value = textContrast,
                        onValueChange = viewModel::setTextContrast,
                    )
                }

                // TIME FORMAT card: every clock in the app (guide header,
                // cell ranges, program info, DVR) follows this.
                item("time-format") {
                    SettingsSection(
                        header = "Time Format",
                        footer = if (isTv) null else "System follows your device's clock setting. Applies to the Guide, program info, and recordings.",
                    ) {
                        SettingsPickerRow(
                            title = "Time Format",
                            options = TIME_FORMAT_OPTIONS.map {
                                SettingsPickerOption(it.first, it.second)
                            },
                            selected = TIME_FORMAT_OPTIONS.firstOrNull { it.first == timeFormat }
                                ?.first ?: "system",
                            onSelect = viewModel::setTimeFormat,
                            leadingIcon = Icons.Filled.Schedule,
                            tvChoiceSheet = true,
                        )
                    }
                }

            }
        }
    }
    }

    if (accentPickerOpen) {
        AccentPickerDialog(
            current = customAccentHex,
            preset = currentTheme.accentPrimary,
            onDismiss = { accentPickerOpen = false },
            onSave = { hex ->
                viewModel.setCustomAccentHex(hex)
                viewModel.setUseCustomAccent(true)
                accentPickerOpen = false
            },
            onReset = {
                viewModel.setCustomAccentHex("")
                viewModel.setUseCustomAccent(false)
                accentPickerOpen = false
            },
        )
    }
}

/**
 * LazyListScope helper that lays out a header/card/footer triplet in three
 * items so the rounded card and the header keep their iOS-style 6dp gap and
 * the footer renders below the card edge. Matches the AppBehaviors /
 * Multiview / DVR SettingsCard composable visually, just unrolled for
 * LazyColumn.
 */
internal fun LazyListScope.settingsCard(
    header: String,
    footer: String?,
    content: @Composable () -> Unit,
) {
    item {
        Column {
            com.aeriotv.android.ui.settings.SettingsSectionHeader(header)
            com.aeriotv.android.ui.settings.SettingsCard { content() }
            if (footer != null) com.aeriotv.android.ui.settings.SettingsSectionFooter(footer)
        }
    }
}

private val TIME_FORMAT_OPTIONS = listOf(
    "system" to "System",
    "12" to "12-hour",
    "24" to "24-hour",
)

/**
 * Divider inset for the theme rows: 16dp row padding + the 22dp swatch + the
 * 14dp spacer, so the hairline starts at the title text like Apple's.
 */
internal val ThemeRowDividerInset = 52.dp

@Composable
internal fun DividerRow(startInset: Dp = 16.dp) {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f),
        modifier = Modifier.padding(start = startInset),
    )
}

@Composable
internal fun ThemeRow(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Row(
        modifier = modifier
            .dpadFocusWash()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Apple parity: a plain 22dp accent dot with a hairline ring, no
        // background tile. The ring keeps a light accent visible on a light
        // card.
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(theme.accentPrimary)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = theme.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = themeSubtitle(theme),
                style = MaterialTheme.typography.bodySmall.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun themeSubtitle(theme: AppTheme): String = when (theme) {
    AppTheme.Aerio -> "Cyan on deep navy (default)"
    AppTheme.Midnight -> "Cool blue on near-black"
    AppTheme.Sunset -> "Warm orange on near-black"
    AppTheme.Forest -> "Green on near-black"
    AppTheme.Lavender -> "Purple on near-black"
    AppTheme.Monochrome -> "Grayscale on near-black"
    AppTheme.Light -> "Neutral teal-gray that reads on white"
}

/** Picker order for the Light and Dark Mode card, matching Apple. */
private val APPEARANCE_MODE_ORDER = listOf(
    AppearanceMode.System,
    AppearanceMode.Light,
    AppearanceMode.Dark,
)

private fun appearanceModeLabel(mode: AppearanceMode): String = when (mode) {
    AppearanceMode.Dark -> "Dark"
    AppearanceMode.Light -> "Light"
    AppearanceMode.System -> "System"
}

private fun appearanceModeSubtitle(mode: AppearanceMode): String = when (mode) {
    AppearanceMode.Dark -> "Dark surfaces (default)"
    AppearanceMode.Light -> "Light surfaces"
    AppearanceMode.System -> "Follow the system setting"
}

/**
 * Custom accent color row. Mirrors iOS Appearance > "Custom Accent Color"
 * toggle + color picker (ThemeManager useCustomAccent / customAccentHex).
 * Toggle enables the override; tapping the swatch opens the hex picker.
 */
@Composable
private fun CustomAccentRow(
    enabled: Boolean,
    hex: String,
    onToggle: (Boolean) -> Unit,
    onPick: () -> Unit,
) {
    val swatch = if (enabled && hex.length == 6) parseHex(hex)
    else MaterialTheme.colorScheme.primary
    // Whole row is the toggle target (visible focus stop on TV); the swatch
    // stays a second focusable that opens the picker.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusWash()
            .clickable {
                val next = !enabled
                onToggle(next)
                if (next && hex.isBlank()) onPick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Apple's leading swatch: a 22pt rounded square in the custom hex
        // with a hairline border, standing in for an icon.
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (hex.length == 6) parseHex(hex) else MaterialTheme.colorScheme.primary)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(4.dp),
                ),
        )
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Custom Accent Color",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            // iOS has no subtitle (the section footer explains the row);
            // tvOS carries this one.
            if (rememberIsTvDevice()) {
                Text(
                    text = "Override the theme accent with a custom hex color",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (enabled) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(swatch)
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(50),
                    )
                    .dpadFocusRing(RoundedCornerShape(50))
                    .clickable(onClick = onPick),
            )
            Spacer(Modifier.size(8.dp))
        }
        SettingsToggleAffordance(checked = enabled, onCheckedChange = onToggle)
    }
}

/**
 * Live preview tile that renders the active accent over the active card
 * background, so the user sees the actual cyan/orange/etc. they're about
 * to commit to before leaving the screen. The accent is pulled from the
 * customAccentHex when set, otherwise from the theme's preset.
 */
@Composable
private fun PreviewCard(theme: AppTheme, customAccentHex: String?) {
    val accent = if (customAccentHex != null && customAccentHex.length == 6) parseHex(customAccentHex)
    else theme.accentPrimary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "PREVIEW",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(theme.cardBackground)
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFFFF4757)),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "LIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFF4757),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = "Channel 042",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent.copy(alpha = 0.65f),
                )
            }
            Text(
                text = "AerioTV Sample Program",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "8:00 PM - 9:00 PM  ·  30m remaining",
                style = MaterialTheme.typography.bodySmall,
                color = accent.copy(alpha = 0.65f),
            )
            Spacer(Modifier.size(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent.copy(alpha = 0.25f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(accent),
                )
            }
        }
    }
}

/** Flat single-choice row for this page's cards: same wash/padding as
 *  [ToggleRow], a check mark instead of the On/Off indicator. */
@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // Whole row is the toggle target, same as CustomAccentRow above. The
    // tvOS-chrome migration (cad31c2) swapped the Switch for the static
    // On/Off indicator but dropped the click along with it, leaving these
    // rows unfocusable on TV (D-pad skipped them) and inert to taps on
    // phone.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusWash()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        SettingsToggleAffordance(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Movies & TV Shows stops, matching Apple exactly (Logan 2026-09-18): the
 * Movies scale runs 85% .. 150% on every platform, and Live TV now shares that
 * ceiling (see [SCALE_SEGMENTS]).
 */
internal val MOVIES_SCALE_SEGMENTS: List<Pair<Float, String>> = listOf(
    0.85f to "85%", 1.00f to "100%", 1.15f to "115%",
    1.25f to "125%", 1.35f to "135%", 1.50f to "150%",
)

/**
 * Live TV stops. Logan 2026-09-18: 85% .. 150%, the same ceiling Movies uses -
 * the 175% step is gone on every platform and stored values above 1.5 are
 * clamped when [com.aeriotv.android.core.preferences.AppPreferences.displayScaleLiveTV]
 * is read.
 */
private val SCALE_SEGMENTS: List<Pair<Float, String>> = listOf(
    0.85f to "85%", 0.92f to "92%", 1.00f to "100%", 1.15f to "115%",
    1.25f to "125%", 1.50f to "150%",
)

/** Text Contrast stops: 0% .. 100% in 10% steps. */
/**
 * tvOS Text Size / Subtext Size / Text Contrast row: minus, percent, plus on
 * one focusable row (D-pad Left/Right), then a Reset pill to its right that
 * D-pad Right reaches from the top stop. Values are percent of [stops].
 */
@Composable
private fun TvPercentStepperRow(
    title: String,
    stops: List<Float>,
    value: Float,
    default: Float,
    onValueChange: (Float) -> Unit,
) {
    val percents = remember(stops) { stops.map { (it * 100).roundToInt() } }
    val current = (value * 100).roundToInt()
    val atDefault = current == (default * 100).roundToInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        com.aeriotv.android.ui.settings.SettingsIntStepperRow(
            title = title,
            options = percents,
            value = current,
            onValueChange = { onValueChange(it / 100f) },
            format = { "$it%" },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .padding(end = 12.dp)
                .clip(RoundedCornerShape(50))
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(50))
                .dpadFocusRing(
                    shape = RoundedCornerShape(50),
                    washTint = MaterialTheme.colorScheme.primary,
                )
                .clickable(enabled = !atDefault) { onValueChange(default) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = "Reset",
                style = com.aeriotv.android.ui.settings.settingsRowValueStyle(),
                color = MaterialTheme.colorScheme.textAccent.copy(alpha = if (atDefault) 0.4f else 1f),
            )
        }
    }
}

private val TEXT_CONTRAST_STOPS: List<Float> = (0..10).map { it / 10f }

/** Text Size / Subtext Size stops: 85% .. 150% in 5% steps. */
private val TEXT_SCALE_STOPS: List<Float> =
    (0..((TEXT_SCALE_MAX - TEXT_SCALE_MIN) * 20f).roundToInt()).map { TEXT_SCALE_MIN + it * 0.05f }

/**
 * Text Size / Subtext Size / Text Contrast row: label left, current percent
 * right, the shared Settings slider beneath. Same composable the App Behaviors
 * rows use, so the track, thumb and focus behavior can only be defined once
 * (UP/DOWN leave the slider, LEFT/RIGHT step one 5% stop).
 */
@Composable
private fun TextSizeSliderRow(
    label: String,
    stops: List<Float>,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    val idx = stops.indices.minByOrNull { kotlin.math.abs(stops[it] - value) } ?: 0
    SettingsSliderRow(
        label = label,
        valueText = "${(stops[idx] * 100f).roundToInt()}%",
        index = idx,
        lastIndex = stops.lastIndex,
        onIndexChange = { newIdx -> if (newIdx != idx) onValueChange(stops[newIdx]) },
    )
}

/**
 * Width at which the label and the seven percentage segments still fit on one
 * line. Below it the row stacks (label above, segments wrapping beneath).
 *
 * A Row measures its unweighted children FIRST, with an unbounded max width,
 * and only hands what is LEFT to the weighted ones. The seven segments are
 * unweighted, so on a phone they eat the whole row and the weighted label is
 * measured at a few dp, which is why it rendered one character per line.
 */
private val ScaleSegmentsInlineMinWidth = 560.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScaleSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    segments: List<Pair<Float, String>> = SCALE_SEGMENTS,
) {
    // tvOS renders Display Scale as inline percentage segments, not a slider
    // (cleaner with a remote + no focus-trap). The selected segment is filled.
    val isTv = rememberIsTvDevice()
    BoxWithConstraints(modifier = modifier) {
        // TV always keeps the single-line row (10-foot layout is wide and the
        // D-pad expects one horizontal axis). Everywhere else it depends on
        // whether the label and all seven segments actually fit.
        val inline = isTv || maxWidth >= ScaleSegmentsInlineMinWidth
        val segments: @Composable (Modifier) -> Unit = { segModifier ->
            FlowRow(
                modifier = segModifier,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                segments.forEach { (segValue, segLabel) ->
                    val selected = kotlin.math.abs(value - segValue) < 0.03f
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                else Color.Transparent,
                            )
                            .dpadFocusRing(
                                shape = RoundedCornerShape(8.dp),
                                washTint = MaterialTheme.colorScheme.primary,
                            )
                            .clickable { onValueChange(segValue) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = segLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) MaterialTheme.colorScheme.textAccent
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        val labelText: @Composable (Modifier) -> Unit = { labelModifier ->
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                modifier = labelModifier,
            )
        }
        if (inline) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labelText(Modifier.weight(1f))
                segments(Modifier)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                labelText(Modifier.fillMaxWidth())
                segments(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun CategoryPaletteRow(
    bucket: ProgramCategory,
    hex: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val swatch = parseHex(hex)
    Row(
        modifier = modifier
            .dpadFocusWash()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = bucket.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = bucket.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(50))
                .background(swatch)
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(50),
                ),
        )
    }
}

@Composable
internal fun AddMoreCategoriesRow(
    extraOn: Int,
    customCount: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val subtitle = when {
        extraOn == 0 && customCount == 0 -> "Documentary, Drama, Comedy, Reality, + 3 more, plus custom."
        else -> "$extraOn extra on · $customCount custom"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusWash()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Add More Categories",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccentPickerDialog(
    current: String,
    preset: Color,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    var input by remember { mutableStateOf(current.uppercase()) }
    val sanitized = input.trim().removePrefix("#").uppercase()
    val isValid = sanitized.length == 6 && sanitized.all { it in HEX_CHARS_ACCENT }
    val preview = if (isValid) parseHex(sanitized) else preset

    com.aeriotv.android.ui.scale.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            SettingsDialogTextButton(
                label = "Save",
                onClick = { if (isValid) onSave(sanitized) },
                enabled = isValid,
            )
        },
        dismissButton = {
            Row {
                SettingsDialogTextButton(label = "Reset", onClick = onReset)
                SettingsDialogTextButton(label = "Cancel", onClick = onDismiss)
            }
        },
        title = { Text("Custom Accent") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(preview)
                            .border(
                                1.5.dp,
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp),
                            ),
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = if (isValid) "Preview $sanitized" else "Enter 6-char hex",
                        style = MaterialTheme.typography.bodySmall.subtext(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                SettingsTextField(
                    label = "Hex Color",
                    value = input,
                    onValueChange = { raw ->
                        input = raw.removePrefix("#").uppercase().filter { it in HEX_CHARS_ACCENT }.take(6)
                    },
                    placeholder = "1AC4D8",
                    keyboardOptions = com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val HEX_CHARS_ACCENT: Set<Char> = (('0'..'9') + ('A'..'F')).toSet()
