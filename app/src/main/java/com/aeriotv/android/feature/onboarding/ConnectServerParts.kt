package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.feature.onboarding.components.OnboardingCard
import com.aeriotv.android.feature.onboarding.components.OnboardingSectionLabel
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.settings.dpadFocusRing

private val DISPATCHARR_FEATURES = listOf(
    "Catch-up from the server",
    "Stream switching and reordering",
    "Server-side DVR with series rules",
    "Multiple user profiles",
    "Set up any device with a QR code",
    "Syncs across platforms",
)

/**
 * Dispatcharr hero card on Connect a Server: centered title, six feature
 * tiles (3 across on TV, 2 on phone), the "You will need" line, then the
 * CTA (TV: Continue with Dispatcharr; phone: Scan QR code + Enter details).
 * Same quiet border as the other cards.
 */
@Composable
internal fun DispatcharrHeroCard(
    isTv: Boolean,
    onEnterDetails: () -> Unit,
    onScanQr: () -> Unit,
    firstFocus: FocusRequester,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f), shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.40f))
            .padding(horizontal = 16.dp, vertical = if (isTv) 12.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (isTv) 8.dp else 10.dp),
    ) {
        Box(
            Modifier.size(if (isTv) 36.dp else 44.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        Text("Dispatcharr Direct Connect", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Your own server, and everything AerioTV can do.",
            style = MaterialTheme.typography.bodyMedium.subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        val perRow = if (isTv) 3 else 2
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DISPATCHARR_FEATURES.chunked(perRow).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { FeatureTile(it, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
        YouWillNeed(isTv)
        val filled = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
        if (isTv) {
            Button(
                onClick = onEnterDetails,
                colors = filled,
                modifier = Modifier.focusRequester(firstFocus).heightIn(min = 44.dp)
                    .dpadFocusRing(RoundedCornerShape(50), onFilled = true),
            ) {
                Text("Continue with Dispatcharr", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onScanQr, colors = filled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Scan QR code")
                }
                OutlinedButton(onClick = onEnterDetails, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Enter details")
                }
            }
        }
    }
}

/** "You will need" as a short list instead of a run-on sentence (Logan, 2026-10-29). */
@Composable
private fun YouWillNeed(isTv: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val body = MaterialTheme.typography.bodySmall.subtext()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("You will need", style = body, fontWeight = FontWeight.SemiBold, color = muted)
        Bullet("A server address", body, muted)
        Bullet("A username and password (with API key access on), or an API key", body, muted)
        if (isTv) {
            Text(
                "Have the QR code from the Users page? Scan it with AerioTV on your phone and set up from there.",
                style = body, color = muted, modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            Text("or", style = body, fontWeight = FontWeight.SemiBold, color = muted)
            Bullet("A QR code from your Dispatcharr administrator", body, muted)
        }
    }
}

@Composable
private fun Bullet(text: String, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("\u2022", style = style, color = color)
        Text(text, style = style, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FeatureTile(text: String, modifier: Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground)
    }
}

/**
 * "Other source types": an outlined pill with a hint that expands in place to
 * the Xtream Codes and M3U cards plus Hide. On TV the expanded cards are
 * focusable and Hide is reachable (it sits on the header row above them).
 */
@Composable
internal fun OtherSourceTypes(
    isTv: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onChoose: (SourceType) -> Unit,
    firstCardFocus: FocusRequester,
) {
    if (!expanded) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = { onExpandedChange(true) },
                modifier = Modifier.heightIn(min = 44.dp).dpadFocusRing(RoundedCornerShape(50)),
            ) { Text("Other source types", style = MaterialTheme.typography.titleSmall) }
            Text(
                if (isTv) "Xtream Codes login, or an M3U playlist with an XMLTV guide"
                else "Xtream Codes, or an M3U playlist with a guide",
                style = MaterialTheme.typography.bodySmall.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OnboardingSectionLabel("Other source types", Modifier.weight(1f))
            TextButton(
                onClick = { onExpandedChange(false) },
                modifier = Modifier.dpadFocusRing(RoundedCornerShape(50)),
            ) { Text("Hide") }
        }
        val xtream: @Composable (Modifier) -> Unit = { m ->
            OnboardingCard(
                icon = Icons.Filled.Tv,
                title = "Xtream Codes",
                subtitle = if (isTv) "Live TV, guide, movies, and series. Needs server address, username, and password."
                else "Server address, username, and password",
                onClick = { onChoose(SourceType.XtreamCodes) },
                compact = isTv,
                chevron = !isTv,
                modifier = m.focusRequester(firstCardFocus),
            )
        }
        val m3u: @Composable (Modifier) -> Unit = { m ->
            OnboardingCard(
                icon = Icons.Filled.Description,
                title = "M3U Playlist",
                subtitle = if (isTv) "Live TV, with a guide from an XMLTV link. Needs a playlist URL."
                else "Playlist URL, and an XMLTV URL for the guide",
                onClick = { onChoose(SourceType.M3uUrl) },
                compact = isTv,
                chevron = !isTv,
                modifier = m,
            )
        }
        if (isTv) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                xtream(Modifier.weight(1f)); m3u(Modifier.weight(1f))
            }
        } else {
            xtream(Modifier); m3u(Modifier)
        }
    }
}
