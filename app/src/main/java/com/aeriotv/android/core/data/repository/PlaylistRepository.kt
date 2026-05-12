package com.aeriotv.android.core.data.repository

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import android.content.Context
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.DispatcharrEpgEntry
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
 *  - DispatcharrUserPass: TODO Phase 4b (JWT flow).
 *  - XtreamCodes: TODO Phase 4c (player_api.php enumeration -> get.php m3u_plus).
 */
@Singleton
class PlaylistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PlaylistDao,
    private val fetcher: PlaylistFetcher,
    private val dispatcharrClient: DispatcharrClient,
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
                dispatcharrClient.fetchCurrentUserApiKey(normalisedBase, jwt.access)
            }
            else -> request.apiKey
        }

        val channels = fetchChannelsFor(
            sourceType = sourceType,
            base = normalisedBase,
            userEpgUrl = request.epgUrl,
            apiKey = resolvedApiKey,
        )

        val entity = PlaylistEntity(
            id = existingId ?: UUID.randomUUID().toString(),
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
        val channels = fetchChannelsFor(
            sourceType = sourceType,
            base = effectiveBaseUrl(playlist),
            userEpgUrl = playlist.epgUrl,
            apiKey = playlist.apiKey,
        )
        dao.update(
            playlist.copy(
                channelCount = channels.size,
                lastRefreshedAt = System.currentTimeMillis(),
            ),
        )
        channels
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
                val key = playlist.apiKey?.takeIf { it.isNotBlank() }
                    ?: return@runCatching emptyList()
                dispatcharrClient.getEpgGrid(base, key)
                    .toProgrammes()
            }
            SourceType.XtreamCodes -> return@runCatching emptyList()
        }
        dao.update(playlist.copy(lastEpgRefreshedAt = System.currentTimeMillis()))
        programmes
    }

    suspend fun clear() = dao.clear()

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
        val channels = fetchChannelsFor(
            sourceType = entity.resolvedSourceType(),
            base = effectiveBaseUrl(entity),
            userEpgUrl = entity.epgUrl,
            apiKey = entity.apiKey,
        )
        dao.update(entity.copy(channelCount = channels.size, lastRefreshedAt = System.currentTimeMillis()))
        entity to channels
    }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> = runCatching {
        dao.deleteById(playlistId)
    }

    private fun deriveName(url: String): String =
        url.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Source" }

    private suspend fun fetchChannelsFor(
        sourceType: SourceType,
        base: String,
        userEpgUrl: String?,
        apiKey: String?,
    ): List<M3UChannel> = when (sourceType) {
        SourceType.M3uUrl -> {
            val bytes = fetcher.fetchBytes(base)
            M3UParser.parseBytes(bytes)
        }
        SourceType.DispatcharrApiKey -> {
            val key = apiKey?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Dispatcharr API key is required")
            val groups = dispatcharrClient.listGroups(base, key)
                .associate { it.id to it.name }
            val channels = dispatcharrClient.listChannels(base, key)
            channels
                .filter { !it.uuid.isNullOrBlank() }
                .sortedWith(compareBy(
                    { it.channelNumber ?: Double.MAX_VALUE },
                    { it.name.lowercase() },
                ))
                .map { ch ->
                    M3UChannel(
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
        SourceType.DispatcharrUserPass ->
            error("DispatcharrUserPass must be resolved to an api_key before fetch")
        SourceType.XtreamCodes -> error("$sourceType is not implemented yet")
    }
}

private fun PlaylistEntity.resolvedSourceType(): SourceType =
    SourceType.entries.firstOrNull { it.name == sourceType } ?: SourceType.M3uUrl

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
            category = "",
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
