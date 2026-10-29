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

/** Guide Back and Hold Back (Logan 2026-10-15). The double-Back rules and the
 *  Radio Mode shortcuts stay fixed, and with a mini up on TV the mini owns
 *  Back, so the mini-only close action is not offered. */
private val GUIDE_BACK_SLOTS = listOf(RemoteSlot.BACK_SHORT, RemoteSlot.BACK_LONG)

private val GUIDE_BACK_CHOICES = listOf(
    GuideRemoteAction.BACK_LADDER,
    GuideRemoteAction.FOCUS_GROUP_PILLS,
    GuideRemoteAction.JUMP_TO_NOW,
    GuideRemoteAction.JUMP_TO_TOP,
    GuideRemoteAction.JUMP_TO_DAY,
    GuideRemoteAction.PAGE_UP,
    GuideRemoteAction.PAGE_DOWN,
    GuideRemoteAction.TIMELINE_BACK,
    GuideRemoteAction.TIMELINE_FORWARD,
    GuideRemoteAction.PROGRAM_INFO,
    GuideRemoteAction.OPEN_SEARCH,
    GuideRemoteAction.RESUME_PLAYER,
    GuideRemoteAction.NONE,
)

private fun guideActionChoices(slot: RemoteSlot): List<GuideRemoteAction> = buildList {
    if (slot in GUIDE_BACK_SLOTS) {
        addAll(GUIDE_BACK_CHOICES)
        return@buildList
    }
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
        RemoteSlot.BACK_SHORT -> "Back"
        RemoteSlot.BACK_LONG -> "Hold Back"
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
        GuideRemoteAction.BACK_LADDER -> "Back ladder (top channel, then close)"
        GuideRemoteAction.NONE -> "Do nothing"
    }

/** Guide actions offered for a learned button: the extended-button list plus
 *  the program actions that act on every press. */
private val LEARNED_GUIDE_CHOICES: List<GuideRemoteAction> = listOf(
    GuideRemoteAction.PAGE_UP,
    GuideRemoteAction.PAGE_DOWN,
    GuideRemoteAction.TIMELINE_BACK,
    GuideRemoteAction.TIMELINE_FORWARD,
    GuideRemoteAction.JUMP_TO_NOW,
    GuideRemoteAction.JUMP_TO_DAY,
    GuideRemoteAction.JUMP_TO_TOP,
    GuideRemoteAction.FOCUS_GROUP_PILLS,
    GuideRemoteAction.RESUME_PLAYER,
    GuideRemoteAction.PLAY,
    GuideRemoteAction.PROGRAM_INFO,
    GuideRemoteAction.PROGRAM_MENU,
    GuideRemoteAction.RECORD,
    GuideRemoteAction.NONE,
)

private fun learnedKeyName(keyCode: Int): String =
    com.aeriotv.android.core.remote.LearnedButtons.displayName(
        android.view.KeyEvent.keyCodeToString(keyCode),
        keyCode,
    )

