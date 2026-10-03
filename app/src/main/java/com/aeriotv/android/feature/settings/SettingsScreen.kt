package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.theme.textAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.db.entity.playlistRowSubtitle
import com.aeriotv.android.core.data.db.entity.sourceTypeBadgeLabel
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import com.aeriotv.android.core.tv.TvQrLink
import com.aeriotv.android.core.tv.TvQrLinkDialog
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.ui.settings.SettingsNavRow
import com.aeriotv.android.feature.whatsnew.WhatsNewSheetOnDemand
import com.aeriotv.android.ui.adaptive.LocalTabBarBottomInset
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.settingsShowsBackArrow
import com.aeriotv.android.ui.settings.settingsFormWidth
import com.aeriotv.android.ui.settings.settingsEyebrowStyle
import com.aeriotv.android.ui.settings.settingsFootnoteStyle
import com.aeriotv.android.ui.settings.settingsRowTitleStyle
import com.aeriotv.android.ui.settings.settingsRowValueStyle
import com.aeriotv.android.ui.settings.settingsTitleStyle
import java.text.DateFormat
import java.util.Date

/**
 * Settings root. Mirrors iOS SettingsView.swift section ordering + grouped-card
 * presentation (lines 150-496):
 *
 *  1. Playlists  - inline list of every saved playlist with tap-to-activate
 *                  (tap the active row again for details, edit, delete) and an
 *                  Add Playlist row. Footer surfaces the matching hints.
 *  2. App Settings - Appearance / App Behaviors / Multiview / Network rows
 *                  inside a single grouped card.
 *  3. Sync       - current cut routes through to the full SyncSettingsScreen.
 *                  iOS surfaces the toggle inline here; that follow-up lands
 *                  alongside the Google Drive Sync rewrite that mirrors the
 *                  iCloud Sync toggle / Sync Now / Clear Data set.
 *  4. DVR        - single nav row.
 *  5. Developer  - single nav row.
 *  6. About      - Device / System / App Version / First Installed /
 *                  Last Updated + Copy / Developer Website / Report an Issue.
 *
 * Each section is a [SettingsSectionGroup] - uppercase header in primary
 * tint, rounded card containing the rows separated by hairline dividers,
 * optional footer text in muted-tint below. Mirrors iOS .insetGrouped list
 * style + sectionHeaderStyle().
 */
/**
 * Which blocks of the Settings root [SettingsScreen] renders.
 *
 * Phase B3: on a phone the root is one scrolling list of everything. In a
 * two-pane host the sidebar takes over the section list, and the Playlists and
 * About blocks - which have no screen of their own - become detail panes. Both
 * panes are this same screen filtered down, so the copy, ordering, and row
 * behavior are literally the phone's.
 */
