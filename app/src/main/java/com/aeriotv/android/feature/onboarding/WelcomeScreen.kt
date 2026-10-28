package com.aeriotv.android.feature.onboarding

import com.aeriotv.android.ui.theme.decorSecondary
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.R
import com.aeriotv.android.feature.onboarding.components.SourceTypeCard

/**
 * Cold-start welcome surface. Mirrors iOS App Store screenshot IMG_1076: AerioTV
 * brand block + supported source types + Sync / Detect-Home-WiFi info cards +
 * "Connect a Server" CTA + "Skip for now" link.
 *
 * Layout adapts to viewport: a short-but-wide screen (Android TV at 960dp x
 * 540dp, tablets in landscape, foldables unfolded) switches to a two-column
 * presentation so the CTA stays above the fold and the user can reach it with
 * a D-pad without scrolling. Portrait phones get the original vertical stack.
 */
@Composable
fun WelcomeScreen(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    /**
     * Optional Sign-in-with-Google handler. When provided, a pill appears
     * between Connect-a-Server and Skip on the welcome screen so the user
     * can authenticate Drive Sync up front instead of being routed back to
     * Settings later. Setting this null hides the row entirely (e.g. on
     * builds that ship without a Google Cloud OAuth client ID baked in).
     */
    onSignInWithGoogle: (() -> Unit)? = null,
    googleSignInInProgress: Boolean = false,
    /** Signed-in Google account, shown on the sync card in place of the chevron. */
    googleSignedInEmail: String? = null,
    /** "Sync via Dispatcharr": opens the Direct Connect form, sync turns on after the connect. */
    onSyncViaDispatcharr: (() -> Unit)? = null,
    /** TV only: "Set up from your phone" (docs/DeviceSetupHandoff.md). Null hides it. */
    onSetUpFromPhone: (() -> Unit)? = null,
) {
    val config = LocalConfiguration.current
    val isTv = com.aeriotv.android.feature.livetv.rememberLiveTvFormFactor().isTv
    // Two-column when the viewport is meaningfully wider than tall (tablet
    // landscape, foldable unfolded). TV is excluded: it uses the centered
    // single column to mirror the tvOS welcome layout.
    val twoColumn = !isTv && config.screenWidthDp >= 720 && config.screenHeightDp < 720

    if (isTv) WelcomeTv(onConnectServer, onSkip, onSignInWithGoogle, googleSignInInProgress, googleSignedInEmail, onSyncViaDispatcharr, onSetUpFromPhone)
    else if (twoColumn) WelcomeTwoColumn(onConnectServer, onSkip, onSignInWithGoogle, googleSignInInProgress, googleSignedInEmail, onSyncViaDispatcharr)
    else WelcomeSingleColumn(onConnectServer, onSkip, onSignInWithGoogle, googleSignInInProgress, googleSignedInEmail, onSyncViaDispatcharr)
}

@Composable
private fun WelcomeSingleColumn(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)?,
    googleSignInInProgress: Boolean,
    googleSignedInEmail: String?,
    onSyncViaDispatcharr: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        WelcomeAmbientOrbs(modifier = Modifier.fillMaxSize())
        // Mirrors the tvOS WelcomeView: a single centered column, brand on top,
        // the three supported source types, ONE Sync card, a Connect-a-Server
        // row, then Skip. Sized to fit a 1080p Android TV (~540dp tall, half
        // tvOS's 1080pt logical height) without scrolling. Centered so it sits
        // balanced; verticalScroll is only a last-ditch fallback for an
        // unusually short viewport.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandBlock()
            Spacer(Modifier.height(22.dp))
            SupportedTypesGroup(alignStart = false)
            Spacer(Modifier.height(22.dp))
            SyncCard(inProgress = googleSignInInProgress, signedInEmail = googleSignedInEmail, onClick = onSignInWithGoogle)
            if (onSignInWithGoogle != null) Spacer(Modifier.height(12.dp))
            if (onSyncViaDispatcharr != null) {
                DispatcharrSyncCard(onClick = onSyncViaDispatcharr)
                Spacer(Modifier.height(12.dp))
            }
            ConnectServerRow(onClick = onConnectServer)
            Spacer(Modifier.height(4.dp))
            SkipRow(onSkip = onSkip)
        }
    }
}

/**
 * TV welcome (1920x1080 = 960dp x 540dp): everything fits without scrolling.
 * Brand block, the source types on one row, then the cards as a single
 * compact list with one-line subtitles, then Skip. The first card takes
 * focus on entry.
 */
