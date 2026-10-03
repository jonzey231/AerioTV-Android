package com.aeriotv.android.feature.settings

import androidx.compose.material.icons.automirrored.filled.HelpOutline
import com.aeriotv.android.ui.theme.textAccent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.text.font.FontWeight
import com.aeriotv.android.ui.settings.SettingsRowContainer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.remote.GuideRemoteAction
import com.aeriotv.android.core.remote.PlayerRemoteAction
import com.aeriotv.android.core.remote.RemoteControlMap
import com.aeriotv.android.core.remote.RemotePreset
import com.aeriotv.android.core.remote.RemoteSlot
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import com.aeriotv.android.ui.settings.settingsFormWidth
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsRowContainer
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.settings.SettingsSelectionRow
import com.aeriotv.android.ui.settings.SettingsToggleRow
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset

/**
 * Settings > App Settings > Remote Control (TV only). Customizable
 * per-context button maps. Reset restores the standard scheme behind
 * a TvMenuGuard-protected confirm.
 */

/** Slots offered per context on Android TV (plan section 5). Guide
 *  up/down long are deliberately omitted until a guide long-press
 *  detector exists (omit rather than fake). */
private val PLAYER_SLOTS = listOf(
    RemoteSlot.OK_SHORT, RemoteSlot.OK_LONG,
    RemoteSlot.UP_SHORT, RemoteSlot.UP_LONG,
    RemoteSlot.DOWN_SHORT, RemoteSlot.DOWN_LONG,
    RemoteSlot.LEFT_SHORT, RemoteSlot.LEFT_LONG,
    RemoteSlot.RIGHT_SHORT, RemoteSlot.RIGHT_LONG,
)
private val PLAYER_EXTENDED_SLOTS = listOf(
    RemoteSlot.PLAY_PAUSE, RemoteSlot.FFWD, RemoteSlot.REWIND,
    RemoteSlot.CHANNEL_UP, RemoteSlot.CHANNEL_DOWN,
)
private val GUIDE_SLOTS = listOf(
    RemoteSlot.OK_SHORT, RemoteSlot.OK_LONG,
    RemoteSlot.LEFT_SHORT, RemoteSlot.LEFT_LONG,
    RemoteSlot.RIGHT_SHORT, RemoteSlot.RIGHT_LONG,
)
private val GUIDE_EXTENDED_SLOTS = listOf(
    RemoteSlot.PLAY_PAUSE, RemoteSlot.FFWD, RemoteSlot.REWIND,
    RemoteSlot.CHANNEL_UP, RemoteSlot.CHANNEL_DOWN,
)

/** Player actions offered in the picker: only actions the executor can
 *  actually run today (implementer note: omit rather than fake). */
private val PLAYER_ACTION_CHOICES = listOf(
    PlayerRemoteAction.CHANNEL_UP,
    PlayerRemoteAction.CHANNEL_DOWN,
    PlayerRemoteAction.LAST_CHANNEL,
    PlayerRemoteAction.RECENT_CHANNELS,
    PlayerRemoteAction.OPEN_SEARCH,
    PlayerRemoteAction.TOGGLE_CONTROLS,
    PlayerRemoteAction.SHOW_PROGRAM_INFO,
    PlayerRemoteAction.OPTIONS_MENU,
    PlayerRemoteAction.SEEK_FORWARD,
    PlayerRemoteAction.SEEK_BACKWARD,
    PlayerRemoteAction.MINIMIZE_TO_GUIDE,
    PlayerRemoteAction.CHANNEL_LIST,
    PlayerRemoteAction.NONE,
)

private val GUIDE_ARROW_SLOTS = setOf(
    RemoteSlot.OK_SHORT, RemoteSlot.OK_LONG,
    RemoteSlot.LEFT_SHORT, RemoteSlot.LEFT_LONG,
    RemoteSlot.RIGHT_SHORT, RemoteSlot.RIGHT_LONG,
)

