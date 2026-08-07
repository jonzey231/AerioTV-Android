package com.aeriotv.android.core.network

import android.util.Log
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
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
 *   4. Re-login via [DispatcharrClient.login] — server revoked the api_key
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
 * decode error, etc.). Callers fall back to surfacing the original 401 —
 * silent recovery is a best-effort upgrade, not a guarantee.
 */
@Singleton
class DispatcharrAuthBroker @Inject constructor(
    private val client: DispatcharrClient,
    private val tokenStore: DispatcharrTokenStore,
    private val dao: PlaylistDao,
) {
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
        val playlist = dao.byId(playlistId)
            ?: throw IllegalStateException("Playlist $playlistId not found")
        val initialKey = playlist.apiKey?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Playlist ${playlistId.take(8)} has no api_key")
        client.seedAuthMode(playlist)
        return try {
            block(initialKey)
        } catch (e: DispatcharrError.Unauthorized) {
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
            if (freshKey == null) {
                Log.w(TAG, "silent rebootstrap returned null for ${playlistId.take(8)}; surfacing original 401")
                throw e
            }
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
        return try {
            val pair = client.login(playlist.urlString, username, password)
            tokenStore.store(playlist.id, pair.access, pair.refresh)
            val newKey = client.fetchCurrentUserApiKey(playlist.urlString, pair.access)
            dao.update(playlist.copy(apiKey = newKey))
            Log.i(TAG, "silentRebootstrap OK for ${playlist.id.take(8)}")
            newKey
        } catch (t: Throwable) {
            Log.w(TAG, "silentRebootstrap FAIL for ${playlist.id.take(8)}: ${t.message}")
            null
        }
    }

    private companion object {
        const val TAG = "DispatcharrAuthBroker"
    }
}
