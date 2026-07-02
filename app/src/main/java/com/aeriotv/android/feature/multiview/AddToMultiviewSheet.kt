package com.aeriotv.android.feature.multiview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.feature.dvr.DvrViewModel
import com.aeriotv.android.feature.ondemand.OnDemandViewModel
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.playlist.nowPlaying
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel
import kotlinx.coroutines.launch

/**
 * Bottom sheet to pick channels for the multiview tile grid. Mirrors iOS
 * AddToMultiviewSheet (project_aeriotv_ios_canon.md "+ Add to Multiview"):
 * header with "Done" + title, group filter chips, a "Recent" section, a search
 * field, and the full "All Channels" list. Each row shows logo / number / name
 * / now-playing metadata, with a cyan + when not selected and a green check
 * when already in the multiview set. Footer counter ("N / 9 max") lives in the
 * header.
 *
 * Phase 11a delivered the basic picker; this revision adds the group chips +
 * Recent + search + section headers for full iOS parity. Recents are fed by
 * RecentChannelsStore (AppPreferences.recentChannelIds), written from
 * PlayerScreen on each channel flip.
 *
 * "Add a tile while watching" flow (player path): [currentChannel] is the
 * stream the user is watching. It is implicitly Tile 1 (seeded into the store
 * + audio-focused by the host), so it is EXCLUDED from the selectable list and
 * shown as a pinned, non-interactive "Now playing" row at the top. The user
 * picks ADDITIONAL channels; [onLaunch] (the "Play" button, enabled only once
 * there are >= 2 tiles) transitions to the multiview grid, while [onCancel]
 * (Back / swipe / "Cancel") closes the sheet and keeps single-stream playback.
 * Search is a toggle button left of the "All" pill (no resting text field, so
 * the IME never auto-opens on a scroll).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToMultiviewSheet(
    currentChannel: M3UChannel?,
    onLaunch: () -> Unit,
    onCancel: () -> Unit,
    // BACK / swipe / tap-scrim: close the picker but KEEP whatever is in the
    // store (the tiles the user just toggled/added stay). Distinct from
    // [onCancel], which the explicit "Cancel" text button uses to REVERT to the
    // pre-open snapshot (player flow only). Hosts that have no snapshot to
    // revert to (the multiview grid's "Add streams") pass the same close lambda
    // for both. Defaults to onCancel so existing call sites keep compiling.
    onDismiss: () -> Unit = onCancel,
    multiviewStore: MultiviewStoreHandle = rememberMultiviewStoreHandle(),
    playlistVm: PlaylistViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
    onDemandVm: OnDemandViewModel = hiltViewModel(),
    dvrVm: DvrViewModel = hiltViewModel(),
    watchVm: WatchProgressViewModel = hiltViewModel(),
) {
    // Phone/tablet container: the native ModalBottomSheet with its natural
    // swipe-to-dismiss. (TV uses a Dialog panel instead -- see the form-factor
    // split below -- so a bottom sheet's gesture model never reaches a D-pad.)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by playlistVm.state.collectAsStateWithLifecycle()
    val onDemandState by onDemandVm.state.collectAsStateWithLifecycle()
    val dvrState by dvrVm.state.collectAsStateWithLifecycle()
    val selected by multiviewStore.selected.collectAsState()
    val recentIds by settingsVm.recentChannelIds.collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedIds = selected.map { it.id }.toSet()
    val scope = rememberCoroutineScope()

    // Source switcher (Phase 2). Channels keeps the existing channel picker;
    // Movies / Series / Recordings draw from the On Demand + DVR view models so
    // a multiview grid can mix live channels with VOD/episode/recording tiles
    // (iOS PickerSource). drillSeriesId is the series whose episodes are listed
    // (null = the series grid); resolving tracks per-row URL-resolve spinners.
    var pickerSource by remember { mutableStateOf(PickerSource.Channels) }
    var drillSeriesId by remember { mutableStateOf<Int?>(null) }
    var resolving by remember { mutableStateOf(setOf<String>()) }

    // Dispatcharr auth headers for VOD / recording tiles (Navigation.kt parity):
    // X-API-Key + Authorization ApiKey, only for Dispatcharr-backed sources.
    val vodHeaders = remember(state.playlist?.apiKey, state.playlist?.sourceType) {
        val pl = state.playlist
        val key = pl?.apiKey?.takeIf { it.isNotBlank() }
        val isDispatcharr = pl?.sourceType == SourceType.DispatcharrApiKey.name ||
            pl?.sourceType == SourceType.DispatcharrUserPass.name
        if (isDispatcharr && key != null) {
            mapOf("X-API-Key" to key, "Authorization" to "ApiKey $key")
        } else {
            emptyMap()
        }
    }

    var selectedGroup by remember { mutableStateOf(PlaylistViewModel.ALL_GROUPS) }
    var query by remember { mutableStateOf("") }
    // Search is a toggle (button left of the "All" pill) so the IME never
    // auto-opens: there is no resting text field for the ModalBottomSheet to
    // land focus on. The field only exists while searchActive (no autofocus;
    // the user taps/clicks the field to bring up the keyboard).
    var searchActive by remember { mutableStateOf(false) }

    // Playable channels only, indexed for the recents join. The
    // now-playing channel (currentChannel) is EXCLUDED here so it never
    // appears as an "add" option -- it is implicitly Tile 1, shown pinned
    // at the top. Excluding from `playable` also drops it from
    // `recentChannels` (which resolves against byId), since it is otherwise
    // the #1 recent on every flip.
    val excludeId = currentChannel?.id
    val playable = remember(state.channels, excludeId) {
        state.channels.filter { it.url.isNotBlank() && it.id != excludeId }
    }
    val byId = remember(playable) { playable.associateBy { it.id } }

    // Group filter chips: "All" + each distinct groupTitle in source order.
    val groups = remember(playable) {
        listOf(PlaylistViewModel.ALL_GROUPS) +
            playable.asSequence().map { it.groupTitle }.filter { it.isNotBlank() }.distinct().toList()
    }

    // Recent rows: resolve LRU ids against the current playlist, capped to a
    // short list. Hidden while searching or when a specific group is selected
    // (matches iOS, where Recent is a top-of-list convenience for the "All"
    // view only).
    val recentChannels = remember(recentIds, byId) {
        recentIds.mapNotNull { byId[it] }.take(8)
    }
    val showRecent = query.isBlank() && selectedGroup == PlaylistViewModel.ALL_GROUPS && recentChannels.isNotEmpty()

    val filtered = remember(playable, selectedGroup, query) {
        val q = query.trim()
        playable.filter { ch ->
            (selectedGroup == PlaylistViewModel.ALL_GROUPS || ch.groupTitle.equals(selectedGroup, ignoreCase = true)) &&
                (q.isEmpty() || ch.name.contains(q, ignoreCase = true))
        }
    }

    // User report (v0.1.6, Amlogic S905X4): the channel-picker "seems to
    // instruct me to drag down, but I am not on a touchscreen." The default
    // ModalBottomSheet renders a drag handle (a grab bar that reads as
    // "drag") which is meaningless on a remote. On TV drop the handle and
    // rely on the "Done" button + BACK to dismiss; BackHandler guarantees the
    // remote BACK button closes the picker.
    val isTvDevice = (
        androidx.compose.ui.platform.LocalConfiguration.current.uiMode and
            Configuration.UI_MODE_TYPE_MASK
        ) == Configuration.UI_MODE_TYPE_TELEVISION
    // Tile count gates the Play action: need the seeded current channel PLUS
    // at least one picked channel (>= 2 tiles) for a real multiview grid.
    val canLaunch = selected.size >= 2

    // #46: soft-limit performance warning. Gates every ADD path in this sheet
    // once the grid already holds softLimit (4) tiles -- i.e. adding the
    // 5th+ -- unless the user picked Don't Show Again (persisted) or a
    // warning already fired within the last 2h (in-memory throttle on the
    // store, iOS warningRecentlyShown). Removals never warn. The commit
    // closure is stashed; Continue / Don't Show Again run it, Cancel drops
    // it. Unlike iOS (whose addVOD path forgot the suppression key), ONE
    // gate covers channel, movie, episode and recording adds uniformly.
    val perfWarnSuppressed by settingsVm.multiviewPerfWarningSuppressed
        .collectAsStateWithLifecycle(initialValue = false)
    var pendingWarnedAdd by remember { mutableStateOf<(() -> Unit)?>(null) }
    val gateAdd: (Boolean, () -> Unit) -> Unit = { isAdding, commit ->
        if (isAdding && selected.size >= multiviewStore.softLimit &&
            !perfWarnSuppressed && !multiviewStore.warningRecentlyShown()
        ) {
            multiviewStore.noteWarningShown()
            pendingWarnedAdd = commit
        } else {
            commit()
        }
    }
    // FORM-FACTOR SPLIT: phones/tablets get the native ModalBottomSheet (with
    // its natural swipe-to-dismiss); Android TV gets a centered Dialog panel. A
    // bottom sheet's drag / nested-scroll dismiss is a touch idiom that misfires
    // on a D-pad (scrolling the list up past the top "fades it downward and
    // closes"). The header + search + list BODY is shared; only the wrapper and
    // the list's height strategy differ. Only the "Play" button launches
    // multiview; every other dismissal path keeps single-stream playback.
    val body: @Composable ColumnScope.() -> Unit = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Add to Multiview",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onLaunch, enabled = canLaunch) {
                    Text(
                        text = "Play (${selected.size})",
                        color = if (canLaunch) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Source switcher (Phase 2): Channels / Movies / Series / Recordings.
            // Styled like the group-filter chips. Movies / Series / Recordings
            // are only offered when the active source actually has them so the
            // sheet never lands on an always-empty tab. Switching sources also
            // resets the series drill so re-entering Series starts at the grid.
            val hasVod = onDemandState.movies.isNotEmpty() ||
                onDemandState.series.isNotEmpty()
            val hasSeries = onDemandState.series.isNotEmpty()
            val hasRecordings = dvrState.recordings.any {
                val s = it.effectiveStatus()
                (s == DvrViewModel.Recording.Status.Completed ||
                    s == DvrViewModel.Recording.Status.Stopped) && it.playbackUrl != null
            }
            val availableSources = buildList {
                add(PickerSource.Channels)
                if (hasVod) add(PickerSource.Movies)
                if (hasSeries) add(PickerSource.Series)
                if (hasRecordings) add(PickerSource.Recordings)
            }
            if (availableSources.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentPadding = PaddingValues(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(items = availableSources, key = { it.name }) { src ->
                        FilterChip(
                            selected = src == pickerSource,
                            onClick = {
                                pickerSource = src
                                drillSeriesId = null
                            },
                            label = { Text(src.label, maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }

            // Control row (Channels only): a search TOGGLE button to the LEFT of
            // the "All" pill (Guide-chrome parity). Tapping it swaps the
            // group-pill row for the search field IN PLACE; tapping it again (or
            // its close) restores the pills and clears the query. No resting text
            // field = the IME never auto-opens while the user scrolls the list.
            if (pickerSource == PickerSource.Channels) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            searchActive = !searchActive
                            if (!searchActive) query = ""
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = if (searchActive) "Close search" else "Search channels",
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (searchActive) {
                        // No FocusRequester / autofocus on purpose: the keyboard
                        // opens only when the user taps (or D-pad-selects) the field.
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search channels") },
                            singleLine = true,
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    } else if (groups.size > 1) {
                        LazyRow(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(end = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            items(items = groups, key = { it }) { group ->
                                FilterChip(
                                    selected = group == selectedGroup,
                                    onClick = { selectedGroup = group },
                                    label = { Text(group, maxLines = 1) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            val atCapNow = selected.size >= multiviewStore.maxTiles
            LazyColumn(
                // TV: fill the remaining Dialog-panel height (weight, inside the
                // bounded-height panel Column). Phone: a fixed max height inside
                // the wrap-height bottom sheet.
                modifier = if (isTvDevice) Modifier.fillMaxWidth().weight(1f)
                else Modifier.fillMaxWidth().height(520.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (pickerSource) {
                    PickerSource.Channels -> {
                        // The now-playing channel, pinned + non-interactive: it is
                        // Tile 1 (already seeded into the store + audio-focused).
                        // Shown so the user sees it WILL be included, and not
                        // offered as an "add" toggle (a stray tap must not
                        // deselect it). Only under the Channels source.
                        if (currentChannel != null) {
                            item(key = "tile1_now") {
                                NowPlayingPinnedRow(
                                    channel = currentChannel,
                                    nowTitle = state.epgByChannel[currentChannel.guideMatchKey]
                                        ?.nowPlaying()?.title.orEmpty(),
                                )
                            }
                        }
                        if (showRecent) {
                            item(key = "hdr_recent") { SectionHeader("Recent") }
                            items(items = recentChannels, key = { "recent_${it.id}" }) { channel ->
                                val isSel = channel.id in selectedIds
                                val now = state.epgByChannel[channel.guideMatchKey]?.nowPlaying()
                                ChannelPickerRow(
                                    channel = channel,
                                    nowTitle = now?.title.orEmpty(),
                                    selected = isSel,
                                    atCap = !isSel && atCapNow,
                                    onToggle = { gateAdd(!isSel) { multiviewStore.toggle(channel) } },
                                )
                            }
                            item(key = "hdr_all") { SectionHeader("All Channels") }
                        }
                        items(items = filtered, key = { "all_${it.id}" }) { channel ->
                            val isSel = channel.id in selectedIds
                            val now = state.epgByChannel[channel.guideMatchKey]?.nowPlaying()
                            ChannelPickerRow(
                                channel = channel,
                                nowTitle = now?.title.orEmpty(),
                                selected = isSel,
                                atCap = !isSel && atCapNow,
                                onToggle = { gateAdd(!isSel) { multiviewStore.toggle(channel) } },
                            )
                        }
                    }

                    PickerSource.Movies -> {
                        items(items = onDemandState.visible, key = { "mv_${it.uuid}" }) { movie ->
                            val tileId = "vod-${movie.uuid}"
                            val isSel = tileId in selectedIds
                            VodPickerRow(
                                title = movie.displayName,
                                subtitle = movie.year?.let { "Movie · $it" } ?: "Movie",
                                posterUrl = movie.posterUrl,
                                selected = isSel,
                                resolving = movie.uuid in resolving,
                                atCap = !isSel && atCapNow,
                                onPick = {
                                    if (isSel) return@VodPickerRow
                                    gateAdd(true) {
                                        scope.launch {
                                            resolving = resolving + movie.uuid
                                            onDemandVm.resolveMovieUrl(movie.uuid).onSuccess { url ->
                                                val resume = watchVm.get(movie.uuid)?.positionMs ?: 0L
                                                multiviewStore.addTile(
                                                    MultiviewTile(
                                                        id = tileId,
                                                        kind = TileKind.Vod,
                                                        displayName = movie.displayName,
                                                        resolvedUrl = url,
                                                        httpHeaders = vodHeaders,
                                                        vodId = movie.uuid,
                                                        vodType = "movie",
                                                        posterUrl = movie.posterUrl,
                                                        resumePositionMs = resume,
                                                    ),
                                                )
                                            }
                                            resolving = resolving - movie.uuid
                                        }
                                    }
                                },
                            )
                        }
                    }

                    PickerSource.Series -> {
                        val drillId = drillSeriesId
                        if (drillId == null) {
                            items(items = onDemandState.visibleSeries, key = { "sr_${it.id}" }) { series ->
                                VodPickerRow(
                                    title = series.displayName,
                                    subtitle = "Series",
                                    posterUrl = series.posterUrl,
                                    selected = false,
                                    resolving = false,
                                    atCap = false,
                                    chevron = true,
                                    onPick = {
                                        drillSeriesId = series.id
                                        onDemandVm.loadEpisodes(series.id)
                                    },
                                )
                            }
                        } else {
                            item(key = "sr_back") {
                                BackRow(onClick = { drillSeriesId = null })
                            }
                            onDemandState.episodesErrorFor[drillId]?.let { err ->
                                item(key = "sr_err") {
                                    Text(
                                        text = "Couldn't load episodes: $err",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                }
                            }
                            if (drillId in onDemandState.episodesLoadingFor) {
                                item(key = "sr_loading") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) { CircularProgressIndicator() }
                                }
                            }
                            val episodes = onDemandState.episodesBySeries[drillId].orEmpty()
                                .sortedWith(
                                    compareBy(
                                        { it.seasonNumber ?: 0 },
                                        { it.episodeNumber ?: 0 },
                                    ),
                                )
                            items(items = episodes, key = { "ep_${it.uuid}" }) { ep ->
                                val tileId = "vod-${ep.uuid}"
                                val isSel = tileId in selectedIds
                                val s = ep.seasonNumber ?: 0
                                val e = ep.episodeNumber ?: 0
                                VodPickerRow(
                                    title = ep.displayName.ifBlank { "Episode $e" },
                                    subtitle = "S${s} E${e}",
                                    posterUrl = ep.stillImageUrl,
                                    selected = isSel,
                                    resolving = ep.uuid in resolving,
                                    atCap = !isSel && atCapNow,
                                    onPick = {
                                        if (isSel) return@VodPickerRow
                                        gateAdd(true) {
                                            scope.launch {
                                                resolving = resolving + ep.uuid
                                                onDemandVm.resolveEpisodeUrl(ep.uuid, ep.firstStreamId)
                                                    .onSuccess { url ->
                                                        val resume = watchVm.get(ep.uuid)?.positionMs ?: 0L
                                                        multiviewStore.addTile(
                                                            MultiviewTile(
                                                                id = tileId,
                                                                kind = TileKind.Vod,
                                                                displayName = ep.displayName.ifBlank { "Episode $e" },
                                                                resolvedUrl = url,
                                                                httpHeaders = vodHeaders,
                                                                vodId = ep.uuid,
                                                                vodType = "episode",
                                                                seriesId = drillId.toString(),
                                                                seasonNumber = s,
                                                                episodeNumber = e,
                                                                posterUrl = ep.stillImageUrl,
                                                                resumePositionMs = resume,
                                                            ),
                                                        )
                                                    }
                                                resolving = resolving - ep.uuid
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }

                    PickerSource.Recordings -> {
                        // Completed / Stopped server + local recordings that have a
                        // finalized playable URL. They play as VOD tiles (no
                        // continue-watching id). file:// recordings play headerless
                        // (Navigation.kt parity); http(s) ones carry the auth
                        // headers.
                        val playableRecordings = dvrState.recordings.filter {
                            val st = it.effectiveStatus()
                            (st == DvrViewModel.Recording.Status.Completed ||
                                st == DvrViewModel.Recording.Status.Stopped) &&
                                it.playbackUrl != null
                        }
                        items(items = playableRecordings, key = { "rec_${it.id}" }) { rec ->
                            val tileId = "dvr-${rec.id}"
                            val isSel = tileId in selectedIds
                            val playUrl = rec.playbackUrl.orEmpty()
                            val remote = playUrl.startsWith("http://", ignoreCase = true) ||
                                playUrl.startsWith("https://", ignoreCase = true)
                            VodPickerRow(
                                title = rec.title,
                                subtitle = "Recording",
                                posterUrl = null,
                                selected = isSel,
                                resolving = false,
                                atCap = !isSel && atCapNow,
                                onPick = {
                                    if (isSel) return@VodPickerRow
                                    gateAdd(true) {
                                        multiviewStore.addTile(
                                            MultiviewTile(
                                                id = tileId,
                                                kind = TileKind.Vod,
                                                displayName = rec.title,
                                                resolvedUrl = playUrl,
                                                httpHeaders = if (remote) vodHeaders else emptyMap(),
                                                vodId = null,
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
    }

    // BACK keeps the user's picks: the now-staged tiles stay in the store and
    // (for the grid "Add streams" path) keep playing; the player flow then
    // relies on its Play button, not BACK, to commit. This is the BackHandler
    // the header comment ("BACK to dismiss") always promised but never wired.
    BackHandler(onBack = onDismiss)
    if (isTvDevice) {
        // Android TV: a centered Dialog panel. No drag/swipe semantics, so the
        // D-pad can scroll the list freely; Back / Cancel / Play dismiss it.
        // onDismissRequest KEEPS selections (onDismiss); the "Cancel" text
        // button is the only revert path (onCancel).
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .fillMaxHeight(0.92f),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp), content = body)
            }
        }
    } else {
        // Phone / tablet: native bottom sheet. Swipe-to-dismiss KEEPS selections
        // (onDismiss); the "Cancel" text button reverts (onCancel).
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            content = body,
        )
    }

    // #46: the soft-limit warning itself. iOS-verbatim copy and outcomes:
    // Continue commits the stashed add; Don't Show Again persists the
    // suppression AND commits (matching iOS, which treats it as consent);
    // Cancel drops the add. Composes its own window, so it overlays both the
    // TV Dialog panel and the phone bottom sheet.
    pendingWarnedAdd?.let { commit ->
        AlertDialog(
            onDismissRequest = { pendingWarnedAdd = null },
            title = { Text("Performance may degrade") },
            text = {
                Text(
                    "Adding more than ${multiviewStore.softLimit} streams may cause " +
                        "audio drops, buffering, or overheating on some devices.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingWarnedAdd = null
                        commit()
                    },
                ) { Text("Continue", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        settingsVm.setMultiviewPerfWarningSuppressed(true)
                        pendingWarnedAdd = null
                        commit()
                    },
                ) { Text("Don't Show Again") }
                TextButton(onClick = { pendingWarnedAdd = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

/**
 * Pinned, NON-interactive row for the now-playing channel (Tile 1). Mirrors
 * [ChannelPickerRow] visually but carries no clickable / onToggle: it is
 * already seeded into the multiview store + audio-focused, and a stray tap
 * must not be able to deselect it (which would silently drop below the
 * 2-tile launch threshold). Always shows the green check + a "Tile 1" badge.
 */