@Composable
private fun WelcomeTv(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)?,
    googleSignInInProgress: Boolean,
    googleSignedInEmail: String?,
    onSyncViaDispatcharr: (() -> Unit)?,
    onSetUpFromPhone: (() -> Unit)?,
) {
    val firstFocus = remember { FocusRequester() }
    val cards = buildList<@Composable (Modifier) -> Unit> {
        if (onSetUpFromPhone != null) add { m ->
            TvCard(m, Icons.Filled.PhoneAndroid, "Set up from your phone",
                "Send your playlist, profiles, and sync from AerioTV on your phone.", onClick = onSetUpFromPhone)
        }
        if (onSignInWithGoogle != null) add { m ->
            TvCard(m, Icons.Filled.CloudOff, "Sync via Google Account",
                if (googleSignedInEmail != null) "Signed in as $googleSignedInEmail"
                else "Mirror playlists, progress, reminders, and preferences across devices.",
                enabled = !googleSignInInProgress, onClick = onSignInWithGoogle)
        }
        if (onSyncViaDispatcharr != null) add { m ->
            TvCard(m, Icons.Filled.Key, "Sync via Dispatcharr",
                "Keep favorites, progress, and preferences on your server.", onClick = onSyncViaDispatcharr)
        }
        add { m ->
            TvCard(m, Icons.Outlined.Hub, "Connect a Server",
                "Add a Dispatcharr, Xtream Codes, or M3U playlist.", onClick = onConnectServer)
        }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        WelcomeAmbientOrbs(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandBlock(compact = true)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                SupportedTypeInline(Icons.Filled.Key, "Dispatcharr Direct Connect")
                SupportedTypeInline(Icons.Filled.Tv, "Xtream Codes")
                SupportedTypeInline(Icons.Filled.Description, "M3U + EPG")
            }
            Spacer(Modifier.height(22.dp))
            // Single list (owner, 2026-10-27): the compact cards fit 1080p in
            // one column, and a 2x2 grid read badly.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                cards.forEachIndexed { i, card ->
                    val m = Modifier.fillMaxWidth()
                    card(if (i == 0) m.focusRequester(firstFocus) else m)
                }
            }
            Spacer(Modifier.height(8.dp))
            SkipRow(onSkip = onSkip)
        }
    }
}

@Composable
private fun TvCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SourceTypeCard(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
        icon = icon,
        title = title,
        subtitle = subtitle,
        compact = true,
    )
}

@Composable
private fun SupportedTypeInline(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GradientIcon(imageVector = icon, brush = accentBrush(), modifier = Modifier.size(18.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun WelcomeTwoColumn(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)?,
    googleSignInInProgress: Boolean,
    googleSignedInEmail: String?,
    onSyncViaDispatcharr: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        WelcomeAmbientOrbs(modifier = Modifier.fillMaxSize())
        WelcomeTwoColumnRow(onConnectServer, onSkip, onSignInWithGoogle, googleSignInInProgress, googleSignedInEmail, onSyncViaDispatcharr)
    }
}

@Composable
private fun WelcomeTwoColumnRow(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)?,
    googleSignInInProgress: Boolean,
    googleSignedInEmail: String?,
    onSyncViaDispatcharr: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        // Left column: brand + supported source types. Centered vertically.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            BrandBlock(alignStart = true)
            Spacer(Modifier.height(20.dp))
            SupportedTypesGroup(alignStart = true)
        }
        // Right column: info cards + CTAs. The Connect button sits in the
        // viewport center on TV so the focus ring lands on it at cold start,
        // which is what a remote-driven flow wants.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .widthIn(max = 540.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            SyncCard(inProgress = googleSignInInProgress, signedInEmail = googleSignedInEmail, onClick = onSignInWithGoogle)
            if (onSignInWithGoogle != null) Spacer(Modifier.height(12.dp))
            if (onSyncViaDispatcharr != null) {
                DispatcharrSyncCard(onClick = onSyncViaDispatcharr)
                Spacer(Modifier.height(12.dp))
            }
            ConnectServerRow(onClick = onConnectServer)
            Spacer(Modifier.height(4.dp))
            SkipRow(onSkip = onSkip)
        }
    }
}

