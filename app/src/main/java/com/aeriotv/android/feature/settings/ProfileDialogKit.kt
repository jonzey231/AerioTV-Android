package com.aeriotv.android.feature.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.aeriotv.android.ui.settings.dpadFocusRing

/**
 * The profile dialogs' one layout (owner 2026-11-12, matching Apple 98fc424):
 * a card centered on screen, sized to its content, scrolling only past about
 * 80% of the screen (with a brief scroll indicator on open when it does).
 * The title and the lines that describe it are centered as one block; rows
 * and body text below share one left edge (no extra row inset on phones and
 * tablets; TV keeps its focus inset). A dialog that is a plain list of
 * actions draws each as a full-width capsule, with Cancel as the last one.
 * A tap outside does exactly what Cancel does; a dialog with no Cancel (an
 * OK-only notice, a PIN entry without Cancel, the launch gate and picker,
 * the sign-in forms, the User QR view) ignores it. On TV, Back is Cancel.
 */
object ProfileDialogRules {
    /** The share of the screen height a profile dialog card grows to before its content scrolls. */
    const val MAX_HEIGHT_FRACTION = 0.8f

    /** A form dialog (Add Profile) on TV grows further, so the whole form fits at 1080p (owner 2026-11-12, Apple parity). */
    const val TV_FORM_MAX_HEIGHT_FRACTION = 0.9f

    /** The gap between a form's parts: tighter on TV so the form fits. */
    fun formGapDp(isTv: Boolean): Int = if (isTv) 4 else 8

    /**
     * Add Profile's form on TV, compact so it fits at 1080p with one "From
     * your other devices" row (owner 2026-11-12, Apple parity): text field
     * height, the segmented control's and Role radios' inner padding, the
     * suggestion row, and the card's own padding. Text keeps its TV style.
     */
    object TvForm {
        /** A text field (Material's default is 56). */
        const val FIELD_HEIGHT_DP = 48
        /** The segmented control's item padding, top and bottom (10 elsewhere). */
        const val SEGMENT_PADDING_DP = 6
        /** A Role radio's padding, top and bottom (4 elsewhere). */
        const val RADIO_PADDING_DP = 0
        /** A suggestion row's minimum height (56 elsewhere). */
        const val SUGGESTION_ROW_DP = 48
        /** The space above the divider after the last suggestion (4 elsewhere). */
        const val DIVIDER_TOP_DP = 0
        /** The card's padding above and below (Material's is 24). */
        const val CARD_PADDING_DP = 16
        /** Between the title and the form (Material's is 16). */
        const val TITLE_GAP_DP = 8
        /** Between the form and the button row (Material's is 24). */
        const val BUTTONS_GAP_DP = 12
    }

    /** The height cap for a dialog: a form dialog on TV gets [TV_FORM_MAX_HEIGHT_FRACTION]. */
    fun maxHeightFraction(isTv: Boolean, form: Boolean): Float = if (isTv && form) TV_FORM_MAX_HEIGHT_FRACTION else MAX_HEIGHT_FRACTION

    /**
     * Where a form's submit sits: on TV in the dialog's bottom button row
     * beside Cancel, below the scrolling form, so both stay on screen (Apple
     * parity, owner 2026-11-12); phones and tablets keep it in the form.
     */
    fun formSubmitInButtonRow(isTv: Boolean): Boolean = true

    /** A button-row submit that cannot run yet (fields empty, or busy) is dimmed, never disabled (TV focus), like Apple's. */
    const val INERT_ALPHA = 0.45f

    fun submitAlpha(ready: Boolean, busy: Boolean): Float = if (ready && !busy) 1f else INERT_ALPHA

    /** The avatar action's label (and the title of its refusal dialog), matching "Set PIN". */
    const val SET_AVATAR = "Set Avatar"

    /** Capsules in one group sit this far apart. */
    const val ACTION_GAP_DP = 8

    /** The larger gap that opens the next group: before Remove, and before Cancel. */
    const val ACTION_GROUP_GAP_DP = 20

