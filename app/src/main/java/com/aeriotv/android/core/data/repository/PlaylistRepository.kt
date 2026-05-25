package com.aeriotv.android.core.data.repository

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import android.content.Context
import com.aeriotv.android.core.network.DispatcharrAuthBroker
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.DispatcharrEpgEntry
import com.aeriotv.android.core.network.DispatcharrTokenStore
import com.aeriotv.android.core.network.PlaylistFetcher
import com.aeriotv.android.core.parser.M3UParser
import com.aeriotv.android.core.parser.XMLTVParser
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.wifi.WifiSsidProbe
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Single source of truth for playlist persistence + fetch + parse.
 * Mirrors how iOS Aerio handles playlists: row stored in SwiftData, channels
 * re-parsed from source on every refresh (NOT cached individually).
 *
 * Derives the M3U URL, EPG URL, and HTTP headers from the playlist's
 * [PlaylistEntity.sourceType]:
 *  - M3uUrl: use [PlaylistEntity.urlString] directly + optional [PlaylistEntity.epgUrl].
 *  - DispatcharrApiKey: M3U = `${urlString}/output/m3u`, EPG = `${urlString}/output/epg`,
 *    headers = `{X-API-Key: ${apiKey}, Accept: application/json}` (Phase 4a).
 *  - DispatcharrUserPass: log in to /api/auth/token/, exchange the JWT access
 *    token for the user's API key via /api/accounts/users/me/, then proceed
 *    exactly like DispatcharrApiKey. Mirrors iOS DispatcharrDirectConnect's
 *    silent-rebootstrap pattern.
 *  - XtreamCodes: TODO Phase 4c (player_api.php enumeration -> get.php m3u_plus).
 */
