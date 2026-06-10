package com.aeriotv.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aeriotv.android.ui.adaptive.adaptiveFormWidth
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Settings root. Mirrors iOS SettingsView.swift section ordering + grouped-card
 * presentation (lines 150-496):
 *
 *  1. Playlists  — inline list of every saved playlist with tap-to-activate,
 *                  long-press context menu (Edit / Delete), and an Add Playlist
 *                  row. Footer surfaces the same hints iOS shows
 *                  ("Tap ○ to set the active playlist" etc.).
 *  2. App Settings — Appearance / App Behaviors / Multiview / Network rows
 *                  inside a single grouped card.
 *  3. Sync       — current cut routes through to the full SyncSettingsScreen.
 *                  iOS surfaces the toggle inline here; that follow-up lands
 *                  alongside the Google Drive Sync rewrite that mirrors the
 *                  iCloud Sync toggle / Sync Now / Clear Data set.
 *  4. DVR        — single nav row.
 *  5. Developer  — single nav row.
 *  6. About      — Device / System / App Version / First Installed /
 *                  Last Updated + Copy / Developer Website / Report an Issue.
 *
 * Each section is a [SettingsSectionGroup] — uppercase header in primary
 * tint, rounded card containing the rows separated by hairline dividers,
 * optional footer text in muted-tint below. Mirrors iOS .insetGrouped list
 * style + sectionHeaderStyle().
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onSectionClick: (SettingsSection) -> Unit,
    onOpenPlaylistDetail: () -> Unit = {},
    onOpenPlaylists: () -> Unit = {},
    onAddPlaylist: () -> Unit = {},
    onEditPlaylist: (PlaylistEntity) -> Unit = {},
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Flavor-gated: the App Updates row only exists on the GitHub/sideload
    // channel (play flavor binds a disabled no-op manager).
    val updateVm: com.aeriotv.android.feature.update.UpdateViewModel = hiltViewModel()
    val updaterEnabled = updateVm.isEnabled
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playlists by viewModel.allPlaylists.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeId = state.playlist?.id

    var pendingDelete by remember { mutableStateOf<PlaylistEntity?>(null) }

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

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
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
            modifier = Modifier.adaptiveFormWidth().fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // MARK: Playlists
            item("playlists") {
                PlaylistsSection(
                    playlists = playlists,
                    activeId = activeId,
                    onTap = { pl ->
                        if (pl.id == activeId) onOpenPlaylistDetail()
                        else viewModel.switchToPlaylist(pl.id)
                    },
                    onEdit = { onEditPlaylist(it) },
                    onDelete = { pendingDelete = it },
                    onAdd = onAddPlaylist,
                    onManage = onOpenPlaylists,
                )
            }

            // MARK: App Settings
            item("app-settings") {
                SettingsSectionGroup(
                    header = "App Settings",
                    rows = buildList {
                        add(SettingsSection.Appearance)
                        add(SettingsSection.AppBehaviors)
                        add(SettingsSection.Multiview)
                        add(SettingsSection.Network)
                        if (updaterEnabled) add(SettingsSection.AppUpdates)
                    },
                    onClick = onSectionClick,
                )
            }

            // MARK: Sync
            item("sync") {
                SettingsSectionGroup(
                    header = "Sync",
                    rows = listOf(SettingsSection.Sync),
                    onClick = onSectionClick,
                    footer = "Playlists, preferences, and watch progress sync across all devices " +
                        "signed into the same Google account. Credentials stay in encrypted Android storage.",
                )
            }

            // MARK: DVR
            item("dvr") {
                SettingsSectionGroup(
                    header = "DVR",
                    rows = listOf(SettingsSection.DvrSettings),
                    onClick = onSectionClick,
                )
            }

            // MARK: Developer
            item("developer") {
                SettingsSectionGroup(
                    header = "Developer",
                    rows = listOf(SettingsSection.Developer),
                    onClick = onSectionClick,
                )
            }

            // MARK: About
            item("about") {
                AboutSection(
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
                    onOpenWebsite = { openUrl(context, "https://github.com/jonzey231/AerioTV-Android") },
                    onReportIssue = { openUrl(context, "https://github.com/jonzey231/AerioTV-Android/issues/new") },
                )
            }
        }
        }
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
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.deletePlaylist(pl.id)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