    /** A capsule's minimum height. */
    const val ACTION_MIN_HEIGHT_DP = 48

    /**
     * The gap above each capsule of an action list ([removeAt]: the index of
     * Remove, or -1; Cancel is the last). The first has none.
     */
    fun gapsAbove(count: Int, removeAt: Int): List<Int> = (0 until count).map { i ->
        when {
            i == 0 -> 0
            i == count - 1 || i == removeAt -> ACTION_GROUP_GAP_DP
            else -> ACTION_GAP_DP
        }
    }

    /** A profile's actions, in the order the actions dialog lists them (Apple's ProfileActionsSheet, word for word). */
    enum class Action { Switch, Rename, Role, MoveUp, MoveDown, SetAvatar, SetPin, RemovePin, ResetPin, ChannelProfiles, UserQr, Remove }

    /**
     * The actions a profile's dialog offers, in Apple's order: Switch to this
     * profile, Rename, Role, Move Up and Down, Set Avatar, the PIN actions,
     * Channel Profiles, Show User QR Code, then Remove (Cancel follows).
     * [roleAdmin]: an Admin or Standard profile is acting; [isAdmin]: the
     * section's Admin gate (Remove).
     */
    fun actions(
        isActive: Boolean,
        roleAdmin: Boolean,
        serverAdmin: Boolean,
        isTv: Boolean,
        index: Int,
        lastIndex: Int,
        canEditAvatar: Boolean,
        canPin: Boolean,
        hasPin: Boolean,
        canChannelProfiles: Boolean,
        hasUserQr: Boolean,
        isAdmin: Boolean,
        isDefault: Boolean,
    ): List<Action> = buildList {
        if (!isActive) add(Action.Switch)
        add(Action.Rename)
        if (roleAdmin) {
            // Dispatcharr admins are always Admin: no Role picker.
            if (!serverAdmin) add(Action.Role)
            // TV reorders by pick-up; phones and tablets keep these.
            if (!isTv && index > 0) add(Action.MoveUp)
            if (!isTv && index in 0 until lastIndex) add(Action.MoveDown)
        }
        if (canEditAvatar) add(Action.SetAvatar)
        if (canPin) {
            add(Action.SetPin)
            if (hasPin) add(Action.RemovePin)
            // An Admin resets another profile's forgotten PIN.
            if (hasPin && roleAdmin && !isActive) add(Action.ResetPin)
        }
        if (canChannelProfiles) add(Action.ChannelProfiles)
        // Own profile, or an Admin for another, with a saved API key; phones and tablets only.
        if (!isTv && (isActive || roleAdmin) && hasUserQr) add(Action.UserQr)
        if (isAdmin && !isDefault) add(Action.Remove)
    }

    /** What kinds of dialog a tap outside may close: only one whose Cancel it can stand for. */
    enum class Kind { WithCancel, OkOnly, PinWithoutCancel, LaunchGate, LaunchPicker, SignInForm, UserQr }

    fun outsideTapCancels(kind: Kind): Boolean = kind == Kind.WithCancel
}

/** True inside a profile dialog's content: its rows drop their own inset on phones and tablets. */
internal val LocalInProfileDialog = staticCompositionLocalOf { false }

/** True inside Add Profile's form on TV: fields, radios, and suggestion rows use [ProfileDialogRules.TvForm]. */
val LocalTvCompactForm = staticCompositionLocalOf { false }

/** The card's height cap (about 80% of the screen; 90% for a form dialog on TV). */
@Composable
private fun profileDialogMaxHeight(form: Boolean = false) = (
    LocalConfiguration.current.screenHeightDp *
        ProfileDialogRules.maxHeightFraction(com.aeriotv.android.ui.settings.rememberIsTvDevice(), form)
    ).dp

/**
 * Draws a thin scroll indicator for a moment once the content is measured,
 * when it overflows (the cut can fall exactly between two rows).
 */
