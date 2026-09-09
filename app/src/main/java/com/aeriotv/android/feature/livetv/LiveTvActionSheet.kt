package com.aeriotv.android.feature.livetv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.tv.TvMenuAction

/**
 * Long-press action surface for a guide cell or channel row on phone and tablet: the
 * Material 3 modal bottom sheet, a title block and one icon row per
 * action. Tapping a row runs it and closes the sheet.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LiveTvActionSheet(
    title: String,
    subtitle: String,
    actions: List<TvMenuAction>,
    onDismiss: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            actions.forEach { action ->
                androidx.compose.material3.ListItem(
                    headlineContent = {
                        Text(
                            action.label,
                            color = when {
                                !action.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                action.destructive -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    leadingContent = {
                        action.icon?.let {
                            Icon(
                                it, contentDescription = null,
                                tint = when {
                                    !action.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    action.destructive -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable(enabled = action.enabled) { action.onClick(); onDismiss() },
                )
            }
        }
    }
}
