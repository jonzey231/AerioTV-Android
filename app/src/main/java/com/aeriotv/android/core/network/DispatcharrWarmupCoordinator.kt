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
        scope.launch { warmupAll() }
        // Cast audio: re-resolve the stereo AAC output profile at every launch
        // and every foreground return, independent of the EPG load (the cached
        // EPG path used to skip it, so a relaunch kept casting with a stale
        // profile id). Rate limited to once per 15 minutes per playlist inside
        // the repository, so a user flipping in and out of the app costs one
        // lookup.
        scope.launch {
            val trigger = if (firstStart) "launch" else "foreground"
            firstStart = false
            runCatching { playlistRepository.get().refreshCastAacProfilesIfDue(trigger) }
                .onFailure { Log.w(TAG, "cast profile re-resolve failed: ${it.message}") }
        }
    }

    private var firstStart = true

    private suspend fun warmupAll() {
        val playlists = dao.allOnce()
        for (playlist in playlists) {
            if (playlist.sourceType != SourceType.DispatcharrApiKey.name &&
                playlist.sourceType != SourceType.DispatcharrUserPass.name
            ) continue
            warmup(playlist)
        }
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

    private companion object {
        const val TAG = "DispatcharrWarmup"
    }
}
