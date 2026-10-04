package com.aeriotv.android.feature.livetv.grid

import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import com.aeriotv.android.ui.theme.forText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.ui.ClockFormat
import com.aeriotv.android.core.ui.EpgFlagsRow
import com.aeriotv.android.core.ui.LocalShowEpgBadges
import com.aeriotv.android.core.ui.ProgramArtSlot
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
 * detail icon, then TMDB (backdrop, else poster), then the program's own
 * XMLTV icon, else the channel logo.
 * The description is the banner's one focus target: OK opens Program Info.
 */
object GuidePreviewBanner {
    /**
     * Art height + the row's own 4 dp bottom padding + 1 dp of slack: 106 dp
     * around the 101 dp slot at 100% Text Size. DERIVED from
     * [ProgramArtSlot.height] rather than copied, so raising the app-wide Text
     * Size (the only art-size knob, Logan 2026-09-15 after the user vote)
     * grows the banner with the art instead of clipping it. The slack scales
     * too, so the proportions hold at every stop (85% to 150%).
     *
     * The guide rows below simply start lower; the drawer top (GuideScreen)
     * and the mini player's baseline (MiniPlayerChrome.bannerArtBottomPx,
     * published from the measured art bounds) both read this rather than a
     * copy of it, so they follow.
     */
    val height: Dp
        @Composable get() = ProgramArtSlot.height + 5.dp * ProgramArtSlot.scale

    /**
     * The TV lift: tvOS pulls the banner 28 pt up under the tab bar so eight
     * guide rows still fit (ChannelListView, Logan 2026-09-05), halved here.
     * GuideScreen applies it; MainScaffold subtracts it to know where the
     * banner's top edge actually lands when it places the remote hint strip.
     * Reduced from 14 dp to 8 dp (Logan, Streamer 2026-09-11) to open up the
     * band under the nav bar: the banner and the guide rows below it move down
     * 6 dp, every other guide inset is unchanged.
     */
    val tvLift = 8.dp

    /**
     * How far the banner's FIRST text line (the program title) sits below the
     * banner's top edge. The copy column is BOTTOM-aligned, so this inset is
     * whatever the banner does not spend on text: measured at 4 dp in the
     * 106 dp row on a 1920x1080 Streamer (Logan 2026-09-11), i.e. a 102 dp
     * text column. DERIVED from [height] so it cannot drift from it, and the
     * text column term scales with Text Size exactly as the text itself does,
     * which keeps the clearance right at every stop. MainScaffold uses it to
     * keep the remote hint strip clear of that first line.
     */
    val firstTextInset: Dp
        @Composable get() = height - 102.dp * ProgramArtSlot.scale
}

/** Session art cache keyed by program id or cleaned title; null = every source missed. */
private val previewArt = mutableStateMapOf<String, String?>()

