package com.aeriotv.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.profiles.DispatcharrProfile
import com.aeriotv.android.core.profiles.PinDirection
import com.aeriotv.android.core.profiles.PinRules
import com.aeriotv.android.core.profiles.PinType
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.settingsRowCard
import kotlinx.coroutines.launch

/**
 * Profile PIN UI (phase 2a, 2026-10-17). Copy: "Set PIN", "Change PIN",
 * "Remove PIN", "PIN type", "Numbers", "Directions", "Enter PIN",
 * "Confirm PIN", "PINs do not match", "Wrong PIN", "Try again in {n}
 * seconds", "Enter PIN for {name}".
 */
fun pinCheckMessage(r: ProfileManager.PinCheck): String? = when (r) {
    ProfileManager.PinCheck.Ok -> null
    ProfileManager.PinCheck.Wrong -> "Wrong PIN"
    is ProfileManager.PinCheck.Locked -> "Try again in ${r.seconds} seconds"
}

/**
 * One PIN entry. [onEntered] gets the sequence and returns an error to show
 * (the entry clears) or null when accepted (the caller closes the dialog).
 * [onDismiss] null = cannot be dismissed (the launch gate).
 */
@Composable
fun PinEntryDialog(
    title: String,
    type: PinType,
    onDismiss: (() -> Unit)?,
    onEntered: (List<String>) -> String?,
    initialError: String? = null,
    /** Seconds left on the wrong-PIN backoff; drives the ticking label. */
    lockSeconds: (() -> Int)? = null,
    /** Start focus on Clear instead of "1" (a step that follows another pad). */
    focusClear: Boolean = false,
    /** Entering an existing PIN: its stored length (null = unknown, 6 dots). */
    pinLength: Int? = null,
    /** Setting a new PIN: 4 dots that grow to 6 as digits are typed. */
    setting: Boolean = false,
) {
    val fixed = pinLength?.coerceIn(PinRules.MIN_LENGTH, PinRules.MAX_LENGTH)
    val minLen = fixed ?: PinRules.MIN_LENGTH
    val maxLen = fixed ?: PinRules.MAX_LENGTH
    val isTv = rememberIsTvDevice()
    val entries = remember { mutableStateListOf<String>() }
    // A key press that started on the previous dialog (OK on Enter PIN)
    // must not land here: presses are ignored for 300 ms after opening.
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    fun ready() = android.os.SystemClock.uptimeMillis() - openedAt >= INPUT_GUARD_MS
    var error by remember { mutableStateOf(initialError) }
    var submits by remember { mutableStateOf(0) }

    fun submit() {
        if (!ready()) return
        if (entries.size < minLen) return
        val msg = onEntered(entries.toList())
        entries.clear()
        error = msg
        submits++
    }
    // Phase 2b: "Try again in N seconds" ticks every second, then clears.
    LaunchedEffect(submits) {
        val left = lockSeconds ?: return@LaunchedEffect
        var n = left()
        if (n <= 0) return@LaunchedEffect
        while (n > 0) {
            error = "Try again in $n seconds"
            kotlinx.coroutines.delay(1_000L)
            n = left()
        }
        error = null
    }
    fun add(v: String) {
        if (!ready()) return
        if (entries.size < maxLen) { entries.add(v); error = null }
    }
    fun back(): Boolean {
        if (entries.isNotEmpty()) { entries.clear(); return true }
        onDismiss?.invoke()
        return true
    }

    val rootFocus = remember { FocusRequester() }
    Dialog(
        onDismissRequest = { back() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (onDismiss == null) 0.92f else 0.6f))
                .onPreviewKeyEvent { ev ->
                    val down = ev.type == KeyEventType.KeyDown
                    when (ev.key) {
                        Key.Back -> { if (!down) back(); true }
                        Key.Zero, Key.NumPad0 -> { if (down && type == PinType.Numbers) add("0"); type == PinType.Numbers }
                        Key.One, Key.NumPad1 -> { if (down && type == PinType.Numbers) add("1"); type == PinType.Numbers }
                        Key.Two, Key.NumPad2 -> { if (down && type == PinType.Numbers) add("2"); type == PinType.Numbers }
                        Key.Three, Key.NumPad3 -> { if (down && type == PinType.Numbers) add("3"); type == PinType.Numbers }
                        Key.Four, Key.NumPad4 -> { if (down && type == PinType.Numbers) add("4"); type == PinType.Numbers }
                        Key.Five, Key.NumPad5 -> { if (down && type == PinType.Numbers) add("5"); type == PinType.Numbers }
                        Key.Six, Key.NumPad6 -> { if (down && type == PinType.Numbers) add("6"); type == PinType.Numbers }
                        Key.Seven, Key.NumPad7 -> { if (down && type == PinType.Numbers) add("7"); type == PinType.Numbers }
                        Key.Eight, Key.NumPad8 -> { if (down && type == PinType.Numbers) add("8"); type == PinType.Numbers }
                        Key.Nine, Key.NumPad9 -> { if (down && type == PinType.Numbers) add("9"); type == PinType.Numbers }
                        Key.Backspace -> { if (down && entries.isNotEmpty()) entries.removeAt(entries.lastIndex); true }
                        else -> {
                            // TV Directions: the D-pad itself is the input.
                            if (isTv && type == PinType.Directions) {
                                val dir = when (ev.key) {
                                    Key.DirectionUp -> PinDirection.Up
                                    Key.DirectionDown -> PinDirection.Down
                                    Key.DirectionLeft -> PinDirection.Left
                                    Key.DirectionRight -> PinDirection.Right
                                    else -> null
                                }
                                when {
                                    dir != null -> { if (down) add(dir.name); true }
                                    ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter -> {
                                        if (!down) submit(); true
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.padding(16.dp).widthIn(max = 360.dp),
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    PinDots(
                        filled = entries.size,
                        count = when {
                            fixed != null -> fixed
                            setting -> entries.size.coerceIn(PinRules.MIN_LENGTH, PinRules.MAX_LENGTH)
                            else -> PinRules.MAX_LENGTH
                        },
                    )
                    Text(
                        error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.height(18.dp),
                    )
                    when {
                        type == PinType.Numbers -> NumberPad(
                            onDigit = { add(it) },
                            onClear = { if (ready()) entries.clear() },
                            onOk = { submit() },
                            okEnabled = entries.size >= minLen,
                            firstFocus = rootFocus,
                            focusClear = focusClear,
                        )
                        isTv -> {
                            // Focus target for the raw D-pad capture.
                            Box(
                                Modifier
                                    .focusRequester(rootFocus)
                                    .focusable()
                                    .padding(8.dp),
                            ) {
                                Text(
                                    "Up, Down, Left, Right. Select to confirm, Back to clear.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        else -> DirectionPad(
                            onDirection = { add(it.name) },
                            onClear = { if (ready()) entries.clear() },
                            onOk = { submit() },
                            okEnabled = entries.size >= minLen,
                            firstFocus = rootFocus,
                            focusClear = focusClear,
                        )
                    }
                    if (onDismiss != null && !isTv) {
                        SettingsDialogTextButton(label = "Cancel", onClick = onDismiss)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
}

@Composable
private fun PinDots(filled: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) { i ->
            val on = i < filled
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
        }
    }
}

@Composable
private fun PadKey(
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(64.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(
                if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val tint = when {
            focused -> MaterialTheme.colorScheme.onPrimary
            enabled -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        }
        if (icon != null) Icon(icon, contentDescription = contentDescription, tint = tint)
        else Text(label.orEmpty(), color = tint, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NumberPad(
    onDigit: (String) -> Unit,
    onClear: () -> Unit,
    onOk: () -> Unit,
    okEnabled: Boolean,
    firstFocus: FocusRequester,
    focusClear: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { d ->
                    PadKey(
                        modifier = if (d == "1" && !focusClear) Modifier.focusRequester(firstFocus) else Modifier,
                        label = d,
                        onClick = { onDigit(d) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PadKey(
                modifier = if (focusClear) Modifier.focusRequester(firstFocus) else Modifier,
                label = "Clear",
                onClick = onClear,
            )
            PadKey(label = "0", onClick = { onDigit("0") })
            PadKey(label = "OK", enabled = okEnabled, onClick = onOk)
        }
    }
}

@Composable
private fun DirectionPad(
    onDirection: (PinDirection) -> Unit,
    onClear: () -> Unit,
    onOk: () -> Unit,
    okEnabled: Boolean,
    firstFocus: FocusRequester,
    focusClear: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PadKey(
            modifier = if (focusClear) Modifier else Modifier.focusRequester(firstFocus),
            icon = Icons.Filled.KeyboardArrowUp,
            contentDescription = "Up",
            onClick = { onDirection(PinDirection.Up) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PadKey(icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Left", onClick = { onDirection(PinDirection.Left) })
            PadKey(label = "OK", enabled = okEnabled, onClick = onOk)
            PadKey(icon = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Right", onClick = { onDirection(PinDirection.Right) })
        }
        PadKey(icon = Icons.Filled.KeyboardArrowDown, contentDescription = "Down", onClick = { onDirection(PinDirection.Down) })
        Spacer(Modifier.height(2.dp))
        Box(if (focusClear) Modifier.focusRequester(firstFocus) else Modifier) {
            SettingsDialogTextButton(label = "Clear", onClick = onClear)
        }
    }
}

private const val INPUT_GUARD_MS = 300L

/**
 * Set PIN / Change PIN: (current PIN when a non-admin changes their own),
 * PIN type, Enter PIN, Confirm PIN. "PINs do not match" restarts the entry.
 */
@Composable
fun SetPinFlow(
    playlistId: String,
    profile: DispatcharrProfile,
    vm: ProfilesViewModel,
    onClose: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var needsCurrent by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(profile.id) { needsCurrent = vm.needsCurrentPin(playlistId, profile.id) }
    val nc = needsCurrent ?: return
    var current by remember { mutableStateOf<List<String>?>(null) }
    var type by remember { mutableStateOf<PinType?>(null) }
    var first by remember { mutableStateOf<List<String>?>(null) }
    var mismatch by remember { mutableStateOf<String?>(null) }
    val title = if (vm.hasPin(profile.id)) "Change PIN" else "Set PIN"

    when {
        nc && current == null -> PinEntryDialog(
            title = "Enter PIN",
            type = vm.pinType(profile.id) ?: PinType.Numbers,
            pinLength = vm.pinLength(profile.id),
            onDismiss = { onClose(null) },
            onEntered = { seq ->
                val r = vm.verifyPin(profile.id, seq)
                if (r == ProfileManager.PinCheck.Ok) { current = seq; null } else pinCheckMessage(r)
            },
            lockSeconds = { vm.pinLockSeconds(profile.id) },
        )
        type == null -> AlertDialog(
            onDismissRequest = { onClose(null) },
            title = { Text("PIN type") },
            text = {
                Column {
                    PinType.entries.forEach { t ->
                        ChoiceRow(label = t.label, onClick = { type = t })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = { onClose(null) }) },
        )
        first == null -> PinEntryDialog(
            title = "$title: Enter PIN",
            type = type!!,
            onDismiss = { onClose(null) },
            initialError = mismatch,
            focusClear = true,
            setting = true,
            onEntered = { seq -> first = seq; mismatch = null; null },
        )
        else -> PinEntryDialog(
            title = "Confirm PIN",
            type = type!!,
            pinLength = first!!.size,
            focusClear = true,
            onDismiss = { onClose(null) },
            onEntered = { seq ->
                if (seq != first) {
                    mismatch = "PINs do not match"
                    first = null
                    null
                } else {
                    val t = type!!
                    scope.launch {
                        onClose(vm.setPin(playlistId, profile.id, t, seq, current).exceptionOrNull()?.message)
                    }
                    null
                }
            },
        )
    }
}

/** Remove PIN: a non-admin enters their current PIN first. */
@Composable
fun RemovePinFlow(
    playlistId: String,
    profile: DispatcharrProfile,
    vm: ProfilesViewModel,
    onClose: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    vm.pinRemovalBlock(playlistId, profile.id)?.let { reason ->
        AlertDialog(
            onDismissRequest = { onClose(null) },
            title = { Text("Remove PIN") },
            text = { Text(reason) },
            confirmButton = { SettingsDialogTextButton(label = "OK", onClick = { onClose(null) }) },
        )
        return
    }
    var needsCurrent by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(profile.id) {
        val n = vm.needsCurrentPin(playlistId, profile.id)
        if (!n) onClose(vm.removePin(playlistId, profile.id, null).exceptionOrNull()?.message)
        else needsCurrent = true
    }
    if (needsCurrent != true) return
    PinEntryDialog(
        title = "Remove PIN",
        type = vm.pinType(profile.id) ?: PinType.Numbers,
        pinLength = vm.pinLength(profile.id),
        onDismiss = { onClose(null) },
        onEntered = { seq ->
            val r = vm.verifyPin(profile.id, seq)
            if (r != ProfileManager.PinCheck.Ok) {
                pinCheckMessage(r)
            } else {
                scope.launch { onClose(vm.removePin(playlistId, profile.id, seq).exceptionOrNull()?.message) }
                null
            }
        },
        lockSeconds = { vm.pinLockSeconds(profile.id) },
    )
}

/** Dialog initial focus: retried because the dialog window takes focus late. */
internal suspend fun focusFirst(fr: FocusRequester) {
    repeat(10) {
        kotlinx.coroutines.delay(50L)
        if (runCatching { fr.requestFocus() }.isSuccess) return
    }
}

@Composable
internal fun ChoiceRow(
    label: String,
    onClick: () -> Unit,
    checked: Boolean? = null,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    // D-pad OK fires only on a KeyUp whose KeyDown this row also saw. The
    // release of the long press that opened a row menu arrives as a bare
    // KeyUp (its KeyDown went to the launching row), so it cannot pick the
    // first item (same latch as TvActionMenuDialog).
    var okLatched by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) okLatched = false
            }
            .onPreviewKeyEvent { ev ->
                val isOk = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                if (!isOk) return@onPreviewKeyEvent false
                when (ev.type) {
                    KeyEventType.KeyDown -> { if (ev.nativeKeyEvent.repeatCount == 0) okLatched = true; true }
                    KeyEventType.KeyUp -> {
                        val latched = okLatched
                        okLatched = false
                        if (latched) onClick()
                        true
                    }
                    else -> false
                }
            }
            .settingsRowCard(focused)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked != null) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.size(8.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Admin only: assign the user's Dispatcharr Channel Profiles. */
@Composable
fun ChannelProfilesDialog(
    playlistId: String,
    profile: DispatcharrProfile,
    vm: ProfilesViewModel,
    onClose: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<ProfileManager.ChannelProfileAssignment?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<Int>() }
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(data != null) { if (data != null) focusFirst(firstRow) }
    LaunchedEffect(profile.id) {
        vm.channelProfileAssignment(playlistId, profile.id).fold(
            { data = it; picked.clear(); picked.addAll(it.assigned) },
            { error = it.message ?: "Could not load channel profiles" },
        )
    }
    AlertDialog(
        onDismissRequest = { onClose(null) },
        title = { Text("Channel Profiles") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val d = data
                when {
                    d == null && error == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    d != null -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        item("all") { ChoiceRow(label = "All", checked = picked.isEmpty(), onClick = { picked.clear() }, modifier = Modifier.focusRequester(firstRow)) }
                        items(d.choices, key = { it.id }) { c ->
                            ChoiceRow(
                                label = c.name,
                                checked = c.id in picked,
                                onClick = { if (c.id in picked) picked.remove(c.id) else picked.add(c.id) },
                            )
                        }
                    }
                }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            val d = data
            if (d != null) {
                SettingsDialogTextButton(label = "Save", enabled = !busy, onClick = {
                    busy = true; error = null
                    scope.launch {
                        vm.saveChannelProfiles(playlistId, profile.id, d.userId, picked.toList()).fold(
                            { onClose(null) },
                            { busy = false; error = it.message ?: "Could not save" },
                        )
                    }
                })
            }
        },
        dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = { onClose(null) }) },
    )
}

/** App launch: the restored profile is locked; nothing shows until its PIN. */
@Composable
fun ProfileLaunchGate(manager: ProfileManager) {
    val gate by manager.launchGate.collectAsStateWithLifecycle()
    val p = gate ?: return
    PinEntryDialog(
        title = "Enter PIN for ${p.displayName}",
        type = manager.pinType(p.id) ?: PinType.Numbers,
        pinLength = manager.pinLength(p.id),
        onDismiss = null,
        onEntered = { seq -> pinCheckMessage(manager.unlockLaunchGate(seq)) },
        lockSeconds = { manager.pinLockSeconds(p.id) },
    )
}
