package com.aeriotv.android.core.data.repository

import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.SourceType
import androidx.room.withTransaction
import com.aeriotv.android.core.data.db.dao.ChannelSnapshotDao
import com.aeriotv.android.core.data.db.dao.EpgProgrammeDao
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.entity.ChannelSnapshotEntity
import com.aeriotv.android.core.data.db.entity.EpgProgrammeEntity
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.db.entity.dispatcharrAccountProfileIdList
import android.content.Context
import android.util.Log
import com.aeriotv.android.core.network.DispatcharrAuthBroker
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.LanReachability
import com.aeriotv.android.core.network.DispatcharrChannel
import com.aeriotv.android.core.network.DispatcharrEpgData
import com.aeriotv.android.core.network.DispatcharrEpgEntry
import com.aeriotv.android.core.network.DispatcharrEpgSource
import com.aeriotv.android.core.network.DispatcharrTokenStore
import com.aeriotv.android.core.network.PlaylistFetcher
import com.aeriotv.android.core.parser.M3UParser
import com.aeriotv.android.core.parser.XMLTVParser
import com.aeriotv.android.core.preferences.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
/** GH #31: channel-snapshot insert batch size. Small enough that one chunk's
 *  entities are trivially cheap against the heap, large enough that per-chunk
 *  SQLite bind overhead stays negligible across a ~100k-row XC catalog. */
private const val CHANNEL_CACHE_CHUNK = 2_000

/** Ceiling on one EPG load (network + parse). Wide enough for a multi-hundred
 *  MB provider XMLTV on a slow link; exists so a wedged load releases the
 *  in-flight latch in [PlaylistRepository.loadEpg] instead of blocking every
 *  future EPG refresh until the process dies. */
private const val EPG_LOAD_TIMEOUT_MS = 5L * 60L * 1000L

/** Upstream Dispatcharr XMLTV feeds to layer for catch-up depth (task #210).
 *  Was 8. A Direct Connect server can list many sources, and each one is a
 *  FULL XMLTV download; the value of the 4th feed is negligible next to the
 *  cost of fetching it on every EPG load. */
private const val MAX_UPSTREAM_EPG_SOURCES = 3

/** Ceiling on ONE upstream feed. A single slow or enormous XMLTV must not be
 *  able to stall the EPG load behind it. */
// Raised 90s -> 5min (2026-09-01): the parse now runs on the background-
// priority EpgWork pool, so a long parse no longer competes with playback;
// at 90s the 224MB national feed kept 6,575 programmes and dropped the rest.
private const val UPSTREAM_EPG_PER_SOURCE_MS = 5L * 60L * 1000L

/** Ceiling on the whole upstream-layering phase. This is bonus history, not
 *  the user's guide: past this the grid ships as-is. */
private const val UPSTREAM_EPG_TOTAL_BUDGET_MS = 12L * 60L * 1000L

/** Orphaned download temp files older than this are swept at startup. The
 *  download path deletes its temp in a `finally`, but a process death mid
 *  download (force-stop while "syncing", low-memory kill) skips that entirely,
 *  and each orphan is the full size of whatever was being fetched. The Discord
 *  report of a 4GB app cache was exactly this, repeated. */
private const val ORPHAN_TEMP_MAX_AGE_MS = 60L * 60L * 1000L

/** E-6: minimum gap between EPG retention sweeps for one playlist. Upstream
 *  layering saves once per source; the cutoff is days out, so re-pruning
 *  seconds later only costs a full-table DELETE scan. */