private fun guideActionChoices(slot: RemoteSlot): List<GuideRemoteAction> = buildList {
    if (slot in GUIDE_ARROW_SLOTS) {
        add(GuideRemoteAction.FOCUS_GROUP_PILLS)
        add(GuideRemoteAction.TIMELINE_BACK)
        add(GuideRemoteAction.TIMELINE_FORWARD)
        // Select has no focus movement of its own to fall back on.
        if (slot != RemoteSlot.OK_SHORT && slot != RemoteSlot.OK_LONG) add(GuideRemoteAction.NAVIGATE)
        add(GuideRemoteAction.PROGRAM_INFO)
        add(GuideRemoteAction.PROGRAM_MENU)
        add(GuideRemoteAction.RECORD)
        add(GuideRemoteAction.PLAY)
        add(GuideRemoteAction.JUMP_TO_NOW)
        add(GuideRemoteAction.JUMP_TO_DAY)
        add(GuideRemoteAction.PAGE_UP)
        add(GuideRemoteAction.PAGE_DOWN)
        if (slot == RemoteSlot.RIGHT_LONG) add(GuideRemoteAction.CLOSE_MINI_PLAYER)
        add(GuideRemoteAction.NONE)
        return@buildList
    }
    add(GuideRemoteAction.TIMELINE_BACK)
    add(GuideRemoteAction.TIMELINE_FORWARD)
    add(GuideRemoteAction.PAGE_UP)
    add(GuideRemoteAction.PAGE_DOWN)
    add(GuideRemoteAction.JUMP_TO_NOW)
    add(GuideRemoteAction.JUMP_TO_DAY)
    add(GuideRemoteAction.JUMP_TO_TOP)
    add(GuideRemoteAction.FOCUS_GROUP_PILLS)
    add(GuideRemoteAction.RESUME_PLAYER)
    // The mini-close teardown runs at the activity layer, which today
    // only dispatches it from the hold-Right path.
    if (slot == RemoteSlot.RIGHT_LONG) add(GuideRemoteAction.CLOSE_MINI_PLAYER)
    if (slot == RemoteSlot.OK_LONG) add(GuideRemoteAction.PROGRAM_INFO)
    add(GuideRemoteAction.NONE)
}

private val RemoteSlot.displayName: String
    get() = when (this) {
        RemoteSlot.OK_SHORT -> "Select"
        RemoteSlot.OK_LONG -> "Select (Hold)"
        RemoteSlot.UP_SHORT -> "Up"
        RemoteSlot.UP_LONG -> "Up (Hold)"
        RemoteSlot.DOWN_SHORT -> "Down"
        RemoteSlot.DOWN_LONG -> "Down (Hold)"
        RemoteSlot.LEFT_SHORT -> "Left"
        RemoteSlot.LEFT_LONG -> "Left (Hold)"
        RemoteSlot.RIGHT_SHORT -> "Right"
        RemoteSlot.RIGHT_LONG -> "Right (Hold)"
        RemoteSlot.PLAY_PAUSE -> "Play/Pause"
        RemoteSlot.FFWD -> "Fast Forward"
        RemoteSlot.REWIND -> "Rewind"
        RemoteSlot.CHANNEL_UP -> "Channel Up"
        RemoteSlot.CHANNEL_DOWN -> "Channel Down"
    }

private val PlayerRemoteAction.displayName: String
    get() = when (this) {
        PlayerRemoteAction.CHANNEL_UP -> "Channel up"
        PlayerRemoteAction.CHANNEL_DOWN -> "Channel down"
        PlayerRemoteAction.LAST_CHANNEL -> "Previous channel"
        PlayerRemoteAction.RECENT_CHANNELS -> "Recently watched"
        // Fixed hold-Back behavior; named here for completeness but never
        // offered in the choice lists (Back semantics stay hardcoded).
        PlayerRemoteAction.STOP_PLAYBACK -> "Stop playback"
        PlayerRemoteAction.TOGGLE_CONTROLS -> "Show/hide controls"
        PlayerRemoteAction.SHOW_PROGRAM_INFO -> "Show program info"
        PlayerRemoteAction.OPTIONS_MENU -> "Options menu"
        PlayerRemoteAction.PLAY_PAUSE -> "Play/Pause"
        PlayerRemoteAction.SEEK_FORWARD -> "Seek forward"
        PlayerRemoteAction.SEEK_BACKWARD -> "Seek back"
        PlayerRemoteAction.RESTART_PROGRAM -> "Restart program"
        PlayerRemoteAction.JUMP_TO_LIVE -> "Jump to live"
        PlayerRemoteAction.MINIMIZE_TO_GUIDE -> "Return to TV Guide"
        PlayerRemoteAction.CHANNEL_LIST -> "Channel list"
        PlayerRemoteAction.SUBTITLES -> "Subtitles"
        PlayerRemoteAction.AUDIO_TRACKS -> "Audio tracks"
        PlayerRemoteAction.ASPECT_RATIO -> "Aspect ratio"
        PlayerRemoteAction.RECORD -> "Record"
        PlayerRemoteAction.SLEEP_TIMER -> "Sleep timer"
        PlayerRemoteAction.OPEN_SEARCH -> "Search"
        PlayerRemoteAction.NONE -> "Do nothing"
    }

