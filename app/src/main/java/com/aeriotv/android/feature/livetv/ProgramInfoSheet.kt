package com.aeriotv.android.feature.livetv

import androidx.compose.material.icons.filled.FiberManualRecord
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.core.ui.subtitleIsRedundant
import com.aeriotv.android.core.ui.rememberClockMode
import com.aeriotv.android.core.ui.ClockFormat
import android.content.res.Configuration
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import com.aeriotv.android.ui.scale.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeriotv.android.ui.scale.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.aeriotv.android.core.category.CategoryPillsFlow
import com.aeriotv.android.core.category.METADATA_TOKENS
import com.aeriotv.android.core.category.categoryTokens
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.network.DispatcharrAuthBroker
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.TMDBService
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.ui.EpgFlag
import com.aeriotv.android.core.ui.EpgFlagsRow
import com.aeriotv.android.core.ui.EpgLiveRed
import com.aeriotv.android.core.ui.LocalShowEpgBadges
import com.aeriotv.android.core.ui.ProgramArtSlot
import com.aeriotv.android.core.ui.epgFlags
import com.aeriotv.android.core.ui.seasonEpisodeLabel
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only ModalBottomSheet showing programme detail. Mirrors iOS
 * `ProgramInfoView` (ProgramInfoView.swift:78) plus iOS's lazy category
 * enrichment from `getProgramDetail` (StreamingAPIs.swift line 1456): when
 * the target carries a Dispatcharr program id and the bulk-grid category
 * field came back empty, we fetch `/api/epg/programs/<id>/` the moment the
 * sheet opens and upgrade the visible category pill row in place.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProgramInfoSheet(
    target: ProgramInfoTarget,
    onDismiss: () -> Unit,
    playlistVm: PlaylistViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val playlistState by playlistVm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Effective category string the rest of the sheet renders. Starts at
    // whatever target carries; flips when the lazy detail fetch resolves
    // with a richer list.
    var effectiveCategory by remember(target.id) { mutableStateOf(target.category) }
    // Rerun flag lazily enriched from /api/epg/programs/<id>/ - the bulk grid
    // Dispatcharr serves Direct Connect guides from carries no repeat field.
    var enrichedRepeat by remember(target) { mutableStateOf(false) }

    // Art order on every surface: the server detail icon (resolved through a
    // regenerated program id when needed), then the program's own feed icon,
    // then TMDB by title only when both are empty and the detail lookup did
    // not fail (an NFL pregame matched an unrelated movie poster on TMDB,
    // Onn 2026-10-12, while the detail icon was available).
    var detailPoster by remember(target.id) { mutableStateOf<String?>(null) }
    var tmdbPoster by remember(target.id) { mutableStateOf<String?>(null) }
    // Art the guide preview banner already resolved for this program (it
    // follows the same order), so the sheet opens with the same picture.
    val bannerPoster = remember(target.id) {
        com.aeriotv.android.feature.livetv.grid.cachedPreviewArt(
            target.dispatcharrProgramId,
            target.title,
        )
    }
    val feedIcon = target.iconUrl?.takeIf { it.isNotBlank() }
    val posterUrl = detailPoster ?: bannerPoster ?: feedIcon ?: tmdbPoster
    LaunchedEffect(posterUrl) { posterUrl?.let { ProgramArtResolver.remember(target, it) } }

    // Keyed on the playlist too: this sheet's PlaylistViewModel can still be
    // loading when it opens, and running with no playlist skipped the detail
    // lookup and let TMDB answer first. The effect waits for the playlist and
    // runs again when it arrives, so the art updates in place.
    val loadedPlaylistId = playlistState.playlist?.id
    LaunchedEffect(target.id, loadedPlaylistId) {
        if (loadedPlaylistId == null) return@LaunchedEffect
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ProgramInfoEntryPoint::class.java,
        )
        // Server detail fetch fires when EITHER the categories or the poster
        // are still missing; one request carries both (iOS fetches the same
        // way, ProgramInfoView.swift:304).
        val categoryNeeded = effectiveCategory.isBlank()
        val programId = target.dispatcharrProgramId
        val playlist = playlistState.playlist
        val isDispatcharr = playlist != null && (
            playlist.sourceType == SourceType.DispatcharrApiKey.name ||
                playlist.sourceType == SourceType.DispatcharrUserPass.name
            )
        var detailFailed = false
        if (programId != null && playlist != null && isDispatcharr) {
            val baseUrl = playlist.urlString
            val playlistId = playlist.id
            val result = runCatching {
                fetchProgramDetailResolving(
                    entry, baseUrl, playlistId, programId,
                    listOfNotNull(target.epgChannelKey),
                    target.startMillis, target.title,
                )
            }.getOrNull()
            val detail = result?.detail
            if (detail == null) {
                detailFailed = true
                android.util.Log.i("AerioGuide", "[PREVIEW-ART] Program Info detail unavailable for $programId (${target.title})")
            } else {
                val joined = detail.categories.filter { it.isNotBlank() }.joinToString(",")
                if (categoryNeeded && joined.isNotBlank()) effectiveCategory = joined
                // The bulk grid has no repeat field, but this endpoint does:
                // surface REPEAT on Direct Connect (mikec79, 2026-08-19).
                if (detail.isPreviouslyShown) enrichedRepeat = true
                detail.bestPosterString?.let { raw ->
                    // Server-relative icon paths (protected /media/ etc.) need
                    // the playlist origin prefixed before Coil can load them.
                    detailPoster = if (raw.startsWith("/")) baseUrl.trimEnd('/') + raw else raw
                }
            }
        }
        // TMDB-by-title, last: opt-in pref + the user's own key only (iOS
        // loadTMDBPosterIfNeeded, ProgramInfoView.swift:369).
        if (!detailFailed && detailPoster == null && bannerPoster == null && feedIcon == null &&
            target.title.isNotBlank()
        ) {
            val prefs = entry.appPreferences()
            if (prefs.programPostersTmdbEnabled.first()) {
                val key = prefs.tmdbApiKey.first()
                if (key.isNotBlank()) {
                    tmdbPoster = entry.tmdbService().posterUrlForTitle(target.title, key)
                }
            }
        }
    }

    val isTv = (
        LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK
        ) == Configuration.UI_MODE_TYPE_TELEVISION

    // Record (Logan 2026-11-05, Apple parity): a visible button that hands
    // over to the standard record sheet, the same one the long-press Record
    // action opens. Hidden once the program has ended.
    var recordOpen by remember(target) { mutableStateOf(false) }
    if (recordOpen) {
        RecordProgramSheet(target = target, onDismiss = onDismiss)
        return
    }
    val canRecord = target.endMillis > System.currentTimeMillis()

    if (isTv) {
        // TV: a centered modal card, not a bottom-edge sheet. tvOS shows
        // program detail as a focused panel in the middle of the screen;
        // a ModalBottomSheet's drag handle + swipe-to-dismiss + edge
        // anchoring are all touch idioms that read as "phone UI" on a
        // 10-foot display and aren't reachable with a remote. Dialog +
        // Back-to-dismiss is the correct TV affordance.
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // tvOS ProgramInfoView.tvCard (Logan 2026-09-05), halved from the
            // 1400x780 pt sheet: art beside the copy, the facts as a row of
            // labelled columns, the description as the card's one focus
            // target, then the metadata and category pills.
            Surface(
                shape = RoundedCornerShape(INFO_CARD_CORNER),
                color = com.aeriotv.android.ui.tv.TvChrome.dialogSurface(),
                tonalElevation = 6.dp,
                modifier = Modifier
                    .width(700.dp)
                    .heightIn(max = 420.dp),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    TvProgramInfoCard(
                        target = target,
                        effectiveCategory = effectiveCategory,
                        posterUrl = posterUrl,
                        enrichedRepeat = enrichedRepeat,
                    )
                    if (canRecord) ProgramInfoRecordButton { recordOpen = true }
                }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            ) {
                ProgramInfoBody(
                    target = target,
                    effectiveCategory = effectiveCategory,
                    posterUrl = posterUrl,
                    posterWidth = 100.dp,
                    enrichedRepeat = enrichedRepeat,
                )
                if (canRecord) {
                    Spacer(Modifier.height(16.dp))
                    ProgramInfoRecordButton { recordOpen = true }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The Program Info "Record" button: D-pad focusable on TV, a tap target
 *  on phones and tablets. */
@Composable
private fun ProgramInfoRecordButton(onClick: () -> Unit) {
    androidx.compose.material3.Button(onClick = onClick) {
        // Text only (Logan 2026-11-05), matching Apple.
        Text("Record")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvProgramInfoCard(
    target: ProgramInfoTarget,
    effectiveCategory: String,
    posterUrl: String?,
    enrichedRepeat: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        // Same slot as the guide preview banner (ProgramArtSlot, Logan
        // 2026-09-15): same size on both surfaces, whole image, no crop.
        if (posterUrl != null) {
            ProgramArtSlot(model = posterUrl, containerCorner = INFO_CARD_CORNER)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = target.channelName.uppercase(Locale.getDefault()),
                fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
                color = colors.onSurfaceVariant,
            )
            Text(
                text = target.title.ifBlank { "Untitled" },
                fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
            )
            target.subTitle?.takeIf { !subtitleIsRedundant(it, target.title, target.description) }?.let { sub ->
                Text(sub, fontSize = 12.sp.subtext(), fontStyle = FontStyle.Italic, color = colors.onSurfaceVariant)
            }
            val showEpgBadges = LocalShowEpgBadges.current
            val badges = buildList {
                if (target.isLiveNow() && !target.isLiveBroadcast) add(EpgFlag("ON NOW", EpgLiveRed))
                if (showEpgBadges) {
                    val hidden = com.aeriotv.android.core.ui.LocalHiddenEpgBadges.current
                    addAll(
                        com.aeriotv.android.core.ui.epgFlagsOf(
                            isNew = target.isNew, isLiveBroadcast = target.isLiveBroadcast,
                            isPremiere = target.isPremiere, isFinale = target.isFinale,
                            isRepeat = target.isRepeat || enrichedRepeat,
                        ).filter { it.label !in hidden },
                    )
                }
            }
            if (badges.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) { badges.forEach { com.aeriotv.android.core.ui.EpgFlagBadge(it) } }
            }
            val clockMode = rememberClockMode()
            val timeFormat = remember(clockMode) { ClockFormat.short(clockMode) }
            val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 3.dp)) {
                TvInfoColumn("Airs", "${timeFormat.format(Date(target.startMillis))} \u2013 ${timeFormat.format(Date(target.endMillis))}")
                TvInfoColumn("Date", dateFormat.format(Date(target.startMillis)))
                TvInfoColumn("Duration", formatDuration(target.endMillis - target.startMillis))
                target.seasonEpisodeLabel()?.let { TvInfoColumn("Episode", it) }
            }
            target.recording?.let { facts ->
                // tvOS: one row, Status included; the Window value wraps
                // after "to" so the row holds every column.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.padding(top = 5.dp),
                ) {
                    facts.rows().forEach { (label, value) ->
                        TvInfoColumn(label, if (label == "Window") value.replace(" to ", " to\n") else value)
                    }
                }
            }
        }
    }
    // The card's one focus target (Menu closes the sheet, no Close button):
    // a faint platter shows where focus sits.
    val descFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var descFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { descFocus.requestFocus() } }
    Text(
        text = if (target.description.isBlank()) "No program description provided in XMLTV." else target.description,
        fontSize = 12.sp, lineHeight = 16.sp,
        fontStyle = if (target.description.isBlank()) FontStyle.Italic else FontStyle.Normal,
        color = if (target.description.isBlank()) colors.tertiary else colors.onBackground,
        // tvOS TVFocusableBlock: the platter hugs the text, it is not a
        // full-width bar (Logan 2026-09-10).
        modifier = Modifier
            .offset(x = (-8).dp)
            .focusRequester(descFocus)
            .onFocusChanged { descFocused = it.isFocused }
            .clip(RoundedCornerShape(6.dp))
            .background(if (descFocused) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .focusable()
            .padding(8.dp),
    )
    val tokens = effectiveCategory.categoryTokens()
    if (tokens.isNotEmpty()) {
        val settingsVm: com.aeriotv.android.feature.settings.SettingsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
        val palette by settingsVm.categoryPalette.collectAsStateWithLifecycle(initialValue = com.aeriotv.android.core.category.CategoryPaletteState.Default)
        val (metadata, genres) = tokens.partition { it.lowercase(Locale.getDefault()) in METADATA_TOKENS }
        if (metadata.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                metadata.forEach { com.aeriotv.android.core.category.TvCategoryPill(it, palette, forceNeutral = true) }
            }
        }
        if (genres.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                genres.forEach { com.aeriotv.android.core.category.TvCategoryPill(it, palette) }
            }
        }
    }
}

