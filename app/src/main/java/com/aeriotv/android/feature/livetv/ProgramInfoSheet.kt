package com.aeriotv.android.feature.livetv

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.network.DispatcharrAuthBroker
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
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

    LaunchedEffect(target.id) {
        if (effectiveCategory.isNotBlank()) return@LaunchedEffect
        val programId = target.dispatcharrProgramId ?: return@LaunchedEffect
        val playlist = playlistState.playlist ?: return@LaunchedEffect
        val isDispatcharr = playlist.sourceType == SourceType.DispatcharrApiKey.name ||
            playlist.sourceType == SourceType.DispatcharrUserPass.name
        if (!isDispatcharr) return@LaunchedEffect
        val baseUrl = playlist.urlString
        val playlistId = playlist.id
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ProgramInfoEntryPoint::class.java,
        )
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
            if (joined.isNotBlank()) effectiveCategory = joined
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
                    .fillMaxWidth(0.55f)
                    .heightIn(max = 620.dp),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        // focusable so the remote's D-pad up/down scrolls
                        // a long description instead of doing nothing.
                        .focusable()
                        .padding(horizontal = 32.dp, vertical = 28.dp),
                ) {
                    ProgramInfoBody(target = target, effectiveCategory = effectiveCategory)
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
                ProgramInfoBody(target = target, effectiveCategory = effectiveCategory)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Shared program-detail body used by both the phone bottom-sheet and the
 * TV centered-dialog presentations. Channel eyebrow, title + LIVE badge,
 * the info-columns block, description, and the metadata / category pills.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgramInfoBody(target: ProgramInfoTarget, effectiveCategory: String) {
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
        if (target.isLiveNow()) {
            Spacer(Modifier.size(10.dp))
            LiveBadge()
        }
    }

    Spacer(Modifier.height(20.dp))
    InfoColumnsRow(target)

    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    Spacer(Modifier.height(20.dp))

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
            Spacer(Modifier.height(20.dp))
            SectionLabel("Metadata")
            Spacer(Modifier.height(8.dp))
            PillsFlow(tokens = metadata)
        }
        if (genres.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionLabel("Categories")
            Spacer(Modifier.height(8.dp))
            PillsFlow(tokens = genres)
        }
    }
}

@Composable
private fun InfoColumnsRow(target: ProgramInfoTarget) {
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val airs = "${timeFormat.format(Date(target.startMillis))} – ${timeFormat.format(Date(target.endMillis))}"
    val date = dateFormat.format(Date(target.startMillis))
    val duration = formatDuration(target.endMillis - target.startMillis)

    Column(modifier = Modifier.fillMaxWidth()) {
        InfoRow(label = "Channel", value = target.channelName)
        InfoRow(label = "Airs", value = airs)
        InfoRow(label = "Date", value = date)
        InfoRow(label = "Duration", value = duration)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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

@Composable
private fun LiveBadge() {
    Surface(
        color = LIVE_RED,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PillsFlow(tokens: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        tokens.forEach { token -> CategoryPill(token = token) }
    }
}

@Composable
private fun CategoryPill(token: String) {
    // Phase 8 (Appearance settings) introduces the per-category colour palette
    // and custom hex overrides. Until then, every pill renders neutral - matches
    // the iOS unresolved-token branch (ProgramInfoView.swift:575).
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = token,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun ProgramInfoTarget.isLiveNow(): Boolean {
    val now = System.currentTimeMillis()
    return startMillis <= now && endMillis > now
}

/** Same tokenizer the iOS sheet uses: split on comma / forward-slash /
 *  semicolon, trim, drop empties. Lifted off ProgramInfoTarget so the lazy
 *  category fetch can tokenize the upgraded string directly. */
private fun String.categoryTokens(): List<String> =
    split(',', '/', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** Hilt EntryPoint so the sheet can reach the singleton Dispatcharr client +
 *  AuthBroker without injecting them through every call site. The sheet is a
 *  plain composable (no ViewModel), so the standard `@Inject` route isn't
 *  available — this is the Compose-idiomatic shortcut. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProgramInfoEntryPoint {
    fun dispatcharrClient(): DispatcharrClient
    fun dispatcharrAuth(): DispatcharrAuthBroker
}

private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "–"
    val totalMinutes = ((millis + 30_000L) / 60_000L).toInt()
    if (totalMinutes < 60) return "$totalMinutes min"
    val hours = totalMinutes / 60
    val mins = totalMinutes % 60
    return if (mins == 0) "$hours h" else "$hours h $mins min"
}

// Mirrors iOS `ProgramInfoView.metadataTokens` (ProgramInfoView.swift:180).
private val METADATA_TOKENS = setOf(
    "episode", "series", "movie", "film", "feature", "feature film",
    "short", "short film", "special", "premiere", "season premiere",
    "series premiere", "finale", "season finale", "series finale",
    "rerun", "repeat", "live", "pilot", "made-for-tv movie",
    "made for tv movie", "miniseries", "limited series",
)

private val LIVE_RED = Color(0xFFFF4757)