@Composable
private fun NowPlayingPinnedRow(
    channel: M3UChannel,
    nowTitle: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = channel.channelNumber ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.tvgLogo.isNotBlank()) {
                AsyncImage(
                    model = channel.tvgLogo,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Text(
                    text = channel.name.take(2).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (nowTitle.isNotBlank()) "Tile 1 · Now playing · $nowTitle"
                else "Tile 1 · Now playing",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Included as Tile 1",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ChannelPickerRow(
    channel: M3UChannel,
    nowTitle: String,
    selected: Boolean,
    atCap: Boolean,
    onToggle: () -> Unit,
) {
    val baseColor = if (selected)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    else
        MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(baseColor)
            .clickable(enabled = !atCap, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = channel.channelNumber ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.tvgLogo.isNotBlank()) {
                AsyncImage(
                    model = channel.tvgLogo,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Text(
                    text = channel.name.take(2).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (nowTitle.isNotBlank()) {
                Text(
                    text = nowTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add",
                    tint = if (atCap)
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    else
                        MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Multiview picker content source. Mirrors iOS PickerSource. */
private enum class PickerSource(val label: String) {
    Channels("Channels"),
    Movies("Movies"),
    Series("Series"),
    Recordings("Recordings"),
}

/**
 * Picker row for a Movie / Episode / Series / Recording. Mirrors
 * [ChannelPickerRow] visually (logo box, title, subtitle, trailing add/check)
 * but works off arbitrary VOD metadata. [resolving] swaps the trailing icon for
 * a spinner while the play URL resolves; [chevron] is used for the Series grid
 * (drill into episodes) instead of an add toggle.
 */
@Composable
private fun VodPickerRow(
    title: String,
    subtitle: String,
    posterUrl: String?,
    selected: Boolean,
    resolving: Boolean,
    atCap: Boolean,
    onPick: () -> Unit,
    chevron: Boolean = false,
) {
    val baseColor = if (selected)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    else
        MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(baseColor)
            .clickable(enabled = !atCap && !resolving, onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            if (!posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                resolving -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                chevron -> Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                selected -> Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                )
                else -> Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add",
                    tint = if (atCap)
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    else
                        MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** "Back to series" row shown above the episode list when drilled into a series. */
@Composable
private fun BackRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "All Series",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}
