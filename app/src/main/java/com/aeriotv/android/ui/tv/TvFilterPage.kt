package com.aeriotv.android.ui.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.EaseInOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Android TV Filter page, ported 1:1 from the tvOS MoviesFilterPage
 * (Features/VOD/MoviesView.swift). tvOS lays out on a 1080-point canvas and a
 * 1080p Android TV is ~540 dp tall, so every tvOS point below is halved.
 *
 * Used by Movies, TV Shows (group names) and DVR (channel names). The tvOS
 * page also carries a Providers tab for Dispatcharr Direct Connect; Android
 * has no disabled-providers preference today, so that tab is not ported.
 *
 * Every toggle calls [onChange] immediately: there is no Done or Save step.
 */
@Composable
fun TvFilterPage(
    groups: List<String>,
    hiddenGroups: Set<String>,
    onChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // We paint our own dim (tvOS Color.black.opacity(0.45)); kill the
        // platform dialog dim so the two do not stack.
        val view = LocalView.current
        LaunchedEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        }
        val colors = MaterialTheme.colorScheme
        val firstRow = remember { FocusRequester() }
        val showAll = hiddenGroups.isNotEmpty()
        val rowCount = groups.size + if (showAll) 1 else 0
        val listHeight = minOf(360.dp.value, rowCount * 39f + 12f).dp

        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = TvChrome.dialogSurface(),
                modifier = Modifier
                    .width(235.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Filter",
                        fontSize = 15.5.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(listHeight),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 3.dp,
                            vertical = 6.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (showAll) {
                            item(key = "__show_all__") {
                                FilterRow(
                                    title = "Show All Groups",
                                    on = false,
                                    accent = true,
                                    modifier = Modifier.focusRequester(firstRow),
                                    onClick = { onChange(emptySet()) },
                                )
                            }
                        }
                        items(groups, key = { it }) { name ->
                            val on = name !in hiddenGroups
                            FilterRow(
                                title = name,
                                on = on,
                                modifier = if (!showAll && groups.firstOrNull() == name) {
                                    Modifier.focusRequester(firstRow)
                                } else {
                                    Modifier
                                },
                                onClick = {
                                    onChange(
                                        if (on) hiddenGroups + name else hiddenGroups - name,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        // The first row is composed by LazyColumn during layout, so a
        // single-frame requestFocus can miss; retry for a few frames.
        LaunchedEffect(Unit) {
            repeat(10) {
                if (runCatching { firstRow.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(32)
            }
        }
    }
}

/** tvOS NativeSheetRowStyle: translucent capsule at rest, white when focused. */
@Composable
private fun FilterRow(
    title: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.03f else 1f,
        animationSpec = tween(durationMillis = 150, easing = EaseInOut),
        label = "tvFilterRowScale",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(33.dp)
            .scale(scale)
            .alpha(if (pressed) 0.8f else 1f)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.10f))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (on && !accent) "$title  ✓" else title,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = colors.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