/** Art the banner already resolved for this program, for sheets opened from the guide (tvOS artCache). */
fun cachedPreviewArt(dispatcharrProgramId: Int?, title: String): String? {
    val key = dispatcharrProgramId?.let { "pid:$it" } ?: ("t:" + cleanPreviewTitle(title))
    return previewArt[key]
}

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
    /** Down handled by the host (tvOS: lands on the clock when no pill row). Return true when consumed. */
    onDown: (() -> Boolean)? = null,
    /** Corner mini player showing: reserve its column in the copy (tvOS
     *  GuidePreviewBanner.trailingReserve = 422 pt -> 211 dp). Nothing else
     *  about the banner changes, and nothing below it moves. */
    miniActive: Boolean = false,
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
        // Program detail icon (Direct Connect) first. Same order and the same
        // URL rules as Apple's GuidePreviewArtCache (Logan 2026-09-17: the
        // two platforms showed different art for the same program).
        var detailSkipped = false
        if (pid != null) {
            val base = ActivePlaylistBase.baseUrl
            val playlistId = ActivePlaylistBase.playlistId
            if (base == null || playlistId == null) {
                // The playlist globals are not set yet on the first focus
                // after launch. Do not cache a miss for that; retry next time.
                detailSkipped = true
            } else {
                url = runCatching {
                    withContext(Dispatchers.IO) {
                        val broker = entry.dispatcharrAuth()
                        val client = entry.dispatcharrClient()
                        broker.withApiKeyRetry(playlistId) { k ->
                            client.getProgramDetail(base, k, pid).bestPosterString?.let { raw ->
                                resolvePreviewArtUrl(raw, base)
                            }
                        }
                    }
                }.getOrNull()
            }
        }
        if (url == null && p.title.isNotBlank() && playlist.programPostersTmdbEnabled.first()) {
            val apiKey = playlist.tmdbApiKey.first()
            if (apiKey.isNotBlank()) {
                val isMovie = p.category.lowercase().let { it.contains("movie") || it.contains("film") }
                url = runCatching {
                    withContext(Dispatchers.IO) {
                        // One typed search hit (Apple's lookupArt): landscape
                        // backdrop first, poster only when there is no
                        // backdrop. No second poster-only search, which is
                        // what produced portrait art here while Apple showed
                        // the landscape card.
                        val tmdb = entry.tmdbService()
                        val art = tmdb.lookupArt(cleanPreviewTitle(p.title), isMovie, apiKey)
                        art?.backdrop?.takeIf { it.isNotBlank() }?.let { tmdb.imageUrlFor(it, "w780") }
                            ?: art?.poster?.takeIf { it.isNotBlank() }?.let { tmdb.imageUrlFor(it, "w500") }
                    }
                }.getOrNull()
            }
        }
        // Last stop: the program's OWN icon from the feed (XMLTV `<icon src>`).
        // Sports events ("Tomorrow at 21:00 - Villarreal v Real Betis") match
        // nothing on TMDB, but the feed ships a real picture for them.
        if (url == null) url = p.iconUrl?.takeIf { it.isNotBlank() }
        // A miss caused by the skipped detail step is not final.
        if (url != null || !detailSkipped) previewArt[key] = url
    }
    val art = artKey?.let { previewArt[it] }
    val artKnown = artKey != null && previewArt.containsKey(artKey)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            com.aeriotv.android.feature.player.MiniPlayerChrome
                .bannerArtBottomPx.value = 0f
        }
    }
    val colors = MaterialTheme.colorScheme
    val clockMode = rememberClockMode()
    val fmt = remember(clockMode) { ClockFormat.guideShort(clockMode) }
    val showBadges = LocalShowEpgBadges.current
    // Appearance > "Rounded corners in Guide view" (default OFF).
    val guideRounded = com.aeriotv.android.core.ui.LocalRoundedArtwork.current.guide

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(GuidePreviewBanner.height)
            .background(colors.background)
            .padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        // The shared program-art slot. Channel logo only once every source
        // missed; nothing while a lookup is open (no logo flash).
        // The corner mini player's BOTTOM edge lines up with this art card's
        // (Logan 2026-09-11; tvOS shares one baseline between the logo, the
        // copy and the mini). Published in root coordinates because the mini
        // is mounted at the activity root, outside this composition.
        // ONE slot shared with the Program Info sheet (ProgramArtSlot, Logan
        // 2026-09-15): fixed size, whole image, never cropped, never jumping
        // as art of a different aspect arrives.
        ProgramArtSlot(
            model = art,
            // GUIDE surface: the program art here follows Appearance >
            // "Rounded corners in Guide view", at the SAME radius as the guide
            // rail logos, so logos and program art match (Logan 2026-09-16).
            // Off means both are square.
            containerCorner = GUIDE_ART_CORNER,
            rounded = guideRounded,
            modifier = Modifier.onGloballyPositioned {
                com.aeriotv.android.feature.player.MiniPlayerChrome
                    .bannerArtBottomPx.value = it.boundsInRoot().bottom
            },
            fallback = {
                if (artKnown && channel != null && channel.tvgLogo.isNotBlank()) {
                    AsyncImage(
                        model = channel.tvgLogo, contentDescription = null, contentScale = ContentScale.Fit,
                        // Channel-logo fallback: the 135x76 dp logo was 75%
                        // of the 180x101 slot, so keep that share at any scale.
                        modifier = Modifier
                            .width(ProgramArtSlot.maxWidth * 0.75f)
                            .height(ProgramArtSlot.height * 0.75f)
                            // Same guide rule for the channel-logo fallback.
                            .clip(
                                com.aeriotv.android.core.ui.artworkTileShape(
                                    container = GUIDE_ART_CORNER,
                                    shorterSide = ProgramArtSlot.height * 0.75f,
                                    model = channel.tvgLogo,
                                    rounded = guideRounded,
                                ),
                            ),
                    )
                }
            },
        )
        if (program == null) {
            Text("Select a program", fontSize = 13.sp.subtext(), fontWeight = FontWeight.Medium, color = colors.tertiary)
        } else {
            // The mini (205 dp wide, 20 dp from the end) floats over the
            // banner's right end; the copy stops short of it rather than the
            // whole guide dropping below the mini.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = if (miniActive) 211.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // tvOS: title and channel name share the first text baseline.
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        program.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                    )
                    if (channel != null) {
                        Text(channel.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.textAccent, maxLines = 1, modifier = Modifier.alignByBaseline())
                    }
                }
                val sub = program.subTitle?.takeIf { !subtitleIsRedundant(it, program.title, program.description) }
                if (sub != null) {
                    Text(sub, fontSize = 11.sp.subtext(), fontWeight = FontWeight.Medium, fontStyle = FontStyle.Italic, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val range = fmt.format(Date(program.startMillis)) + " - " + fmt.format(Date(program.endMillis))
                    Text(range, fontSize = 10.sp.subtext(), fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant, maxLines = 1)
                    if (program.startMillis <= nowMs && nowMs < program.endMillis) {
                        val left = ((program.endMillis - nowMs) / 60_000L).toInt().coerceAtLeast(1)
                        Text("·", fontSize = 10.sp.subtext(), color = colors.tertiary)
                        Text(if (left >= 60) "${left / 60} h ${left % 60} min left" else "$left min left", fontSize = 10.sp.subtext(), fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)
                    }
                    // SEASON / EPISODE lives HERE now, in the meta line, right
                    // after the time left and before the LIVE / NEW badges
                    // (Logan 2026-09-17). It is plain text in the same small
                    // style as the time, never a pill, and the guide program
                    // cells no longer draw it at all. Date-coded seasons
                    // ("S2026 E917") are shown too: they are what the provider
                    // sends and Logan wants them readable here.
                    val se = seasonEpisodeLabel(program.season, program.episode)
                    if (se != null) {
                        Text("·", fontSize = 10.sp.subtext(), color = colors.tertiary)
                        Text(se, fontSize = 10.sp.subtext(), fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)
                    }
                    if (showBadges) EpgFlagsRow(flags = program.epgFlags(), compact = true)
                }
                if (program.description.isNotBlank()) {
                    val interaction = remember { MutableInteractionSource() }
                    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
                    Text(
                        program.description, fontSize = 11.sp.subtext(), lineHeight = 14.sp.subtext(),
                        // The banner height is fixed (drawer math + mini
                        // baseline), so large Text Sizes trade a description
                        // line for room instead of pushing the title out the top.
                        color = colors.onBackground.copy(alpha = 0.85f).forText(),
                        maxLines = (androidx.compose.ui.platform.LocalDensity.current.fontScale * com.aeriotv.android.ui.scale.LocalSubtextScale.current).let { fs ->
                            when { fs >= 1.35f -> 1; fs >= 1.1f -> 2; else -> 3 }
                        },
                        overflow = TextOverflow.Ellipsis,
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
                            .onPreviewKeyEvent { e ->
                                onDown != null &&
                                    e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                                    e.key == androidx.compose.ui.input.key.Key.DirectionDown && onDown()
                            }
                            .onFocusChanged {
                                if (it.isFocused && !focused) com.aeriotv.android.ui.tv.TvFocusTrace.focus("banner:description")
                                else if (!it.isFocused && focused) com.aeriotv.android.ui.tv.TvFocusTrace.blurred("banner:description")
                                focused = it.isFocused; onDescriptionFocusChanged(it.isFocused)
                            }
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

/** Width/height below which banner art counts as a portrait poster. */
internal const val PORTRAIT_ART_MAX_ASPECT = 0.8f

/**
 * The radius the guide's program art rounds to when Appearance > "Rounded
 * corners in Guide view" is on: the SAME 6 dp the guide rail logos use, so
 * logos and program art in the guide share one silhouette (Logan 2026-09-16).
 */
private val GUIDE_ART_CORNER = 6.dp

/**
 * Mirrors Apple's `VODService.resolveImageURL`: absolute URLs pass through,
 * a flat single-segment image path is a TMDB path and goes to the TMDB
 * image host, anything else is relative to the playlist origin.
 */
internal fun resolvePreviewArtUrl(raw: String, base: String, size: String = "w780"): String? {
    if (raw.isBlank()) return null
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
    val leading = raw.removePrefix("/")
    val isImage = leading.endsWith(".jpg") || leading.endsWith(".jpeg") ||
        leading.endsWith(".png") || leading.endsWith(".webp")
    if (isImage && !leading.contains("/")) return "https://image.tmdb.org/t/p/$size/$leading"
    return base.trimEnd('/') + "/" + leading
}
