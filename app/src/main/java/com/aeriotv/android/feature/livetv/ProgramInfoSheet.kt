package com.aeriotv.android.feature.livetv

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
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.window.Dialog
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

    // Program poster: server detail artwork first (SD proxy / XMLTV image /
    // icon), then TMDB-by-title when that opt-in is enabled and a key is
    // stored. Null = no poster, render nothing. Mirrors iOS ProgramInfoView
    // (posterURL state at ProgramInfoView.swift:96).
    var posterUrl by remember(target.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(target.id) {
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
        if (programId != null && playlist != null && isDispatcharr &&
            (categoryNeeded || posterUrl == null)
        ) {
            val baseUrl = playlist.urlString
            val playlistId = playlist.id
            val broker = entry.dispatcharrAuth()
            val client = entry.dispatcharrClient()
            runCatching {
                withContext(Dispatchers.IO) {
                    broker.withApiKeyRetry(playlistId) { key ->
                        client.getProgramDetail(baseUrl, key, programId)
                    }
                }
            }.onSuccess { detail ->
                val joined = detail.categories.filter { it.isNotBlank() }.joinToString(",")
                if (categoryNeeded && joined.isNotBlank()) effectiveCategory = joined
                detail.bestPosterString?.let { raw ->
                    // Server-relative icon paths (protected /media/ etc.) need
                    // the playlist origin prefixed before Coil can load them.
                    posterUrl = if (raw.startsWith("/")) baseUrl.trimEnd('/') + raw else raw
                }
            }
        }
        // TMDB-by-title fallback for ANY source: opt-in pref + the user's own
        // key only (iOS loadTMDBPosterIfNeeded, ProgramInfoView.swift:369).
        if (posterUrl == null && target.title.isNotBlank()) {
            val prefs = entry.appPreferences()
            if (prefs.programPostersTmdbEnabled.first()) {
                val key = prefs.tmdbApiKey.first()
                if (key.isNotBlank()) {
                    posterUrl = entry.tmdbService().posterUrlForTitle(target.title, key)
                }
            }
        }
    }

    val isTv = (
        LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK
        ) == Configuration.UI_MODE_TYPE_TELEVISION

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
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth(0.62f)
                    // TV canvas is 960x540dp: cap below 540 so the card never
                    // clips past the top/bottom edges; scroll is the backstop.
                    .heightIn(max = 500.dp),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        // focusable so the remote's D-pad up/down scrolls
                        // a long description instead of doing nothing.
                        .focusable()
                        .padding(horizontal = 28.dp, vertical = 20.dp),
                ) {
                    ProgramInfoBody(
                        target = target,
                        effectiveCategory = effectiveCategory,
                        posterUrl = posterUrl,
                        posterWidth = 120.dp,
                        sectionGap = 12.dp,
                        metaRowPadding = 2.dp,
                    )
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
                )
                Spacer(Modifier.height(24.dp))
            }
        }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = target.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // Feed-driven badges (LIVE / NEW / PREMIERE / FINALE / REPEAT),
                // plus an "ON NOW" pill for a program airing right now that is
                // not itself flagged a live broadcast (avoids a double LIVE).
                val badges = buildList {
                    if (target.isLiveNow() && !target.isLiveBroadcast) {
                        add(EpgFlag("ON NOW", EpgLiveRed))
                    }
                    addAll(target.epgFlags())
                }
                if (badges.isNotEmpty()) {
                    Spacer(Modifier.size(10.dp))
                    EpgFlagsRow(badges)
                }
            }
            target.subTitle?.takeIf { it.isNotBlank() }?.let { sub ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodyMedium,
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
                contentScale = ContentScale.Crop,
                onSuccess = { state ->
                    val s = state.painter.intrinsicSize
                    if (s.width > 0f && s.height > 0f) {
                        posterRatio = (s.width / s.height).coerceIn(0.55f, 1.9f)
                    }
                },
                modifier = Modifier
                    .width(posterWidth)
                    .aspectRatio(posterRatio)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
    }

    Spacer(Modifier.height(sectionGap))
    InfoColumnsRow(target, rowPadding = metaRowPadding)

    Spacer(Modifier.height(sectionGap))
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    Spacer(Modifier.height(sectionGap))

    SectionLabel("Description")
    Spacer(Modifier.height(8.dp))
    if (target.description.isBlank()) {
        Text(
            text = "No program description provided in XMLTV.",
            style = MaterialTheme.typography.bodyMedium,
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
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
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
            style = MaterialTheme.typography.bodyMedium,
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
}

private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "–"
    val totalMinutes = ((millis + 30_000L) / 60_000L).toInt()
    if (totalMinutes < 60) return "$totalMinutes min"
    val hours = totalMinutes / 60
    val mins = totalMinutes % 60
    return if (mins == 0) "$hours h" else "$hours h $mins min"
}