/** Guide action name; the group menu is named for the selector it opens. */
private fun GuideRemoteAction.displayName(sidebarGroups: Boolean): String = when (this) {
    GuideRemoteAction.FOCUS_GROUP_PILLS -> if (sidebarGroups) "Open sidebar" else "Go to group pills"
    else -> displayName
}

private val GuideRemoteAction.displayName: String
    get() = when (this) {
        GuideRemoteAction.PAGE_UP -> "Page channels up"
        GuideRemoteAction.PAGE_DOWN -> "Page channels down"
        GuideRemoteAction.TIMELINE_BACK -> "Browse earlier programs"
        GuideRemoteAction.TIMELINE_FORWARD -> "Browse later programs"
        GuideRemoteAction.JUMP_TO_NOW -> "Jump to now"
        GuideRemoteAction.JUMP_TO_DAY -> "Jump to a day and time"
        GuideRemoteAction.JUMP_TO_TOP -> "Jump to top channel"
        GuideRemoteAction.FOCUS_GROUP_PILLS -> "Go to group pills"
        GuideRemoteAction.RESUME_PLAYER -> "Return to player"
        GuideRemoteAction.CLOSE_MINI_PLAYER -> "Close mini player"
        GuideRemoteAction.PROGRAM_INFO -> "Program info"
        GuideRemoteAction.PROGRAM_MENU -> "Program menu"
        GuideRemoteAction.RECORD -> "Record"
        GuideRemoteAction.PLAY -> "Play channel"
        GuideRemoteAction.NAVIGATE -> "Move focus"
        GuideRemoteAction.OPEN_SEARCH -> "Search"
        GuideRemoteAction.NONE -> "Do nothing"
    }

