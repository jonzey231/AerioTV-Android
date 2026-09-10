package com.aeriotv.android.feature.dvr

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.RecordingFacts
import com.aeriotv.android.feature.livetv.ProgramInfoSheet
import com.aeriotv.android.feature.livetv.rememberLiveTvFormFactor
import com.aeriotv.android.feature.movies.AlphabetRail
import com.aeriotv.android.feature.movies.PhoneCardDeck
import com.aeriotv.android.feature.movies.PageSectionTitle
import com.aeriotv.android.feature.movies.PageRow
import com.aeriotv.android.feature.movies.PageDeck
import com.aeriotv.android.feature.movies.MediaPageScaffold
import com.aeriotv.android.feature.movies.nearestAvailable
import com.aeriotv.android.feature.movies.railLetters
import com.aeriotv.android.feature.movies.stripQualityPrefix
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.Normalizer
import java.util.Date

private typealias Rec = DvrViewModel.Recording

/** DVR library sort (Apple parity: DVRView.SortOrder). Title A to Z default. */
enum class DvrSortOrder(val wire: String, val label: String) {
    Newest("newest", "Newest First"), Oldest("oldest", "Oldest First"),
    Title("title", "Title A to Z"), Channel("channel", "Channel");
    companion object { fun fromWire(s: String?) = entries.firstOrNull { it.wire == s } ?: Title }
}

/** Recording kind for the genre pills (Apple parity: DVRContentKind). */
enum class DvrKind(val label: String) { Movies("Movies"), TVShows("TV Shows"), Sports("Sports"), News("News"), Kids("Kids"), Other("Other") }

// Port of the Apple DVRClassifier (DVRArtResolver.swift): category words
// first, newscast title shapes, real (non date-coded) episode identity, then
// title and subtitle hints, and finally airing length when the EPG says
// nothing. Memoised per row because the tab classifies every row per render.
private val sportsWords = listOf(
    "sport", "football", "soccer", "basketball", "baseball", "hockey", "nfl", "nba",
    "mlb", "nhl", "ncaa", "golf", "tennis", "racing", "nascar", "formula 1", "f1",
    "ufc", "mma", "boxing", "wrestling", "wwe", "olympic", "cricket", "rugby",
    "motogp", "lacrosse", "volleyball", "playoffs", "championship", "premier league",
    "la liga", "bundesliga", "serie a", "champions league",
)
private val movieWords = listOf("movie", "film", "cinema")
private val newsWords = listOf("news", "newscast", "current affairs", "weather", "politics", "public affairs")
private val newsTitleWords = listOf(
    "news", "newshour", "nightly", "60 minutes", "dateline", "meet the press",
    "face the nation", "this week", "good morning america", "today show",
)
private val kidsWords = listOf("kids", "children", "preschool", "family", "cartoon")
private val seriesWords = listOf(
    "series", "episode", "sitcom", "drama", "comedy", "reality", "talk", "animation",
    "documentary", "game show", "soap", "crime", "sci-fi", "science fiction", "fantasy",
    "mystery", "variety",
)
private val newscastTimeTitle = Regex("""\bat \d{1,2}(:\d{2})?\s*(am|pm)?$""")
private val classifyMemo = java.util.concurrent.ConcurrentHashMap<String, DvrKind>()

