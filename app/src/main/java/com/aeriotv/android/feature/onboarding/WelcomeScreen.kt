package com.aeriotv.android.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.aeriotv.android.ui.settings.dpadFocusRing
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.ContentScale
import com.aeriotv.android.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.feature.onboarding.components.OnboardingCard
import com.aeriotv.android.feature.onboarding.components.OnboardingSectionLabel
import com.aeriotv.android.feature.onboarding.components.SkipPill
import com.aeriotv.android.feature.onboarding.components.TvBackHint
import com.aeriotv.android.ui.scale.subtext

/**
 * Cold-start welcome (onboarding redesign 2026-10-29): wordmark, headline,
 * START HERE (TV: Set up from your phone first, accent-outlined, then Connect a Server;
 * phone and tablet: Connect a Server only, QR scanning lives on Connect a Server),
 * KEEP YOUR DEVICES IN SYNC (Google Drive, Dispatcharr), then Skip directly
 * under the sync rows so it is one D-pad press from the last row.
 *
 * Phone: single column. Tablet and large foldables: two columns, brand left,
 * cards right. Android TV: two columns, the first card takes focus.
 */
@Composable
fun WelcomeScreen(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    /** Shows the "Sync with Google Drive" card (sign-in turns Drive on and restores a backup). */
    showDrive: Boolean = false,
    googleSignInInProgress: Boolean = false,
    /** Signed-in Google account, shown on the sync row. */
    googleSignedInEmail: String? = null,
    /** Shows the "Sync with Dispatcharr" card (Direct Connect form, sync on after the connect). */
    showDispatcharr: Boolean = false,
    /**
     * Continue under the sync cards (Logan, 2026-10-30): the cards only toggle
     * selection; this runs the chain for the selection (see WelcomeSyncChain).
     */
    onContinueSync: (drive: Boolean, dispatcharr: Boolean) -> Unit = { _, _ -> },
    /** TV only: "Set up from your phone" (docs/DeviceSetupHandoff.md). Null hides it. */
    onSetUpFromPhone: (() -> Unit)? = null,
) {
    val config = LocalConfiguration.current
    val isTv = com.aeriotv.android.feature.livetv.rememberLiveTvFormFactor().isTv
    val twoColumn = !isTv && config.screenWidthDp >= 720 && config.screenHeightDp < 720
    val content = WelcomeContent(
        isTv = isTv,
        onConnectServer = onConnectServer,
        onSkip = onSkip,
        showDrive = showDrive,
        googleSignInInProgress = googleSignInInProgress,
        googleSignedInEmail = googleSignedInEmail,
        showDispatcharr = showDispatcharr,
        onContinueSync = onContinueSync,
        onFastest = if (isTv) onSetUpFromPhone else null,
    )
    // TV: Welcome is the top of onboarding, and Back never closes the app.
    BackHandler(enabled = isTv) { }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            isTv -> WelcomeTv(content)
            twoColumn -> WelcomeTwoColumn(content)
            else -> WelcomeSingleColumn(content)
        }
    }
}

private class WelcomeContent(
    val isTv: Boolean,
    val onConnectServer: () -> Unit,
    val onSkip: () -> Unit,
    val showDrive: Boolean,
    val googleSignInInProgress: Boolean,
    val googleSignedInEmail: String?,
    val showDispatcharr: Boolean,
    val onContinueSync: (Boolean, Boolean) -> Unit,
    val onFastest: (() -> Unit)?,
)

@Composable
private fun WelcomeSingleColumn(c: WelcomeContent) {
    // Logan, 2026-10-29: the logo floats centered between the status bar and
    // the headline; the headline, cards, and Skip sit in the lower part of
    // the screen. Weights only apply when there is room; on a short screen
    // the band collapses to a fixed gap and the column scrolls.
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        val tall = maxHeight >= 640.dp
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .then(if (tall) Modifier.fillMaxHeight() else Modifier.verticalScroll(rememberScrollState()))
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (tall) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { WelcomeLogo() }
            } else {
                Spacer(Modifier.height(24.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { WelcomeLogo() }
                Spacer(Modifier.height(24.dp))
            }
            WelcomeHeadline("Welcome.\nLet's set up your content.")
            Spacer(Modifier.height(12.dp))
            StartHereSection(c)
            Spacer(Modifier.height(6.dp))
            SyncSection(c, sideBySide = false, skipLine = PHONE_SKIP)
            if (tall) Spacer(Modifier.weight(0.45f))
        }
    }
}

@Composable
private fun WelcomeTwoColumn(c: WelcomeContent) {
    Row(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 48.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            WelcomeBrand(headline = "Welcome.\nLet's get connected.")
        }
        Column(
            modifier = Modifier.weight(1f).widthIn(max = 540.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            StartHereSection(c)
            Spacer(Modifier.height(6.dp))
            SyncSection(c, sideBySide = false, skipLine = PHONE_SKIP)
        }
    }
}

/** 960 x 540 dp: brand and the Back hint left, the cards right, no scrolling. */
@Composable
private fun WelcomeTv(c: WelcomeContent) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(Modifier.weight(0.42f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WelcomeBrand(headline = "Welcome.\nLet's get your TV connected.")
            Spacer(Modifier.height(6.dp))
            TvBackHint("Back returns to the previous step at any time")
        }
        Column(
            modifier = Modifier.weight(0.58f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StartHereSection(c)
            Spacer(Modifier.height(4.dp))
            SyncSection(
                c,
                sideBySide = true,
                skipLine = "Look around first. You can connect a server any time from Settings.",
                skipCentered = false,
            )
        }
    }
}

