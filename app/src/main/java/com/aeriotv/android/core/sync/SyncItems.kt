package com.aeriotv.android.core.sync

/**
 * Per-item sync choices (owner decision 2026-10-27): every synced option is
 * selectable on its own in Settings > Sync > Sync Categories, for Drive and
 * Dispatcharr Sync alike. Mirrors Apple Shared/SyncFlags.swift (same item
 * ids, labels, and groups).
 *
 * Settings items take a four-way [SyncScope] stored per device under
 * "syncScope.<item id>" (missing = the old App Preferences category flag:
 * on = All devices, off = Off). Data items keep a plain on/off, either the
 * existing category flag or "syncItem.<id>".
 *
 * Lanes (docs/DispatcharrSyncSchema.md 4.9): All devices rides `v`, TVs only
 * rides `tv` (written and read by TVs only), Phones and tablets only rides
 * `mobile` (phones and tablets only). Off neither writes nor reads, and the
 * value already on the server or in Drive is left untouched.
 *
 * Pure (no Android types) so the lane rules are unit tested.
 */
enum class SyncScope(val wire: String, val title: String) {
    All("all", "All devices"),
    Tv("tv", "TVs only"),
    Mobile("mobile", "Phones and tablets only"),
    Off("off", "Off"),
    ;

    /**
     * The scope as this device honors it: a device offers only its own class,
     * so the other class's lane (for example "TVs only" restored onto a phone)
     * reads as All devices.
     */
    fun effective(isTv: Boolean): SyncScope = when (this) {
        Tv -> if (isTv) Tv else All
        Mobile -> if (isTv) All else Mobile
        else -> this
    }

    /** The lane this device writes and reads for an item with this scope, or null (Off). */
    fun lane(isTv: Boolean): String? = when (effective(isTv)) {
        All -> LANE_ALL
        Tv -> LANE_TV
        Mobile -> LANE_MOBILE
        Off -> null
    }

    companion object {
        const val LANE_ALL = "v"
        const val LANE_TV = "tv"
        const val LANE_MOBILE = "mobile"
        val LANES = listOf(LANE_ALL, LANE_TV, LANE_MOBILE)
        fun fromWire(s: String?): SyncScope? = entries.firstOrNull { it.wire == s }

        /** The choices this device offers: its own class lane only. */
        fun choices(isTv: Boolean): List<SyncScope> = listOf(All, if (isTv) Tv else Mobile, Off)
    }
}

/**
 * One row in Sync Categories. [names] are the Dispatcharr schema names
 * (section 4.9) the item carries; [prefixes] cover name families such as
 * `categoryColor.<bucket>`. Data items name their Dispatcharr key in
 * [dsyncSuffix]; [category] binds the toggle to an existing category flag.
 */
data class SyncItem(
    val id: String,
    val label: String,
    val names: List<String> = emptyList(),
    val prefixes: List<String> = emptyList(),
    val isData: Boolean = false,
    val category: SyncCategory? = null,
    val dsyncSuffix: String? = null,
    /** Shown only for Drive (Playlists & Servers, Credentials). */
    val driveOnly: Boolean = false,
    /** Shown only for Dispatcharr Sync (Profiles). */
    val dsyncOnly: Boolean = false,
) {
    val scopeKey: String get() = "syncScope.$id"
    val toggleKey: String get() = "syncItem.$id"
    fun covers(name: String): Boolean = name in names || prefixes.any { name.startsWith(it) }
}

data class SyncItemGroup(val id: String, val title: String, val items: List<SyncItem>) {
    val isData: Boolean get() = items.all { it.isData }
}

object SyncItemCatalog {