/** tvOS infoColumn: uppercase tracked label over the value. */
@Composable
private fun TvInfoColumn(title: String, value: String) {
    // Explicit line heights: the theme's 24 sp body line height otherwise
    // pushed the value a full line below its header (Logan 2026-09-10).
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title.uppercase(Locale.getDefault()), fontSize = 8.sp, lineHeight = 10.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.tertiary,
        )
        Text(value, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
    }
}

/**
 * Shared program-detail body used by both the phone bottom-sheet and the
 * TV centered-dialog presentations. Program poster (when resolved) beside
 * the channel eyebrow + title + LIVE badge block, then the info-columns
 * block, description, and the metadata / category pills.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgramInfoBody(
    target: ProgramInfoTarget,
    effectiveCategory: String,
    posterUrl: String?,
    posterWidth: Dp,
    /** Rerun flag lazily enriched from the program-detail endpoint; the bulk
     *  grid Direct Connect guides ride carries no repeat field (mikec79). */
    enrichedRepeat: Boolean = false,
    // Defaults match the phone sheet; the TV dialog passes tighter values so
    // the whole body fits inside its 500dp height cap.
    sectionGap: Dp = 20.dp,
    metaRowPadding: Dp = 4.dp,
) {
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = target.channelName.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(6.dp))
            // Title spans the FULL text column and wraps on word boundaries
            // (badges no longer share its row, which had squeezed a long
            // single-word title into ugly mid-word breaks like "Somethi/ng").
            Text(
                text = target.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                softWrap = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // Feed-driven badges (LIVE / NEW / PREMIERE / FINALE / REPEAT),
            // plus an "ON NOW" pill for a program airing right now that is
            // not itself flagged a live broadcast (avoids a double LIVE).
            // "ON NOW" is a live-airing status, not one of the toggleable feed
            // pills, so it stays; the LIVE/NEW/etc feed flags follow the user's
            // "Show program badges" preference. Rendered UNDER the title in a
            // wrapping row so several badges flow onto extra lines instead of
            // stealing width from the title.
            val showEpgBadges = LocalShowEpgBadges.current
            val badges = buildList {
                if (target.isLiveNow() && !target.isLiveBroadcast) {
                    add(EpgFlag("ON NOW", EpgLiveRed))
                }
                if (showEpgBadges) {
                    val hidden = com.aeriotv.android.core.ui.LocalHiddenEpgBadges.current
                    addAll(
                        com.aeriotv.android.core.ui.epgFlagsOf(
                            isNew = target.isNew,
                            isLiveBroadcast = target.isLiveBroadcast,
                            isPremiere = target.isPremiere,
                            isFinale = target.isFinale,
                            isRepeat = target.isRepeat || enrichedRepeat,
                        ).filter { it.label !in hidden },
                    )
                }
            }
            if (badges.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                ) {
                    badges.forEach { flag ->
                        com.aeriotv.android.core.ui.EpgFlagBadge(flag)
                    }
                }
            }
            target.subTitle?.takeIf { !subtitleIsRedundant(it, target.title, target.description) }?.let { sub ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodyMedium.subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
        if (posterUrl != null) {
            Spacer(Modifier.width(16.dp))
            // GH #53: frame follows the REAL image ratio (width fixed,
            // height derived). XMLTV `<icon>` art is usually landscape
            // (EPG Guru sends 16:9); the old hard 2:3 portrait frame
            // cropped the sides off. TMDB fallback posters (genuinely
            // 2:3) render exactly as before. Clamped so a pathological
            // banner can't stretch the sheet.
            var posterRatio by remember(posterUrl) { mutableStateOf(2f / 3f) }
            // Decoration only: deliberately NOT focusable, or it would insert
            // itself into the TV dialog's D-pad scroll order.
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                // Fit, not Crop: the frame already follows the real ratio, and
                // the clamp above can leave a sliver that Crop would shave off.
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    val s = state.painter.intrinsicSize
                    if (s.width > 0f && s.height > 0f) {
                        posterRatio = (s.width / s.height).coerceIn(0.55f, 1.9f)
                    }
                },
                modifier = Modifier
                    .width(posterWidth)
                    .aspectRatio(posterRatio)
                    .clip(com.aeriotv.android.core.ui.artworkTileShape(INFO_CARD_CORNER)),
            )
        }
    }

    Spacer(Modifier.height(sectionGap))
    InfoColumnsRow(target, rowPadding = metaRowPadding)
    target.recording?.let { facts ->
        Spacer(Modifier.height(sectionGap))
        Column {
            facts.rows().forEach { (label, value) -> InfoRow(label = label, value = value, verticalPadding = metaRowPadding) }
        }
    }

    Spacer(Modifier.height(sectionGap))
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    Spacer(Modifier.height(sectionGap))

    SectionLabel("Description")
    Spacer(Modifier.height(8.dp))
    if (target.description.isBlank()) {
        Text(
            text = "No program description provided in XMLTV.",
            style = MaterialTheme.typography.bodyMedium.subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
        )
    } else {
        Text(
            text = target.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }

    val tokens = effectiveCategory.categoryTokens()
    if (tokens.isNotEmpty()) {
        val (metadata, genres) = tokens.partition { it.lowercase(Locale.getDefault()) in METADATA_TOKENS }
        if (metadata.isNotEmpty()) {
            Spacer(Modifier.height(sectionGap))
            SectionLabel("Metadata")
            Spacer(Modifier.height(8.dp))
            CategoryPillsFlow(tokens = metadata)
        }
        if (genres.isNotEmpty()) {
            Spacer(Modifier.height(sectionGap))
            SectionLabel("Categories")
            Spacer(Modifier.height(8.dp))
            CategoryPillsFlow(tokens = genres)
        }
    }
}

