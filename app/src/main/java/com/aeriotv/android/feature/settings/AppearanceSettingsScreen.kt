package com.aeriotv.android.feature.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.aeriotv.android.ui.theme.AppTheme

/**
 * Appearance sub-screen. Mirrors iOS Settings -> Appearance
 * (project_aeriotv_ios_canon.md "Appearance" section).
 *
 * Theme card -> 6 brand presets + Custom Accent override + a live Preview
 * tile so the user can see how their accent reads on a card without leaving
 * Settings. Display Scale card -> independent Movies & Series / Live TV
 * sliders (85-125%). Category Colors card -> master toggle. Palette card ->
 * default buckets + Add More Categories + Reset.
 *
 * Theme propagation: changes from `viewModel.setSelectedTheme` /
 * `setUseCustomAccent` / `setCustomAccentHex` flow through DataStore into
 * MainActivity's collectAsState bindings, which rebuild AerioTVTheme's
 * Material3 colorScheme. Every surface that reads MaterialTheme.colorScheme
 * (top bars, nav bar, sheets, dialogs, mini-player, splash) re-themes in
 * the same frame — no recreate needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    onOpenAddMoreCategories: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val currentTheme by viewModel.selectedTheme.collectAsStateWithLifecycle(initialValue = AppTheme.Aerio)
    val palette by viewModel.categoryPalette.collectAsStateWithLifecycle(initialValue = CategoryPaletteState.Default)
    val scaleMovies by viewModel.displayScaleMovies.collectAsStateWithLifecycle(initialValue = 1.0f)
    val scaleLiveTV by viewModel.displayScaleLiveTV.collectAsStateWithLifecycle(initialValue = 1.0f)
    val useCustomAccent by viewModel.useCustomAccent.collectAsStateWithLifecycle(initialValue = false)
    val customAccentHex by viewModel.customAccentHex.collectAsStateWithLifecycle(initialValue = "")
    val showChannelLogos by viewModel.showChannelLogos.collectAsStateWithLifecycle(initialValue = true)

    var pickerTarget by remember { mutableStateOf<ProgramCategory?>(null) }
    var accentPickerOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "Appearance", onBack = onBack)

        val vp = rememberViewport()
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = if (vp.formMaxWidth != Dp.Unspecified)
                    Modifier.widthIn(max = vp.formMaxWidth)
                else
                    Modifier,
                // Bottom padding covers the bottom-nav bar (~80 dp) plus
                // 24 dp breathing room, so the last LazyColumn item ("Reset
                // Colors to Defaults" under the Palette card) isn't clipped
                // behind MainScaffold's NavigationBar. Without this, the
                // user can't scroll past the nav bar to reach the Reset row
                // or the Add More Categories navigator — the LazyColumn
                // hits its content edge first.
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 104.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // THEME card — six brand presets + Custom Accent override row.
                settingsCard(
                    header = "Theme",
                    footer = "Choose the palette used across the app. Switching themes applies live; the preset accent kicks in unless Custom Accent is on.",
                ) {
                    AppTheme.entries.forEachIndexed { index, theme ->
                        if (index > 0) DividerRow()
                        ThemeRow(
                            theme = theme,
                            selected = theme == currentTheme,
                            onClick = { viewModel.setSelectedTheme(theme) },
                        )
                    }
                    DividerRow()
                    CustomAccentRow(
                        enabled = useCustomAccent,
                        hex = customAccentHex,
                        onToggle = viewModel::setUseCustomAccent,
                        onPick = { accentPickerOpen = true },
                    )
                }

                // PREVIEW card — shows how the active theme reads on a card,
                // including the accent-tinted title and Now-Playing pill.
                item {
                    PreviewCard(
                        theme = currentTheme,
                        customAccentHex = customAccentHex.takeIf { useCustomAccent },
                    )
                }

                // DISPLAY SCALE card — independent sliders for the two
                // density-tunable surfaces (Movies & Series, Live TV List).
                settingsCard(
                    header = "Display Scale",
                    footer = "Independent scale for Movies & Series and Live TV List. 100% matches the default; 85-125% lets you trade density for readability. Changes apply live.",
                ) {
                    ScaleSliderRow(
                        label = "Movies & Series",
                        value = scaleMovies,
                        onValueChange = viewModel::setDisplayScaleMovies,
                    )
                    DividerRow()
                    ScaleSliderRow(
                        label = "Live TV List",
                        value = scaleLiveTV,
                        onValueChange = viewModel::setDisplayScaleLiveTV,
                    )
                }

                // CATEGORY COLORS card — master toggle that gates the
                // palette card below. iOS keeps these as separate sections
                // for the same reason: the master toggle is binary and gets
                // its own footer, the palette grid is browse-and-tweak.
                settingsCard(
                    header = "Category Colors",
                    footer = "Tint EPG cells and channel cards by programme category. Select a category below to override its hex.",
                ) {
                    ToggleRow(
                        title = "Color Programs by Category",
                        subtitle = "Apply category tints to the guide and channel rows.",
                        checked = palette.masterEnabled,
                        onCheckedChange = viewModel::setCategoryColorsEnabled,
                    )
                }

                // Channel List card (iOS Issue #28). Hide logos so long
                // channel names get the full row width.
                settingsCard(
                    header = "Channel List",
                    footer = "Turn logos off to give long channel names the full row width.",
                ) {
                    ToggleRow(
                        title = "Show Channel Logos",
                        subtitle = "Display each channel's logo in the Live TV list.",
                        checked = showChannelLogos,
                        onCheckedChange = viewModel::setShowChannelLogos,
                    )
                }

                // PALETTE card — the default 4 buckets plus the "Add more
                // categories" navigator and the Reset link.
                settingsCard(
                    header = "Palette",
                    footer = null,
                ) {
                    ProgramCategory.defaultBuckets.forEachIndexed { idx, bucket ->
                        if (idx > 0) DividerRow()
                        Box(modifier = Modifier.alpha(if (palette.masterEnabled) 1f else 0.4f)) {
                            CategoryPaletteRow(
                                bucket = bucket,
                                hex = palette.hexFor(bucket),
                                enabled = palette.masterEnabled,
                                onClick = { pickerTarget = bucket },
                            )
                        }
                    }
                    DividerRow()
                    Box(modifier = Modifier.alpha(if (palette.masterEnabled) 1f else 0.4f)) {
                        AddMoreCategoriesRow(
                            extraOn = ProgramCategory.additionalBuckets.count { palette.isBucketEnabled(it) },
                            customCount = palette.custom.size,
                            enabled = palette.masterEnabled,
                            onClick = onOpenAddMoreCategories,
                        )
                    }
                    DividerRow()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .dpadFocusWash()
                            .clickable(enabled = palette.masterEnabled) { viewModel.resetCategoryPalette() }
                            .alpha(if (palette.masterEnabled) 1f else 0.4f)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Reset Colors to Defaults",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFB8C00),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }

    pickerTarget?.let { bucket ->
        HexPickerDialog(
            bucket = bucket,
            currentHex = palette.hexFor(bucket),
            onDismiss = { pickerTarget = null },
            onSave = { hex ->
                viewModel.setCategoryBucketHex(bucket, hex)
                pickerTarget = null
            },
            onReset = {
                viewModel.setCategoryBucketHex(bucket, null)
                pickerTarget = null
            },
        )
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
private fun LazyListScope.settingsCard(
    header: String,
    footer: String?,
    content: @Composable () -> Unit,
) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = header.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        RoundedCornerShape(12.dp),
                    ),
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
}

@Composable
private fun DividerRow() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f),
        modifier = Modifier.padding(start = 16.dp),
    )
}

@Composable
private fun ThemeRow(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusWash()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(theme.appBackground)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(50))
                    .background(theme.accentPrimary),
            )
        }
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
                style = MaterialTheme.typography.bodySmall,
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
    AppTheme.Monochrome -> "Greyscale on near-black"
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Custom Accent Color",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (enabled && hex.isNotBlank()) "Override active: #${hex.uppercase()}"
                else "Override the preset accent with your own hex.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        OnOffIndicator(on = enabled)
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
            color = MaterialTheme.colorScheme.primary,
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

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
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
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        OnOffIndicator(on = checked)
    }
}

/** tvOS Display Scale segments (s_05/s_06): 85 / 92 / 100 / 114 / 125 %. */
private val SCALE_SEGMENTS: List<Pair<Float, String>> = listOf(
    0.85f to "85%", 0.92f to "92%", 1.00f to "100%", 1.14f to "114%", 1.25f to "125%",
)

@Composable
private fun ScaleSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    // tvOS renders Display Scale as inline percentage segments, not a slider
    // (cleaner with a remote + no focus-trap). The selected segment is filled.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        SCALE_SEGMENTS.forEach { (segValue, segLabel) ->
            val selected = kotlin.math.abs(value - segValue) < 0.03f
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
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
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun CategoryPaletteRow(
    bucket: ProgramCategory,
    hex: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val swatch = parseHex(hex)
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
private fun AddMoreCategoriesRow(
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
                style = MaterialTheme.typography.bodySmall,
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

    androidx.compose.material3.AlertDialog(
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = input,
                    onValueChange = { raw ->
                        input = raw.removePrefix("#").uppercase().filter { it in HEX_CHARS_ACCENT }.take(6)
                    },
                    label = { Text("Hex color (e.g. 1AC4D8)") },
                    singleLine = true,
                    keyboardOptions = com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val HEX_CHARS_ACCENT: Set<Char> = (('0'..'9') + ('A'..'F')).toSet()
