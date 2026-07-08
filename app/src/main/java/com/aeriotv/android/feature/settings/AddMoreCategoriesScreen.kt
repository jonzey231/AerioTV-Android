package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.adaptive.adaptiveFormWidth

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.category.CustomCategoryEntry
import com.aeriotv.android.core.category.ProgramCategory
import com.aeriotv.android.core.category.parseHex
import java.util.UUID

/**
 * Sub-screen reached from Appearance > Add more categories. Two sections:
 *
 *   1. Additional buckets (Documentary, Drama, Comedy, Reality, Educational,
 *      Sci-Fi / Fantasy, Music): toggle on/off + select to pick a hex.
 *   2. Custom categories: user-defined `match` substring + hex, persisted as
 *      a JSON array.
 *
 * Custom entries take precedence over built-in buckets at resolve time so a
 * user who adds e.g. "Horror" sees horror programmes coloured even though
 * Horror isn't a built-in bucket. Mirrors iOS Settings > Guide Display > Add
 * More Categories.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMoreCategoriesScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val palette by viewModel.categoryPalette.collectAsStateWithLifecycle(initialValue = CategoryPaletteState.Default)

    var pickerTarget by remember { mutableStateOf<ProgramCategory?>(null) }
    var addCustomOpen by remember { mutableStateOf(false) }
    var editingCustom by remember { mutableStateOf<CustomCategoryEntry?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Add More Categories",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                // No back arrow on Android TV -- the remote BACK pops it.
                if (!rememberIsTvDevice()) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
        LazyColumn(
            modifier = Modifier.adaptiveFormWidth(),
            // 104dp bottom clears the MainScaffold NavigationBar so the
            // custom-category section + Save row at the bottom stay
            // reachable.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = "Extra buckets",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Toggle a bucket on to colour matching programmes. Select the swatch to override its hex.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(ProgramCategory.additionalBuckets, key = { it.storageSuffix }) { bucket ->
                AdditionalBucketRow(
                    bucket = bucket,
                    enabled = palette.isBucketEnabled(bucket),
                    hex = palette.hexFor(bucket),
                    onToggle = { viewModel.setCategoryBucketEnabled(bucket, it) },
                    onPick = { pickerTarget = bucket },
                )
            }

            item { Spacer(Modifier.height(12.dp)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Custom",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Match an XMLTV category substring and pick its colour. Useful for genres outside the built-in buckets (e.g. Horror, Anime).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { addCustomOpen = true },
                        modifier = Modifier.dpadFocusRing(
                            CircleShape,
                            washTint = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add custom category",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (palette.custom.isEmpty()) {
                item {
                    Text(
                        text = "No custom categories yet. Select + above to add one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(palette.custom, key = { it.id }) { entry ->
                    CustomEntryRow(
                        entry = entry,
                        onEdit = { editingCustom = entry },
                        onDelete = {
                            viewModel.setCustomCategories(palette.custom.filterNot { it.id == entry.id })
                        },
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

    if (addCustomOpen) {
        CustomEntryDialog(
            initial = null,
            onDismiss = { addCustomOpen = false },
            onSave = { match, hex ->
                val updated = palette.custom + CustomCategoryEntry(
                    id = UUID.randomUUID().toString(),
                    match = match,
                    hex = hex,
                )
                viewModel.setCustomCategories(updated)
                addCustomOpen = false
            },
        )
    }
    editingCustom?.let { existing ->
        CustomEntryDialog(
            initial = existing,
            onDismiss = { editingCustom = null },
            onSave = { match, hex ->
                val updated = palette.custom.map {
                    if (it.id == existing.id) it.copy(match = match, hex = hex) else it
                }
                viewModel.setCustomCategories(updated)
                editingCustom = null
            },
        )
    }
}

@Composable
private fun AdditionalBucketRow(
    bucket: ProgramCategory,
    enabled: Boolean,
    hex: String,
    onToggle: (Boolean) -> Unit,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .dpadFocusWash()
            .clickable { onToggle(!enabled) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = bucket.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = bucket.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(50))
                .background(parseHex(hex))
                .border(
                    1.5.dp,
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(50),
                )
                .dpadFocusRing(RoundedCornerShape(50))
                .clickable(onClick = onPick),
        )
        Spacer(Modifier.width(10.dp))
        OnOffIndicator(on = enabled)
    }
}

@Composable
private fun CustomEntryRow(
    entry: CustomCategoryEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .dpadFocusWash()
            .clickable(onClick = onEdit)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(50))
                .background(parseHex(entry.hex))
                .border(
                    1.5.dp,
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(50),
                ),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.match.ifBlank { "(unnamed)" },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "#${entry.hex.uppercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.dpadFocusRing(
                CircleShape,
                washTint = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CustomEntryDialog(
    initial: CustomCategoryEntry?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var match by remember { mutableStateOf(initial?.match.orEmpty()) }
    var hex by remember { mutableStateOf((initial?.hex ?: "888888").uppercase()) }

    val sanitizedHex = hex.trim().removePrefix("#").uppercase()
    val hexValid = sanitizedHex.length == 6
    val canSave = match.trim().isNotEmpty() && hexValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Custom Category" else "Edit Custom Category") },
        text = {
            Column {
                OutlinedTextField(
                    value = match,
                    onValueChange = { match = it },
                    label = { Text("Match (substring, case-insensitive)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = aerioTextFieldKeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (hexValid) parseHex(sanitizedHex) else androidx.compose.ui.graphics.Color.Gray)
                            .border(
                                1.5.dp,
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp),
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = {
                            hex = it.removePrefix("#").uppercase().filter { c -> c in HEX_CHARS_FOR_CUSTOM }.take(6)
                        },
                        label = { Text("Hex color") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = aerioTextFieldKeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            SettingsDialogTextButton(
                label = "Save",
                onClick = { if (canSave) onSave(match.trim(), sanitizedHex) },
                enabled = canSave,
            )
        },
        dismissButton = {
            SettingsDialogTextButton(label = "Cancel", onClick = onDismiss)
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val HEX_CHARS_FOR_CUSTOM: Set<Char> = (('0'..'9') + ('A'..'F')).toSet()
