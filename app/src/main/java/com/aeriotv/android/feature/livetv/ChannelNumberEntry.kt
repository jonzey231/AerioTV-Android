package com.aeriotv.android.feature.livetv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeriotv.android.core.data.M3UChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * GH #71: direct channel-number entry from a TV remote's digit keys, shared
 * by the live player (tunes) and the guide (moves focus).
 *
 * Digits accumulate (up to [MAX_DIGITS]) and the number commits on OK /
 * Enter only (Logan 2026-09-02: no auto-commit; OK while digits are
 * pending enters the number and never clicks the focused cell, release
 * included). An entry left alone for [IDLE_CLEAR_MS] clears itself.
 * Back / Escape cancels a pending entry. The host's
 * `onCommit` resolves the number (see [resolveChannelNumber]) and returns
 * false when nothing matched, which shows "No channel <n>" for
 * [MESSAGE_MS]. TV only: the hosts gate [onKeyEvent] on their TV form check.
 */
@Stable
class ChannelNumberEntryState(
    private val scope: CoroutineScope,
    private val onCommit: (String) -> Boolean,
) {
    var digits: String by mutableStateOf("")
        private set
    var message: String? by mutableStateOf(null)
        private set

    private var commitJob: Job? = null
    private var messageJob: Job? = null
    // A Back press that cancelled an entry must also eat its release: the
    // activity routes Back through the OnBackPressedDispatcher on KeyUp.
    private var swallowBackUp = false
    // OK commits on the press and clears the digits; its release must not
    // reach the focused cell's clickable (that tuned the highlighted row).
    private var swallowOkUp = false

    val isVisible: Boolean get() = digits.isNotEmpty() || message != null
    val hasPendingDigits: Boolean get() = digits.isNotEmpty()

    /**
     * Feed a key event. Returns true when the event was consumed: every
     * digit press (both actions, so the release cannot click anything
     * beneath), and OK / Back only while digits are pending. Everything
     * else returns false so the host's own mappings keep working.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        val down = event.type == KeyEventType.KeyDown
        val digit = digitFor(native.keyCode)
        if (digit != null) {
            if (down && native.repeatCount == 0) append(digit)
            return true
        }
        val isBack = native.keyCode == AndroidKeyEvent.KEYCODE_BACK ||
            native.keyCode == AndroidKeyEvent.KEYCODE_ESCAPE
        if (isBack && !down && swallowBackUp) {
            swallowBackUp = false
            return true
        }
        val isOk = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
            native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
            native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
        if (isOk && !down && swallowOkUp) {
            swallowOkUp = false
            return true
        }
        if (digits.isEmpty()) return false
        return when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            AndroidKeyEvent.KEYCODE_ENTER,
            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
            -> {
                if (down && native.repeatCount == 0) { commit(); swallowOkUp = true }
                true
            }
            AndroidKeyEvent.KEYCODE_BACK,
            AndroidKeyEvent.KEYCODE_ESCAPE,
            -> {
                if (down) { cancel(); swallowBackUp = true }
                true
            }
            else -> false
        }
    }

    private fun append(digit: Char) {
        if (digits.length >= MAX_DIGITS) return
        messageJob?.cancel()
        message = null
        digits += digit
        commitJob?.cancel()
        commitJob = scope.launch {
            delay(IDLE_CLEAR_MS)
            cancel()
        }
    }

    fun commit() {
        commitJob?.cancel()
        val entry = digits
        digits = ""
        if (entry.isEmpty()) return
        if (!onCommit(entry)) showMessage("No channel ${normalizeChannelNumber(entry)}")
    }

    fun cancel() {
        commitJob?.cancel()
        messageJob?.cancel()
        digits = ""
        message = null
    }

    private fun showMessage(text: String) {
        messageJob?.cancel()
        message = text
        messageJob = scope.launch {
            delay(MESSAGE_MS)
            message = null
        }
    }

    companion object {
        const val MAX_DIGITS = 4
        const val IDLE_CLEAR_MS = 6_000L
        const val MESSAGE_MS = 1_500L

        private fun digitFor(keyCode: Int): Char? = when (keyCode) {
            in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 ->
                ('0' + (keyCode - AndroidKeyEvent.KEYCODE_0))
            in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 ->
                ('0' + (keyCode - AndroidKeyEvent.KEYCODE_NUMPAD_0))
            else -> null
        }
    }
}

/** Leading zeros dropped ("007" -> "7"); a run of zeros stays "0". */
fun normalizeChannelNumber(raw: String): String = raw.trim().trimStart('0').ifEmpty { "0" }

/**
 * Resolve a typed number against [channels]: a `channelNumber` match first
 * (string compare after trimming leading zeros), else the 1-based position
 * in the list. Null when neither matches.
 */
fun resolveChannelNumber(entry: String, channels: List<M3UChannel>): M3UChannel? {
    val target = normalizeChannelNumber(entry)
    channels.firstOrNull { ch ->
        ch.channelNumber?.let { normalizeChannelNumber(it) == target } == true
    }?.let { return it }
    val index = target.toIntOrNull() ?: return null
    return channels.getOrNull(index - 1)
}

@Composable
fun rememberChannelNumberEntry(onCommit: (String) -> Boolean): ChannelNumberEntryState {
    val scope = rememberCoroutineScope()
    val latestOnCommit by rememberUpdatedState(onCommit)
    return remember(scope) { ChannelNumberEntryState(scope) { latestOnCommit(it) } }
}

/** Small top-right readout: the digits typed so far, or the no-match notice. */
@Composable
fun ChannelNumberEntryOverlay(state: ChannelNumberEntryState, modifier: Modifier = Modifier) {
    if (!state.isVisible) return
    Box(
        modifier = modifier
            .padding(24.dp)
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = state.message ?: state.digits,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = if (state.message == null) FontFamily.Monospace else FontFamily.Default,
        )
    }
}