@Composable
internal fun Modifier.profileScrollFlash(state: ScrollState): Modifier {
    val alpha = remember { Animatable(0f) }
    var flashed by remember { mutableStateOf(false) }
    LaunchedEffect(state.maxValue) {
        if (flashed || state.maxValue <= 0) return@LaunchedEffect
        flashed = true
        alpha.snapTo(1f)
        kotlinx.coroutines.delay(1_000)
        alpha.animateTo(0f, tween(400))
    }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    return drawWithContent {
        drawContent()
        val a = alpha.value
        if (a <= 0f || state.maxValue <= 0) return@drawWithContent
        val viewport = size.height
        val total = viewport + state.maxValue
        val thumb = (viewport * viewport / total).coerceAtLeast(24.dp.toPx())
        val top = (viewport - thumb) * (state.value.toFloat() / state.maxValue)
        val w = 3.dp.toPx()
        drawRoundRect(
            color = color.copy(alpha = 0.6f * a),
            topLeft = Offset(size.width - w, top),
            size = Size(w, thumb),
            cornerRadius = CornerRadius(w / 2, w / 2),
        )
    }
}

/**
 * A profile dialog with the app's Settings dialog look: centered title,
 * content capped at about 80% of the screen (scrolling past it), rows on one
 * edge. [outsideTapCancels]: a tap outside runs [onDismissRequest] (the
 * dialog's Cancel); false for a dialog with no Cancel. [scrollContent]: false
 * when the content scrolls on its own. TV Back runs [onDismissRequest].
 */
@Composable
fun ProfileAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    outsideTapCancels: Boolean = true,
    scrollContent: Boolean = true,
    /** A form (Add Profile): on TV it may grow to about 90% of the screen. */
    form: Boolean = false,
) {
    if (form && com.aeriotv.android.ui.settings.rememberIsTvDevice()) {
        TvFormDialog(onDismissRequest, confirmButton, modifier, dismissButton, title, text, outsideTapCancels)
        return
    }
    com.aeriotv.android.ui.settings.SettingsAlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.heightIn(max = profileDialogMaxHeight(form)),
        dismissButton = dismissButton,
        title = title?.let { t ->
            {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ProvideTextStyle(LocalTextStyle.current.copy(textAlign = TextAlign.Center)) { t() }
                }
            }
        },
        text = text?.let { body ->
            {
                // Phones and tablets: rows sit on the card with no row card or
                // divider; TV keeps its focus chrome.
                val isTv = com.aeriotv.android.ui.settings.rememberIsTvDevice()
                CompositionLocalProvider(
                    LocalInProfileDialog provides true,
                    com.aeriotv.android.ui.settings.LocalSuppressRowChrome provides !isTv,
                ) {
                    if (scrollContent) {
                        val state = rememberScrollState()
                        Column(Modifier.fillMaxWidth().verticalScroll(state).profileScrollFlash(state)) { body() }
                    } else {
                        Box(Modifier.fillMaxWidth()) { body() }
                    }
                }
            }
        },
        properties = DialogProperties(dismissOnClickOutside = outsideTapCancels),
    )
}

