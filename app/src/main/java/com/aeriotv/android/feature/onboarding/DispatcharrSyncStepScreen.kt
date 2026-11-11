package com.aeriotv.android.feature.onboarding

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.sync.DriveSyncWorker
import com.aeriotv.android.core.sync.dsync.DispatcharrSyncManager
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.settings.dpadFocusRing
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dispatcharr Direct Connect only, after the "Add profiles for other users?"
 * step (shown, skipped, or auto-skipped): "Turn on Dispatcharr Sync?".
 * Turn On picks the same provider as Settings > Sync and waits for the first
 * pull so the guide opens with restored data. Skip leaves the provider alone.
 */
@HiltViewModel
class DispatcharrSyncStepViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val dsync: DispatcharrSyncManager,
    private val client: com.aeriotv.android.core.network.DispatcharrClient,
    private val providersStore: com.aeriotv.android.core.sync.SyncProvidersStore,
    private val setupSource: com.aeriotv.android.core.sync.SetupSourceCoordinator,
) : ViewModel() {

    /** Rule 4: both Drive and Dispatcharr hold data for this user? Read-only. */
    suspend fun probe(): com.aeriotv.android.core.sync.SetupSourceCoordinator.Probe? = setupSource.probe()

    suspend fun choose(source: com.aeriotv.android.core.sync.SetupSourceChoice.Source, playlistId: String) =
        setupSource.choose(source, playlistId)

    /** No choice needed (or Skip): Drive applies whatever it held, as before. */
    suspend fun releaseHeld() = setupSource.releaseHeld()

    /**
     * After a Dispatcharr user QR sign-in: does this user already have AerioTV
     * sync data on the server? One call to the existing App Data list
     * endpoint with the scanned key. Any failure counts as "none".
     */
    suspend fun serverHasData(signIn: com.aeriotv.android.core.qr.QrImportBus.SignIn): Boolean {
        val r = runCatching { client.appDataList(signIn.baseUrl, signIn.apiKey) }.getOrNull()
        val found = r?.ok == true && com.aeriotv.android.core.qr.DsyncRestoreProbe.listsMainKey(r.body)
        val who = signIn.username ?: "this user"
        Log.i(TAG, if (found) "[DSYNC] server data found for $who, offering restore" else "[DSYNC] server data none for $who")
        return found
    }

    suspend fun alreadyOn(): Boolean = prefs.dispatcharrSyncEnabledOnce()

    /** Turns Dispatcharr Sync on; Drive Sync is independent and stays as it is. */
    suspend fun turnOn(fromCard: Boolean = false, where: String = "onboarding") {
        prefs.setDispatcharrSyncEnabled(true)
        Log.i(TAG, if (fromCard) "[DSYNC] enabled from $where (sync card)" else "[DSYNC] enabled from $where")
        val ok = dsync.awaitFirstPull()
        Log.i(TAG, "[DSYNC] $where first pull ${if (ok) "done" else "not finished; continuing"}")
        // Onboarding first pull for the base user: evaluate the providers offer once in Main.
        if (where == "onboarding" && com.aeriotv.android.core.profiles.ProfileScope.token.value.isEmpty()) providersStore.arm()
        setupSource.afterDispatcharrOn()
    }

    private companion object { const val TAG = "AerioDsync" }
}

