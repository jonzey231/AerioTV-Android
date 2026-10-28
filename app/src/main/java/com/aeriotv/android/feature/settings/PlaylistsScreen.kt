package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.scale.subtext
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.aeriotv.android.ui.scale.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.db.entity.playlistRowSubtitle
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.ui.settings.settingsFormWidth
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import com.aeriotv.android.ui.settings.SettingsHeaderTextButton
import com.aeriotv.android.ui.settings.dpadFocusRing
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset

/**
 * Presentation-only order for every playlist list in Settings
 * (Logan 2026-09-18, Apple `sortedServers`): by name, case-insensitive and
 * numeric-aware, so "Playlist 2" sorts before "Playlist 10". Ties fall back to
 * the id so the order is stable. Nothing here touches the STORED order or the
 * sync payload.
 */
internal fun List<PlaylistEntity>.sortedForDisplay(): List<PlaylistEntity> =
    sortedWith(
        compareBy(NaturalNameOrder) { pl: PlaylistEntity -> pl.name }
            .thenBy { pl: PlaylistEntity -> pl.id },
    )

/**
 * Natural-order string comparator: digit runs compare as numbers, everything
 * else case-insensitively. A java.text.Collator alone is not numeric-aware.
 */
internal val NaturalNameOrder: Comparator<String> = Comparator { a, b ->
    var i = 0
    var j = 0
    var result = 0
    while (result == 0 && i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ia = i
            var jb = j
            while (ia < a.length && a[ia].isDigit()) ia++
            while (jb < b.length && b[jb].isDigit()) jb++
            // Compare digit runs by value, trimming leading zeros so the
            // lengths are meaningful.
            val na = a.substring(i, ia).trimStart('0')
            val nb = b.substring(j, jb).trimStart('0')
            result = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
            i = ia
            j = jb
        } else {
            result = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            i++
            j++
        }
    }
    if (result != 0) result else (a.length - i) - (b.length - j)
}

