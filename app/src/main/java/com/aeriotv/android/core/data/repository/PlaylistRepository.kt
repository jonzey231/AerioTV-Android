package com.aeriotv.android.core.data.repository

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.dao.ChannelSnapshotDao
import com.aeriotv.android.core.data.db.dao.EpgProgrammeDao
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.entity.ChannelSnapshotEntity
import com.aeriotv.android.core.data.db.entity.EpgProgrammeEntity
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

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
    private val epgProgrammeDao: EpgProgrammeDao,
    private val channelSnapshotDao: ChannelSnapshotDao,
    private val activeCredentials: com.aeriotv.android.core.network.ActivePlaylistCredentials,
) {

    /**
     * Audit task #54: push the active playlist's apiKey + URL prefix set into
     * the synchronous [com.aeriotv.android.core.network.ActivePlaylistCredentials]
     * cache so Coil's OkHttp interceptor can attach `X-API-Key` to image
     * fetches against the same source. Called from every code path that
     * mutates or selects the active playlist; safe to call with null on a
     * source that doesn't need auth (clears the cache).
     */
    private fun publishActiveCredentials(playlist: PlaylistEntity?) {
        if (playlist == null) {
            activeCredentials.clear()
            return
        }
        val prefixes = listOfNotNull(
            playlist.urlString.takeIf { it.isNotBlank() },
            playlist.lanUrlString?.takeIf { it.isNotBlank() },
        )
        activeCredentials.set(prefixes, playlist.apiKey)
    }

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
        /** Per-playlist On Demand opt-in (iOS ServerConnection.vodEnabled).
         *  Default true. UI exposes this for Dispatcharr / Xtream sources; M3U
         *  doesn't carry VOD so the field is ignored downstream for it. */
        val vodEnabled: Boolean = true,
    )

    suspend fun activePlaylist(): PlaylistEntity? {
        val pl = dao.firstActive()
        // Keep the sync credential cache in lockstep with the DB; covers the
        // cold-launch path where AerioTVApplication kicks off a read on
        // startup. Read-only callers benefit too -- they're cheap.
        publishActiveCredentials(pl)
        return pl
    }

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
            username = request.username,
            password = request.password,
        )

        // Capture the connected user's Dispatcharr account level (10 = admin,
        // 1 = standard, 0 = streamer). Only admins can POST server recordings,
        // so this gates the Record affordances; best-effort, a failed read
        // keeps the recording-capable default (10). Mirrors iOS d8aa76b.
        val dispatcharrUserLevel: Int =
            if (sourceType == SourceType.DispatcharrApiKey ||
                sourceType == SourceType.DispatcharrUserPass
            ) {
                resolvedApiKey?.takeIf { it.isNotBlank() }
                    ?.let { dispatcharrClient.fetchUserLevel(normalisedBase, it) }
                    ?: 10
            } else {
                10
            }

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
            dispatcharrUserLevel = dispatcharrUserLevel,
            vodEnabled = request.vodEnabled,
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
        // Cache the first-load channels so the very next launch is instant
        // (Phase 130 channel snapshot cache).
        try {
            saveChannelsToCache(playlistId, channels)
        } catch (t: Throwable) {
            android.util.Log.w("PlaylistRepository", "saveChannelsToCache failed (loadAndPersist)", t)
        }
        publishActiveCredentials(entity)
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
            else -> fetchChannelsFor(
                sourceType, base, playlist.epgUrl, playlist.apiKey, playlist.dispatcharrProfileId,
                playlist.username, playlist.password,
            )
        }
        val refreshed = playlist.copy(
            channelCount = channels.size,
            lastRefreshedAt = System.currentTimeMillis(),
        )
        dao.update(refreshed)
        // Persist the freshly-fetched channels so the next cold launch repaints
        // the rail INSTANTLY from disk (Phase 130 channel snapshot cache).
        // Best-effort: a cache-write failure must NOT fail the refresh.
        try {
            saveChannelsToCache(playlist.id, channels)
        } catch (t: Throwable) {
            android.util.Log.w("PlaylistRepository", "saveChannelsToCache failed (refresh)", t)
        }
        publishActiveCredentials(refreshed)
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

    /**
     * In-flight [loadEpg] deferreds keyed by `playlist.id`. Two callers that
     * start an EPG fetch for the same source before the first one returns
     * share the same [CompletableDeferred] and the same network roundtrip,
     * instead of each independently parsing 60K-programme XMLTV or hitting
     * Dispatcharr's `/api/epg/grid/` twice. Mirrors iOS GuideStore's
     * `inFlightLoadTask` / `inFlightXMLTVTask` coalescing
     * (EPGGuideView.swift lines 1300-1340).
     *
     * Entries are removed in the `finally` block of the winning caller so
     * SEQUENTIAL re-fetches (cache went stale between two visits) still
     * round-trip; only OVERLAPPING calls coalesce.
     */
    private val inFlightLoads = ConcurrentHashMap<String, CompletableDeferred<Result<List<EPGProgramme>>>>()

    suspend fun loadEpg(playlist: PlaylistEntity): Result<List<EPGProgramme>> {
        val key = playlist.id
        val mine = CompletableDeferred<Result<List<EPGProgramme>>>()
        val winner = inFlightLoads.putIfAbsent(key, mine)
        if (winner != null) {
            // Another caller already started the fetch; await their result.
            return winner.await()
        }
        return try {
            val result = loadEpgInternal(playlist)
            mine.complete(result)
            result
        } catch (t: Throwable) {
            val r: Result<List<EPGProgramme>> = Result.failure(t)
            mine.complete(r)
            r
        } finally {
            // Drop the entry so the next (sequential) call hits the network
            // again instead of awaiting a long-stale completed Deferred.
            inFlightLoads.remove(key, mine)
        }
    }

    private suspend fun loadEpgInternal(playlist: PlaylistEntity): Result<List<EPGProgramme>> =
        // Parse + grid-mapping run on Default, NOT the caller's (Main) dispatcher.
        // A large provider EPG is hundreds of thousands of programmes; parsing
        // that XMLTV / mapping the Dispatcharr grid on Main froze the UI for
        // minutes before the guide could paint.
        withContext(Dispatchers.Default) { runCatching {
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
                val grid = dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getEpgGrid(base, key).toProgrammes()
                }
                // iOS parity (EPGGuideView.swift Dispatcharr branch + the user-
                // configurable custom-XMLTV URL on the source): when the user
                // sets a separate XMLTV URL on a Dispatcharr playlist (Edit
                // Playlist > EPG URL), fetch that XMLTV too and layer it on
                // top of the grid. The Dispatcharr grid usually keys by
                // channel number and is sparse on category tags; a richer
                // provider's XMLTV fills in categories, descriptions, and
                // covers channels the grid misses. Same-airing dedup at
                // groupByChannel time (PlaylistViewModel.dedupSameAiring)
                // collapses any pair that overlaps > 80% or shares a title
                // within 60s, so the same programme never appears twice.
                val customXmltv = playlist.epgUrl?.takeIf { it.isNotBlank() }
                if (customXmltv != null) {
                    val xmltv = runCatching {
                        XMLTVParser.parseBytes(fetcher.fetchBytes(customXmltv))
                    }.getOrElse {
                        // Don't fail the whole EPG load just because the user's
                        // custom XMLTV URL is unreachable; surface a warning
                        // and keep the grid the user already paid the auth-
                        // retry roundtrip for.
                        emptyList()
                    }
                    if (xmltv.isNotEmpty()) grid + xmltv else grid
                } else {
                    grid
                }
            }
            SourceType.XtreamCodes -> {
                // Xtream EPG is a standard XMLTV feed at xmltv.php. Reuse the
                // XMLTV parser; programmes map to channels by tvg-id like M3U.
                //
                // Audit task #19: if the user has set a custom XMLTV URL on
                // the playlist (Edit Playlist -> EPG URL field), prefer
                // that. iOS parity: a separate XMLTV provider often supplies
                // richer category/genre tags than the Xtream server's own
                // xmltv.php, which the channel tinting + category list
                // depend on. Fall back to xmltv.php only when no override
                // is set.
                val override = playlist.epgUrl?.takeIf { it.isNotBlank() }
                val xmltvUrl = override ?: run {
                    val user = playlist.username?.takeIf { it.isNotBlank() }
                        ?: return@runCatching emptyList()
                    val b = base.trimEnd('/')
                    "$b/xmltv.php?username=${xtreamEncode(user)}" +
                        "&password=${xtreamEncode(playlist.password.orEmpty())}"
                }
                XMLTVParser.parseBytes(fetcher.fetchBytes(xmltvUrl))
            }
        }
        dao.update(playlist.copy(lastEpgRefreshedAt = System.currentTimeMillis()))
        programmes
    } }

    /**
     * Disk-cached EPG (iOS GuideStore parity). [loadCachedEpg] returns the last
     * persisted guide for a source so the UI can paint now-playing + the guide
     * instantly on relaunch; [newestEpgFetch] drives the freshness check; and
     * [saveEpgToCache] replaces the source's rows after a network fetch, pruning
     * programmes that have already ended.
     */
    suspend fun loadCachedEpg(playlistId: String): List<EPGProgramme> =
        withContext(Dispatchers.Default) {
            epgProgrammeDao.forPlaylist(playlistId).map { it.toProgramme() }
        }

    /**
     * Time-windowed cached-EPG read (iOS GuideStore parity --
     * EPGGuideView.swift `loadFromCache` predicate). Returns only programmes
     * whose airing overlaps [[fromMillis]..[toMillis]], so cold-launch paint
     * loads ~5-15% of the cache (a 24h window over a 7-day grid) instead of
     * every row. The user's epgWindowHours preference dictates [toMillis] in
     * the calling ViewModel; [fromMillis] is typically now-1h so the
     * "currently airing" programme is always inside the result regardless of
     * how long it's been running.
     */
    suspend fun loadCachedEpg(
        playlistId: String,
        fromMillis: Long,
        toMillis: Long,
    ): List<EPGProgramme> =
        withContext(Dispatchers.Default) {
            epgProgrammeDao
                .forPlaylistInWindow(playlistId, fromMillis, toMillis)
                .map { it.toProgramme() }
        }

    suspend fun newestEpgFetch(playlistId: String): Long? =
        epgProgrammeDao.newestFetchedAt(playlistId)

    suspend fun saveEpgToCache(playlistId: String, programmes: List<EPGProgramme>) {
        val now = System.currentTimeMillis()
        val entities = withContext(Dispatchers.Default) {
            programmes.map { it.toCacheEntity(playlistId, now) }
        }
        epgProgrammeDao.replaceForPlaylist(playlistId, entities)
        // Hygiene: drop programmes that ended over an hour ago across all sources
        // (mirrors GuideStore.saveToCache's stale-row delete).
        epgProgrammeDao.deleteEndedBefore(now - 60L * 60L * 1000L)
    }

    /**
     * Disk-cached channel snapshot (iOS ChannelStore parity, sister to the EPG
     * cache above). [loadCachedChannels] returns the last persisted channel
     * list for a source so a cold launch can paint the Live TV rail INSTANTLY
     * while a background refresh runs; [newestChannelFetch] drives the
     * freshness check; and [saveChannelsToCache] replaces the source's rows
     * after a successful network fetch (the [List.mapIndexed] preserves the
     * exact order [fetchChannelsFor] produced, which is what the rail / list
     * sort by).
     */
    suspend fun loadCachedChannels(playlistId: String): List<M3UChannel> =
        channelSnapshotDao.forPlaylist(playlistId).map { it.toChannel() }

    suspend fun newestChannelFetch(playlistId: String): Long? =
        channelSnapshotDao.newestFetchedAt(playlistId)

    suspend fun saveChannelsToCache(playlistId: String, channels: List<M3UChannel>) {
        val now = System.currentTimeMillis()
        channelSnapshotDao.replaceForPlaylist(
            playlistId,
            channels.mapIndexed { idx, ch -> ch.toCacheEntity(playlistId, idx, now) },
        )
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

    /**
     * The active playlist's id, or null when none is active. Emits on
     * switch / add / delete (the playlists table changes), so observers can
     * react to the active source changing -- e.g. On Demand clears the previous
     * source's movies/series so stale, unplayable VOD never lingers after a
     * playlist is deleted or switched (iOS Issue #25).
     */
    fun observeActiveId(): kotlinx.coroutines.flow.Flow<String?> =
        dao.observeActive().map { it.firstOrNull()?.id }.distinctUntilChanged()
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
            else -> fetchChannelsFor(
                sourceType, base, entity.epgUrl, entity.apiKey, entity.dispatcharrProfileId,
                entity.username, entity.password,
            )
        }
        val updated = entity.copy(channelCount = channels.size, lastRefreshedAt = System.currentTimeMillis())
        dao.update(updated)
        // Cache the post-switch channel list (Phase 130 channel snapshot cache).
        try {
            saveChannelsToCache(updated.id, channels)
        } catch (t: Throwable) {
            android.util.Log.w("PlaylistRepository", "saveChannelsToCache failed (switchActive)", t)
        }
        publishActiveCredentials(updated)
        updated to channels
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
        username: String? = null,
        password: String? = null,
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
        SourceType.XtreamCodes -> {
            // Xtream Codes serves a standard M3U at get.php?type=m3u_plus with the
            // real proxy stream URLs, group-title, tvg-id, and logos already in it
            // (iOS StreamingAPIs notes the M3U carries the playable URLs). Reuse
            // the M3U parser for live channels rather than the player_api JSON;
            // VOD + series use the JSON client. EPG comes from xmltv.php (loadEpg).
            val user = username?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Xtream Codes username is required")
            val b = base.trimEnd('/')
            val m3uUrl = "$b/get.php?username=${xtreamEncode(user)}" +
                "&password=${xtreamEncode(password.orEmpty())}&type=m3u_plus"
            M3UParser.parseBytes(fetcher.fetchBytes(m3uUrl))
        }
    }
}

