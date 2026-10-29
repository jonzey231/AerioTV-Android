package com.aeriotv.android.feature.onboarding.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.settings.dpadFocusRing

/**
 * Shared pieces of the onboarding redesign (Welcome, Connect a Server, Add
 * Profiles): section labels, tappable cards with the D-pad focus ring, the
 * Skip pill, and step dots.
 */

/** Rounded card tap target: D-pad ring on TV, accent wash while pressed on touch. */
@Composable
fun Modifier.onboardingTappable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val accent = MaterialTheme.colorScheme.primary
    return this.clip(shape)
        .dpadFocusRing(shape, washTint = accent)
        .then(
            if (pressed) Modifier.background(accent.copy(alpha = 0.16f), shape).border(1.5.dp, accent, shape)
            else Modifier,
        )
        .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
}

/** Small uppercase section header ("START HERE", "KEEP YOUR DEVICES IN SYNC"). */
@Composable
fun OnboardingSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, bottom = 2.dp),
    )
}

/** One onboarding choice card: icon tile, title, a wrapped subtitle, chevron. */
@Composable
fun OnboardingCard(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
    chevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    SourceTypeCard(
        modifier = modifier.onboardingTappable(enabled = enabled, onClick = onClick),
        icon = icon,
        title = title,
        subtitle = subtitle,
        compact = compact,
        highlighted = highlighted,
        subtitleMaxLines = 4,
        trailing = trailing ?: if (chevron) ({
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }) else null,
    )
}

/** "Skip for now" outlined pill with its explanation line, directly under the sync rows. */
@Composable
fun SkipPill(line: String, onSkip: () -> Unit, modifier: Modifier = Modifier, centered: Boolean = true) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.heightIn(min = 44.dp).dpadFocusRing(RoundedCornerShape(50)),
        ) { Text("Skip for now", style = MaterialTheme.typography.titleSmall) }
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall.subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
    }
}

/** Step dots, [current] is 1-based. */
@Composable
fun StepDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..total) {
            Box(
                Modifier
                    .size(if (i == current) 9.dp else 7.dp)
                    .clip(CircleShape)
                    .background(
                        if (i == current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    ),
            )
        }
    }
}

/** "Step 1 of 3" caption over a screen title. */
@Composable
fun StepCaption(current: Int, total: Int) {
    Text(
        "Step $current of $total",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

/** TV-only Back hint: a chevron glyph plus a line. */
@Composable
fun TvBackHint(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("‹", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall.subtext(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One-shot request that the next Dispatcharr form opens the QR scanner right away. */
object QrAutoScan {
    @Volatile private var pending = false
    fun request() { pending = true }
    fun consume(): Boolean = pending.also { pending = false }
}
