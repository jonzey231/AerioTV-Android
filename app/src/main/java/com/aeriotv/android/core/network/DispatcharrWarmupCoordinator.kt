package com.aeriotv.android.core.network

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Refreshes Dispatcharr JWTs whenever the app enters the foreground so the
 * first API call after a long backgrounded period lands with a warm token
 * cache. Mirrors iOS `DispatcharrTokenStore.warmup` (DispatcharrDirectConnect.swift
 * lines 200-241) plus the scene-active observer in AerioApp that drives it.
 *
 * Strategy per playlist:
 *  1. Try a refresh against the cached refresh token (no re-login needed when
 *     the user was active recently — typical 30 min access TTL inside the
 *     24 h refresh window).
 *  2. On [DispatcharrError.RefreshExpired] or any other refresh failure,
 *     fall back to a fresh login from the playlist row's username + password.
 *  3. Both failures are logged but never thrown — the api_key fallback path
 *     in every call site keeps the app working when warmup fails (server
 *     unreachable, network blip), so this coordinator's failure modes are
 *     non-fatal.
 *
 * Bound from [com.aeriotv.android.AerioTVApplication.onCreate] via
 * [bind] so the [ProcessLifecycleOwner] observer survives configuration
 * changes and individual Activity lifecycles.
 */
@Singleton
class DispatcharrWarmupCoordinator @Inject constructor(
    private val dao: PlaylistDao,
    private val client: DispatcharrClient,
    private val tokenStore: DispatcharrTokenStore,
    // Lazy so the DispatcharrClient -> repository direction stays one-way at
    // construction time; the cast re-resolve only needs it at ON_START.
    private val playlistRepository: dagger.Lazy<
        com.aeriotv.android.core.data.repository.PlaylistRepository,
        >,
) : DefaultLifecycleObserver {

    // SupervisorJob so a warmup failure on one playlist doesn't cancel the
    // siblings. Dispatchers.IO since the work is network-bound.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bound = false

    // ON_START fires on cold launch AND on every return to the foreground.
    // Only the first one is a cold launch, and only that one force-probes.
    private var coldLaunch = true

    /** When the app last went to the background, for the foreground re-probe. */
    private var backgroundedAt = 0L

    /**
     * Attach this coordinator to the ProcessLifecycleOwner. Idempotent — a
     * second call is a no-op so it's safe to invoke from Application.onCreate
     * without guard logic at the call site.
     */
    fun bind() {
        if (bound) return
        bound = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        // ON_START fires both on cold-launch foreground AND every time the
        // app comes back from the background. Both cases benefit from a
        // token refresh — match iOS scene-phase .active behavior.
        val isColdLaunch = coldLaunch
        coldLaunch = false
        // A return from the background after more than a minute re-reads
        // permissions too, matching Apple: an admin can grant or revoke while
        // the user is out of the app, and the TTL would otherwise hold a
        // stale answer for hours.
        val awayLongEnough = !isColdLaunch && backgroundedAt > 0L &&
            System.currentTimeMillis() - backgroundedAt >= FOREGROUND_REPROBE_MS
        val forceProbe = isColdLaunch || awayLongEnough
        scope.launch {
            warmupAll()
            // Per-user capabilities, every launch AND every return to the
            // foreground: an admin can grant or revoke DVR / VOD / catch-up
            // access at any time, and the app should reflect it without the
            // user editing the playlist.
            //
            // A COLD LAUNCH always forces, TTL ignored. An admin demotion
            // happens while the app is closed, and force-closing plus
            // relaunching is exactly what a user does to make the app notice;
            // with the 6 h TTL that relaunch probed nothing and the app kept
            // showing admin affordances. A return to the foreground keeps the
            // TTL (cheap, frequent, and the snapshot is usually minutes old);
            // so do the opportunistic DVR / On Demand entry checks.
            runCatching {
                playlistRepository.get().probeAllCapabilities(
                    force = forceProbe,
                    trigger = if (isColdLaunch) "cold launch" else "foreground",
                    // Foreground re-reads at most once a minute per playlist.
                    minIntervalMs = FOREGROUND_REPROBE_MS,
                )
            }
                .onFailure { Log.w(TAG, "capability probe pass failed: ${it.message}") }
        }
        // Cast audio, 2026-09-13: the launch / foreground re-resolve of the
        // Dispatcharr AAC output profile is GONE. Cast sessions ingest the
        // plain stream and AC-3 / E-AC-3 passes through to the receiver, so
        // there is no server-side profile to keep in sync.
    }

    /**
     * ACTIVE PLAYLIST ONLY (Logan 2026-09-16). Warming a saved-but-inactive
     * playlist logged this device into a server nothing was about to call,
     * and it paired with the capability probe to make every foreground a
     * fan-out across every server the user has ever saved. An inactive row's
     * tokens are minted when it is made active.
     */
    private suspend fun warmupAll() {
        val playlist = dao.firstActive() ?: return
        if (playlist.sourceType != SourceType.DispatcharrApiKey.name &&
            playlist.sourceType != SourceType.DispatcharrUserPass.name
        ) return
        warmup(playlist)
    }

    private suspend fun warmup(playlist: PlaylistEntity) {
        // Phase 1: try refresh. Only meaningful when a refresh token is
        // cached — that happens after a successful login during this same
        // process run, OR a previous warmup that ran login.
        tokenStore.refreshToken(playlist.id)?.let { refresh ->
            try {
                val newAccess = client.refreshAccessToken(playlist.urlString, refresh)
                tokenStore.storeRefreshedAccess(playlist.id, newAccess)
                Log.i(TAG, "refresh OK for ${playlist.id.take(8)}")
                return
            } catch (e: DispatcharrError.RefreshExpired) {
                tokenStore.clear(playlist.id)
                Log.i(TAG, "refresh expired for ${playlist.id.take(8)}; falling back to login")
            } catch (t: Throwable) {
                Log.w(TAG, "refresh failed for ${playlist.id.take(8)}: ${t.message}")
            }
        }

        // Phase 2: fresh login. Only meaningful for UserPass-mode playlists;
        // ApiKey-mode rows have no password saved (matching iOS, which gates
        // the login phase behind a "has password in Keychain" check).
        val username = playlist.username?.takeIf { it.isNotBlank() } ?: return
        val password = playlist.password?.takeIf { it.isNotBlank() } ?: return
        try {
            val pair = client.login(playlist.urlString, username, password)
            tokenStore.store(playlist.id, pair.access, pair.refresh)
            Log.i(TAG, "login OK for ${playlist.id.take(8)}")
        } catch (t: Throwable) {
            Log.w(TAG, "login failed for ${playlist.id.take(8)}: ${t.message}")
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = System.currentTimeMillis()
    }

    private companion object {
        const val TAG = "DispatcharrWarmup"

        /** Away this long and the next foreground re-reads permissions. */
        const val FOREGROUND_REPROBE_MS = 60_000L
    }
}
