package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aeriotv.android.feature.onboarding.components.SourceTypeCard

/**
 * Cold-start welcome surface. Mirrors iOS App Store screenshot IMG_1076: AerioTV
 * brand block at top, supported source types listed, Sync + Detect-Home-WiFi
 * cards (placeholders for Phase 9/10 here), "Connect a Server" CTA, "Skip for
 * now" link.
 */
@Composable
fun WelcomeScreen(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
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
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Phone, tablet, & Google TV",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

        SupportedTypeRow(icon = Icons.Outlined.Key, label = "Dispatcharr Server Credentials")
        SupportedTypeRow(icon = Icons.Outlined.Storage, label = "Xtream Codes")
        SupportedTypeRow(icon = Icons.Outlined.Description, label = "M3U + EPG")

        Spacer(Modifier.height(20.dp))

        // Cloud-sync + Home-WiFi tiles surface the corresponding feature
        // entry points. The actual configuration screens live in Settings;
        // these tiles are info cards on first launch so the user knows the
        // capabilities exist.
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

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = onConnectServer,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Hub,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = "Connect a Server",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text(
                text = "Skip for now",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BrandLogo() {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.LiveTv,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
    }
}

@Composable
private fun SupportedTypeRow(icon: ImageVector, label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