@Composable
fun DispatcharrSyncStepScreen(
    /** Came from the Welcome "Sync via Dispatcharr" card: turn on without asking. */
    autoEnable: Boolean = false,
    /** Set when the playlist was just signed in from a Dispatcharr user QR. */
    qrSignIn: com.aeriotv.android.core.qr.QrImportBus.SignIn? = null,
    onDone: () -> Unit,
    /** Cancel on the "Which setup" prompt; null keeps the step open. */
    onCancel: (() -> Unit)? = null,
    vm: DispatcharrSyncStepViewModel = hiltViewModel(),
) {
    // This launch already asked about Dispatcharr Sync: no upgrade offer on top.
    remember { com.aeriotv.android.core.sync.dsync.DsyncUpgradeOffer.onboardedThisLaunch = true }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // Rule 4: both sides hold differing data; the prompt decides before Turn On.
    var ask by remember { mutableStateOf<com.aeriotv.android.core.sync.SetupSourceCoordinator.Probe?>(null) }
    var askFromCard by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val runTurnOn: suspend (Boolean) -> Unit = { fromCard ->
        val p = vm.probe()
        when (p?.decision) {
            is com.aeriotv.android.core.sync.SetupSourceChoice.Decision.Ask -> { askFromCard = fromCard; ask = p }
            com.aeriotv.android.core.sync.SetupSourceChoice.Decision.UseDrive -> {
                vm.choose(com.aeriotv.android.core.sync.SetupSourceChoice.Source.Drive, p.playlistId)
                vm.turnOn(fromCard); onDone()
            }
            com.aeriotv.android.core.sync.SetupSourceChoice.Decision.UseDispatcharr -> {
                vm.choose(com.aeriotv.android.core.sync.SetupSourceChoice.Source.Dispatcharr, p.playlistId)
                vm.turnOn(fromCard); onDone()
            }
            null -> { vm.releaseHeld(); vm.turnOn(fromCard); onDone() }
        }
    }
    // Server already holds this user's AerioTV data: lead with the restore
    // copy and put focus on Turn On.
    var restore by remember { mutableStateOf(false) }
    val turnOnFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Already on (e.g. a second Dispatcharr playlist): nothing to ask.
        when {
            vm.alreadyOn() -> onDone()
            autoEnable -> runTurnOn(true)
            else -> {
                restore = qrSignIn != null && vm.serverHasData(qrSignIn)
                ready = true
            }
        }
    }
    LaunchedEffect(ready, restore) { if (ready && restore) runCatching { turnOnFocus.requestFocus() } }
    ask?.let { p ->
        SetupSourceDialog(
            drive = p.drive,
            dsync = p.dsync,
            busy = choosing,
            onContinue = { source ->
                // Dismiss at once; the step's progress view shows while the
                // choice applies (the chain needs it done before the next step).
                choosing = true
                ask = null
                ready = false
                scope.launch {
                    vm.choose(source, p.playlistId)
                    vm.turnOn(askFromCard)
                    onDone()
                }
            },
            onCancel = {
                ask = null
                busy = false
                if (onCancel != null) onCancel() else if (!ready) ready = true
            },
        )
    }
    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    SyncPromptLayout(
        title = "Turn on Dispatcharr Sync?",
        lead = if (restore) QR_RESTORE_COPY else null,
        body = "Your favorites, watch progress, reminders, and preferences are stored on your " +
            "Dispatcharr server under each profile and follow you to every device. " +
            "Admins on the server can view this data.",
        primaryLabel = "Turn On",
        secondaryLabel = "Skip",
        busy = busy,
        primaryFocus = turnOnFocus,
        onPrimary = {
            if (!busy) {
                busy = true
                scope.launch { runTurnOn(false) }
            }
        },
        onSecondary = { if (!busy) scope.launch { vm.releaseHeld(); onDone() } },
    )
}

/**
 * Full-screen Dispatcharr Sync prompt layout shared by the onboarding step and
 * the main-screen offers (matches the Apple prompts): centered title, body, a
 * filled accent pill, an outlined pill, and an optional footer.
 */
