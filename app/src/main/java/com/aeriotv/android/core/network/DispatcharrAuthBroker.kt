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
    init {
        // Stream opens hand their 401s here (StreamGate, Logan 2026-11-10).
        com.aeriotv.android.core.playback.StreamAuth.handler = ::handleStreamUnauthorized
    }

    /**
     * A stream open (live, on demand, DVR, catch-up, Live Rewind, Multiview,
     * Cast ingest, recording) was answered 401 with [usedKey]. The same steps
     * as an API call's 401, once for every stream that hits it together
     * (StreamAuth shares the call): a key already replaced or held is
     * answered from memory; otherwise one probe; a rejected key is re-signed
     * in when the playlist stores a password (the stream retries with the new
     * key), else held, with the "Sign-in not accepted" prompt. A key the probe
     * accepts (or cannot judge) leaves the 401 to the stream's own handling.
     */
    suspend fun handleStreamUnauthorized(usedKey: String): com.aeriotv.android.core.playback.StreamAuth.Outcome {
        val key = usedKey.trim()
        if (DispatcharrRequestGate.isKeyRejected(key)) return com.aeriotv.android.core.playback.StreamAuth.Outcome.Held
        DispatcharrRequestGate.replacementFor(key)?.takeIf { !DispatcharrRequestGate.isKeyRejected(it) }?.let { return com.aeriotv.android.core.playback.StreamAuth.Outcome.Retry(it) }
        val row = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            dao.allOnce().firstOrNull { it.apiKey?.trim() == key }
        }
        if (row == null) {
            // No playlist holds this key any more (an edit replaced it): never send it again.
            Log.i(TAG, "[AUTH] stream 401 with a key no playlist holds now; stripping it")
            return com.aeriotv.android.core.playback.StreamAuth.Outcome.Held
        }
        val probe = client.probeApiKey(row.urlString, key)
        if (probe !is DispatcharrClient.KeyProbe.Rejected) {
            Log.i(TAG, "[AUTH] stream 401 for ${row.id.take(8)}: key probe ${probe::class.simpleName}; the stream's own error stands")
            return com.aeriotv.android.core.playback.StreamAuth.Outcome.NotKey
        }
        val hasPassword = !row.password.isNullOrBlank() && !row.username.isNullOrBlank()
        val fresh = runCatching { silentRebootstrapApiKey(row, refusedKey = key) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()
            ?.trim()
        // The re-login's own key came back and is the one just refused: nobody
        // gets it again for the window, and it is held below (2026-11-12).
        val verified = fresh != null && DispatcharrLoginGate.keyVerified(loginSlot(row), fresh)
        if (fresh != null && fresh == key && !verified) DispatcharrLoginGate.noteKeyRefused(loginSlot(row))
        if (fresh != null && (fresh != key || verified) && !DispatcharrRequestGate.isKeyRejected(fresh)) {
            Log.i(TAG, "[AUTH] stream 401 for ${row.id.take(8)}: re-signed in; the stream retries with the new key")
            return com.aeriotv.android.core.playback.StreamAuth.Outcome.Retry(fresh)
        }
        val noKey = DispatcharrLoginGate.accountHasNoKey(loginSlot(row))
        // The password signed in when only the key it issued was refused: the prompt names the key.
        val keyRefused = DispatcharrLoginGate.foundRefusedKey(loginSlot(row))
        gateRejectedKey(row, key, passwordToo = hasPassword && !keyRefused, noApiKey = noKey)
        Log.w(
            TAG,
            "[AUTH] stream 401 for ${row.id.take(8)}: key rejected" +
                (if (noKey) ", re-sign-in found no API key" else if (keyRefused) ", the key a re-sign-in issued was refused too" else if (hasPassword) ", re-sign-in refused" else "") +
                "; held, stream stopped",
        )
        return com.aeriotv.android.core.playback.StreamAuth.Outcome.Held
    }

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
        // Per identity this read carries (a default-only caller reads the
        // playlist's own row even while a profile is installed): one
        // identity's cached key never serves another (2026-11-12).
        val token = com.aeriotv.android.core.profiles.ProfileScope.readScope(playlistId)
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
        // Known rejected and unrecoverable: fail here, before any request
        // (DispatcharrRequestGate). Every looping caller (the playback status
        // poll every 12 s, the DVR poll, VOD lanes, EPG) lands here.
        if (DispatcharrRequestGate.isKeyRejected(initialKey)) throw DispatcharrError.KeyRejected()
        return try {
            block(initialKey)
        } catch (e: DispatcharrError.Unauthorized) {
            val hasPassword = !playlist.password.isNullOrBlank() && !playlist.username.isNullOrBlank()
            recoverFromUnauthorized(
                tag = playlistId.take(8),
                initialKey = initialKey,
                hasPassword = hasPassword,
                e = e,
                ops = KeyRecoveryOps(
                    block = block,
                    currentKeyIfReplaced = { currentKeyIfReplaced(playlistId, initialKey) },
                    probe = { key -> client.probeApiKey(playlist.urlString, key) },
                    persistModeIfChanged = { mode -> persistAuthModeIfChanged(playlist, mode) },
                    relogin = { silentRebootstrapApiKey(playlist, refusedKey = initialKey) },
                    loginRejected = { DispatcharrLoginGate.isRejected(loginSlot(playlist)) },
                    accountHasNoKey = { DispatcharrLoginGate.accountHasNoKey(loginSlot(playlist)) },
                    keyRefused = { DispatcharrLoginGate.foundRefusedKey(loginSlot(playlist)) },
                    noteKeyRefused = { DispatcharrLoginGate.noteKeyRefused(loginSlot(playlist)) },
                    keyVerified = { key -> DispatcharrLoginGate.keyVerified(loginSlot(playlist), key) },
                    hold = { key, passwordToo, noApiKey -> gateRejectedKey(playlist, key, passwordToo, noApiKey) },
                    invalidate = { invalidateRow(playlistId) },
                    log = { warn, line -> if (warn) Log.w(TAG, line) else Log.i(TAG, line) },
                ),
            )
        }
    }

    /** The playlist's key now, when it is no longer [usedKey] (and not itself held), else null. */
    private suspend fun currentKeyIfReplaced(playlistId: String, usedKey: String): String? {
        val stored = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dao.byId(playlistId) }
            ?.apiKey?.trim()?.takeIf { it.isNotEmpty() }
        val current = stored ?: DispatcharrRequestGate.replacementFor(usedKey)
        return currentKeyAfterSwap(usedKey, current, DispatcharrRequestGate::isKeyRejected)
    }

    private fun gateRejectedKey(playlist: PlaylistEntity, key: String, passwordToo: Boolean, noApiKey: Boolean = false) {
        // Held for the identity the key is (the playlist's own, or a profile's).
        val profileId = com.aeriotv.android.core.profiles.ProfileScope.signInIdentity(playlist.id, playlist.username)
        if (DispatcharrRequestGate.markKeyRejected(playlist.id, key, playlist.name, passwordToo, baseUrl = playlist.urlString, noApiKey = noApiKey, profileId = profileId)) {
            Log.w(
                TAG,
                "[AUTH] API key for ${playlist.id.take(8)} rejected by the server; holding every API call " +
                    "for this playlist locally (no network) until Edit Playlist changes the key or credentials",
            )
        }
    }

    /**
     * Persist a header shape the probe found when it differs from what the
     * playlist row records. Returns the mode only when it is a CHANGE worth
     * replaying the original call for.
     */
    private suspend fun persistAuthModeIfChanged(playlist: PlaylistEntity, detected: String): String? {
        val previous = playlist.dispatcharrAuthMode.ifBlank { AUTH_MODE_BOTH }
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
    suspend fun silentRebootstrapApiKey(
        playlist: PlaylistEntity,
        /** The key the caller just had refused: a shared result with this same key is not handed back (DispatcharrLoginGate rule 8). */
        refusedKey: String? = null,
    ): String? {
        val username = playlist.username?.takeIf { it.isNotBlank() }
        val password = playlist.password?.takeIf { it.isNotBlank() }
        if (username == null || password == null) {
            Log.i(TAG, "silentRebootstrap SKIP: playlist ${playlist.id.take(8)} has no creds (API Key mode)")
            return null
        }
        // A deleted playlist signs in nowhere (playlist delete cutoff).
        if (com.aeriotv.android.core.data.repository.DeletedPlaylists.isDeleted(playlist.id)) return null
        // The saved password goes only to a proven address (StoredSecrets):
        // never to a new server address the same-server check has not
        // passed. The row is read again: the caller's copy can predate the
        // address change.
        val target = (dao.byId(playlist.id) ?: playlist).urlString
        if (!com.aeriotv.android.core.profiles.StoredSecrets.check(playlist.id, target, com.aeriotv.android.core.profiles.StoredSecrets.Path.SilentRelogin)) return null
        // One attempt per identity per 60 s, shared by concurrent callers
        // signing in as the same user; 429 and rejected logins park
        // (DispatcharrLoginGate). A non-default profile re-logs in only with
        // its own stored password, never the playlist's.
        return DispatcharrLoginGate.silentRelogin(loginSlot(playlist), refusedKey) {
            rebootstrapOnce(playlist, username, password)
        }
    }

    /** The re-login slot for [row]: the identity its sign-in belongs to (DispatcharrLoginGate.slotFor). */
    fun loginSlot(row: PlaylistEntity): String =
        DispatcharrLoginGate.slotFor(row.id, com.aeriotv.android.core.profiles.ProfileScope.signInIdentity(row.id, row.username))

    private suspend fun rebootstrapOnce(
        playlist: PlaylistEntity,
        username: String,
        password: String,
    ): String? {
        return try {
            // Re-read the row: a caller that waited may hold a stale copy. No
            // row: the playlist was deleted, and nothing is sent for it.
            val row = dao.byId(playlist.id) ?: return null
            // A profile switch since the caller read its row: the row now
            // carries another identity, and that identity's key must never be
            // replaced with this account's (2026-11-12, security).
            if (!row.username.orEmpty().trim().equals(username.trim(), ignoreCase = true)) {
                Log.i(TAG, "silentRebootstrap SKIP for ${playlist.id.take(8)}: the active profile changed since the call started")
                return null
            }
            // The address may have moved while this waited (StoredSecrets).
            if (!com.aeriotv.android.core.profiles.StoredSecrets.check(row.id, row.urlString, com.aeriotv.android.core.profiles.StoredSecrets.Path.SilentRelogin)) return null
            val pair = client.login(row.urlString, username, password)
            val minted = client.fetchCurrentUserApiKey(row.urlString, pair.access)
            // EXACTLY one request checks the key before anything stores or
            // sends it (/api/core/version/ in the playlist's header shape, past
            // the held-key gate). Refused: the re-login ends as key refused
            // (DispatcharrLoginGate parks the identity, every waiter gets no
            // key, the caller holds the old key). No answer is not a refusal.
            val proof = client.probeApiKeyOnce(row.urlString, minted)
            val newKey = reloginOutcome(minted, proof)
            if (proof is DispatcharrClient.KeyProbe.Accepted) {
                DispatcharrRequestGate.clearKey(newKey)
                // It passed its check: if it is refused later, the server rotated it (a fresh re-login, not a park).
                DispatcharrLoginGate.noteKeyVerified(loginSlot(row), newKey)
            }
            tokenStore.store(row.id, pair.access, pair.refresh)
            // The startup login runs this too (one login per launch): an
            // unchanged key writes nothing, so a launch never looks like a
            // sign-in change to sync.
            if (reloginChangesKey(row.apiKey, newKey)) {
                dao.update(row.copy(apiKey = newKey))
                // Sync: the re-issued key is this playlist's newest sign-in
                // change here (a profile's own key is not the playlist's).
                if (com.aeriotv.android.core.profiles.ProfileScope.signInIdentity(row.id, row.username) == null) {
                    com.aeriotv.android.core.sync.SyncStamps.touched(com.aeriotv.android.core.sync.SyncStamps.Cat.Credentials, row.id)
                }
                // Requests already built with the old key go out with the new one.
                row.apiKey?.let { DispatcharrRequestGate.noteKeyReplaced(it, newKey) }
                invalidateRow(row.id)
                Log.i(TAG, "silentRebootstrap OK for ${playlist.id.take(8)}: the account's key changed; saved")
            } else {
                Log.i(TAG, "silentRebootstrap OK for ${playlist.id.take(8)}: key unchanged")
            }
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
        } catch (e: DispatcharrError.NoApiKey) {
            Log.w(TAG, "silentRebootstrap for ${playlist.id.take(8)}: signed in, but the account has no API key")
            throw e // the gate records it; the caller holds the key
        } catch (e: ReloginKeyRefused) {
            Log.w(TAG, "silentRebootstrap for ${playlist.id.take(8)}: signed in, but the server refused the key it issued; not stored")
            throw e // the gate parks the identity; the caller holds the old key
        } catch (t: Throwable) {
            Log.w(TAG, "silentRebootstrap FAIL for ${playlist.id.take(8)}: ${t.message}")
            null
        }
    }

    private companion object {
        const val TAG = "DispatcharrAuthBroker"
    }
}

