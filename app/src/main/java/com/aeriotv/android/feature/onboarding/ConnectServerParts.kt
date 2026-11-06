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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.automirrored.outlined.Login
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.feature.onboarding.components.OnboardingCard
import com.aeriotv.android.feature.onboarding.components.OnboardingSectionLabel
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.settings.dpadFocusRing

/** Per-feature glyphs, the Material counterparts of Apple's SF symbols
 *  (AddServerView.dispatcharrFeatures: clock.arrow.circlepath,
 *  arrow.triangle.swap, record.circle, person.2.fill, qrcode,
 *  arrow.triangle.2.circlepath). */
private val DISPATCHARR_FEATURES = listOf(
    Icons.Outlined.History to "Catch-up from the server",
    Icons.Outlined.SwapVert to "Stream switching and reordering",
    Icons.Outlined.RadioButtonChecked to "Server-side DVR with series rules",
    Icons.Outlined.People to "Multiple user profiles",
    Icons.Outlined.QrCode2 to "Set up any device with a QR code",
    Icons.Outlined.Sync to "Syncs across platforms",
)

/**
 * Dispatcharr hero card on Connect a Server: centered title, six feature
 * tiles (2 across), the "You will need" line, then the
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
    // TV metrics are Apple TV's AddServerView at half scale (1920 pt canvas
    // to the 960 dp canvas): 18 dp radius, 16 dp card padding, 10 dp between
    // blocks, 16 sp bold title, 11 sp subtitle (Streamer 2026-11-06, Logan:
    // the card had to match the Apple TV one).
    val shape = RoundedCornerShape(if (isTv) 18.dp else 16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f), shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.40f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Dispatcharr Direct Connect",
                style = if (isTv) MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, lineHeight = 20.sp) else MaterialTheme.typography.titleLarge,
                fontWeight = if (isTv) FontWeight.Bold else FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Your own server, and everything AerioTV can do.",
                style = (if (isTv) MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp, lineHeight = 14.sp) else MaterialTheme.typography.bodyMedium).subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        // Two tiles across on every device: the TV card is a 480 dp column
        // now (Apple e5cb037), three tiles wrapped their labels there.
        val perRow = 2
        val tileGap = if (isTv) 7.dp else 6.dp
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(tileGap)) {
            DISPATCHARR_FEATURES.chunked(perRow).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(tileGap),
                ) {
                    row.forEach { (icon, text) -> FeatureTile(icon, text, Modifier.weight(1f).fillMaxHeight(), isTv) }
                }
            }
        }
        val filled = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
        if (isTv) {
            // "YOU WILL NEED" line, Apple TV copy verbatim (AddServerView).
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("YOU WILL NEED ") }
                    append(
                        "server address, plus a username and password (with API key access on) or an API key. " +
                            "Have the QR code from the Users page? Scan it with AerioTV on your phone and set up from there.",
                    )
                },
                // Apple TV 20 pt is 10 sp; 9 sp keeps Apple's two lines
                // (Roboto runs about 8 percent wider than SF).
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp, lineHeight = 12.sp).subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            // Sized to its label and centered, filled with the accent gradient,
            // no trailing arrow (Apple OnboardingPillButton: 24 pt semibold, 36 pt
            // side padding, 60 pt minimum height). Focus: the app's filled-pill
            // treatment, white ring plus a slight scale (dpadFocusRing onFilled).
            val gradient = Brush.linearGradient(
                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
            )
            Box(
                modifier = Modifier
                    .focusRequester(firstFocus)
                    .dpadFocusRing(RoundedCornerShape(50), onFilled = true)
                    .clip(RoundedCornerShape(50))
                    .background(gradient)
                    .clickable(onClick = onEnterDetails)
                    .heightIn(min = 30.dp)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Continue with Dispatcharr",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onScanQr, colors = filled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Scan QR code")
                }
                OutlinedButton(onClick = onEnterDetails, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Enter details")
                }
            }
        }
    }
}

/**
 * One feature row: the feature's own glyph in the accent, then the label.
 * TV: a plain top-aligned row (no fill), 10 sp medium label, the glyph in a
 * 13 dp slot (Apple TV 20 / 26 pt). Apple's 72 pt rows with 14 pt padding
 * would be 36 / 7 dp; 28 / 4 dp here because the Android TV page keeps the
 * tab bar above it (about 60 dp Apple TV does not spend) and has to fit the
 * 540 dp canvas without a scroll. Phone and tablet keep the quiet tinted tile.
 */
@Composable
private fun FeatureTile(icon: ImageVector, text: String, modifier: Modifier, isTv: Boolean) {
    Row(
        modifier = modifier
            .then(
                if (isTv) Modifier.heightIn(min = 28.dp).padding(horizontal = 7.dp, vertical = 4.dp)
                else Modifier.clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ),
        verticalAlignment = if (isTv) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isTv) 6.dp else 8.dp),
    ) {
        Box(Modifier.size(if (isTv) 13.dp else 16.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (isTv) 12.dp else 16.dp))
        }
        Text(
            text,
            style = if (isTv) MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 13.sp) else MaterialTheme.typography.bodySmall,
            fontWeight = if (isTv) FontWeight.Medium else null,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * "Other source types": an outlined pill that expands in place to
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
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (isTv) 2.dp else 4.dp)) {
            OutlinedButton(
                onClick = { onExpandedChange(true) },
                modifier = Modifier.heightIn(min = if (isTv) 30.dp else 44.dp).dpadFocusRing(RoundedCornerShape(50)),
                contentPadding = if (isTv) PaddingValues(horizontal = 18.dp, vertical = 6.dp) else ButtonDefaults.ContentPadding,
            ) {
                Text(
                    "Other source types",
                    style = if (isTv) MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp, lineHeight = 16.sp) else MaterialTheme.typography.titleSmall,
                    fontWeight = if (isTv) FontWeight.SemiBold else null,
                )
            }
            // Helper line under the pill, Apple TV copy (AddServerView otherSourceTypes).
            Text(
                if (isTv) "Xtream Codes login, or an M3U playlist with an XMLTV guide"
                else "Xtream Codes, or an M3U playlist with a guide",
                style = (if (isTv) MaterialTheme.typography.bodySmall.copy(fontSize = 9.5.sp, lineHeight = 12.5.sp) else MaterialTheme.typography.bodySmall).subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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