/** URL-encode an Xtream credential for use in a query string. */
private fun xtreamEncode(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8")

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

/** EPG disk-cache row <-> domain model mapping. */
private fun EpgProgrammeEntity.toProgramme(): EPGProgramme = EPGProgramme(
    channelId = channelId,
    title = title,
    description = description,
    startMillis = startMillis,
    endMillis = endMillis,
    category = category,
    dispatcharrProgramId = dispatcharrProgramId,
)

private fun EPGProgramme.toCacheEntity(playlistId: String, fetchedAt: Long): EpgProgrammeEntity =
    EpgProgrammeEntity(
        playlistId = playlistId,
        channelId = channelId,
        title = title,
        description = description,
        startMillis = startMillis,
        endMillis = endMillis,
        category = category,
        dispatcharrProgramId = dispatcharrProgramId,
        fetchedAt = fetchedAt,
    )

/** Channel snapshot cache row <-> M3UChannel mapping. We deliberately drop
 *  `rawAttributes` (write-only at parse time, never read after) so the cache
 *  stays a single tabular Room row instead of needing a TypeConverter. */
private fun ChannelSnapshotEntity.toChannel(): M3UChannel = M3UChannel(
    id = channelId,
    name = name,
    url = url,
    groupTitle = groupTitle,
    tvgID = tvgID,
    tvgName = tvgName,
    tvgLogo = tvgLogo,
    channelNumber = channelNumber,
    dispatcharrChannelId = dispatcharrChannelId,
)

private fun M3UChannel.toCacheEntity(
    playlistId: String,
    position: Int,
    fetchedAt: Long,
): ChannelSnapshotEntity = ChannelSnapshotEntity(
    playlistId = playlistId,
    channelId = id,
    position = position,
    name = name,
    url = url,
    groupTitle = groupTitle,
    tvgID = tvgID,
    tvgName = tvgName,
    tvgLogo = tvgLogo,
    channelNumber = channelNumber,
    dispatcharrChannelId = dispatcharrChannelId,
    fetchedAt = fetchedAt,
)

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
