package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.scale.subtext
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.pip.findActivity
import com.aeriotv.android.core.sync.DriveSyncManager
import com.aeriotv.android.core.sync.SyncCategory
import com.aeriotv.android.core.sync.SyncConfig
import com.aeriotv.android.ui.settings.settingsFormWidth
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.settings.dpadFocusRing
import com.aeriotv.android.ui.settings.settingsRowCard
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import com.aeriotv.android.ui.settings.SettingsRowContainer
import com.aeriotv.android.ui.settings.SettingsToggleAffordance
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.settingsFootnoteStyle
import com.aeriotv.android.ui.settings.settingsRowTitleStyle
import com.aeriotv.android.ui.theme.textAccent
import kotlinx.coroutines.launch
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset

/**
 * Settings > Sync sub-screen. Mirrors iOS Settings > iCloud Sync layout.
 *
 * Sign-in is a two-step flow:
 *   1. Tap "Sign in with Google" → Credential Manager surfaces the
 *      account picker → we get the user's email + GoogleId token.
 *   2. We immediately call AuthorizationClient for the Drive AppData
 *      scope. If the user has previously granted, we get an access
 *      token directly. Otherwise we launch the consent IntentSender via
 *      an ActivityResultLauncher and parse the result on return.
 *
 * After step 2 succeeds Push / Pull / Clear unlock. The per-category
 * toggles live on the pushed [SyncCategoriesScreen], as Apple's do. The "missing OAuth config" red banner shows only when the
 * BuildConfig.GOOGLE_DRIVE_WEB_CLIENT_ID field is empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit,
    /** Pushes Sync Categories (Apple's NavigationLink). Defaulted so a host
     *  that has not wired the route yet still compiles. */
    onOpenSyncCategories: () -> Unit = {},
    viewModel: SyncSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val masterEnabled by viewModel.masterEnabled.collectAsStateWithLifecycle(initialValue = false)
    val accountEmail by viewModel.accountEmail.collectAsStateWithLifecycle(initialValue = "")
    val lastPush by viewModel.lastPushAt.collectAsStateWithLifecycle(initialValue = 0L)
    val lastPull by viewModel.lastPullAt.collectAsStateWithLifecycle(initialValue = 0L)
    val statusObj by viewModel.driveStatus.collectAsState()
    val clearStatus by viewModel.clearStatus.collectAsState()
    val pushStatus by viewModel.pushStatus.collectAsState()
    val pullStatus by viewModel.pullStatus.collectAsState()
    var pushConfirmOpen by remember { mutableStateOf(false) }
    var pullConfirmOpen by remember { mutableStateOf(false) }
    var clearConfirmOpen by remember { mutableStateOf(false) }
    val configured = remember { SyncConfig.isConfigured() }

    // One-time disclosure that server credentials sync to Drive in cleartext
    // (audit task #53). initialValue=true keeps the dialog from flashing before
    // the real flag loads; the flow settles well before the user can toggle.
    val credsSyncDisclosed by viewModel.credentialsSyncDisclosed
        .collectAsStateWithLifecycle(initialValue = true)
    var credsSyncDisclosureOpen by remember { mutableStateOf(false) }

    // Silently restore a persisted Drive session on open so the screen shows
    // signed-in (and Push/Pull work) without a manual re-login.
    LaunchedEffect(Unit) { viewModel.restoreSessionIfPossible() }

    // Action results are point-in-time feedback; don't let a stale "Synced 5
    // categories" line greet the next visit (PlaylistDetailScreen pattern).
    DisposableEffect(Unit) {
        onDispose { viewModel.clearActionStatuses() }
    }

    var inFlight by remember { mutableStateOf(false) }
    // When Sign-in with Google is tapped on a build without an OAuth client
    // ID baked in, surface an explanatory dialog instead of silently doing
    // nothing - the prior "disabled button + no feedback" UX had testers
    // believing the integration itself was broken.
    var notConfiguredDialogOpen by remember { mutableStateOf(false) }

    // Consent intent launcher for step 2 of sign-in. AuthorizationClient may
    // return either an access token outright or a pendingIntent we have to
    // launch; this catches the result of the latter path.
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.acceptConsentResult(result.data)
        inFlight = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "Sync", onBack = onBack)

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
        val signedIn = statusObj is DriveSyncManager.Status.SignedIn
        // Single coroutine-launching closure shared between the inline Sign-In
        // button below the account card and any future entry point that wants
        // to kick off the flow.
        val triggerSignIn = {
            val activity = context.findActivity()
            if (activity != null) {
                inFlight = true
                scope.launch {
                    val email = viewModel.signInWithGoogle(activity)
                    if (email == null) {
                        inFlight = false
                        Toast.makeText(context, "Sign-in cancelled or failed.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    when (val driveResult = viewModel.requestDriveScope()) {
                        is DriveSyncManager.RequestResult.Authorized -> {
                            inFlight = false
                            Toast.makeText(context, "Signed in as $email", Toast.LENGTH_SHORT).show()
                        }
                        is DriveSyncManager.RequestResult.NeedsConsent -> {
                            consentLauncher.launch(
                                IntentSenderRequest.Builder(driveResult.intentSender).build(),
                            )
                            // inFlight cleared by the launcher callback.
                        }
                        DriveSyncManager.RequestResult.Failed,
                        null -> {
                            inFlight = false
                            Toast.makeText(context, "Drive authorization failed.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // Apple locks both directions (and Clear) while any one runs so a
        // double tap cannot stack operations on each other.
        val actionRunning =
            clearStatus is SyncSettingsViewModel.ActionStatus.Running ||
                pushStatus is SyncSettingsViewModel.ActionStatus.Running ||
                pullStatus is SyncSettingsViewModel.ActionStatus.Running
        // Apple shows one "Last synced" stamp; Android records the two
        // directions separately, so the newer of the two is the last sync.
        val lastSynced = maxOf(lastPush, lastPull)

        LazyColumn(
            // fillMaxHeight bounds the LazyColumn so its inner viewport can
            // scroll past the first screen of content - without it, the column
            // sized to wrap its contents and Settings -> Sync was stuck on
            // whatever fit above the bottom edge.
            modifier = Modifier.settingsFormWidth().fillMaxHeight(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = LocalTabBarBottomInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!signedIn) {
                item { SignedOutWelcomeBanner() }
            }
            item {
                // Apple SyncSettingsView parity: ONE section holding the
                // toggle, Push, Pull, Sync Categories and Clear, each on a
                // tinted icon tile, with Drive in place of iCloud. The
                // account row stays first because Drive, unlike iCloud,
                // needs an in-app sign-in.
                SettingsSection(
                    header = "",
                    footer = "Playlists, preferences, and VOD watch progress sync across all devices signed into the same Google account. Credentials are stored in your Drive app data, which only AerioTV can read.",
                ) {
                    AccountRow(
                        signedIn = signedIn,
                        email = accountEmail,
                    )
                    SyncTileToggleRow(
                        icon = Icons.Filled.Cloud,
                        title = "Drive Sync",
                        subtitle = "Sync playlists, preferences, and watch progress",
                        checked = masterEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setMasterEnabled(enabled)
                            // First time sync is turned on, disclose that server
                            // credentials are part of the Drive snapshot.
                            if (enabled && !credsSyncDisclosed) {
                                credsSyncDisclosureOpen = true
                            }
                        },
                    )
                    // Apple shows Push and Pull only while sync is on; Drive
                    // also needs a signed-in token to run either.
                    if (masterEnabled && signedIn) {
                        SyncTileActionRow(
                            icon = Icons.Filled.CloudSync,
                            title = "Push to Drive",
                            subtitle = if (lastSynced > 0L) {
                                "Send this device's data up  ·  Last synced ${lastSyncedAgo(lastSynced)}"
                            } else {
                                "Send this device's playlists, preferences and progress up"
                            },
                            running = pushStatus is SyncSettingsViewModel.ActionStatus.Running,
                            status = pushStatus,
                            onClick = {
                                if (inFlight || actionRunning) return@SyncTileActionRow
                                pushConfirmOpen = true
                            },
                        )
                        SyncTileActionRow(
                            icon = Icons.Filled.CloudDownload,
                            title = "Pull from Drive",
                            subtitle = "Replace this device's data with the Drive copy",
                            running = pullStatus is SyncSettingsViewModel.ActionStatus.Running,
                            status = pullStatus,
                            onClick = {
                                if (inFlight || actionRunning) return@SyncTileActionRow
                                pullConfirmOpen = true
                            },
                        )
                    }
                    // Reachable even with sync off, as Apple: the Delete
                    // actions there work for stale-state cleanup.
                    SyncTileActionRow(
                        icon = Icons.Filled.Tune,
                        title = "Sync Categories",
                        subtitle = "Choose what syncs across your devices",
                        chevron = true,
                        onClick = onOpenSyncCategories,
                    )
                    // Always offered on Apple, even with sync off. Drive needs
                    // a token to delete anything, so it waits for sign-in.
                    if (signedIn) {
                        SyncTileActionRow(
                            icon = Icons.Filled.Delete,
                            title = "Clear Drive Data",
                            subtitle = "Wipe synced playlists, preferences, watch progress, and credentials from Drive",
                            destructive = true,
                            running = clearStatus is SyncSettingsViewModel.ActionStatus.Running,
                            status = clearStatus,
                            onClick = {
                                if (inFlight || actionRunning) return@SyncTileActionRow
                                clearConfirmOpen = true
                            },
                        )
                    }
                }
            }
            // Sign-in / sign-out lives in its own row below the card so the
            // button has breathing room instead of getting squeezed into the
            // right edge of a multi-line description row.
            item {
                if (signedIn) {
                    SignOutButton(
                        enabled = !inFlight,
                        onClick = {
                            viewModel.signOut()
                            Toast.makeText(context, "Signed out of Drive.", Toast.LENGTH_SHORT).show()
                        },
                    )
                } else {
                    SignInWithGoogleButton(
                        // Stay enabled even without OAuth config so the
                        // tap surfaces the explanation dialog. inFlight
                        // is the only true disabled state - prevents
                        // double-launching the credential picker.
                        enabled = !inFlight,
                        onClick = {
                            if (!configured) {
                                notConfiguredDialogOpen = true
                            } else {
                                triggerSignIn()
                            }
                        },
                    )
                    if (!configured) {
                        Spacer(Modifier.height(8.dp))
                        DeveloperConfigHint()
                    }
                }
            }
        }
        }
    }

    if (pushConfirmOpen) {
        // Kept although Apple pushes without asking: a blank install once
        // overwrote a good Drive backup, so an Android push states its cost.
        com.aeriotv.android.ui.scale.AlertDialog(
            onDismissRequest = { pushConfirmOpen = false },
            title = { Text("Push to Drive?") },
            text = {
                Text(
                    "This replaces the entire Drive backup with this device's " +
                        "current configuration. Other devices that pull later " +
                        "receive this copy.",
                )
            },
            confirmButton = {
                SettingsDialogTextButton(
                    label = "Push to Drive",
                    onClick = {
                        pushConfirmOpen = false
                        viewModel.runPushOnly()
                    },
                )
            },
            dismissButton = {
                SettingsDialogTextButton(label = "Cancel", onClick = { pushConfirmOpen = false })
            },
        )
    }

    if (pullConfirmOpen) {
        // Apple's "Pull from iCloud?" alert with Drive substituted. Apple's
        // "Playlists or progress on this device that are not in iCloud are
        // removed" sentence is left out: Android's pull merges onto local
        // rows and removes nothing, so that line would be false here.
        com.aeriotv.android.ui.scale.AlertDialog(
            onDismissRequest = { pullConfirmOpen = false },
            title = { Text("Pull from Drive?") },
            text = {
                Text(
                    "This replaces this device's playlists and watch progress with the copy in Drive. " +
                        "Preferences merge normally. If this device has the newest changes, push them up first.",
                )
            },
            confirmButton = {
                SettingsDialogTextButton(
                    label = "Replace This Device",
                    onClick = {
                        pullConfirmOpen = false
                        viewModel.runPullOnly()
                    },
                    destructive = true,
                )
            },
            dismissButton = {
                SettingsDialogTextButton(label = "Cancel", onClick = { pullConfirmOpen = false })
            },
        )
    }

    if (clearConfirmOpen) {
        // Apple's "Clear iCloud Data?" alert, word for word with Drive in
        // place of iCloud. Clearing leaves sync on, exactly as Apple does.
        com.aeriotv.android.ui.scale.AlertDialog(
            onDismissRequest = { clearConfirmOpen = false },
            title = { Text("Clear Drive Data?") },
            text = {
                Text(
                    "Wipes synced playlists, preferences, watch progress, and credentials from Drive. " +
                        "This device's data is preserved. Drive Sync stays enabled, so your local state " +
                        "will replace whatever was on Drive the next time the app pushes.",
                )
            },
            confirmButton = {
                SettingsDialogTextButton(
                    label = "Clear",
                    onClick = {
                        clearConfirmOpen = false
                        viewModel.runClearRemote()
                    },
                    destructive = true,
                )
            },
            dismissButton = {
                SettingsDialogTextButton(label = "Cancel", onClick = { clearConfirmOpen = false })
            },
        )
    }

    if (credsSyncDisclosureOpen) {
        com.aeriotv.android.ui.scale.AlertDialog(
            onDismissRequest = {
                credsSyncDisclosureOpen = false
                viewModel.markCredentialsSyncDisclosed()
            },
            title = { Text("Credentials sync to your Drive") },
            text = {
                Text(
                    "So your servers restore automatically on another device, AerioTV " +
                        "includes each server's sign-in details (username, password, and API " +
                        "key) in the Drive backup. These files live in your own Google Drive " +
                        "app data, are reachable only by AerioTV, and never appear in your " +
                        "Drive UI, but they are stored without an extra password. On this " +
                        "device the same credentials are encrypted at rest.\n\n" +
                        "You can turn this off any time with the Credentials toggle in Sync Categories.",
                )
            },
            confirmButton = {
                SettingsDialogTextButton(
                    label = "Got it",
                    onClick = {
                        credsSyncDisclosureOpen = false
                        viewModel.markCredentialsSyncDisclosed()
                    },
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (notConfiguredDialogOpen) {
        com.aeriotv.android.ui.scale.AlertDialog(
            onDismissRequest = { notConfiguredDialogOpen = false },
            title = { Text("Drive Sync isn't set up yet") },
            text = {
                Text(
                    "This AerioTV build doesn't have a Google Cloud OAuth Web Client ID baked in, " +
                        "so the Sign in with Google sheet can't load.\n\n" +
                        "To enable Drive Sync on your own build, create an OAuth Web Client ID in " +
                        "Google Cloud Console, register the signing-cert SHA-1 of this APK as an " +
                        "Android Client in the same project, then add the line " +
                        "GOOGLE_DRIVE_WEB_CLIENT_ID=<your-id> to local.properties before rebuilding.",
                )
            },
            confirmButton = {
                SettingsDialogTextButton(
                    label = "Got it",
                    onClick = { notConfiguredDialogOpen = false },
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Friendly intro banner shown while the user hasn't signed in. The OAuth
 * config check moved to its own [DeveloperConfigHint] beneath the button,
 * so this card stays user-facing copy regardless of build state.
 */
@Composable
private fun SignedOutWelcomeBanner() {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(0.5.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Drive Sync isn't set up yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Select Sign in with Google below to connect your account. AerioTV will " +
                    "then keep your playlists, watch progress, reminders, and preferences in " +
                    "sync across every device signed into the same Google account.",
                style = MaterialTheme.typography.labelSmall.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Tiny dev-facing note that surfaces only when the OAuth client ID is
 * missing from BuildConfig. Sits below the disabled Sign-In button instead
 * of dominating the screen with a red error banner. End-user releases ship
 * with the ID baked in and never see this row.
 */
@Composable
private fun DeveloperConfigHint() {
    Text(
        text = "This build doesn't have a Google Cloud OAuth client configured, so " +
            "Sign in with Google is disabled. Add GOOGLE_DRIVE_WEB_CLIENT_ID to " +
            "local.properties and register the signing-cert SHA-1 in the same Cloud project.",
        style = MaterialTheme.typography.labelSmall.subtext(),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun AccountRow(signedIn: Boolean, email: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsRowCard(focused = false)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(
                    if (signedIn) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (signedIn)
                    androidx.compose.material.icons.Icons.Filled.AccountCircle
                else
                    androidx.compose.material.icons.Icons.Outlined.AccountCircle,
                contentDescription = null,
                tint = if (signedIn) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (signedIn) "Signed in to Drive" else "Not signed in",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = when {
                    signedIn && email.isNotBlank() -> email
                    signedIn -> "Account connected"
                    else -> "Sign in to start syncing across devices"
                },
                style = MaterialTheme.typography.bodySmall.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Full-width Sign-Out CTA - pairs visually with [SignInWithGoogleButton]
 * (same rounded-pill height and stretch). Renders below the account card
 * when signed in so the destructive action has space to breathe.
 */
@Composable
private fun SignOutButton(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f), RoundedCornerShape(50))
            .dpadFocusRing(RoundedCornerShape(50), washTint = MaterialTheme.colorScheme.error)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Sign Out",
            color = if (enabled) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Google-branded Sign in button. We render a Compose approximation that
 * follows Google's brand guidelines (white background, 1px outline, Google G
 * mark on the left, "Sign in with Google" label). Avoids embedding a raster
 * since the brand asset has strict size/colour rules and the rendering here
 * stays consistent across light/dark themes.
 */
@Composable
private fun SignInWithGoogleButton(enabled: Boolean, onClick: () -> Unit) {
    // Google ships two officially-permitted button styles: light (white BG /
    // dark text) and dark (#131314 BG / white text). Both must use the
    // full four-color G mark - the only freedom callers have is which
    // background variant they pick. The dark variant lands much better
    // against AerioTV's navy app surface than the white pill the previous
    // cut used, while staying compliant with Google's brand guidelines.
    // Tokens:
    //  - background #131314 (Google's "Dark" button surface)
    //  - 1dp outline #8E918F (Google's "Dark" 1dp stroke)
    //  - text #E3E3E3 (Google's "Dark" foreground)
    val bg = if (enabled) Color(0xFF131314) else Color(0xFF131314).copy(alpha = 0.55f)
    val stroke = Color(0xFF8E918F).copy(alpha = if (enabled) 1f else 0.55f)
    val fg = if (enabled) Color(0xFFE3E3E3) else Color(0xFFE3E3E3).copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, stroke, RoundedCornerShape(50))
            // Brand pill stays untouched at rest; the white ring only draws under D-pad focus.
            .dpadFocusRing(RoundedCornerShape(50))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(
                id = com.aeriotv.android.R.drawable.ic_google_g,
            ),
            contentDescription = null,
            // Tint.Unspecified preserves the four-color brand mark; tinting
            // would flatten it to a single colour which Google disallows.
            tint = Color.Unspecified,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = "Sign in with Google",
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

// Phase 61b: removed the inline GoogleGMark approximation - the button now
// renders the official four-color brand mark from res/drawable/ic_google_g.xml.


// MARK: - Apple-style tiled rows
//
// Apple's Sync rows are SettingsRow: a 32pt rounded tile in the icon color at
// 20% with the glyph in full color. The shared Android rows draw a bare
// glyph, so this page builds on SettingsRowContainer instead of changing
// every other page's rows.

@Composable
private fun SyncIconTile(icon: ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.20f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SyncRowText(
    title: String,
    subtitle: String?,
    titleColor: Color,
    statusLine: String? = null,
    statusIsError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = settingsRowTitleStyle(),
            color = titleColor,
            fontWeight = FontWeight.Medium,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = settingsFootnoteStyle().subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (statusLine != null) {
            Text(
                text = statusLine,
                style = settingsFootnoteStyle(),
                color = if (statusIsError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.textAccent,
            )
        }
    }
}

@Composable
private fun SyncTileToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SettingsRowContainer(
        onClick = { if (enabled) onCheckedChange(!checked) },
        // Apple dims disabled category rows to 50% rather than hiding them.
        modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
    ) {
        SyncIconTile(icon, MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        SyncRowText(
            title = title,
            subtitle = subtitle,
            titleColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        SettingsToggleAffordance(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * Tiled action row. While [running] the click is swallowed rather than the
 * row disabled, so it stays in D-pad traversal (SettingsActionRow's rule).
 */
@Composable
private fun SyncTileActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    destructive: Boolean = false,
    running: Boolean = false,
    status: SyncSettingsViewModel.ActionStatus = SyncSettingsViewModel.ActionStatus.Idle,
    chevron: Boolean = false,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    SettingsRowContainer(onClick = { if (!running) onClick() }) {
        SyncIconTile(icon, tint)
        Spacer(Modifier.width(14.dp))
        SyncRowText(
            title = title,
            subtitle = subtitle,
            titleColor = if (destructive) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onBackground,
            statusLine = when (status) {
                is SyncSettingsViewModel.ActionStatus.Success -> status.message
                is SyncSettingsViewModel.ActionStatus.Failure -> status.message
                else -> null
            },
            statusIsError = status is SyncSettingsViewModel.ActionStatus.Failure,
            modifier = Modifier.weight(1f),
        )
        if (running) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Apple's lastSyncedString: "just now", "5m ago", "3h ago", "2d ago". */
private fun lastSyncedAgo(millis: Long): String {
    val secs = (System.currentTimeMillis() - millis) / 1000
    return when {
        secs < 60 -> "just now"
        secs < 3600 -> "${secs / 60}m ago"
        secs < 86400 -> "${secs / 3600}h ago"
        else -> "${secs / 86400}d ago"
    }
}

// MARK: - Sync Categories copy
//
// Settings-page titles and subtitles, kept here rather than on SyncCategory
// because the onboarding chooser reads the enum's shorter copy (Apple splits
// the two the same way: subtitle vs briefSubtitle). Where Apple has the
// category its wording is used with Drive in place of iCloud; a clause that
// is false on Android is dropped instead of copied.

/** Row title on the Sync Categories page; also names a category in results. */
internal fun settingsCategoryTitle(category: SyncCategory): String = when (category) {
    SyncCategory.WatchProgress -> "VOD Watch Progress"
    else -> category.displayName
}

private fun settingsCategorySubtitle(category: SyncCategory): String = when (category) {
    // Apple adds "and reorder positions": Android's snapshot carries no order.
    SyncCategory.Playlists -> "Server configurations, playlist URLs, and per-server toggles."
    SyncCategory.WatchProgress -> "Resume points and last-watched timestamps for movies and TV episodes."
    SyncCategory.Reminders -> "Upcoming-program reminders you scheduled from the EPG."
    SyncCategory.Favorites -> "Favorite channels and your manual order."
    SyncCategory.Watchlist -> "Movies and TV shows you saved for later, and titles you hid."
    SyncCategory.Preferences -> "Theme, appearance mode, accent color, default tab, hidden groups, and palette overrides."
    SyncCategory.Credentials -> "Server passwords and API keys, stored in your Drive app data."
}

private fun settingsCategoryIcon(category: SyncCategory): ImageVector = when (category) {
    SyncCategory.Playlists -> Icons.Filled.Inbox
    SyncCategory.WatchProgress -> Icons.Filled.PlayCircle
    SyncCategory.Reminders -> Icons.Filled.NotificationsActive
    SyncCategory.Favorites -> Icons.Filled.Star
    SyncCategory.Watchlist -> Icons.Filled.Bookmark
    SyncCategory.Preferences -> Icons.Filled.Settings
    SyncCategory.Credentials -> Icons.Filled.Key
}

/**
 * Settings > Sync > Sync Categories. Apple SyncCategoriesSettingsView: one
 * toggle per category plus a destructive "Delete from Drive" per category.
 * Touch pairs each toggle with its Delete button (iOS); TV lists the toggles,
 * then a "Delete from Drive" section of "Delete <category>" rows (tvOS),
 * because a small inline button is a poor D-pad target.
 */
@Composable
fun SyncCategoriesScreen(
    onBack: () -> Unit,
    viewModel: SyncSettingsViewModel = hiltViewModel(),
) {
    val isTv = rememberIsTvDevice()
    val masterEnabled by viewModel.masterEnabled.collectAsStateWithLifecycle(initialValue = false)
    val clearStatuses by viewModel.categoryClearStatus.collectAsState()
    var pendingDelete by remember { mutableStateOf<SyncCategory?>(null) }

    // Delete needs a live token; restore it silently as the Sync page does.
    LaunchedEffect(Unit) { viewModel.restoreSessionIfPossible() }
    DisposableEffect(Unit) { onDispose { viewModel.clearCategoryStatuses() } }

    // Apple's footer with Drive in place of iCloud. Apple's "sent a few
    // seconds later" and "toggle states sync across your devices" are not
    // true on Android (DriveSyncWorker runs every 6 hours; the toggles are
    // per-device), so those sentences state what Android actually does.
    val footer = if (masterEnabled) {
        "Syncing is automatic: AerioTV syncs about every 6 hours while you are on Wi-Fi and the battery is not low. Push to Drive on the previous screen just forces that round trip immediately.\n\n" +
            "Each toggle controls whether this device pushes and pulls that category. The toggles apply to this device only. Use the Delete buttons to remove a category's cloud copy without affecting local data."
    } else {
        "Drive Sync is off. Per-category toggles take effect when you re-enable Sync at the top. The Delete buttons still work: useful for scrubbing stale Drive state before re-enabling Sync."
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "Sync Categories", onBack = onBack)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.settingsFormWidth().fillMaxHeight(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = LocalTabBarBottomInset.current,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    SettingsSection(header = "Categories", footer = if (isTv) null else footer) {
                        SyncCategory.entries.forEach { category ->
                            val enabled by viewModel.categoryEnabled(category)
                                .collectAsStateWithLifecycle(initialValue = true)
                            SyncTileToggleRow(
                                icon = settingsCategoryIcon(category),
                                title = settingsCategoryTitle(category),
                                subtitle = settingsCategorySubtitle(category),
                                checked = enabled,
                                onCheckedChange = { viewModel.setCategoryEnabled(category, it) },
                                enabled = masterEnabled,
                            )
                            if (!isTv) {
                                CategoryDeleteButton(
                                    status = clearStatuses[category],
                                    onClick = { pendingDelete = category },
                                )
                            }
                            // Slice of App Preferences rather than a category of
                            // its own: the map rides preferences.v1.json and each
                            // SyncCategory owns exactly one Drive file, so it has
                            // no Delete of its own. Sits under its parent so the
                            // nesting reads.
                            if (category == SyncCategory.Preferences) {
                                val shareRemoteMap by viewModel.syncRemoteControlMap
                                    .collectAsStateWithLifecycle(initialValue = true)
                                SyncTileToggleRow(
                                    icon = Icons.Filled.SettingsRemote,
                                    title = "Remote Button Map",
                                    subtitle = "Your customized remote button assignments. Turn this off on a TV whose remote is a different model from your others.",
                                    checked = shareRemoteMap,
                                    onCheckedChange = { viewModel.setSyncRemoteControlMap(it) },
                                    enabled = masterEnabled,
                                )
                            }
                        }
                    }
                }
                if (isTv) {
                    item {
                        SettingsSection(header = "Delete from Drive", footer = footer) {
                            SyncCategory.entries.forEach { category ->
                                val status = clearStatuses[category]
                                SyncTileActionRow(
                                    icon = Icons.Filled.CloudOff,
                                    title = "Delete ${settingsCategoryTitle(category)}",
                                    subtitle = null,
                                    destructive = true,
                                    running = status is SyncSettingsViewModel.ActionStatus.Running,
                                    status = status ?: SyncSettingsViewModel.ActionStatus.Idle,
                                    onClick = { pendingDelete = category },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { category ->
        val name = settingsCategoryTitle(category)
        com.aeriotv.android.ui.scale.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove $name from Drive?") },
            text = { Text("This will remove your $name from Drive. Other devices will keep their local copy.") },
            confirmButton = {
                SettingsDialogTextButton(
                    label = "Delete",
                    onClick = {
                        pendingDelete = null
                        viewModel.runClearRemoteCategory(category)
                    },
                    destructive = true,
                )
            },
            dismissButton = {
                SettingsDialogTextButton(label = "Cancel", onClick = { pendingDelete = null })
            },
        )
    }
}

/**
 * iOS's trailing red bordered "Delete from iCloud" button under each toggle,
 * with the result line where Apple shows its toast.
 */
@Composable
private fun CategoryDeleteButton(
    status: SyncSettingsViewModel.ActionStatus?,
    onClick: () -> Unit,
) {
    val error = MaterialTheme.colorScheme.error
    val running = status is SyncSettingsViewModel.ActionStatus.Running
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        val line = when (status) {
            is SyncSettingsViewModel.ActionStatus.Success -> status.message
            is SyncSettingsViewModel.ActionStatus.Failure -> status.message
            else -> null
        }
        if (line != null) {
            Text(
                text = line,
                style = settingsFootnoteStyle(),
                color = if (status is SyncSettingsViewModel.ActionStatus.Failure) error
                else MaterialTheme.colorScheme.textAccent,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(error.copy(alpha = 0.15f))
                .dpadFocusRing(RoundedCornerShape(8.dp), washTint = error)
                .clickable { if (!running) onClick() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = error)
            } else {
                Icon(Icons.Filled.CloudOff, contentDescription = null, tint = error, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Delete from Drive",
                style = settingsFootnoteStyle(),
                color = error,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