@Composable
fun SyncPromptLayout(
    title: String,
    body: String,
    primaryLabel: String,
    secondaryLabel: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
    lead: String? = null,
    busy: Boolean = false,
    primaryFocus: androidx.compose.ui.focus.FocusRequester? = null,
    footer: String? = "You can change this later in Settings > Sync.",
) {
    if (com.aeriotv.android.ui.settings.rememberIsTvDevice()) {
        TvSyncPromptLayout(title, body, primaryLabel, secondaryLabel, onPrimary, onSecondary, modifier, lead, busy, primaryFocus, footer)
        return
    }
    val vp = com.aeriotv.android.ui.adaptive.rememberViewport()
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = vp.gutter, vertical = 24.dp),
        // Centered like the Welcome and Connect steps before it (Logan, 2026-10-29).
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = if (vp.onboardingMaxWidth != androidx.compose.ui.unit.Dp.Unspecified)
                Modifier.widthIn(max = vp.onboardingMaxWidth).fillMaxWidth()
            else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            if (lead != null) {
                Text(
                    lead,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            // Buttons are never disabled mid-flow (TV focus would fall away);
            // taps are ignored while busy instead.
            Button(
                onClick = onPrimary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                    .then(if (primaryFocus != null) Modifier.focusRequester(primaryFocus) else Modifier)
                    .dpadFocusRing(RoundedCornerShape(50), onFilled = true),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else Text(primaryLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onSecondary,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).dpadFocusRing(RoundedCornerShape(50)),
            ) { Text(secondaryLabel, style = MaterialTheme.typography.titleMedium) }
            if (footer != null) {
                Text(
                    footer,
                    style = MaterialTheme.typography.bodySmall.subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * TV geometry of [SyncPromptLayout], measured from the Apple TV prompt
 * (DispatcharrSyncStepView on tvOS, 1920x1080 points): a 900 point column,
 * 38 point title, 24 point body, 66 point capsule buttons with 26 point
 * labels, a 20 point footer, 20 point gaps (28 above Turn On), and the block
 * centered vertically. Every size scales with screen height so a 1080p
 * Streamer draws the same pixels as the Apple TV.
 */
@Composable
private fun TvSyncPromptLayout(
    title: String,
    body: String,
    primaryLabel: String,
    secondaryLabel: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier,
    lead: String?,
    busy: Boolean,
    primaryFocus: androidx.compose.ui.focus.FocusRequester?,
    footer: String?,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // One Apple TV point, in dp, for this screen.
        val u = maxHeight / 1080f
        val density = androidx.compose.ui.platform.LocalDensity.current
        fun sp(points: Float) = with(density) { (u * points).toSp() }
        val column = maxWidth * (900f / 1920f)
        val pageScroll = rememberScrollState()
        // Focus back on a control near the top scrolls the page to its top
        // (only matters when large text makes the prompt taller than the screen).
        com.aeriotv.android.ui.tv.TvTopSnapPage(pageScroll) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(pageScroll)
                .padding(horizontal = u * 80f, vertical = u * 60f),
            contentAlignment = Alignment.Center,
        ) {
            Inside {
            Column(
                modifier = Modifier.width(column),
                verticalArrangement = Arrangement.spacedBy(u * 20f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    fontSize = sp(34f),
                    lineHeight = sp(41f),
                    letterSpacing = 0.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                if (lead != null) {
                    Text(
                        lead,
                        fontSize = sp(23f),
                        lineHeight = sp(28f),
                        letterSpacing = 0.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium.subtext(),
                    fontSize = sp(20f),
                    lineHeight = sp(26.5f),
                    letterSpacing = 0.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                // Shared onboarding pill; ring 3 Apple TV points (3 px at 1080p).
                com.aeriotv.android.feature.onboarding.components.OnboardingPill(
                    filled = true,
                    onClick = onPrimary,
                    ringWidth = u * 3f,
                    modifier = Modifier.padding(top = u * 8f).fillMaxWidth().height(u * 66f)
                        .then(if (primaryFocus != null) Modifier.focusRequester(primaryFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) com.aeriotv.android.ui.tv.TvFocusTrace.focus("prompt:$primaryLabel") },
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(u * 30f), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = u * 3f)
                    else Text(primaryLabel, fontSize = sp(23f), letterSpacing = 0.sp, fontWeight = FontWeight.Bold)
                }
                com.aeriotv.android.feature.onboarding.components.OnboardingPill(
                    filled = false,
                    onClick = onSecondary,
                    ringWidth = u * 3f,
                    modifier = Modifier.fillMaxWidth().height(u * 66f)
                        .onFocusChanged { if (it.isFocused) com.aeriotv.android.ui.tv.TvFocusTrace.focus("prompt:$secondaryLabel") },
                ) { Text(secondaryLabel, fontSize = sp(23f), letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold) }
                if (footer != null) {
                    Text(
                        footer,
                        style = MaterialTheme.typography.bodySmall.subtext(),
                        fontSize = sp(17f),
                        lineHeight = sp(21f),
                        letterSpacing = 0.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            }
        }
        }
    }
}

internal const val QR_RESTORE_COPY = "Restore your favorites, progress, and settings from your server."

/**
 * Settings > Playlists > Add Playlist, when the Dispatcharr playlist was added
 * from a user QR: if the server already holds this user's AerioTV data, offer
 * Dispatcharr Sync in one tap. Shows nothing when there is no data or sync is
 * already on; [onClose] runs either way.
 */
@Composable
fun QrRestoreOfferDialog(
    signIn: com.aeriotv.android.core.qr.QrImportBus.SignIn,
    onClose: () -> Unit,
    vm: DispatcharrSyncStepViewModel = hiltViewModel(),
) {
    var show by remember(signIn) { mutableStateOf(false) }
    var busy by remember(signIn) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(signIn) {
        if (vm.alreadyOn() || !vm.serverHasData(signIn)) onClose() else show = true
    }
    if (!show) return
    com.aeriotv.android.ui.settings.SettingsAlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Turn on Dispatcharr Sync?") },
        text = { Text(QR_RESTORE_COPY) },
        confirmButton = {
            com.aeriotv.android.ui.settings.SettingsDialogTextButton(
                label = if (busy) "Turning On" else "Turn On",
                onClick = {
                    if (busy) return@SettingsDialogTextButton
                    busy = true
                    scope.launch { vm.turnOn(where = "add playlist QR restore"); onClose() }
                },
            )
        },
        dismissButton = {
            com.aeriotv.android.ui.settings.SettingsDialogTextButton(label = "Not Now", onClick = { if (!busy) onClose() })
        },
    )
}