@Singleton
class PlaylistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PlaylistDao,
    private val fetcher: PlaylistFetcher,
    private val dispatcharrClient: DispatcharrClient,
    private val dispatcharrAuth: DispatcharrAuthBroker,
    private val dispatcharrTokenStore: DispatcharrTokenStore,
    private val appPreferences: AppPreferences,
) {

    /**
     * Returns [PlaylistEntity.lanUrlString] when the device is connected to
     * one of the user's saved home SSIDs and the playlist has a LAN URL set;
     * otherwise [PlaylistEntity.urlString]. Reads the SSID via WifiSsidProbe
     * on every call so a network change since the last read is reflected
     * immediately.
     */
    suspend fun effectiveBaseUrl(playlist: PlaylistEntity): String {
        val lan = playlist.lanUrlString?.takeIf { it.isNotBlank() } ?: return playlist.urlString
        val homeSsids = appPreferences.homeSsidsOnce()
        if (homeSsids.isEmpty()) return playlist.urlString
        val ssid = WifiSsidProbe.currentSsid(context) ?: return playlist.urlString
        return if (ssid in homeSsids) lan else playlist.urlString
    }

    /** Inputs for creating or updating a playlist row. */
    data class SaveRequest(
        val sourceType: SourceType,
        val name: String?,
        val url: String,
        val lanUrl: String? = null,
        val epgUrl: String? = null,
        val apiKey: String? = null,
        val username: String? = null,
        val password: String? = null,
        /** Dispatcharr channel-profile id to scope this playlist to, or null
         * for "All Channels". Ignored for non-Dispatcharr sources. */
        val dispatcharrProfileId: Int? = null,
    )

    suspend fun activePlaylist(): PlaylistEntity? = dao.firstActive()

    suspend fun loadAndPersist(
        request: SaveRequest,
        existingId: String? = null,
    ): Result<Pair<PlaylistEntity, List<M3UChannel>>> = runCatching {
        val normalisedBase = request.url.trimEnd('/')
        val sourceType = request.sourceType
        if (!sourceType.isImplemented) {
            throw UnsupportedOperationException(
                "${sourceType.displayName} support lands in a later phase",
            )
        }

        // Generate the playlist id up front so the JWT pair from a UserPass login
        // can land in DispatcharrTokenStore under the same key the rest of the
        // flow uses (refresh, warmup, silent rebootstrap on subsequent 401s).
        val playlistId = existingId ?: UUID.randomUUID().toString()

        // For Dispatcharr User/Pass, do the JWT exchange up front and resolve to an api_key
        // so the rest of the flow looks identical to API-key mode. iOS does this too
        // (silent rebootstrap pattern, DispatcharrDirectConnect.swift line 534-588).
        val resolvedApiKey: String? = when (sourceType) {
            SourceType.DispatcharrUserPass -> {
                val u = request.username?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Username is required")
                val p = request.password?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Password is required")
                val jwt = dispatcharrClient.login(normalisedBase, u, p)
                // Stash the JWT pair so the warmup coordinator picks up the
                // refresh token on the next app foreground and the bearer-mode
                // calls don't need to re-login from scratch every session.
                dispatcharrTokenStore.store(playlistId, jwt.access, jwt.refresh)
                dispatcharrClient.fetchCurrentUserApiKey(normalisedBase, jwt.access)
            }
            else -> request.apiKey
        }

        val channels = fetchChannelsFor(
            sourceType = sourceType,
            base = normalisedBase,
            userEpgUrl = request.epgUrl,
            apiKey = resolvedApiKey,
            profileId = request.dispatcharrProfileId,
        )

        val entity = PlaylistEntity(
            id = playlistId,
            name = request.name?.takeIf { it.isNotBlank() } ?: deriveName(normalisedBase),
            urlString = normalisedBase,
            lanUrlString = request.lanUrl?.trimEnd('/')?.takeIf { it.isNotBlank() },
            epgUrl = request.epgUrl?.takeIf { it.isNotBlank() },
            sourceType = sourceType.name,
            apiKey = resolvedApiKey?.takeIf { it.isNotBlank() },
            username = request.username?.takeIf { it.isNotBlank() },
            password = request.password?.takeIf { it.isNotBlank() },
            channelCount = channels.size,
            lastRefreshedAt = System.currentTimeMillis(),
            isActive = true,
            dispatcharrProfileId = request.dispatcharrProfileId,
        )
        // New / re-loaded playlist becomes the active one. Mirrors iOS commit
        // f72b942 — wrap "deactivate others + upsert" in a transactional DAO
        // method so two concurrent server-add calls can't interleave between
        // the deactivate pass and the upsert, leaving zero or two active rows.
        // Editing the already-active row skips the deactivate step.
        if (existingId == null || dao.byId(existingId)?.isActive != true) {
            dao.upsertAsActive(entity)
        } else {
            dao.upsert(entity)
        }
        entity to channels
    }

    /**
     * Re-fetch channels for an existing playlist row, updating channelCount and
     * lastRefreshedAt without changing identity fields.
     */
    suspend fun refresh(playlist: PlaylistEntity): Result<List<M3UChannel>> = runCatching {
        val sourceType = playlist.resolvedSourceType()
        val base = effectiveBaseUrl(playlist)
        // Dispatcharr branches go through the AuthBroker so a rotated api_key
        // gets silently rebootstrapped instead of surfacing a 401. M3U / Xtream
        // fall straight through to fetchChannelsFor.
        val channels = when (sourceType) {
            SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass ->
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    fetchChannelsFor(sourceType, base, playlist.epgUrl, key, playlist.dispatcharrProfileId)
                }
            else -> fetchChannelsFor(sourceType, base, playlist.epgUrl, playlist.apiKey, playlist.dispatcharrProfileId)
        }
        dao.update(
            playlist.copy(
                channelCount = channels.size,
                lastRefreshedAt = System.currentTimeMillis(),
            ),
        )
        channels
    }

    /**
     * Dispatcharr-only follow-up that fans `/api/epg/programs/<id>/` requests
     * for every channel's currently-airing programme, in parallel with a cap
     * of 4 concurrent in-flight fetches. Mirrors iOS
     * `EPGGuideView.enrichDispatcharrCategories` (post v1.6.22) — the bulk
     * `/api/epg/grid/` endpoint deliberately strips `<category>` for perf, so
     * we lazily backfill the category on the now-airing program per channel
     * after the grid lands. Categories are propagated to every same-titled
     * future programme on the same channel so a recurring show (SportsCenter,
     * Dateline) keeps its tint across re-airings.
     *
     * Returns the input list when [playlist] isn't a Dispatcharr source.
     * Failures on individual program fetches are swallowed silently — a
     * single 404 shouldn't blank out the whole channel's tint.
     */
    suspend fun enrichNowPlayingCategories(
        playlist: PlaylistEntity,
        programmes: List<EPGProgramme>,
    ): List<EPGProgramme> {
        val sourceType = playlist.resolvedSourceType()
        val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
            sourceType == SourceType.DispatcharrUserPass
        if (!isDispatcharr || programmes.isEmpty()) return programmes

        val now = System.currentTimeMillis()
        // Group nominees: channels whose currently-airing program has a real
        // integer programID and a blank category (so we don't re-fetch
        // already-enriched data or Dummy EPG string-id rows).
        val nowPlayingByChannel: Map<String, EPGProgramme> = programmes.asSequence()
            .filter { it.startMillis <= now && it.endMillis > now }
            .filter { it.category.isBlank() && it.dispatcharrProgramId != null }
            .associateBy { it.channelId }
        if (nowPlayingByChannel.isEmpty()) return programmes

        val base = effectiveBaseUrl(playlist)

        // Cap-of-4 fan-out matches iOS `enrichCategories(programIDs:)`.
        // Anything higher and Dispatcharr starts shedding connections; lower
        // and a thousand-channel playlist takes minutes to fully tint.
        val gate = Semaphore(4)
        val categoryByProgramId: Map<Int, String> = coroutineScope {
            nowPlayingByChannel.values.mapNotNull { p ->
                val pid = p.dispatcharrProgramId ?: return@mapNotNull null
                async {
                    gate.withPermit {
                        runCatching {
                            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                                dispatcharrClient.getProgramDetail(base, key, pid)
                            }
                        }.getOrNull()?.let { detail ->
                            val joined = detail.categories
                                ?.filter { it.isNotBlank() }
                                ?.joinToString(",")
                                .orEmpty()
                            if (joined.isNotBlank()) pid to joined else null
                        }
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }

        if (categoryByProgramId.isEmpty()) return programmes

        // Title-match propagation: for each channel that just got a fresh
        // category on its now-airing program, stamp that category onto every
        // future programme of the SAME title on the same channel. iOS does
        // the same so a recurring news/sports show keeps its tint across the
        // schedule without N more enrichment fetches per channel.
        val titleCategoryByChannel: Map<String, Pair<String, String>> = buildMap {
            nowPlayingByChannel.forEach { (channelId, p) ->
                val pid = p.dispatcharrProgramId ?: return@forEach
                val cat = categoryByProgramId[pid] ?: return@forEach
                put(channelId, p.title to cat)
            }
        }

        return programmes.map { p ->
            if (p.category.isNotBlank()) return@map p
            val pid = p.dispatcharrProgramId
            val direct = pid?.let { categoryByProgramId[it] }
            if (direct != null) return@map p.copy(category = direct)
            // Title-match: same channel + same title gets the same category.
            val (matchTitle, matchCat) = titleCategoryByChannel[p.channelId] ?: return@map p
            if (p.title == matchTitle) p.copy(category = matchCat) else p
        }
    }

    suspend fun loadEpg(playlist: PlaylistEntity): Result<List<EPGProgramme>> = runCatching {
        val sourceType = playlist.resolvedSourceType()
        val base = effectiveBaseUrl(playlist)
        val programmes = when (sourceType) {
            SourceType.M3uUrl -> {
                val epgUrl = playlist.epgUrl?.takeIf { it.isNotBlank() } ?: return@runCatching emptyList()
                val bytes = fetcher.fetchBytes(epgUrl)
                XMLTVParser.parseBytes(bytes)
            }
            SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass -> {
                if (playlist.apiKey.isNullOrBlank()) return@runCatching emptyList()
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getEpgGrid(base, key).toProgrammes()
                }
            }
            SourceType.XtreamCodes -> return@runCatching emptyList()
        }
        dao.update(playlist.copy(lastEpgRefreshedAt = System.currentTimeMillis()))
        programmes
    }

    /**
     * Fetch the Dispatcharr channel profiles available for [playlist] so the
     * Edit Playlist screen can offer them as scoping options. Returns an empty
     * list for non-Dispatcharr sources. Routed through the AuthBroker so a
     * rotated api_key silently rebootstraps instead of surfacing a 401.
     */
    suspend fun listChannelProfiles(playlist: PlaylistEntity): List<ChannelProfileOption> {
        val sourceType = playlist.resolvedSourceType()
        val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
            sourceType == SourceType.DispatcharrUserPass
        if (!isDispatcharr) return emptyList()
        val base = effectiveBaseUrl(playlist)
        return dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
            dispatcharrClient.listProfiles(base, key)
        }.map { ChannelProfileOption(id = it.id, name = it.name, channelCount = it.channels.size) }
    }

    suspend fun clear() {
        dispatcharrTokenStore.clearAll()
        dao.clear()
    }

    /** All stored playlists, observed for the multi-playlist switcher. */
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<PlaylistEntity>> = dao.observeAll()
    suspend fun allOnce(): List<PlaylistEntity> = dao.allOnce()

    /**
     * Make [playlistId] the active row and load its channels + EPG. Returns
     * the resolved entity + channel list, mirroring loadAndPersist's shape
     * so callers can drop a switch into the same state-update flow.
     */
    suspend fun switchActive(playlistId: String): Result<Pair<PlaylistEntity, List<M3UChannel>>> = runCatching {
        dao.switchActive(playlistId)
        val entity = dao.byId(playlistId)
            ?: throw IllegalStateException("Playlist $playlistId vanished after switch")
        val base = effectiveBaseUrl(entity)
        val sourceType = entity.resolvedSourceType()
        val channels = when (sourceType) {
            SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass ->
                dispatcharrAuth.withApiKeyRetry(entity.id) { key ->
                    fetchChannelsFor(sourceType, base, entity.epgUrl, key, entity.dispatcharrProfileId)
                }
            else -> fetchChannelsFor(sourceType, base, entity.epgUrl, entity.apiKey, entity.dispatcharrProfileId)
        }
        dao.update(entity.copy(channelCount = channels.size, lastRefreshedAt = System.currentTimeMillis()))
        entity to channels
    }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> = runCatching {
        // Drop any in-memory JWT pair for the row we're removing so the
        // warmup coordinator stops trying to refresh a dead playlist on
        // the next foreground.
        dispatcharrTokenStore.clear(playlistId)
        dao.deleteById(playlistId)
    }

    /** Persist a user-chosen ordering of playlists. Sequence of ids is taken
     * top-to-bottom and stamped onto displayOrder = 0..n-1 atomically. */
    suspend fun applyPlaylistOrder(orderedIds: List<String>): Result<Unit> = runCatching {
        dao.applyDisplayOrder(orderedIds)
    }

    private fun deriveName(url: String): String =
        url.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Source" }

    private suspend fun fetchChannelsFor(
        sourceType: SourceType,
        base: String,
        userEpgUrl: String?,
        apiKey: String?,
        profileId: Int? = null,
    ): List<M3UChannel> = when (sourceType) {
        SourceType.M3uUrl -> {
            val bytes = fetcher.fetchBytes(base)
            M3UParser.parseBytes(bytes)
        }
        // Both Dispatcharr modes converge here. UserPass calls in with a key
        // that was resolved via JWT login + /api/accounts/users/me/ earlier in
        // loadAndPersist; ApiKey calls in with the user-supplied key. Subsequent
        // refreshes / switchActive use the persisted key on the row regardless
        // of which auth mode originally produced it.
        SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass -> {
            val key = apiKey?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Dispatcharr API key is required")
            val groups = dispatcharrClient.listGroups(base, key)
                .associate { it.id to it.name }
            // Channel-profile scoping. When the playlist is pinned to a profile,
            // resolve that profile's member channel ids and keep only those.
            // A selected-but-now-deleted profile (firstOrNull == null) falls
            // back to showing everything rather than a blank list. Skipped
            // entirely (no extra request) when no profile is selected.
            val allowedChannelIds: Set<Int>? = profileId?.let { pid ->
                runCatching {
                    dispatcharrClient.listProfiles(base, key).firstOrNull { it.id == pid }
                }.getOrNull()?.channels?.toSet()
            }
            val channels = dispatcharrClient.listChannels(base, key)
                .let { list ->
                    if (allowedChannelIds != null) list.filter { it.id in allowedChannelIds } else list
                }
            channels
                .filter { !it.uuid.isNullOrBlank() }
                .sortedWith(compareBy(
                    { it.channelNumber ?: Double.MAX_VALUE },
                    { it.name.lowercase() },
                ))
                .map { ch ->
                    // Stable ID derived from Dispatcharr's server UUID so the
                    // favorites store key survives playlist refreshes. The
                    // default `UUID.randomUUID()` in M3UChannel re-rolled on
                    // every fetch and orphaned existing FavoriteChannel rows.
                    M3UChannel(
                        id = "disp:${ch.uuid!!}",
                        name = ch.name,
                        url = dispatcharrClient.streamUrl(base, ch.uuid!!),
                        groupTitle = ch.channelGroupId?.let { groups[it] }.orEmpty(),
                        tvgID = ch.tvgId.orEmpty(),
                        tvgName = ch.name,
                        tvgLogo = ch.logoId?.let { dispatcharrClient.logoUrl(base, it) }.orEmpty(),
                        channelNumber = ch.channelNumber?.formatChannelNumber(),
                        dispatcharrChannelId = ch.id,
                    )
                }
        }
        SourceType.XtreamCodes -> error("$sourceType is not implemented yet")
    }
}

