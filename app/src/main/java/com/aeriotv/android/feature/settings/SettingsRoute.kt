// SettingsRoute.kt
//
// Settings redesign Phase B2 (SettingsUIRedesign.md B1/B2): the typed route
// model and back stack that replace the six independent `mutableStateOf`
// booleans in `SettingsTabContent`.
//
// Why a stack instead of booleans: with booleans, "what is on top" is encoded
// implicitly in the ORDER OF THE `when` BRANCHES, in two places that have to
// agree (the BackHandler and the content `when`). Adding a screen means
// remembering to slot it into both at the right position. A stack makes
// "innermost" literal, so Back is a pop and the ordering rules fall out of
// push order.
//
// Rev 2 requirement: PlaylistDetail and EditPlaylist carry the playlist id IN
// THE ROUTE (like Apple's `.server(id)`) so restoration after a fold or
// process death does not depend on unsaved view-model selection state.
//
// Selection is NOT the stack (Rev 2): in the two-pane hosts of B3/B4 the pane
// selection lives in its own value and rail/sidebar browsing never pushes, so
// Back can never replay browse history. This stack holds only the pushes ABOVE
// the pane baseline.

package com.aeriotv.android.feature.settings

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.Composable
import com.aeriotv.android.core.data.SourceType

/** One destination in the Settings area. */
sealed interface SettingsRoute {
    /** The stacked root list. Phone only; the two-pane hosts never push it. */
    data object Root : SettingsRoute

    /** One of the canonical settings sections (Appearance, Network, ...). */
    data class Section(val section: SettingsSection) : SettingsRoute

    /** The playlist list page (its own page on Android, unlike iPad). */
    data object Playlists : SettingsRoute

    /** Playlist detail. Carries the id so restore does not need the view model. */
    data class PlaylistDetail(val playlistId: String) : SettingsRoute

    /** Edit Playlist form; a full-screen takeover on TV. */
    data class EditPlaylist(val playlistId: String) : SettingsRoute

    /** The Add Playlist wizard; [step] is the stage within it. */
    data class AddPlaylist(val step: AddPlaylistWizardStep) : SettingsRoute

    /** Debug log viewer; stays a full-screen takeover everywhere (wide lines). */
    data object LogViewer : SettingsRoute

    /** The extra category-colour editor. */
    data object AddMoreCategories : SettingsRoute

    /**
     * The About block. On a phone it is inline at the bottom of the root list
     * and this route is never pushed; in a two-pane host the sidebar replaces
     * that list, so About needs a destination of its own to stay reachable.
     */
    data object About : SettingsRoute
}

/**
 * The routes a two-pane host may hold as its SELECTION (the pane baseline).
 * Everything else is a push above whichever of these is selected.
 */
val SettingsRoute.isPaneBaseline: Boolean
    get() = this is SettingsRoute.Playlists ||
        this is SettingsRoute.Section ||
        this is SettingsRoute.About

// MARK: - Frozen section canon
//
// Rev 2 requires the visible-section gating to be extracted VERBATIM rather
// than re-typed, because it is now read from two places (the phone root list
// and the tablet/TV sidebar) and any drift between them would be a silent
// content-canon violation.

/** One header + rows group in the Settings root list and sidebar. */
data class SettingsSectionGroupSpec(
    /** Stable LazyColumn item key; matches the pre-B3 hand-written keys. */
    val key: String,
    val header: String,
    val sections: List<SettingsSection>,
    val footer: String? = null,
)

/**
 * The canonical Settings groups, in canon order, with the existing per-form
 * factor gates: Remote Control is TV-only and App Updates is sideload-only.
 */
fun visibleSettingsSections(
    isTv: Boolean,
    updaterEnabled: Boolean,
): List<SettingsSectionGroupSpec> = listOf(
    SettingsSectionGroupSpec(
        key = "app-settings",
        header = "App Settings",
        sections = buildList {
            add(SettingsSection.Appearance)
            add(SettingsSection.AppBehaviors)
            add(SettingsSection.Multiview)
            add(SettingsSection.Network)
            // Remote Control initiative: TV form factors only.
            if (isTv) add(SettingsSection.RemoteControl)
            if (updaterEnabled) add(SettingsSection.AppUpdates)
        },
    ),
    SettingsSectionGroupSpec(
        key = "sync",
        header = "Sync",
        sections = listOf(SettingsSection.Sync),
        footer = "Playlists, preferences, and watch progress sync across all devices " +
            "signed into the same Google account. Credentials stay in encrypted Android storage.",
    ),
    SettingsSectionGroupSpec(
        key = "dvr",
        header = "DVR",
        sections = listOf(SettingsSection.DvrSettings),
    ),
    SettingsSectionGroupSpec(
        key = "developer",
        header = "Developer",
        sections = listOf(SettingsSection.Developer),
    ),
)

/** Stage within the Add Playlist wizard. */
sealed interface AddPlaylistWizardStep {
    data object ChooseType : AddPlaylistWizardStep
    data class Configure(val sourceType: SourceType) : AddPlaylistWizardStep
}

/**
 * The Settings back stack. Holds ONLY pushes above the baseline: on a phone the
 * baseline is the root list, in a two-pane host it is the selected pane.
 *
 * `current` is the innermost route, or null when the baseline is showing.
 */
class SettingsNavState(initial: List<SettingsRoute> = emptyList()) {
    val stack: SnapshotStateList<SettingsRoute> = mutableStateListOf<SettingsRoute>().apply {
        addAll(initial)
    }