/**
 * Multi-playlist switcher reachable from Settings root. Lists every saved
 * playlist with an Active checkmark on the current one; select to open its detail,
 * long-press for delete confirm (on TV the long-press opens a Delete menu).
 * Rows are sorted by name ([sortedForDisplay]), so there is no manual reorder
 * on any form factor any more (Logan 2026-09-18). The top-bar
 * add action routes to the same
 * Choose-Source-Type onboarding screen used for first-run setup, except after
 * the new playlist persists the user pops back here instead of being thrown
 * into the player.
 *
 * Mirrors iOS Playlists screen (project_aeriotv_ios_canon.md "Settings" >
 * "Playlists section" implicit in canon since the iOS test-server screenshots
 * show only a single playlist; multi-playlist UX is taken from iOS source).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistsScreen(
    onBack: () -> Unit,
    onAddPlaylist: () -> Unit,
    onOpenPlaylistDetail: (String) -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val storedPlaylists: List<PlaylistEntity> by viewModel.allPlaylists
        .collectAsStateWithLifecycle(initialValue = emptyList<PlaylistEntity>())
    // Presentation only: the stored order is left exactly as it is.
    val playlists = remember(storedPlaylists) { storedPlaylists.sortedForDisplay() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // LIVE from the DAO, not the UiState snapshot: the selection indicator
    // has to move to the newly active row the moment the switch commits,
    // rather than after its channel fetch returns (Logan 2026-09-16).
    val activeIdLive by viewModel.activeIdLive
        .collectAsStateWithLifecycle(initialValue = state.playlist?.id)
    val activeId = activeIdLive
    val isTv = rememberIsTvDevice()
    val context = androidx.compose.ui.platform.LocalContext.current

    var pendingDelete by remember { mutableStateOf<PlaylistEntity?>(null) }
    // Set up a TV (docs/DeviceSetupHandoff.md): phone and tablet only, Admin
    // or default profile only (the payload carries other profiles' sign-ins).
    var showSetUpTv by remember { mutableStateOf(false) }
    val setupPm = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext, SetupEntryPoint::class.java,
        ).profileManager()
    }
    val canSetUpTv = !isTv && activeId != null && setupPm.canSetUpTv(activeId)
    // TV-only long-press menu target (Delete / Cancel).
    var menuFor by remember { mutableStateOf<PlaylistEntity?>(null) }
    val tvGuard = rememberTvMenuGuard()

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Playlists",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                // No back arrow on Android TV -- the remote BACK pops it.
                if (!isTv) {
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
                if (isTv) {
                    // Focusable pill inset to the overscan margin; a bare
                    // IconButton has no visible D-pad focus state.
                    SettingsHeaderTextButton(label = "Add", onClick = onAddPlaylist)
                } else {
                    IconButton(onClick = onAddPlaylist) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add playlist",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isTv) "No saved playlists. Select Add above to add one."
                    else "No saved playlists. Tap + to add one.",
                    style = MaterialTheme.typography.bodyMedium.subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
        val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.settingsFormWidth(),
            // 104dp bottom clears the MainScaffold NavigationBar so the
            // last playlist row stays draggable down to the very bottom.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = LocalTabBarBottomInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (canSetUpTv) {
                item(key = "set-up-tv") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                            .clickable { showSetUpTv = true }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("Set up a TV", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Send the active playlist to AerioTV on a TV. Nothing to type on the TV.",
                                style = MaterialTheme.typography.bodySmall.subtext(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(playlists, key = { it.id }) { pl ->
                    SwipeablePlaylistRow(
                        playlist = pl,
                        isActive = pl.id == activeId,
                        // Guarded so the OK-release after a TV long-press can't
                        // also register as a tap on the row (see TvMenuGuard).
                        // Phase 3, item 5: selecting a row opens its detail
                        // on EVERY form factor. Set Active is the first row of
                        // the detail's Actions section.
                        onTap = tvGuard.wrap { onOpenPlaylistDetail(pl.id) },
                        onLongPress = {
                            if (isTv) {
                                menuFor = pl
                                tvGuard.arm()
                            } else {
                                pendingDelete = pl
                            }
                        },
                        onSwipedToDelete = { pendingDelete = pl },
                        // Leading-radio tap activates, exactly as on the
                        // Settings root list. TV keeps one focus stop (the row
                        // itself), which PlaylistRow decides.
                        onActivate = { viewModel.switchToPlaylist(pl.id) },
                    )
            }
        }

        menuFor?.let { pl ->
            // TvActionMenuDialog dismisses (menuFor = null) before running the
            // row's onClick, so the actions only carry their own effect.
            // No Move Up / Move Down: the list is name-sorted now.
            TvActionMenuDialog(
                title = pl.name,
                actions = listOf(
                    TvMenuAction("Delete", Icons.Filled.Delete, destructive = true) { pendingDelete = pl },
                    TvMenuAction("Cancel", Icons.Filled.Close) {},
                ),
                guard = tvGuard,
                onDismiss = { menuFor = null },
            )
        }
        }
    }

    if (showSetUpTv) {
        com.aeriotv.android.feature.devicesetup.SetUpTvDialog(playlistId = activeId, onDismiss = { showSetUpTv = false })
    }

    pendingDelete?.let { pl ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete playlist?") },
            text = {
                Text(
                    "This removes \"${pl.name}\" and its credentials from this device. " +
                        if (pl.id == activeId) "The next playlist on the list will be activated." else "",
                )
            },
            confirmButton = {
                SettingsDialogTextButton(
                    label = "Delete",
                    destructive = true,
                    onClick = {
                        val id = pl.id
                        pendingDelete = null
                        viewModel.deletePlaylist(id)
                    },
                )
            },
            dismissButton = {
                SettingsDialogTextButton(label = "Cancel", onClick = { pendingDelete = null })
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: PlaylistEntity,
    isActive: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    /** Non-null on touch: the leading radio becomes its own 44dp control. */
    onActivate: (() -> Unit)? = null,
) {
    // One playlist row on every surface (Logan 2026-09-18): leading radio,
    // name, "<Type> \u00B7 <N> channels", trailing chevron. Never the URL.
    val isTv = rememberIsTvDevice()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .dpadFocusRing(RoundedCornerShape(12.dp), washTint = MaterialTheme.colorScheme.primary)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val glyph: @Composable () -> Unit = {
            Icon(
                imageVector = if (isActive) Icons.Filled.RadioButtonChecked
                else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (isActive) "Active" else "Set as active playlist",
                tint = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        // On TV the radio stays a pure glyph so the row keeps exactly one
        // focus stop; on touch it is its own 44dp control that activates the
        // playlist without opening the detail (Settings root parity).
        if (!isTv && onActivate != null && !isActive) {
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .requiredSize(44.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onActivate,
                        ),
                    contentAlignment = Alignment.Center,
                ) { glyph() }
            }
        } else {
            glyph()
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = playlist.playlistRowSubtitle(),
                style = MaterialTheme.typography.bodySmall.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Wraps a [PlaylistRow] in Material 3's SwipeToDismissBox so the user can
 * swipe either direction to delete (mirrors iOS SettingsView swipe action).
 * The dismiss action surfaces the same confirmation dialog as long-press, so
 * a wayward swipe never silently destroys a playlist row + its credentials.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeablePlaylistRow(
    playlist: com.aeriotv.android.core.data.db.entity.PlaylistEntity,
    isActive: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipedToDelete: () -> Unit,
    onActivate: (() -> Unit)? = null,
) {
    val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd ||
                value == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart
            ) {
                onSwipedToDelete()
                // Don't actually dismiss the row here - the confirmation dialog
                // owns the delete. Returning false snaps the row back to settled.
                false
            } else {
                true
            }
        },
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // Only paint the red action WHILE a swipe is in progress. The
            // playlist row's own fill is translucent, so an always-drawn
            // background bled through every settled row (screenshot pass).
            if (dismissState.dismissDirection !=
                androidx.compose.material3.SwipeToDismissBoxValue.Settled
            ) Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.85f))
                    .padding(horizontal = 20.dp),
                contentAlignment = if (dismissState.dismissDirection ==
                    androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd
                ) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Text(
                    text = "Delete",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onError,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    ) {
        PlaylistRow(
            playlist = playlist,
            isActive = isActive,
            onTap = onTap,
            onLongPress = onLongPress,
            onActivate = onActivate,
        )
    }
}


@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface SetupEntryPoint {
    fun profileManager(): com.aeriotv.android.core.profiles.ProfileManager
}