@Composable
private fun WelcomeLogo(modifier: Modifier = Modifier) {
    // The logo with the name (Logan, 2026-10-29): the TV banner's wordmark
    // keyed onto a transparent background (drawable-nodpi).
    Image(
        painter = painterResource(id = R.drawable.aerio_wordmark),
        contentDescription = "AerioTV",
        contentScale = ContentScale.Fit,
        modifier = modifier.fillMaxWidth(0.8f).heightIn(max = 64.dp),
    )
}

@Composable
private fun WelcomeHeadline(headline: String) {
    Text(
        headline,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun WelcomeBrand(headline: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        WelcomeLogo(Modifier.align(Alignment.CenterHorizontally))
        WelcomeHeadline(headline)
    }
}

@Composable
private fun StartHereSection(c: WelcomeContent) {
    val firstFocus = remember { FocusRequester() }
    if (c.isTv) LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    OnboardingSectionLabel("Start here")
    val fastest = c.onFastest
    if (fastest != null) {
        if (c.isTv) {
            OnboardingCard(
                icon = Icons.Filled.PhoneAndroid,
                title = "Set up from your phone",
                subtitle = "Fastest. On your phone, scan the code on the next screen, tap the banner on the " +
                    "Live TV tab, or navigate to Settings. Your playlist, profiles, and preferences come over in one step.",
                onClick = fastest,
                highlighted = true,
                compact = true,
                modifier = Modifier.focusRequester(firstFocus),
            )
        } else {
            OnboardingCard(
                icon = Icons.Outlined.QrCodeScanner,
                title = "Scan a QR code",
                subtitle = "Fastest. Scan the QR code from the Users page in Dispatcharr, or import it from Photos.",
                onClick = fastest,
                highlighted = true,
            )
        }
    }
    OnboardingCard(
        icon = Icons.Outlined.Dns,
        title = "Connect a Server",
        subtitle = if (c.isTv) "Enter the details yourself. Dispatcharr, Xtream Codes, or an M3U playlist with an optional EPG."
        else "Dispatcharr, Xtream Codes, or an M3U playlist with an optional EPG.",
        onClick = c.onConnectServer,
        highlighted = fastest == null,
        compact = c.isTv,
        modifier = if (fastest == null) Modifier.focusRequester(firstFocus) else Modifier,
    )
}

/**
 * Both cards are selectable (checkmark plus accent outline, both may be on);
 * the D-pad focus ring stays separate from selection. A filled Continue shows
 * once one is selected and replaces Skip for now; with nothing selected Skip stays.
 */
@Composable
private fun SyncSection(c: WelcomeContent, sideBySide: Boolean, skipLine: String, skipCentered: Boolean = true) {
    var driveSelected by rememberSaveable { mutableStateOf(false) }
    var dispatcharrSelected by rememberSaveable { mutableStateOf(false) }
    if (c.showDrive || c.showDispatcharr) {
        OnboardingSectionLabel("Keep your devices in sync")
        Text(
            "Pick one or both, then Continue.",
            style = MaterialTheme.typography.bodySmall.subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val selectMark: @Composable (Boolean) -> Unit = { on ->
        if (on) Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        else Icon(Icons.Outlined.Circle, contentDescription = "Not selected", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val googleRow: @Composable (Modifier) -> Unit = { m ->
        if (c.showDrive) OnboardingCard(
            icon = Icons.Filled.CloudSync,
            title = "Sync with Google Drive",
            subtitle = c.googleSignedInEmail?.let { "Signed in as $it" }
                ?: if (c.isTv) "Turns on sync from here on. If you already have a backup, it restores it too."
                else "Turns on sync. Restores a backup if you have one.",
            onClick = { driveSelected = !driveSelected },
            enabled = !c.googleSignInInProgress,
            compact = c.isTv,
            highlighted = driveSelected,
            trailing = if (c.googleSignInInProgress) ({ Text("...", style = MaterialTheme.typography.titleMedium) })
            else ({ selectMark(driveSelected) }),
            modifier = m.semantics { selected = driveSelected },
        )
    }
    val dispatcharrRow: @Composable (Modifier) -> Unit = { m ->
        if (c.showDispatcharr) OnboardingCard(
            icon = Icons.Filled.Key,
            title = "Sync with Dispatcharr",
            subtitle = if (c.isTv) "Connects your server and turns on sync for favorites, progress, and settings."
            else "Connects your server and turns on sync.",
            onClick = { dispatcharrSelected = !dispatcharrSelected },
            compact = c.isTv,
            highlighted = dispatcharrSelected,
            trailing = { selectMark(dispatcharrSelected) },
            modifier = m.semantics { selected = dispatcharrSelected },
        )
    }
    if (sideBySide) {
        // Dispatcharr first, Google Drive second (Logan, 2026-10-29).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.showDispatcharr) dispatcharrRow(Modifier.weight(1f))
            if (c.showDrive) googleRow(Modifier.weight(1f))
        }
    } else {
        dispatcharrRow(Modifier)
        googleRow(Modifier)
    }
    val drive = driveSelected && c.showDrive
    val dispatcharr = dispatcharrSelected && c.showDispatcharr
    if (drive || dispatcharr) {
        Button(
            onClick = { if (!c.googleSignInInProgress) c.onContinueSync(drive, dispatcharr) },
            modifier = Modifier.fillMaxWidth().heightIn(min = if (c.isTv) 44.dp else 54.dp)
                .dpadFocusRing(RoundedCornerShape(50), onFilled = true),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) { Text("Continue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
    } else {
        SkipPill(line = skipLine, onSkip = c.onSkip, centered = skipCentered)
    }
}

private const val PHONE_SKIP = "You can connect a server any time from Settings."