// MARK: - Playlists section

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistsSection(
    playlists: List<PlaylistEntity>,
    activeId: String?,
    onTap: (PlaylistEntity) -> Unit,
    onEdit: (PlaylistEntity) -> Unit,
    onDelete: (PlaylistEntity) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
) {
    Column {
        SectionHeader("Playlists")
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)),
        ) {
            if (playlists.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No playlists added",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                playlists.forEachIndexed { index, pl ->
                    if (index > 0) RowDivider()
                    PlaylistRow(
                        playlist = pl,
                        isActive = pl.id == activeId,
                        onTap = { onTap(pl) },
                        onEdit = { onEdit(pl) },
                        onDelete = { onDelete(pl) },
                    )
                }
                RowDivider()
            }
            // Add Playlist row — iOS calls this out with a cyan plus glyph
            // (SettingsView line 206-220).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
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
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (playlists.size > 1) {
                RowDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onManage)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Manage Playlists",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (playlists.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SectionFooter("Tap ○ to set the active playlist · Long press to edit or delete")
            if (playlists.size > 1) {
                SectionFooter("Tap Manage Playlists to reorder")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: PlaylistEntity,
    isActive: Boolean,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Guard the long-press menu against the Android TV D-pad bug where the OK
    // RELEASE auto-selects the menu's first item (Edit) the instant the user
    // lifts off -- so a long-press to Delete always landed on Edit. arm() when
    // the menu opens, wrap() each item: the spurious release-click within the
    // grace window is swallowed, leaving Delete reachable. No-op on touch.
    val tvGuard = rememberTvMenuGuard()
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Wrap onTap too: on some focus configurations the OK-release
                // KEY_UP after a long-press is delivered back to the originating
                // row (firing onTap = "open Edit") rather than to the menu item.
                // Guarding both paths means the spurious release-click is caught
                // wherever it lands; the AerioLongPress log shows which fired.
                .combinedClickable(onClick = tvGuard.wrap(onTap), onLongClick = { menuOpen = true; tvGuard.arm() })
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Radio-button active marker — filled cyan dot inside a ring on
            // the active row, empty ring on the rest. Matches the iOS
            // SettingsView footer hint "Tap ○ to set the active playlist".
            Icon(
                imageVector = if (isActive) Icons.Filled.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (isActive) "Active" else "Set active",
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                )
                val subtitle = buildString {
                    append("${playlist.channelCount} channels")
                    val pretty = playlist.sourceType
                        .replace("Dispatcharr", "Dispatcharr ")
                        .replace("ApiKey", "API Key")
                        .replace("UserPass", "Username & Password")
                        .replace("M3uUrl", "M3U + EPG")
                        .replace("XtreamCodes", "Xtream Codes")
                    if (pretty.isNotBlank()) append("  ·  ").append(pretty)
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                onClick = tvGuard.wrap { menuOpen = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = tvGuard.wrap { menuOpen = false; onDelete() },
            )
        }
    }
}

// MARK: - Generic grouped section

@Composable
private fun SettingsSectionGroup(
    header: String,
    rows: List<SettingsSection>,
    onClick: (SettingsSection) -> Unit,
    footer: String? = null,
) {
    Column {
        SectionHeader(header)
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)),
        ) {
            rows.forEachIndexed { index, section ->
                if (index > 0) RowDivider()
                SectionNavRow(section = section, onClick = { onClick(section) })
            }
        }
        footer?.let {
            Spacer(Modifier.height(8.dp))
            SectionFooter(it)
        }
    }
}

@Composable
private fun SectionNavRow(section: SettingsSection, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = section.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = section.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - About section

@Composable
private fun AboutSection(
    versionName: String,
    versionCode: Long,
    installedAt: Long,
    updatedAt: Long,
    onCopy: () -> Unit,
    onOpenWebsite: () -> Unit,
    onReportIssue: () -> Unit,
) {
    Column {
        SectionHeader("About")
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)),
        ) {
            AboutInfoRow("Device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
            RowDivider()
            AboutInfoRow("System", "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            RowDivider()
            AboutInfoRow("App Version", "$versionName ($versionCode)")
            RowDivider()
            AboutInfoRow("First Installed", formatInstallTime(installedAt))
            RowDivider()
            AboutInfoRow(
                "Last Updated",
                if (updatedAt > 0 && updatedAt != installedAt) formatInstallTime(updatedAt) else "Never",
            )
            RowDivider()
            AboutActionRow("Copy to Clipboard", Icons.Filled.ContentCopy, onClick = onCopy)
            RowDivider()
            AboutActionRow(
                "Developer Website",
                Icons.Outlined.OpenInNew,
                onClick = onOpenWebsite,
                external = true,
            )
            RowDivider()
            AboutActionRow(
                "Report an Issue",
                Icons.Outlined.BugReport,
                onClick = onReportIssue,
                external = true,
            )
        }
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun AboutActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    external: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (external) {
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
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun SectionFooter(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f),
        modifier = Modifier.padding(start = 14.dp),
    )
}

private fun formatInstallTime(ms: Long): String {
    if (ms <= 0L) return "Unknown"
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
}

private fun buildAboutClipboard(
    versionName: String,
    versionCode: Long,
    installedAt: Long,
    updatedAt: Long,
): String = buildString {
    appendLine("AerioTV diagnostics")
    appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
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
    val subtitle: String,
    val icon: ImageVector,
) {
    Appearance(
        title = "Appearance",
        subtitle = "Theme, scale & category colors",
        icon = Icons.Filled.Palette,
    ),
    AppBehaviors(
        title = "App Behaviors",
        subtitle = "Splash, default tab, channel-flip",
        icon = Icons.Outlined.PlayCircle,
    ),
    Multiview(
        title = "Multiview",
        subtitle = "Audio focus, tile spacing & corners",
        icon = Icons.Filled.GridView,
    ),
    Network(
        title = "Network",
        subtitle = "Timeout, buffer, home WiFi & refresh",
        icon = Icons.Filled.Wifi,
    ),
    AppUpdates(
        title = "App Updates",
        subtitle = "Check for new releases",
        icon = Icons.Filled.SystemUpdate,
    ),
    Sync(
        title = "Sync",
        subtitle = "Sync playlists, preferences, and watch progress",
        icon = Icons.Filled.Cloud,
    ),
    DvrSettings(
        title = "DVR",
        subtitle = "Recordings, buffers & storage",
        icon = Icons.Filled.FiberManualRecord,
    ),
    Developer(
        title = "Developer",
        subtitle = "Debug logging & diagnostics",
        icon = Icons.Filled.Movie,
    ),
}
