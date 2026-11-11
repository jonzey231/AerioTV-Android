package com.aeriotv.android.core.sync

import android.util.Log
import androidx.room.withTransaction
import com.aeriotv.android.core.data.db.AerioDatabase
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.network.DispatcharrRequestGate
import com.aeriotv.android.core.network.DispatcharrTokenStore
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.profiles.ProfileStore
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Carries out [PlaylistIdentity] on this device after a sync pull: playlists
 * that sign in to one server account become one playlist (Logan 2026-11-10).
 * The loser's watch progress, reminders, recordings, profiles, and
 * per-playlist settings move to the winner, the winner keeps a working
 * sign-in, the loser gets a deletion time (so every device converges), and
 * the loser row goes through the normal delete cleanup.
 */
@Singleton
class PlaylistCollapse @Inject constructor(
    private val database: AerioDatabase,
    private val playlistDao: PlaylistDao,
    private val appPreferences: AppPreferences,
    private val profileStore: ProfileStore,
    private val playlistRepository: Provider<com.aeriotv.android.core.data.repository.PlaylistRepository>,
    private val tokenStore: DispatcharrTokenStore,
) {
    private val mutex = Mutex()

    /** Collapses every group of playlists here that share an account. Returns the number of playlists merged away. */
    suspend fun run(why: String): Int = mutex.withLock {
        kotlinx.coroutines.withContext(com.aeriotv.android.core.profiles.ProfileScope.defaultOnly()) {
            val rows = playlistDao.allOnce()
            if (rows.size < 2) return@withContext 0
            val byId = rows.associateBy { it.id }
            val identities = rows.map { r -> PlaylistIdentity.Row(r.id, identityOf(r), SyncStamps.touchedAt(SyncStamps.Cat.Playlists, r.id) ?: 0L, r.createdAt.takeIf { it > 0L }) }
            val groups = PlaylistIdentity.groups(identities)
            // Which rows share an account, without the account: the basis
            // (username, key fingerprint, M3U URL, unknown) and a short hash.
            Log.i(TAG, "[MERGE] identities ($why): " + identities.joinToString(" ") { r ->
                val basis = when {
                    r.identity == null -> "none"
                    r.identity.startsWith("m3u|") -> "m3u"
                    "|u:" in r.identity -> "user"
                    else -> "key"
                }
                "${r.id.take(8)}=$basis" + (r.identity?.let { "#" + PlaylistIdentity.keyFingerprint(it).take(6) } ?: "")
            } + " shared=${groups.size}")
            if (groups.isEmpty()) return@withContext 0
            var merged = 0
            for (g in groups) {
                val w = PlaylistIdentity.winner(g)
                for (l in g.filter { it.id != w.id }) {
                    // Re-read both: an earlier merge in this run may have changed them.
                    val winner = playlistDao.byId(w.id) ?: byId[w.id] ?: continue
                    val loser = playlistDao.byId(l.id) ?: continue
                    runCatching { merge(winner, loser, why) }
                        .onSuccess { merged++ }
                        .onFailure { Log.w(TAG, "[MERGE] ${loser.id.take(8)} -> ${winner.id.take(8)} failed: ${it.javaClass.simpleName}") }
                }
            }
            merged
        }
    }

    /** The account a row signs in as, from what this device knows. */
    private suspend fun identityOf(r: PlaylistEntity): String? {
        val user = r.username?.takeIf { it.isNotBlank() }
            ?: profileStore.forPlaylist(r.id).firstOrNull { it.isDefault }?.username?.takeIf { it.isNotBlank() }
            ?: runCatching { appPreferences.dispatcharrAccountFactsOnce(r.id).username }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: SyncStamps.learnedAccount(r.id)
        // An XC username is its own account; only Dispatcharr learns one for a key.
        val forType = if (PlaylistIdentity.kind(r.sourceType) == PlaylistIdentity.Kind.Xtream) r.username else user
        return PlaylistIdentity.of(r.sourceType, r.urlString, forType, r.apiKey)
    }

    private fun credView(r: PlaylistEntity): PlaylistIdentity.CredView {
        val key = r.apiKey?.takeIf { it.isNotBlank() }
        val has = when (PlaylistIdentity.kind(r.sourceType)) {
            PlaylistIdentity.Kind.M3u -> true
            PlaylistIdentity.Kind.Xtream -> !r.username.isNullOrBlank() && !r.password.isNullOrBlank()
            else -> key != null || (!r.username.isNullOrBlank() && !r.password.isNullOrBlank())
        }
        val rejected = DispatcharrRequestGate.isPlaylistRejected(r.id) ||
            (key != null && (DispatcharrRequestGate.isKeyRejected(key) || SyncStamps.isSuperseded(DispatcharrRequestGate.keyHash(key))))
        return PlaylistIdentity.CredView(
            hasCredential = has,
            rejected = rejected,
            inUseHere = r.isActive,
            changedAt = SyncStamps.touchedAt(SyncStamps.Cat.Credentials, r.id) ?: 0L,
        )
    }

    private suspend fun merge(winner: PlaylistEntity, loser: PlaylistEntity, why: String) {
        val plan = PlaylistIdentity.plan(
            winner = PlaylistIdentity.Row(winner.id, null, 0L),
            loser = PlaylistIdentity.Row(loser.id, null, 0L),
            loserActive = loser.isActive,
            winnerCred = credView(winner),
            loserCred = credView(loser),
            winnerProfiles = profileStore.forPlaylist(winner.id).map { PlaylistIdentity.Profile(it.id, it.username, it.isDefault) },
            loserProfiles = profileStore.forPlaylist(loser.id).map { PlaylistIdentity.Profile(it.id, it.username, it.isDefault) },
        )
        val w = winner.id
        val l = loser.id
        // 1. Rows tied to the playlist (ON DELETE CASCADE would take them with the loser).
        var moved = intArrayOf(0, 0, 0)
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            moved = intArrayOf(
                db.update("watch_progress", 0, android.content.ContentValues().apply { put("playlistId", w) }, "playlistId = ?", arrayOf(l)),
                db.update("reminder", 0, android.content.ContentValues().apply { put("playlistId", w) }, "playlistId = ?", arrayOf(l)),
                db.update("local_recording", 0, android.content.ContentValues().apply { put("playlistId", w) }, "playlistId = ?", arrayOf(l)),
            )
        }
        // 2. A working sign-in is kept: the loser's when it is the working one.
        if (plan.credentials == PlaylistIdentity.CredentialSource.Loser) {
            val bothDispatcharr = PlaylistIdentity.kind(winner.sourceType) == PlaylistIdentity.Kind.Dispatcharr &&
                PlaylistIdentity.kind(loser.sourceType) == PlaylistIdentity.Kind.Dispatcharr
            val oldKey = winner.apiKey?.takeIf { it.isNotBlank() }
            playlistDao.update(
                winner.copy(
                    sourceType = if (bothDispatcharr) loser.sourceType else winner.sourceType,
                    username = loser.username,
                    password = loser.password,
                    apiKey = loser.apiKey,
                ),
            )
            // The key the winner had is set aside for good: never adopted from
            // Drive again, never pushed (it is the rejected or unused one).
            if (oldKey != null && oldKey != loser.apiKey) SyncStamps.supersede(DispatcharrRequestGate.keyHash(oldKey))
            tokenStore.clear(w)
            DispatcharrRequestGate.clearPlaylist(w)
            SyncStamps.touched(SyncStamps.Cat.Credentials, w)
            SyncStamps.touched(SyncStamps.Cat.Playlists, w)
        }
        // 3. Profiles and per-playlist settings.
        profileStore.applyMerge(l, w, plan.profiles.move, plan.profiles.drop, takeActive = loser.isActive)
        val prefsMoved = runCatching { appPreferences.movePlaylistKeys(l, w) }.getOrDefault(0)
        // 4. The loser was the playlist in use here: the winner takes over.
        if (plan.activateWinner) playlistDao.switchActive(w)
        // 5. Deletion time, then the normal delete cleanup (guide, channel
        // cache, VOD catalog, tokens). The winner's sign-in is its own row's,
        // so nothing it uses goes with the loser.
        SyncStamps.removed(SyncStamps.Cat.Playlists, l)
        val inUse = PlaylistMerges.uiBoundId == l
        if (!inUse) playlistRepository.get().deletePlaylist(l).getOrThrow()
        PlaylistMerges.emit(PlaylistMerges.Event(loserId = l, winnerId = w, deferredDelete = inUse))
        Log.i(
            TAG,
            "[MERGE] playlist ${l.take(8)} -> ${w.take(8)} (one account, $why): progress=${moved[0]} reminders=${moved[1]} " +
                "recordings=${moved[2]} settings=$prefsMoved profiles moved=${plan.profiles.move.size} dropped=${plan.profiles.drop.size} " +
                "sign-in=${plan.credentials.name.lowercase()} active=${plan.activateWinner}" +
                if (inUse) " (loser on screen: deleted after the switch)" else "",
        )
    }

    private companion object { const val TAG = "PlaylistCollapse" }
}

/** Playlist merges for the screens: the one showing a merged-away playlist switches to the winner. */
object PlaylistMerges {
    data class Event(val loserId: String, val winnerId: String, val deferredDelete: Boolean)

    /** The playlist the main screen is showing (PlaylistViewModel), or null. */
    @Volatile var uiBoundId: String? = null

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun emit(e: Event) { _events.tryEmit(e) }
}
