package com.aeriotv.android.core.network

import android.util.Log
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central point for "is the persisted api_key still valid, and if not, how do
 * we recover?" wired around every Dispatcharr API-key call. Mirrors iOS
 * DispatcharrAPI.silentRebootstrapApiKey (DispatcharrDirectConnect.swift
 * lines 485-588) so an admin rotating a user's api_key on the server is
 * recovered from transparently on the next API call rather than surfacing
 * a 401 and forcing the user to manually re-edit the playlist.
 *
 * Recovery sequence:
 *   1. Caller wraps a Dispatcharr API call in [withApiKeyRetry].
 *   2. First attempt uses the api_key persisted on [PlaylistEntity].
 *   3. If that call throws [DispatcharrError.Unauthorized], we read the
 *      saved username + password from the same row.
 *   4. Re-login via [DispatcharrClient.login], server revoked the api_key
 *      but the credentials themselves are still valid.
 *   5. Cache the new JWT pair in [DispatcharrTokenStore] so subsequent
 *      bearer-mode calls (e.g. fetchCurrentUser) skip another round trip.
 *   6. Fetch /api/accounts/users/me/ with the fresh access token, extract
 *      the new api_key.
 *   7. Persist the new api_key to the playlist row so subsequent calls
 *      use it directly.
 *   8. Replay the original block with the new key.
 *
 * Returns null on any failure (no creds saved, login rejected, users/me
 * decode error, etc.). Callers fall back to surfacing the original 401,
 * silent recovery is a best-effort upgrade, not a guarantee.
 */
