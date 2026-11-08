package com.aeriotv.android.feature.onboarding

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.entity.isDispatcharrDirectConnect
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.core.profiles.ProfileScope
import com.aeriotv.android.core.sync.SetupSourceChoice
import com.aeriotv.android.core.sync.dsync.DispatcharrSyncManager
import com.aeriotv.android.core.sync.dsync.DsyncMissingRecord
import com.aeriotv.android.core.sync.dsync.DsyncUpgradeOffer
import com.aeriotv.android.feature.player.ExoWindowState
import com.aeriotv.android.feature.settings.SyncSettingsViewModel
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Dispatcharr Sync offer on the first launch after an update ([DsyncUpgradeOffer]). */
@HiltViewModel
class DsyncUpgradeOfferViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val playlistDao: PlaylistDao,
    val dsync: DispatcharrSyncManager,
    val profiles: ProfileManager,
    val exoWindow: ExoWindowState,
) : ViewModel() {
    data class Offer(val playlistId: String, val decision: DsyncUpgradeOffer.Decision, val driveOn: Boolean)

    /** Null = nothing to show. Runs at most once per launch (sets the process flag when it decides). */
    suspend fun evaluate(): Offer? {
        if (DsyncUpgradeOffer.evaluatedThisLaunch) return null
        val vc = BuildConfig.VERSION_CODE
        if (DsyncUpgradeOffer.onboardedThisLaunch) {
            // Onboarding just asked about sync on this version: never offer on top of it.
            DsyncUpgradeOffer.evaluatedThisLaunch = true
            prefs.setDsyncUpgradeEvaluatedVersion(vc)
            return null
        }
        if (ProfileScope.token.value.isNotEmpty()) return null // base profile only; retry when it is active
        val active = playlistDao.allOnce().firstOrNull { it.isActive } ?: return null // playlist not loaded yet
        DsyncUpgradeOffer.evaluatedThisLaunch = true
        val last = prefs.dsyncUpgradeEvaluatedVersionOnce()
        val isD = active.isDispatcharrDirectConnect()
        val on = prefs.dispatcharrSyncEnabledOnce()
        val declined = prefs.dsyncUpgradeDeclinedOnce()
        val pre = DsyncUpgradeOffer.gate(vc, last, isD, on, declined)
        val record = if (pre != null) DsyncUpgradeOffer.Record.Unread
        else runCatching { dsync.upgradeRecordState(active.id) }.getOrDefault(DsyncUpgradeOffer.Record.Error)
        val decision = DsyncUpgradeOffer.decide(vc, last, isD, on, declined, record)
        DsyncUpgradeOffer.logLine(record, decision)?.let { Log.i(TAG, it) }
        if (DsyncUpgradeOffer.stamps(decision)) prefs.setDsyncUpgradeEvaluatedVersion(vc)
        if (decision != DsyncUpgradeOffer.Decision.ShowOptIn && decision != DsyncUpgradeOffer.Decision.ShowMigrate) return null
        return Offer(active.id, decision, prefs.syncMasterEnabled.first())
    }

    /** A received setup asked for Dispatcharr Sync: the opt-in dialog, unless declined before or already on. */
    suspend fun setupOptIn(playlistId: String): Offer? {
        DsyncUpgradeOffer.setupOptIn.value = null
        if (prefs.dispatcharrSyncEnabledOnce() || prefs.dsyncUpgradeDeclinedOnce()) {
            Log.i(TAG, "[DSYNC] setup opt-in: not shown (already on or declined before)")
            return null
        }
        Log.i(TAG, "[DSYNC] setup opt-in: shown")
        return Offer(playlistId, DsyncUpgradeOffer.Decision.ShowOptIn, prefs.syncMasterEnabled.first())
    }

    /** Back without choosing (Apple swipe-away): store the offered version, no decline. */
    suspend fun dismiss() {
        prefs.setDsyncUpgradeEvaluatedVersion(BuildConfig.VERSION_CODE)
        Log.i(TAG, "[DSYNC] upgrade offer: dismissed")
    }

    suspend fun decline() {
        prefs.setDsyncUpgradeDeclined()
        Log.i(TAG, "[DSYNC] upgrade offer: not now")
    }

    private companion object { const val TAG = "AerioSync" }
}