enum class SettingsRootContent(val title: String) {
    Full("Settings"),
    PlaylistsOnly("Playlists"),
    AboutOnly("About"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onSectionClick: (SettingsSection) -> Unit,
    /** Back affordance for the pushed About page; unused by the root list. */
    onBack: () -> Unit = {},
    onOpenPlaylistDetail: (String) -> Unit = {},
    onOpenPlaylists: () -> Unit = {},
    /** Long-press Edit on a playlist row (Apple's context menu). Falls back to
     *  the detail page, which carries Edit, when a host does not wire it. */
    onEditPlaylist: (String) -> Unit = onOpenPlaylistDetail,
    onAddPlaylist: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    viewModel: PlaylistViewModel = hiltViewModel(),
    // Phase B3: the two-pane hosts render the Playlists and About blocks as
    // detail panes. Rather than duplicate either block (and risk the copy
    // drifting from the phone root, which the plan freezes), the same screen
    // renders a subset of itself.
    content: SettingsRootContent = SettingsRootContent.Full,
    /** Hoisted so the host can scroll this list back to the top when the
     *  Settings tab is re-tapped at its root (see TabReselect). */
    listState: LazyListState = rememberLazyListState(),
) {
    val fullRoot = content == SettingsRootContent.Full
    val context = androidx.compose.ui.platform.LocalContext.current
    // Flavor-gated: the App Updates row only exists on the GitHub/sideload
    // channel (play flavor binds a disabled no-op manager).
    val updateVm: com.aeriotv.android.feature.update.UpdateViewModel = hiltViewModel()
    val updaterEnabled = updateVm.isEnabled
    // Root row values: Sync reads On/Off, About reads the installed version.
    val settingsVm: SettingsViewModel = hiltViewModel()
    val syncEnabled by settingsVm.syncMasterEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val storedPlaylists by viewModel.allPlaylists.collectAsStateWithLifecycle(initialValue = emptyList())
    // Presentation only (Logan 2026-09-18): name-sorted, stored order untouched.
    val playlists = remember(storedPlaylists) { storedPlaylists.sortedForDisplay() }
    // LIVE from the DAO, not the UiState snapshot (Logan 2026-09-16): the
    // radio button must fill in on the new row as soon as the switch commits.
    val activeIdLive by viewModel.activeIdLive
        .collectAsStateWithLifecycle(initialValue = state.playlist?.id)
    val activeId = activeIdLive


    val packageInfo = remember {
        runCatching {
            val pm = context.packageManager
            val pkg = context.packageName
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }.getOrNull()
    }
    val installedAt = packageInfo?.firstInstallTime ?: 0L
    val updatedAt = packageInfo?.lastUpdateTime ?: 0L
    val versionName = packageInfo?.versionName ?: "0.1.0"

    // TV: external links surface as a QR dialog (no browser on Android TV);
    // phones keep the ACTION_VIEW intent in openUrl.
    val isTv = rememberIsTvDevice()
    var qrLink by remember { mutableStateOf<TvQrLink?>(null) }
    var showWhatsNew by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Apple TV draws no page title above the first section.
        if (isTv && com.aeriotv.android.ui.settings.LocalSettingsInPane.current) {
            Spacer(Modifier.height(12.dp))
        } else
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = content.title,
                    style = settingsTitleStyle(),
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                // About is a pushed page like any other sub-screen, so it gets
                // the same back arrow (TV and pane hosts suppress it, as there
                // the remote's BACK or the rail beside it does the popping).
                if (content == SettingsRootContent.AboutOnly && settingsShowsBackArrow()) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
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

        // Center + cap the form on wider viewports. The Pixel Tablet,
        // unfolded foldables, AND phone-landscape (~997 dp wide on a
        // Pixel 10 Pro XL) all hit the Expanded breakpoint, which without
        // the cap stretches a single column of settings rows edge-to-edge
        // and turns the playlist card into a 900-dp-wide stripe. iOS gets
        // the equivalent narrowing for free via insetGrouped.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
        LazyColumn(
            state = listState,
            // Full-screen measures the WINDOW; a detail pane must measure the
            // PANE or it sizes itself against the whole tablet and overflows.
            modifier = Modifier.settingsFormWidth()
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                // TV: keep the last row above the ~5% bottom overscan band.
                // Phones reserve the floating tab pill / cast controls the same
                // way every other scrolling surface does.
                bottom = if (rememberIsTvDevice()) 28.dp else LocalTabBarBottomInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(com.aeriotv.android.ui.settings.SettingsCardMetrics.sectionSpacing),
        ) {
            // MARK: Playlists
            if (content != SettingsRootContent.AboutOnly) item("playlists") {
                PlaylistsSection(
                    playlists = playlists,
                    activeId = activeId,
                    // In a pane the top bar already reads "Playlists".
                    showHeader = fullRoot,
                    paneHost = content == SettingsRootContent.PlaylistsOnly || isTv,
                    // Phase 3, item 5: selecting a playlist row opens its
                    // DETAIL on every form factor, phone included. Rev 2 had
                    // already done this for the rail/sidebar hosts because
                    // tap-to-activate made the detail unreachable when nothing
                    // was active yet; the phone had the same hole, and having
                    // one row mean "activate" and another mean "open" was the
                    // thing nobody could predict. Set Active is the first row
                    // of the detail's Actions section.
                    onTap = { pl -> onOpenPlaylistDetail(pl.id) },
                    // Phase 3 (Logan 2026-09-18): the leading RADIO is its own
                    // control and activates the playlist through the same path
                    // as the detail page's Set Active, without opening details.
                    onActivate = { pl -> viewModel.switchToPlaylist(pl.id) },
                    onAdd = onAddPlaylist,
                    onEdit = { pl -> onEditPlaylist(pl.id) },
                    onDelete = { pl -> viewModel.deletePlaylist(pl.id) },
                )
            }

            // MARK: App Settings / Sync / DVR / Developer
            //
            // Sourced from the shared canon so the sidebar in the two-pane
            // hosts cannot drift from this list (plan B7: frozen canon).
            if (fullRoot) {
                items(
                    items = visibleSettingsSections(isTv = isTv, updaterEnabled = updaterEnabled),
                    key = { it.key },
                ) { group ->
                    SettingsSectionGroup(
                        header = group.header,
                        rows = group.sections,
                        onClick = onSectionClick,
                        footer = group.footer,
                        // Apple shows the build as the About row's SUBTITLE,
                        // not as a trailing value.
                        subtitleOverride = { section ->
                            if (section == SettingsSection.About) {
                                "$versionName (${packageInfo?.longVersionCode ?: 0L})"
                            } else null
                        },
                        syncEnabled = syncEnabled,
                    )
                }
            }

            // MARK: About
            //
            // Settings phase 1: About is a PUSHED page (and a pane in the
            // two-pane hosts), reached from the closing group's About row. It
            // is no longer inlined at the bottom of the root list.
            if (content == SettingsRootContent.AboutOnly) item("about") {
                AboutSection(
                    showHeader = fullRoot,
                    onShowWhatsNew = { showWhatsNew = true },
                    versionName = versionName,
                    versionCode = packageInfo?.longVersionCode ?: 0L,
                    installedAt = installedAt,
                    updatedAt = updatedAt,
                    onCopy = {
                        val text = buildAboutClipboard(
                            versionName,
                            packageInfo?.longVersionCode ?: 0L,
                            installedAt,
                            updatedAt,
                        )
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("AerioTV diagnostics", text))
                        android.widget.Toast.makeText(
                            context,
                            "Copied diagnostics to clipboard.",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onOpenWebsite = {
                        val url = "https://github.com/jonzey231/AerioTV-Android"
                        if (isTv) {
                            qrLink = TvQrLink(
                                title = "Developer Website",
                                caption = "Scan with your phone to open this page.",
                                url = url,
                            )
                        } else {
                            openUrl(context, url)
                        }
                    },
                    onOpenLicenses = onOpenLicenses,
                    onReportIssue = {
                        val url = "https://github.com/jonzey231/AerioTV-Android/issues/new"
                        if (isTv) {
                            qrLink = TvQrLink(
                                title = "Report an Issue",
                                caption = "Scan with your phone to open this page.",
                                url = url,
                            )
                        } else {
                            openUrl(context, url)
                        }
                    },
                )
            }
        }
        }
    }

