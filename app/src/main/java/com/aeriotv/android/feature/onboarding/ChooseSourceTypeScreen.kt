package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.feature.onboarding.components.QrAutoScan
import com.aeriotv.android.feature.onboarding.components.StepCaption
import com.aeriotv.android.feature.onboarding.components.StepDots
import com.aeriotv.android.feature.onboarding.components.TvBackHint
import com.aeriotv.android.ui.adaptive.rememberViewport
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.settings.TvSettingsMetrics
import com.aeriotv.android.ui.settings.dpadFocusRing
import com.aeriotv.android.ui.settings.rememberIsTvDevice

/**
 * Connect a Server (onboarding step 1 of 3, and Settings > Add Playlist):
 * Dispatcharr hero card first, "Other source types" expands in place to
 * Xtream Codes and M3U. Each card pushes to its existing Configure form.
 * "Scan QR code" opens the Dispatcharr form with the scanner already up.
 *
 * Per the no-bundled-media rule, this surface ships zero hardcoded URLs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseSourceTypeScreen(
    onBack: () -> Unit,
    onChoose: (SourceType) -> Unit,
    /** Onboarding: step caption and dots; TV footer with the Back hint. */
    onboarding: Boolean = false,
    /** "Set up from another device": opens the setup receiver (docs/DeviceSetupHandoff.md). Null hides it. */
    onSetUpFromPhone: (() -> Unit)? = null,
) {
    val isTv = rememberIsTvDevice()
    val firstFocus = remember { FocusRequester() }
    val otherFocus = remember { FocusRequester() }
    var showOthers by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    if (isTv) LaunchedEffect(Unit) {
        runCatching { firstFocus.requestFocus() }
        // The focus bring-into-view nudged the column up and slid the
        // "Connect a Server" heading under the top bar (2026-11-05). The hero
        // card fits below the heading, so park the column at its top.
        kotlinx.coroutines.delay(300)
        scroll.scrollTo(0)
    }
    LaunchedEffect(showOthers) {
        if (isTv) runCatching { if (showOthers) otherFocus.requestFocus() else firstFocus.requestFocus() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!(isTv && onboarding)) {
            // Phones and tablets: "Add Playlist" centered over the centered content, as on iPhone and iPad. TV unchanged.
            val bar: @Composable (@Composable () -> Unit, @Composable () -> Unit, @Composable androidx.compose.foundation.layout.RowScope.() -> Unit, androidx.compose.material3.TopAppBarColors) -> Unit =
                if (isTv) { t, n, a, c -> TopAppBar(title = t, navigationIcon = n, actions = a, colors = c) }
                else { t, n, a, c -> androidx.compose.material3.CenterAlignedTopAppBar(title = t, navigationIcon = n, actions = a, colors = c) }
            bar(
                {
                    Text(
                        if (onboarding) "" else "Add Playlist",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = if (isTv) Modifier.padding(start = TvSettingsMetrics.topAppBarTitleOverscanPadding) else Modifier,
                    )
                },
                {
                    // No back arrow on Android TV: the remote BACK steps back.
                    if (!isTv) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                { if (onboarding) StepDots(current = 1, total = 3, modifier = Modifier.padding(end = 16.dp)) },
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        }

        val vp = rememberViewport()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = vp.gutter, vertical = if (isTv && onboarding) 20.dp else 12.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val maxW = if (isTv) 760.dp else vp.onboardingMaxWidth
            Column(
                modifier = if (maxW != Dp.Unspecified) Modifier.widthIn(max = maxW).fillMaxWidth() else Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (isTv) 10.dp else 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (onboarding) StepCaption(1, 3)
                Text(
                    "Connect a Server",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "AerioTV is built around Dispatcharr. Other playlist types work too, with fewer features.",
                    style = MaterialTheme.typography.bodyMedium.subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                DispatcharrHeroCard(
                    isTv = isTv,
                    onEnterDetails = { onChoose(SourceType.DispatcharrUserPass) },
                    onScanQr = {
                        QrAutoScan.request()
                        onChoose(SourceType.DispatcharrUserPass)
                    },
                    firstFocus = firstFocus,
                )
                OtherSourceTypes(
                    isTv = isTv,
                    expanded = showOthers,
                    onExpandedChange = { showOthers = it },
                    onChoose = onChoose,
                    firstCardFocus = otherFocus,
                )
                // Settings > Add Playlist only (Welcome has its own card), every
                // device: Apple AddServerView's OnboardingActionCard, same copy.
                if (onSetUpFromPhone != null && !onboarding) {
                    com.aeriotv.android.feature.onboarding.components.OnboardingCard(
                        icon = Icons.Filled.Devices,
                        title = "Set up from another device",
                        subtitle = "Fastest. On your other device, scan the code on the next screen, tap the banner on the " +
                            "Live TV tab, or navigate to Settings. Your playlist, profiles, and preferences come over in one step.",
                        onClick = onSetUpFromPhone,
                        compact = isTv,
                    )
                }
            }
        }

        if (isTv && onboarding) {
            // Equal-width side columns keep the dots centered.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { TvBackHint("Back to Welcome") }
                StepDots(current = 1, total = 3)
                // Right column kept empty and equal width so the dots stay
                // centered (Apple tvFooter); Welcome carries the handoff card.
                Box(Modifier.weight(1f))
            }
        }
    }
}
