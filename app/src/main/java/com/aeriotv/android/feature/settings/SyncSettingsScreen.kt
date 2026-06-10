package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.adaptive.adaptiveFormWidth

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
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
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

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
 * After step 2 succeeds the per-category toggles + Sync Now / Clear
 * unlock. The "missing OAuth config" red banner shows only when the
 * BuildConfig.GOOGLE_DRIVE_WEB_CLIENT_ID field is empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit,
    viewModel: SyncSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val masterEnabled by viewModel.masterEnabled.collectAsStateWithLifecycle(initialValue = false)
    val accountEmail by viewModel.accountEmail.collectAsStateWithLifecycle(initialValue = "")
    val lastPush by viewModel.lastPushAt.collectAsStateWithLifecycle(initialValue = 0L)
    val lastPull by viewModel.lastPullAt.collectAsStateWithLifecycle(initialValue = 0L)
    val statusObj by viewModel.driveStatus.collectAsState()
    val syncNowStatus by viewModel.syncNowStatus.collectAsState()
    val clearStatus by viewModel.clearStatus.collectAsState()
    val configured = remember { SyncConfig.isConfigured() }

    // Silently restore a persisted Drive session on open so the screen shows
    // signed-in (and Sync Now works) without a manual re-login.
    LaunchedEffect(Unit) { viewModel.restoreSessionIfPossible() }

    // Action results are point-in-time feedback; don't let a stale "Synced 5
    // categories" line greet the next visit (PlaylistDetailScreen pattern).
    DisposableEffect(Unit) {
        onDispose { viewModel.clearActionStatuses() }
    }

    var inFlight by remember { mutableStateOf(false) }
    // When Sign-in with Google is tapped on a build without an OAuth client
    // ID baked in, surface an explanatory dialog instead of silently doing
    // nothing — the prior "disabled button + no feedback" UX had testers
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

        LazyColumn(
            // fillMaxHeight bounds the LazyColumn so its inner viewport can
            // scroll past the first screen of content — without it, the column
            // sized to wrap its contents and Settings -> Sync was stuck on
            // whatever fit above the bottom edge.
            modifier = Modifier.adaptiveFormWidth().fillMaxHeight(),
            // 104dp bottom clears the MainScaffold NavigationBar so the
            // Actions card (Sync Now + Clear Drive Data) isn't clipped
            // when signed in.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!signedIn) {
                item { SignedOutWelcomeBanner() }
            }
            item {
                SettingsSection(
                    header = "Drive Sync",
                    footer = "Playlists, watch progress, reminders, app preferences and credentials sync via your Drive AppData folder. Files are scoped per-app and never appear in your main Drive UI.",
                ) {
                    AccountRow(
                        signedIn = signedIn,
                        email = accountEmail,
                    )
                    SettingsToggleRow(
                        title = "Sync enabled",
                        subtitle = if (signedIn)
                            "Auto-syncing the categories you've toggled below."
                        else
                            "Sign in below, then enable sync to push and pull data.",
                        checked = masterEnabled,
                        onCheckedChange = viewModel::setMasterEnabled,
                    )
                }
            }
            // Sign-in / sign-out lives in its own row below the account card so
            // the button has breathing room instead of getting squeezed into
                // the right edge of a multi-line description row.
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
                            // is the only true disabled state — prevents
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

            item {
                SettingsSection(header = "Categories", footer = "Choose what syncs across your devices.") {
                    SyncCategory.entries.forEach { category ->
                        val enabled by viewModel.categoryEnabled(category).collectAsStateWithLifecycle(initialValue = true)
                        SettingsToggleRow(
                            title = category.displayName,
                            subtitle = category.subtitle,
                            checked = enabled,
                            onCheckedChange = { viewModel.setCategoryEnabled(category, it) },
                        )
                    }
                }
            }

            if (signedIn) {
                item {
                    SettingsSection(
                        header = "Actions",
                        footer = "Sync Now pushes local changes then pulls remote changes. Last Push: ${formatTimestamp(lastPush)}. Last Pull: ${formatTimestamp(lastPull)}.",
                    ) {
                        // Inline spinner + result line replaced the old Toasts,
                        // which never surfaced on Android TV (rows looked dead).
                        val actionRunning =
                            syncNowStatus is SyncSettingsViewModel.ActionStatus.Running ||
                                clearStatus is SyncSettingsViewModel.ActionStatus.Running
                        SettingsActionRow(
                            label = "Sync Now",
                            subtitle = "Last synced: ${formatTimestamp(lastPull)}",
                            leadingIcon = Icons.Filled.Sync,
                            running = syncNowStatus is SyncSettingsViewModel.ActionStatus.Running,
                            statusLine = when (val s = syncNowStatus) {
                                is SyncSettingsViewModel.ActionStatus.Success -> s.message
                                is SyncSettingsViewModel.ActionStatus.Failure -> s.message
                                else -> null
                            },
                            statusIsError = syncNowStatus is SyncSettingsViewModel.ActionStatus.Failure,
                            onClick = {
                                if (inFlight || actionRunning) return@SettingsActionRow
                                viewModel.runSyncNow()
                            },
                        )
                        SettingsActionRow(
                            label = "Clear Drive Data",
                            leadingIcon = Icons.Filled.CloudOff,
                            destructive = true,
                            running = clearStatus is SyncSettingsViewModel.ActionStatus.Running,
                            statusLine = when (val s = clearStatus) {
                                is SyncSettingsViewModel.ActionStatus.Success -> s.message
                                is SyncSettingsViewModel.ActionStatus.Failure -> s.message
                                else -> null
                            },
                            statusIsError = clearStatus is SyncSettingsViewModel.ActionStatus.Failure,
                            onClick = {
                                if (inFlight || actionRunning) return@SettingsActionRow
                                viewModel.runClearRemote()
                            },
                        )
                    }
                }
            }
        }
        }
    }

    if (notConfiguredDialogOpen) {
        androidx.compose.material3.AlertDialog(
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
                style = MaterialTheme.typography.labelSmall,
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
        style = MaterialTheme.typography.labelSmall,
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Full-width Sign-Out CTA — pairs visually with [SignInWithGoogleButton]
 * (same rounded-pill height and stretch). Renders below the account card
 * when signed in so the destructive action has space to breathe.
 */
@Composable
private fun SignOutButton(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f), RoundedCornerShape(50))
            .dpadFocusRing(RoundedCornerShape(50), washTint = MaterialTheme.colorScheme.error)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Sign out",
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
    // full four-color G mark — the only freedom callers have is which
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
            .height(48.dp)
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

// Phase 61b: removed the inline GoogleGMark approximation — the button now
// renders the official four-color brand mark from res/drawable/ic_google_g.xml.

private fun formatTimestamp(value: Long): String {
    if (value <= 0L) return "never"
    val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return df.format(Date(value))
}