/** What a failed silent re-login means for the key that was refused. */
internal enum class ReloginHold {
    /** Not known to be dead: leave the 401 to the caller. */
    None,
    /** The stored password was refused too. */
    PasswordRefused,
    /** The re-login signed in, but the account has no API key: every key is dead. */
    NoApiKey,
    /** The re-login signed in, but the server refused the key it issued (2026-11-12). */
    KeyRefused,
}

/**
 * Pure rule after a re-login returned no key: an account with no API key
 * holds the key whatever the probe said (signing in proved the account, and
 * it has no key at all); a refused password holds it when the probe also
 * rejected the key; anything else (a network failure) does not.
 */
internal fun reloginHold(keyRejected: Boolean, loginRejected: Boolean, accountHasNoKey: Boolean, keyRefused: Boolean = false): ReloginHold = when {
    accountHasNoKey -> ReloginHold.NoApiKey
    keyRejected && loginRejected -> ReloginHold.PasswordRefused
    // Signed in, but the key it issued was refused too: re-signing in mints the same refused key.
    keyRejected && keyRefused -> ReloginHold.KeyRefused
    else -> ReloginHold.None
}

/**
 * A re-login signed in and read [minted] from the account; [proof] is the ONE
 * request that checked it. Refused ends the re-login ([ReloginKeyRefused]):
 * the key is not stored, nobody gets it, and the old key is held, so a key
 * the server still refuses never goes out again (2026-11-12: it was never
 * held, and about 15 refused requests went out in a second, tripping
 * Dispatcharr's 10-per-minute IP lockout). No answer (network, 5xx) is not a
 * refusal: the key is used. Apple twin: DispatcharrRelogin.outcome.
 */