    val current: SettingsRoute? get() = stack.lastOrNull()

    val canPop: Boolean get() = stack.isNotEmpty()

    fun push(route: SettingsRoute) {
        stack.add(route)
    }

    /**
     * Replaces the top of the stack. Used by the Add Playlist wizard, whose
     * stages advance in place rather than nesting (Configure -> ChooseType on
     * Back is a replace, matching today's `when`-branch behavior).
     */
    fun replaceTop(route: SettingsRoute) {
        if (stack.isEmpty()) stack.add(route) else stack[stack.lastIndex] = route
    }

    /** Pops one level. Returns false when already at the baseline. */
    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun popToRoot() {
        stack.clear()
    }

    /**
     * Inserts [route] UNDER everything already pushed. Used by the posture
     * mapping when a two-pane host collapses to stacked: the pane selection
     * becomes the bottom-most push, so Back walks out through it exactly as if
     * the user had navigated there from the root list.
     */
    fun insertAtBaseline(route: SettingsRoute) {
        stack.add(0, route)
    }

    /**
     * Removes and returns the bottom-most entry, or null when empty. The
     * stacked-to-two-pane direction of the same mapping: that entry becomes the
     * pane selection and any deeper pushes stay on the stack above it.
     */
    fun removeBaseline(): SettingsRoute? {
        if (stack.isEmpty()) return null
        return stack.removeAt(0)
    }

    /** Stable key for the focus contract; see MainScaffold's settingsContentFocus. */
    val focusKey: String?
        get() = when (val r = current) {
            null -> null
            is SettingsRoute.Root -> null
            is SettingsRoute.Section -> "section-${r.section.name}"
            is SettingsRoute.Playlists -> "playlists"
            is SettingsRoute.PlaylistDetail -> "detail-${r.playlistId}"
            is SettingsRoute.EditPlaylist -> "edit-${r.playlistId}"
            is SettingsRoute.AddPlaylist -> when (r.step) {
                is AddPlaylistWizardStep.ChooseType -> "choosetype"
                is AddPlaylistWizardStep.Configure -> "configure"
            }
            is SettingsRoute.LogViewer -> "log"
            is SettingsRoute.AddMoreCategories -> "addmore"
            is SettingsRoute.About -> "about"
        }
}

// MARK: - Saver
//
// Routes are value-like, so each encodes to a single string. rememberSaveable
// then survives rotation, fold posture changes, and process death, which the
// boolean set could not (it lived in plain `remember`).

internal fun encodeSettingsRoute(route: SettingsRoute): String = when (route) {
    is SettingsRoute.Root -> "root"
    is SettingsRoute.Section -> "section:${route.section.name}"
    is SettingsRoute.Playlists -> "playlists"
    is SettingsRoute.PlaylistDetail -> "detail:${route.playlistId}"
    is SettingsRoute.EditPlaylist -> "edit:${route.playlistId}"
    is SettingsRoute.AddPlaylist -> when (val s = route.step) {
        is AddPlaylistWizardStep.ChooseType -> "add:choose"
        is AddPlaylistWizardStep.Configure -> "add:configure:${s.sourceType.name}"
    }
    is SettingsRoute.LogViewer -> "log"
    is SettingsRoute.AddMoreCategories -> "addmore"
    is SettingsRoute.About -> "about"
}

internal fun decodeSettingsRoute(raw: String): SettingsRoute? = when {
    raw == "root" -> SettingsRoute.Root
    raw == "about" -> SettingsRoute.About
    raw == "playlists" -> SettingsRoute.Playlists
    raw == "log" -> SettingsRoute.LogViewer
    raw == "addmore" -> SettingsRoute.AddMoreCategories
    raw == "add:choose" -> SettingsRoute.AddPlaylist(AddPlaylistWizardStep.ChooseType)
    raw.startsWith("section:") ->
        // An unknown section name means the enum changed under a saved state;
        // drop the entry rather than crash the restore.
        SettingsSection.entries.firstOrNull { it.name == raw.removePrefix("section:") }
            ?.let { SettingsRoute.Section(it) }
    raw.startsWith("detail:") -> SettingsRoute.PlaylistDetail(raw.removePrefix("detail:"))
    raw.startsWith("edit:") -> SettingsRoute.EditPlaylist(raw.removePrefix("edit:"))
    raw.startsWith("add:configure:") ->
        SourceType.entries.firstOrNull { it.name == raw.removePrefix("add:configure:") }
            ?.let { SettingsRoute.AddPlaylist(AddPlaylistWizardStep.Configure(it)) }
    else -> null
}

val SettingsNavStateSaver: Saver<SettingsNavState, List<String>> = Saver(
    save = { it.stack.map(::encodeSettingsRoute) },
    restore = { SettingsNavState(it.mapNotNull(::decodeSettingsRoute)) },
)

/**
 * Saver for the two-pane SELECTION, which lives outside the stack (Rev 2:
 * "selection is not the stack") and so needs its own saveable encoding.
 */
val SettingsRouteSaver: Saver<SettingsRoute, String> = Saver(
    save = { encodeSettingsRoute(it) },
    restore = { decodeSettingsRoute(it) },
)

@Composable
fun rememberSettingsNavState(): SettingsNavState =
    rememberSaveable(saver = SettingsNavStateSaver) { SettingsNavState() }
