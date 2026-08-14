package com.aeriotv.android.feature.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.data.ChannelCollection

/**
 * GH #57 (Logan 2026-08-10): the TV Guide's round Manage Groups control.
 *
 * The TV guide cleanup stripped the leading control circles off the guide's
 * pill row, and the comment left behind said Manage Groups stayed reachable
 * from the List view's Tune circle. That is a view switch to reach a setting
 * about the view you are already in, and for anyone whose default Live TV
 * view is the Guide the feature simply looks absent. Logan's placement: a
 * round button AFTER the last group when pills are showing, and beside the
 * "Groups" header in sidebar mode.
 *
 * Styled off the guide's own TV pill (30dp tall, soft surfaceVariant fill, a
 * 2dp white focus ring - the same ring a programme cell draws) rather than
 * the phone circle, so it reads as part of the pill row instead of a
 * Material chip dropped into it.
 */
@Composable
internal fun TvManageGroupsCircle(
    hiddenGroupsCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .then(
                if (focused) Modifier.border(2.dp, Color.White, CircleShape) else Modifier,
            )
            // clickable() already contributes the focus target. Chaining a
            // second focusable() on the same interaction source nests two focus
            // nodes, and on the Streamer the D-pad walked straight past this
            // button to the top nav bar. The guide's own TV pills use the bare
            // clickable form and focus correctly, so match them exactly.
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Tune,
            contentDescription = if (hiddenGroupsCount == 0) "Manage groups"
            else "Manage groups ($hiddenGroupsCount hidden)",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        // iOS parity (ManageGroupsButton): warning dot when groups are hidden,
        // so a user who cannot find a group has a visible reason to open this.
        if (hiddenGroupsCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 3.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFA502)),
            )
        }
    }
}

/**
 * Centered "Live TV" title bar whose trailing actions SHRINK on narrow
 * screens so the title stays truly screen-centered (user report: on the
 * Fold's cover display the title sat left of center). Material's
 * CenterAlignedTopAppBar cannot do this: with an empty leading slot and
 * three or four default 48dp actions it squeezes the title box and the
 * text re-aligns toward the leading edge. Here the title is absolutely
 * centered in the bar and the action cluster sizes itself to whatever
 * width is left BESIDE the centered title: 48dp buttons where they fit,
 * scaling down to a 32dp floor, stepping the title from titleLarge down
 * to titleMedium before accepting a tight fit. Measured with
 * TextMeasurer, so the math follows font scale too.
 *
 * [actions] receives the resolved per-button and glyph sizes; build each
 * action as `IconButton(modifier = Modifier.size(buttonSize)) {
 * Icon(modifier = Modifier.size(iconSize)) }` so the whole cluster obeys
 * the fit.
 */
@Composable
fun LiveTvTopBar(
    actionCount: Int,
    modifier: Modifier = Modifier,
    actions: @Composable (buttonSize: Dp, iconSize: Dp) -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.background),
    ) {
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val titleLarge = MaterialTheme.typography.titleLarge
        val titleMedium = MaterialTheme.typography.titleMedium
        val barWidth = maxWidth
        val fit = remember(barWidth, actionCount, titleLarge, titleMedium, density) {
            fun tryFit(style: TextStyle): Pair<TextStyle, Dp>? {
                val titleWidth = with(density) {
                    textMeasurer.measure(
                        text = "Live TV",
                        style = style.copy(fontWeight = FontWeight.Bold),
                    ).size.width.toDp()
                }
                // Space beside the centered title on ONE side, minus a small
                // breathing gap so icons never kiss the text.
                val side = (barWidth - titleWidth) / 2 - 8.dp
                if (actionCount == 0) return style to 48.dp
                val perAction = side / actionCount
                return when {
                    perAction >= 48.dp -> style to 48.dp
                    perAction >= 32.dp -> style to perAction
                    else -> null
                }
            }
            tryFit(titleLarge) ?: tryFit(titleMedium) ?: (titleMedium to 32.dp)
        }
        val (titleStyle, buttonSize) = fit
        val iconSize = (buttonSize - 14.dp).coerceIn(18.dp, 24.dp)
        Text(
            text = "Live TV",
            style = titleStyle,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.Center),
        )
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions(buttonSize, iconSize)
        }
    }
}

/**
 * Phone filter + group-pills row shared by the List and Guide views so the
 * two render pixel-identically (user reports: first the pill shape, then a
 * positional shift when toggling views). Geometry is the List view's
 * original: one 56dp LazyRow, 16dp/8dp content padding, 8dp item spacing,
 * with the Manage Groups circle scrolling as the first item. Phone-only;
 * both TV paths keep their own tvOS-style rows.
 */
@Composable
fun LiveTvPillsRow(
    groups: List<String>,
    selectedGroup: String,
    onSelectGroup: (String) -> Unit,
    collections: List<ChannelCollection>,
    hiddenGroupsCount: Int,
    onManageGroups: () -> Unit,
    collectionPillItem: @Composable (ChannelCollection) -> Unit,
) {
    val pillListState = rememberLazyListState()
    // GH #55 polish: whenever the selection changes (pill tap OR a list
    // group-swipe), glide the row so the selected pill sits about a third
    // in from the left edge - the pills visibly follow the swipe.
    LaunchedEffect(selectedGroup, groups, collections) {
        val idx = groups.indexOf(selectedGroup)
        if (idx >= 0) {
            val item = 1 +
                collections.count { it.placement == ChannelCollection.PLACEMENT_BEGINNING } +
                idx
            val viewport = pillListState.layoutInfo.viewportEndOffset
            pillListState.animateScrollToItem(item, scrollOffset = -(viewport / 3))
        }
    }
    LazyRow(
        state = pillListState,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .clickable(onClick = onManageGroups),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = if (hiddenGroupsCount == 0) "Manage groups"
                    else "Manage groups ($hiddenGroupsCount hidden)",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                // iOS parity: warning dot flags that at least one group is
                // hidden (ManageGroupsSheet.swift's ManageGroupsButton).
                if (hiddenGroupsCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 6.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFFA502)),
                    )
                }
            }
        }
        // #45: collection pills join the group row -- placement "beginning"
        // renders before All, "end" after the last group.
        items(
            collections.filter { it.placement == ChannelCollection.PLACEMENT_BEGINNING },
            key = { "coll_${it.id}" },
        ) { c -> collectionPillItem(c) }
        items(groups, key = { "grp_$it" }) { group ->
            FilterChip(
                selected = selectedGroup == group,
                onClick = { onSelectGroup(group) },
                label = { Text(group, style = MaterialTheme.typography.labelLarge) },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedGroup == group,
                ),
            )
        }
        items(
            collections.filter { it.placement != ChannelCollection.PLACEMENT_BEGINNING },
            key = { "coll_${it.id}" },
        ) { c -> collectionPillItem(c) }
    }
}
