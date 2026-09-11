package com.aeriotv.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeriotv.android.core.remote.RemoteHint

/**
 * The remote hint strip (Logan's design, approved 2026-09-11): ONE line of
 * "key  action" pairs separated by a middle dot, no pill backgrounds, 9 sp,
 * key names in the theme accent and actions in the same accent muted. Only the
 * pairs that apply right now are passed in; an empty list draws nothing.
 *
 * Replaces the old stack of capsule hint chips, which sat in the tab bar's
 * own band and collided with the nav circles. Rendered as a SINGLE Text so a
 * strip that outgrows its width truncates with an ellipsis instead of
 * shifting off center. TV-only, and never focusable: plain Text, so D-pad
 * traversal cannot land on it.
 */
@Composable
fun TvRemoteHintStrip(
    hints: List<RemoteHint>,
    modifier: Modifier = Modifier,
    // tvOS theme coloring (Logan 2026-09-11): key names in the theme accent,
    // actions in the same accent muted, separators fainter still, so changing
    // the app theme recolors every strip. Same on all three surfaces.
    keyColor: Color = MaterialTheme.colorScheme.primary,
    actionColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
    separatorColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
) {
    if (hints.isEmpty()) return
    val text = buildAnnotatedString {
        hints.forEachIndexed { index, hint ->
            if (index > 0) {
                withStyle(SpanStyle(color = separatorColor)) { append("  ·  ") }
            }
            withStyle(SpanStyle(color = keyColor, fontWeight = FontWeight.Medium)) {
                append(hint.key)
            }
            withStyle(SpanStyle(color = actionColor)) { append("  " + hint.action) }
        }
    }
    Text(
        text = text,
        modifier = modifier,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}

/** The strip's own line height, for callers that need to reserve a band. */
val remoteHintStripHeight = 11.dp