@Composable
fun RemoteControlSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val map by viewModel.remoteControlMap.collectAsStateWithLifecycle(
        initialValue = RemoteControlMap.DEFAULT,
    )
    var editingPlayerSlot by remember { mutableStateOf<RemoteSlot?>(null) }
    var editingGuideSlot by remember { mutableStateOf<RemoteSlot?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val groupSelector by viewModel.guideGroupSelector.collectAsStateWithLifecycle(
        initialValue = "pills",
    )
    val tuneInMini by viewModel.guideTuneInMini.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val showRemoteHints by viewModel.showRemoteHints.collectAsStateWithLifecycle(
        initialValue = true,
    )
    val menuGuard = rememberTvMenuGuard()

    fun saveEdited(newMap: RemoteControlMap) {
        viewModel.setRemoteControlMap(newMap.copy(preset = RemotePreset.CUSTOM))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "Remote Control", onBack = onBack)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .settingsFormWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = LocalTabBarBottomInset.current),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettingsSection(header = "On-Screen Hints") {
                    SettingsToggleRow(
                        title = "Show Remote Hints",
                        // Apple TV: questionmark.circle.
                        leadingIcon = if (com.aeriotv.android.ui.settings.rememberIsTvDevice()) Icons.AutoMirrored.Filled.HelpOutline else null,
                        subtitle = "Key reminders on Live TV and in the player",
                        checked = showRemoteHints,
                        onCheckedChange = { viewModel.setShowRemoteHints(it) },
                    )
                }

                // Pick-one rows rather than a value row + dialog, matching the
                // tvOS page, which lists both targets inline with a checkmark.
                SettingsSection(
                    header = "Play Channels In",
                    footer = "Where a channel starts playing when you press Select on it in Live TV. Mini Player keeps you browsing with the channel in the corner; press Select on it again to go full screen.",
                ) {
                    SettingsSelectionRow(
                        label = "Full Screen",
                        selected = !tuneInMini,
                        onClick = { viewModel.setGuideTuneInMini(false) },
                    )
                    SettingsSelectionRow(
                        label = "Mini Player",
                        selected = tuneInMini,
                        onClick = { viewModel.setGuideTuneInMini(true) },
                    )
                }

                SettingsSection(
                    header = "While Watching",
                    footer = "What each button does while a channel is playing full screen. Changes apply immediately. Back always navigates and cannot be reassigned.",
                ) {
                    PLAYER_SLOTS.forEach { slot ->
                        SlotRow(
                            slotName = slot.displayName,
                            valueName = map.playerAction(slot).displayName,
                            onClick = { editingPlayerSlot = slot },
                        )
                    }
                }

                SettingsSection(
                    header = "In the TV Guide",
                    footer = "What each button does while browsing the guide. Up and Down always navigate. Left or Right set to Play channel, Record, Program info or Program menu acts on every press. Any other Left action acts only from the program airing now, and any other Right action only from the last program in the row; elsewhere the arrow moves between programs. In Sidebar Menu mode, if no button is set to Open sidebar, holding Left opens it.",
                ) {
                    GUIDE_SLOTS.forEach { slot ->
                        SlotRow(
                            slotName = slot.displayName,
                            valueName = map.guideAction(slot, groupSelector == "sidebar").displayName(groupSelector == "sidebar"),
                            onClick = { editingGuideSlot = slot },
                        )
                    }
                }

                SettingsSection(
                    header = "Additional Buttons",
                    footer = "For remotes with dedicated playback and channel buttons. These retarget what the button does while a channel is playing.",
                ) {
                    AdditionalSubheader("While Watching")
                    PLAYER_EXTENDED_SLOTS.forEach { slot ->
                        SlotRow(
                            slotName = slot.displayName,
                            valueName = map.playerAction(slot).displayName,
                            onClick = { editingPlayerSlot = slot },
                        )
                    }
                    AdditionalSubheader("In the Guide")
                    GUIDE_EXTENDED_SLOTS.forEach { slot ->
                        SlotRow(
                            slotName = slot.displayName,
                            valueName = map.guideAction(slot).displayName(groupSelector == "sidebar"),
                            onClick = { editingGuideSlot = slot },
                        )
                    }
                }

                SettingsSection(
                    header = "Reset",
                    footer = "Restore every button to the standard AerioTV scheme.",
                ) {
                    // tvOS TVSettingsActionRow(isDestructive:): red label
                    // behind the arrow.counterclockwise glyph.
                    SettingsRowContainer(onClick = { showResetConfirm = true }) {
                        Icon(
                            imageVector = Icons.Filled.Restore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = "Reset to Defaults",
                            style = com.aeriotv.android.ui.settings.settingsRowTitleStyle(),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    editingPlayerSlot?.let { slot ->
        TvActionMenuDialog(
            title = slot.displayName,
            actions = PLAYER_ACTION_CHOICES.map { action ->
                val current = map.playerAction(slot) == action
                TvMenuAction(
                    label = if (current) "${action.displayName}  (current)" else action.displayName,
                ) {
                    saveEdited(map.copy(player = map.player + (slot to action)))
                    editingPlayerSlot = null
                }
            },
            onDismiss = { editingPlayerSlot = null },
            guard = menuGuard,
            width = 340.dp,
        )
    }

    editingGuideSlot?.let { slot ->
        TvActionMenuDialog(
            title = slot.displayName,
            actions = guideActionChoices(slot).map { action ->
                val current = map.guideAction(slot, groupSelector == "sidebar") == action
                val name = action.displayName(groupSelector == "sidebar")
                TvMenuAction(
                    label = if (current) "$name  (current)" else name,
                ) {
                    saveEdited(map.copy(guide = map.guide + (slot to action)))
                    editingGuideSlot = null
                }
            },
            onDismiss = { editingGuideSlot = null },
            guard = menuGuard,
            width = 340.dp,
        )
    }

    if (showResetConfirm) {
        TvActionMenuDialog(
            title = "Lose your remote control customizations?",
            actions = listOf(
                TvMenuAction(label = "Reset to Defaults", destructive = true) {
                    viewModel.setRemoteControlMap(RemoteControlMap.DEFAULT)
                    showResetConfirm = false
                },
                TvMenuAction(label = "Cancel") { showResetConfirm = false },
            ),
            onDismiss = { showResetConfirm = false },
            guard = menuGuard,
        )
    }
}

@Composable
private fun SlotRow(
    slotName: String,
    valueName: String,
    onClick: () -> Unit,
) {
    SettingsRowContainer(onClick = onClick) {
        // Same title size and weight as every other Settings row.
        Text(
            text = slotName,
            style = com.aeriotv.android.ui.settings.settingsRowTitleStyle(),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (valueName.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = valueName,
                style = com.aeriotv.android.ui.settings.settingsRowValueStyle(),
                color = MaterialTheme.colorScheme.textAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                // fill = false so a long action name ("Browse earlier
                // programs") shrinks to fit instead of running past the row,
                // while the slot name keeps first claim on the width.
                // Capped rather than weighted so the value and chevron sit
                // at the trailing edge, as tvOS; a long name still ellipsizes.
                modifier = Modifier.widthIn(max = 300.dp),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A labeled sub-section inside Additional Buttons ("While Watching", "In the
 * Guide"): bare text between the row cards, no card of its own.
 */
@Composable
private fun AdditionalSubheader(text: String) {
    Text(
        text = text,
        style = com.aeriotv.android.ui.settings.settingsRowValueStyle(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .layoutId(com.aeriotv.android.ui.settings.SettingsFooterLayoutId)
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
    )
}
