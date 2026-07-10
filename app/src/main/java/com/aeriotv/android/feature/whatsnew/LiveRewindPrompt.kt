package com.aeriotv.android.feature.whatsnew

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.NavEntryPoint
import com.aeriotv.android.Routes
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Live Rewind one-time feature prompt (task #145 P2).
 *
 * The P1 field test proved the need: the feature shipped OFF by default
 * and the toggle sat unnoticed in Settings > App Behaviors, so the
 * player showed no transport at all ("Still no playback controls").
 * This asks once, on the main tabs, whether to turn it on.
 *
 * Sequencing: renders only on [Routes.MAIN] (never over onboarding) and
 * waits until the What's New sheet has settled for this build
 * (lastSeenWhatsNewVersion == current), so the two never stack. New
 * installs therefore see it right after onboarding lands on the main
 * tabs; upgrades see it after dismissing What's New.
 */
@Composable
fun LiveRewindPromptGate(currentRoute: String?) {
    val context = LocalContext.current
    val prefs = remember {
        EntryPointAccessors
            .fromApplication(context.applicationContext, NavEntryPoint::class.java)
            .appPreferences()
    }
    val scope = rememberCoroutineScope()
    val onMainTabs = currentRoute == Routes.MAIN
    val whatsNewSettled by prefs.lastSeenWhatsNewVersion.collectAsState(initial = null)
    var decided by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(onMainTabs, whatsNewSettled) {
        if (decided || !onMainTabs) return@LaunchedEffect
        if (whatsNewSettled != BuildConfig.VERSION_NAME) return@LaunchedEffect
        if (prefs.liveRewindPromptSeen.first()) {
            decided = true
            return@LaunchedEffect
        }
        if (prefs.liveRewindEnabled.first()) {
            // User already found the toggle on their own; never prompt.
            prefs.setLiveRewindPromptSeen(true)
            decided = true
            return@LaunchedEffect
        }
        visible = true
        decided = true
    }

    if (!visible) return

    val enableFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = { /* explicit choice required; both write the seen flag */ },
        title = { Text("New: Live Rewind") },
        text = {
            Column {
                Text(
                    "Pause and rewind live TV. While you watch a channel " +
                        "fullscreen, AerioTV keeps a rolling buffer on this " +
                        "device so you can skip back, scrub the timeline, or " +
                        "pause and pick up where you left off.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Buffered video is deleted automatically. You can change " +
                        "this anytime in Settings > App Behaviors > Live Rewind.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    visible = false
                    scope.launch {
                        prefs.setLiveRewindEnabled(true)
                        prefs.setLiveRewindPromptSeen(true)
                    }
                },
                modifier = Modifier.focusRequester(enableFocus),
            ) { Text("Enable") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    visible = false
                    scope.launch { prefs.setLiveRewindPromptSeen(true) }
                },
            ) { Text("Not Now") }
        },
    )
    // TV: land D-pad focus on Enable (WhatsNewSheet retry pattern; the
    // dialog needs a frame or two before the requester is attached).
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { enableFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(16L)
        }
    }
}