private fun PlaylistEntity.resolvedSourceType(): SourceType =
    SourceType.entries.firstOrNull { it.name == sourceType } ?: SourceType.M3uUrl

/**
 * UI-facing summary of a Dispatcharr channel profile for the Edit Playlist
 * picker. [channelCount] lets the row show "Plex (65 channels)" so the user
 * can tell the profiles apart at a glance.
 */
data class ChannelProfileOption(
    val id: Int,
    val name: String,
    val channelCount: Int,
)

/**
 * Convert Dispatcharr `/api/epg/grid/` entries into the universal EPGProgramme
 * shape the rest of the app consumes. Entries without a tvg_id are dropped -
 * they cannot be matched back to a channel row. Entries with malformed times
 * are dropped too (Instant.parse will throw).
 *
 * Dispatcharr bulk grid intentionally omits `category` for perf; we propagate
 * empty string. Lazy category enrichment via /api/epg/programs/<id>/ lives in
 * a later phase tied to ProgramInfoView.
 */
private fun List<DispatcharrEpgEntry>.toProgrammes(): List<EPGProgramme> =
    mapNotNull { entry ->
        val channelId = entry.tvgId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val start = runCatching { Instant.parse(entry.startTime).toEpochMilli() }.getOrNull()
            ?: return@mapNotNull null
        val end = runCatching { Instant.parse(entry.endTime).toEpochMilli() }.getOrNull()
            ?: return@mapNotNull null
        val title = entry.title.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        EPGProgramme(
            channelId = channelId,
            title = title,
            description = entry.description,
            startMillis = start,
            endMillis = end,
            // Dispatcharr's bulk grid usually strips <category>; pick up
            // the array when newer builds emit it as a free upgrade, fall
            // back to per-program lazy fetch in ProgramInfoSheet otherwise.
            category = entry.categories?.filter { it.isNotBlank() }?.joinToString(",").orEmpty(),
            dispatcharrProgramId = entry.programIdInt,
        )
    }

/**
 * Format a Dispatcharr-API channel-number Double back to a display string,
 * trimming the trailing `.0` when the value is integer-valued. Matches the
 * iOS commit d1ac87a behaviour: prefer "11444" over "11444.0", but preserve
 * "2.1" / "1.10" verbatim.
 */
private fun Double.formatChannelNumber(): String {
    return if (this == kotlin.math.floor(this) && !this.isInfinite()) {
        this.toLong().toString()
    } else {
        this.toString()
    }
}
