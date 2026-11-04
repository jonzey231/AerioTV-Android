package com.aeriotv.android.feature.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aeriotv.android.core.data.M3UChannel

/**
 * Live TV search sections (Logan 2026-11-04, Apple LiveProgramSearchSection):
 * "Channels" header, then "Programs": every guide program whose title
 * matches, now forward, by start time, capped at [LiveProgramHit.CAP] with a
 * footer. Rows are lazy items of the surrounding list.
 */
internal fun LazyListScope.liveSearchSectionHeader(title: String, key: String) {
    item(key = key) { LiveSearchSectionHeader(title) }
}

internal fun LazyListScope.liveProgramSearchSection(
    hits: List<LiveProgramHit>,
    truncated: Boolean,
    pending: Boolean,
    isTv: Boolean,
    firstRowModifier: Modifier = Modifier,
    onTap: (LiveProgramHit) -> Unit,
) {
    liveSearchSectionHeader(LiveSearchCopy.PROGRAMS_HEADER, "live.programs.header")
    if (hits.isEmpty()) {
        if (!pending) item(key = "live.programs.empty") {
            Text(
                text = LiveSearchCopy.NO_PROGRAMS,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
        return
    }
    items(hits.size, key = { hits[it].key }) { i ->
        val hit = hits[i]
        LiveProgramHitRow(
            hit = hit, isTv = isTv,
            modifier = if (i == 0) firstRowModifier else Modifier,
            onClick = { onTap(hit) },
        )
    }
    if (truncated) item(key = "live.programs.footer") {
        Text(
            text = LiveSearchCopy.CAP_FOOTER,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        )
    }
}

@Composable
internal fun LiveSearchSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 14.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/** Program row: title, channel logo and name, day and start time, live badge. */
@Composable
private fun LiveProgramHitRow(hit: LiveProgramHit, isTv: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val now = System.currentTimeMillis()
    LiveSearchCard(
        logo = hit.channel.tvgLogo,
        title = hit.programme.title,
        subtitle = listOf(hit.channel.name.takeIf { it.isNotBlank() }, programHitWhen(hit.programme, now))
            .filterNotNull().joinToString(" · "),
        live = hit.isAiring(now),
        isTv = isTv,
        modifier = modifier,
        onClick = onClick,
    )
}

/** Channels row of the TV Search screen: logo, name, number, and the
 *  "why it matched" line for a program-only match. */
@Composable
internal fun LiveChannelHitRow(
    channel: M3UChannel,
    matchLine: String?,
    isTv: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    LiveSearchCard(
        logo = channel.tvgLogo,
        title = channel.name,
        subtitle = listOf(channel.channelNumber?.takeIf { it.isNotBlank() }, matchLine)
            .filterNotNull().joinToString(" · "),
        live = false,
        isTv = isTv,
        modifier = modifier,
        onClick = onClick,
    )
}

@Composable
private fun LiveSearchCard(
    logo: String,
    title: String,
    subtitle: String,
    live: Boolean,
    isTv: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    val logoSize = if (isTv) 56.dp else 40.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (focused) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(logoSize)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (logo.isNotBlank()) {
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Tv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (live) LiveNowBadge()
        }
    }
}

/** Apple LiveBadge: red dot plus LIVE in a tinted capsule. */
@Composable
private fun LiveNowBadge() {
    val red = Color(0xFFE53935)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(red.copy(alpha = 0.15f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(red))
        Spacer(Modifier.width(4.dp))
        Text("LIVE", style = MaterialTheme.typography.labelSmall, color = red)
    }
}
