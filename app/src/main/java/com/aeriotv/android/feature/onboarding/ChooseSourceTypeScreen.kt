package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.feature.onboarding.components.SourceTypeCard
import com.aeriotv.android.ui.adaptive.rememberViewport

/**
 * Mirrors iOS App Store screenshot IMG_1077: "Add Playlist" back stack frame
 * with "Choose Source Type" header and three rounded cards (Dispatcharr,
 * Xtream Codes, M3U + EPG). Tapping a card pushes to the Configure form.
 *
 * Per the no-bundled-media rule, this surface ships zero hardcoded URLs - the
 * cards only describe types, the user picks one and supplies their own URL on
 * the next screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseSourceTypeScreen(
    onBack: () -> Unit,
    onChoose: (SourceType) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Add Playlist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        val vp = rememberViewport()
        // On wide+short viewports (TV landscape, foldables-landscape) lay the
        // three cards in a single row so all three are reachable above the
        // fold without a remote scroll. On any other size the cards stack
        // vertically; on tablets they stay centered with a max width so they
        // don't stretch unreadably wide.
        val rowLayout = vp.widthDp >= 840 && vp.heightDp < 720

        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = vp.gutter, vertical = 12.dp),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
            Column(
                modifier = if (vp.formMaxWidth != androidx.compose.ui.unit.Dp.Unspecified && !rowLayout)
                    Modifier.widthIn(max = vp.formMaxWidth).fillMaxWidth()
                else
                    Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Choose Source Type",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Select how you want to connect to your media source.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(4.dp))

                if (rowLayout) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SourceTypeCard(
                            icon = Icons.Outlined.Key,
                            title = "Dispatcharr Direct Connect",
                            subtitle = "Connect with your admin login or a personal API key.",
                            modifier = Modifier
                                .weight(1f)
                                .tappable { onChoose(SourceType.DispatcharrApiKey) },
                        )
                        SourceTypeCard(
                            icon = Icons.Outlined.Storage,
                            title = "Xtream Codes",
                            subtitle = "Xtream Codes API. Live TV, VOD movies & series.",
                            modifier = Modifier
                                .weight(1f)
                                .tappable { onChoose(SourceType.XtreamCodes) },
                        )
                        SourceTypeCard(
                            icon = Icons.Outlined.Description,
                            title = "M3U + EPG",
                            subtitle = "Any M3U playlist URL. Works with any IPTV provider.",
                            modifier = Modifier
                                .weight(1f)
                                .tappable { onChoose(SourceType.M3uUrl) },
                        )
                    }
                } else {
                    SourceTypeCard(
                        icon = Icons.Outlined.Key,
                        title = "Dispatcharr Direct Connect",
                        subtitle = "Connect to Dispatcharr with your admin login or a personal API key " +
                                "(*AerioTV is not officially affiliated with the Dispatcharr project)",
                        modifier = Modifier.tappable { onChoose(SourceType.DispatcharrApiKey) },
                    )
                    SourceTypeCard(
                        icon = Icons.Outlined.Storage,
                        title = "Xtream Codes",
                        subtitle = "Xtream Codes API. Live TV, VOD movies & series.",
                        modifier = Modifier.tappable { onChoose(SourceType.XtreamCodes) },
                    )
                    SourceTypeCard(
                        icon = Icons.Outlined.Description,
                        title = "M3U + EPG",
                        subtitle = "Any M3U playlist URL. Works with Dispatcharr, any IPTV provider.",
                        modifier = Modifier.tappable { onChoose(SourceType.M3uUrl) },
                    )
                }
            }
        }
    }
}

private fun Modifier.tappable(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