internal fun reloginOutcome(minted: String, proof: DispatcharrClient.KeyProbe): String {
    if (proof == DispatcharrClient.KeyProbe.Rejected) throw ReloginKeyRefused()
    return minted
}

/** A re-login's key replaces the stored one only when it differs (blank stored = differs). */
internal fun reloginChangesKey(stored: String?, fromServer: String): Boolean =
    stored?.trim()?.takeIf { it.isNotEmpty() } != fromServer.trim()

/** The side effects [recoverFromUnauthorized] takes, passed in so its request counts are unit tested. */
internal class KeyRecoveryOps<T>(
    /** The caller's request with a key. */
    val block: suspend (String) -> T,
    /** The playlist's key now, when another caller or an edit replaced the one used. */
    val currentKeyIfReplaced: suspend () -> String?,
    /** One key probe (/api/core/version/; concurrent probes of a key share one). */
    val probe: suspend (String) -> DispatcharrClient.KeyProbe,
    /** Persist a header shape the probe found; non-null only when it changed. */
    val persistModeIfChanged: suspend (String) -> String?,
    /** The silent re-login (one per identity per minute, shared; its key checked once: DispatcharrLoginGate). */
    val relogin: suspend () -> String?,
    val loginRejected: () -> Boolean,
    val accountHasNoKey: () -> Boolean,
    /** The identity's last re-login signed in, but the server refused the key it issued. */
    val keyRefused: () -> Boolean,
    /** A handed-out key came back refused: park the identity, nobody gets it again for the window. */
    val noteKeyRefused: () -> Unit,
    /** [key] is the identity's last re-login result and passed its check when issued. */
    val keyVerified: (String) -> Boolean = { false },
    /** Hold [key] for this playlist and identity ([DispatcharrRequestGate.markKeyRejected]). */
    val hold: (key: String, passwordToo: Boolean, noApiKey: Boolean) -> Unit,
    val invalidate: () -> Unit,
    val log: (warn: Boolean, line: String) -> Unit,
)

