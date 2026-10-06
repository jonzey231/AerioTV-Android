package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Wifi
import com.aeriotv.android.ui.scale.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.text.format.DateUtils
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.capability.Capability
import com.aeriotv.android.core.data.db.entity.capabilities
import com.aeriotv.android.core.data.db.entity.dispatcharrEffectiveDvrAccess
import com.aeriotv.android.core.data.db.entity.isDispatcharrDirectConnect
import com.aeriotv.android.core.preferences.DispatcharrAccountFacts
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.ui.settings.settingsFormWidth
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import com.aeriotv.android.ui.settings.SettingsHeaderTextButton
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import java.text.DateFormat
import java.util.Date
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset

/**
 * Playlist Detail. Mirrors iOS Settings > tap-a-playlist row:
 * CONNECTION DETAILS / ACTIONS (Test Connection) / EPG CACHE (Refresh).
 *
 * v1 surfaces the active playlist only; multi-playlist support lands later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onEdit: () -> Unit = {},
    // Rev 2 canon amendment 1: the rail and sidebar select ANY playlist and
    // show its detail, so this page can no longer assume it is looking at the
    // active one. Null keeps the old behavior for callers that mean "active".
    playlistId: String? = null,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val allPlaylists by viewModel.allPlaylists.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    // A requested row shows only itself: before the DAO flow's first
    // emission the old fallback to the active snapshot drew the ACTIVE
    // playlist's page for a frame, then swapped (Logan, Nothing Phone
    // 2026-10-02). Null = active, as before.
    //
    // The ROW drawn is always the DAO's current row once the flow has
    // emitted, active playlist included. The UiState snapshot is the entity a
    // save or refresh returned, written before the capability probe lands, so
    // drawing it left the User Permissions block on "Make this playlist active
    // to load permissions" for the active playlist after an account edit
    // (Nothing Phone 2026-10-05). The snapshot is only the first-frame
    // fallback for the active playlist.
    val requestedId = playlistId ?: state.playlist?.id
    val playlist = allPlaylists.firstOrNull { it.id == requestedId }
        ?: if (playlistId == null || playlistId == state.playlist?.id) state.playlist else null
    // Whether the playlist ON SCREEN is the active one. The refresh/test
    // actions below are deliberately gated on this: every one of them resolves
    // `repository.activePlaylist()` and loads its result into the single
    // channel/EPG store, so running them while viewing a DIFFERENT playlist
    // would act on the wrong source and leave the store disagreeing with the
    // page. Set Active is the call to action instead; refresh once it is live.
    // LIVE from the DAO, not the UiState snapshot (Logan 2026-09-16): "Set
    // Active" has to become "Active Playlist" the moment the switch commits.
    val activeIdLive by viewModel.activeIdLive
        .collectAsStateWithLifecycle(initialValue = state.playlist?.id)
    val isActivePlaylist = playlist != null && playlist.id == activeIdLive
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRefreshAll by remember { mutableStateOf(false) }

    // LAN/WAN route is a point-in-time probe (no NetworkCallback flow in the
    // app); re-check on entry and ON_RESUME, mirroring NetworkSettingsScreen's
    // HomeWifiSection. Action statuses reset when the screen goes away.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(playlist?.id) { viewModel.refreshActiveRoute() }
    // Throttled permissions re-read for the ACTIVE playlist only; an inactive
    // playlist shows what its last probe persisted.
    LaunchedEffect(playlist?.id, isActivePlaylist) {
        if (isActivePlaylist && playlist?.isDispatcharrDirectConnect() == true) {
            viewModel.refreshPermissionsForDetail()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshActiveRoute()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.clearDetailActionStatuses()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = playlist?.name ?: "Playlist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                // No back arrow on Android TV -- the remote BACK pops it (user
                // request). Phones/tablets keep it.
                if (!rememberIsTvDevice()) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            actions = {
                // TV gets an Edit Playlist row in the ACTIONS section instead;
                // a corner action sits off the natural D-pad path.
                if (!rememberIsTvDevice()) {
                    SettingsHeaderTextButton(
                        label = "Edit",
                        // Any playlist, active or not: Edit Playlist saves to
                        // the row whose id it was opened with.
                        enabled = playlist != null,
                        onClick = onEdit,
                    )
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        // The requested row has not arrived from the DAO yet: draw nothing
        // for that frame rather than the empty-state text.
        val awaitingRow = playlist == null && playlistId != null && allPlaylists.isEmpty()
        if (playlist == null) {
            if (awaitingRow) Box(modifier = Modifier.fillMaxSize())
            else Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "No playlist loaded",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
        val isTv = rememberIsTvDevice()
        LazyColumn(
            modifier = Modifier.settingsFormWidth(),
            // 104dp bottom clears the MainScaffold NavigationBar on phones;
            // the TV nav lives at the top, so a slim overscan inset suffices.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = if (isTv) 32.dp else LocalTabBarBottomInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Apple's order: iOS/iPadOS put the read-only Connection Details
            // and Permissions first; tvOS puts Actions first so a focusable
            // row is on the first screen (ServerDetailView readOnlyInfoSections).
            val infoSections: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            item {
                Section(
                    header = "Connection Details",
                    // Only worth explaining when there are two URLs to
                    // choose between.
                    footer = if (!playlist.lanUrlString.isNullOrBlank()) {
                        "A checkmark marks the connection in use right now. The local URL is used " +
                            "automatically whenever the server answers on your home network; run " +
                            "Refresh LAN Detection after a network change."
                    } else {
                        null
                    },
                ) {
                    // On TV the card is itself a (read-only) focus stop: with
                    // only the Action rows focusable, D-pad UP from "Test
                    // Connection" had nowhere to go, so the list stayed
                    // scrolled with this card clipped under the title bar.
                    var infoFocused by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .then(
                                if (isTv) {
                                    Modifier
                                        .onFocusChanged { infoFocused = it.isFocused }
                                        .background(
                                            if (infoFocused) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                            } else {
                                                Color.Transparent
                                            },
                                        )
                                        .focusable()
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        DetailRow("Type", playlist.detailTypeLabel())
                        // Checkmark marks whichever URL is currently in effect
                        // per PlaylistRepository.effectiveBaseUrl's decision.
                        val activeRoute = state.activeRoute
                        DetailRow(
                            label = "Remote URL",
                            value = playlist.urlString,
                            icon = Icons.Filled.CheckCircle.takeIf { activeRoute?.isLan == false },
                            iconTint = MaterialTheme.colorScheme.primary,
                        )
                        playlist.lanUrlString?.takeIf { it.isNotBlank() }?.let { lan ->
                            DetailRow(
                                label = "Local URL",
                                value = lan,
                                icon = Icons.Filled.CheckCircle.takeIf { activeRoute?.isLan == true },
                                iconTint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        playlist.username?.takeIf { it.isNotBlank() }?.let { user ->
                            DetailRow("Username", user)
                        }
                        // Apple reads "Verified"/"Unverified" as plain text. Android
                        // has no stored verify flag; a source that has loaded
                        // channels has passed a real connection, so that is the
                        // signal (never asserted unconditionally).
                        DetailRow(
                            label = "Status",
                            value = if (playlist.channelCount > 0) "Verified" else "Unverified",
                        )
                        playlist.lastRefreshedAt?.let { ts ->
                            // Apple: relative, named ("2 weeks ago").
                            DetailRow(
                                "Last Connected",
                                DateUtils.getRelativeTimeSpanString(
                                    ts,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS,
                                ).toString(),
                            )
                        }
                        // Apple shows the count for the active playlist only.
                        if (isActivePlaylist) DetailRow("Channels", playlist.channelCount.toString())
                        if (!playlist.epgUrl.isNullOrBlank()) {
                            DetailRow("EPG", playlist.epgUrl!!)
                        }
                    }
                }
            }

            // Dispatcharr User Permissions (read-only), Apple
            // ServerDetailView.swift parity. Direct Connect only: Xtream Codes
            // rows -- including Dispatcharr's own XC emulation, which the app
            // sees as an XC source -- and M3U rows have no per-user permission
            // model to show. Only the active playlist is re-read (throttled, on
            // entry); an inactive one shows whatever its last probe persisted.
            if (playlist.isDispatcharrDirectConnect()) item {
                val facts by viewModel.dispatcharrAccountFacts(playlist.id)
                    .collectAsStateWithLifecycle(initialValue = DispatcharrAccountFacts())
                Section(
                    header = "Dispatcharr User Permissions",
                    footer = "Set by your Dispatcharr admin. Permissions also refresh when you " +
                        "open the app or use Refresh Playlist.",
                ) {
                    // One block, one focus stop on TV (or none on phones):
                    // per-row focus stops here would trap the D-pad in a wall
                    // of unactionable text.
                    var permsFocused by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .then(
                                if (isTv) {
                                    Modifier
                                        .onFocusChanged { permsFocused = it.isFocused }
                                        .background(
                                            if (permsFocused) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                            } else {
                                                Color.Transparent
                                            },
                                        )
                                        .focusable()
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        val caps = playlist.capabilities()
                        // A denial reads as absence, never as an error: muted,
                        // never red, and no icons.
                        val mutedValue = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        val plainValue = MaterialTheme.colorScheme.onBackground
                        if (caps.isUnknownSnapshot) {
                            DetailRow(
                                label = "Permissions",
                                value = "Make this playlist active to load permissions",
                                valueColor = mutedValue,
                            )
                        } else {
                            val account = facts.username.takeIf { it.isNotBlank() }
                                ?: playlist.username?.takeIf { it.isNotBlank() }
                            if (account != null) DetailRow("Account", account)
                            // Dispatcharr's own tiers: user_level 0 = Streamer,
                            // 1 = Standard, 10 = Admin, with staff / superuser
                            // promoted to 10 exactly as the server does.
                            DetailRow(
                                label = "Role",
                                value = when {
                                    caps.effectiveUserLevel >= 10 -> "Admin"
                                    caps.effectiveUserLevel >= 1 -> "Standard"
                                    else -> "Streamer"
                                },
                            )
                            // Any account that can authenticate can watch live
                            // TV; Dispatcharr has no per-user live gate.
                            DetailRow("Live TV", "Allowed")
                            // One row for two real flags (vod_movies_enabled,
                            // vod_series_enabled) so a half-granted account is
                            // not misreported.
                            val movies = caps.allows(Capability.CanViewVod)
                            val series = caps.allows(Capability.CanViewSeries)
                            val vodText = when {
                                movies && series -> "Allowed"
                                movies -> "Movies Only"
                                series -> "TV Shows Only"
                                else -> "Not Allowed"
                            }
                            DetailRow(
                                label = "Movies & TV Shows",
                                value = vodText,
                                valueColor = if (movies || series) plainValue else mutedValue,
                            )
                            val dvrText = when (playlist.dispatcharrEffectiveDvrAccess()) {
                                "manage" -> "Allowed"
                                "view" -> "View Only"
                                else -> "Not Allowed"
                            }
                            DetailRow(
                                label = "DVR",
                                value = dvrText,
                                valueColor = if (dvrText == "Allowed") plainValue else mutedValue,
                            )
                            // The server-wide catchup_enabled being off is a
                            // server fact, not a permission, so it is worded
                            // differently from a per-user denial.
                            val catchupText = when {
                                playlist.dispatcharrSystemCatchupEnabled == 0 -> "Not Supported by Server"
                                caps.allows(Capability.CanUseCatchup) -> "Allowed"
                                else -> "Not Allowed"
                            }
                            DetailRow(
                                label = "Catch-Up",
                                value = catchupText,
                                valueColor = if (catchupText == "Allowed") plainValue else mutedValue,
                            )
                            // Assigned Channel Profile names when the last probe
                            // could read them, else their ids, else the
                            // unrestricted case.
                            val profileIds = playlist.dispatcharrAccountProfileIds
                                .split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                            DetailRow(
                                label = "Channel Profiles",
                                value = when {
                                    facts.profileNames.isNotEmpty() ->
                                        facts.profileNames.joinToString(", ")
                                    profileIds.isEmpty() -> "All Channels"
                                    else -> profileIds.joinToString(", ") { "#$it" }
                                },
                            )
                            // Real, separately-derived flag: the /proxy control
                            // endpoints (Switch Stream) stay server-side IsAdmin.
                            val canSwitch = caps.allows(Capability.CanSwitchStream)
                            DetailRow(
                                label = "Switch Stream",
                                value = if (canSwitch) "Allowed" else "Not Allowed",
                                valueColor = if (canSwitch) plainValue else mutedValue,
                            )
                            playlist.dispatcharrCapabilitiesFetchedAt.takeIf { it > 0L }?.let { ts ->
                                DetailRow(
                                    label = "Last Checked",
                                    value = DateUtils.getRelativeTimeSpanString(
                                        ts,
                                        System.currentTimeMillis(),
                                        DateUtils.MINUTE_IN_MILLIS,
                                    ).toString(),
                                )
                            }
                        }
                    }
                    // Unthrottled re-read of the user level (Apple parity:
                    // ServerDetailView). Active playlist only, hidden otherwise
                    // like Test Connection. The rows above read the DAO row,
                    // so they update live.
                    if (isActivePlaylist) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        val permsRefreshing by viewModel.permissionsRefreshing.collectAsStateWithLifecycle()
                        ActionRow(
                            icon = Icons.Filled.Refresh,
                            label = if (permsRefreshing) "Refreshing..." else "Refresh Permissions",
                            onClick = { viewModel.refreshPermissionsNow() },
                            running = permsRefreshing,
                        )
                    }
                }
            }

            }
            if (isTv) {
            item {
                Section(header = "Actions", footer = null) {
                    // Rev 2 canon amendment 1: activation lives here on every
                    // form factor. The rail and sidebar make selection show the
                    // detail, so OK-to-activate cannot survive on those roots;
                    // this row replaces it one move away. Mirrors Apple's
                    // ServerDetailView.swift:243-253.
                    ActionRow(
                        icon = if (isActivePlaylist) Icons.Filled.CheckCircle
                        else Icons.Outlined.PowerSettingsNew,
                        label = if (isActivePlaylist) "Active Playlist" else "Set Active",
                        onClick = { playlist?.id?.let { viewModel.switchToPlaylist(it) } },
                        enabled = !isActivePlaylist,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    // Any playlist is editable, active or not (Settings phase 3).
                    if (isTv) {
                        ActionRow(
                            icon = Icons.Outlined.Edit,
                            label = "Edit Playlist",
                            onClick = onEdit,
                        )
                        if (isActivePlaylist) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                    if (isActivePlaylist) {
                    ActionRow(
                        icon = Icons.Outlined.Public,
                        label = "Test Connection",
                        onClick = { viewModel.testConnection() },
                        running = state.testStatus is PlaylistViewModel.ActionStatus.Running,
                        status = state.testStatus,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    ActionRow(
                        icon = Icons.Filled.Refresh,
                        label = "Refresh Playlist",
                        onClick = { viewModel.refreshPlaylist() },
                        running = state.playlistRefreshStatus is PlaylistViewModel.ActionStatus.Running,
                        status = state.playlistRefreshStatus,
                    )
                    }
                    if (isActivePlaylist && !playlist.lanUrlString.isNullOrBlank()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        ActionRow(
                            icon = Icons.Outlined.Wifi,
                            label = "Refresh LAN Detection",
                            onClick = { viewModel.refreshLanDetection() },
                            running = state.lanRefreshStatus is PlaylistViewModel.ActionStatus.Running,
                            status = state.lanRefreshStatus,
                        )
                    }
                }
            }

                infoSections()
            } else {
                infoSections()
            item {
                Section(header = "Actions", footer = null) {
                    // Rev 2 canon amendment 1: activation lives here on every
                    // form factor. The rail and sidebar make selection show the
                    // detail, so OK-to-activate cannot survive on those roots;
                    // this row replaces it one move away. Mirrors Apple's
                    // ServerDetailView.swift:243-253.
                    ActionRow(
                        icon = if (isActivePlaylist) Icons.Filled.CheckCircle
                        else Icons.Outlined.PowerSettingsNew,
                        label = if (isActivePlaylist) "Active Playlist" else "Set Active",
                        onClick = { playlist?.id?.let { viewModel.switchToPlaylist(it) } },
                        enabled = !isActivePlaylist,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    // Any playlist is editable, active or not (Settings phase 3).
                    if (isTv) {
                        ActionRow(
                            icon = Icons.Outlined.Edit,
                            label = "Edit Playlist",
                            onClick = onEdit,
                        )
                        if (isActivePlaylist) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                    if (isActivePlaylist) {
                    ActionRow(
                        icon = Icons.Outlined.Public,
                        label = "Test Connection",
                        onClick = { viewModel.testConnection() },
                        running = state.testStatus is PlaylistViewModel.ActionStatus.Running,
                        status = state.testStatus,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    ActionRow(
                        icon = Icons.Filled.Refresh,
                        label = "Refresh Playlist",
                        onClick = { viewModel.refreshPlaylist() },
                        running = state.playlistRefreshStatus is PlaylistViewModel.ActionStatus.Running,
                        status = state.playlistRefreshStatus,
                    )
                    }
                    if (isActivePlaylist && !playlist.lanUrlString.isNullOrBlank()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        ActionRow(
                            icon = Icons.Outlined.Wifi,
                            label = "Refresh LAN Detection",
                            onClick = { viewModel.refreshLanDetection() },
                            running = state.lanRefreshStatus is PlaylistViewModel.ActionStatus.Running,
                            status = state.lanRefreshStatus,
                        )
                    }
                }
            }

            }

            if (isActivePlaylist) item {
                Section(
                    header = "EPG Cache",
                    footer = "Clears this playlist's cached guide data and downloads it fresh from the server. Use this if program cells look wrong or are missing. Takes a few minutes on large playlists.",
                ) {
                    ActionRow(
                        icon = Icons.Filled.Refresh,
                        label = "Refresh EPG Data",
                        onClick = { viewModel.refreshEpg() },
                        running = state.epgRefreshStatus is PlaylistViewModel.ActionStatus.Running,
                        status = state.epgRefreshStatus,
                    )
                    playlist.lastEpgRefreshedAt?.let { ts ->
                        Text(
                            text = "Last refreshed: ${DateFormat.getDateTimeInstance().format(Date(ts))}",
                            style = MaterialTheme.typography.bodySmall.subtext(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            if (isActivePlaylist) item {
                Section(
                    header = "Full Refresh",
                    footer = "Clears every cache (channels, guide data, and On Demand) and reloads this playlist from scratch. Use this if newly-added channels, guide data, or movies and shows are missing or stale after changes on the server.",
                ) {
                    ActionRow(
                        icon = Icons.Filled.Refresh,
                        label = "Refresh Everything",
                        destructive = true,
                        onClick = { confirmRefreshAll = true },
                        running = state.refreshAllStatus is PlaylistViewModel.ActionStatus.Running,
                        status = state.refreshAllStatus,
                    )
                }
            }

            item {
                Section(
                    header = "Danger Zone",
                    footer = "Removes this playlist and its credentials from this device. Your server data will not be affected.",
                ) {
                    ActionRow(
                        icon = Icons.Outlined.Delete,
                        label = "Delete Playlist",
                        destructive = true,
                        onClick = { confirmDelete = true },
                    )
                }
            }
        }
        }
    }

    if (confirmDelete && playlist != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?") },
            text = {
                Text(
                    "This removes \"${playlist.name}\" and its credentials from this " +
                        "device. If another playlist is saved, it becomes active.",
                )
            },
            confirmButton = {
                SettingsDialogTextButton(
                    label = "Delete",
                    destructive = true,
                    onClick = {
                        confirmDelete = false
                        viewModel.deletePlaylist(playlist.id)
                        onBack()
                    },
                )
            },
            dismissButton = {
                SettingsDialogTextButton(label = "Cancel", onClick = { confirmDelete = false })
            },
        )
    }

    if (confirmRefreshAll && playlist != null) {
        AlertDialog(
            onDismissRequest = { confirmRefreshAll = false },
            title = { Text("Refresh Everything?") },
            text = {
                Text(
                    "Clears all cached channels, guide data, and On Demand, then " +
                        "reloads \"${playlist.name}\" from scratch. Use this if " +
                        "channels or guide data are missing or stale. May take a " +
                        "few minutes on large playlists.",
                )
            },
            confirmButton = {
                SettingsDialogTextButton(
                    label = "Refresh",
                    destructive = true,
                    onClick = {
                        confirmRefreshAll = false
                        viewModel.refreshEverything()
                    },
                )
            },
            dismissButton = {
                SettingsDialogTextButton(label = "Cancel", onClick = { confirmRefreshAll = false })
            },
        )
    }
}

@Composable
private fun Section(
    header: String,
    footer: String?,
    content: @Composable () -> Unit,
) {
    Column {
        com.aeriotv.android.ui.settings.SettingsSectionHeader(header)
        com.aeriotv.android.ui.settings.SettingsCard { content() }
        if (footer != null) com.aeriotv.android.ui.settings.SettingsSectionFooter(footer)
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onBackground,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    // Lets the URL rows show a primary checkmark without tinting the URL text.
    iconTint: androidx.compose.ui.graphics.Color = valueColor,
) {
    // Apple infoRow: label left, value pushed to the trailing edge on one line
    // with middle truncation, a hairline between rows on touch (List
    // separators; the tvOS read-only card draws none).
    val isTv = rememberIsTvDevice()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.weight(1f),
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis,
                )
            }
        }
        if (!isTv) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        }
    }
}

/** Apple ServerType.displayName: the Type row names the source kind only, no
 *  auth-mode suffix (the old "- Admin API Key" tail was Android-only). */
