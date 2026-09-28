// SettingsSlider.kt
//
// Settings redesign Phase 3, item 4: the ONE slider used by every Settings
// page (Skip Back / Skip Forward, buffer depth, DVR Maximum, Text Size,
// Subtext Size, Text Contrast).
//
// Material's own slider draws a thick track with a tick dot under every stop,
// so a 14-stop Text Size row read as a row of beads and each screen's slider
// looked slightly different from the next. Apple's is a plain continuous line:
// a thin track, the accent on the left of the thumb, a muted rail on the right,
// a round thumb, and no tick marks at all.
//
// The VALUES stay stepped -- the callers still snap to their own ladders, and
// the D-pad still walks one stop per LEFT / RIGHT -- only the ticks are gone.

package com.aeriotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import com.aeriotv.android.ui.tv.dpadFocusEscape

/** Track thickness. Apple's slider rail, not Material's expressive bar. */
private val SettingsSliderTrackHeight = 4.dp

/** Drawn diameter of the thumb. */
private val SettingsSliderThumbSize = 20.dp

/**
 * The shared Settings slider: thin continuous track, accent to the left of the
 * thumb, muted rail to the right, round thumb, NO tick marks.
 *
 * [steps] still snaps the value; it just no longer paints a dot per stop.
 * [Modifier.dpadFocusEscape] is applied here rather than at the call sites so
 * every Settings slider keeps the TV behavior the #90 report asked for: UP and
 * DOWN move focus off the slider, LEFT and RIGHT adjust it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    val accent = MaterialTheme.colorScheme.primary
    val rail = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier.dpadFocusEscape(),
        // Belt and braces with the custom track below: anything Material still
        // draws for ticks resolves to fully transparent.
        colors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = rail,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
            disabledActiveTickColor = Color.Transparent,
            disabledInactiveTickColor = Color.Transparent,
        ),
        thumb = {
            Box(
                modifier = Modifier
                    .size(SettingsSliderThumbSize)
                    .clip(CircleShape)
                    .background(if (enabled) accent else rail),
            )
        },
        track = { state -> SettingsSliderTrack(state = state, accent = accent, rail = rail) },
    )
}

/**
 * The continuous two-tone rail. Drawn rather than composed so the thickness is
 * exactly [SettingsSliderTrackHeight] regardless of what Material's own track
 * metrics happen to be in the current library version.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSliderTrack(state: SliderState, accent: Color, rail: Color) {
    val span = (state.valueRange.endInclusive - state.valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingsSliderTrackHeight)
            .drawBehind {
                val radius = CornerRadius(size.height / 2f, size.height / 2f)
                drawRoundRect(color = rail, cornerRadius = radius)
                val active = size.width * fraction
                if (active > 0f) {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset.Zero,
                        size = Size(active, size.height),
                        cornerRadius = radius,
                    )
                }
            },
    )
}

/**
 * A full Settings slider ROW: label on the left, the current value in the
 * accent on the right, the shared slider beneath. Every stepped setting in
 * Settings is one of these, so the label and value typography can only be set
 * in one place.
 *
 * The caller owns its own ladder and hands over the index; [valueText] is
 * whatever the stop should read as ("30s", "16 GB", "125%").
 */
@Composable
fun SettingsSliderRow(
    label: String,
    valueText: String,
    index: Int,
    lastIndex: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // Apple's iOS slider rows print the value small and in the tertiary
    // tint (labelSmall, textTertiary). Opt-in so other pages keep the
    // accent readout until they are matched.
    dimValue: Boolean = false,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = settingsRowTitleStyle(),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (dimValue) {
                Text(
                    text = valueText,
                    style = settingsFootnoteStyle().subtext(),
                    color = settingsDimTint(),
                )
            } else {
                Text(
                    text = valueText,
                    style = settingsRowValueStyle(),
                    color = MaterialTheme.colorScheme.textAccent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        SettingsSlider(
            value = index.toFloat(),
            onValueChange = { raw ->
                val next = kotlin.math.round(raw).toInt().coerceIn(0, lastIndex)
                if (next != index) onIndexChange(next)
            },
            valueRange = 0f..lastIndex.toFloat().coerceAtLeast(1f),
            steps = (lastIndex - 1).coerceAtLeast(0),
        )
    }
}