private const val RETENTION_SWEEP_COOLDOWN_MS = 5L * 60L * 1000L

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
    // GH #31: injected so the channel-snapshot persist can delete-then-insert in
    // CHUNKS inside one transaction (see saveChannelsToCache) instead of binding
    // the whole ~100k-row catalog at once. Same pattern DriveSyncManager uses.
    private val database: com.aeriotv.android.core.data.db.AerioDatabase,
    private val activeCredentials: com.aeriotv.android.core.network.ActivePlaylistCredentials,
    private val lanReachability: LanReachability,
    private val xtreamApi: com.aeriotv.android.core.network.XtreamCodesApi,
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
     * Returns [PlaylistEntity.lanUrlString] when the server actually answers
     * at that address; otherwise [PlaylistEntity.urlString]. Replaced the old
     * home-SSID match (fine-location permission + per-device saved networks
     * that never synced, so fresh installs silently routed WAN) with a cached
     * reachability probe; see [LanReachability] for the trigger points.
     */
    suspend fun effectiveBaseUrl(playlist: PlaylistEntity): String {
        // Task #49: every Dispatcharr call path resolves its base through
        // here, so this is the one choke point that guarantees the client's
        // per-host auth-mode registry is primed from the persisted row
        // before the request goes out (no-op for the legacy empty mode).
        dispatcharrClient.seedAuthMode(playlist)
        val lan = playlist.lanUrlString?.takeIf { it.isNotBlank() } ?: return playlist.urlString
        return if (lanReachability.isReachable(lan)) lan else playlist.urlString
    }

    /**
     * The verdict-flip signal from [LanReachability] (LAN URL key that just
     * flipped LAN<->WAN). The player collects this to re-tune a live Dispatcharr
     * stream onto the now-reachable base. iOS analog: TVLANProbe -> PlayerSession
     * .retuneCurrentToActiveURL() (commit e6ca1d207).
     */
    val lanVerdictFlips: kotlinx.coroutines.flow.SharedFlow<String> =
        lanReachability.verdictFlips

    /** Force a fresh LAN/WAN probe of the active playlist's LAN URL, returning
     *  the resolved effective base afterward. Used by the player's terminal-
     *  error failover (iOS failoverRetryCurrent: reprobeAndWait then re-tune). */
    suspend fun reprobeActiveBase(): String? {
        val pl = activePlaylist() ?: return null
        pl.lanUrlString?.takeIf { it.isNotBlank() }?.let { lanReachability.refresh(it) }
        return effectiveBaseUrl(pl)
    }

    /**
     * Rebuild the canonical /proxy/ts/stream/<uuid> URL for a Dispatcharr
     * channel from the active playlist's CURRENT effective base (LAN vs WAN),
     * rather than trusting a cached channel.url baked at last fetch. Returns
     * null when there is no active playlist, the source is not Dispatcharr, or
     * the playlist has no LAN URL (nothing to flip). Mirrors the streamUrlFor
     * idiom in AutoBrowseTree.kt and iOS ChannelStore.dispatcharrStreamURLs.
     */
    suspend fun rebuildLiveStreamUrl(channelUuid: String): String? {
        val pl = activePlaylist() ?: return null
        val sourceType = pl.resolvedSourceType()
        val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
            sourceType == SourceType.DispatcharrUserPass
        if (!isDispatcharr) return null
        val base = effectiveBaseUrl(pl)
        return dispatcharrClient.streamUrl(base, channelUuid)
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
        /** Days of already-aired EPG kept for catch-up browsing (task #135). */
        val epgRetentionDays: Int = 7,
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
                // A playlist ORIGINALLY added with username/password can be
                // switched to API Key mode in Edit Playlist; its stored
                // sourceType stays DispatcharrUserPass while the credential it
                // now carries is a key. Demanding a username here made that
                // save throw "Username is required", so the entered key was
                // silently discarded and the STALE key kept 401ing every VOD
                // call. Reported 2026-08-08 (Logan, Z Fold + Streamer): "I
                // keep entering a new API Key and it isn't taking it", while
                // channels and the test button kept working from cache and
                // the token path. Honour the key when that is what was
                // supplied; only demand credentials when there is no key.
                val suppliedKey = request.apiKey?.takeIf { it.isNotBlank() }
                val u = request.username?.takeIf { it.isNotBlank() }
                val p = request.password?.takeIf { it.isNotBlank() }
                if (suppliedKey != null && (u == null || p == null)) {
                    Log.i(
                        "PlaylistRepo",
                        "UserPass playlist saved in API Key mode; using the supplied key",
                    )
                    suppliedKey
                } else {
                    val user = u ?: throw IllegalArgumentException("Username is required")
                    val pass = p ?: throw IllegalArgumentException("Password is required")
                    val jwt = dispatcharrClient.login(normalisedBase, user, pass)
                    // Stash the JWT pair so the warmup coordinator picks up the
                    // refresh token on the next app foreground and the
                    // bearer-mode calls don't re-login from scratch every session.
                    dispatcharrTokenStore.store(playlistId, jwt.access, jwt.refresh)
                    dispatcharrClient.fetchCurrentUserApiKey(normalisedBase, jwt.access)
                }
            }
            else -> request.apiKey
        }

        // Capture the connected account's assigned Channel Profile id(s) for the
        // FAIL-CLOSED child-safety filter (iOS 3eb4ae3d8). Best-effort: a failed
        // whoami returns null -> emptyList (no account filter) on first add; the
        // value self-heals on every subsequent refresh.
        val accountProfileIds: List<Int> =
            if (sourceType == SourceType.DispatcharrApiKey ||
                sourceType == SourceType.DispatcharrUserPass
            ) {
                resolvedApiKey?.takeIf { it.isNotBlank() }
                    ?.let { dispatcharrClient.fetchCurrentUserProfileIds(normalisedBase, it) }
                    ?: emptyList()
            } else {
                emptyList()
            }

        // GH #83: an EDIT shows its new values immediately. The full entity
        // used to land only after the channel fetch, so the Playlists page
        // kept showing the old URL (or credentials) for the whole sync and
        // users re-opened Edit thinking the save was lost. Write the editable
        // fields to the existing row now; a failed fetch restores the row.
        val previous = existingId?.let { dao.byId(it) }
        if (previous != null) {
            dao.update(
                previous.copy(
                    name = request.name?.takeIf { it.isNotBlank() } ?: previous.name,
                    urlString = normalisedBase,
                    lanUrlString = request.lanUrl?.trimEnd('/')?.takeIf { it.isNotBlank() },
                    epgUrl = request.epgUrl?.takeIf { it.isNotBlank() },
                    apiKey = resolvedApiKey?.takeIf { it.isNotBlank() } ?: previous.apiKey,
                    username = request.username?.takeIf { it.isNotBlank() },
                    password = request.password?.takeIf { it.isNotBlank() },
                    dispatcharrProfileId = request.dispatcharrProfileId,
                    vodEnabled = request.vodEnabled,
                    epgRetentionDays = request.epgRetentionDays.coerceIn(1, 30),
                ),
            )
        }

        val channels = try {
            fetchChannelsFor(
                sourceType = sourceType,
                base = normalisedBase,
                userEpgUrl = request.epgUrl,
                apiKey = resolvedApiKey,
                profileId = request.dispatcharrProfileId,
                accountProfileIds = accountProfileIds,
                username = request.username,
                password = request.password,
            )
        } catch (t: Throwable) {
            if (previous != null) runCatching { dao.update(previous) }
            throw t
        }

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
            dispatcharrAccountProfileIds = accountProfileIds.joinToString(","),
            vodEnabled = request.vodEnabled,
            epgRetentionDays = request.epgRetentionDays.coerceIn(1, 30),
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
        // The user just edited connection details: probe the LAN URL now so
        // the very next request routes correctly instead of waiting for a
        // network change.
        entity.lanUrlString?.takeIf { it.isNotBlank() }?.let { lanReachability.refresh(it) }
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
        // Self-heal the account's assigned Channel Profile id(s) on every load
        // (the server-side assignment can change). A failed whoami (null) falls
        // back to the persisted snapshot so a network blip never widens a kids
        // account to all channels. iOS 1cc51fc59.
        val liveAccountIds: List<Int>? =
            if (sourceType == SourceType.DispatcharrApiKey ||
                sourceType == SourceType.DispatcharrUserPass
            ) {
                playlist.apiKey?.takeIf { it.isNotBlank() }
                    ?.let { dispatcharrClient.fetchCurrentUserProfileIds(base, it) }
            } else {
                null
            }
        val effectiveAccountIds = liveAccountIds ?: playlist.dispatcharrAccountProfileIdList()
        // Audit gap: re-capture the account level on every refresh so a
        // server-side demote/promote is reflected without a full Edit-Playlist
        // Save. Best-effort: a null read keeps the persisted level (never
        // clobbers a good value with the recording-capable default). Mirrors
        // the loadAndPersist level capture and the liveAccountIds self-heal
        // just above. iOS d8aa76b re-reads dispatcharrUserLevel on reconnect.
        val liveUserLevel: Int? =
            if (sourceType == SourceType.DispatcharrApiKey ||
                sourceType == SourceType.DispatcharrUserPass
            ) {
                playlist.apiKey?.takeIf { it.isNotBlank() }
                    ?.let { dispatcharrClient.fetchUserLevel(base, it) }
            } else {
                null
            }
        val channels = when (sourceType) {
            SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass ->
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    fetchChannelsFor(
                        sourceType, base, playlist.epgUrl, key,
                        playlist.dispatcharrProfileId, effectiveAccountIds,
                    )
                }
            else -> fetchChannelsFor(
                sourceType, base, playlist.epgUrl, playlist.apiKey,
                playlist.dispatcharrProfileId, emptyList(),
                playlist.username, playlist.password,
            )
        }
        val refreshed = playlist.copy(
            channelCount = channels.size,
            lastRefreshedAt = System.currentTimeMillis(),
            // Persist the self-healed snapshot ONLY when the live whoami
            // succeeded (liveAccountIds != null); a transient failure must not
            // clobber a good fail-closed snapshot via the fallback value.
            dispatcharrAccountProfileIds =
                if (liveAccountIds != null) liveAccountIds.joinToString(",")
                else playlist.dispatcharrAccountProfileIds,
            dispatcharrUserLevel =
                liveUserLevel ?: playlist.dispatcharrUserLevel,
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

    /** Owns the background upstream-layering jobs. Supervisor so one playlist's
     *  failed layering never cancels another's; Default because the work is a
     *  CPU-bound parse. Repository is a @Singleton, so this scope lives for the
     *  process -- layering outlives the screen that triggered it, which is the
     *  point: the user keeps browsing on the grid while history accumulates. */
    /** Two threads at THREAD_PRIORITY_BACKGROUND for every EPG/playlist
     *  download + gunzip + parse. simpleperf on the Google TV Streamer
     *  (2026-08-31) showed this work at normal priority costing ~34% of the
     *  app's CPU while four multiview decoders ran; the MediaCodec loops spent
     *  22s of a 20s window combined waiting for a core. Thread priority only
     *  bites under contention: idle, this parses exactly as fast as
     *  Dispatchers.Default did; under playback, decoders win every time.
     *  NOT Dispatchers.IO for the hops either: IO shares Default's
     *  normal-priority worker pool. */
    private val layeringDispatcher = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "EpgWork").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()

    private val layeringScope = CoroutineScope(SupervisorJob() + layeringDispatcher)

    /** One layering job per playlist. A refresh while one is running reuses it
     *  rather than downloading the same feeds twice in parallel. */
    private val layeringJobs = ConcurrentHashMap<String, Job>()

    private val _upstreamEpgLayered = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Emits a playlistId after that playlist's upstream catch-up history has
     *  been merged into the EPG cache. Collectors re-read the cache (the
     *  existing mergeEpgHistory path) rather than receiving rows directly, so
     *  there is exactly one merge/dedup implementation. */
    val upstreamEpgLayered = _upstreamEpgLayered.asSharedFlow()

    /**
     * Fetch + parse the upstream XMLTV sources OFF the EPG critical path and
     * merge the result into the cache. Same budget model as 0.4.5, with the
     * enforcement fixed: the wall-clock deadline is checked INSIDE the parse
     * loop (XMLTVParser.shouldAbort), because a multi-million-programme feed
     * parses for minutes without ever reaching a suspension point where
     * withTimeout could fire. A source that blows its ceiling keeps what it
     * parsed (partial history is still history) and yields to the next.
     */
    private fun layerUpstreamInBackground(
        playlistId: String,
        base: String,
        customXmltvUrl: String?,
        knownChannelKeys: Set<String>?,
    ) {
        layeringJobs.compute(playlistId) { _, existing ->
            if (existing?.isActive == true) return@compute existing
            layeringScope.launch {
                // GH #53: source discovery moved in here with the fetching. It
                // needs /api/epg/epgdata/ as well as /api/epg/sources/ now (to
                // learn which feed each tvg-id came from), and neither belongs
                // in front of the guide paint.
                val layers = runCatching {
                    dispatcharrAuth.withApiKeyRetry(playlistId) { key ->
                        dispatcharrEpgLayers(
                            epgData = dispatcharrClient.listEpgData(base, key),
                            sources = dispatcharrClient.listEpgSources(base, key),
                            knownChannelKeys = knownChannelKeys,
                            customXmltvUrl = customXmltvUrl,
                        )
                    }
                }.getOrElse {
                    if (it is CancellationException) throw it
                    Log.w("PlaylistRepo", "upstream layering: source discovery failed: $it")
                    emptyList()
                }.take(MAX_UPSTREAM_EPG_SOURCES)
                if (layers.isEmpty()) return@launch
                val phaseDeadline = System.currentTimeMillis() + UPSTREAM_EPG_TOTAL_BUDGET_MS
                var collected = 0
                var downloadMs = 0L
                for (layer in layers) {
                    // Phase budget gates STARTING another source only, and it
                    // must not count download time either: one 224MB feed at
                    // ~3MB/s is 75s of the 3-minute phase before a byte is
                    // parsed. Extend the phase by each source's download time
                    // so the cap still bounds parse work, not the user's link.
                    if (System.currentTimeMillis() >= phaseDeadline + downloadMs) {
                        Log.w("PlaylistRepo", "upstream layering: phase budget spent; stopping")
                        break
                    }
                    // The per-source budget clocks the PARSE only, from the
                    // moment the download lands. It used to start before the
                    // fetch, so a 224MB national feed spent all 90s downloading
                    // and the parser aborted at 0 programmes (Streamer log
                    // 2026-09-01: "parse aborted by budget; keeping 0") -- the
                    // guide silently lost every channel that feed carried. The
                    // download has its own stall floor in PlaylistFetcher.
                    var sourceDeadline = Long.MAX_VALUE
                    val truncated = java.util.concurrent.atomic.AtomicBoolean(false)
                    // Conditional GET: an upstream feed the server says is
                    // unchanged since the last complete parse is neither
                    // downloaded nor parsed (validators are stored only after
                    // an untruncated parse, so a budget-cut feed retries).
                    val stored = runCatching { appPreferences.feedValidators(layer.url).first() }.getOrNull()
                    val sent = stored?.let { (etag, lastModified) ->
                        if (etag == null && lastModified == null) null
                        else com.aeriotv.android.core.network.FeedValidators(etag, lastModified)
                    }
                    var fresh: com.aeriotv.android.core.network.FeedValidators? = null
                    val xmltv = runCatching {
                        // GH #53: parse against THIS feed's own guide keys, not
                        // the playlist's whole key set. tvg-id values are
                        // broadcaster strings and collide freely across
                        // unrelated feeds; parsing globally let one provider's
                        // schedule land on another provider's channel.
                        val fetchStartMs = System.currentTimeMillis()
                        val result = fetchViaTempFileIfChanged(layer.url, ".xmltv", sent) { file ->
                            downloadMs += System.currentTimeMillis() - fetchStartMs
                            sourceDeadline = minOf(
                                System.currentTimeMillis() + UPSTREAM_EPG_PER_SOURCE_MS,
                                phaseDeadline + downloadMs,
                            )
                            XMLTVParser.parseFile(
                                file,
                                layer.channelKeys,
                                shouldAbort = { System.currentTimeMillis() >= sourceDeadline },
                                truncated = truncated,
                            )
                        }
                        if (result == null) {
                            Log.i("PlaylistRepo", "upstream source unchanged (304); parse skipped")
                            emptyList()
                        } else {
                            fresh = result.second
                            result.first
                        }
                    }.getOrElse {
                        if (it is CancellationException) throw it
                        Log.w("PlaylistRepo", "upstream EPG source skipped: ${it.javaClass.simpleName}")
                        emptyList()
                    }
                    val complete = fresh?.takeIf { !truncated.get() && (it.etag != null || it.lastModified != null) }
                    if (complete != null) {
                        runCatching { appPreferences.setFeedValidators(layer.url, complete.etag, complete.lastModified) }
                    }
                    if (xmltv.isNotEmpty()) {
                        // Merge per-source so a kill mid-phase loses at most
                        // one feed, not all of them. saveEpgToCache merges and
                        // prunes to the retention window.
                        // A parse that ran out of budget returns a FRAGMENT of
                        // the feed. Insert what it got, but do not let it delete
                        // the covered window: the channels it mentions are not
                        // the channels it fully carries, and the rows it never
                        // reached are usually somebody else's good data.
                        if (truncated.get()) {
                            Log.w(
                                "PlaylistRepo",
                                "upstream source truncated by budget; merging insert-only " +
                                    "(${xmltv.size} programmes)",
                            )
                        }
                        // Insert-only, ALWAYS (2026-09-01): the Dispatcharr grid is
                        // the authority for now/future. An authoritative layer
                        // merge deletes each mentioned channel's covered window
                        // and re-inserts only what the feed carries, so a feed
                        // that merely mentions a channel (or lands on it through
                        // a key collision) wiped the grid's rows and left holes
                        // (ESPN HD 4:00-6:00 on the Streamer). Layering exists to
                        // ADD history and detail; dedupSameAiring collapses any
                        // same-airing overlap downstream.
                        runCatching {
                            saveEpgToCache(playlistId, xmltv, authoritative = false)
                        }.onFailure { Log.w("PlaylistRepo", "layered cache merge failed", it) }
                        collected += xmltv.size
                    }
                }
                if (collected > 0) {
                    Log.i(
                        "PlaylistRepo",
                        "upstream layering: merged $collected programmes from " +
                            "${layers.size} source-scoped feed(s) in background",
                    )
                    _upstreamEpgLayered.tryEmit(playlistId)
                }
            }
        }
    }

    suspend fun loadEpg(
        playlist: PlaylistEntity,
        knownChannelKeys: Set<String>? = null,
    ): Result<List<EPGProgramme>> {
        val key = playlist.id
        val mine = CompletableDeferred<Result<List<EPGProgramme>>>()
        val winner = inFlightLoads.putIfAbsent(key, mine)
        if (winner != null) {
            // Another caller already started the fetch; await their result.
            // Log the join: the 2026-08 "no EPG" report showed a load that
            // never returned wedging every later call on this latch. If that
            // recurs, this line is the tell.
            Log.i("PlaylistRepo", "loadEpg: joining in-flight load for ${key.take(8)}")
            return winner.await()
        }
        return try {
            // Hard ceiling so a load that hangs pre-network (same report:
            // "fetching EPG" logged, then silence -- no grid request, no
            // success, no failure) completes the latch as a failure instead
            // of wedging this playlist's EPG until force-stop. Generous
            // because M3U XMLTV sources legitimately stream hundreds of MB.
            val result = withTimeout(EPG_LOAD_TIMEOUT_MS) {
                loadEpgInternal(playlist, knownChannelKeys)
            }
            mine.complete(result)
            result
        } catch (timeout: TimeoutCancellationException) {
            // The wedge this fix exists for. Report it as a failure so the
            // latch releases and the next refresh actually retries.
            Log.w(
                "PlaylistRepo",
                "loadEpg: timed out after ${EPG_LOAD_TIMEOUT_MS / 1000}s; releasing the in-flight latch",
            )
            val r: Result<List<EPGProgramme>> = Result.failure(timeout)
            mine.complete(r)
            r
        } catch (cancel: CancellationException) {
            // NOT a failure: the caller's scope went away (screen left
            // composition, process winding down). Release the latch for any
            // joiner, then let cancellation propagate rather than reporting a
            // bogus EPG error and continuing work in a dead scope. The
            // timeout arm above runs first, so this only sees real
            // cancellation -- TimeoutCancellationException is a subclass.
            mine.complete(Result.failure(cancel))
            throw cancel
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

    private suspend fun loadEpgInternal(
        playlist: PlaylistEntity,
        knownChannelKeys: Set<String>? = null,
    ): Result<List<EPGProgramme>> =
        // Parse + grid-mapping run on Default, NOT the caller's (Main) dispatcher.
        // A large provider EPG is hundreds of thousands of programmes; parsing
        // that XMLTV / mapping the Dispatcharr grid on Main froze the UI for
        // minutes before the guide could paint.
        withContext(layeringDispatcher) { runCatching {
        val sourceType = playlist.resolvedSourceType()
        // Breadcrumbs bracketing base resolution: the 2026-08 hang sat
        // somewhere between the ViewModel's "fetching EPG" line and the first
        // AerioNet request, and nothing in that stretch logged. Which of
        // these two lines is the last one out pins the stall to either the
        // LAN probe (before) or the auth-broker Room read (after).
        Log.i("PlaylistRepo", "loadEpg: resolving base (source=$sourceType)")
        val base = effectiveBaseUrl(playlist)
        Log.i("PlaylistRepo", "loadEpg: base resolved, starting fetch")
        val programmes = when (sourceType) {
            SourceType.M3uUrl -> {
                // A pasted XC get.php link now loads through player_api (see
                // loadChannels), so its channels carry epg_channel_id and the
                // panel's own xmltv.php will match them. Derive that URL when
                // the user supplied no XMLTV, otherwise those playlists end up
                // with guide ids and no guide to match against. An explicit
                // epgUrl always wins -- the user chose it deliberately.
                val epgUrl = playlist.epgUrl?.takeIf { it.isNotBlank() }
                    ?: xtreamCredsFromGetPhpUrl(base)?.let { xc ->
                        "${xc.base}/xmltv.php?username=${xtreamEncode(xc.username)}" +
                            "&password=${xtreamEncode(xc.password)}"
                    }
                    ?: return@runCatching emptyList()
                // GH #26: constant-memory download + parse (multi-hundred-MB
                // provider XMLTVs OOM'd fetchBytes the same way big M3Us did).
                fetchViaTempFile(epgUrl, ".xmltv") { XMLTVParser.parseFile(it, knownChannelKeys) }
            }
            SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass -> {
                if (playlist.apiKey.isNullOrBlank()) return@runCatching emptyList()
                // iOS GuideStore audit P2 #9 (EPGGuideView.swift:903-947):
                // try the bulk grid first, fall back to current-programs +
                // bulk-upcoming when the grid endpoint is unavailable (older
                // Dispatcharr versions ship with only the legacy endpoints)
                // or returns a 5xx / parse-fail. The fallback path produces
                // a strict subset of the grid's information (no `is_new` /
                // `is_live` / `is_premiere` flags, and no rich descriptions
                // on legacy installs) but keeps the guide useful instead of
                // blank.
                val grid = dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    runCatching { dispatcharrClient.getEpgGrid(base, key).toProgrammes() }
                        .getOrElse { gridErr ->
                            Log.w(
                                "PlaylistRepo",
                                "Dispatcharr grid failed; falling back to current + bulk-upcoming",
                                gridErr,
                            )
                            val current = runCatching {
                                dispatcharrClient.getCurrentPrograms(base, key)
                            }.getOrDefault(emptyList())
                            val upcoming = runCatching {
                                dispatcharrClient.getBulkUpcomingPrograms(base, key)
                            }.getOrDefault(emptyList())
                            if (current.isEmpty() && upcoming.isEmpty()) {
                                // Both fallbacks failed too -- re-throw the
                                // grid error so the outer loadEpg returns a
                                // proper failure Result, not a silently-
                                // empty success.
                                throw gridErr
                            }
                            (current + upcoming).toProgrammes()
                        }
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
                // Catch-up depth (task #210): Dispatcharr's grid only retains a
                // couple of days of history, so Direct Connect users could not
                // browse catch-up content older than that even when the playlist's
                // Guide History setting allows more. The server knows where its
                // guide comes from, though: /api/epg/sources/ lists the XMLTV
                // feeds assigned to channels, and those upstream feeds usually
                // carry a much deeper past window. Fetch each active xmltv source
                // directly and layer it on the grid exactly like the manual EPG
                // URL override below; saveEpgToCache retention + dedupSameAiring
                // handle accumulation and overlap downstream. Sources that are
                // unreachable from the app (LAN-only paths, file:// mounts on the
                // server) fail silently per-source; the grid is never at risk.
                // The user's OWN EPG URL is not optional work and stays on
                // the critical path (it is guide data they configured).
                var layered = grid
                customXmltv?.let { own ->
                    val xmltv = runCatching {
                        fetchViaTempFile(own, ".xmltv") { XMLTVParser.parseFile(it, knownChannelKeys) }
                    }.getOrElse { emptyList() }
                    if (xmltv.isNotEmpty()) layered = layered + xmltv
                }
                // Discord reports 2026-08-08 (rmebast; needcoffee, measured
                // directly against their server): the upstream layering used
                // to run HERE, between the grid fetch and the return, so the
                // guide could not paint until every feed was downloaded and
                // parsed. The 0.4.5 budget capped the damage but kept the
                // architecture: on a server with four epg.guru 7-day national
                // feeds the user still stared at an empty "syncing" guide for
                // the full budget on EVERY load, because a CPU-bound XMLTV
                // parse never hits a suspension point and withTimeout cannot
                // fire mid-parse. Meanwhile the grid itself had returned in
                // ~1s (3.9MB measured on the affected server).
                //
                // The grid now returns IMMEDIATELY and the layering runs in
                // [layerUpstreamInBackground]: same source list, same budget,
                // but off the critical path, cancellable mid-parse, merged
                // through the EPG cache, and announced via [upstreamEpgLayered]
                // so the ViewModel folds it in when it lands. Source discovery
                // moved in there too (GH #53), so nothing before the guide
                // paint touches /api/epg/sources/ or /api/epg/epgdata/.
                layerUpstreamInBackground(playlist.id, base, customXmltv, knownChannelKeys)
                layered
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
                // GH #26: constant-memory download + parse.
                fetchViaTempFile(xmltvUrl, ".xmltv") { XMLTVParser.parseFile(it, knownChannelKeys) }
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
        withContext(layeringDispatcher) {
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
        withContext(layeringDispatcher) {
            epgProgrammeDao
                .forPlaylistInWindow(playlistId, fromMillis, toMillis)
                .map { it.toProgramme() }
        }

    /**
     * EPG-scope search (iOS SearchView EPG scope). Returns near-term
     * programmes whose title/description match [query] for the active
     * source, ordered soonest-first. The returned EPGProgramme.channelId is
     * the canonical guideMatchKey (bridgeChannelIds already rewrote it at
     * fetch time), so a Search result can be handed straight to the
     * guide-jump path without further tvg-id/uuid resolution.
     *
     * TODO(parity task #41): consumed by the #41 global-Search ViewModel
     * (EPG scope), which is not built yet. Exposed here now so the deep-link
     * guide-jump path is complete; wire this into the Search VM when #41 lands.
     */
    suspend fun searchEpg(playlistId: String, query: String): List<EPGProgramme> =
        withContext(layeringDispatcher) {
            val q = query.trim()
            if (q.isBlank()) return@withContext emptyList()
            val like = "%" + q.replace("%", "\\%").replace("_", "\\_") + "%"
            epgProgrammeDao
                .searchInWindow(playlistId, like, System.currentTimeMillis())
                .map { it.toProgramme() }
        }

    suspend fun newestEpgFetch(playlistId: String): Long? =
        epgProgrammeDao.newestFetchedAt(playlistId)

    /**
     * Per-playlist EPG cache purge (iOS GuideStore audit P2 #11). Called by
     * the user-initiated "Refresh EPG Data" action on the playlist detail
     * so the next fetch starts from a clean slate instead of reusing
     * possibly-corrupt cached rows. Idempotent; safe to call when no rows
     * exist.
     */
    suspend fun purgeEpgCache(playlistId: String) {
        epgProgrammeDao.deleteForPlaylist(playlistId)
    }

    suspend fun saveEpgToCache(
        playlistId: String,
        programmes: List<EPGProgramme>,
        /** False when [programmes] is a FRAGMENT of its feed (a budget-
         *  truncated XMLTV parse). The merge then only inserts, and never
         *  deletes a region this list cannot vouch for. */
        authoritative: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        val entities = withContext(layeringDispatcher) {
            programmes.map { it.toCacheEntity(playlistId, now) }
        }
        // Catch-up (task #135): MERGE the feed instead of replacing the whole
        // cache, so already-aired rows survive refreshes and accumulate into a
        // browsable history, then prune to the playlist's retention window
        // (default 7 days). The old behaviour (full replace + drop everything
        // ended over an hour ago) erased exactly the programmes the catch-up
        // "Watch" action needs.
        epgProgrammeDao.mergeForPlaylist(
            playlistId,
            entities,
            now,
            replaceCoveredWindow = authoritative,
        )
        // E-6: prune at most once per cycle. Upstream layering calls this once
        // PER SOURCE on purpose (so a kill mid-phase loses at most one feed),
        // and every one of those calls used to run a full-table retention
        // DELETE -- three sources, three sweeps, for a horizon measured in
        // DAYS. Nothing about a 7-day cutoff needs re-applying seconds later,
        // so the first save in a cycle sweeps and the rest skip.
        val lastSweep = lastRetentionSweepAtMs[playlistId] ?: 0L
        if (now - lastSweep >= RETENTION_SWEEP_COOLDOWN_MS) {
            lastRetentionSweepAtMs[playlistId] = now
            val retentionDays = (dao.byId(playlistId)?.epgRetentionDays ?: 7).coerceAtLeast(1)
            epgProgrammeDao.deleteEndedBeforeForPlaylist(
                playlistId,
                now - retentionDays * 24L * 60L * 60L * 1000L,
            )
        }
    }

    /** Last retention prune per playlist; see the cooldown in [saveEpgToCache]. */
    private val lastRetentionSweepAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Write back JUST these programmes, leaving every other cached row alone.
     *
     * E-6 (perf campaign 2026-08-20): category enrichment changes a category
     * string on the few hundred programmes that happen to be airing, and used
     * to persist that by handing the ENTIRE feed back to [saveEpgToCache] -- a
     * second full rewrite of a ~139k-row table, four indexes per row, plus
     * another retention sweep, minutes after the first one finished. That is
     * the disk churn behind the io-wait storm that turned a 1.4s guide stall
     * into an ANR.
     *
     * These rows already exist: they carry the same (playlistId, channelId,
     * startMillis) as their cached copies, so the REPLACE conflict strategy on
     * that unique index updates them in place. Deliberately NOT
     * [EpgProgrammeDao.mergeForPlaylist] -- that deletes the present-and-future
     * region for every channel it touches before inserting, which for a partial
     * list would erase the programmes that were not enriched.
     */
    suspend fun updateEpgRowsInCache(playlistId: String, programmes: List<EPGProgramme>) {
        if (programmes.isEmpty()) return
        val now = System.currentTimeMillis()
        val entities = withContext(layeringDispatcher) {
            programmes.mapNotNull { p ->
                // Same drawability floor mergeForPlaylist applies; an enriched
                // row must not sneak past the one filter every write shares.
                if (p.endMillis - p.startMillis < 30_000L) null
                else p.toCacheEntity(playlistId, now)
            }
        }
        if (entities.isEmpty()) return
        epgProgrammeDao.insertAll(entities)
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
        // distinctBy url: an exact-duplicate stream (identical URL) is dropped so
        // the Live TV lists (keyed by url) can never see a duplicate key. Channels
        // that merely SHARE a tvg-id but have different URLs are kept (they are
        // distinct streams) -- the list key is url precisely so those don't collide.
        channelSnapshotDao.forPlaylist(playlistId).map { it.toChannel() }.distinctBy { it.url }

    suspend fun newestChannelFetch(playlistId: String): Long? =
        channelSnapshotDao.newestFetchedAt(playlistId)

    suspend fun saveChannelsToCache(playlistId: String, rawChannels: List<M3UChannel>) {
        val now = System.currentTimeMillis()
        // Drop exact-duplicate streams (same URL) before persisting so the cache
        // stays clean and reloads never feed a duplicate key to the url-keyed
        // Live TV lists. Distinct streams that share a tvg-id are kept.
        val channels = rawChannels.distinctBy { it.url }
        // GH #31: persist in CHUNKS inside ONE transaction instead of mapping the
        // whole ~100k-row entity list + one giant insertAll. That overlap (the
        // channel list + the full entity list + the transaction bind) was the
        // bulk of the 505MB heap peak that wedged a large XC import. delete-then-
        // insert stays atomic (a reader never sees a half-written playlist, and a
        // crash rolls back to the previous snapshot), while SQLite buffers pending
        // rows on disk (WAL), so only ONE chunk of entities is ever resident.
        // The delete-first is MANDATORY: ChannelSnapshotEntity's PK is
        // autoGenerate with only a NON-unique index on playlistId, so REPLACE
        // never dedups — chunked inserts without it would duplicate every row.
        database.withTransaction {
            channelSnapshotDao.deleteForPlaylist(playlistId)
            var position = 0
            for (chunk in channels.asSequence().chunked(CHANNEL_CACHE_CHUNK)) {
                channelSnapshotDao.insertAll(
                    chunk.map { ch -> ch.toCacheEntity(playlistId, position++, now) },
                )
            }
            // Stamp the displayed count from the list ACTUALLY PERSISTED, in the
            // same transaction that persists it.
            //
            // Callers used to set channelCount = channels.size from their own
            // pre-dedup list while this function stored `distinctBy { url }`, so
            // the number on the Playlists / playlist-detail screens was computed
            // from a different list than the one the user then scrolls. Any
            // provider that repeats a stream URL made the two disagree
            // permanently, and every caller had to remember to stamp it at all.
            // Doing it here means the count cannot drift from the snapshot by
            // construction, for every write path (add, refresh, switch-active,
            // background refresh worker).
            dao.updateChannelCount(playlistId, channels.size)
        }
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

    /**
     * The ordered member streams of a Dispatcharr channel (highest-priority
     * first) with their probed quality stats, for the player's "Switch Stream"
     * sheet. [channelIntPk] is M3UChannel.dispatcharrChannelId. Empty for
     * non-Dispatcharr sources or when there is no active playlist. AuthBroker-
     * wrapped so a rotated api_key silently rebootstraps instead of surfacing 401.
     */
    suspend fun listDispatcharrChannelStreams(
        channelIntPk: Int,
    ): List<com.aeriotv.android.core.network.DispatcharrChannelStream> {
        val playlist = activePlaylist() ?: return emptyList()
        val sourceType = playlist.resolvedSourceType()
        val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
            sourceType == SourceType.DispatcharrUserPass
        if (!isDispatcharr) return emptyList()
        val base = effectiveBaseUrl(playlist)
        return dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
            dispatcharrClient.listChannelStreams(base, key, channelIntPk)
        }
    }

    /**
     * Map of Dispatcharr M3U account id -> source name, to label each alternate
     * in the Switch Stream sheet with the M3U it comes from. Empty for
     * non-Dispatcharr sources, no active playlist, or on any failure (the sheet
     * then just omits the source label). AuthBroker-wrapped.
     */
    suspend fun dispatcharrM3uAccountNames(): Map<Int, String> {
        val playlist = activePlaylist() ?: return emptyMap()
        val sourceType = playlist.resolvedSourceType()
        val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
            sourceType == SourceType.DispatcharrUserPass
        if (!isDispatcharr) return emptyMap()
        val base = effectiveBaseUrl(playlist)
        return runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.listM3uAccounts(base, key)
            }.mapNotNull { acct -> acct.name?.takeIf { it.isNotBlank() }?.let { acct.id to it } }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * The provider relations Dispatcharr deduped into one logical movie, for
     * the VOD Version picker. Empty for non-Dispatcharr sources or when there
     * is no active playlist. AuthBroker-wrapped like the channel-streams
     * lookup above. The payload nests provider credentials (the client model
     * declares only safe fields); never log the raw rows.
     */
    suspend fun listVodMovieProviders(
        movieId: Int,
    ): List<com.aeriotv.android.core.network.DispatcharrVODProviderRelation> {
        val playlist = activePlaylist() ?: return emptyList()
        val sourceType = playlist.resolvedSourceType()
        val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
            sourceType == SourceType.DispatcharrUserPass
        if (!isDispatcharr) return emptyList()
        val base = effectiveBaseUrl(playlist)
        return dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
            dispatcharrClient.getMovieProviders(base, key, movieId)
        }
    }

    /**
     * MEASURED stream properties of ONE provider copy (the picker's quality
     * labels). Null for non-Dispatcharr sources, when there is no active
     * playlist, or on any failure: this is best-effort enrichment, so a copy
     * the server cannot describe simply shows account + container.
     *
     * Latency: the first call for a copy Dispatcharr has never inspected can
     * take several seconds (it fetches upstream), then caches 24h server-side.
     * Callers must render the picker first and fill labels in as these land.
     */
    suspend fun vodMovieProviderMedia(
        movieId: Int,
        relationId: Int,
    ): com.aeriotv.android.core.network.DispatcharrVODProviderMedia? {
        val playlist = activePlaylist() ?: return null
        val sourceType = playlist.resolvedSourceType()
        val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
            sourceType == SourceType.DispatcharrUserPass
        if (!isDispatcharr) return null
        val base = effectiveBaseUrl(playlist)
        return runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.getMovieProviderMedia(base, key, movieId, relationId)
            }
        }.getOrNull()
    }

    /** Series counterpart of [listVodMovieProviders]. */
    suspend fun listVodSeriesProviders(
        seriesId: Int,
    ): List<com.aeriotv.android.core.network.DispatcharrVODProviderRelation> {
        val playlist = activePlaylist() ?: return emptyList()
        val sourceType = playlist.resolvedSourceType()
        val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
            sourceType == SourceType.DispatcharrUserPass
        if (!isDispatcharr) return emptyList()
        val base = effectiveBaseUrl(playlist)
        return dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
            dispatcharrClient.getSeriesProviders(base, key, seriesId)
        }
    }

    /**
     * Switch a Dispatcharr channel's active upstream to [streamId] (a Stream pk
     * from [listDispatcharrChannelStreams]). [channelUuid] is the channel UUID
     * (M3UChannel.id minus the "disp:" prefix). Dispatcharr swaps the source
     * server-side behind the unchanged /proxy/ts/stream/<uuid> URL; the caller
     * re-primes that URL afterwards so playback pulls the new source.
     */
    /** Switch the channel's upstream. Returns the resolved upstream URL the server
     *  swapped to (for the client re-prime gate), or null if the response omitted it. */
    suspend fun switchDispatcharrStream(channelUuid: String, streamId: Int): String? {
        val playlist = activePlaylist() ?: error("No active playlist for stream switch")
        val base = effectiveBaseUrl(playlist)
        return dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
            dispatcharrClient.changeStream(base, key, channelUuid, streamId)
        }
    }

    /** The currently-active stream pk for a Dispatcharr channel (Switch Stream
     *  sheet radio mark), from /proxy/ts/status. null when unknown / not playing. */
    suspend fun currentDispatcharrStreamId(channelUuid: String): Int? {
        val playlist = activePlaylist() ?: return null
        val base = effectiveBaseUrl(playlist)
        return runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.getCurrentStreamId(base, key, channelUuid)
            }
        }.getOrNull()
    }

    /** The currently-active upstream URL for a Dispatcharr channel, from
     *  /proxy/ts/status. Used to confirm a stream switch landed (reliable on both
     *  the owner-direct and event-apply paths, unlike stream_id). */
    /**
     * Tri-state for the player's dead-session poller (adversarial review
     * 2026-07-15): url = session alive; "" = Dispatcharr ANSWERED and reported
     * no active session (confirmed dead); null = transport/auth failure --
     * unknown, and must NOT be treated as a dead session (a Wi-Fi blip or a
     * server restart while ExoPlayer coasts on its buffer is not a wedge).
     */
    suspend fun currentDispatcharrStreamUrl(channelUuid: String): String? {
        val playlist = activePlaylist() ?: return null
        val base = effectiveBaseUrl(playlist)
        return runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.getCurrentStreamUrl(base, key, channelUuid) ?: ""
            }
        }.getOrNull()
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
                    fetchChannelsFor(
                        sourceType, base, entity.epgUrl, key,
                        entity.dispatcharrProfileId, entity.dispatcharrAccountProfileIdList(),
                    )
                }
            else -> fetchChannelsFor(
                sourceType, base, entity.epgUrl, entity.apiKey,
                entity.dispatcharrProfileId, emptyList(),
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

    /** GH #26: download-to-temp-file + parse + delete. Constant memory for
     *  arbitrarily large payloads (XC-panel M3Us and provider XMLTVs run
     *  100-200MB+; buffering them as one ByteArray OOM'd constrained
     *  heaps). The temp file lives in cacheDir so the OS can reclaim it
     *  even if a crash skips the finally. */
    /**
     * The XC live channel list, shared by the XtreamCodes source type AND by
     * an M3U playlist whose URL is really an XC `get.php` link (see
     * [xtreamCredsFromGetPhpUrl]). Both end up wanting exactly the same thing:
     * the compact JSON list rather than the provider's giant flattened M3U.
     */
    private suspend fun xtreamLiveChannels(
        b: String,
        user: String,
        pass: String,
    ): List<M3UChannel> {
        // Live channels come from player_api.php get_live_streams, NOT
        // from get.php?type=m3u_plus. Measured against a real provider on
        // 2026-08-10 (Logan: panels this size are "very typical of any
        // user that is NOT using Dispatcharr"):
        //
        //     get.php m3u_plus     538 MB
        //     get_live_streams      23.7 MB   -- the same 53,599 channels
        //
        // m3u_plus is one flat dump of live + ALL VOD + ALL series, so
        // ~95% of that 538MB was movie/episode rows this code parsed and
        // then deliberately threw away, because On Demand loads them from
        // the JSON endpoints anyway. We were ALSO already calling
        // get_live_streams on every refresh just for catch-up flags, and
        // then downloading the M3U on top of it.
        //
        // The M3U additionally CANNOT produce a guide on that panel: every
        // m3u_plus entry carries tvg-id="" (2000/2000 sampled), so a
        // perfectly good 32MB xmltv.php matched nothing and the app logged
        // "EPG loaded: 0 programmes across 53306 channels".
        // get_live_streams carries epg_channel_id. This is the shape Apple
        // has always used (StreamingAPIs.getLiveStreams) and what other
        // mainstream clients do.
        // Group names are a separate 83KB call. A failure here costs only
        // the group labels, so it must not fail the whole playlist.
        val categoryNames = runCatching {
            xtreamApi.getLiveCategories(b, user, pass).associate { it.id to it.name }
        }.getOrDefault(emptyMap())
        val streams = xtreamApi.getLiveStreams(b, user, pass)
        if (streams.isEmpty()) {
            // Same contract as PlaylistFetcher's empty-body guard: a
            // playlist that resolves to zero channels is a failure to
            // surface, not a legitimately empty source to store silently.
            // fetchAndMapArray swallows transport/parse failures to an
            // empty list, so this is also where a broken panel lands.
            throw IllegalStateException(
                "The server returned no live channels for these credentials. " +
                    "Check the username and password, and that this device is " +
                    "allowed to connect.",
            )
        }
        android.util.Log.i(
            "PlaylistRepository",
            "XC live list: ${streams.size} channels from player_api, " +
                "${categoryNames.size} categories, " +
                "${streams.count { it.epgChannelId.isNotBlank() }} with EPG ids",
        )
        // See XtreamLiveStream.directSource: not played, but a panel where
        // most channels publish one is the shape where our rebuilt /live/
        // URLs may 404 across the board. One line here turns that report
        // into a diagnosis instead of a hunt.
        val directSourceCount = streams.count { it.directSource.isNotBlank() }
        if (directSourceCount > streams.size / 2) {
            android.util.Log.w(
                "PlaylistRepository",
                "XC panel publishes direct_source on $directSourceCount/${streams.size} " +
                    "channels; if channels list but do not play, this panel may be " +
                    "direct_source-only and the standard /live/ URL form will 404",
            )
        }
        // Same id formula as M3UParser ("m3u:" + tvg-id, else the URL) AND the
        // same duplicate handling. epg_channel_id is routinely SHARED across
        // streams (HD/FHD/SD variants of one channel all carry "espn.us"), and
        // M3UParser has always disambiguated repeats by appending "|url".
        // Skipping that here shipped duplicate ids straight into id-keyed UI
        // (LazyColumn keys throw on duplicates - a crash in the player's
        // channel overlay) and made favoriting one variant flag them all.
        val seenIds = HashSet<String>()
        val channels = streams.map { s ->
            val url = xtreamApi.liveStreamUrl(b, user, pass, s.streamId)
            var id = "m3u:${s.epgChannelId.ifBlank { url }}"
            if (!seenIds.add(id)) {
                id = "$id|$url"
                seenIds.add(id)
            }
            M3UChannel(
                id = id,
                name = s.name,
                url = url,
                groupTitle = categoryNames[s.categoryId].orEmpty(),
                tvgID = s.epgChannelId,
                tvgName = s.name,
                tvgLogo = s.icon,
                channelNumber = s.num?.toString(),
                catchupDays = s.catchupDays,
                catchupStreamId = if (s.catchupDays > 0) s.streamId.toString() else null,
            )
        }
        migrateUrlKeyedChannelIds(channels)
        return channels
    }

    /**
     * Heal favorites and recents that still point at URL-keyed channel ids.
     *
     * The switch to player_api JSON claimed ids were unchanged for existing
     * installs because "a panel that publishes tvg-id publishes the same value
     * as epg_channel_id". True - but the panel that MOTIVATED the switch emits
     * tvg-id="" on every M3U entry while epg_channel_id is populated, so on
     * that class of panel the old M3U path keyed those channels by URL and
     * this path keys them by epg id. Without this pass, updating the app
     * silently emptied those users' Favorites and recents.
     *
     * The URL is the stable half of both schemes, so the mapping is exact: a
     * channel whose new id is epg-keyed may have old rows under "m3u:<url>".
     * Idempotent - after the first run nothing is left under a URL-keyed id
     * that has an epg-keyed replacement - and cheap (favorites and recents
     * are both small), so it simply runs on every XC fetch.
     */
    /**
     * [fetchViaTempFile] as a conditional GET (upstream layering only):
     * null when the feed is unchanged since [validators] were taken, without
     * a download or a parse. Local files always parse.
     */
    private suspend fun <T> fetchViaTempFileIfChanged(
        url: String,
        suffix: String,
        validators: com.aeriotv.android.core.network.FeedValidators?,
        parse: (java.io.File) -> T,
    ): Pair<T, com.aeriotv.android.core.network.FeedValidators?>? = withContext(layeringDispatcher) {
        if (url.startsWith("file:", ignoreCase = true)) {
            return@withContext fetchViaTempFile(url, suffix, parse) to null
        }
        val tmp = java.io.File.createTempFile("aerio_dl", suffix, context.cacheDir)
        try {
            val fresh = fetcher.fetchToFileIfChanged(url, tmp, validators) ?: return@withContext null
            parse(tmp) to fresh
        } finally {
            tmp.delete()
        }
    }

    /**
     * The pre-0.4.11 XC live fetch, kept as the fallback for panels whose
     * player_api is broken while get.php still works. Same streaming
     * temp-file parse (GH #31 memory discipline), same VOD/series row drop,
     * same output=ts-then-bare 404 retry (5a8a605, both directions). No
     * catch-up enrichment: that data came from get_live_streams, which is
     * exactly the endpoint that just failed.
     */
    private suspend fun xtreamLiveChannelsViaM3uPlus(
        b: String,
        user: String,
        pass: String,
    ): List<M3UChannel> {
        val creds = "username=${xtreamEncode(user)}&password=${xtreamEncode(pass)}"
        val m3uUrl = "$b/get.php?$creds&type=m3u_plus&output=ts"
        val m3uUrlNoOutput = "$b/get.php?$creds&type=m3u_plus"
        return fetchViaTempFileWithFallback(m3uUrl, m3uUrlNoOutput, ".m3u") { file ->
            val out = ArrayList<M3UChannel>()
            var droppedVodSeries = 0
            M3UParser.parseFile(file) { ch ->
                if (isXtreamVodOrSeriesUrl(ch.url)) {
                    droppedVodSeries++
                    return@parseFile
                }
                out.add(ch)
            }
            if (droppedVodSeries > 0) {
                android.util.Log.i(
                    "PlaylistRepository",
                    "XC m3u_plus fallback: dropped $droppedVodSeries VOD/series entries; " +
                        "kept ${out.size} live channels",
                )
            }
            out
        }
    }

    private suspend fun migrateUrlKeyedChannelIds(channels: List<M3UChannel>) {
        val renames = HashMap<String, String>()
        for (ch in channels) {
            val urlKeyed = "m3u:${ch.url}"
            if (ch.id != urlKeyed) renames[urlKeyed] = ch.id
        }
        if (renames.isEmpty()) return

        val favoriteDao = database.favoriteChannelDao()
        var migratedFavorites = 0
        for (fav in favoriteDao.allOnce()) {
            val newId = renames[fav.channelId] ?: continue
            // Keep displayOrder/addedAt; skip the insert if the new id already
            // exists (the user re-favorited it by hand after the update).
            if (favoriteDao.getOnce(newId) == null) {
                favoriteDao.upsert(fav.copy(channelId = newId))
            }
            favoriteDao.delete(fav.channelId)
            migratedFavorites++
        }

        val migratedRecents = appPreferences.renameRecentChannelIds(renames)
        if (migratedFavorites > 0 || migratedRecents > 0) {
            android.util.Log.i(
                "PlaylistRepository",
                "Channel-id migration: rewrote $migratedFavorites favorite(s) and " +
                    "$migratedRecents recent(s) from URL-keyed to EPG-keyed ids",
            )
        }
    }

    private suspend fun <T> fetchViaTempFile(
        url: String,
        suffix: String,
        parse: (java.io.File) -> T,
    ): T = withContext(layeringDispatcher) {
        // Task #45 file import: an imported source is a file:// URI pointing
        // at the copy the picker flow stored under filesDir/imports/. No
        // download needed -- parse it in place (refresh re-reads the same
        // snapshot, matching iOS which parses its imported copy).
        if (url.startsWith("file:", ignoreCase = true)) {
            val local = java.io.File(java.net.URI(url))
            if (!local.isFile) {
                throw java.io.FileNotFoundException(
                    "Imported file is missing: ${local.name}. Re-import it from Edit Playlist.",
                )
            }
            return@withContext parse(local)
        }
        // Off the main thread: Ktor's execute{} block (the file copy) and the
        // parse both run on the CALLER'S dispatcher, and the add/refresh flows
        // call in from viewModelScope (Main). A small Dispatcharr payload froze
        // the UI imperceptibly, but a full XC-panel m3u_plus (Discord report
        // 2026-07-30: 385k rows / 31k live on an Onn box) blocked Main for the
        // ENTIRE download + parse - Choreographer logged 4359 skipped frames /
        // a 72.8s Davey, which the reporter experienced as "locked up".
        val tmp = java.io.File.createTempFile("aerio_dl", suffix, context.cacheDir)
        try {
            fetcher.fetchToFile(url, tmp)
            parse(tmp)
        } finally {
            tmp.delete()
        }
    }

    /**
     * [fetchViaTempFile] that retries once against [fallbackUrl] when the
     * primary URL comes back 404. Used for XC `get.php`, where panels disagree
     * about whether the `output` parameter is required or rejected; trying the
     * standard form first and the legacy form only on a hard 404 means neither
     * flavour of panel can break the other. Deliberately narrow: any other
     * failure (timeout, 403, empty body, parse error) propagates untouched so
     * a real problem is not masked by a second doomed download.
     */
    private suspend fun <T> fetchViaTempFileWithFallback(
        primaryUrl: String,
        fallbackUrl: String,
        suffix: String,
        parse: (java.io.File) -> T,
    ): T = try {
        fetchViaTempFile(primaryUrl, suffix, parse)
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (e: Exception) {
        // Callers with nothing better to try pass the same URL twice; retrying
        // an identical request would just burn a second doomed download.
        if (fallbackUrl == primaryUrl) throw e
        if (e.message?.contains("HTTP 404") != true) throw e
        android.util.Log.w(
            "PlaylistRepository",
            "get.php returned 404; retrying without the output parameter",
        )
        fetchViaTempFile(fallbackUrl, suffix, parse)
    }

    /**
     * Delete download temp files left behind by a process that died mid
     * fetch. [fetchViaTempFile] removes its own temp in a `finally`, but that
     * never runs when the app is force-stopped or killed for memory while a
     * download is in flight -- and each orphan is the full size of the payload
     * (a large XMLTV or a 100MB+ M3U). Repeated over a wedged EPG load this is
     * what grew one reporter's app cache past 4GB.
     *
     * Age-gated so a download running RIGHT NOW in another coroutine is never
     * pulled out from under itself.
     */
    fun sweepOrphanedDownloads() {
        runCatching {
            val cutoff = System.currentTimeMillis() - ORPHAN_TEMP_MAX_AGE_MS
            var freed = 0L
            var count = 0
            context.cacheDir.listFiles { f -> f.isFile && f.name.startsWith("aerio_dl") }
                ?.forEach { f ->
                    if (f.lastModified() < cutoff) {
                        val size = f.length()
                        if (f.delete()) {
                            freed += size
                            count++
                        }
                    }
                }
            if (count > 0) {
                Log.i("PlaylistRepo", "swept $count orphaned download temp file(s), freed ${freed / 1024 / 1024}MB")
            }
        }
    }

    private fun deriveName(url: String): String =
        url.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Source" }

    private suspend fun fetchChannelsFor(
        sourceType: SourceType,
        base: String,
        userEpgUrl: String?,
        apiKey: String?,
        profileId: Int? = null,
        accountProfileIds: List<Int> = emptyList(),
        username: String? = null,
        password: String? = null,
        // Dispatchers.IO for the same main-thread reason as fetchViaTempFile:
        // the Dispatcharr branch decodes multi-MB JSON bodies and sorts/maps
        // the full channel list, and callers arrive on Main.
    ): List<M3UChannel> = withContext(layeringDispatcher) { when (sourceType) {
        SourceType.M3uUrl -> {
            // GH #26: stream the download to a temp file + line-parse it.
            // A full XC-panel M3U runs 100-200MB; fetchBytes materialized
            // it as one allocation and OOM'd 256MB-heap phones on Add.
            //
            // Discord report (Onn 4K box, 2026-07-29): users also paste an XC
            // panel's get.php?type=m3u_plus URL directly as a plain M3U source.
            // Those panels dump their whole VOD + series catalog into the
            // "live" m3u (~80% of a ~1M-row, 100MB+ file), and while the
            // DOWNLOAD streams in constant memory, the parse-to-List
            // materialization alone holds ~2.2x the file size in live objects
            // (emulator-measured) -- fatal on a 256MB largeHeap TV box. The
            // XtreamCodes source type already guards this (GH #31) by
            // stream-parsing and dropping VOD/series rows; give the plain-M3U
            // path the SAME guard when the URL is actually an XC get.php
            // fetch. A normal M3U playlist (no get.php) keeps its VOD groups.
            // The streaming parseFile overload also detects charset up front
            // instead of the parse-then-retry double read.
            // A pasted XC get.php link is really an Xtream playlist wearing an
            // M3U hat. Use the panel's JSON endpoints instead: 23.7MB rather
            // than 538MB on the panel measured 2026-08-10, and it carries the
            // epg_channel_id the M3U omits entirely.
            val pastedXtream = xtreamCredsFromGetPhpUrl(base)
            if (pastedXtream != null) {
                android.util.Log.i(
                    "PlaylistRepository",
                    "M3U URL is an Xtream get.php link; loading via player_api instead",
                )
                // The reroute is a URL-shape guess, not a probe. Middleware
                // that serves get.php with player_api disabled, broken, or
                // separately IP-locked exists, and for those users the pasted
                // M3U worked before the reroute did - so a player_api failure
                // falls through to fetching the M3U they actually pasted
                // instead of failing a previously-working playlist outright.
                try {
                    return@withContext xtreamLiveChannels(
                        pastedXtream.base,
                        pastedXtream.username,
                        pastedXtream.password,
                    )
                } catch (e: Exception) {
                    android.util.Log.w(
                        "PlaylistRepository",
                        "player_api failed for the get.php link (${e.message}); " +
                            "falling back to the pasted M3U itself",
                    )
                }
            }
            val isXtreamGetPhp = base.contains("get.php", ignoreCase = true)
            // Some panels 404 a get.php URL that omits `output` (measured
            // 2026-08-10: crx.watch answers `type=m3u_plus` with a bare 404 and
            // serves `output=ts` fine). Providers hand out links in both forms,
            // so a pasted one can fail through no fault of the user. Try their
            // URL exactly as given, and only on a 404 retry it once with
            // `&output=ts` appended. The XtreamCodes source type no longer
            // touches get.php at all, so this pasted-link case is the only
            // place that still needs the retry.
            val getPhpFallback = if (isXtreamGetPhp && !base.contains("output=", ignoreCase = true)) {
                base + (if (base.contains("?")) "&" else "?") + "output=ts"
            } else {
                null
            }
            fetchViaTempFileWithFallback(base, getPhpFallback ?: base, ".m3u") { file ->
                val out = ArrayList<M3UChannel>()
                var droppedVodSeries = 0
                M3UParser.parseFile(file) { ch ->
                    if (isXtreamGetPhp && isXtreamVodOrSeriesUrl(ch.url)) {
                        droppedVodSeries++
                        return@parseFile
                    }
                    out.add(ch)
                }
                if (droppedVodSeries > 0) {
                    android.util.Log.i(
                        "PlaylistRepository",
                        "M3U (XC get.php): dropped $droppedVodSeries VOD/series entries from " +
                            "the live list (add the panel as an Xtream Codes source for On " +
                            "Demand); kept ${out.size} live channels",
                    )
                }
                out
            }
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
            // Layer A: child-safety account filter (FAIL-CLOSED). When the
            // connected account is assigned Channel Profile(s), keep only
            // channels in the UNION of their memberships. A membership fetch
            // that throws propagates -> the whole load fails -> the caller keeps
            // the prior channels instead of leaking the full list. An account
            // profile that resolves to an empty set is respected literally
            // (enables no channels). Mirrors iOS 3eb4ae3d8.
            val accountAllowedIds: Set<Int>? =
                if (accountProfileIds.isNotEmpty()) {
                    val union = HashSet<Int>()
                    for (pid in accountProfileIds) {
                        union += dispatcharrClient.fetchChannelProfileChannelIds(base, key, pid)
                    }
                    union
                } else {
                    null
                }
            // Layer B: user-chosen per-playlist profile (FAIL-OPEN, unchanged).
            // A selected-but-now-deleted profile (firstOrNull == null) falls
            // back to no manual filter rather than a blank list. Skipped (no
            // extra request) when no profile is selected.
            val manualAllowedIds: Set<Int>? = profileId?.let { pid ->
                runCatching {
                    dispatcharrClient.listProfiles(base, key).firstOrNull { it.id == pid }
                }.getOrNull()?.channels?.toSet()
            }
            // Resolve each channel's EPG via its epg_data_id FK. The grid keys
            // programmes by the EPGData's tvg_id, which routinely differs from a
            // channel's own tvg_id, so map epg_data_id -> EPGData.tvg_id and use
            // THAT as the channel's tvgID below. Without this, only channels
            // whose raw tvg_id happens to equal the EPGData tvg_id get a guide.
            val epgDataById: Map<Int, DispatcharrEpgData>? = runCatching {
                dispatcharrClient.listEpgData(base, key).associateBy { it.id }
            }.getOrNull()
            // GH #53: which SOURCE an EPGData row came from decides whether its
            // tvg_id is a real broadcast identity at all. See [dispatcharrGuideKey].
            val epgSourceTypeById: Map<Int, String?>? = runCatching {
                dispatcharrClient.listEpgSources(base, key).associate { it.id to it.sourceType }
            }.getOrNull()
            val serverChannels = dispatcharrClient.listChannels(base, key)
            val afterAccount =
                if (accountAllowedIds != null) serverChannels.filter { it.id in accountAllowedIds } else serverChannels
            val channels =
                if (manualAllowedIds != null) afterAccount.filter { it.id in manualAllowedIds } else afterAccount
            // 2026-08 "only ~40 channels" report: neither filter layer logged,
            // so a truncated or filtered list was indistinguishable from a
            // small server. Always record where the count came from.
            Log.i(
                "PlaylistRepo",
                "Dispatcharr channels: server=${serverChannels.size}, " +
                    "accountFilter=${accountAllowedIds?.size?.toString() ?: "off"} -> ${afterAccount.size}, " +
                    "manualFilter=${manualAllowedIds?.size?.toString() ?: "off"} -> ${channels.size}",
            )
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
                        tvgID = dispatcharrGuideKey(ch, epgDataById, epgSourceTypeById),
                        // GH #53: when the guide key above had to become the
                        // channel UUID, the channel's own tvg-id is still a
                        // legitimate INBOUND key for a tvg-id-keyed feed (a
                        // custom XMLTV the user configured). Carry it so
                        // buildChannelEpgKeyBridge can still route those
                        // programmes onto this channel.
                        rawAttributes = ch.tvgId?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { mapOf("tvg-id" to it) }
                            .orEmpty(),
                        tvgName = ch.name,
                        tvgLogo = ch.logoId?.let { dispatcharrClient.logoUrl(base, it) }.orEmpty(),
                        channelNumber = ch.channelNumber?.formatChannelNumber(),
                        dispatcharrChannelId = ch.id,
                        // Catch-up (task #133): Dispatcharr's /timeshift/
                        // endpoint identifies the channel by Channel.id (its
                        // XC layer exposes it as stream_id). Gate on BOTH
                        // flags so a stale/partial payload can't produce a
                        // dead Watch affordance.
                        catchupDays = if (ch.isCatchup) ch.catchupDays else 0,
                        catchupStreamId = if (ch.isCatchup && ch.catchupDays > 0) {
                            ch.id.toString()
                        } else {
                            null
                        },
                    )
                }
        }
        SourceType.XtreamCodes -> {
            val user = username?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Xtream Codes username is required")
            try {
                xtreamLiveChannels(base.trimEnd('/'), user, password.orEmpty())
            } catch (e: Exception) {
                // Until 0.4.10 this source type fetched get.php m3u_plus, so
                // panels with a broken, disabled, or separately IP-locked
                // player_api WORKED - restreamers and aggregators that only
                // emulate get.php exist. A player_api failure must not turn a
                // previously-working playlist into a permanent "check the
                // username and password" error; fall back to the m3u_plus
                // fetch this branch used to be. Catch-up enrichment is
                // deliberately absent here: it came from get_live_streams,
                // which is exactly what just failed.
                android.util.Log.w(
                    "PlaylistRepository",
                    "XC player_api failed (${e.message}); falling back to get.php m3u_plus",
                )
                xtreamLiveChannelsViaM3uPlus(base.trimEnd('/'), user, password.orEmpty())
            }
        }
    } }
}

