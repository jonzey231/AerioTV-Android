package com.aeriotv.android.feature.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.NavEntryPoint
import com.aeriotv.android.ui.FormFactorModal
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import dagger.hilt.android.EntryPointAccessors
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
@Composable
fun WhatsNewSheet(
    version: String,
    items: List<WhatsNewItem>,
    onDismiss: () -> Unit,
) {
    // Form-factor split through the house container [FormFactorModal]: phone
    // and tablet get a Material3 ModalBottomSheet (drag handle, swipe down or
    // scrim tap to dismiss, content capped at 88% of the window so the notes
    // below get a real scroll viewport), Android TV gets the centered Dialog
    // panel painted with TvChrome.dialogSurface like every other TV pop-up,
    // dismissed with BACK.
    //
    // There is no Done button on either form factor (Logan 2026-09-12): the
    // platform dismiss gesture is the only way out, so nothing competes with
    // the notes for focus on TV or steals vertical room on a phone.
    FormFactorModal(
        onDismiss = onDismiss,
        tvWidthFraction = 0.55f,
        tvMaxHeight = 560.dp,
    ) {
        WhatsNewBody(version, items)
    }
}

@Composable
private fun ColumnScope.WhatsNewBody(
    version: String,
    items: List<WhatsNewItem>,
) {
    val isTv = rememberIsTvDevice()
    Text(
        text = "What's New in v$version",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
    Spacer(Modifier.height(16.dp))
    // GH #59: the notes scroll, and on TV the D-pad has to be able to drive
    // that scroll. Rather than making the whole block one focusable proxy
    // (the old tvDpadScrollable wrapper, which read as a single oversized
    // target), every note row is its own focus target: Up/Down walks the
    // rows and Compose's focus machinery brings each one into view, so the
    // panel scrolls as a side effect and there is no dead end at either end.
    val notesScroll = rememberScrollState()
    val firstRowFocus = remember { FocusRequester() }
    Column(
        modifier = Modifier
            // fill = false so a short release (today: 3 notes) still wraps
            // and the sheet stays compact; a long one takes the remaining
            // height and scrolls inside it.
            .weight(1f, fill = false)
            .verticalScroll(notesScroll),
    ) {
        items.forEachIndexed { index, item ->
            WhatsNewRow(
                item = item,
                isTv = isTv,
                modifier = if (isTv && index == 0) {
                    Modifier.focusRequester(firstRowFocus)
                } else {
                    Modifier
                },
            )
        }
    }
    // Bottom breathing room only. The navigation-bar inset is already handled
    // by the sheet: ModalBottomSheet pads its content with
    // BottomSheetDefaults.windowInsets, which is safeDrawing's Top + Bottom
    // sides (material3 1.4.0, SheetDefaults.kt), and windowInsetsPadding
    // CONSUMES that inset, so a navigationBarsPadding() here would measure
    // zero anyway. On TV the inset is zero and this is plain padding.
    Spacer(Modifier.height(24.dp))
    if (isTv) {
        // Park initial focus on the first note so Up/Down scrolls
        // immediately. Retried because the dialog's focus owner is not ready
        // on the very first frame.
        LaunchedEffect(Unit) {
            repeat(10) {
                if (runCatching { firstRowFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(16L)
            }
        }
    }
}

/**
 * One release note. On TV it is focusable and carries the house focus
 * treatment (primary wash plus a 2 dp accent ring) so the D-pad position is
 * obvious at 10 feet; on touch it is plain text with no focus chrome.
 */
@Composable
private fun WhatsNewRow(
    item: WhatsNewItem,
    isTv: Boolean,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (isTv) {
                    Modifier
                        .background(
                            if (focused) colors.primary.copy(alpha = 0.18f) else Color.Transparent,
                        )
                        .border(
                            width = 2.dp,
                            color = if (focused) colors.primary else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
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
            title = "Smoother resolution switching on NVIDIA Shield",
            body = "With Match Content Resolution on, the audio no longer " +
                "drops out and re-buffers right after the picture mode " +
                "changes.",
        ),
        WhatsNewItem(
            title = "Event channels in the guide",
            body = "Channels with no guide data show the channel name as the " +
                "programme, and when the name carries a date and time the " +
                "guide marks when the event starts.",
        ),
        WhatsNewItem(
            title = "Manage groups from the pill row",
            body = "When groups are shown as pills at the top of the guide, " +
                "the round Manage Groups button now sits before the first " +
                "pill, the same as in the sidebar.",
        ),
    )
}
