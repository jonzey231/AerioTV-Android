package com.aeriotv.android.ui.settings

import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared tvOS-style Settings building blocks (mirrors the `TVSettings*` row
 * components in `Aerio/Features/Settings/SettingsView.swift`).
 *
 * The tvOS Settings design is **one rounded card per row** (not a single
 * grouped card with dividers, which is the iOS-phone style the Android
 * sub-screens previously copied). Each row sits on `tvSettingsCardBG`:
 *   - at rest: a soft card fill + a faint accent hairline border
 *   - on D-pad focus: an accent-tinted fill (0.18), and a bright accent border
 *     (0.65, 2dp). No scale bump (Phase 3)
 *
 * Selections show an accent checkmark (no RadioButton); toggles keep a Switch
 * (idiomatic on Android) but ride the same focus card. Sections are introduced
 * by an uppercase accent [SettingsSectionHeader].
 *
 * Used by every Settings sub-screen so the look is uniform and matches tvOS.
 */

/**
 * The one focus-ring width in Settings, matching the guide grid's focused
 * cell (`GuideGrid`: 2dp, theme accent). Logan's standing rule: never white,
 * never oversized.
 */
val SettingsFocusRingWidth = 2.dp

/**
 * Phase 3b: the Apple grouped-card metrics. A Settings section is ONE rounded
 * card on the page background, rows stacked inside it with hairline dividers
 * inset to the text start. Nothing draws a per-row card or outline any more.
 */
object SettingsCardMetrics {
    /** Corner radius of the grouped section card. */
    val cardCorner = 16.dp
    /** Corner radius of the focus ring drawn on a focused row INSIDE the card. */
    val rowCorner = 10.dp
    /** Side gutter from the page edge to the card. */
    val gutter = 16.dp
    /** Vertical space between two sections (header to previous footer). */
    val sectionSpacing = 24.dp
    /** Divider inset for a plain row (text starts at the row inset). */
    val dividerInset = 16.dp
    /** Divider inset for a row with a leading icon tile (16 + tile + 12). */
    val iconRowDividerInset = 60.dp
    /**
     * Leading icon tile. Apple's SettingsIconTile is 32pt with a 14pt
     * semibold glyph and a 7pt corner on iPhone and iPad; our 40dp tile read
     * visibly larger beside it. Material glyphs carry ~2dp of built-in
     * padding, so an 18dp icon draws at about the SF Symbol's 14pt.
     */
    val iconTile = 32.dp
    val iconTileCorner = 7.dp
    val iconGlyph = 18.dp
}

/**
 * Apple's SettingsIconTile: a dim tinted rounded tile with the glyph centered
 * in it. Every touch Settings row that has an icon uses this, so a page never
 * mixes bare glyphs with tiles (Apple's Phase 3 item 5 rule).
 */
@Composable
fun SettingsIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .size(SettingsCardMetrics.iconTile)
            .clip(RoundedCornerShape(SettingsCardMetrics.iconTileCorner))
            .background(tint.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(SettingsCardMetrics.iconGlyph),
        )
    }
}

/** The grouped card fill: the theme surface, a shade lighter than the page. */
@Composable
fun settingsCardFill(): Color = MaterialTheme.colorScheme.surface

/** Hairline divider color between rows inside a grouped card. */
@Composable
fun settingsDividerColor(): Color =
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)

/**
 * THE hairline between two rows inside a grouped card, for rows that are laid
 * out by hand rather than through [settingsRowCard] (the Playlists and About
 * cards). One implementation, one color, always inset to the text start.
 */
@Composable
fun SettingsRowDivider(startInset: androidx.compose.ui.unit.Dp = SettingsCardMetrics.dividerInset) {
    androidx.compose.material3.HorizontalDivider(
        thickness = 1.dp,
        color = settingsDividerColor(),
        modifier = Modifier.padding(start = startInset),
    )
}

/** Dim tint used by footers, chevrons and secondary values. */
@Composable
fun settingsDimTint(): Color =
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)

/**
 * Uppercase, letter-spaced ACCENT section header above each card (Apple's
 * grouped-list header).
 */