    if (showWhatsNew) {
        WhatsNewSheetOnDemand(onDismiss = { showWhatsNew = false })
    }

    qrLink?.let { link ->
        TvQrLinkDialog(
            title = link.title,
            caption = link.caption,
            url = link.url,
            onDismiss = { qrLink = null },
        )
    }
}

// MARK: - Playlists section

@Composable
private fun PlaylistsSection(
    playlists: List<PlaylistEntity>,
    activeId: String?,
    showHeader: Boolean = true,
    paneHost: Boolean = false,
    onTap: (PlaylistEntity) -> Unit,
    /** Leading-radio tap: activate that playlist. Null on TV, which keeps a
     *  single focus stop per row. */
    onActivate: ((PlaylistEntity) -> Unit)? = null,
    onAdd: () -> Unit,
    onEdit: (PlaylistEntity) -> Unit,
    onDelete: (PlaylistEntity) -> Unit,
) {
    val isTv = rememberIsTvDevice()
    // Apple's row context menu (long press): on tvOS "Use This Playlist"
    // (only with 2+ playlists), Edit, Delete; on iPhone/iPad Edit, Delete,
    // because the radio already activates there.
    val tvGuard = com.aeriotv.android.core.tv.rememberTvMenuGuard()
    var menuFor by remember { mutableStateOf<PlaylistEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<PlaylistEntity?>(null) }
    Column {
        if (showHeader) {
            SectionHeader("Playlists")
        }
        com.aeriotv.android.ui.settings.SettingsCard {
            if (playlists.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No playlists added",
                        style = settingsRowValueStyle().subtext(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                playlists.forEachIndexed { index, pl ->
                    if (index > 0) RowDivider()
                    PlaylistRow(
                        playlist = pl,
                        isActive = pl.id == activeId,
                        // Guarded so the OK release after a TV long press does
                        // not also open the detail.
                        onTap = tvGuard.wrap { onTap(pl) },
                        onLongPress = {
                            menuFor = pl
                            tvGuard.arm()
                        },
                        onActivate = if (isTv) null else onActivate?.let { act -> { act(pl) } },
                    )
                }
                RowDivider()
            }
            // Add Playlist row - iOS calls this out with a cyan plus glyph
            // (SettingsView line 206-220).
            var addFocused by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { addFocused = it.isFocused }
                    .groupRowFocus(addFocused)
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = "Add Playlist",
                    style = settingsRowValueStyle(),
                    color = MaterialTheme.colorScheme.textAccent,
                    fontWeight = FontWeight.Medium,
                )
            }
            // No Manage Playlists row: Apple has none on any platform (the
            // list is name-sorted, so there is nothing to reorder).
        }
        if (playlists.isNotEmpty()) {
            // Apple's playlistFooterHint, verbatim per platform: the TV row
            // is one focus stop with no circle, so tvOS points at Actions.
            if (isTv) {
                // tvOS pane: plain sentence, no glyph (Apple Phase 3 item 10).
                SectionFooter("Select a playlist to open it; Set Active is in its Actions")
            } else {
                // iPhone/iPad: Label with the list.bullet glyph. Apple's second
                // line ("Tap Edit to reorder") is NOT mirrored: Apple removed
                // reordering on 2026-09-18 and the hint now names a control
                // that does not exist on either platform.
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = com.aeriotv.android.ui.settings.settingsDimTint(),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "Tap a playlist to open it, or its circle to make it active.",
                        style = MaterialTheme.typography.labelSmall.subtext(),
                        color = com.aeriotv.android.ui.settings.settingsDimTint(),
                    )
                }
            }
        }
    }

    menuFor?.let { pl ->
        val isActive = pl.id == activeId
        com.aeriotv.android.core.tv.TvActionMenuDialog(
            title = pl.name,
            actions = buildList {
                if (isTv && playlists.size > 1) {
                    // Apple disables this item on the active row and relabels
                    // it; a disabled entry has no Android menu equivalent, so
                    // the active row simply omits it.
                    if (!isActive) {
                        add(
                            com.aeriotv.android.core.tv.TvMenuAction(
                                "Use This Playlist",
                                Icons.Filled.RadioButtonChecked,
                            ) { onActivate?.invoke(pl) },
                        )
                    }
                }
                // Active row only: EditPlaylistScreen saves through the
                // ACTIVE playlist, so editing another row would overwrite the
                // active one's URL and credentials (same gate as the detail).
                if (isActive) {
                    add(com.aeriotv.android.core.tv.TvMenuAction("Edit", Icons.Filled.Edit) { onEdit(pl) })
                }
                add(
                    com.aeriotv.android.core.tv.TvMenuAction("Delete", Icons.Filled.Delete, destructive = true) {
                        pendingDelete = pl
                    },
                )
            },
            guard = tvGuard,
            onDismiss = { menuFor = null },
        )
    }

    pendingDelete?.let { pl ->
        // Apple's root-list alert, word for word.
        com.aeriotv.android.ui.scale.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete Playlist?") },
            text = {
                Text("This will remove \"${pl.name}\" from the app. Your server data will not be affected.")
            },
            confirmButton = {
                com.aeriotv.android.ui.settings.SettingsDialogTextButton(
                    label = "Delete",
                    destructive = true,
                    onClick = {
                        pendingDelete = null
                        onDelete(pl)
                    },
                )
            },
            dismissButton = {
                com.aeriotv.android.ui.settings.SettingsDialogTextButton(
                    label = "Cancel",
                    onClick = { pendingDelete = null },
                )
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: PlaylistEntity,
    isActive: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    /** Non-null on touch: the radio becomes its own 44dp control. */
    onActivate: (() -> Unit)? = null,
) {
    val isTv = rememberIsTvDevice()
    var focused by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .groupRowFocus(focused)
                .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Radio-button active marker - filled cyan dot inside a ring on
            // the active row, empty ring on the rest. On TOUCH it is also a
            // control (Apple parity, Logan 2026-09-18): tapping it activates
            // that playlist without opening the detail, and the rest of the
            // row still opens the detail. On TV it stays a pure glyph so the
            // row keeps exactly one focus stop.
            val glyph: @Composable () -> Unit = {
                Icon(
                    // Apple: checkmark.circle.fill when active, circle otherwise.
                    imageVector = if (isActive) Icons.Filled.CheckCircle
                    else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (isActive) "Active" else "Set as active playlist",
                    tint = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (!isTv && onActivate != null && !isActive) {
                // 44dp target without changing the drawn size: requiredSize
                // overflows the row's own 14dp vertical padding box.
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
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
                // Logan 2026-09-18, one row on both platforms: name, then
                // "<Type> \u00B7 <N> channels". The source-type pill and the
                // monospace URL line are gone - a provider URL can carry
                // credentials in its query string, and this is the row users
                // screenshot.
                Text(
                    text = playlist.name,
                    style = settingsRowTitleStyle(),
                    color = MaterialTheme.colorScheme.onBackground,
                    // Apple ServerListRow sets the name in bodyMedium (regular)
                    // on every row; the checkmark alone marks the active one.
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    // Apple ServerListRow.subtitleText: the count only for the
                    // ACTIVE playlist (the only one whose channels are loaded);
                    // other rows read their type alone.
                    text = if (isActive) playlist.playlistRowSubtitle() else playlist.sourceTypeBadgeLabel(),
                    style = settingsFootnoteStyle().subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            // Apple's status dot: statusOnline (the theme accent) once the
            // source has verified (Android's signal is a successful channel
            // load), muted otherwise.
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(if (isTv) 12.dp else 8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        if (playlist.channelCount > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    ),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


/**
 * D-pad focus highlight for rows inside the grouped settings cards. The
 * default Material ripple is nearly invisible at 10 feet; this paints the
 * same accent wash the sub-screen rows use. No-op while unfocused (touch).
 */
@Composable
private fun Modifier.groupRowFocus(focused: Boolean): Modifier = this
    .clip(RoundedCornerShape(com.aeriotv.android.ui.settings.SettingsCardMetrics.rowCorner))
    .background(
        if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
    )
    .then(
        if (focused) {
            Modifier.border(
                com.aeriotv.android.ui.settings.SettingsFocusRingWidth,
                MaterialTheme.colorScheme.primary,
                RoundedCornerShape(com.aeriotv.android.ui.settings.SettingsCardMetrics.rowCorner),
            )
        } else Modifier,
    )

// MARK: - Generic grouped section

@Composable
private fun SettingsSectionGroup(
    header: String,
    rows: List<SettingsSection>,
    onClick: (SettingsSection) -> Unit,
    footer: String? = null,
    subtitleOverride: (SettingsSection) -> String? = { null },
    syncEnabled: Boolean = false,
) {
    Column {
        // A blank header means the group carries no label (the closing
        // Developer / About group).
        if (header.isNotBlank()) {
            SectionHeader(header)
        }
        com.aeriotv.android.ui.settings.SettingsCard {
            rows.forEach { section ->
                // No RowDivider here: SettingsNavRow draws the hairline itself,
                // inset to the TITLE start (past the icon tile). Drawing both
                // was what made some rows look full-width and others inset.
                SectionNavRow(
                    section = section,
                    subtitleOverride = subtitleOverride(section),
                    syncEnabled = syncEnabled,
                    onClick = { onClick(section) },
                )
            }
        }
        footer?.let { SectionFooter(it) }
    }
}

@Composable
private fun SectionNavRow(
    section: SettingsSection,
    onClick: () -> Unit,
    subtitleOverride: String? = null,
    syncEnabled: Boolean = false,
) {
    // Phase B1: delegates to the shared row so the root gets the same
    // border+scale+wash focus treatment as every subpage (the old
    // groupRowFocus was noticeably weaker on TV).
    SettingsNavRow(
        title = section.title,
        subtitle = subtitleOverride ?: settingsSectionSubtitle(section, syncEnabled),
        icon = section.icon,
        onClick = onClick,
    )
}

// MARK: - About section

@Composable
private fun AboutSection(
    showHeader: Boolean = true,
    onShowWhatsNew: () -> Unit,
    versionName: String,
    versionCode: Long,
    installedAt: Long,
    updatedAt: Long,
    onCopy: () -> Unit,
    onOpenWebsite: () -> Unit,
    onReportIssue: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val isTv = rememberIsTvDevice()
    Column {
        if (showHeader) {
            SectionHeader("About")
        }
        com.aeriotv.android.ui.settings.SettingsCard {
            AboutInfoRow("Device", deviceDisplayName())
            RowDivider()
            AboutInfoRow("System", "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            RowDivider()
            AboutVersionRow(
                value = "$versionName ($versionCode)",
                onClick = onShowWhatsNew,
            )
            RowDivider()
            AboutInfoRow("First Installed", formatInstallTime(installedAt))
            RowDivider()
            AboutInfoRow(
                "Last Updated",
                if (updatedAt > 0 && updatedAt != installedAt) formatInstallTime(updatedAt) else "Never",
            )
            RowDivider()
            // Apple's order differs per platform (AboutSettingsView): iOS is
            // Copy to Clipboard, Open Source Licenses, Developer Website,
            // Report an Issue; tvOS has no Copy (no clipboard to paste into)
            // and ends with Open Source Licenses.
            if (!isTv) {
                AboutActionRow("Copy to Clipboard", Icons.Filled.ContentCopy, onClick = onCopy)
                RowDivider()
                AboutActionRow(
                    "Open Source Licenses",
                    Icons.Outlined.Description,
                    onClick = onOpenLicenses,
                    chevron = true,
                )
                RowDivider()
            }
            AboutActionRow(
                "Developer Website",
                Icons.Outlined.Link,
                onClick = onOpenWebsite,
                external = true,
            )
            RowDivider()
            AboutActionRow(
                "Report an Issue",
                // Apple: exclamationmark.bubble.
                Icons.Outlined.Feedback,
                onClick = onReportIssue,
                external = true,
            )
            if (isTv) {
                RowDivider()
                AboutActionRow(
                    "Open Source Licenses",
                    Icons.Outlined.Description,
                    onClick = onOpenLicenses,
                    chevron = true,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "In loving memory of Jesse Mann aka EPG Guru",
            style = settingsFootnoteStyle().subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = settingsRowValueStyle().subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = settingsRowValueStyle(),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * App Version row. Reads as an info row (label + value) but is clickable and,
 * on TV, focusable with the same card highlight as the action rows below it,
 * so the D-pad can reach it and DPAD_CENTER opens the What's New notes for
 * the installed build. The trailing "What's New" hint is the only affordance;
 * nothing about the launch-time gate changes.
 */
@Composable
private fun AboutVersionRow(value: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .groupRowFocus(focused)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "App Version",
            style = settingsRowValueStyle().subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = settingsRowValueStyle(),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            // Apple: caption in textTertiary, dim chevron.
            text = "What's New",
            style = settingsFootnoteStyle().subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun AboutActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    external: Boolean = false,
    chevron: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .groupRowFocus(focused)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val isTv = rememberIsTvDevice()
        // Apple TV: no leading glyph on these rows.
        if (!isTv) {
            // Apple draws these link rows in textSecondary, not the accent.
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(10.dp))
        }
        Text(
            text = label,
            style = settingsRowValueStyle().subtext(),
            // Apple TV: Open Source Licenses reads in the primary text color.
            color = if (isTv && chevron) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (chevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
        if (external && isTv) {
            // Apple TV: a QR glyph on the trailing edge (the row opens a QR
            // code to scan with a phone).
            Icon(
                imageVector = Icons.Filled.QrCode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        } else if (external) {
            Icon(
                imageVector = Icons.Outlined.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

// MARK: - Section shell helpers

@Composable
private fun SectionHeader(text: String) {
    // Phase 3b: one header style app-wide (uppercase, letter-spaced, accent).
    com.aeriotv.android.ui.settings.SettingsSectionHeader(text)
}

@Composable
private fun SectionFooter(text: String) {
    Text(
        text = text,
        // TV takes the tvOS footnote (20pt halved); PHONES keep labelSmall
        // verbatim - the shared helper falls back to bodySmall, which would
        // have quietly enlarged frozen phone canon.
        style = (if (rememberIsTvDevice()) settingsFootnoteStyle()
        else MaterialTheme.typography.labelSmall).subtext(),
        color = com.aeriotv.android.ui.settings.settingsDimTint(),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun RowDivider() {
    // One implementation app-wide (ui/settings/SettingsRows.kt).
    com.aeriotv.android.ui.settings.SettingsRowDivider()
}

/**
 * Marketing name for this device. Several makers already prefix the model with
 * the brand ("Google TV Streamer" on a Google box), so blindly joining the two
 * printed "Google Google TV Streamer" in the About panel.
 */
private fun deviceDisplayName(): String {
    val manufacturer = android.os.Build.MANUFACTURER.orEmpty().trim()
    val model = android.os.Build.MODEL.orEmpty().trim()
    return when {
        model.isEmpty() -> manufacturer
        manufacturer.isEmpty() -> model
        model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "$manufacturer $model"
    }
}

private fun formatInstallTime(ms: Long): String {
    if (ms <= 0L) return "Unknown"
    // Apple .long: "September 25, 2026".
    return DateFormat.getDateInstance(DateFormat.LONG).format(Date(ms))
}

private fun buildAboutClipboard(
    versionName: String,
    versionCode: Long,
    installedAt: Long,
    updatedAt: Long,
): String = buildString {
    appendLine("AerioTV diagnostics")
    appendLine("Device: ${deviceDisplayName()}")
    appendLine("System: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
    appendLine("App Version: $versionName ($versionCode)")
    appendLine("First Installed: ${formatInstallTime(installedAt)}")
    appendLine("Last Updated: ${if (updatedAt > 0 && updatedAt != installedAt) formatInstallTime(updatedAt) else "Never"}")
}

private fun openUrl(context: android.content.Context, url: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            android.widget.Toast.makeText(
                context,
                "No browser available to open $url",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
}

/**
 * Settings tree sub-sections. Each carries its title / subtitle / icon for
 * the parent [SettingsScreen] rows and a stable identifier for navigation.
 */
enum class SettingsSection(
    val title: String,
    val subtitle: String?,
    val icon: ImageVector,
) {
    LiveTV(
        title = "Live TV",
        subtitle = "Guide, groups, badges, colors",
        icon = Icons.Filled.LiveTv,
    ),
    Player(
        title = "Player",
        subtitle = "Info card, rewind, gestures, multiview",
        icon = Icons.Outlined.PlayCircle,
    ),
    MoviesAndTvShows(
        title = "Movies & TV Shows",
        subtitle = "Library refresh, posters",
        icon = Icons.Filled.Movie,
    ),
    DvrSettings(
        title = "DVR",
        subtitle = "Recordings, buffers, storage",
        icon = Icons.Filled.FiberManualRecord,
    ),
    Appearance(
        title = "Appearance",
        subtitle = "Theme, text size, time format",
        icon = Icons.Filled.Palette,
    ),
    General(
        title = "General",
        subtitle = "Startup, refresh, network",
        icon = Icons.Filled.Tune,
    ),
    RemoteControl(
        title = "Remote Control",
        subtitle = "Customize remote buttons",
        icon = Icons.Filled.SettingsRemote,
    ),
    Sync(
        // Subtitle comes from [settingsSectionSubtitle]: the row reads the live
        // On / Off state instead of a description. Apple does the same, and the
        // long string truncated on the Android TV rail. The description lives on
        // the Sync page's own Drive Sync footer.
        title = "Sync",
        subtitle = null,
        icon = Icons.Filled.Cloud,
    ),
    AppUpdates(
        title = "Updates",
        subtitle = "Check for new releases",
        icon = Icons.Filled.SystemUpdate,
    ),
    Developer(
        title = "Developer",
        subtitle = "Debug logging & diagnostics",
        icon = Icons.Outlined.BugReport,
    ),
    About(
        title = "About",
        subtitle = null,
        icon = Icons.Outlined.Info,
    ),
}

/**
 * Subtitle for a section row in the root list, the tablet sidebar and the TV
 * rail. Everything but Sync uses its static enum subtitle; Sync reports whether
 * Drive sync is currently on.
 */
fun settingsSectionSubtitle(section: SettingsSection, syncEnabled: Boolean): String? =
    when (section) {
        SettingsSection.Sync -> if (syncEnabled) "On" else "Off"
        // Apple shows AboutInfo.version, "1.8.40 (123)", under About on every
        // platform; same shape here so the rails read identically.
        SettingsSection.About ->
            "${com.aeriotv.android.BuildConfig.VERSION_NAME} (${com.aeriotv.android.BuildConfig.VERSION_CODE})"
        else -> section.subtitle
    }