/**
 * What an API-key call does after a 401 ([DispatcharrAuthBroker.withApiKeyRetry]):
 * a replaced key is retried; otherwise one probe tells a header-shape or
 * endpoint 401 from a refused key, then the silent re-login. The re-login
 * checks the key it reads with one request before anything stores or sends
 * it, so a key the server refuses never comes back from it; a shared key that
 * comes back as the very key just refused is marked the same way. Either way
 * the old key is held (2026-11-12, Apple 44c544c): a burst of 401s costs one
 * probe of the old key and one check of the new one, and nothing after that.
 */
internal suspend fun <T> recoverFromUnauthorized(
    tag: String,
    initialKey: String,
    hasPassword: Boolean,
    e: DispatcharrError.Unauthorized,
    ops: KeyRecoveryOps<T>,
): T {
    if (e is DispatcharrError.KeyRejected) throw e
    // A 401 that lands after another caller already proved this key
    // rejected: the hold is in place, so no probe of our own.
    if (DispatcharrRequestGate.isKeyRejected(initialKey)) throw DispatcharrError.KeyRejected()
    ops.invalidate()
    // The key was replaced while this call was in flight (another caller's
    // re-sign-in, or an Edit Playlist save): retry with the current key.
    // Probing or re-signing in for a key that is already gone would spend
    // failed-key attempts for nothing.
    ops.currentKeyIfReplaced()?.let { current ->
        ops.log(false, "401 for $tag used a replaced key; retrying with the current one (no probe)")
        return ops.block(current)
    }
    // Task #49 (iOS verifyConnection v1.6.20 parity): before assuming the KEY
    // was rotated, check whether the 401 is a header-SHAPE rejection -- some
    // deployments (reverse proxies, hardened builds) reject
    // `Authorization: ApiKey` or require it, with a perfectly valid key. The
    // probe also tells a rejected KEY apart from an endpoint-specific 401/403
    // for a key that still works, and it stops at Dispatcharr's "Invalid API
    // key" answer. Concurrent 401s for the same key share one probe
    // (DispatcharrClient.probeApiKey is single-flight per key).
    val probe = ops.probe(initialKey)
    if (probe is DispatcharrClient.KeyProbe.Accepted) {
        val detected = ops.persistModeIfChanged(probe.mode)
        if (detected != null) {
            ops.log(false, "401 for $tag was a header-shape rejection; retrying with mode=$detected")
            return ops.block(initialKey)
        }
    }
    val keyRejected = probe is DispatcharrClient.KeyProbe.Rejected
    ops.log(false, "401 on apiKey call for $tag (key probe: ${probe::class.simpleName}); attempting silent rebootstrap")
    var freshKey = ops.relogin()?.trim()?.takeIf { it.isNotEmpty() }
    if (freshKey != null && keyRejected && freshKey == initialKey.trim() && !ops.keyVerified(freshKey)) {
        // The re-login's key came back and is the one just refused, and it
        // never passed a check: nobody gets it again for the window; held
        // below. (A key that passed its check and was rotated since gets a
        // fresh re-login inside the gate instead: DispatcharrLoginGate rule 8.)
        ops.noteKeyRefused()
        freshKey = null
    }
    if (freshKey == null && !hasPassword) {
        // API-Key-mode playlists have no credentials to re-mint from, so a
        // rejected key is terminal until the user replaces it. Retrying it
        // fed the server's failed-key throttle (10 per minute per IP) every
        // 12 s until every API-key request from the network got 429
        // (2026-11-09). Hold it locally until Edit Playlist changes something.
        ops.log(
            true,
            "silent rebootstrap returned null for $tag: API-Key-mode playlist with a rejected key has nothing " +
                "to re-mint from. The user must enter a new key in Edit Playlist. Surfacing the 401.",
        )
        if (keyRejected) {
            ops.hold(initialKey, false, false)
            throw DispatcharrError.KeyRejected()
        }
        throw e
    }
    if (freshKey == null) {
        // A password playlist whose re-login was itself refused
        // (DispatcharrLoginGate parks it), signed in to an account with no
        // API key (removed on the server; 2026-11-11), or signed in but was
        // issued a key the server refuses too (2026-11-12): the same hold, or
        // every caller would keep sending the dead key, which counts toward
        // Dispatcharr's failed-key lockout for the whole network. The held
        // key's re-check is then one re-login now and then.
        when (reloginHold(keyRejected, ops.loginRejected(), ops.accountHasNoKey(), ops.keyRefused())) {
            ReloginHold.NoApiKey -> ops.hold(initialKey, true, true)
            ReloginHold.PasswordRefused -> ops.hold(initialKey, true, false)
            // The password signed in; the key it issued was refused: the prompt names the key.
            ReloginHold.KeyRefused -> ops.hold(initialKey, false, false)
            ReloginHold.None -> throw e
        }
        throw DispatcharrError.KeyRejected()
    }
    return ops.block(freshKey)
}
