package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
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
) {
    val config = LocalConfiguration.current
    // Two-column when the viewport is meaningfully wider than tall. Threshold
    // chosen so Pixel Tablet portrait stays single-column (it's >=600w but also
    // >=800h) while TV landscape and foldable-unfolded tip into two-column.
    val twoColumn = config.screenWidthDp >= 720 && config.screenHeightDp < 720

    if (twoColumn) WelcomeTwoColumn(onConnectServer, onSkip)
    else WelcomeSingleColumn(onConnectServer, onSkip)
}

@Composable
private fun WelcomeSingleColumn(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        WelcomeAmbientOrbs(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            BrandBlock()
            Spacer(Modifier.height(28.dp))
            SupportedTypesGroup(alignStart = false)
            Spacer(Modifier.height(20.dp))
            InfoCardsGroup()
            Spacer(Modifier.height(28.dp))
            ActionButtons(onConnectServer = onConnectServer, onSkip = onSkip)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WelcomeTwoColumn(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        WelcomeAmbientOrbs(modifier = Modifier.fillMaxSize())
        WelcomeTwoColumnRow(onConnectServer, onSkip)
    }
}

@Composable
private fun WelcomeTwoColumnRow(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
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
            InfoCardsGroup()
            Spacer(Modifier.height(20.dp))
            ActionButtons(onConnectServer = onConnectServer, onSkip = onSkip)
        }
    }
}

@Composable
private fun BrandBlock(alignStart: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        BrandLogo()
        Spacer(Modifier.height(20.dp))
        Text(
            text = "AerioTV",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Your IPTV & Media Hub",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SupportedTypesGroup(alignStart: Boolean = false) {
    // Re-use the rasterised SF Symbol PNGs so the feature pills match the
    // server-type chooser cards and the iOS WelcomeView line-for-line.
    Column(modifier = Modifier.fillMaxWidth()) {
        SupportedTypeRow(
            iconPainter = painterResource(id = R.drawable.ic_key_fill),
            label = "Dispatcharr Direct Connect",
            alignStart = alignStart,
        )
        SupportedTypeRow(
            iconPainter = painterResource(id = R.drawable.ic_tv_speaker_fill),
            label = "Xtream Codes",
            alignStart = alignStart,
        )
        SupportedTypeRow(
            iconPainter = painterResource(id = R.drawable.ic_doc_text_fill),
            label = "M3U + EPG",
            alignStart = alignStart,
        )
    }
}

@Composable
private fun InfoCardsGroup() {
    Column(modifier = Modifier.fillMaxWidth()) {
        SourceTypeCard(
            icon = Icons.Filled.CloudOff,
            title = "Sync via Google Account",
            subtitle = "After setup, sign in to Drive in Settings > Sync to mirror playlists, watch progress, reminders, and preferences across devices.",
        )
        Spacer(Modifier.height(10.dp))
        SourceTypeCard(
            icon = Icons.Outlined.Wifi,
            title = "Detect Home WiFi",
            subtitle = "After setup, add a LAN URL to your playlist in Settings and AerioTV will switch to it automatically when you're on your home network.",
        )
    }
}

@Composable
private fun ActionButtons(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
) {
    val gradient = accentBrush()
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // iOS WelcomeView line 174: `.background(LinearGradient.accentGradient)`.
        // Material3 Button can't host a Brush container, so the CTA is a
        // gradient-backed Box with clickable + same Material ripple behavior.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(brush = gradient, shape = shape)
                .clickable(onClick = onConnectServer),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Hub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Connect a Server",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text(
                text = "Skip for now",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun BrandLogo() {
    // Mirrors iOS WelcomeView's `.shadow(color: aerioCyan @ 0.45, radius: 20, y: 8)`
    // under the logo. Compose's shadow modifier only renders elevation drop-shadows,
    // not colored glows, so we draw a soft radial gradient halo behind the logo
    // bitmap instead — same visual effect, API-agnostic.
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier.size(140.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(140.dp)) {
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
            modifier = Modifier.size(96.dp),
        )
    }
}

/**
 * Two soft, blurred accent orbs in opposite corners — mirrors iOS WelcomeView
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

/** Icon filled with a Brush instead of a solid tint — mirrors iOS FeaturePill's
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
            modifier = Modifier.fillMaxSize().gradientMask(brush),
        )
    }
}

@Composable
private fun GradientIcon(
    iconPainter: Painter,
    brush: Brush,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        Icon(
            painter = iconPainter,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.fillMaxSize().gradientMask(brush),
        )
    }
}

private fun Modifier.gradientMask(brush: Brush): Modifier = this.drawWithContent {
    drawContent()
    drawRect(brush = brush, blendMode = BlendMode.SrcIn)
}

@Composable
private fun SupportedTypeRow(iconPainter: Painter, label: String, alignStart: Boolean = false) {
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
            iconPainter = iconPainter,
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