private fun classify(rec: Rec): DvrKind {
    val key = rec.id + "|" + rec.category + "|" + rec.title + "|" + rec.subTitle + "|" + rec.description.hashCode() +
        "|" + rec.season + "|" + rec.episode + "|" + rec.startMillis + "|" + rec.endMillis
    classifyMemo[key]?.let { return it }
    val category = rec.category.lowercase()
    val title = rec.title.lowercase()
    val sub = rec.subTitle.orEmpty().lowercase()
    val desc = rec.description.lowercase()
    // "S2026 E905" is a date code, not an episode identity.
    val realEpisode = (rec.season ?: 0) in 1..1899 && (rec.episode ?: 0) > 0
    val kind = when {
        sportsWords.any { category.contains(it) } -> DvrKind.Sports
        newsWords.any { category.contains(it) } -> DvrKind.News
        kidsWords.any { category.contains(it) } -> DvrKind.Kids
        newsTitleWords.any { title.contains(it) } -> DvrKind.News
        newscastTimeTitle.containsMatchIn(title) -> DvrKind.News
        desc.contains("news coverage") || desc.contains("local news") || desc.contains("regional news") ||
            desc.contains("headlines") || desc.startsWith("news") -> DvrKind.News
        realEpisode -> DvrKind.TVShows
        movieWords.any { category.contains(it) } -> DvrKind.Movies
        seriesWords.any { category.contains(it) } -> DvrKind.TVShows
        sportsWords.any { title.contains(it) } -> DvrKind.Sports
        sub.isNotEmpty() -> DvrKind.TVShows
        else -> {
            val minutes = (rec.endMillis - rec.startMillis) / 60_000
            when {
                minutes >= 80 -> DvrKind.Movies
                minutes >= 15 -> DvrKind.TVShows
                else -> DvrKind.Other
            }
        }
    }
    if (classifyMemo.size > 2000) classifyMemo.clear()
    classifyMemo[key] = kind
    return kind
}

