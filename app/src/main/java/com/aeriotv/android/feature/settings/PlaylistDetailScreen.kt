package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.adaptive.adaptiveFormWidth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
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
import com.aeriotv.android.core.data.db.entity.sourceTypeDisplayLabel
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import java.text.DateFormat
import java.util.Date

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
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playlist = state.playlist
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRefreshAll by remember { mutableStateOf(false) }

    // LAN/WAN route is a point-in-time probe (no NetworkCallback flow in the
    // app); re-check on entry and ON_RESUME, mirroring NetworkSettingsScreen's
    // HomeWifiSection. Action statuses reset when the screen goes away.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(playlist?.id) { viewModel.refreshActiveRoute() }
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

        if (playlist == null) {
            Column(
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
            modifier = Modifier.adaptiveFormWidth(),
            // 104dp bottom clears the MainScaffold NavigationBar on phones;
            // the TV nav lives at the top, so a slim overscan inset suffices.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = if (isTv) 32.dp else 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Section(header = "Connection Details", footer = null) {
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
                        DetailRow("Type", playlist.sourceTypeDisplayLabel())
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
                        // Reflect a real signal -- whether the source has ever
                        // loaded channels -- instead of asserting "Verified"
                        // unconditionally (which read as connected even for a
                        // source that never reached the server). The "Last
                        // Connected" row below dates the last successful load.
                        val hasConnected = playlist.channelCount > 0
                        DetailRow(
                            label = "Status",
                            value = if (hasConnected) "Connected" else "Not connected yet",
                            valueColor = if (hasConnected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            icon = Icons.Filled.CheckCircle.takeIf { hasConnected },
                        )
                        playlist.lastRefreshedAt?.let { ts ->
                            DetailRow(
                                "Last Connected",
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                    .format(Date(ts)),
                            )
                        }
                        DetailRow("Channels", playlist.channelCount.toString())
                        if (!playlist.epgUrl.isNullOrBlank()) {
                            DetailRow("EPG", playlist.epgUrl!!)
                        }
                    }
                }
            }

            item {
                Section(header = "Actions", footer = null) {
                    if (isTv) {
                        ActionRow(
                            icon = Icons.Outlined.Edit,
                            label = "Edit Playlist",
                            onClick = onEdit,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    }
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
            }

            item {
                Section(
                    header = "EPG Cache",
                    footer = "Refresh when channel guide data is missing or out of date. Pulls a fresh XMLTV from the configured EPG URL.",
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            item {
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
                    footer = "Removes this playlist and its credentials from this device.",
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = header.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
        ) { content() }
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(0.6f),
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
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    running: Boolean = false,
    status: PlaylistViewModel.ActionStatus = PlaylistViewModel.ActionStatus.Idle,
) {
    // The whole row is the click/focus target. The old shape (label inside a
    // TextButton) gave D-pad focus a tiny pill around the text only, which
    // looked out of place next to the full-width rows around it.
    var focused by remember { mutableStateOf(false) }
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
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
            .clickable(onClick = { if (!running) onClick() })
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
                    color = MaterialTheme.colorScheme.primary,
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