    val groups: List<SyncItemGroup> = listOf(
        SyncItemGroup("appearance", "Appearance", listOf(
            SyncItem("selectedTheme", "Color Theme", listOf("selectedTheme")),
            SyncItem("appearanceMode", "Light and Dark Mode", listOf("appearanceMode")),
            SyncItem("trueBlack", "True Black Background", listOf("trueBlack")),
            SyncItem("customAccent", "Custom Accent Color", listOf("useCustomAccent", "customAccentHex")),
            SyncItem("textScale", "Text Size", listOf("textScale")),
            SyncItem("subtextScale", "Subtext Size", listOf("subtextScale")),
            SyncItem("textContrast", "Text Contrast", listOf("textContrast")),
            SyncItem("timeFormat", "Time Format", listOf("timeFormat")),
        )),
        SyncItemGroup("general", "General", listOf(
            SyncItem("defaultTab", "Default Tab", listOf("defaultTab")),
            SyncItem("skipLoadingScreen", "Skip Loading Screen", listOf("skipLoadingScreen")),
            SyncItem("resumeLastChannel", "Resume Last Channel", listOf("autoResumeLastChannel")),
        )),
        SyncItemGroup("liveTV", "Live TV", listOf(
            SyncItem("defaultLiveTVView", "Default View", listOf("defaultLiveTvView")),
            SyncItem("guideLayout", "Guide Layout", listOf("guideLayout")),
            SyncItem("groupSelection", "Group Selection", listOf("phoneGroupSelector", "guideGroupSelector", "guideSidebarLayout")),
            SyncItem("edgeSwipeSidebar", "Swipe from Left Edge Opens Sidebar", listOf("liveTvEdgeSwipeOpensSidebar")),
            SyncItem("showChannelLogos", "Show Channel Logos", listOf("channelListShowLogos")),
            SyncItem("showChannelNumbers", "Show Channel Numbers", listOf("channelListShowNumbers")),
            SyncItem("showChannelNames", "Show Channel Names", listOf("channelListShowNames")),
            SyncItem("showProgramSubtitles", "Show Program Subtitles", listOf("channelListShowProgramSubtitles")),
            SyncItem("logoSize", "Logo Size", listOf("channelLogoSize")),
            SyncItem("programBadges", "Show Program Badges", listOf("showEpgBadgesTv", "showEpgBadgesMobile", "hiddenEpgBadges")),
            SyncItem("programInfoButton", "Program Info Button", listOf("guideProgramInfoButton", "programInfoButtonGuide", "programInfoButtonList")),
            SyncItem("roundedGuideLogos", "Rounded Guide Logos", listOf("logoCornersGuide")),
            SyncItem("roundedProgramCells", "Rounded Program Cells", listOf("logoCornersGuideCells")),
            SyncItem("roundedListLogos", "Rounded List Logos and Artwork", listOf("logoCornersList")),
            SyncItem("liveTVDisplayScale", "Display Scale", listOf("displayScaleLiveTv", "displayScaleGuide")),
            SyncItem(
                "categoryColors", "Color Programs by Category", listOf("enableCategoryColors", "customCategories"),
                prefixes = listOf("categoryColor.", "categoryBucketEnabled."),
            ),
        )),
        SyncItemGroup("player", "Player", listOf(
            SyncItem("infoCard", "Pop Up Info Card on Channel Change", listOf("playerInfoCard.showOnChannelChange") + INFO_CARD_ROWS.map { "playerInfoCard.$it" }),
            SyncItem("liveRewind", "Pause & Rewind Live TV", listOf(
                "liveRewindEnabled", "liveRewindDepthMinutes", "liveRewindKeepRecent", "liveRewindKeepCount",
                "liveRewindRetentionHours", "liveRewindBudgetGb",
            )),
            SyncItem("skipIntervals", "Skip Back and Skip Forward", listOf("skipBack", "skipForward")),
            SyncItem("skipWithoutControls", "Skip Without Controls", listOf("skipWithoutControls")),
            SyncItem("autoRecover", "Auto-Recover Frozen Streams", listOf("autoRecoverFrozenStreams")),
            SyncItem("channelFlip", "Up / Down Channel Change", listOf("appleTVChannelFlip")),
            SyncItem("edgeBrightness", "Edge Slide for Brightness", listOf("edgeBrightnessGesture", "brightnessEdge")),
            SyncItem("edgeVolume", "Edge Slide for Volume", listOf("edgeVolumeGesture")),
            SyncItem("multiviewAudioFocus", "Audio Focus Indicator", listOf("multiview.audioFocusStyle")),
            SyncItem("multiviewPadding", "Padding Between Tiles", listOf("multiview.tilePadding")),
            SyncItem("multiviewCorners", "Tile Corners", listOf("multiview.tileCornersRounded")),
            SyncItem("multiviewLogos", "Multiview Channel Logos", listOf("multiview.showLogos", "multiview.logoPosition", "multiview.logoSize")),
            SyncItem("multiviewSubtitles", "Multiview Subtitles", listOf("multiview.pickerShowSubtitles")),
            SyncItem("multiviewDescriptions", "Multiview Descriptions", listOf("multiview.pickerShowDescriptions")),
        )),
        SyncItemGroup("vod", "Movies & TV Shows", listOf(
            SyncItem("refreshLibrary", "Refresh Library", listOf("vodLibraryRefreshHours")),
            SyncItem("tmdb", "Use TMDB for Movies and TV Shows", listOf("tmdbPostersEnabled")),
            SyncItem("vodDisplayScale", "Display Scale", listOf("displayScaleMovies")),
            SyncItem("vodSortOrder", "Sort Order", listOf("moviesSortOrder", "seriesSortOrder")),
        )),
        SyncItemGroup("dvr", "DVR", listOf(
            SyncItem("preRoll", "Start Early (Pre-Roll)", listOf("dvrPreRollMins")),
            SyncItem("postRoll", "End Late (Post-Roll)", listOf("dvrPostRollMins")),
            SyncItem("defaultDestination", "Default Destination", listOf("dvrDefaultDestination")),
            SyncItem("dvrSortOrder", "Sort Order", listOf("dvrSortOrder")),
        )),
        SyncItemGroup("remote", "Remote Control", listOf(
            SyncItem("remoteHints", "Show Remote Hints", listOf("showRemoteHints")),
            SyncItem("exitFocus", "Default to Exit", listOf("exitDialogFocusExit")),
            SyncItem("playChannelsIn", "Play Channels In", listOf("guideTuneInMini")),
            SyncItem("remoteMap", "Remote Button Map", isData = true, dsyncSuffix = "remoteMap"),
        )),
        SyncItemGroup("data", "Your Data", listOf(
            SyncItem("servers", "Playlists & Servers", isData = true, category = SyncCategory.Playlists, driveOnly = true),
            SyncItem("credentials", "Credentials", isData = true, category = SyncCategory.Credentials, driveOnly = true),
            SyncItem("favorites", "Favorites", isData = true, category = SyncCategory.Favorites, dsyncSuffix = "favorites"),
            SyncItem("recents", "Recently Watched", isData = true, dsyncSuffix = "recents"),
            SyncItem("collections", "Collections", isData = true, dsyncSuffix = "collections"),
            SyncItem("watchProgress", "Watch Progress", isData = true, category = SyncCategory.WatchProgress, dsyncSuffix = "progress"),
            SyncItem("reminders", "Reminders", isData = true, category = SyncCategory.Reminders, dsyncSuffix = "reminders"),
            SyncItem("watchlist", "My List", isData = true, category = SyncCategory.Watchlist, dsyncSuffix = "watchlist"),
            SyncItem("hiddenTitles", "Hidden Titles", isData = true, dsyncSuffix = "hiddenTitles"),
            SyncItem("hiddenGroups", "Hidden Groups", isData = true, dsyncSuffix = "hiddenGroups"),
            // The per-playlist group choices ride `settings` but follow this toggle (Apple parity).
            SyncItem("groupOrder", "Group Order", listOf("defaultGroup", "lastGroup", "recentGroupVisible"), isData = true, dsyncSuffix = "groupOrder"),
            SyncItem("profiles", "Profiles", isData = true, dsyncSuffix = "profiles", dsyncOnly = true),
            SyncItem("radioDefault", "Radio Mode by Default", isData = true, dsyncSuffix = "radioDefault"),
        )),
    )