private fun bucket(title: String): Char {
    val t = Normalizer.normalize(stripQualityPrefix(title), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    val c = t.firstOrNull { !it.isWhitespace() }?.uppercaseChar() ?: return '#'
    return if (c in 'A'..'Z') c else '#'
}

private fun Rec.progressKey(): String = when (source) {
    DvrViewModel.Source.Server -> "dvr-" + id.removePrefix("server-")
    else -> playbackUrl ?: id
}

private fun Rec.serverId(): Int = if (source == DvrViewModel.Source.Server) id.removePrefix("server-").toIntOrNull() ?: -1 else -1

/**
 * DVR tab, phone and tablet (media-center redesign, Apple parity with the
 * iPhone DVRView). Top to bottom: Recording Now / Continue Watching deck,
 * Scheduled shelf, Recent Recordings deck, All Recordings header with the
 * sort circle, kind pills, three-column poster grid, alphabet rail. Tap a
 * poster to play, long press for the menu (Program Info first). TV keeps
 * DvrTabContent.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DvrMediaTabContent(
    onPlayRecording: (String, String, Int) -> Unit,
    onWatchLive: (String, String, Boolean, Long, Int?) -> Unit,
    onWatchFromBeginning: (String, String, Boolean, Long, Int?, Boolean) -> Unit,
    viewModel: DvrViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
    playlistVm: PlaylistViewModel = hiltViewModel(),
    watchVm: WatchProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playlistState by playlistVm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val compact = rememberLiveTvFormFactor().widthClass == WindowWidthSizeClass.Compact
    val sortWire by settingsVm.dvrSortOrder.collectAsStateWithLifecycle(initialValue = "title")
    val sortOrder = DvrSortOrder.fromWire(sortWire)
    val recent by watchVm.observeRecent(80).collectAsStateWithLifecycle(initialValue = emptyList())
    val now = System.currentTimeMillis()

    val channelName: (Rec) -> String = { rec ->
        playlistState.channels.firstOrNull { it.dispatcharrChannelId != null && it.dispatcharrChannelId == rec.dispatcharrChannelId }?.name ?: ""
    }
    val channelLogo: (Rec) -> String? = { rec ->
        playlistState.channels.firstOrNull { it.dispatcharrChannelId != null && it.dispatcharrChannelId == rec.dispatcharrChannelId }?.tvgLogo?.takeIf { it.isNotBlank() }
    }
    val progressOf: (Rec) -> Float = { rec ->
        val row = recent.firstOrNull { it.videoId == rec.progressKey() }
        val total = (rec.endMillis - rec.startMillis).toDouble()
        if (row == null || row.positionMs <= 0L) 0f
        else (row.positionMs / (if (row.durationMs > 0) row.durationMs.toDouble() else total.coerceAtLeast(1.0))).toFloat().coerceIn(0f, 1f)
    }

    val recordings = state.recordings
    val recordingNow = remember(recordings, now) { recordings.filter { it.effectiveStatus(now) == DvrViewModel.Recording.Status.Recording }.sortedByDescending { it.startMillis } }
    val scheduled = remember(recordings, now) { recordings.filter { it.effectiveStatus(now) == DvrViewModel.Recording.Status.Scheduled }.sortedBy { it.startMillis } }
    val completed = remember(recordings, now) { recordings.filter { val s = it.effectiveStatus(now); s == DvrViewModel.Recording.Status.Completed || s == DvrViewModel.Recording.Status.Stopped } }
    val continueWatching = remember(recordingNow, completed, recent) {
        recordingNow + completed.filter { val p = progressOf(it); p > 0f && p < 0.97f }.sortedByDescending { it.startMillis }
    }
    val recentRecordings = remember(completed) { completed.sortedByDescending { it.startMillis }.take(20) }
    val library = remember(recordingNow, completed) { recordingNow + completed }
    var selectedKind by remember { mutableStateOf<DvrKind?>(null) }
    val kindsPresent = remember(library) { library.map(::classify).toSet() }
    val filteredLibrary = remember(library, selectedKind, sortOrder) {
        library.filter { selectedKind == null || classify(it) == selectedKind }.let { list ->
            when (sortOrder) {
                DvrSortOrder.Newest -> list.sortedByDescending { it.startMillis }
                DvrSortOrder.Oldest -> list.sortedBy { it.startMillis }
                DvrSortOrder.Title -> list.sortedWith(compareBy({ stripQualityPrefix(it.title).lowercase() }, { it.startMillis }))
                DvrSortOrder.Channel -> list.sortedWith(compareBy({ channelName(it).lowercase() }, { it.startMillis }))
            }
        }
    }
    val available = remember(filteredLibrary) { filteredLibrary.map { bucket(it.title) }.toSet() }

    var infoTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var pendingDelete by remember { mutableStateOf<Rec?>(null) }
    var pendingEdit by remember { mutableStateOf<Rec?>(null) }
    var showSort by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val bottomInset = LocalTabBarBottomInset.current

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    fun play(rec: Rec) {
        val url = rec.playbackUrl
        if (rec.effectiveStatus(now) == DvrViewModel.Recording.Status.Recording) {
            rec.inProgressUrl?.let { onWatchFromBeginning(it, rec.title, rec.isDvr, rec.endMillis, rec.dispatcharrChannelId, true) }
                ?: toast("This recording is not playable yet.")
        } else if (!url.isNullOrBlank()) onPlayRecording(url, rec.title, rec.serverId())
        else toast("This recording has no playable file yet.")
    }
    fun playFromStart(rec: Rec) {
        if (rec.effectiveStatus(now) == DvrViewModel.Recording.Status.Recording) {
            rec.inProgressUrl?.let { onWatchFromBeginning(it, rec.title, rec.isDvr, rec.endMillis, rec.dispatcharrChannelId, false) }
        } else {
            watchVm.delete(rec.progressKey()); play(rec)
        }
    }
    fun jumpToLive(rec: Rec) { rec.inProgressUrl?.let { onWatchLive(it, rec.title, rec.isDvr, rec.endMillis, rec.dispatcharrChannelId) } }
    fun showInfo(rec: Rec) {
        val statusLabel = when (rec.effectiveStatus(now)) {
            DvrViewModel.Recording.Status.Scheduled -> "Scheduled"; DvrViewModel.Recording.Status.Recording -> "Recording"; DvrViewModel.Recording.Status.Completed -> "Completed"
            DvrViewModel.Recording.Status.Stopped -> "Stopped"; DvrViewModel.Recording.Status.Failed -> "Failed"; DvrViewModel.Recording.Status.Unknown -> "Unknown"
        }
        val ext = (rec.fileName ?: rec.playbackUrl ?: "").substringAfterLast('.', "").takeIf { it.length in 2..5 && !it.contains('/') }
        infoTarget = ProgramInfoTarget(
            channelName = channelName(rec), title = rec.title.ifBlank { "Recording" },
            startMillis = rec.startMillis, endMillis = rec.endMillis,
            description = rec.description, category = rec.category,
            channelDispatcharrId = rec.dispatcharrChannelId, dispatcharrProgramId = rec.programId,
            subTitle = rec.subTitle, season = rec.season, episode = rec.episode,
            recording = RecordingFacts(
                recordedOnMillis = rec.startMillis, windowStartMillis = rec.startMillis, windowEndMillis = rec.endMillis,
                fileSizeBytes = rec.fileSizeBytes,
                format = if (ext.equals("m3u8", true)) "HLS" else ext,
                location = if (rec.source == DvrViewModel.Source.Local) "This device" else (playlistState.playlist?.name ?: "Dispatcharr"),
                status = statusLabel,
                videoCodec = rec.videoCodec, resolution = rec.resolution, frameRate = rec.frameRate,
                videoBitrateKbps = rec.videoBitrateKbps, audioCodec = rec.audioCodec, audioChannels = rec.audioChannels,
            ),
        )
    }

    @Composable
    fun menuItems(rec: Rec, close: () -> Unit) {
        val s = rec.effectiveStatus(now)
        val isServer = rec.source == DvrViewModel.Source.Server
        DropdownMenuItem(text = { Text("Program Info") }, onClick = { close(); showInfo(rec) })
        if (s == DvrViewModel.Recording.Status.Completed || s == DvrViewModel.Recording.Status.Stopped) {
            DropdownMenuItem(text = { Text("Play") }, onClick = { close(); play(rec) })
            if (isServer) {
                DropdownMenuItem(text = { Text("Watch from Beginning") }, onClick = { close(); playFromStart(rec) })
                DropdownMenuItem(text = { Text("Save to Device") }, onClick = { close(); scope.launch { viewModel.saveToDevice(rec).onFailure { toast("Save failed: ${it.message}") }.onSuccess { toast("Saving to device") } } })
                DropdownMenuItem(text = { Text("Remove Commercials") }, onClick = { close(); scope.launch { viewModel.applyComskip(rec).onFailure { toast("Comskip failed: ${it.message}") }.onSuccess { toast("Comskip started") } } })
            }
        }
        if (s == DvrViewModel.Recording.Status.Recording) {
            if (rec.inProgressUrl != null) {
                DropdownMenuItem(text = { Text("Start at Live") }, onClick = { close(); jumpToLive(rec) })
                DropdownMenuItem(text = { Text("Watch from Beginning") }, onClick = { close(); playFromStart(rec) })
            }
            DropdownMenuItem(text = { Text("Stop Recording") }, onClick = { close(); scope.launch { viewModel.stopRecording(rec).onFailure { toast("Stop failed: ${it.message}") } } })
        }
        if (s == DvrViewModel.Recording.Status.Scheduled) {
            if (isServer) DropdownMenuItem(text = { Text("Edit Recording") }, onClick = { close(); pendingEdit = rec })
            DropdownMenuItem(text = { Text("Cancel Recording", color = MaterialTheme.colorScheme.error) }, onClick = { close(); pendingDelete = rec })
        } else {
            DropdownMenuItem(
                text = { Text(if (isServer) "Delete from Server" else "Delete", color = MaterialTheme.colorScheme.error) },
                onClick = { close(); pendingDelete = rec },
            )
        }
    }

    if (recordings.isEmpty() && !state.isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No Recordings", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(6.dp))
                Text("Record a program from the guide or Live TV.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        // Same page as Movies and TV Shows (Logan 2026-09-09): the only
        // difference is the second deck's title, Recently Recorded.
        val heroCard: @Composable (Rec) -> Unit = { rec ->
            DvrHeroCard(rec, channelName(rec), channelLogo(rec), progressOf(rec), now,
                onPrimary = { if (rec.effectiveStatus(now) == DvrViewModel.Recording.Status.Recording) playFromStart(rec) else play(rec) },
                onSecondary = { if (rec.effectiveStatus(now) == DvrViewModel.Recording.Status.Recording) jumpToLive(rec) else playFromStart(rec) },
                onStop = { scope.launch { viewModel.stopRecording(rec) } },
                onInfo = { showInfo(rec) })
        }
        val recentCard: @Composable (Rec) -> Unit = { rec ->
            DvrHeroCard(rec, channelName(rec), channelLogo(rec), progressOf(rec), now,
                onPrimary = { play(rec) }, onSecondary = { playFromStart(rec) },
                onStop = {}, onInfo = { showInfo(rec) })
        }
        val kindPills = DvrKind.entries.filter { it in kindsPresent }
        MediaPageScaffold(
            gridState = gridState,
            compact = compact,
            decks = listOf(
                PageDeck(
                    if (continueWatching.isNotEmpty() && continueWatching.all { it.effectiveStatus(now) == DvrViewModel.Recording.Status.Recording }) "Recording Now" else "Continue Watching",
                    continueWatching, { it.id }, heroCard,
                ),
                PageDeck("Recently Recorded", if (recentRecordings.size > 1) recentRecordings else emptyList(), { it.id }, recentCard),
            ),
            rows = if (scheduled.isEmpty()) emptyList() else listOf(PageRow("scheduled") {
                Column {
                    PageSectionTitle("Scheduled")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(scheduled.size, key = { scheduled[it].id }) { i ->
                            val rec = scheduled[i]
                            Box(modifier = Modifier.width(150.dp)) {
                                DvrPosterCard(rec, channelLogo(rec), 0f, now, onClick = { showInfo(rec) }, menu = { close -> menuItems(rec, close) })
                            }
                        }
                    }
                }
            }),
            headerTitle = "All Recordings",
            headerCount = filteredLibrary.size,
            sortMenu = {
                DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                    DvrSortOrder.entries.forEach { o ->
                        DropdownMenuItem(
                            text = { Text(o.label) },
                            trailingIcon = { if (o == sortOrder) Icon(Icons.Filled.Check, contentDescription = null) },
                            onClick = { showSort = false; settingsVm.setDvrSortOrder(o.wire) },
                        )
                    }
                }
            },
            onSort = { showSort = true },
            pills = if (kindPills.size > 1) kindPills.map { it.label } else emptyList(),
            selectedPill = selectedKind?.label,
            onPill = { label -> selectedKind = kindPills.firstOrNull { it.label == label } },
            gridItems = filteredLibrary,
            gridKey = { it.id },
            cell = { rec -> DvrPosterCard(rec, channelLogo(rec), progressOf(rec), now, onClick = { play(rec) }, menu = { close -> menuItems(rec, close) }) },
            emptyContent = {
                if (state.isLoading) CircularProgressIndicator()
                else Text("No Recordings", color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            railLetters = available,
            railIndexOf = { letter -> filteredLibrary.indexOfFirst { bucket(it.title) == letter } },
            isRefreshing = state.isLoading && recordings.isNotEmpty(),
            onRefresh = { viewModel.refresh() },
        )
    }

    infoTarget?.let { ProgramInfoSheet(target = it, onDismiss = { infoTarget = null }) }
    pendingEdit?.let { rec ->
        EditRecordingSheet(
            recording = rec,
            onDismiss = { pendingEdit = null },
            onSave = { newStart, newEnd, newTitle, newDescription ->
                val id = rec.serverId()
                pendingEdit = null
                if (id <= 0) { toast("Invalid recording id."); return@EditRecordingSheet }
                scope.launch {
                    viewModel.editServerRecording(recordingId = id, startMillis = newStart, endMillis = newEnd, title = newTitle, description = newDescription)
                        .fold(onSuccess = { toast("Recording updated.") }, onFailure = { toast("Update failed: ${it.message ?: it::class.simpleName}") })
                }
            },
        )
    }
    pendingDelete?.let { rec ->
        val scheduledNow = rec.effectiveStatus(now) == DvrViewModel.Recording.Status.Scheduled
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (scheduledNow) "Cancel Recording" else "Delete Recording") },
            text = { Text(if (scheduledNow) "Cancel the scheduled recording of \"${rec.title}\"?" else "Delete \"${rec.title}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val target = rec; pendingDelete = null
                    scope.launch { viewModel.deleteRecording(target).onFailure { toast("Delete failed: ${it.message}") } }
                }) { Text(if (scheduledNow) "Cancel Recording" else "Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep") } },
        )
    }
}

private fun metaLine(rec: Rec, now: Long): String {
    val day = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(rec.startMillis))
    val s = rec.effectiveStatus(now)
    val second = when (s) {
        DvrViewModel.Recording.Status.Recording -> "Recording"
        DvrViewModel.Recording.Status.Scheduled -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(rec.startMillis))
        else -> { val m = ((rec.endMillis - rec.startMillis) / 60_000L).toInt(); if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }
    }
    val se = if ((rec.season ?: 0) > 0) "S${rec.season} E${rec.episode ?: 0}" else null
    return listOfNotNull(day, second, se).joinToString(" · ")
}

/** 2:3 poster (Apple parity: DVRPosterCard): art or channel logo, REC badge, progress bar, two-line title, meta line. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DvrPosterCard(
    rec: Rec, channelLogo: String?, progress: Float, now: Long,
    onClick: () -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)) {
            when {
                !rec.posterUrl.isNullOrBlank() -> AsyncImage(model = rec.posterUrl, contentDescription = rec.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                !channelLogo.isNullOrBlank() -> AsyncImage(model = channelLogo, contentDescription = rec.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(18.dp))
                else -> Text(rec.title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 4, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
            if (rec.effectiveStatus(now) == DvrViewModel.Recording.Status.Recording) {
                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 5.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFFF4757)))
                    Text("REC", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color(0xFFFF4757))
                }
            }
            if (progress > 0f) {
                Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.45f))) {
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress).background(MaterialTheme.colorScheme.primary))
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menu { menuOpen = false } }
        }
        // Centered under the poster (Logan 2026-09-10, all platforms).
        Text(rec.title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().height(30.dp))
        Text(metaLine(rec, now), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
    }
}

/** Hero card (Apple parity: DVRHero on iPhone, inDeck). Tap on the card opens Program Info. */
@Composable
fun DvrHeroCard(
    rec: Rec, channelName: String, channelLogo: String?, progress: Float, now: Long,
    onPrimary: () -> Unit, onSecondary: () -> Unit, onStop: () -> Unit, onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = MaterialTheme.colorScheme.background
    val recording = rec.effectiveStatus(now) == DvrViewModel.Recording.Status.Recording
    val canPlay = recording && rec.inProgressUrl != null || !recording && rec.playbackUrl != null
    Box(modifier = modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onInfo)) {
        when {
            !rec.posterUrl.isNullOrBlank() -> AsyncImage(model = rec.posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            !channelLogo.isNullOrBlank() -> AsyncImage(model = channelLogo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth(0.28f).fillMaxHeight().align(Alignment.CenterEnd).padding(24.dp))
        }
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.05f), bg.copy(alpha = 0.92f)))))
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (recording) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFFF4757)))
                    Text("Recording now", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF4757))
                }
            }
            Text(rec.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            rec.subTitle?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val meta = listOfNotNull(channelName.takeIf { it.isNotBlank() }, metaLine(rec, now)).joinToString(" · ")
            Text(meta, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (recording) {
                    if (canPlay) {
                        HeroPill("Watch from Start", Icons.Filled.PlayArrow, primary = true, onPrimary)
                        HeroRound(Icons.Filled.Sensors, "Jump to Live", onSecondary)
                    }
                    HeroRound(Icons.Filled.Stop, "Stop Recording", onStop)
                } else {
                    HeroPill(if (progress > 0f) "Resume" else "Play", Icons.Filled.PlayArrow, primary = true, onPrimary)
                    if (progress > 0f) HeroRound(Icons.Filled.Replay, "Play from Beginning", onSecondary)
                }
                HeroRound(Icons.Outlined.Info, "Details", onInfo)
            }
        }
    }
}

@Composable
private fun HeroPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.height(40.dp).clip(CircleShape)
            .background(if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun HeroRound(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp)) }
}
