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
import androidx.compose.ui.unit.sp
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
    /** Settings host: room for the floating tab bar so the last card scrolls clear of it. */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    /** "Set up from another device": opens the setup receiver (docs/DeviceSetupHandoff.md). Null hides it. */
    onSetUpFromPhone: (() -> Unit)? = null,
) {
    val isTv = rememberIsTvDevice()
    // Short tab-hosted windows (the 8 inch tablet in landscape, 727 dp tall):
    // tighter header and gaps so the page, through the Set up from another
    // device card, fits above the floating tab bar without a scroll (Logan
    // 2026-11-07: less room between Add Playlist and Connect a Server).
    val short = !isTv && bottomInset > 0.dp &&
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 800
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
        // Android TV: no page bar at all. Apple TV's Settings > Add Playlist
        // presents Connect a Server with no title (AddServerView
        // navigationTitle is empty on tvOS, simulator 2026-11-06); the
        // Streamer showed "Add Playlist" above the centered title.
        if (!isTv) {
            // Phones and tablets: "Add Playlist" centered over the centered content, as on iPhone and iPad.
            val bar: @Composable (@Composable () -> Unit, @Composable () -> Unit, @Composable androidx.compose.foundation.layout.RowScope.() -> Unit, androidx.compose.material3.TopAppBarColors) -> Unit =
                // TV: a 56 dp page bar (the 64 dp default put the handoff
                // card's bottom edge 9 dp past the screen, measured 16:11 on
                // the 1080p emulator).
                if (isTv) { t, n, a, c -> TopAppBar(title = t, navigationIcon = n, actions = a, colors = c, expandedHeight = 56.dp) }
                else if (short) { t, n, a, c -> androidx.compose.material3.CenterAlignedTopAppBar(title = t, navigationIcon = n, actions = a, colors = c, expandedHeight = 52.dp) }
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
                // TV type picker: no vertical padding, so title through the
                // Set up from another device card fits the safe area and the
                // first focus (Continue) never drags the page down (Apple
                // 256f206, Logan tvOS review 2026-11-06).
                .padding(horizontal = vp.gutter, vertical = if (isTv) 0.dp else if (short) 4.dp else 12.dp)
                .padding(bottom = if (isTv) 0.dp else bottomInset),
            contentAlignment = Alignment.TopCenter,
        ) {
            // TV: one 480 dp column on the 960 dp canvas (Apple e5cb037: the
            // page spanned most of the screen with Continue filling the card
            // and the handoff card the same width).
            val maxW = if (isTv) 480.dp else vp.onboardingMaxWidth
            Column(
                modifier = if (maxW != Dp.Unspecified) Modifier.widthIn(max = maxW).fillMaxWidth() else Modifier.fillMaxWidth(),
                // TV: Apple TV's 28 pt between blocks would be 14 dp; 8 dp
                // here because the tab bar stays above this page (about 60 dp
                // Apple TV does not spend) and the Set up from another device
                // card has to fit the 540 dp canvas at open (measured
                // 2026-11-06 on the 1080p emulator: 14 dp overflowed by about
                // 60 dp, 10 dp by 16 dp, and the first Down scrolled the
                // title away each time). Phones and tablets keep 14 dp.
                verticalArrangement = Arrangement.spacedBy(if (isTv) 8.dp else if (short) 10.dp else 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (onboarding) StepCaption(1, 3)
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Connect a Server",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "AerioTV is built around Dispatcharr. Other playlist types work too, with fewer features.",
                        // Apple TV 24 pt is 12 sp; 11.5 sp here keeps the line
                        // count (Roboto runs about 8 percent wider than SF, and
                        // the page has to fit under the tab bar without a scroll).
                        style = (if (isTv) MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp, lineHeight = 15.sp) else MaterialTheme.typography.bodyMedium).subtext(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
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
                        // Apple TV detail text is 20 pt (10 sp); 9 sp keeps its two lines.
                        subtitleStyle = if (isTv) MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp, lineHeight = 12.sp) else null,
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