/**
 * Main-screen host. Waits for the profile/PIN gates to clear, and on TV for
 * no fullscreen player or Multiview tile on screen, then evaluates once.
 */
@Composable
fun DsyncUpgradeOfferHost(
    syncVm: SyncSettingsViewModel,
    vm: DsyncUpgradeOfferViewModel = hiltViewModel(),
) {
    val isTv = rememberIsTvDevice()
    val gate by vm.profiles.launchGate.collectAsStateWithLifecycle()
    val picker by vm.profiles.launchPicker.collectAsStateWithLifecycle()
    val switching by vm.profiles.switching.collectAsStateWithLifecycle()
    val mode by vm.exoWindow.mode.collectAsStateWithLifecycle()
    val busy = isTv && (mode == ExoWindowState.Mode.Fullscreen || mode == ExoWindowState.Mode.Tile)
    val idle = gate == null && picker == null && !switching && !busy
    var offer by remember { mutableStateOf<DsyncUpgradeOfferViewModel.Offer?>(null) }
    var ask by remember { mutableStateOf<SetupSourceChoiceAsk?>(null) }
    val scope = rememberCoroutineScope()
    val dsyncUi by vm.dsync.ui.collectAsStateWithLifecycle()
    val setupPending by DsyncUpgradeOffer.setupOptIn.collectAsStateWithLifecycle()

    LaunchedEffect(idle, setupPending) {
        val id = setupPending ?: return@LaunchedEffect
        if (!idle || offer != null) return@LaunchedEffect
        vm.setupOptIn(id)?.let { offer = it }
    }

    LaunchedEffect(idle) {
        if (!idle || offer != null || DsyncUpgradeOffer.evaluatedThisLaunch) return@LaunchedEffect
        delay(SETTLE_MS) // the playlist load and the launch gates settle first
        repeat(LOAD_TRIES) {
            vm.evaluate()?.let { offer = it; return@LaunchedEffect }
            if (DsyncUpgradeOffer.evaluatedThisLaunch) return@LaunchedEffect
            delay(SETTLE_MS)
        }
    }

    ask?.let { a ->
        SetupSourceDialog(
            drive = a.probe.drive,
            dsync = a.probe.dsync,
            onContinue = { source ->
                ask = null
                syncVm.applySetupSourceAndEnable(source, a.probe.playlistId, enablingDrive = false)
            },
            onCancel = { ask = null },
        )
    }

    // Record missing after the first read (DsyncMissingRecord): no writes until answered.
    if (dsyncUi.recordMissing && idle) {
        val upFocus = remember { FocusRequester() }
        val turnOff = {
            vm.dsync.answerMissingRecord(upload = false)
            syncVm.setDispatcharrSyncEnabled(false)
        }
        PromptSurface(onBack = {}, initialFocus = upFocus, traceName = "Upload Again") {
            SyncPromptLayout(
                title = DsyncMissingRecord.TITLE,
                body = DsyncMissingRecord.BODY,
                primaryLabel = "Upload Again",
                secondaryLabel = "Turn Off",
                primaryFocus = upFocus,
                footer = null,
                onPrimary = { vm.dsync.answerMissingRecord(upload = true) },
                onSecondary = { turnOff() },
            )
        }
        return
    }

    val o = offer ?: return
    // Hold the dialog while a TV player is up; it shows at the next idle moment.
    if (busy) return
    val optIn = o.decision == DsyncUpgradeOffer.Decision.ShowOptIn
    val focus = remember(o) { FocusRequester() }
    val notNow = {
        offer = null
        scope.launch { vm.decline() }
        Unit
    }
    // Apple: the first-device offer says Skip; the record-found and received-setup opt-ins say Not Now.
    val dismiss = {
        offer = null
        scope.launch { vm.dismiss() }
        Unit
    }
    PromptSurface(onBack = dismiss, initialFocus = focus, traceName = "Turn On") {
        SyncPromptLayout(
            title = if (optIn) DsyncUpgradeOffer.OPT_IN_TITLE else DsyncUpgradeOffer.MIGRATE_TITLE,
            body = if (optIn) DsyncUpgradeOffer.optInBody(o.driveOn) else DsyncUpgradeOffer.MIGRATE_BODY,
            primaryLabel = "Turn On",
            secondaryLabel = if (optIn) "Not Now" else "Skip",
            primaryFocus = focus,
            onSecondary = notNow,
            onPrimary = {
                    offer = null
                    scope.launch {
                        when {
                            // Another device migrated: adopt the Dispatcharr record, no comparison prompt.
                            optIn && o.driveOn -> syncVm.applySetupSourceAndEnable(
                                SetupSourceChoice.Source.Dispatcharr, o.playlistId, enablingDrive = false,
                            )
                            optIn -> syncVm.setDispatcharrSyncEnabled(true)
                            // First device: today's Settings > Sync flow, comparison prompt included.
                            o.driveOn -> {
                                val p = runCatching { syncVm.probeSetupSource() }.getOrNull()
                                when (p?.decision) {
                                    is SetupSourceChoice.Decision.Ask -> ask = SetupSourceChoiceAsk(p)
                                    SetupSourceChoice.Decision.UseDrive ->
                                        syncVm.applySetupSourceAndEnable(SetupSourceChoice.Source.Drive, p.playlistId, false)
                                    SetupSourceChoice.Decision.UseDispatcharr ->
                                        syncVm.applySetupSourceAndEnable(SetupSourceChoice.Source.Dispatcharr, p.playlistId, false)
                                    null -> syncVm.setDispatcharrSyncEnabled(true)
                                }
                            }
                            else -> syncVm.setDispatcharrSyncEnabled(true)
                        }
                        Log.i("AerioSync", "[DSYNC] enabled from upgrade offer")
                    }
            },
        )
    }
}