/** Matches the reserved Xtream VOD/series path segment as a DELIMITED segment
 *  (leading + trailing slash), so a live channel merely NAMED "...movie..." in
 *  its display text can't trip it -- only the URL path is inspected. */
private val XC_VOD_SERIES_SEGMENT = Regex("/(?:movie|series)/", RegexOption.IGNORE_CASE)

/**
 * True when an XC-delivered stream URL points at a VOD movie or a series
 * episode rather than a live channel. Xtream Codes serves VOD under
 * "…/movie/<user>/<pass>/<id>.<ext>" and episodes under
 * "…/series/<user>/<pass>/<id>.<ext>" (see XtreamCodesApi.buildVodUrl /
 * buildEpisodeUrl), while live channels use "…/live/<user>/<pass>/<id>" (or the
 * bare "…/<user>/<pass>/<id>" form) and carry no such segment.
 *
 * Host-independent on purpose: load-balanced panels rewrite the host in the URLs
 * they return, so anchoring to the entered base would miss them.
 *
 * The explicit "/live/" guard covers the COMMON pathological case -- a panel
 * whose username or password is literally "movie"/"series" still keeps its
 * "…/live/…"-form channels. One residual edge is knowingly left UNHANDLED as
 * negligible: a channel delivered in the BARE "…/<user>/<pass>/<id>" live form
 * (no "/live/" segment) whose credential is EXACTLY "movie"/"series" would match
 * and be dropped. That needs a reserved-word credential AND a panel that emits
 * bare-form live URLs in its m3u_plus -- vanishingly unlikely. Deliberately NOT
 * tightened to a fixed "<kind>/<user>/<pass>/<id>" segment position: that would
 * instead risk MISSING real VOD on panels whose paths deviate from that shape,
 * which defeats the point (the goal is to shed the VOD/series bloat, so a false
 * negative that keeps the bloat is worse than this impossible-in-practice drop).
 */
