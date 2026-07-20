package com.aeriotv.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import com.aeriotv.android.ui.adaptive.adaptiveFormWidth

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
    RemoteSlot.OK_LONG, RemoteSlot.LEFT_LONG, RemoteSlot.RIGHT_LONG,
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
    PlayerRemoteAction.NONE,
)

private fun guideActionChoices(slot: RemoteSlot): List<GuideRemoteAction> = buildList {
    add(GuideRemoteAction.TIMELINE_BACK)
    add(GuideRemoteAction.TIMELINE_FORWARD)
    add(GuideRemoteAction.PAGE_UP)
    add(GuideRemoteAction.PAGE_DOWN)
    add(GuideRemoteAction.JUMP_TO_NOW)
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
        RemoteSlot.OK_SHORT -> "OK"
        RemoteSlot.OK_LONG -> "OK (hold)"
        RemoteSlot.UP_SHORT -> "Up"
        RemoteSlot.UP_LONG -> "Up (hold)"
        RemoteSlot.DOWN_SHORT -> "Down"
        RemoteSlot.DOWN_LONG -> "Down (hold)"
        RemoteSlot.LEFT_SHORT -> "Left"
        RemoteSlot.LEFT_LONG -> "Left (hold)"
        RemoteSlot.RIGHT_SHORT -> "Right"
        RemoteSlot.RIGHT_LONG -> "Right (hold)"
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

private val GuideRemoteAction.displayName: String
    get() = when (this) {
        GuideRemoteAction.PAGE_UP -> "Page channels up"
        GuideRemoteAction.PAGE_DOWN -> "Page channels down"
        GuideRemoteAction.TIMELINE_BACK -> "Browse earlier programs"
        GuideRemoteAction.TIMELINE_FORWARD -> "Browse later programs"
        GuideRemoteAction.JUMP_TO_NOW -> "Jump to now"
        GuideRemoteAction.JUMP_TO_TOP -> "Jump to top channel"
        GuideRemoteAction.FOCUS_GROUP_PILLS -> "Go to group pills"
        GuideRemoteAction.RESUME_PLAYER -> "Return to player"
        GuideRemoteAction.CLOSE_MINI_PLAYER -> "Close mini player"
        GuideRemoteAction.PROGRAM_INFO -> "Program menu"
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
    val menuGuard = rememberTvMenuGuard()

    fun saveEdited(newMap: RemoteControlMap) {
        viewModel.setRemoteControlMap(newMap.copy(preset = RemotePreset.CUSTOM))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "Remote Control", onBack = onBack)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .adaptiveFormWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettingsSection(
                    header = "While Watching",
                    footer = "What each button does while a channel is playing fullscreen. Changes apply immediately. Back always navigates and cannot be reassigned.",
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
                    footer = "What each button does while browsing the guide. Short arrow presses always navigate.",
                ) {
                    GUIDE_SLOTS.forEach { slot ->
                        SlotRow(
                            slotName = slot.displayName,
                            valueName = map.guideAction(slot).displayName,
                            onClick = { editingGuideSlot = slot },
                        )
                    }
                }

                SettingsSection(
                    header = "Additional Buttons",
                    footer = "Applies when your remote has these buttons (many Bluetooth and Shield remotes do; the stock Google TV remote does not).",
                ) {
                    PLAYER_EXTENDED_SLOTS.forEach { slot ->
                        SlotRow(
                            slotName = "${slot.displayName} (watching)",
                            valueName = map.playerAction(slot).displayName,
                            onClick = { editingPlayerSlot = slot },
                        )
                    }
                    GUIDE_EXTENDED_SLOTS.forEach { slot ->
                        SlotRow(
                            slotName = "${slot.displayName} (guide)",
                            valueName = map.guideAction(slot).displayName,
                            onClick = { editingGuideSlot = slot },
                        )
                    }
                }

                SettingsSection(
                    header = "Reset",
                    footer = "Restore every button to the standard AerioTV scheme.",
                ) {
                    SlotRow(
                        slotName = "Reset to Defaults",
                        valueName = "",
                        onClick = { showResetConfirm = true },
                    )
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
        )
    }

    editingGuideSlot?.let { slot ->
        TvActionMenuDialog(
            title = slot.displayName,
            actions = guideActionChoices(slot).map { action ->
                val current = map.guideAction(slot) == action
                TvMenuAction(
                    label = if (current) "${action.displayName}  (current)" else action.displayName,
                ) {
                    saveEdited(map.copy(guide = map.guide + (slot to action)))
                    editingGuideSlot = null
                }
            },
            onDismiss = { editingGuideSlot = null },
            guard = menuGuard,
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
        Text(
            text = slotName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (valueName.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = valueName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
