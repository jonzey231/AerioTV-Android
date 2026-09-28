// SettingsPicker.kt
//
// Settings redesign Phase 3, items 3 and 4: the ONE picker and the ONE
// collapsed sub-toggle group used by every Settings page.
//
// Item 3, pickers. Before this, a pick-one choice was drawn three different
// ways depending on which screen it landed on: a flat list of rows inline in
// the page (Default Tab, Buffer Size), a DropdownMenu anchored to a value row
// (the DVR buffers) and a full TvActionMenuDialog (Group Selection on TV).
// Now there is one behavior per input:
//   - TOUCH: the row shows its current value at the right and pushes a
//     single-choice page (radio list with a check).
//   - TV: the choices render inline with a check, exactly as today's Guide
//     Layout rows do. A remote should never pay a BACK press for a two- or
//     three-option choice.
//
// Item 4, sub-toggle groups. A master row carries a summary subtitle ("All 5",
// "3 of 5", "Logo, name, time") and owns the detail toggles; on touch it
// pushes them onto a page, on TV they stay inline under it.

package com.aeriotv.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.aeriotv.android.ui.theme.textAccent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One choice in a [SettingsPickerRow]. */
data class SettingsPickerOption<T>(
    val value: T,
    val label: String,
    val subtitle: String? = null,
    /** Leading glyph on the choice row, as Apple's SettingsChoice(icon:). */
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
)

/**
 * A pick-one setting. See the file header for the per-input behavior.
 *
 * [title] is both the row label and the pushed page's title, so it reads as a
 * noun phrase ("Default Tab", "Buffer Size"), not a sentence.
 *
 * [pageKey] only has to be unique within the screen; it defaults to the title,
 * which already is.
 */
@Composable
fun <T> ColumnScope.SettingsPickerRow(
    title: String,
    options: List<SettingsPickerOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    pageKey: String = title,
    /**
     * Whether the INLINE (TV) rendering prints [title] above its options.
     *
     * Off where the section header already names the choice, which is the
     * common case and what keeps the Guide Layout rows exactly as they are.
     * ON wherever a section holds more than one picker, or mixes a picker with
     * toggles: two unlabeled option lists stacked in one section (the DVR
     * Start Early / End Late pair) are indistinguishable on a TV.
     */
    inlineTitle: Boolean = false,
    /**
     * Caption under the choices, on the pushed page and inline alike. Apple's
     * SettingsChoicePicker carries its footer with the choices, so the
     * explanation sits beside the options it explains; callers must not repeat
     * it in their section footer.
     */
    footer: String? = null,
    /** Glyph on the collapsed (touch) row, as Apple's SettingsChoicePicker(icon:). */
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val push = settingsPushesSubPages()
    val host = rememberSubPageRegistration(pageKey) {
        val controller = LocalSettingsSubPageHost.current
        options.forEach { option ->
            SettingsSelectionRow(
                label = option.label,
                subtitle = option.subtitle,
                leadingIcon = option.icon,
                selected = option.value == selected,
                onClick = {
                    onSelect(option.value)
                    // Single-choice pages pop on pick, as Apple's do; the
                    // check is visible for the moment the pop takes.
                    controller?.close()
                },
            )
        }
        if (footer != null) SettingsSectionFooter(footer)
    }
    if (push && host != null) {
        SettingsSubPageRow(
            title = title,
            subtitle = subtitle,
            value = options.firstOrNull { it.value == selected }?.label,
            onOpen = { host.open(pageKey, title) },
            modifier = modifier,
            leadingIcon = leadingIcon,
        )
    } else {
        if (inlineTitle) {
            Text(
                text = title,
                style = settingsRowTitleStyle(),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp, top = 4.dp),
            )
        }
        options.forEach { option ->
            SettingsSelectionRow(
                label = option.label,
                subtitle = option.subtitle,
                leadingIcon = option.icon,
                selected = option.value == selected,
                onClick = { onSelect(option.value) },
                modifier = modifier,
            )
        }
        if (footer != null) SettingsSectionFooter(footer)
    }
}

/**
 * A master row that owns a set of sub-toggles.
 *
 * [summary] is the whole point of the row: it has to say what the group is set
 * to without opening it ("All 5", "3 of 5", "Logo, name, time"). Build it with
 * [settingsCountSummary] for plain on-of-n groups.
 */
@Composable
fun ColumnScope.SettingsSubGroup(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    pageKey: String = title,
    /** Glyph on the master row, as Apple's SettingsSubgroup(icon:). */
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /**
     * Footer under the pushed page only (Apple's SettingsSubgroup footer).
     * On TV the rows render inline inside the caller's section, whose own
     * footer already carries the explanation, so it is not repeated there.
     */
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val push = settingsPushesSubPages()
    val host = rememberSubPageRegistration(pageKey) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
            if (footer != null) SettingsSectionFooter(footer)
        }
    }
    if (push && host != null) {
        SettingsSubPageRow(
            title = title,
            subtitle = summary,
            onOpen = { host.open(pageKey, title) },
            modifier = modifier,
            leadingIcon = leadingIcon,
        )
    } else {
        content()
    }
}

/** "All 5" when everything is on, "3 of 5" otherwise, "None" at zero. */
fun settingsCountSummary(on: Int, total: Int): String = when (on) {
    total -> "All $total"
    0 -> "None"
    else -> "$on of $total"
}

/**
 * Comma list of the first few enabled items, falling back to [settingsCountSummary]
 * once the list would be longer than it is useful ("Logo, name, time" versus
 * "All 6"). Labels arrive already lowercased where the copy wants that.
 */
fun settingsItemsSummary(enabled: List<String>, total: Int, maxItems: Int = 3): String = when {
    enabled.size == total -> "All $total"
    enabled.isEmpty() -> "None"
    enabled.size <= maxItems -> enabled.joinToString(", ")
    else -> settingsCountSummary(enabled.size, total)
}

/**
 * TV stepper row: label left, current value on the right of the label, and
 * D-pad LEFT / RIGHT walking [options]. One focusable row instead of six
 * selection rows, which is what a six-option choice is worth on a remote
 * (Logan on the Streamer; Apple's Settings does the same).
 *
 * A stored value that is not in [options] (an older custom setting) is shown
 * as-is and kept until the user steps away from it: stepping then moves to the
 * nearest option in that direction.
 *
 * Touch never sees this - the callers keep the pushed picker page there.
 */
@Composable
fun SettingsIntStepperRow(
    title: String,
    options: List<Int>,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    format: (Int) -> String = { it.toString() },
) {
    val sorted = remember(options) { options.sorted() }
    fun step(delta: Int) {
        if (sorted.isEmpty()) return
        val index = sorted.indexOf(value)
        val next = when {
            index >= 0 -> sorted[(index + delta).coerceIn(0, sorted.lastIndex)]
            // Custom value: the nearest option in the direction of travel.
            delta > 0 -> sorted.firstOrNull { it > value } ?: sorted.last()
            else -> sorted.lastOrNull { it < value } ?: sorted.first()
        }
        if (next != value) onValueChange(next)
    }
    SettingsRowContainer(
        // OK on the row steps forward and wraps, so the control is usable
        // from a remote that only clicks.
        onClick = { step(if (value == sorted.lastOrNull()) -sorted.lastIndex else 1) },
        modifier = modifier.onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { step(-1); true }
                Key.DirectionRight -> { step(1); true }
                else -> false
            }
        },
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = settingsRowTitleStyle(),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = settingsFootnoteStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = format(value),
            style = settingsRowValueStyle(),
            color = MaterialTheme.colorScheme.textAccent,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
