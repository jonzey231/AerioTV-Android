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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.collectAsState
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
import com.aeriotv.android.core.data.toInfoTarget
import com.aeriotv.android.ui.scale.subtext
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

/**
 * Program result card (Logan 2026-11-05, Apple parity): the List view
 * channel card's height, corner, and padding. Program art on the left (the
 * feed icon, then art the guide preview already resolved, then the channel
 * logo), then the title, the day and start time, and the channel logo, name,
 * and number with a LIVE badge while it airs.
 */
@Composable
private fun LiveProgramHitRow(hit: LiveProgramHit, isTv: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val now = System.currentTimeMillis()
    val p = hit.programme
    // Program Info's art path (ProgramArtResolver), lazy per visible card;
    // the channel logo only when it finds no program art.
    val target = remember(hit.key) {
        p.let { it.toInfoTarget(hit.channel.name, hit.channel.dispatcharrChannelId) }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val playlistVm: com.aeriotv.android.feature.playlist.PlaylistViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    val playlist = playlistVm.state.collectAsState().value.playlist
    val programArt by androidx.compose.runtime.produceState(ProgramArtResolver.cached(target), hit.key, playlist?.id) {
        if (value == null && playlist != null) value = ProgramArtResolver.resolve(context, playlist, target)
    }
    val art = programArt ?: hit.channel.tvgLogo.takeIf { it.isNotBlank() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val corner = com.aeriotv.android.feature.channels.CHANNEL_ROW_CORNER
    val shape = RoundedCornerShape(corner)
    val height = rememberChannelCardHeight(isTv)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), shape),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .height(height)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(PROGRAM_CARD_ART_WIDTH)
                .fillMaxHeight()
                .clip(RoundedCornerShape(corner))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Tv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = p.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = programHitWhen(p, now),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 28.dp, height = 18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (hit.channel.tvgLogo.isNotBlank()) AsyncImage(
                        model = hit.channel.tvgLogo,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(2.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                Spacer(Modifier.width(6.dp))
                // Name, then the radio glyph right after it (Logan 2026-11-05,
                // Apple parity), then the number; the name alone ellipsizes.
                val name = hit.channel.name.takeIf { it.isNotBlank() }
                val number = hit.channel.channelNumber?.takeIf { it.isNotBlank() }
                if (name != null) Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hit.channel.isRadio) {
                    if (name != null) Spacer(Modifier.width(if (isTv) 6.dp else 4.dp))
                    com.aeriotv.android.feature.channels.RadioChannelGlyph(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    )
                }
                if (number != null) Text(
                    text = if (name != null) " · $number" else number,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = if (hit.channel.isRadio) 4.dp else 0.dp),
                )
                if (hit.isAiring(now)) {
                    Spacer(Modifier.width(8.dp))
                    LiveNowBadge()
                }
            }
        }
    }
}

/** Program art slot width: the List view channel card's leading column. */
private val PROGRAM_CARD_ART_WIDTH = 86.dp

/**
 * The List view channel card's height (ChannelRow): 10 dp padding top and
 * bottom around the name line, the program title line, an optional subtitle
 * line (phones and tablets with subtitles on), two description lines, and the
 * 4 dp gap plus 2 dp progress bar. Measured from the same text styles at the
 * current Text Size, like the row itself.
 */
@Composable
private fun rememberChannelCardHeight(isTv: Boolean): androidx.compose.ui.unit.Dp {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val name = MaterialTheme.typography.bodyLarge
    val title = MaterialTheme.typography.bodyMedium
    val body = MaterialTheme.typography.bodySmall.subtext()
    val subtitles = !isTv && com.aeriotv.android.core.ui.LocalShowProgramSubtitles.current
    return remember(name, title, body, density, subtitles) {
        fun lines(style: androidx.compose.ui.text.TextStyle, count: Int): Int =
            measurer.measure(List(count) { " " }.joinToString("\n"), style = style, maxLines = count).size.height
        val px = lines(name, 1) + lines(title, 1) + (if (subtitles) lines(body, 1) else 0) + lines(body, 2)
        with(density) { px.toDp() + 6.dp + 20.dp }
    }
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
        radio = channel.isRadio,
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
    radio: Boolean,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (radio) {
                    Spacer(Modifier.width(if (isTv) 6.dp else 4.dp))
                    com.aeriotv.android.feature.channels.RadioChannelGlyph(
                        fontSize = MaterialTheme.typography.titleMedium.fontSize,
                    )
                }
            }
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