/**
 * Add Profile's card on TV: the Settings dialog look (surface card, centered
 * title, the form, Cancel and Add Profile at the bottom right, below the
 * scrolling form) with tighter padding, so the form fits at 1080p
 * ([ProfileDialogRules.TvForm]). The content scrolls on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvFormDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier,
    dismissButton: (@Composable () -> Unit)?,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    outsideTapCancels: Boolean,
) {
    androidx.compose.material3.BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnClickOutside = outsideTapCancels),
    ) {
        // Its own window: re-apply Text Size, as the other dialogs do.
        com.aeriotv.android.ui.scale.ProvideAppTextScale {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = modifier.heightIn(max = profileDialogMaxHeight(form = true)),
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = ProfileDialogRules.TvForm.CARD_PADDING_DP.dp)) {
                title?.let { t ->
                    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center)) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { t() }
                        }
                    }
                    Spacer(Modifier.height(ProfileDialogRules.TvForm.TITLE_GAP_DP.dp))
                }
                text?.let { body ->
                    Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        CompositionLocalProvider(
                            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                            LocalInProfileDialog provides true,
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { body() }
                        }
                    }
                }
                Spacer(Modifier.height(ProfileDialogRules.TvForm.BUTTONS_GAP_DP.dp))
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
        }
    }
}

/** The centered lines under a dialog's title that describe it (a role and username, a profile name). */
@Composable
internal fun ProfileDialogHeader(vararg lines: Pair<String, androidx.compose.ui.graphics.Color>) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        lines.forEach { (line, color) ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * One full-width action capsule (a dialog that is a plain list of actions):
 * secondary fill, centered label, red label for [destructive]. [newGroup]
 * opens a group (the larger gap above it). TV: the existing focus ring, and
 * OK fires only on a KeyUp whose KeyDown it saw (the long press that opened
 * the dialog never picks the first action).
 */
@Composable
internal fun ProfileActionButton(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    newGroup: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isTv = com.aeriotv.android.ui.settings.rememberIsTvDevice()
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    var okLatched by remember { mutableStateOf(false) }
    val fill = MaterialTheme.colorScheme.onSurface.copy(alpha = if (pressed || focused) 0.22f else 0.13f)
    Box(
        modifier
            .padding(top = if (newGroup) (ProfileDialogRules.ACTION_GROUP_GAP_DP - ProfileDialogRules.ACTION_GAP_DP).dp else 0.dp)
            .fillMaxWidth()
            .heightIn(min = ProfileDialogRules.ACTION_MIN_HEIGHT_DP.dp)
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
            .then(if (isTv) Modifier.dpadFocusRing(shape) else Modifier)
            .clip(shape)
            .background(fill)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A dialog that is a plain list of actions (a profile's actions, the TV
 * picker's Move dialog): centered title and [header], the capsules
 * ([actions], 8 dp apart; [ProfileActionButton] newGroup for Remove), and
 * Cancel as the last capsule, kept visible below the scroll after the larger
 * gap. A tap outside and TV Back are Cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileActionStackDialog(
    title: String,
    onCancel: () -> Unit,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.BasicAlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = ProfileDialogRules.outsideTapCancels(ProfileDialogRules.Kind.WithCancel)),
    ) {
        // Its own window: re-apply Text Size, as the other dialogs do.
        com.aeriotv.android.ui.scale.ProvideAppTextScale {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.heightIn(max = profileDialogMaxHeight()),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                val state = rememberScrollState()
                CompositionLocalProvider(LocalInProfileDialog provides true) {
                    Column(
                        Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(state).profileScrollFlash(state),
                        verticalArrangement = Arrangement.spacedBy(ProfileDialogRules.ACTION_GAP_DP.dp),
                    ) {
                        header?.invoke(this)
                        actions()
                    }
                }
                Spacer(Modifier.height(ProfileDialogRules.ACTION_GROUP_GAP_DP.dp))
                ProfileActionButton(label = "Cancel", onClick = onCancel)
            }
        }
        }
    }
}

/**
 * Add Profile's buttons (Apple parity): capsules, right-aligned by the
 * dialog. [filled] is the main action (Add Profile); the other is tonal on
 * phones and tablets, outlined on TV. The focus ring follows the capsule.
 */
@Composable
internal fun ProfileDialogCapsule(label: String, onClick: () -> Unit, filled: Boolean, modifier: Modifier = Modifier) {
    val isTv = com.aeriotv.android.ui.settings.rememberIsTvDevice()
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
    val primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val bg = when {
        filled -> primary
        isTv -> androidx.compose.ui.graphics.Color.Transparent
        else -> primary.copy(alpha = 0.14f)
    }
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .dpadFocusRing(shape, onFilled = filled)
            .clip(shape)
            .background(bg)
            .then(if (!filled && isTv) androidx.compose.ui.Modifier.border(1.dp, primary.copy(alpha = 0.7f), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = if (filled) androidx.compose.material3.MaterialTheme.colorScheme.onPrimary else primary,
            maxLines = 1,
        )
    }
}