    val allItems: List<SyncItem> get() = groups.flatMap { it.items }

    fun item(id: String): SyncItem? = allItems.firstOrNull { it.id == id }

    /** The item that carries a settings schema name, or null (not ours). */
    fun itemForName(name: String): SyncItem? = allItems.firstOrNull { it.covers(name) }

    /** The data item for a Dispatcharr key suffix. */
    fun itemForDsync(suffix: String): SyncItem? = allItems.firstOrNull { it.dsyncSuffix == suffix }
}

/** The six Player Info Card rows, as `playerInfoCard.<row>`. */
val INFO_CARD_ROWS = listOf("channelLogo", "channelName", "programName", "programTime", "programSubtitle", "programDescription")

/**
 * Splits per-lane values for a push: every name goes to the lane its item's
 * scope picks on this device; names whose scope is Off here (or whose lane
 * belongs to the other form factor) are not written. Pure.
 */
fun <V> laneValues(values: Map<String, V>, scopeOf: (String) -> SyncScope, isTv: Boolean): Map<String, Map<String, V>> {
    val out = LinkedHashMap<String, LinkedHashMap<String, V>>()
    values.forEach { (name, v) ->
        val lane = scopeOf(name).lane(isTv) ?: return@forEach
        out.getOrPut(lane) { LinkedHashMap() }[name] = v
    }
    return out
}

/**
 * Picks pulled values: each name is read from the lane its scope picks on
 * this device and nowhere else. Pure.
 */
fun <V> pickFromLanes(lanes: Map<String, Map<String, V>>, names: Collection<String>, scopeOf: (String) -> SyncScope, isTv: Boolean): Map<String, V> {
    val out = LinkedHashMap<String, V>()
    val all = names.toMutableSet()
    lanes.values.forEach { all += it.keys }
    all.forEach { name ->
        val lane = scopeOf(name).lane(isTv) ?: return@forEach
        lanes[lane]?.get(name)?.let { out[name] = it }
    }
    return out
}