/** Learn Button flow: wait for a press, then pick the two actions. */
private sealed interface LearnStep {
    data object Capturing : LearnStep
    data class PickPlayer(val keyCode: Int) : LearnStep
    data class PickGuide(val keyCode: Int, val player: PlayerRemoteAction) : LearnStep
    data class Edit(val keyCode: Int) : LearnStep
    data class EditPlayer(val keyCode: Int) : LearnStep
    data class EditGuide(val keyCode: Int) : LearnStep
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
    val learned by viewModel.learnedRemoteButtons.collectAsStateWithLifecycle(initialValue = emptyMap())
    var learnStep by remember { mutableStateOf<LearnStep?>(null) }
    fun saveLearned(b: com.aeriotv.android.core.remote.LearnedButton) {
        viewModel.setLearnedRemoteButtons((learned + (b.keyCode to b)).values)
    }
    val groupSelector by viewModel.guideGroupSelector.collectAsStateWithLifecycle(
        initialValue = "pills",
    )
    val tuneInMini by viewModel.guideTuneInMini.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val showRemoteHints by viewModel.showRemoteHints.collectAsStateWithLifecycle(
        initialValue = true,
    )
    val exitFocusExit by viewModel.exitDialogFocusExit.collectAsStateWithLifecycle(
        initialValue = false,
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
                    // TV only: the Exit AerioTV? confirm is a D-pad prompt.
                    if (com.aeriotv.android.ui.settings.rememberIsTvDevice()) {
                        SettingsToggleRow(
                            title = "Exit Prompt Starts on Exit",
                            subtitle = "When you press Back to leave AerioTV, Exit is highlighted instead of Cancel.",
                            checked = exitFocusExit,
                            onCheckedChange = { viewModel.setExitDialogFocusExit(it) },
                        )
                    }
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
                    GUIDE_BACK_SLOTS.forEach { slot ->
                        SlotRow(
                            slotName = slot.displayName,
                            valueName = map.guideAction(slot, groupSelector == "sidebar").displayName(groupSelector == "sidebar"),
                            subtitle = if (slot == RemoteSlot.BACK_SHORT) {
                                "Single press. Pressing twice quickly and the Radio Mode shortcuts are fixed."
                            } else null,
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
                    header = "Learned Buttons",
                    footer = "Teach AerioTV a button the lists above do not cover, such as the color, Guide, Info or F buttons on your remote. Choose Learn Button, press the button (Back cancels), then pick what it does while watching and in the guide.",
                ) {
                    learned.values.sortedBy { it.keyCode }.forEach { b ->
                        SlotRow(
                            slotName = learnedKeyName(b.keyCode),
                            valueName = b.player.displayName,
                            onClick = { learnStep = LearnStep.Edit(b.keyCode) },
                        )
                    }
                    SlotRow(
                        slotName = "Learn Button",
                        valueName = if (learnStep == LearnStep.Capturing) "Press a button now" else " ",
                        onClick = {
                            learnStep = if (learnStep == LearnStep.Capturing) null else LearnStep.Capturing
                        },
                    )
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

    when (val step = learnStep) {
        null -> Unit
        LearnStep.Capturing -> {
            androidx.compose.runtime.DisposableEffect(Unit) {
                com.aeriotv.android.core.remote.LearnedButtons.onCapture = { code ->
                    learnStep = LearnStep.PickPlayer(code)
                }
                onDispose { com.aeriotv.android.core.remote.LearnedButtons.onCapture = null }
            }
            // Inline, not a dialog: a dialog is its own window, and the
            // capture runs in MainActivity.dispatchKeyEvent. Back cancels.
            androidx.activity.compose.BackHandler { learnStep = null }
        }
        is LearnStep.PickPlayer, is LearnStep.EditPlayer -> {
            val code = if (step is LearnStep.PickPlayer) step.keyCode else (step as LearnStep.EditPlayer).keyCode
            val existing = learned[code]
            TvActionMenuDialog(
                title = "${learnedKeyName(code)}: While Watching",
                actions = PLAYER_ACTION_CHOICES.map { action ->
                    val current = existing?.player == action
                    TvMenuAction(label = if (current) "${action.displayName}  (current)" else action.displayName) {
                        if (step is LearnStep.PickPlayer) {
                            learnStep = LearnStep.PickGuide(code, action)
                        } else {
                            saveLearned((existing ?: com.aeriotv.android.core.remote.LearnedButton(code)).copy(player = action))
                            learnStep = null
                        }
                    }
                },
                onDismiss = { learnStep = null },
                guard = menuGuard,
                width = 340.dp,
            )
        }
        is LearnStep.PickGuide, is LearnStep.EditGuide -> {
            val code = if (step is LearnStep.PickGuide) step.keyCode else (step as LearnStep.EditGuide).keyCode
            val existing = learned[code]
            val sidebar = groupSelector == "sidebar"
            TvActionMenuDialog(
                title = "${learnedKeyName(code)}: In the Guide",
                actions = LEARNED_GUIDE_CHOICES.map { action ->
                    val current = existing?.guide == action
                    val name = action.displayName(sidebar)
                    TvMenuAction(label = if (current) "$name  (current)" else name) {
                        val base = existing ?: com.aeriotv.android.core.remote.LearnedButton(code)
                        saveLearned(
                            if (step is LearnStep.PickGuide) base.copy(player = step.player, guide = action)
                            else base.copy(guide = action),
                        )
                        learnStep = null
                    }
                },
                onDismiss = { learnStep = null },
                guard = menuGuard,
                width = 340.dp,
            )
        }
        is LearnStep.Edit -> {
            val b = learned[step.keyCode]
            if (b == null) {
                learnStep = null
            } else {
                val sidebar = groupSelector == "sidebar"
                TvActionMenuDialog(
                    title = learnedKeyName(b.keyCode),
                    actions = listOf(
                        TvMenuAction(label = "While Watching: ${b.player.displayName}") {
                            learnStep = LearnStep.EditPlayer(b.keyCode)
                        },
                        TvMenuAction(label = "In the Guide: ${b.guide.displayName(sidebar)}") {
                            learnStep = LearnStep.EditGuide(b.keyCode)
                        },
                        TvMenuAction(label = "Forget Button", destructive = true) {
                            viewModel.setLearnedRemoteButtons((learned - b.keyCode).values)
                            learnStep = null
                        },
                    ),
                    onDismiss = { learnStep = null },
                    guard = menuGuard,
                    width = 340.dp,
                )
            }
        }
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
    subtitle: String? = null,
) {
    SettingsRowContainer(onClick = onClick) {
        // Same title size and weight as every other Settings row.
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(
                text = slotName,
                style = com.aeriotv.android.ui.settings.settingsRowTitleStyle(),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
