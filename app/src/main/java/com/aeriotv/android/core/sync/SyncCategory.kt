package com.aeriotv.android.core.sync

/**
 * One category of synced data. iOS mirrors the same taxonomy under iCloud
 * Sync > Sync Categories. Each category persists to its own Drive AppData
 * file ([fileName]) so partial-category sync is atomic.
 */
enum class SyncCategory(
    val displayName: String,
    val subtitle: String,
    val fileName: String,
    val storageSuffix: String,
) {
    Playlists(
        displayName = "Playlists & Servers",
        subtitle = "M3U URLs, Dispatcharr servers, Xtream credentials (server-side only)",
        fileName = "playlists.v1.json",
        storageSuffix = "playlists",
    ),
    WatchProgress(
        displayName = "VOD Watch Progress",
        subtitle = "Resume points for movies + episodes",
        fileName = "watch_progress.v1.json",
        storageSuffix = "watch_progress",
    ),
    Reminders(
        displayName = "Reminders",
        subtitle = "Scheduled programme reminders",
        fileName = "reminders.v1.json",
        storageSuffix = "reminders",
    ),
    Preferences(
        displayName = "App Preferences",
        subtitle = "Theme, appearance mode, accent color, default tab, hidden groups, palette overrides",
        fileName = "preferences.v1.json",
        storageSuffix = "preferences",
    ),
    Credentials(
        displayName = "Credentials",
        subtitle = "Server passwords + API keys (Drive AppData is private to the app)",
        fileName = "credentials.v1.json",
        storageSuffix = "credentials",
    );

    fun enabledStorageKey(): String = "syncCategory.$storageSuffix"
}