@Singleton
class DispatcharrAuthBroker @Inject constructor(
    private val client: DispatcharrClient,
    private val tokenStore: DispatcharrTokenStore,
    private val dao: PlaylistDao,
) {
    /**
     * Per-session row cache (review 2026-10-27): a sweep made one Room read
     * plus decrypt per page. Keyed by playlist and profile scope token, and
     * cleared on ANY write to the playlists table (a key rotation, an edit,
     * a profile switch rewriting the row), so a stale key is never served.
     */
    private class CachedRow(val token: String, val row: PlaylistEntity)
    private val rowCache = java.util.concurrent.ConcurrentHashMap<String, CachedRow>()
    private val invalidator = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun startInvalidator() {
        if (!invalidator.compareAndSet(false, true)) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            dao.observeAll().collect { rowCache.clear() }
        }
    }

    private suspend fun loadRow(playlistId: String): PlaylistEntity? {
        startInvalidator()
        val token = com.aeriotv.android.core.profiles.ProfileScope.token.value
        rowCache[playlistId]?.takeIf { it.token == token }?.let { return it.row }
        val row = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dao.byId(playlistId) }
            ?: return null
        rowCache[playlistId] = CachedRow(token, row)
        Log.d(TAG, "withApiKeyRetry: row loaded for ${playlistId.take(8)}")
        return row
    }

    /** Drop the cached row (the key on it was just rejected or replaced). */
    fun invalidateRow(playlistId: String) { rowCache.remove(playlistId) }

    /**
     * Run [block] with the playlist's persisted api_key. On
     * [DispatcharrError.Unauthorized], run [silentRebootstrapApiKey] and
     * replay [block] with the freshly-issued key. On any other failure
     * (including a second-attempt 401, or the absence of saved credentials)
     * re-throw the original.
     */
    suspend fun <T> withApiKeyRetry(
        playlistId: String,
        block: suspend (apiKey: String) -> T,
    ): T {
        // Row load off the caller's dispatcher: callers on Main (enrichment,
        // 2026-10-26 Streamer capture, tid == pid) ran this Room read there.
        val playlist = loadRow(playlistId)
            ?: throw IllegalStateException("Playlist $playlistId not found")
        val initialKey = playlist.apiKey?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Playlist ${playlistId.take(8)} has no api_key")
        client.seedAuthMode(playlist)
        client.seedUserAgent(playlist)
        return try {
            block(initialKey)
        } catch (e: DispatcharrError.Unauthorized) {
            invalidateRow(playlistId)
            // Task #49 (iOS verifyConnection v1.6.20 parity): before assuming
            // the KEY was rotated, check whether the 401 is a header-SHAPE
            // rejection -- some deployments (reverse proxies, hardened
            // builds) reject `Authorization: ApiKey` or require it, with a
            // perfectly valid key. The probe records the working shape in
            // the client's registry; persist it and replay. Cheap (a couple
            // of /api/core/version/ GETs) and requires no saved credentials,
            // so it runs ahead of the credential rebootstrap.
            val detected = detectAndPersistAuthMode(playlist, initialKey)
            if (detected != null) {
                Log.i(TAG, "401 for ${playlistId.take(8)} was a header-shape rejection; retrying with mode=$detected")
                return block(initialKey)
            }
            Log.i(TAG, "401 on apiKey call for ${playlistId.take(8)}; attempting silent rebootstrap")
            val freshKey = silentRebootstrapApiKey(playlist)
            if (freshKey == null && (playlist.password.isNullOrBlank() || playlist.username.isNullOrBlank())) {
                // API-Key-mode playlists have no credentials to re-mint from,
                // so a rejected key is terminal until the user replaces it.
                // Before this flag the failure was invisible: Live TV and the
                // guide kept serving from cache while VOD alone 401'd, so the
                // Movies & TV tab simply never appeared and nothing said why
                // (reported 2026-08-08 on both a Z Fold and the Streamer).
                // Record it so Settings can prompt for a new key.
                Log.w(
                    TAG,
                    "silent rebootstrap returned null for ${playlistId.take(8)}: API-Key-mode " +
                        "playlist with a rejected key has nothing to re-mint from. The user must " +
                        "enter a new key in Edit Playlist. Surfacing the 401.",
                )
                throw e
            }
            if (freshKey == null) throw e
            block(freshKey)
        }
    }

    /**
     * Probe the server's accepted auth header shape and, when one works AND
     * differs from what the playlist row already records, persist it so
     * every later call starts in the right shape. Returns the detected mode
     * only when it represents a CHANGE worth replaying the original call
     * for; null when no shape authenticates (key genuinely bad), the server
     * is unreachable, or the detected shape is what we were already using
     * (replaying would just 401 again).
     */
    private suspend fun detectAndPersistAuthMode(
        playlist: PlaylistEntity,
        apiKey: String,
    ): String? {
        val previous = playlist.dispatcharrAuthMode.ifBlank { AUTH_MODE_BOTH }
        val detected = client.detectAuthMode(playlist.urlString, apiKey) ?: return null
        // Also register the LAN route's host under the detected mode.
        client.seedAuthMode(playlist.copy(dispatcharrAuthMode = detected))
        if (detected == previous) return null
        dao.update(playlist.copy(dispatcharrAuthMode = detected))
        invalidateRow(playlist.id)
        Log.i(TAG, "auth-mode auto-detect for ${playlist.id.take(8)}: $previous -> $detected")
        return detected
    }

    /**
     * Re-login from the playlist row's saved username + password, exchange
     * for a fresh api_key via /api/accounts/users/me/, persist it. Returns
     * the new key or null on any failure.
     *
     * Public so the WarmupCoordinator and the Edit Playlist test-connection
     * flow can call it directly when needed (matching iOS where the helper
     * is a static method on DispatcharrAPI, not gated behind the retry
     * wrapper).
     */
    suspend fun silentRebootstrapApiKey(playlist: PlaylistEntity): String? {
        val username = playlist.username?.takeIf { it.isNotBlank() }
        val password = playlist.password?.takeIf { it.isNotBlank() }
        if (username == null || password == null) {
            Log.i(TAG, "silentRebootstrap SKIP: playlist ${playlist.id.take(8)} has no creds (API Key mode)")
            return null
        }
        // One attempt per playlist per 60 s, shared by concurrent callers;
        // 429 and rejected logins park (DispatcharrLoginGate).
        return DispatcharrLoginGate.silentRelogin(playlist.id) {
            rebootstrapOnce(playlist, username, password)
        }
    }

    private suspend fun rebootstrapOnce(
        playlist: PlaylistEntity,
        username: String,
        password: String,
    ): String? {
        return try {
            // Re-read the row: a caller that waited may hold a stale copy.
            val row = dao.byId(playlist.id) ?: playlist
            val pair = client.login(row.urlString, username, password)
            tokenStore.store(row.id, pair.access, pair.refresh)
            val newKey = client.fetchCurrentUserApiKey(row.urlString, pair.access)
            dao.update(row.copy(apiKey = newKey))
            invalidateRow(row.id)
            Log.i(TAG, "silentRebootstrap OK for ${playlist.id.take(8)}")
            newKey
        } catch (c: kotlinx.coroutines.CancellationException) {
            // A cancelled caller (a sweep preempted by a playlist switch, the
            // user leaving the tab) is not a login failure: logging it as FAIL
            // sent GH #109 chasing a credential problem that did not exist,
            // and swallowing it let the cancelled coroutine keep running.
            throw c
        } catch (e: DispatcharrError.InvalidCredentials) {
            Log.w(TAG, "silentRebootstrap FAIL for ${playlist.id.take(8)}: ${e.message}")
            throw e // the gate parks the playlist and surfaces it once
        } catch (t: Throwable) {
            Log.w(TAG, "silentRebootstrap FAIL for ${playlist.id.take(8)}: ${t.message}")
            null
        }
    }

    private companion object {
        const val TAG = "DispatcharrAuthBroker"
    }
}
