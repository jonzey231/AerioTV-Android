package com.aeriotv.android.feature.livetv.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.ui.ClockFormat
import com.aeriotv.android.core.ui.EpgFlagsRow
import com.aeriotv.android.core.ui.LocalShowEpgBadges
import com.aeriotv.android.core.ui.epgFlags
import com.aeriotv.android.core.ui.rememberClockMode
import com.aeriotv.android.core.ui.seasonEpisodeLabel
import com.aeriotv.android.core.ui.subtitleIsRedundant
import com.aeriotv.android.feature.livetv.ProgramInfoEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Live TV layout "Channel Preview" (tvOS GuidePreviewBanner, Logan
 * 2026-09-05), halved from the 1080 pt canvas: a 106 dp banner above the
 * guide for the FOCUSED program. Text comes straight from the cell's
 * program; only the art arrives later. Art order: the Dispatcharr program
 * detail icon, then TMDB (backdrop, else poster), else the channel logo.
 * The description is the banner's one focus target: OK opens Program Info.
 */
object GuidePreviewBanner {
    val height = 106.dp
}

/** Session art cache keyed by program id or cleaned title; null = every source missed. */
private val previewArt = mutableStateMapOf<String, String?>()

@Composable
fun GuidePreviewBanner(
    program: EPGProgramme?,
    channel: M3UChannel?,
    nowMs: Long,
    onOpenInfo: () -> Unit,
    descriptionFocus: FocusRequester,
    /** Down from the description (tvOS: the first pill, else the clock). */
    downTarget: FocusRequester?,
    upTarget: FocusRequester?,
    onDescriptionFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val artKey = program?.let { p -> p.dispatcharrProgramId?.let { "pid:$it" } ?: ("t:" + cleanPreviewTitle(p.title)) }
    LaunchedEffect(artKey) {
        val p = program ?: return@LaunchedEffect
        val key = artKey ?: return@LaunchedEffect
        if (previewArt.containsKey(key)) return@LaunchedEffect
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, ProgramInfoEntryPoint::class.java)
        var url: String? = null
        val pid = p.dispatcharrProgramId
        val playlist = entry.appPreferences()
        // Program detail icon (Direct Connect) first.
        if (pid != null) {
            url = runCatching {
                withContext(Dispatchers.IO) {
                    val broker = entry.dispatcharrAuth()
                    val client = entry.dispatcharrClient()
                    val base = ActivePlaylistBase.baseUrl ?: return@withContext null
                    val playlistId = ActivePlaylistBase.playlistId ?: return@withContext null
                    broker.withApiKeyRetry(playlistId) { k ->
                        client.getProgramDetail(base, k, pid).bestPosterString?.let { raw ->
                            if (raw.startsWith("/")) base.trimEnd('/') + raw else raw
                        }
                    }
                }
            }.getOrNull()
        }
        if (url == null && p.title.isNotBlank() && playlist.programPostersTmdbEnabled.first()) {
            val apiKey = playlist.tmdbApiKey.first()
            if (apiKey.isNotBlank()) {
                val isMovie = p.category.lowercase().let { it.contains("movie") || it.contains("film") }
                url = runCatching {
                    withContext(Dispatchers.IO) {
                        val tmdb = entry.tmdbService()
                        val d = tmdb.detailsForTitle(cleanPreviewTitle(p.title), isMovie, apiKey)
                        d?.backdropPath?.takeIf { it.isNotBlank() }?.let { tmdb.imageUrlFor(it, "w780") }
                            ?: tmdb.posterUrlForTitle(cleanPreviewTitle(p.title), apiKey)
                    }
                }.getOrNull()
            }
        }
        previewArt[key] = url
    }
    val art = artKey?.let { previewArt[it] }
    val artKnown = artKey != null && previewArt.containsKey(artKey)
    val colors = MaterialTheme.colorScheme
    val clockMode = rememberClockMode()
    val fmt = remember(clockMode) { ClockFormat.guideShort(clockMode) }
    val showBadges = LocalShowEpgBadges.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(GuidePreviewBanner.height)
            .background(colors.background)
            .padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        // 360x203 pt art slot, halved. Channel logo only once every source
        // missed; nothing while a lookup is open (no logo flash).
        Box(modifier = Modifier.width(180.dp).height(101.dp), contentAlignment = Alignment.Center) {
            when {
                art != null -> AsyncImage(
                    model = art, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.width(180.dp).height(101.dp).clip(RoundedCornerShape(6.dp)),
                )
                artKnown && channel != null && channel.tvgLogo.isNotBlank() -> AsyncImage(
                    model = channel.tvgLogo, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.width(135.dp).height(76.dp),
                )
            }
        }
        if (program == null) {
            Text("Select a program", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.tertiary)
        } else {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        program.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (channel != null) {
                        Text(channel.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.primary, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
                    }
                }
                val sub = program.subTitle?.takeIf { !subtitleIsRedundant(it, program.title, program.description) }
                if (sub != null) {
                    Text(sub, fontSize = 11.sp, fontWeight = FontWeight.Medium, fontStyle = FontStyle.Italic, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val range = fmt.format(Date(program.startMillis)) + " - " + fmt.format(Date(program.endMillis))
                    Text(range, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant, maxLines = 1)
                    // Date-coded seasons ("S2026 E905") are not episode identity.
                    val se = seasonEpisodeLabel(program.season, program.episode)?.takeIf { (program.season ?: 0) < 1900 }
                    if (se != null) {
                        Text("·", fontSize = 10.sp, color = colors.tertiary)
                        Text(se, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)
                    }
                    if (program.startMillis <= nowMs && nowMs < program.endMillis) {
                        val left = ((program.endMillis - nowMs) / 60_000L).toInt().coerceAtLeast(1)
                        Text("·", fontSize = 10.sp, color = colors.tertiary)
                        Text(if (left >= 60) "${left / 60} h ${left % 60} min left" else "$left min left", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)
                    }
                    if (showBadges) EpgFlagsRow(flags = program.epgFlags(), compact = true)
                }
                if (program.description.isNotBlank()) {
                    val interaction = remember { MutableInteractionSource() }
                    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
                    Text(
                        program.description, fontSize = 11.sp, lineHeight = 14.sp,
                        color = colors.onBackground.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            // tvOS BannerTextButtonStyle: the platter's inset
                            // is cancelled so the text stays flush with the
                            // time line above it.
                            .offset(x = (-6).dp)
                            .focusRequester(descriptionFocus)
                            .focusProperties {
                                if (downTarget != null) down = downTarget
                                if (upTarget != null) up = upTarget
                            }
                            .onFocusChanged { focused = it.isFocused; onDescriptionFocusChanged(it.isFocused) }
                            .clip(RoundedCornerShape(5.dp))
                            // tvOS BannerTextButtonStyle: a faint platter on focus, no scale.
                            .background(if (focused) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                            .clickable(interactionSource = interaction, indication = null, onClick = onOpenInfo)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** Trailing "(2024)" style year tags never help an art lookup. */
private fun cleanPreviewTitle(raw: String): String =
    raw.replace(Regex("""\s*\((\d{4})\)\s*$"""), "").trim()

/** The active playlist's Dispatcharr origin, published by GuideScreen for the art fetch. */
object ActivePlaylistBase {
    @Volatile var baseUrl: String? = null
    @Volatile var playlistId: String? = null
}
