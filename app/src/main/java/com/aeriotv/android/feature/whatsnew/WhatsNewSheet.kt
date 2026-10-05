package com.aeriotv.android.feature.whatsnew

import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    val firstRowFocus = remember { FocusRequester() }
    if (isTv) {
        // TV: unchanged. The panel is a Dialog with a fixed heightIn cap, the
        // rows are focusable, and D-pad focus movement drives the scroll.
        val notesScroll = rememberScrollState()
        Column(
            modifier = Modifier
                // fill = false so a short release still wraps and the panel
                // stays compact; a long one takes the remaining height.
                .weight(1f, fill = false)
                .verticalScroll(notesScroll),
        ) {
            items.forEachIndexed { index, item ->
                WhatsNewRow(
                    item = item,
                    isTv = true,
                    modifier = if (index == 0) {
                        Modifier.focusRequester(firstRowFocus)
                    } else {
                        Modifier
                    },
                )
            }
        }
        // Bottom breathing room. On TV the window inset is zero and this is
        // plain padding.
        Spacer(Modifier.height(24.dp))
    } else {
        // PHONE / TABLET: the notes list gets a FIXED height, not a weight.
        //
        // Bug (2026-09-13): the sheet jumped up and down rapidly once the user
        // reached the bottom of the notes. Root cause was a content-derived
        // sheet height. FormFactorModal's touch branch is a wrap-content
        // Column under heightIn(max = 88% of the window), and this block used
        // Modifier.weight(1f, fill = false). "fill = false" means the measured
        // height of the scroll viewport feeds back into the height of the
        // sheet content, and ModalBottomSheet derives its Expanded anchor from
        // exactly that measured content height. Once the notes saturate the
        // weight, any remeasure during the gesture (overscroll settle, the
        // safeDrawing inset the sheet pads with as the system bars react to
        // the fling) moves the anchor, the sheet animates toward the new
        // anchor, which changes the space offered to the weighted child, which
        // changes the content height again: a measure/anchor feedback loop.
        //
        // fillMaxHeight(0.85f) resolves against the bounded 88% cap, so the
        // viewport is a constant number of pixels regardless of how many notes
        // there are or where the scroll sits. The sheet's height, and with it
        // the Expanded anchor, can no longer change while scrolling.
        //
        // Single scrollable as well: one LazyColumn, no verticalScroll around
        // it, and the trailing breathing room is contentPadding inside the
        // same scroller instead of a sibling Spacer that would again make the
        // wrapping column taller.
        LazyColumn(
            modifier = Modifier.fillMaxHeight(0.85f),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(items) { item ->
                WhatsNewRow(item = item, isTv = false)
            }
        }
    }
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
            color = MaterialTheme.colorScheme.textAccent,
        )
        if (item.body.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.body,
                style = MaterialTheme.typography.bodyMedium.subtext(),
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

/**
 * On-demand entry point: the About > App Version row in Settings opens the
 * same sheet the launch gate shows, with the notes for the installed build.
 * Deliberately does NOT touch lastSeenWhatsNewVersion, so re-reading the
 * notes here never changes whether the launch gate fires.
 */
@Composable
fun WhatsNewSheetOnDemand(onDismiss: () -> Unit) {
    WhatsNewSheet(
        version = BuildConfig.VERSION_NAME,
        items = WhatsNewContent.CURRENT,
        onDismiss = onDismiss,
    )
}

data class WhatsNewItem(val title: String, val body: String)

object WhatsNewContent {
    /** Headline changes for the current build. Edit this list and bump
     *  versionName in build.gradle.kts to surface a new sheet on next launch. */
    val CURRENT = listOf(
        WhatsNewItem(
            title = "Multiview channel logos",
            body = "Settings > Player > Multiview can show each tile's channel logo inside the picture, with a corner position and a size from 5 to 25 percent.",
        ),
        WhatsNewItem(
            title = "Reorder a channel's streams",
            body = "Dispatcharr Direct Connect admins can reorder a channel's streams from Switch Stream: drag the handle on phones and tablets, or use the reorder button on TV.",
        ),
        WhatsNewItem(
            title = "Skip Without Controls",
            body = "A new Player setting: with the controls hidden, Left and Right skip back and forward instead of opening the timeline. Works for live TV, recordings and movies.",
        ),
        WhatsNewItem(
            title = "Kept Live and tablets",
            body = "Closing the player can keep recent channels live for an instant return, shown on a Kept Live card. Tablets use the phone's bottom tab bar.",
        ),
        WhatsNewItem(
            title = "Smoother Android TV",
            body = "Back on DVR, Movies, TV Shows and Settings returns to the tab bar first, focus fixes across the guide and player, and hidden tabs no longer rebuild every 2 seconds.",
        ),
        WhatsNewItem(
            title = "Fixes",
            body = "Fixed a crash when a stream switch followed a 503 retry. Guide colors and drag precision match the Apple app. The guide paints faster at launch.",
        ),
    )
}
