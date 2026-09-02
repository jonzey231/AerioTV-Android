package com.aeriotv.android.core.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * One row of a [TvActionMenuDialog]. [icon] is nullable because some menus
 * (the guide programme cell) are text-only; [destructive] swaps the icon and
 * label to the error color; [enabled] keeps the row visible but inert and
 * dimmed (e.g. a "Multiview full" state).
 */
data class TvMenuAction(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The house long-press action menu for Android TV: a centered Dialog with a
 * title header and focusable rows carrying the primary 0.18 wash on focus.
 * Anchored DropdownMenus float oddly mid-screen at 10 feet, so TV branches
 * render this instead (extracted from the DVR tab's RecordingActionMenu).
 *
 * Pair with [TvMenuGuard]: arm() at the long-press that opens the dialog;
 * row clicks are wrapped here so the spurious OK-release that follows a TV
 * long-press cannot auto-pick a row. Callers compose this only while open:
 *   if (menuOpen) TvActionMenuDialog(..., onDismiss = { menuOpen = false })
 *
 * D-pad activation is latched per row, not time-based: a row only fires on
 * an OK KeyUp whose KeyDown it also saw. The release of the long-press that
 * OPENED the dialog arrives as a bare KeyUp (its KeyDown went to the
 * launching row), so it is ignored no matter how long the button was held,
 * including releases slower than the guard's grace window.
 */
@Composable
fun TvActionMenuDialog(
    title: String,
    actions: List<TvMenuAction>,
    guard: TvMenuGuard,
    onDismiss: () -> Unit,
) {
    // Apple TV look (Logan 2026-09-02): a centred translucent card, the
    // title on top, one capsule per action, the focused capsule white with
    // accent text, and a Cancel capsule closing the list.
    val accent = MaterialTheme.colorScheme.primary
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.width(300.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                actions.forEach { action ->
                    TvMenuCapsule(
                        label = action.label,
                        enabled = action.enabled,
                        destructive = action.destructive,
                        accent = accent,
                        onActivate = guard.wrap { onDismiss(); action.onClick() },
                    )
                }
                TvMenuCapsule(
                    label = "Cancel",
                    enabled = true,
                    destructive = false,
                    accent = accent,
                    onActivate = guard.wrap { onDismiss() },
                )
            }
        }
    }
}

@Composable
private fun TvMenuCapsule(
    label: String,
    enabled: Boolean,
    destructive: Boolean,
    accent: Color,
    onActivate: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var okLatched by remember { mutableStateOf(false) }
    val textColor = when {
        !enabled -> accent.copy(alpha = 0.35f)
        destructive -> MaterialTheme.colorScheme.error
        else -> accent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) okLatched = false
            }
            .onPreviewKeyEvent { event ->
                val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (!isOk) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> { if (event.nativeKeyEvent.repeatCount == 0) okLatched = true; true }
                    KeyEventType.KeyUp -> {
                        val latched = okLatched
                        okLatched = false
                        if (latched && enabled) onActivate()
                        true
                    }
                    else -> false
                }
            }
            .background(
                color = if (focused) Color.White else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(24.dp),
            )
            .clickable(enabled = enabled, onClick = onActivate),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused && enabled) accent else textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
