package com.aeriotv.android.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.Routes
import com.aeriotv.android.core.update.UpdateState
import com.aeriotv.android.feature.settings.rememberIsTvDevice

/**
 * Launch-prompt gate for the in-app updater (github flavor; renders nothing
 * when the flavor-bound manager is disabled). Mounted at the Navigation root
 * beside WhatsNewGate.
 *
 * Sequencing rules: prompts only on the main tabs (never over the player,
 * multiview, or onboarding), only after the current version's What's New was
 * dismissed/seeded (no stacked sheets), at most once per session for a
 * version the user hasn't engaged with, and never for a version they chose
 * "Later" on. Engaged states (downloading, ready, installing) stay visible.
 */
@Composable
fun UpdateGate(currentRoute: String?) {
    val vm: UpdateViewModel = hiltViewModel()
    if (!vm.isEnabled) return
    val state by vm.state.collectAsStateWithLifecycle()
    val lastSeenWhatsNew by vm.lastSeenWhatsNewVersion.collectAsStateWithLifecycle(initialValue = null)
    var sessionDismissed by remember { mutableStateOf(false) }

    // Resume a staged update that survived a process death (unknown-sources
    // grant / install commit both kill the process).
    LaunchedEffect(Unit) { vm.resumePending() }

    // Throttled auto-check on every app foreground (TV keeps the process
    // resident for days, so cold-launch-only checks would go stale).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.autoCheck()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val onMainTabs = currentRoute == Routes.MAIN
    val whatsNewSettled = lastSeenWhatsNew == BuildConfig.VERSION_NAME
    val visible = !sessionDismissed && onMainTabs && when (state) {
        is UpdateState.Available -> whatsNewSettled
        is UpdateState.Downloading,
        is UpdateState.Verifying,
        is UpdateState.ReadyToInstall,
        is UpdateState.AwaitingInstallPermission,
        is UpdateState.Installing,
        -> true
        // Download/install failures re-surface here; check failures stay in
        // the Settings screen (manual checks render their own error there).
        is UpdateState.Error -> (state as UpdateState.Error).info != null
        else -> false
    }
    if (!visible) return

    UpdatePromptSheet(
        state = state,
        onPrimary = {
            when (state) {
                is UpdateState.Available -> vm.download()
                is UpdateState.ReadyToInstall,
                is UpdateState.AwaitingInstallPermission,
                -> vm.install()
                is UpdateState.Error ->
                    if ((state as UpdateState.Error).info != null) vm.download() else vm.manualCheck()
                else -> Unit
            }
        },
        onLater = {
            sessionDismissed = true
            if (state is UpdateState.Error) vm.dismissError() else vm.later()
        },
        onHide = { sessionDismissed = true },
    )
}

/**
 * The update prompt. Same form-factor split as the multiview picker: a
 * centered Dialog panel on TV (a bottom sheet's gesture model misfires on a
 * D-pad), the native ModalBottomSheet on phone/tablet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatePromptSheet(
    state: UpdateState,
    onPrimary: () -> Unit,
    onLater: () -> Unit,
    onHide: () -> Unit,
) {
    val isTv = rememberIsTvDevice()
    if (isTv) {
        Dialog(
            onDismissRequest = onHide,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.55f),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    UpdatePromptBody(state, onPrimary, onLater, autoFocusPrimary = true)
                }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onHide,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                UpdatePromptBody(state, onPrimary, onLater, autoFocusPrimary = false)
            }
        }
    }
}

@Composable
private fun UpdatePromptBody(
    state: UpdateState,
    onPrimary: () -> Unit,
    onLater: () -> Unit,
    autoFocusPrimary: Boolean,
) {
    val primaryFocus = remember { FocusRequester() }

    val titleText: String
    val bodyText: String
    var notes = ""
    var primaryLabel: String? = null
    var laterLabel: String? = "Later"
    var progressPercent: Int? = null
    var indeterminate = false

    when (state) {
        is UpdateState.Available -> {
            titleText = "Update available"
            val mb = state.info.apkSizeBytes / (1024 * 1024)
            bodyText = "AerioTV ${state.info.versionName} is ready to download (${mb} MB)."
            notes = state.info.notes
            primaryLabel = "Download"
        }
        is UpdateState.Downloading -> {
            titleText = "Downloading update"
            bodyText = "AerioTV ${state.info.versionName}"
            progressPercent = state.progressPercent
            laterLabel = null
        }
        is UpdateState.Verifying -> {
            titleText = "Verifying download"
            bodyText = "Checking the update's signature and version."
            indeterminate = true
            laterLabel = null
        }
        is UpdateState.ReadyToInstall -> {
            titleText = "Ready to install"
            bodyText = "AerioTV ${state.info.versionName} is verified and ready. Your " +
                "channels, settings, and recordings are kept. AerioTV will close to " +
                "install; reopen it from your home screen."
            primaryLabel = "Install"
        }
        is UpdateState.AwaitingInstallPermission -> {
            titleText = "One-time permission needed"
            bodyText = "Android needs you to allow AerioTV to install updates. In the " +
                "Settings screen that opens, turn on \"Allow from this source\", then " +
                "come back and tap Install."
            primaryLabel = "Open Settings"
        }
        is UpdateState.Installing -> {
            titleText = "Installing"
            bodyText = "Confirm the update in the Android dialog. AerioTV will close to " +
                "install; reopen it from your home screen. Your data is kept."
            indeterminate = true
            laterLabel = null
        }
        is UpdateState.Error -> {
            titleText = "Update problem"
            bodyText = state.message
            primaryLabel = "Try again"
            laterLabel = "Close"
        }
        else -> return
    }

    Text(
        text = titleText,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = bodyText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (notes.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = notes,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
    progressPercent?.let { pct ->
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { pct / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "$pct%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (indeterminate) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp))
        }
    }
    Spacer(Modifier.height(18.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        laterLabel?.let { label ->
            TextButton(onClick = onLater) { Text(label) }
            Spacer(Modifier.width(8.dp))
        }
        primaryLabel?.let { label ->
            Button(
                onClick = onPrimary,
                modifier = Modifier.focusRequester(primaryFocus),
            ) { Text(label) }
        }
    }
    if (autoFocusPrimary && primaryLabel != null) {
        LaunchedEffect(state::class) {
            // Attached a frame after composition; retry briefly (banner-focus
            // pattern from GuideScreen).
            repeat(10) {
                if (runCatching { primaryFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(16L)
            }
        }
    }
}
