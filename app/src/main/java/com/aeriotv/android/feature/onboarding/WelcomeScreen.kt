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
import androidx.compose.ui.Alignment
import com.aeriotv.android.R
import androidx.compose.foundation.layout.size
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
    /** Google sign-in; turns Drive sync on and restores a backup when one exists. Null hides the row. */
    onSignInWithGoogle: (() -> Unit)? = null,
    googleSignInInProgress: Boolean = false,
    /** Signed-in Google account, shown on the sync row. */
    googleSignedInEmail: String? = null,
    /** "Sign in with Dispatcharr": opens the Direct Connect form, sync turns on after the connect. */
    onSyncViaDispatcharr: (() -> Unit)? = null,
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
        onSignInWithGoogle = onSignInWithGoogle,
        googleSignInInProgress = googleSignInInProgress,
        googleSignedInEmail = googleSignedInEmail,
        onSyncViaDispatcharr = onSyncViaDispatcharr,
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
    val onSignInWithGoogle: (() -> Unit)?,
    val googleSignInInProgress: Boolean,
    val googleSignedInEmail: String?,
    val onSyncViaDispatcharr: (() -> Unit)?,
    val onFastest: (() -> Unit)?,
)

@Composable
private fun WelcomeSingleColumn(c: WelcomeContent) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WelcomeBrand(headline = "Welcome.\nLet's set up your content.")
            Spacer(Modifier.height(8.dp))
            StartHereSection(c)
            Spacer(Modifier.height(6.dp))
            SyncSection(c, sideBySide = false)
            SkipPill(line = PHONE_SKIP, onSkip = c.onSkip)
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
            SyncSection(c, sideBySide = false)
            SkipPill(line = PHONE_SKIP, onSkip = c.onSkip)
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
            SyncSection(c, sideBySide = true)
            SkipPill(line = "Look around first. You can connect a server any time from Settings.", onSkip = c.onSkip, centered = false)
        }
    }
}

@Composable
private fun WelcomeBrand(headline: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Centered app logo (Logan, 2026-10-29): the launcher tile from the
        // splash, no wordmark and no body paragraph.
        Image(
            painter = painterResource(id = R.drawable.aerio_logo),
            contentDescription = null,
            modifier = Modifier.size(112.dp).align(Alignment.CenterHorizontally),
        )
        Text(
            headline,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
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
        subtitle = if (c.isTv) "Enter the details yourself. Dispatcharr, Xtream Codes, or an M3U playlist with an EPG."
        else "Dispatcharr, Xtream Codes, or an M3U playlist with an EPG.",
        onClick = c.onConnectServer,
        highlighted = fastest == null,
        compact = c.isTv,
        modifier = if (fastest == null) Modifier.focusRequester(firstFocus) else Modifier,
    )
}

@Composable
private fun SyncSection(c: WelcomeContent, sideBySide: Boolean) {
    val google = c.onSignInWithGoogle
    val dispatcharr = c.onSyncViaDispatcharr
    if (google == null && dispatcharr == null) return
    OnboardingSectionLabel("Keep your devices in sync")
    val googleRow: @Composable (Modifier) -> Unit = { m ->
        if (google != null) OnboardingCard(
            icon = Icons.Filled.CloudSync,
            title = "Sync with Google Drive",
            subtitle = c.googleSignedInEmail?.let { "Signed in as $it" }
                ?: if (c.isTv) "Turns on sync from here on. If you already have a backup, it restores it too."
                else "Turns on sync. Restores a backup if you have one.",
            onClick = google,
            enabled = !c.googleSignInInProgress,
            compact = c.isTv,
            chevron = !sideBySide,
            trailing = when {
                c.googleSignInInProgress -> ({ Text("...", style = MaterialTheme.typography.titleMedium) })
                c.googleSignedInEmail != null -> ({
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Signed in", tint = MaterialTheme.colorScheme.primary)
                })
                else -> null
            },
            modifier = m,
        )
    }
    val dispatcharrRow: @Composable (Modifier) -> Unit = { m ->
        if (dispatcharr != null) OnboardingCard(
            icon = Icons.Filled.Key,
            title = "Sign in with Dispatcharr",
            subtitle = if (c.isTv) "Connects your server and turns on sync for favorites, progress, and settings."
            else "Connects your server and turns on sync.",
            onClick = dispatcharr,
            compact = c.isTv,
            chevron = !sideBySide,
            modifier = m,
        )
    }
    if (sideBySide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (google != null) googleRow(Modifier.weight(1f))
            if (dispatcharr != null) dispatcharrRow(Modifier.weight(1f))
        }
    } else {
        googleRow(Modifier)
        dispatcharrRow(Modifier)
    }
}

private const val PHONE_SKIP = "You can connect a server any time from Settings."
