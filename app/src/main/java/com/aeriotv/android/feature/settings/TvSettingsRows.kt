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
import android.content.res.Configuration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
 * Toggle row with the tvOS `• On` / `• Off` indicator (accent dot + text),
 * not a Switch -- this is exactly what the tvOS Settings show
 * (`TVSettingsToggleRow`). Selecting the row flips the value. Optional
 * leading icon (Debug Logging bug, Padding-Between-Tiles, etc.).
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
        OnOffIndicator(on = checked && enabled)
    }
}

/** The tvOS "• On" / "• Off" toggle indicator: an accent dot + label. */
@Composable
fun OnOffIndicator(on: Boolean) {
    val onColor = MaterialTheme.colorScheme.primary
    val offColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(if (on) onColor else offColor.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (on) "On" else "Off",
            style = MaterialTheme.typography.bodyLarge,
            color = if (on) onColor else offColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Action row (View Log File, Share, Clear, ...): leading icon + accent label
 * (red when [destructive]) + optional subtitle, on the shared focus card.
 * tvOS `TVSettingsActionRow`.
 *
 * Async actions (Sync Now, Clear Drive Data) pass [running] for a trailing
 * spinner and [statusLine] for an inline result under the label (primary
 * tint, or error tint when [statusIsError]). While [running] the click is
 * swallowed rather than the row disabled: a disabled clickable drops out of
 * D-pad focus traversal entirely (same guard as PlaylistDetail's ActionRow).
 */
@Composable
fun SettingsActionRow(
    label: String,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    running: Boolean = false,
    statusLine: String? = null,
    statusIsError: Boolean = false,
) {
    val accent = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    SettingsRowContainer(
        onClick = { if (!running) onClick() },
        modifier = modifier,
        enabled = enabled,
    ) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = accent,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (statusLine != null) {
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (running) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

/**
 * Read-only info row: label (left) + value (right), on the resting card (not
 * focusable). For "Log File Size", About facts, etc.
 */
@Composable
fun SettingsInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .settingsRowCard(focused = false)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
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

/** True on Android TV / leanback boxes (drives the no-back-button behaviour). */
@Composable
@ReadOnlyComposable
fun rememberIsTvDevice(): Boolean {
    val uiMode = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK
    return uiMode == Configuration.UI_MODE_TYPE_TELEVISION
}

/**
 * Settings sub-screen top bar. On Android TV the back arrow is omitted -- the
 * remote's BACK button already pops the screen, so an on-screen affordance is
 * redundant clutter (user request). Phones/tablets keep the arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDetailTopBar(title: String, onBack: () -> Unit) {
    val isTv = rememberIsTvDevice()
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        navigationIcon = {
            if (!isTv) {
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
}

/**
 * Header-corner text action ("Save" on Edit Playlist, "Edit" on Playlist
 * Detail) for the CenterAlignedTopAppBar `actions` slot.
 *
 * On TV a bare TextButton is invisible to D-pad focus (no chrome) and the
 * appbar parks it at the raw screen edge, outside the 48dp overscan margin.
 * This gives it the guide-pill treatment (white 2dp focus ring + tinted
 * fill) and insets it to the title-safe area. Phones keep the plain
 * iOS-style text action.
 */
@Composable
fun SettingsHeaderTextButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    if (rememberIsTvDevice()) {
        var focused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                // The appbar's own end inset is ~12dp; +36dp lands the pill
                // at the 48dp overscan margin.
                .padding(end = 36.dp)
                .heightIn(min = 36.dp)
                .onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(50))
                .background(
                    if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                )
                .border(
                    width = 2.dp,
                    color = if (focused) androidx.compose.ui.graphics.Color.White
                    else androidx.compose.ui.graphics.Color.Transparent,
                    shape = RoundedCornerShape(50),
                )
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                fontWeight = FontWeight.Medium,
            )
        }
    } else {
        androidx.compose.material3.TextButton(onClick = onClick, enabled = enabled) {
            Text(label, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Self-contained D-pad focus wash for an interactive row that lives INSIDE a
 * shared grouped card (Theme presets, palette rows, profile pickers...).
 * Tracks its own focus state; paints a tinted fill only while focused, so the
 * resting look on every form factor is unchanged. Place AFTER any clip and
 * BEFORE clickable/padding.
 */
@Composable
fun Modifier.dpadFocusWash(tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .background(
            if (focused) tint.copy(alpha = 0.16f)
            else androidx.compose.ui.graphics.Color.Transparent,
        )
}

/**
 * Self-contained D-pad focus ring (the app-wide white 2dp convention) plus a
 * subtle tinted wash, for pills / segments / swatches / cards that keep their
 * own selection styling. The border is transparent (not absent) at rest so
 * the element's measured size never changes on focus. Place AFTER
 * clip/background, BEFORE clickable.
 */
@Composable
fun Modifier.dpadFocusRing(
    shape: androidx.compose.ui.graphics.Shape,
    washTint: androidx.compose.ui.graphics.Color? = null,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (washTint != null) {
                Modifier.background(
                    if (focused) washTint.copy(alpha = 0.16f)
                    else androidx.compose.ui.graphics.Color.Transparent,
                    shape,
                )
            } else {
                Modifier
            },
        )
        .border(
            width = 2.dp,
            color = if (focused) androidx.compose.ui.graphics.Color.White
            else androidx.compose.ui.graphics.Color.Transparent,
            shape = shape,
        )
}

/**
 * Drop-in replacement for the bare TextButton in AlertDialog
 * confirmButton/dismissButton slots. Material's TextButton focus state is a
 * faint overlay, invisible at couch distance; this keeps the text-button look
 * at rest and adds the white 2dp focus ring + tinted fill under D-pad focus.
 * Safe on phones (the chrome only appears with focus, which touch never has).
 */
@Composable
fun SettingsDialogTextButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(50))
            .background(
                if (focused) accent.copy(alpha = 0.18f)
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .border(
                width = 2.dp,
                color = if (focused) androidx.compose.ui.graphics.Color.White
                else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(50),
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) accent
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            fontWeight = FontWeight.Medium,
        )
    }
}