@Composable
private fun BrandBlock(alignStart: Boolean = false, compact: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        BrandLogo(compact = compact)
        Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
        Text(
            text = "AerioTV",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Your IPTV & Media Hub",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.textAccent,
        )
        if (!compact) Spacer(Modifier.height(2.dp))
        if (!compact) Text(
            text = "Android TV · Phone · Tablet",
            style = MaterialTheme.typography.bodySmall.subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SupportedTypesGroup(alignStart: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SupportedTypeRow(icon = Icons.Filled.Key, label = "Dispatcharr Direct Connect", alignStart = alignStart)
        SupportedTypeRow(icon = Icons.Filled.Tv, label = "Xtream Codes", alignStart = alignStart)
        SupportedTypeRow(icon = Icons.Filled.Description, label = "M3U + EPG", alignStart = alignStart)
    }
}

/**
 * Sync opt-in card, the Drive analog of the tvOS WelcomeView iCloud card: one
 * card with an On/Off status pill. On the welcome screen sync is always Off
 * (signing in transitions onboarding to the restore-progress screen), so the
 * card is the tap target that launches Google sign-in. Hidden entirely when
 * the build ships without an OAuth client id (onClick null).
 */
@Composable
private fun SyncCard(inProgress: Boolean, signedInEmail: String?, onClick: (() -> Unit)?) {
    if (onClick == null) return
    SourceTypeCard(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !inProgress, onClick = onClick),
        icon = Icons.Filled.CloudOff,
        title = "Sync via Google Account",
        subtitle = if (signedInEmail != null) "Signed in as $signedInEmail"
        else "Sign in to mirror playlists, watch progress, reminders, and preferences across your devices.",
        trailing = {
            when {
                inProgress -> Text(
                    text = "...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                signedInEmail != null -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Signed in",
                    tint = MaterialTheme.colorScheme.primary,
                )
                else -> Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** Dispatcharr Sync entry: the Direct Connect form, then sync turns on by itself. */
@Composable
private fun DispatcharrSyncCard(onClick: () -> Unit) {
    SourceTypeCard(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        icon = Icons.Filled.Key,
        title = "Sync via Dispatcharr",
        subtitle = "Connect your Dispatcharr server to keep favorites, progress, reminders, and preferences on it for every profile and device.",
        trailing = {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** "Connect a Server" as a single-line row with a trailing chevron, mirroring
 *  the tvOS WelcomeView nav row (not a full-bleed gradient button). */
@Composable
private fun ConnectServerRow(onClick: () -> Unit) {
    SourceTypeCard(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        icon = Icons.Outlined.Hub,
        title = "Connect a Server",
        subtitle = null,
        trailing = {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun SkipRow(onSkip: () -> Unit) {
    TextButton(onClick = onSkip) {
        Text(
            text = "Skip for now",
            color = MaterialTheme.colorScheme.textAccent,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BrandLogo(compact: Boolean = false) {
    // Mirrors iOS WelcomeView's `.shadow(color: aerioCyan @ 0.45, radius: 20, y: 8)`
    // under the logo. Compose's shadow modifier only renders elevation drop-shadows,
    // not colored glows, so we draw a soft radial gradient halo behind the logo
    // bitmap instead, same visual effect, API-agnostic.
    val accent = MaterialTheme.colorScheme.primary
    // Sized to read as a hero logo while still letting the whole stacked page
    // fit a ~540dp-tall Android TV without scrolling.
    val haloSize = if (compact) 68.dp else 84.dp
    val imageSize = if (compact) 46.dp else 56.dp
    Box(
        modifier = Modifier.size(haloSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(haloSize)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to accent.copy(alpha = 0.55f),
                        0.35f to accent.copy(alpha = 0.28f),
                        0.7f to accent.copy(alpha = 0.06f),
                        1.0f to Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height / 2f + 8.dp.toPx()),
                    radius = size.minDimension / 2f,
                ),
                center = Offset(size.width / 2f, size.height / 2f + 8.dp.toPx()),
                radius = size.minDimension / 2f,
            )
        }
        Image(
            painter = painterResource(id = R.drawable.aerio_logo),
            contentDescription = null,
            modifier = Modifier.size(imageSize),
        )
    }
}

/**
 * Two soft, blurred accent orbs in opposite corners, mirrors iOS WelcomeView
 * lines 22-35 where SwiftUI uses `Circle().blur(radius: 80)` on top of the
 * navy background. Compose's Modifier.blur is API 31+, so the same look here
 * comes from radial gradients with carefully tuned color stops.
 */
@Composable
private fun WelcomeAmbientOrbs(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Canvas(modifier = modifier) {
        // Top-left orb (cyan)
        val tlCenter = Offset(-50.dp.toPx(), -30.dp.toPx())
        val tlRadius = 360.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to primary.copy(alpha = 0.18f),
                    0.4f to primary.copy(alpha = 0.10f),
                    0.75f to primary.copy(alpha = 0.03f),
                    1.0f to Color.Transparent,
                ),
                center = tlCenter,
                radius = tlRadius,
            ),
            center = tlCenter,
            radius = tlRadius,
        )
        // Bottom-right orb (teal)
        val brCenter = Offset(size.width + 50.dp.toPx(), size.height - 80.dp.toPx())
        val brRadius = 320.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to secondary.copy(alpha = 0.16f),
                    0.4f to secondary.copy(alpha = 0.08f),
                    0.75f to secondary.copy(alpha = 0.02f),
                    1.0f to Color.Transparent,
                ),
                center = brCenter,
                radius = brRadius,
            ),
            center = brCenter,
            radius = brRadius,
        )
    }
}

/** Linear cyan→teal gradient matching iOS LinearGradient.accentGradient
 * (top-leading to bottom-trailing). */
@Composable
private fun accentBrush(): Brush = Brush.linearGradient(
    colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
    ),
)

/** Icon filled with a Brush instead of a solid tint, mirrors iOS FeaturePill's
 * `.foregroundStyle(LinearGradient.accentGradient)` on the SF Symbol. */
@Composable
private fun GradientIcon(
    imageVector: ImageVector,
    brush: Brush,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    drawRect(brush = brush, blendMode = BlendMode.SrcIn)
                },
        )
    }
}

@Composable
private fun SupportedTypeRow(icon: ImageVector, label: String, alignStart: Boolean = false) {
    val gradient = accentBrush()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(
            10.dp,
            if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
        ),
    ) {
        GradientIcon(
            imageVector = icon,
            brush = gradient,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
