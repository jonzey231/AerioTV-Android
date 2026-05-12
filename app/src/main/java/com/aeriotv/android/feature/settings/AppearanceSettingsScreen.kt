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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.category.ProgramCategory
import com.aeriotv.android.core.category.parseHex
import com.aeriotv.android.ui.adaptive.rememberViewport
import com.aeriotv.android.ui.theme.AppTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn

/**
 * Appearance sub-screen. Mirrors iOS Settings -> Appearance (project_aeriotv_ios_canon.md).
 * Phase 8a delivered the 6-theme picker; Phase 15 adds the category palette
 * section (4 default buckets + "Add more categories" + Reset to defaults) and
 * the master Enable Category Colors toggle.
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

    var pickerTarget by remember { mutableStateOf<ProgramCategory?>(null) }
    var accentPickerOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Appearance", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        val vp = rememberViewport()
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
        LazyColumn(
            modifier = if (vp.formMaxWidth != androidx.compose.ui.unit.Dp.Unspecified)
                Modifier.widthIn(max = vp.formMaxWidth)
            else
                Modifier,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionHeader("Theme", "Choose the colour palette used across the app. Changes apply immediately and persist across launches.")
            }
            items(AppTheme.entries, key = { it.name }) { theme ->
                ThemeRow(
                    theme = theme,
                    selected = theme == currentTheme,
                    onClick = { viewModel.setSelectedTheme(theme) },
                )
            }

            item {
                CustomAccentRow(
                    enabled = useCustomAccent,
                    hex = customAccentHex,
                    onToggle = viewModel::setUseCustomAccent,
                    onPick = { accentPickerOpen = true },
                )
            }

            item { Spacer(Modifier.height(8.dp)) }
            item {
                SectionHeader(
                    "Display Scale",
                    "Independent scale for Movies & Series and Live TV List. 100% matches the default; 85–125% lets you trade density for readability.",
                )
            }
            item {
                ScaleSliderRow(
                    label = "Movies & Series",
                    value = scaleMovies,
                    onValueChange = viewModel::setDisplayScaleMovies,
                )
            }
            item {
                ScaleSliderRow(
                    label = "Live TV List",
                    value = scaleLiveTV,
                    onValueChange = viewModel::setDisplayScaleLiveTV,
                )
            }

            item { Spacer(Modifier.height(8.dp)) }
            item {
                SectionHeader(
                    "Palette",
                    "Tint EPG cells and channel cards by programme category. Long-press a colour to override its hex.",
                )
            }
            item {
                PaletteToggleRow(
                    enabled = palette.masterEnabled,
                    onToggle = viewModel::setCategoryColorsEnabled,
                )
            }
            items(ProgramCategory.defaultBuckets, key = { it.storageSuffix }) { bucket ->
                Box(modifier = Modifier.alpha(if (palette.masterEnabled) 1f else 0.4f)) {
                    CategoryPaletteRow(
                        bucket = bucket,
                        hex = palette.hexFor(bucket),
                        onClick = { if (palette.masterEnabled) pickerTarget = bucket },
                    )
                }
            }
            item {
                Box(modifier = Modifier.alpha(if (palette.masterEnabled) 1f else 0.4f)) {
                    AddMoreCategoriesRow(
                        extraOn = ProgramCategory.additionalBuckets.count { palette.isBucketEnabled(it) },
                        customCount = palette.custom.size,
                        onClick = { if (palette.masterEnabled) onOpenAddMoreCategories() },
                    )
                }
            }
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (palette.masterEnabled) 1f else 0.4f),
                ) {
                    TextButton(onClick = { if (palette.masterEnabled) viewModel.resetCategoryPalette() }) {
                        Text(
                            text = "Reset Colors to Defaults",
                            color = androidx.compose.ui.graphics.Color(0xFFFB8C00),
                        )
                    }
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

@Composable
private fun SectionHeader(label: String, body: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PaletteToggleRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Enable Category Colors",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Tint EPG cells and channel cards by programme category.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun CategoryPaletteRow(
    bucket: ProgramCategory,
    hex: String,
    onClick: () -> Unit,
) {
    val swatch = parseHex(hex)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
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
    onClick: () -> Unit,
) {
    val subtitle = when {
        extraOn == 0 && customCount == 0 -> "Documentary, Drama, Comedy, Reality, + 3 more, plus custom."
        else -> "$extraOn extra on · $customCount custom"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Add more categories",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "▸",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
            .clip(RoundedCornerShape(12.dp))
            .background(theme.cardBackground)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) theme.accentPrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(theme.appBackground),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
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
                fontWeight = FontWeight.SemiBold,
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
                tint = theme.accentPrimary,
            )
        }
    }
}

@Composable
private fun ScaleSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.85f..1.25f,
            steps = 7, // 0.85, 0.90, 0.95, 1.00, 1.05, 1.10, 1.15, 1.20, 1.25
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
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
    val swatch = if (enabled && hex.length == 6) com.aeriotv.android.core.category.parseHex(hex)
    else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Custom Accent Color",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (enabled && hex.isNotBlank()) "Override active: #$hex"
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
                    .clickable(onClick = onPick),
            )
            Spacer(Modifier.size(8.dp))
        }
        androidx.compose.material3.Switch(checked = enabled, onCheckedChange = { isOn ->
            onToggle(isOn)
            if (isOn && hex.isBlank()) onPick()
        })
    }
}

@Composable
private fun AccentPickerDialog(
    current: String,
    preset: androidx.compose.ui.graphics.Color,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    var input by remember { mutableStateOf(current.uppercase()) }
    val sanitized = input.trim().removePrefix("#").uppercase()
    val isValid = sanitized.length == 6 && sanitized.all { it in HEX_CHARS_ACCENT }
    val preview = if (isValid) com.aeriotv.android.core.category.parseHex(sanitized) else preset

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (isValid) onSave(sanitized) },
                enabled = isValid,
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = onReset) { Text("Reset") }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
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
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
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
