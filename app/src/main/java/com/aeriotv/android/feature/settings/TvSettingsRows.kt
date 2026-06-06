package com.aeriotv.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.ui.tv.tvFocusScale

/**
 * Shared tvOS-style Settings building blocks (mirrors the `TVSettings*` row
 * components in `Aerio/Features/Settings/SettingsView.swift`).
 *
 * The tvOS Settings design is **one rounded card per row** (not a single
 * grouped card with dividers, which is the iOS-phone style the Android
 * sub-screens previously copied). Each row sits on `tvSettingsCardBG`:
 *   - at rest: a soft card fill + a faint accent hairline border
 *   - on D-pad focus: an accent-tinted fill (0.18), a bright accent border
 *     (0.65, 2dp), and a 1.02 scale (via [tvFocusScale])
 *
 * Selections show an accent checkmark (no RadioButton); toggles keep a Switch
 * (idiomatic on Android) but ride the same focus card. Sections are introduced
 * by an uppercase accent [SettingsSectionHeader].
 *
 * Used by every Settings sub-screen so the look is uniform and matches tvOS.
 */

/** Uppercase accent section header (tvOS `tvSettingsHeader`). */
@Composable
fun SettingsSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 6.dp, top = 4.dp, bottom = 2.dp),
    )
}

/** Footer caption under a section (tvOS settings footnote). */
@Composable
fun SettingsSectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp),
    )
}

/**
 * The teal-focus card chrome shared by every settings row. Apply BEFORE the
 * content padding. `focused` flips the fill/border/scale; it's only ever true
 * under D-pad focus, so phones (which never focus these) just render the calm
 * resting card.
 */
@Composable
fun Modifier.settingsRowCard(focused: Boolean): Modifier {
    val primary = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    return this
        .tvFocusScale(focused, focusedScale = 1.02f)
        .clip(RoundedCornerShape(12.dp))
        .background(if (focused) primary.copy(alpha = 0.18f) else rest)
        .border(
            width = if (focused) 2.dp else 1.dp,
            color = primary.copy(alpha = if (focused) 0.65f else 0.10f),
            shape = RoundedCornerShape(12.dp),
        )
}

/**
 * Generic focusable settings row container: tracks focus, paints the card,
 * runs [onClick]. Children supply the inner content (already padded by the
 * standard 16/12 inset). Use the typed helpers below for the common shapes.
 */
@Composable
fun SettingsRowContainer(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: RowScopeContent,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .onFocusChanged { focused = it.isFocused }
            .settingsRowCard(focused)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = { content() },
    )
}

/** Trailing-lambda content shape for [SettingsRowContainer]. */
typealias RowScopeContent = @Composable androidx.compose.foundation.layout.RowScope.() -> Unit

/**
 * Selection row (pick-one lists: Default Tab, Color Theme, buffer size, EPG
 * window, ...). Accent checkmark on the selected option; optional leading
 * icon + subtitle. tvOS `TVSettingsSelectionRow`.
 */
@Composable
fun SettingsSelectionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
) {
    SettingsRowContainer(onClick = onClick, modifier = modifier) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Toggle row (Switch) on the shared focus card. tvOS uses an On/Off pill, but
 * an Android Switch reads more naturally here; the row still rides the teal
 * focus chrome so it matches the rest of the page on a remote.
 */
@Composable
fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    SettingsRowContainer(
        onClick = { if (enabled) onCheckedChange(!checked) },
        modifier = modifier,
        enabled = enabled,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/**
 * A section: uppercase header, a column of per-row cards (spaced, NOT grouped
 * into one card), and an optional footer. Drop the row helpers above inside.
 */
@Composable
fun SettingsSection(
    header: String,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: ColumnScopeContent,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeader(header)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        if (footer != null) SettingsSectionFooter(footer)
    }
}

/** Trailing-lambda content shape for [SettingsSection]. */
typealias ColumnScopeContent = @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
