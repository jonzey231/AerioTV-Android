package com.aeriotv.android.feature.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.NavEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Modal bottom sheet that surfaces the headline changes in a fresh app build.
 * Audit task #46 (What's New / release-notes flow).
 *
 * Triggered by [WhatsNewGate]: compares [BuildConfig.VERSION_NAME] against the
 * value the user has dismissed before. First-ever install seeds the pref to
 * the current version silently (no sheet on top of the onboarding flow);
 * upgrades after that pop the sheet once and write the new value on dismiss.
 *
 * Content is hardcoded per-release; future releases just edit the
 * [WhatsNewContent] list below before bumping versionName in build.gradle.kts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(
    version: String,
    items: List<WhatsNewItem>,
    onDismiss: () -> Unit,
) {
    // Form-factor split (AddToMultiviewSheet / UpdatePromptSheet precedent):
    // a bottom sheet is a touch idiom -- on Android TV its drag handle reads
    // as a dead control and the sheet sits awkwardly at the screen bottom.
    // TV gets a centered Dialog panel with the dismiss button auto-focused.
    val isTv = com.aeriotv.android.feature.settings.rememberIsTvDevice()
    if (isTv) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .heightIn(max = 560.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    WhatsNewBody(version, items, onDismiss, autoFocusDismiss = true)
                }
            }
        }
    } else {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                WhatsNewBody(version, items, onDismiss, autoFocusDismiss = false)
            }
        }
    }
}

@Composable
private fun ColumnScope.WhatsNewBody(
    version: String,
    items: List<WhatsNewItem>,
    onDismiss: () -> Unit,
    autoFocusDismiss: Boolean,
) {
    val dismissFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    Text(
        text = "What's New in v$version",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(16.dp))
    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState()),
    ) {
        items.forEach { item ->
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (item.body.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.focusRequester(dismissFocus),
        ) {
            Text("Got it")
        }
    }
    Spacer(Modifier.height(20.dp))
    if (autoFocusDismiss) {
        LaunchedEffect(Unit) {
            repeat(10) {
                if (runCatching { dismissFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(16L)
            }
        }
    }
}

/** Top-level gate. Drop into [AerioTVNavHost]'s root [androidx.compose.foundation.layout.Box]. */
@Composable
fun WhatsNewGate() {
    val context = LocalContext.current
    val prefs = remember {
        EntryPointAccessors
            .fromApplication(context.applicationContext, NavEntryPoint::class.java)
            .appPreferences()
    }
    val scope = rememberCoroutineScope()
    val current = BuildConfig.VERSION_NAME
    val lastSeen by prefs.lastSeenWhatsNewVersion.collectAsState(initial = null)
    var shownThisSession by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }

    // Wait until we've definitively read the stored value (lastSeen != null),
    // then decide once per session: first-ever install seeds silently; an
    // upgrade pops the sheet; same-version does nothing.
    LaunchedEffect(lastSeen) {
        val seen = lastSeen ?: return@LaunchedEffect
        if (shownThisSession) return@LaunchedEffect
        if (seen.isBlank()) {
            // First-ever install: seed silently. The onboarding flow is the
            // more relevant first-launch surface, no need to also pop this.
            prefs.setLastSeenWhatsNewVersion(current)
        } else if (seen != current) {
            visible = true
        }
        shownThisSession = true
    }

    if (visible) {
        WhatsNewSheet(
            version = current,
            items = WhatsNewContent.CURRENT,
            onDismiss = {
                visible = false
                scope.launch { prefs.setLastSeenWhatsNewVersion(current) }
            },
        )
    }
}

data class WhatsNewItem(val title: String, val body: String)

private object WhatsNewContent {
    /** Headline changes for the current build. Edit this list and bump
     *  versionName in build.gradle.kts to surface a new sheet on next launch. */
    val CURRENT = listOf(
        WhatsNewItem(
            title = "Global Search",
            body = "One search covers everything. Look across live channels and " +
                "the guide, movies, and series at once, and narrow the results " +
                "with the scope chips at the top.",
        ),
        WhatsNewItem(
            title = "Channel Collections",
            body = "Build your own channel groups (Favorites, Kids, Sports, " +
                "anything you like) and they show up as pills right on Live TV.",
        ),
        WhatsNewItem(
            title = "List or Guide, your choice",
            body = "Switch Live TV between a clean channel list and the classic " +
                "grid guide with one tap.",
        ),
        WhatsNewItem(
            title = "Multiview, upgraded",
            body = "Pick a layout (Even Grid, Hero and Corner, and more), scrub " +
                "each tile on its own, and mix live channels with movies, " +
                "series, and recordings in the same view.",
        ),
        WhatsNewItem(
            title = "DVR that keeps up",
            body = "Start watching a recording while it is still recording, see " +
                "genre pills on your recordings, and get a Recording indicator " +
                "the moment one starts.",
        ),
        WhatsNewItem(
            title = "On Demand, richer",
            body = "A Continue Watching tab picks up where you left off, movie " +
                "and series pages now show cast bios and Known For, and you can " +
                "jump straight to a show with Open Series.",
        ),
        WhatsNewItem(
            title = "Kid-safe channels",
            body = "AerioTV now respects a Dispatcharr account's Channel " +
                "Profile, so a child profile only ever sees the channels it is " +
                "allowed to.",
        ),
        WhatsNewItem(
            title = "Safer, smoother, more polished",
            body = "Your server credentials are now encrypted on the device and " +
                "kept out of logs. Plus smoother live playback, readable detail " +
                "text on unfolded foldables, cleaner Picture-in-Picture, and a " +
                "raft of Android TV navigation fixes.",
        ),
    )
}
