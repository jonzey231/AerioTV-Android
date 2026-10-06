package com.aeriotv.android.feature.multiview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import com.aeriotv.android.ui.settings.dpadFocusRing

/**
 * The "N tiles staged for Multiview" banner with Clear, Play and Done
 * (iOS MultiviewStagingBanner, EPGGuideView.swift). Shared by the Live TV
 * Guide and List so staging from either view shows the same banner at the
 * top of the screen. Renders nothing while staging is off or the pile is
 * empty.
 *
 * On TV, focus is pulled to Play after every add (the D-pad cannot reliably
 * climb from the grid to the top of the screen), guarded so the OK release
 * that confirmed the menu action cannot launch Multiview by itself.
 */
@Composable
fun MultiviewStagingBanner(
    store: MultiviewStoreHandle,
    isTv: Boolean,
    onLaunchMultiview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val staging by store.isStaging.collectAsStateWithLifecycle()
    val tiles by store.selected.collectAsStateWithLifecycle()
    if (!staging || tiles.isEmpty()) return
    val count = tiles.size
    val label = if (count == 1) "1 tile staged for Multiview" else "$count tiles staged for Multiview"
    val guard = rememberTvMenuGuard()
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(count) {
        if (isTv) {
            guard.arm()
            repeat(10) {
                if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(16L)
            }
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val pill = RoundedCornerShape(50)
    val buttonPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
            .padding(horizontal = if (isTv) 24.dp else 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.GridView,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        // The label is the only flexible child, so the buttons never wrap
        // ("Pla / y" on narrow phones, Logan 2026-10-06). When the full label
        // overflows it falls back to the short "N staged" form (iOS
        // ViewThatFits), then ellipsizes.
        var useShort by remember(count) { mutableStateOf(false) }
        Text(
            text = if (useShort) "$count staged" else label,
            onTextLayout = { if (!useShort && it.hasVisualOverflow) useShort = true },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = guard.wrap {
                store.clear()
                store.setStaging(false)
            },
            contentPadding = buttonPadding,
            modifier = Modifier.dpadFocusRing(shape = pill, washTint = accent),
        ) {
            Text("Clear", maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(
            onClick = guard.wrap {
                guard.arm()
                store.setStaging(false)
                onLaunchMultiview()
            },
            shape = pill,
            contentPadding = buttonPadding,
            colors = ButtonDefaults.buttonColors(containerColor = accent),
            modifier = Modifier.focusRequester(playFocus).dpadFocusRing(shape = pill, washTint = accent),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("Play", maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = guard.wrap { store.setStaging(false) },
            shape = pill,
            contentPadding = buttonPadding,
            border = BorderStroke(1.dp, accent),
            modifier = Modifier.dpadFocusRing(shape = pill, washTint = accent),
        ) {
            Text("Done", maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold, color = accent)
        }
    }
}