@Composable
fun SettingsSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color? = null,
) {
    Text(
        text = text.uppercase(),
        style = settingsEyebrowStyle().copy(letterSpacing = 0.9.sp),
        color = color ?: MaterialTheme.colorScheme.textAccent,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
    )
}

/** Footer caption under a section card, in the dim tint. */
@Composable
fun SettingsSectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = settingsFootnoteStyle().subtext(),
        color = settingsDimTint(),
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
    )
}

/**
 * The grouped card container: one rounded, unoutlined surface holding a column
 * of rows. Drop row helpers straight inside; each row paints its own hairline
 * top divider (all but the first) and its own focus ring.
 */
@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: ColumnScopeContent) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingsCardMetrics.cardCorner))
            .background(settingsCardFill()),
        content = { content() },
    )
}

/**
 * Row chrome INSIDE a grouped card (Phase 3b). At rest a row draws nothing but
 * a hairline top divider, inset to the text start, which it suppresses when it
 * is the first row in the card (it checks its own offset, so no call site has
 * to count). Under D-pad focus the row background lifts slightly and takes the
 * app-wide 2dp accent ring. Never white, never a scale bump.
 */
@Composable
fun Modifier.settingsRowCard(
    focused: Boolean,
    dividerInset: androidx.compose.ui.unit.Dp = SettingsCardMetrics.dividerInset,
): Modifier {
    val primary = MaterialTheme.colorScheme.primary
    val divider = settingsDividerColor()
    var isFirst by remember { mutableStateOf(true) }
    var isLast by remember { mutableStateOf(true) }
    val insetPx = with(LocalDensity.current) { dividerInset.toPx() }
    val hairline = with(LocalDensity.current) { 1.dp.toPx() }
    // The focus fill and ring of the FIRST and LAST rows have to follow the
    // card's own radius, or the card's clip shaves their square corners off
    // (Logan on the Streamer). Interior rows keep the tighter row radius.
    val shape = RoundedCornerShape(
        topStart = if (isFirst) SettingsCardMetrics.cardCorner else SettingsCardMetrics.rowCorner,
        topEnd = if (isFirst) SettingsCardMetrics.cardCorner else SettingsCardMetrics.rowCorner,
        bottomStart = if (isLast) SettingsCardMetrics.cardCorner else SettingsCardMetrics.rowCorner,
        bottomEnd = if (isLast) SettingsCardMetrics.cardCorner else SettingsCardMetrics.rowCorner,
    )
    return this
        .onPlaced { coords ->
            val parent = coords.parentLayoutCoordinates
            val top = coords.positionInRoot().y - (parent?.positionInRoot()?.y ?: 0f)
            val first = top <= 0.5f
            val last = parent == null ||
                top + coords.size.height >= parent.size.height - 0.5f
            if (first != isFirst) isFirst = first
            if (last != isLast) isLast = last
        }
        .drawBehind {
            if (!isFirst && !focused) {
                drawLine(
                    color = divider,
                    start = Offset(insetPx, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = hairline,
                )
            }
        }
        .clip(shape)
        .background(if (focused) primary.copy(alpha = 0.16f) else Color.Transparent)
        .border(
            width = if (focused) SettingsFocusRingWidth else 0.dp,
            color = if (focused) primary else Color.Transparent,
            shape = shape,
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
                style = settingsRowTitleStyle(),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = settingsFootnoteStyle().subtext(),
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
    /**
     * Touch only: draw [leadingIcon] in Apple's filled SettingsIconTile
     * (rows Apple builds on SettingsRow). TV keeps the bare tvOS glyph.
     */
    tiledIcon: Boolean = false,
) {
    val tiled = tiledIcon && !rememberIsTvDevice()
    SettingsRowContainer(
        onClick = { if (enabled) onCheckedChange(!checked) },
        modifier = modifier,
        enabled = enabled,
    ) {
        if (leadingIcon != null && tiled) {
            SettingsIconTile(icon = leadingIcon)
            Spacer(Modifier.width(12.dp))
        } else if (leadingIcon != null) {
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
                style = settingsRowTitleStyle(),
                color = if (enabled) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = settingsFootnoteStyle().subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // Phase 3b: touch gets the Material switch in the accent (what Apple's
        // phone/tablet Settings show). TV keeps the tvOS "dot + On/Off" text:
        // a Switch has no D-pad affordance at 10 feet.
        SettingsToggleAffordance(
            checked = checked && enabled,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * The trailing control on a toggle row. Touch gets the Material switch in the
 * accent (what Apple's phone and tablet Settings show); TV keeps the tvOS
 * "dot + On/Off" text, which a Switch cannot replace at 10 feet.
 */
@Composable
fun SettingsToggleAffordance(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    if (rememberIsTvDevice()) {
        OnOffIndicator(on = checked)
    } else {
        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
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
            style = settingsRowTitleStyle(),
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
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        // Label text + its leading glyph: accent for TEXT (Text Contrast).
        else -> MaterialTheme.colorScheme.textAccent
    }
    SettingsRowContainer(
        // Plan B4: a truly disabled clickable drops out of D-pad traversal on
        // TV, stranding focus. Stay focusable and swallow the click, but mark
        // the row disabled for accessibility and dim its content.
        onClick = { if (!running && enabled) onClick() },
        modifier = modifier.semantics { if (!enabled) disabled() },
        enabled = true,
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
                style = settingsRowTitleStyle(),
                color = accent,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = settingsFootnoteStyle().subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (statusLine != null) {
                Text(
                    text = statusLine,
                    style = settingsFootnoteStyle(),
                    color = if (statusIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.textAccent,
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
            style = settingsRowTitleStyle().subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = settingsRowTitleStyle(),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * A section: uppercase accent header, ONE rounded card holding the rows with
 * hairline dividers between them, and an optional footer in the dim tint.
 * Drop the row helpers above inside.
 */
@Composable
fun SettingsSection(
    header: String,
    modifier: Modifier = Modifier,
    footer: String? = null,
    headerColor: androidx.compose.ui.graphics.Color? = null,
    content: ColumnScopeContent,
) {
    Column(modifier = modifier) {
        if (header.isNotBlank()) SettingsSectionHeader(header, color = headerColor)
        SettingsCard { content() }
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
    val showBack = settingsShowsBackArrow()
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
                style = settingsTitleStyle(),
                fontWeight = FontWeight.Bold,
            )
        },
        navigationIcon = {
            if (showBack) {
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
 * This gives it the guide-pill treatment (accent focus ring + tinted
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
                    width = SettingsFocusRingWidth,
                    color = if (focused) MaterialTheme.colorScheme.primary
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
                color = if (enabled) MaterialTheme.colorScheme.textAccent
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                fontWeight = FontWeight.Medium,
            )
        }
    } else {
        androidx.compose.material3.TextButton(onClick = onClick, enabled = enabled) {
            Text(label, color = MaterialTheme.colorScheme.textAccent)
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
 * Self-contained D-pad focus ring (the app-wide accent convention) plus a
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
            width = SettingsFocusRingWidth,
            color = if (focused) MaterialTheme.colorScheme.primary
            else androidx.compose.ui.graphics.Color.Transparent,
            shape = shape,
        )
}

/**
 * Drop-in replacement for the bare TextButton in AlertDialog
 * confirmButton/dismissButton slots. Material's TextButton focus state is a
 * faint overlay, invisible at couch distance; this keeps the text-button look
 * at rest and adds the accent focus ring + tinted fill under D-pad focus.
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
                width = SettingsFocusRingWidth,
                color = if (focused) MaterialTheme.colorScheme.primary
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

// MARK: - Pane mode

/**
 * True while a Settings surface is rendering inside a two-pane host's DETAIL
 * pane rather than as a full-screen page (plan B3/B4).
 *
 * The sidebar or rail stays on screen beside the pane, so a pane header is a
 * static label with nothing to go back to - the same situation Android TV has
 * always been in, where the remote's BACK does the popping. Screens read this
 * through [settingsShowsBackArrow] instead of testing the form factor
 * themselves.
 */
val LocalSettingsInPane = staticCompositionLocalOf { false }

/** Whether a Settings top bar should draw a back arrow at this position. */
@Composable
fun settingsShowsBackArrow(): Boolean = !rememberIsTvDevice() && !LocalSettingsInPane.current
