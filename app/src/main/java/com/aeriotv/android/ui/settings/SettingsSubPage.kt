// SettingsSubPage.kt
//
// Settings redesign Phase 3, items 3 and 4: the push-a-page mechanism that
// pickers and collapsed sub-toggle groups share on TOUCH form factors.
//
// Why a host instead of a route: a picker page and a sub-toggle page are not
// destinations. They carry no state of their own, they are never deep-linked,
// they must not survive a fold posture change as separate entries, and adding
// a dozen of them to `SettingsRoute` would put a dozen new encodings into the
// saver for no gain. They are a presentation detail of the row that opens
// them, so they live in the page that owns that row.
//
// The host draws the sub-page OVER its content rather than replacing it, so
// the settings page underneath keeps its scroll offset and its state, and
// closing the sub-page is a pure teardown with nothing to restore.
//
// TV never uses any of this: with a remote, a two- or three-option choice is
// faster inline (exactly what today's Guide Layout rows do) than a push that
// costs a BACK press to leave, and the sub-toggles stay visible under their
// master row. The [SettingsSubPageRow] helpers below branch on form factor so
// call sites do not have to.

package com.aeriotv.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset

/** Content of one registered sub-page. */
private typealias SubPageBody = @Composable ColumnScope.() -> Unit

/**
 * Owns which sub-page (if any) is showing, and the registry of bodies.
 *
 * The body is registered by the ROW, every composition, rather than captured
 * once when the page opens: the row composes inside the settings screen, so
 * its closure is the one that sees the live preference state. Capturing at
 * open time would freeze the page's toggles at their values on the way in.
 */
class SettingsSubPageController internal constructor() {
    internal var openKey by mutableStateOf<String?>(null)
    internal var openTitle by mutableStateOf("")
    internal val bodies = mutableStateMapOf<String, SubPageBody>()

    internal fun open(key: String, title: String) {
        openTitle = title
        openKey = key
    }

    /** Closes whatever is open. Safe to call when nothing is. */
    fun close() {
        openKey = null
    }
}

/** Null outside a [SettingsSubPageHost] (which is how TV runs). */
val LocalSettingsSubPageHost = staticCompositionLocalOf<SettingsSubPageController?> { null }

/**
 * Wraps a Settings page so its rows can push single-choice and sub-toggle
 * pages. Put it immediately inside the screen's root, around the whole body
 * INCLUDING the top bar, so the pushed page replaces the title too.
 */
@Composable
fun SettingsSubPageHost(content: @Composable () -> Unit) {
    val controller = remember { SettingsSubPageController() }
    // Survives rotation and process death; the body is re-registered by the
    // row on the way back, so only the identity has to be saved.
    var savedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var savedTitle by rememberSaveable { mutableStateOf("") }
    SideEffect {
        if (controller.openKey != savedKey) savedKey = controller.openKey
        if (controller.openTitle != savedTitle) savedTitle = controller.openTitle
    }
    remember {
        controller.openKey = savedKey
        controller.openTitle = savedTitle
        true
    }
    CompositionLocalProvider(LocalSettingsSubPageHost provides controller) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            val key = controller.openKey
            val body = key?.let { controller.bodies[it] }
            if (key != null && body != null) {
                // Deeper in the composition than the hosts' own handlers, so
                // this pops the sub-page before anything pops the pane. Gated
                // on the Settings tab being the active one, exactly as the TV
                // rail host gates its own: a sub-page left open while the user
                // is on another tab must not eat that tab's Back.
                BackHandler(
                    enabled = com.aeriotv.android.feature.main.LocalTabIsActive.current,
                ) { controller.close() }
                SettingsSubPageSurface(
                    title = controller.openTitle,
                    onBack = { controller.close() },
                    body = body,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubPageSurface(
    title: String,
    onBack: () -> Unit,
    body: SubPageBody,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Swallows taps that would otherwise reach the page underneath,
            // which is still composed (that is what preserves its scroll).
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = title,
                    style = settingsTitleStyle(),
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                // Always drawn, unlike SettingsDetailTopBar: inside a tablet
                // detail pane there IS something to go back to now.
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .settingsFormWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = LocalTabBarBottomInset.current,
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = body,
            )
        }
    }
}

/**
 * Registers [body] under [key] and returns whether a push is available here.
 *
 * Call it unconditionally from a row that can push; registration is cheap and
 * keeping it unconditional is what guarantees the open page always has the
 * CURRENT closure.
 */
@Composable
internal fun rememberSubPageRegistration(key: String, body: SubPageBody): SettingsSubPageController? {
    val host = LocalSettingsSubPageHost.current ?: return null
    SideEffect { host.bodies[key] = body }
    DisposableEffect(key) {
        onDispose {
            host.bodies.remove(key)
            if (host.openKey == key) host.close()
        }
    }
    return host
}

/**
 * A settings row that pushes a sub-page on touch: title, optional summary
 * subtitle, the current [value] at the right, and a chevron.
 *
 * On TV (or anywhere with no host) it renders nothing and the caller lays the
 * page's contents out inline instead; use [settingsPushesSubPages] to decide.
 */
@Composable
fun SettingsSubPageRow(
    title: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    // Optional so existing callers are unchanged; Apple's pushed rows
    // (SettingsChoicePicker / SettingsSubgroup) carry an accent glyph.
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    SettingsRowContainer(onClick = onOpen, modifier = modifier, traceTitle = title) {
        if (leadingIcon != null) {
            // Apple's SettingsSubgroup / SettingsChoicePicker master rows use
            // the tiled SettingsRow icon, not a bare glyph.
            SettingsIconTile(icon = leadingIcon)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = settingsRowTitleStyle(),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = settingsFootnoteStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = settingsRowValueStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * True where a row should PUSH its contents onto a sub-page (phone, tablet,
 * foldable) rather than lay them out inline (TV).
 */
@Composable
fun settingsPushesSubPages(): Boolean =
    !rememberIsTvDevice() && LocalSettingsSubPageHost.current != null