private fun isXtreamVodOrSeriesUrl(url: String): Boolean =
    !url.contains("/live/", ignoreCase = true) && XC_VOD_SERIES_SEGMENT.containsMatchIn(url)

/**
 * Xtream credentials recovered from a pasted `get.php` playlist URL.
 *
 * Providers hand users a link like
 * `http://host:8080/get.php?username=U&password=P&type=m3u_plus&output=ts`
 * and users paste it as a plain "M3U URL" playlist rather than choosing the
 * Xtream Codes source type -- it is, after all, an M3U link. That lands them
 * on the worst path we have: the provider flattens live + ALL VOD + ALL series
 * into one file (538MB on a real panel measured 2026-08-10) and frequently
 * emits `tvg-id=""` on every entry, so the guide can never populate. The XC
 * JSON endpoints behind the SAME credentials are 23.7MB and carry
 * epg_channel_id.
 *
 * So recognise the shape and use the better source. Returns null for anything
 * that is not unambiguously an XC get.php URL with both credentials.
 */
private data class XtreamCredsFromUrl(val base: String, val username: String, val password: String)

private fun xtreamCredsFromGetPhpUrl(rawUrl: String): XtreamCredsFromUrl? {
    if (!rawUrl.contains("get.php", ignoreCase = true)) return null
    val uri = runCatching { java.net.URI(rawUrl.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host ?: return null
    val query = uri.rawQuery ?: return null
    val params = query.split("&").mapNotNull { pair ->
        val i = pair.indexOf('=')
        if (i <= 0) return@mapNotNull null
        val k = pair.substring(0, i).lowercase()
        val v = runCatching {
            java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }.getOrNull() ?: return@mapNotNull null
        k to v
    }.toMap()
    val user = params["username"]?.takeIf { it.isNotBlank() } ?: return null
    val pass = params["password"] ?: return null
    // Everything before the trailing "/get.php" is the panel base, so panels
    // served from a subdirectory keep working.
    val path = uri.rawPath.orEmpty()
    val cut = path.lastIndexOf("/get.php", ignoreCase = true)
    val basePath = if (cut >= 0) path.substring(0, cut) else ""
    val port = if (uri.port > 0) ":${uri.port}" else ""
    return XtreamCredsFromUrl("$scheme://$host$port$basePath", user, pass)
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
 * Parse a Dispatcharr grid timestamp to epoch millis, accepting both forms the
 * server emits.
 *
 * Dispatcharr renders regular programme times as `2026-08-08T08:35:00Z` but
 * GENERATED dummy programmes as an explicit offset, `2026-08-08T08:35:00+00:00`.
 * `Instant.parse` is ISO_INSTANT, and on the desugared java.time shipped to
 * older Android runtimes that rejects the offset form, so every generated dummy
 * row was silently dropped in DTO conversion while regular rows survived. That
 * is the "guide shows placeholders on channels with dummy EPG" class of report
 * (GitHub #53: 3,754 of 4,226 rows dropped on a SHIELD).
 *
 * Instant first (the common case, no exception on the hot path), then
 * OffsetDateTime. Strictly additive: anything that parsed before still parses.
 */
private fun parseGridInstantMillis(raw: String?): Long? {
    val text = raw?.takeIf { it.isNotBlank() } ?: return null
    runCatching { return Instant.parse(text).toEpochMilli() }
    return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}

/**
 * The key a Dispatcharr channel's guide programmes will arrive under (GH #53).
 *
 * Dispatcharr's `/api/epg/grid/` buckets a programme by the tvg_id of the
 * EPGData row assigned to the channel, EXCEPT for generated dummy / no-EPG
 * rows, which it keys by `str(channel.uuid)`
 * (apps/epg/api_views.py::EPGGridAPIView). Resolving every channel to its
 * EPGData tvg_id therefore collapses siblings: several channels can share one
 * dummy EPGData, so they all resolved to the same key and rendered the same
 * schedule. That is the FANSEAT `120 0` / `120 1` / `120 2` screenshot on
 * GH #53 - three channels, one programme list, plus each other's matches
 * interleaved with generic dummy blocks.
 *
 * So the key follows the SOURCE TYPE behind the assignment:
 *  - dummy source, or no assignment at all -> the channel's own UUID, which
 *    is exactly what the grid keys those rows by, and is unique per channel.
 *  - a real source -> that EPGData's tvg_id, unchanged from before, so
 *    channels legitimately sharing one broadcast feed still share a bucket.
 *
 * Anything unresolved is deliberately AMBIGUOUS rather than wrong: if the
 * epgdata or sources call failed, or the assigned row is missing (stale FK,
 * pagination skew, older Dispatcharr), fall back to the pre-#53 key. A
 * temporarily unreachable endpoint must not re-key an entire playlist.
 */
internal fun dispatcharrGuideKey(
    channel: DispatcharrChannel,
    epgDataById: Map<Int, DispatcharrEpgData>?,
    sourceTypeById: Map<Int, String?>?,
): String {
    val uuid = channel.uuid.orEmpty()
    val legacyKey = channel.tvgId?.trim()?.takeIf { it.isNotBlank() } ?: uuid
    // No metadata at all: keep the previous behaviour rather than guess.
    if (epgDataById == null) return legacyKey
    val assignmentId = channel.effectiveEpgDataId ?: channel.epgDataId
    // A successful epgdata response positively establishes that an unassigned
    // channel has no EPG identity, so its grid rows are UUID-keyed dummies.
    if (assignmentId == null) return uuid.takeIf { it.isNotBlank() } ?: legacyKey
    val data = epgDataById[assignmentId] ?: return legacyKey
    val sourceType = data.epgSourceId?.let { sourceTypeById?.get(it) }?.trim()?.lowercase()
    return when {
        sourceType == "dummy" -> uuid.takeIf { it.isNotBlank() } ?: legacyKey
        sourceType == null -> legacyKey
        else -> data.tvgId?.trim()?.takeIf { it.isNotBlank() } ?: legacyKey
    }
}

/** One upstream XMLTV feed plus the guide keys it is allowed to supply. */
internal data class DispatcharrEpgLayer(
    val url: String,
    val channelKeys: Set<String>,
)

/**
 * Pair each active upstream XMLTV source with ONLY the guide keys Dispatcharr
 * actually sourced from it (GH #53).
 *
 * Upstream feeds used to be parsed against the playlist's whole key set, so
 * two unrelated feeds that happen to reuse a tvg-id - which is common, the
 * value is a broadcaster string, not a GUID - both dumped programmes into the
 * same bucket. That is the other half of the FANSEAT report: named matches
 * belonging to sibling channels appearing on `120 1` alongside its own dummy
 * blocks.
 *
 * The scoping needs no channel list: every EPGData row already names both its
 * tvg_id and its source, so source -> keys falls straight out of
 * `/api/epg/epgdata/`. Intersecting with [knownChannelKeys] keeps the parser
 * filter as tight as before. Dummy sources are excluded implicitly - they are
 * not `xmltv` and have no URL to fetch.
 */
internal fun dispatcharrEpgLayers(
    epgData: List<DispatcharrEpgData>,
    sources: List<DispatcharrEpgSource>,
    knownChannelKeys: Set<String>?,
    customXmltvUrl: String?,
): List<DispatcharrEpgLayer> {
    val keysBySource = LinkedHashMap<Int, MutableSet<String>>()
    for (row in epgData) {
        val sourceId = row.epgSourceId ?: continue
        val tvgId = row.tvgId?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: continue
        if (knownChannelKeys != null && tvgId !in knownChannelKeys) continue
        keysBySource.getOrPut(sourceId) { linkedSetOf() }.add(tvgId)
    }
    return sources.asSequence()
        .filter { it.isActive && it.sourceType == "xmltv" && it.hasChannels != false }
        .mapNotNull { src ->
            val url = src.url?.trim()?.takeIf { u ->
                u.startsWith("http://", ignoreCase = true) ||
                    u.startsWith("https://", ignoreCase = true)
            } ?: return@mapNotNull null
            if (url == customXmltvUrl) return@mapNotNull null
            val keys = keysBySource[src.id] ?: return@mapNotNull null
            if (keys.isEmpty()) null else DispatcharrEpgLayer(url, keys)
        }
        // Two source rows can point at the same URL (the same feed assigned
        // twice); fetch it once with the union of both scopes.
        .groupBy { it.url }
        .map { (url, same) -> DispatcharrEpgLayer(url, same.flatMapTo(linkedSetOf()) { it.channelKeys }) }
}

/**
 * Convert Dispatcharr `/api/epg/grid/` entries into the universal EPGProgramme
 * shape the rest of the app consumes. Entries without a tvg_id are dropped -
 * they cannot be matched back to a channel row. Entries with genuinely
 * malformed times are dropped too (see [parseGridInstantMillis]).
 *
 * Dispatcharr bulk grid intentionally omits `category` for perf; we propagate
 * empty string. Lazy category enrichment via /api/epg/programs/<id>/ lives in
 * a later phase tied to ProgramInfoView.
 */
private fun List<DispatcharrEpgEntry>.toProgrammes(): List<EPGProgramme> =
    mapNotNull { entry ->
        val channelId = entry.tvgId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val start = parseGridInstantMillis(entry.startTime) ?: return@mapNotNull null
        val end = parseGridInstantMillis(entry.endTime) ?: return@mapNotNull null
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
            // Badge metadata decoded off the grid. is_previously_shown is not
            // in Dispatcharr's slim grid serializer TODAY, so isRepeat is
            // false on current servers - but it is decoded and mapped so the
            // pill lights up as soon as the server starts emitting it.
            subTitle = entry.subTitle?.takeIf { it.isNotBlank() },
            season = entry.season,
            episode = entry.episode,
            isNew = entry.isNew,
            isLiveBroadcast = entry.isLive,
            isPremiere = entry.isPremiere,
            isFinale = entry.isFinale,
            isRepeat = entry.isPreviouslyShown,
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
    subTitle = subTitle,
    season = season,
    episode = episode,
    isNew = isNew,
    isLiveBroadcast = isLiveBroadcast,
    isPremiere = isPremiere,
    isFinale = isFinale,
    isRepeat = isRepeat,
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
        subTitle = subTitle,
        season = season,
        episode = episode,
        isNew = isNew,
        isLiveBroadcast = isLiveBroadcast,
        isPremiere = isPremiere,
        isFinale = isFinale,
        isRepeat = isRepeat,
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
    drmLicenseType = drmLicenseType,
    drmLicenseKey = drmLicenseKey,
    dispatcharrChannelId = dispatcharrChannelId,
    catchupDays = catchupDays,
    catchupStreamId = catchupStreamId,
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
    catchupDays = catchupDays,
    catchupStreamId = catchupStreamId,
    drmLicenseType = drmLicenseType,
    drmLicenseKey = drmLicenseKey,
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
