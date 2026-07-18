package com.aeriotv.android.core.playback

import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.XtreamCodesApi
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns "replay this past programme on this channel" into a playable timeshift
 * URL for the active source (task #133). Two paths:
 *
 * - Dispatcharr Direct Connect: the /timeshift/ endpoint authenticates with the
 *   Django username + the user's XC output password (no ApiKey support), both
 *   readable by the authenticated user from /api/accounts/users/me/ -- fetched
 *   once per server and memoized. The base host is derived from the channel's
 *   live stream URL so catch-up follows the same LAN/WAN host the live stream
 *   was rebuilt for. Dispatcharr advertises server_info.timezone = UTC, so the
 *   start renders in UTC, and the endpoint's one-time 301 (session_id) is
 *   pre-resolved so player seeks stay on one server session.
 *
 * - Xtream Codes provider: path-embedded playlist credentials against the
 *   panel base, with the start rendered in the panel's server_info.timezone
 *   (fetched once per server and memoized; the classic wrong-hour bug lives
 *   here). Raw panels answer directly -- no redirect resolve needed.
 */
@Singleton
class CatchupPlaybackResolver @Inject constructor(
    private val dispatcharrClient: DispatcharrClient,
    private val xtreamApi: XtreamCodesApi,
) {

    /** XC output creds per Dispatcharr base URL, memoized for the process. */
    private val xcCredsCache = ConcurrentHashMap<String, Pair<String, String>>()

    /** Panel timezone per XC base URL, memoized for the process. */
    private val panelTzCache = ConcurrentHashMap<String, String>()

    /** Task #149: whether a Dispatcharr base supports the native catch-up
     *  sessions API (POST /api/catchup/sessions/, dev PR #1432). false is
     *  cached after a 404 so stable-tag servers pay the probe once per
     *  process; absent = not probed yet. */
    private val nativeSupportCache = ConcurrentHashMap<String, Boolean>()

    /** A playable timeshift URL plus the panel timezone that rendered its
     *  start segment; the player needs the zone to rebuild the URL for
     *  scrub-seeks (task #136, CatchupUrlBuilder.rebuildForOffset).
     *  `channelUuid` is non-null only on the NATIVE Dispatcharr path
     *  (task #149): seeks then re-mint a session at programmeStart+offset
     *  instead of rebuilding an XC wall-clock URL, and the player revokes
     *  the session on close. */
    data class Playback(
        val url: String,
        val panelTimeZoneId: String,
        val channelUuid: String? = null,
    )

    sealed class Failure(message: String) : Exception(message) {
        class NotCatchup :
            Failure("This channel has no catch-up archive.")

        class MissingXcPassword : Failure(
            "Catch-up needs an XC password on your Dispatcharr user. " +
                "Ask an admin to set one under Users in Dispatcharr.",
        )

        class Unsupported :
            Failure("Catch-up is available on Dispatcharr and Xtream Codes sources.")
    }

    suspend fun resolve(
        playlist: PlaylistEntity,
        channel: M3UChannel,
        startMillis: Long,
        endMillis: Long,
    ): Result<Playback> = runCatching {
        val streamId = channel.catchupStreamId
            ?.takeIf { channel.catchupDays > 0 }
            ?: throw Failure.NotCatchup()
        val sourceType = SourceType.entries.firstOrNull { it.name == playlist.sourceType }
        when (sourceType) {
            SourceType.DispatcharrApiKey, SourceType.DispatcharrUserPass -> {
                // Follow the live stream's host (LAN/WAN-aware) rather than the
                // saved base, mirroring how playback URLs are rebuilt.
                val base = CatchupUrlBuilder.dispatcharrBaseFromStreamUrl(channel.url)
                    ?: playlist.urlString.trimEnd('/')
                val apiKey = playlist.apiKey?.takeIf { it.isNotBlank() }
                    ?: throw Failure.Unsupported()
                // Task #149: prefer the native sessions API (normal auth on
                // the mint, header-free playback URL, per-session provider
                // slot, no xc_password dependency). One 404 marks the base
                // legacy for the process and we fall through to the XC
                // /timeshift/ path below - which also remains THE path for
                // genuine Xtream Codes playlists (next branch), forever.
                val channelUuid = dispatcharrChannelUuid(channel.url)
                if (channelUuid != null && nativeSupportCache[base] != false) {
                    when (val minted = dispatcharrClient.createCatchupSession(
                        baseUrl = base,
                        apiKey = apiKey,
                        channelUuid = channelUuid,
                        startMillis = startMillis,
                        durationMinutes = programmeMinutes(startMillis, endMillis),
                    )) {
                        is DispatcharrClient.CatchupSessionResult.Created -> {
                            nativeSupportCache[base] = true
                            return@runCatching Playback(
                                url = base + minted.session.playbackUrl,
                                panelTimeZoneId = "UTC",
                                channelUuid = channelUuid,
                            )
                        }
                        DispatcharrClient.CatchupSessionResult.Unsupported ->
                            nativeSupportCache[base] = false
                        is DispatcharrClient.CatchupSessionResult.Error -> {
                            // Transient (5xx/transport): fall back to XC for
                            // THIS attempt without caching a verdict.
                        }
                    }
                }
                val creds = xcCredsCache[base]
                    ?: dispatcharrClient.fetchXcCredentials(base, apiKey)
                        ?.also { xcCredsCache[base] = it }
                    ?: throw Failure.MissingXcPassword()
                // Hand ExoPlayer the raw timeshift URL and let ITS first
                // request follow the 301 that appends ?session_id=. We must
                // NOT pre-resolve that redirect ourselves: Dispatcharr ties
                // the session's serving generator to the request that created
                // it, so a throwaway probe that opens then closes SPENDS the
                // session and the player's subsequent open 404s (verified on
                // device). ExoPlayer's DefaultHttpDataSource follows the 301
                // in-band and keeps its own session alive for the playback.
                Playback(
                    url = CatchupUrlBuilder.build(
                        CatchupUrlBuilder.Context(
                            baseUrl = base,
                            username = creds.first,
                            password = creds.second,
                            streamId = streamId,
                            panelTimeZoneId = "UTC",
                        ),
                        startMillis = startMillis,
                        endMillis = endMillis,
                    ),
                    panelTimeZoneId = "UTC",
                )
            }
            SourceType.XtreamCodes -> {
                val base = playlist.urlString.trimEnd('/')
                val user = playlist.username?.takeIf { it.isNotBlank() }
                    ?: throw Failure.Unsupported()
                val pass = playlist.password.orEmpty()
                val tz = panelTzCache[base]
                    ?: xtreamApi.getServerTimezone(base, user, pass)
                        ?.also { panelTzCache[base] = it }
                    ?: "UTC"
                Playback(
                    url = CatchupUrlBuilder.build(
                        CatchupUrlBuilder.Context(
                            baseUrl = base,
                            username = user,
                            password = pass,
                            streamId = streamId,
                            panelTimeZoneId = tz,
                        ),
                        startMillis = startMillis,
                        endMillis = endMillis,
                    ),
                    panelTimeZoneId = tz,
                )
            }
            else -> throw Failure.Unsupported()
        }
    }

    /** Channel UUID from a Dispatcharr live stream URL
     *  (/proxy/ts/stream/<uuid>); null for any other shape. */
    private fun dispatcharrChannelUuid(streamUrl: String): String? {
        val marker = "/proxy/ts/stream/"
        val idx = streamUrl.indexOf(marker).takeIf { it >= 0 } ?: return null
        return streamUrl.substring(idx + marker.length)
            .substringBefore('?')
            .substringBefore('/')
            .takeIf { it.isNotBlank() }
    }

    /**
     * Task #149: mint a fresh native session for a seek re-tune at
     * programmeStart+offset (the floored-minute model keeps its exact
     * semantics; only the URL construction changed). The base host is
     * taken from the CURRENT playback URL so LAN/WAN routing follows
     * whatever the original mint used. Returns the new absolute
     * playback URL, or null on any failure (the caller keeps playing
     * the current window).
     */
    suspend fun remintNative(
        playlist: PlaylistEntity,
        channelUuid: String,
        currentPlaybackUrl: String,
        absStartMillis: Long,
        programmeEndMillis: Long = 0L,
    ): String? {
        val apiKey = playlist.apiKey?.takeIf { it.isNotBlank() } ?: return null
        val base = baseOf(currentPlaybackUrl) ?: return null
        val minted = dispatcharrClient.createCatchupSession(
            baseUrl = base,
            apiKey = apiKey,
            channelUuid = channelUuid,
            startMillis = absStartMillis,
            // Task #183: window the re-mint to the REMAINING length (the
            // start is mid-programme); full length would overshoot into
            // the next show by the seek offset. 0/absent end => null =>
            // server default window.
            durationMinutes = programmeMinutes(absStartMillis, programmeEndMillis),
        )
        return (minted as? DispatcharrClient.CatchupSessionResult.Created)
            ?.let { base + it.session.playbackUrl }
    }

    /** Task #183: report the local playhead / pause state for the native
     *  session embedded in `playbackUrl`. Returns false ONLY when the
     *  server lacks the endpoint (404) so the caller can stop reporting
     *  for this playback; any other failure returns true (keep trying). */
    suspend fun reportNativePosition(
        playlist: PlaylistEntity,
        playbackUrl: String,
        positionSecs: Double,
        paused: Boolean,
    ): Boolean {
        val apiKey = playlist.apiKey?.takeIf { it.isNotBlank() } ?: return true
        val base = baseOf(playbackUrl) ?: return true
        val sessionId = playbackUrl.substringAfter("session_id=", "")
            .substringBefore('&')
            .takeIf { it.isNotBlank() } ?: return true
        return dispatcharrClient.reportCatchupPosition(base, apiKey, sessionId, positionSecs, paused)
    }

    /** Task #183: programme length in whole minutes for the server's
     *  `duration` hint (rounded up so a 29m30s programme asks for 30),
     *  or null when the window is empty/invalid (server derives from its
     *  own EPG or default instead). */
    private fun programmeMinutes(startMillis: Long, endMillis: Long): Int? {
        val spanMs = endMillis - startMillis
        if (spanMs <= 0L) return null
        return (((spanMs + 59_999L) / 60_000L).toInt()).coerceAtLeast(1)
    }

    /** Task #149: best-effort revoke of the session embedded in a native
     *  playback URL (frees the server's provider slot ahead of the idle
     *  TTL). No-op for XC-shaped URLs or when parsing fails. */
    suspend fun revokeNative(playlist: PlaylistEntity, playbackUrl: String) {
        val apiKey = playlist.apiKey?.takeIf { it.isNotBlank() } ?: return
        val base = baseOf(playbackUrl) ?: return
        val sessionId = playbackUrl.substringAfter("session_id=", "")
            .substringBefore('&')
            .takeIf { it.isNotBlank() } ?: return
        dispatcharrClient.deleteCatchupSession(base, apiKey, sessionId)
    }

    /** scheme://host[:port] of an absolute URL, or null. */
    private fun baseOf(url: String): String? = runCatching {
        val u = java.net.URI(url)
        val port = if (u.port > 0) ":${u.port}" else ""
        if (u.scheme.isNullOrBlank() || u.host.isNullOrBlank()) null
        else "${u.scheme}://${u.host}$port"
    }.getOrNull()
}
