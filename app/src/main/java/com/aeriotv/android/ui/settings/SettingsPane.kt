// SettingsPane.kt
//
// Settings redesign Phase B1 (SettingsUIRedesign.md B4): the pane-aware
// content width cap that replaces `Modifier.adaptiveFormWidth()` on Settings
// surfaces.
//
// The difference that matters: `adaptiveFormWidth` measures the WINDOW, so a
// settings page hosted inside a 60%-width detail pane still sized itself
// against the whole tablet or TV screen and overflowed its column. This one
// uses BoxWithConstraints, so the cap is applied relative to whatever space
// the caller actually got, and it centers what is left over.
//
// On a phone (stacked, full-window) the behavior is identical to before: the
// screen is narrower than the cap, so the content simply fills it.

package com.aeriotv.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Readable content width for a Settings detail pane (plan B2/B4). */
val SettingsPaneMaxWidth: Dp = 640.dp

/**
 * Caps [content] at [maxWidth] measured against the PANE, not the window, and
 * centers it. Use for every Settings page body so the same page reads
 * correctly full-screen on a phone and inside a detail pane on tablet/TV.
 */
@Composable
fun SettingsPaneContent(
    modifier: Modifier = Modifier,
    maxWidth: Dp = SettingsPaneMaxWidth,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth(),
                verticalArrangement = verticalArrangement,
                horizontalAlignment = horizontalAlignment,
            ) {
                content()
            }
        }
    }
}

/**
 * Modifier form for callers that already own their scroll container (a
 * LazyColumn item, for instance) and only need the width cap.
 */
@Composable
fun Modifier.settingsPaneWidth(maxWidth: Dp = SettingsPaneMaxWidth): Modifier =
    this.widthIn(max = maxWidth).fillMaxWidth()
