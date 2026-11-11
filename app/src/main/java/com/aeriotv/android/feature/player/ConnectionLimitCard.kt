package com.aeriotv.android.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.playback.DispatcharrConnectionLimit

/**
 * Dispatcharr connection-limit notice (see [DispatcharrConnectionLimit]).
 * Same look as the player's Channel Unavailable / Catch-up Unavailable cards,
 * but deliberately static: the server refused on purpose, so there is no
 * countdown, no auto-retry and no Reconnecting line. Retry is one fresh
 * attempt. [compact] is the multiview tile size.
 */
@Composable
fun ConnectionLimitCard(
    notice: DispatcharrConnectionLimit.Notice,
    isTv: Boolean,
    onRetry: () -> Unit,
    compact: Boolean = false,
) {
    val retryFocus = remember { FocusRequester() }
    if (isTv && !compact) {
        LaunchedEffect(notice) {
            runCatching { retryFocus.requestFocus() }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (compact) 0.82f else 0.72f))
            .padding(if (compact) 12.dp else 32.dp),
        verticalArrangement = Arrangement.spacedBy(
            if (compact) 6.dp else 10.dp,
            Alignment.CenterVertically,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (notice.title.isNotBlank()) Text(
            text = notice.title,
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.headlineSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            text = notice.message,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            maxLines = if (compact) 3 else Int.MAX_VALUE,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.focusRequester(retryFocus),
        ) {
            Text("Retry")
        }
        if (!compact && notice.closeAnotherStreamHint) {
            Text(
                text = "Close another stream, then press Retry.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