private fun PlaylistEntity.detailTypeLabel(): String = when (sourceType) {
    SourceType.DispatcharrUserPass.name, SourceType.DispatcharrApiKey.name -> "Dispatcharr Direct Connect"
    SourceType.XtreamCodes.name -> "Xtream Codes"
    SourceType.M3uUrl.name -> "M3U + EPG"
    else -> sourceType
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    running: Boolean = false,
    enabled: Boolean = true,
    status: PlaylistViewModel.ActionStatus = PlaylistViewModel.ActionStatus.Idle,
) {
    // The whole row is the click/focus target. The old shape (label inside a
    // TextButton) gave D-pad focus a tiny pill around the text only, which
    // looked out of place next to the full-width rows around it.
    var focused by remember { mutableStateOf(false) }
    val baseAccent = if (destructive) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    // Same rule as the shared SettingsActionRow: a disabled row stays FOCUSABLE
    // so it does not drop out of D-pad traversal and strand focus; it dims and
    // swallows the click instead. "Active Playlist" is exactly this state.
    val accent = if (enabled) baseAccent else baseAccent.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .background(
                if (focused) {
                    accent.copy(alpha = 0.16f)
                } else {
                    Color.Transparent
                },
            )
            // Guarded instead of clickable(enabled = !running) so the row
            // keeps its D-pad focus stop while a run is in flight; a disabled
            // clickable drops out of focus traversal entirely.
            .clickable(onClick = { if (!running && enabled) onClick() })
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = accent,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            when (status) {
                is PlaylistViewModel.ActionStatus.Success -> Text(
                    text = status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.textAccent,
                )
                is PlaylistViewModel.ActionStatus.Failure -> Text(
                    text = status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
        }
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else if (status is PlaylistViewModel.ActionStatus.Success) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