/**
 * Full-screen themed surface above the main screen (the host composes before
 * the main content, so a window keeps it on top). Back runs [onBack].
 *
 * Focus (Streamer 2026-11-07): with the prompt on screen dumpsys reported
 * mCurrentFocus = MainActivity and the tracer's last line was
 * "[FOCUS] none -> tab:LiveTV", so the Live TV tab still held Compose focus
 * behind the prompt while Turn On drew its ring in the dialog's own focus
 * owner. The request made from the host ran before the dialog window was
 * attached. Now the main window's Compose focus is cleared while the prompt
 * is up (nothing behind it can hold or take focus), the dialog window asks
 * for window focus, and [initialFocus] is requested from inside the dialog
 * after its first frames, retried until it lands, and traced.
 */
@Composable
private fun PromptSurface(
    onBack: () -> Unit,
    initialFocus: FocusRequester,
    traceName: String,
    content: @Composable () -> Unit,
) {
    val mainFocus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) { mainFocus.clearFocus(force = true) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onBack,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val view = androidx.compose.ui.platform.LocalView.current
        LaunchedEffect(initialFocus) {
            repeat(FOCUS_TRIES) {
                androidx.compose.runtime.withFrameNanos { }
                if (view.isAttachedToWindow) {
                    view.rootView.requestFocus()
                    if (runCatching { initialFocus.requestFocus() }.isSuccess && view.hasFocus()) {
                        com.aeriotv.android.ui.tv.TvFocusTrace.focus("prompt:$traceName")
                        return@LaunchedEffect
                    }
                }
                delay(FOCUS_RETRY_MS)
            }
            Log.i(com.aeriotv.android.ui.tv.TvFocusTrace.TAG, "[FOCUS] prompt:$traceName request did not land")
        }
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxSize(),
            color = androidx.compose.material3.MaterialTheme.colorScheme.background,
        ) { content() }
    }
}

private data class SetupSourceChoiceAsk(val probe: com.aeriotv.android.core.sync.SetupSourceCoordinator.Probe)

private const val SETTLE_MS = 2_000L
private const val LOAD_TRIES = 15
private const val FOCUS_TRIES = 10
private const val FOCUS_RETRY_MS = 50L
