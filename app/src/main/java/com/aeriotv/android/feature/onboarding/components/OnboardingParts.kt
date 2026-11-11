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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.sp
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
    subtitleStyle: androidx.compose.ui.text.TextStyle? = null,
) {
    SourceTypeCard(
        modifier = modifier.onboardingTappable(enabled = enabled, onClick = onClick),
        icon = icon,
        title = title,
        subtitle = subtitle,
        compact = compact,
        highlighted = highlighted,
        subtitleMaxLines = 4,
        subtitleStyle = subtitleStyle,
        trailing = trailing ?: if (chevron) ({
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }) else null,
    )
}

/**
 * The onboarding capsule pill, filled (primary) or outlined, sized by the
 * caller. Matches tvOS SyncStepPrimaryButton / SyncStepSecondaryButton under
 * TVNoHighlightButtonStyle, measured from the Apple TV frame (3840x2160):
 * the focus ring sits flush on the pill edge (strokeBorder, no gap), 3 pt
 * wide on the filled pill (white) and 2.5 pt on the outlined one (accent);
 * at rest the outlined pill has a 1.5 pt accent-at-10% hairline; both labels
 * are white. [ringWidth] is the 3 pt ring in this screen's dp; the outlined
 * ring and hairline derive from it. Material's 48 dp minimum touch size is
 * turned off here: on a 1080p TV a 66 point pill is 33 dp, and the padding
 * Material added put the ring about 15 px outside the pill.
 */
@Composable
fun OnboardingPill(
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ringWidth: androidx.compose.ui.unit.Dp = 2.dp,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val colors = MaterialTheme.colorScheme
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
    ) {
        if (filled) {
            androidx.compose.material3.Button(
                onClick = onClick,
                modifier = modifier.dpadFocusRing(shape, onFilled = true, ringWidth = ringWidth, scaleOnFocus = false),
                shape = shape,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                ),
                content = content,
            )
        } else {
            OutlinedButton(
                onClick = onClick,
                modifier = modifier.dpadFocusRing(shape, ringWidth = ringWidth * (2.5f / 3f)),
                shape = shape,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                border = androidx.compose.foundation.BorderStroke(ringWidth * 0.5f, colors.primary.copy(alpha = 0.10f)),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = androidx.compose.ui.graphics.Color.White,
                ),
                content = content,
            )
        }
    }
}

/**
 * TV only: Apple TV's full-width OnboardingPillButton (60 pt tall, 24 pt
 * semibold label, 3 pt focus ring) drawn with [OnboardingPill] at half
 * scale for the 960 x 540 dp canvas: 30 dp, 12 sp, 1.5 dp. The phone
 * Material buttons are 54 dp, which a 1080p TV draws 108 px tall.
 */
@Composable
fun TvOnboardingPillButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    OnboardingPill(
        filled = filled,
        onClick = onClick,
        ringWidth = 1.5.dp,
        modifier = modifier.fillMaxWidth().height(30.dp),
    ) {
        if (busy) {
            androidx.compose.material3.CircularProgressIndicator(
                Modifier.size(15.dp),
                color = androidx.compose.ui.graphics.Color.White,
                strokeWidth = 1.5.dp,
            )
        } else {
            Text(
                label,
                fontSize = 12.sp,
                letterSpacing = 0.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
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

/**
 * One-shot handoff from a Welcome Drive restore into the configure route's
 * "Turn on Dispatcharr Sync?" step. [answered] holds playlists whose step was
 * shown this session so the providers offer leaves Dispatcharr out for them.
 */
object RestoreSyncStep {
    @Volatile private var pending: String? = null
    private val answered = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    fun request(playlistId: String) { pending = playlistId; answered.add(playlistId) }
    fun consume(): String? = pending.also { pending = null }
    fun wasShown(playlistId: String): Boolean = playlistId in answered
}
