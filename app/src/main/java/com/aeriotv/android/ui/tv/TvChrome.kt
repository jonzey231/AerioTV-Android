package com.aeriotv.android.ui.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Android TV chrome canon, ported 1:1 from the tvOS rulings (Logan
 * 2026-09-05, MoviesPillStyle / TVGroupPillButtonStyle /
 * TVNavCircleButtonStyle / the library search capsule). tvOS lays out on a
 * 1080-point canvas and a 1080p Android TV is ~540 dp tall, so every tvOS
 * point below is halved.
 *
 *  - Choice pills are CAPSULES. Selected: accent fill, dark text. Unselected:
 *    elevated fill, secondary text (white while focused), 0.85 alpha at rest.
 *    Focus ring 2 dp (tvOS 3 pt): WHITE when selected, ACCENT when not.
 *    Scale 1.05 on focus.
 *  - Action circles (search / sort / filter / refresh / manage groups) are
 *    30 dp (tvOS 60 pt): quiet white-12% fill at rest with an 85% glyph;
 *    focused = white platter with a dark glyph, scale 1.08; selected (the
 *    control's screen is up) = accent fill, dark glyph.
 *  - The search field is a capsule the height of the circles (tvOS 380x60)
 *    with the elevated fill and a 2 dp ACCENT ring while focused.
 *
 * Every TV surface (guide, Movies, TV Shows, DVR, detail pages, Settings)
 * draws these instead of its own variant so the tabs read as one app.
 */
object TvChrome {
    /** Every TV pop-up sheet paints this (Logan 2026-09-10: one translucency everywhere). */
    @Composable
    fun dialogSurface(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)

    val circleSize: Dp = 30.dp
    val pillHorizontalPadding: Dp = 13.dp
    val pillVerticalPadding: Dp = 6.dp
    val ringWidth: Dp = 2.dp
    val searchWidth: Dp = 190.dp
    val pillFontSize = 11.sp
}

@Composable
fun TvPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    // Unselected fill is a white wash rather than the card colour: on the
    // translucent sheets the card colour vanished into the surface (Logan
    // 2026-09-10), a wash reads on the guide and on every dialog alike.
    // Solid, not see-through (Logan 2026-09-10): the wash is pre-composited
    // over the card colour so nothing behind the sheet shows through a pill.
    val fill = if (selected) colors.primary
    else colors.onSurface.copy(alpha = 0.12f).compositeOver(colors.surface)
    val ink = when {
        selected -> colors.onPrimary
        focused -> colors.onSurface
        else -> colors.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .tvFocusScale(focused, focusedScale = 1.05f)
            .clip(CircleShape)
            .background(fill)
            .border(
                width = TvChrome.ringWidth,
                color = when {
                    !focused -> Color.Transparent
                    selected -> Color.White
                    else -> colors.primary
                },
                shape = CircleShape,
            )
            // One focus target only: clickable() contributes it (a nested
            // focusable() drew the square ripple, see the guide pills).
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = TvChrome.pillHorizontalPadding, vertical = TvChrome.pillVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = ink, modifier = Modifier.size(12.dp))
        }
        Text(
            text = label,
            fontSize = TvChrome.pillFontSize,
            fontWeight = FontWeight.Medium,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun TvActionCircle(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    spinning: Boolean = false,
    /** Accent tint for the resting glyph (e.g. Filter while groups are hidden). */
    accentGlyph: Boolean = false,
    size: Dp = TvChrome.circleSize,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    badge: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        targetValue = when {
            focused -> Color.White
            selected -> colors.primary
            else -> colors.onSurface.copy(alpha = 0.12f)
        },
        label = "tvActionCircleFill",
    )
    val glyph by animateColorAsState(
        targetValue = when {
            focused -> Color.Black
            selected -> colors.onPrimary
            accentGlyph -> colors.primary
            else -> colors.onSurface.copy(alpha = 0.85f)
        },
        label = "tvActionCircleGlyph",
    )
    Box(
        modifier = modifier
            .tvFocusScale(focused, focusedScale = 1.08f)
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !spinning,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (spinning) {
            CircularProgressIndicator(color = glyph, strokeWidth = 2.dp, modifier = Modifier.size(size * 0.5f))
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = glyph,
                modifier = Modifier.size(size * 0.47f),
            )
        }
        badge?.invoke(this)
    }
}

/**
 * Library search capsule. Keyboard opens on OK only (the same
 * [TvKeyboardOnOkHost] gate every TV form uses) and Up / Down leave the
 * field without popping the IME. Wrap the screen in [TvKeyboardOnOkHost].
 */
@Composable
fun TvSearchCapsule(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    upTarget: FocusRequester? = null,
    width: Dp = TvChrome.searchWidth,
    onSearch: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        interactionSource = interaction,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface, fontSize = 12.sp),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (upTarget != null) Modifier.focusProperties { up = upTarget } else Modifier)
            .width(width)
            .height(TvChrome.circleSize)
            .clip(CircleShape)
            .background(colors.surfaceVariant)
            .border(
                width = TvChrome.ringWidth,
                color = if (focused) colors.primary else Color.Transparent,
                shape = CircleShape,
            )
            .tvFormFieldInput(),
        decorationBox = { inner ->
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(7.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            }
        },
    )
}
