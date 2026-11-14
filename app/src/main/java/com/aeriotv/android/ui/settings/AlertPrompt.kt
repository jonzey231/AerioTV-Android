package com.aeriotv.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** What an alert button does, as Apple's alert action roles name it. */
enum class AlertRole {
    /**
     * Apple's preferred action (preferredAction, or the filled button of
     * Apple's own prompt): a filled button, first in a stack, default focus on
     * TV. An Apple alert with no preferred action has no Primary here either,
     * so it shows no filled button (owner 2026-11-12).
     */
    Primary,

    /** Any other action: a tonal button. */
    Default,

    /** Deletes or replaces something: a tonal button in the error color (Apple's red). */
    Destructive,

    /** Closes the alert and does nothing: left of two, last in a stack. */
    Cancel,
}

/** One button of an [AlertPrompt]. */
data class AlertAction(
    val label: String,
    val role: AlertRole = AlertRole.Default,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * How an alert lays out its buttons (owner 2026-11-12: the same layout as
 * the Apple alert, in Material 3). Pure, for the tests.
 *
 * Phones and tablets follow iOS 26 alerts: one button full width; two side
 * by side at equal width, Cancel on the left; three or more stacked full
 * width, the primary first and Cancel last. TV follows the Apple TV alert:
 * always stacked full width, Cancel last, and the primary takes the
 * default focus.
 */
object AlertLayout {
    enum class Arrangement { Single, SideBySide, Stacked }

    /** Which Apple presentation a prompt matches. */
    enum class Kind {
        /** A system alert: the rules above. */
        Alert,

        /** Apple's own prompt with one equal-width row of buttons, on every device. */
        Row,

        /**
         * Apple's own prompt row while every label fits on one line in it
         * ([rowWeights]); otherwise stacked full width, the primary first
         * and Cancel last (Apple PromptButtons, dc7cae2). TV keeps the row.
         */
        RowWhileFits,

        /** An action sheet: always stacked, Cancel last. */
        Sheet,
    }

    /** [labelsFit]: for [Kind.RowWhileFits], whether a row can give every label one line ([rowWeights] is not null). */
    fun arrangement(count: Int, isTv: Boolean, kind: Kind = Kind.Alert, labelsFit: Boolean = true): Arrangement = when {
        count <= 1 -> Arrangement.Single
        kind == Kind.Row -> Arrangement.SideBySide
        kind == Kind.RowWhileFits -> if (isTv || labelsFit) Arrangement.SideBySide else Arrangement.Stacked
        kind == Kind.Sheet -> Arrangement.Stacked
        isTv -> Arrangement.Stacked
        count == 2 -> Arrangement.SideBySide
        else -> Arrangement.Stacked
    }

    /**
     * Apple's ViewThatFits(in: .horizontal) for an equal-width row of
     * buttons: it holds while every button's one-line width fits its share of
     * [available] once the [gap]s between them are taken out. Any one unit.
     */
    fun equalRowFits(buttonWidths: List<Float>, available: Float, gap: Float): Boolean {
        if (buttonWidths.isEmpty()) return true
        val share = (available - gap * (buttonWidths.size - 1)) / buttonWidths.size
        return buttonWidths.all { it <= share }
    }

    /** The same for a row of buttons at their own one-line widths, side by side. */
    fun naturalRowFits(buttonWidths: List<Float>, available: Float, gap: Float): Boolean =
        buttonWidths.sum() + gap * (buttonWidths.size - 1).coerceAtLeast(0) <= available

    /**
     * Row weights that give every button's label one line, in [buttonWidths]
     * order, or null when no row can and the buttons stack. Equal while
     * every label fits an equal share (the row's usual look); otherwise in
     * proportion to the labels while they and the [gap]s fit [available], as
     * Apple's row of pills at their own widths still fits there (an iPad
     * keeps its row at every Text Size). Any one unit.
     */
    fun rowWeights(buttonWidths: List<Float>, available: Float, gap: Float): List<Float>? = when {
        equalRowFits(buttonWidths, available, gap) -> buttonWidths.map { 1f }
        naturalRowFits(buttonWidths, available, gap) -> buttonWidths
        else -> null
    }

    /**
     * The buttons in the order they show. Side by side: Cancel first (left),
     * then the rest in declared order. Stacked: the primary first, the rest
     * in declared order, Cancel last.
     */
    fun <T> ordered(actions: List<T>, role: (T) -> AlertRole, arrangement: Arrangement): List<T> {
        val cancels = actions.filter { role(it) == AlertRole.Cancel }
        val others = actions.filter { role(it) != AlertRole.Cancel }
        return when (arrangement) {
            Arrangement.SideBySide -> cancels + others
            Arrangement.Stacked, Arrangement.Single ->
                others.filter { role(it) == AlertRole.Primary } + others.filter { role(it) != AlertRole.Primary } + cancels
        }
    }

    /** The button that takes focus when the alert opens (an index into [ordered]): the primary, else the first that is not Cancel. */
    fun <T> defaultFocus(ordered: List<T>, role: (T) -> AlertRole): Int {
        val primary = ordered.indexOfFirst { role(it) == AlertRole.Primary }
        if (primary >= 0) return primary
        val action = ordered.indexOfFirst { role(it) != AlertRole.Cancel }
        return if (action >= 0) action else 0
    }

    /** TV alert card width: the Apple TV alert is about 26% of the screen (500 of 1920 pt, 0.5 dp per pt). */
    const val TV_ALERT_WIDTH_DP = 250

    /** TV capsule height and the gap between capsules (Apple TV alert: about 72 and 10 of 2160 px). */
    const val TV_BUTTON_HEIGHT_DP = 36
    const val TV_BUTTON_GAP_DP = 6

    /** The Material 3 button each role draws as. */
    enum class Style { Filled, Tonal, TonalError }

    /** Phones and tablets: the primary filled, others tonal, a destructive action in the error color. TV: see [tvStyle]. */
    fun style(role: AlertRole): Style = when (role) {
        AlertRole.Primary -> Style.Filled
        AlertRole.Destructive -> Style.TonalError
        AlertRole.Default, AlertRole.Cancel -> Style.Tonal
    }

    /** TV, like the Apple TV alert: every button tonal, the focused one filled (a destructive one keeps the error color). */
    fun tvStyle(role: AlertRole, focused: Boolean): Style = when {
        focused -> Style.Filled
        role == AlertRole.Destructive -> Style.TonalError
        else -> Style.Tonal
    }
}

/**
 * An alert-style prompt: title and message on the left (as on iPhone and
 * Apple TV), then Material 3 buttons laid out by [AlertLayout]. The app's
 * Text Size applies. Back and a tap outside run [onDismissRequest] (the
 * prompt's Cancel), unless [properties] says otherwise.
 *
 * [focusButton]: whether a button takes focus when it opens
 * ([AlertLayout.defaultFocus]); by default on TV only. False when the
 * prompt focuses its own content (a choice card).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertPrompt(
    onDismissRequest: () -> Unit,
    title: String?,
    actions: List<AlertAction>,
    message: String? = null,
    /** Content under the message (a field, a list); the whole body scrolls when tall. */
    content: (@Composable () -> Unit)? = null,
    /** Apple centers a few custom prompts ("This playlist already exists"); alerts are left-aligned. */
    centered: Boolean = false,
    /** Which Apple presentation this prompt matches (an alert, Apple's own one-row prompt, or an action sheet). */
    kind: AlertLayout.Kind = AlertLayout.Kind.Alert,
    focusButton: Boolean? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val isTv = rememberIsTvDevice()
    // An Apple TV system alert (not Apple's own one-row prompt): its narrow card and title.
    val tvAlert = isTv && kind != AlertLayout.Kind.Row && kind != AlertLayout.Kind.RowWhileFits
    val focus = remember { FocusRequester() }
    val align = if (centered) TextAlign.Center else TextAlign.Start
    androidx.compose.material3.BasicAlertDialog(onDismissRequest = onDismissRequest, properties = properties) {
        com.aeriotv.android.ui.scale.ProvideAppTextScale {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                // TV: the Apple TV alert's width (about a quarter of the screen); Material's range elsewhere.
                modifier = if (tvAlert) Modifier.width(AlertLayout.TV_ALERT_WIDTH_DP.dp) else Modifier.sizeIn(minWidth = 280.dp, maxWidth = 560.dp),
            ) {
                Column(Modifier.padding(24.dp)) {
                    title?.let {
                        Text(
                            it,
                            // TV: Apple TV's bold alert title, a step above the body; Material's dialog title elsewhere.
                            style = if (tvAlert) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = align,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (message != null || content != null) {
                        if (title != null) Spacer(Modifier.height(16.dp))
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(textAlign = align)) {
                                    message?.let { Text(it, modifier = Modifier.fillMaxWidth()) }
                                    if (message != null && content != null) Spacer(Modifier.height(12.dp))
                                    content?.invoke()
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                        // RowWhileFits: each label at its one-line width, in the
                        // button's own style and side padding, against the row
                        // (Apple's ViewThatFits). TV keeps the equal row.
                        val weights: List<Float>? = if (kind == AlertLayout.Kind.RowWhileFits && !isTv) {
                            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                            val density = androidx.compose.ui.platform.LocalDensity.current
                            val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                            val pad = ButtonDefaults.ContentPadding
                            val sides = with(density) {
                                (pad.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) +
                                    pad.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)).toPx()
                            }
                            val widths = actions.map { measurer.measure(it.label, style, maxLines = 1, softWrap = false).size.width + sides }
                            AlertLayout.rowWeights(widths, constraints.maxWidth.toFloat(), with(density) { 8.dp.toPx() })
                        } else actions.map { 1f }
                        val weightOf = { a: AlertAction -> weights?.getOrNull(actions.indexOfFirst { it === a }) ?: 1f }
                        val arrangement = AlertLayout.arrangement(actions.size, isTv, kind, labelsFit = weights != null)
                        val shown = AlertLayout.ordered(actions, { it.role }, arrangement)
                        val focusIndex = AlertLayout.defaultFocus(shown, { it.role })
                        val button: @Composable (Int, AlertAction, Modifier) -> Unit = { i, a, m ->
                            AlertButton(a, isTv, if (i == focusIndex) m.focusRequester(focus) else m)
                        }
                        val gap = if (tvAlert) AlertLayout.TV_BUTTON_GAP_DP.dp else 8.dp
                        // TV: no touch-target padding around the capsules (a remote has no touch), so they sit as close as Apple's.
                        CompositionLocalProvider(
                            androidx.compose.material3.LocalMinimumInteractiveComponentSize provides if (isTv) 0.dp else androidx.compose.material3.LocalMinimumInteractiveComponentSize.current,
                        ) {
                            when (arrangement) {
                                AlertLayout.Arrangement.SideBySide -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    shown.forEachIndexed { i, a -> button(i, a, Modifier.weight(weightOf(a))) }
                                }
                                else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                                    shown.forEachIndexed { i, a -> button(i, a, Modifier.fillMaxWidth()) }
                                }
                            }
                        }
                    }
                    // Once, inside the dialog's own window and only after that
                    // window has input focus: a request made earlier was lost
                    // (on TV no button had focus until a D-pad press).
                    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
                    var focused by remember { mutableStateOf(false) }
                    LaunchedEffect(windowFocused) {
                        if (windowFocused && !focused && (focusButton ?: isTv)) {
                            // A few frames: the request fails until the button is attached and laid out.
                            repeat(10) {
                                kotlinx.coroutines.delay(16)
                                if (runCatching { focus.requestFocus() }.getOrDefault(false)) {
                                    focused = true
                                    return@LaunchedEffect
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertButton(action: AlertAction, isTv: Boolean, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val style = if (isTv) AlertLayout.tvStyle(action.role, focused) else AlertLayout.style(action.role)
    val shape = RoundedCornerShape(50)
    val m = modifier
        .heightIn(min = if (isTv) AlertLayout.TV_BUTTON_HEIGHT_DP.dp else 48.dp)
        .onFocusChanged { focused = it.isFocused }
        .then(if (isTv) Modifier.dpadFocusRing(shape, onFilled = style == AlertLayout.Style.Filled) else Modifier)
    val label: @Composable () -> Unit = {
        Text(action.label, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
    }
    // One Button whose colors follow the style: swapping between a filled and
    // a tonal composable when focus arrives would drop the focused node (TV).
    val error = action.role == AlertRole.Destructive
    val colors = when (style) {
        AlertLayout.Style.Filled -> if (error) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
        } else ButtonDefaults.buttonColors()
        // The same neutral fill as the profile dialogs' capsules (Apple's gray), not the theme's secondary hue.
        AlertLayout.Style.Tonal, AlertLayout.Style.TonalError -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f),
            contentColor = if (style == AlertLayout.Style.TonalError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
    Button(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = m,
        shape = shape,
        colors = colors,
        elevation = null,
    ) { label() }
}