@Composable
private fun InfoColumnsRow(target: ProgramInfoTarget, rowPadding: Dp) {
    val clockMode = rememberClockMode()
    val timeFormat = remember(clockMode) { ClockFormat.short(clockMode) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val airs = "${timeFormat.format(Date(target.startMillis))} – ${timeFormat.format(Date(target.endMillis))}"
    val date = dateFormat.format(Date(target.startMillis))
    val duration = formatDuration(target.endMillis - target.startMillis)

    Column(modifier = Modifier.fillMaxWidth()) {
        InfoRow(label = "Channel", value = target.channelName, verticalPadding = rowPadding)
        InfoRow(label = "Airs", value = airs, verticalPadding = rowPadding)
        InfoRow(label = "Date", value = date, verticalPadding = rowPadding)
        InfoRow(label = "Duration", value = duration, verticalPadding = rowPadding)
        target.seasonEpisodeLabel()?.let {
            InfoRow(label = "Episode", value = it, verticalPadding = rowPadding)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, verticalPadding: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun ProgramInfoTarget.isLiveNow(): Boolean {
    val now = System.currentTimeMillis()
    return startMillis <= now && endMillis > now
}

/** Hilt EntryPoint so the sheet can reach the singleton Dispatcharr client +
 *  AuthBroker without injecting them through every call site. The sheet is a
 *  plain composable (no ViewModel), so the standard `@Inject` route isn't
 *  available; this is the Compose-idiomatic shortcut. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProgramInfoEntryPoint {
    fun dispatcharrClient(): DispatcharrClient
    fun dispatcharrAuth(): DispatcharrAuthBroker
    fun tmdbService(): TMDBService
    fun appPreferences(): AppPreferences
    fun epgProgrammeDao(): com.aeriotv.android.core.data.db.dao.EpgProgrammeDao
}

/**
 * Program detail through [com.aeriotv.android.core.network.ProgramDetailResolver]:
 * a 404 for a regenerated program id is re-resolved by tvg_id and start, and
 * the cached guide rows move to the new id. Null on any failure (never a
 * cached miss). Shared by the guide preview banner and Program Info.
 */
suspend fun fetchProgramDetailResolving(
    entry: ProgramInfoEntryPoint,
    baseUrl: String,
    playlistId: String,
    programId: Int,
    tvgIds: List<String>,
    startMillis: Long,
    title: String,
): com.aeriotv.android.core.network.ProgramDetailResolver.Result =
    withContext(Dispatchers.IO) {
        val broker = entry.dispatcharrAuth()
        val client = entry.dispatcharrClient()
        val result = com.aeriotv.android.core.network.ProgramDetailResolver.resolve(
            programId = programId,
            tvgIds = tvgIds,
            startMillis = startMillis,
            title = title,
            fetchDetail = { id -> broker.withApiKeyRetry(playlistId) { k -> client.getProgramDetail(baseUrl, k, id) } },
            cachedGridId = {
                tvgIds.firstNotNullOfOrNull { key ->
                    entry.epgProgrammeDao().currentProgramId(playlistId, key, startMillis, programId)
                }
            },
            fetchGrid = { broker.withApiKeyRetry(playlistId) { k -> client.getEpgGrid(baseUrl, k) } },
            listByTvgId = { tvg -> broker.withApiKeyRetry(playlistId) { k -> client.getProgramsByTvgId(baseUrl, k, tvg) } },
            log = { android.util.Log.i("AerioGuide", it) },
        )
        val stale = result.staleFrom
        if (stale != null && result.detail != null) {
            runCatching { entry.epgProgrammeDao().remapProgramId(playlistId, stale, result.programId) }
                .onFailure { android.util.Log.w("AerioGuide", "[PREVIEW-ART] program id remap failed", it) }
        }
        result
    }

private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "–"
    val totalMinutes = ((millis + 30_000L) / 60_000L).toInt()
    if (totalMinutes < 60) return "$totalMinutes min"
    val hours = totalMinutes / 60
    val mins = totalMinutes % 60
    return if (mins == 0) "$hours h" else "$hours h $mins min"
}

/**
 * The Program Info card's own corner radius. Program artwork on this surface
 * matches it (Appearance > Rounded corners on logos and artwork).
 */
private val INFO_CARD_CORNER = 14.dp


/**
 * Program art on the Live TV search Programs cards (Logan 2026-11-05): the
 * SAME calls and order Program Info uses, so a card shows the picture the
 * sheet shows. Server program detail poster (Dispatcharr only, through
 * [fetchProgramDetailResolving]), then art the guide preview already
 * resolved, then the program's feed icon, then TMDB by title (opt-in, the
 * user's own key, only when the detail lookup did not fail). Memoized per
 * airing; null means no program art (the card falls back to the channel logo).
 */
object ProgramArtResolver {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val misses = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private fun key(t: ProgramInfoTarget) = t.dispatcharrProgramId?.let { "pid:$it" } ?: "t:${t.epgChannelKey}|${t.startMillis}|${t.title}"

    fun cached(t: ProgramInfoTarget): String? = cache[key(t)]

    fun remember(t: ProgramInfoTarget, url: String) { cache[key(t)] = url }

    suspend fun resolve(
        context: android.content.Context,
        playlist: com.aeriotv.android.core.data.db.entity.PlaylistEntity?,
        t: ProgramInfoTarget,
    ): String? {
        val k = key(t)
        cache[k]?.let { return it }
        if (k in misses) return null
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, ProgramInfoEntryPoint::class.java)
        var detailFailed = false
        var source = "none"
        var url: String? = null
        val programId = t.dispatcharrProgramId
        val isDispatcharr = playlist != null && (
            playlist.sourceType == SourceType.DispatcharrApiKey.name ||
                playlist.sourceType == SourceType.DispatcharrUserPass.name
            )
        if (programId != null && playlist != null && isDispatcharr) {
            val detail = runCatching {
                fetchProgramDetailResolving(
                    entry, playlist.urlString, playlist.id, programId,
                    listOfNotNull(t.epgChannelKey), t.startMillis, t.title,
                )
            }.getOrNull()?.detail
            if (detail == null) detailFailed = true
            else detail.bestPosterString?.let { raw ->
                url = if (raw.startsWith("/")) playlist.urlString.trimEnd('/') + raw else raw
                source = "detail"
            }
        }
        if (url == null) com.aeriotv.android.feature.livetv.grid.cachedPreviewArt(programId, t.title)?.let { url = it; source = "banner" }
        if (url == null) t.iconUrl?.takeIf { it.isNotBlank() }?.let { url = it; source = "feed" }
        if (url == null && !detailFailed && t.title.isNotBlank()) {
            val prefs = entry.appPreferences()
            if (prefs.programPostersTmdbEnabled.first()) {
                val apiKey = prefs.tmdbApiKey.first()
                if (apiKey.isNotBlank()) {
                    url = runCatching { entry.tmdbService().posterUrlForTitle(t.title, apiKey) }.getOrNull()
                    if (url != null) source = "tmdb"
                }
            }
        }
        android.util.Log.i("AerioGuide", "[PREVIEW-ART] search card art source=$source pid=$programId (${t.title})")
        val out = url
        if (out != null) cache[k] = out else if (!detailFailed) misses += k
        return out
    }
}
