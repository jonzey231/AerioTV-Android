package com.aeriotv.android.core.data.db.dao

import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.profiles.ProfileScope
import com.aeriotv.android.core.security.CredentialCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Transparent encrypt-at-rest decorator over the Room-generated [PlaylistDao]
 * (audit task #53). The three credential columns -- apiKey, username, password --
 * are encrypted by [CredentialCipher] on the way IN (every write) and decrypted
 * on the way OUT (every read), so the ~275 consumer sites keep reading
 * `entity.apiKey` as cleartext with zero changes.
 *
 * This is wired as the ONLY binding for [PlaylistDao] in DatabaseModule, and
 * `AerioDatabase.playlistDao()` is referenced nowhere else, so every injection
 * point -- repository, ViewModels, Drive sync, DVR, Coil cache plumbing -- goes
 * through here. Consumers therefore never see ciphertext, which is the invariant
 * that lets [CredentialCipher.encrypt] safely assume its input is cleartext.
 *
 * The three Room `@Transaction` default methods (applyDisplayOrder, switchActive,
 * upsertAsActive) are DELEGATED to the real DAO rather than re-implemented, so
 * Room's generated transaction wrapping is preserved -- re-implementing their
 * bodies here would run the steps outside any transaction and break the
 * single-active-row invariant.
 */
class EncryptingPlaylistDao(
    private val delegate: PlaylistDao,
    private val cipher: CredentialCipher,
) : PlaylistDao {

    private fun PlaylistEntity.encrypted(): PlaylistEntity = copy(
        apiKey = cipher.encrypt(apiKey),
        username = cipher.encrypt(username),
        password = cipher.encrypt(password),
    )

    private fun PlaylistEntity.decrypted(): PlaylistEntity = copy(
        apiKey = decryptCached(apiKey),
        username = decryptCached(username),
        password = decryptCached(password),
    ).overlaid()

    // --- Profiles (2026-10-16) ---
    // A non-default active profile signs in as a different Dispatcharr user.
    // Its per-user columns are overlaid on every read so the ~275 consumers
    // (fetches, auth broker, capability gating, VOD/DVR cache identities)
    // act as that user with no changes. Writes divert those columns into the
    // profile record so the playlist row keeps the default login. Under
    // ProfileScope.defaultOnly() (Drive sync) reads and writes are raw.
    private fun PlaylistEntity.overlaid(): PlaylistEntity {
        val o = ProfileScope.overlayFor(id) ?: return this
        return copy(
            apiKey = o.apiKey,
            username = o.username,
            password = o.password,
            dispatcharrUserLevel = o.userLevel,
            dispatcharrDvrAccess = o.dvrAccess,
            dispatcharrCatchupEnabled = o.catchupEnabled,
            dispatcharrVodMoviesEnabled = o.vodMoviesEnabled,
            dispatcharrVodSeriesEnabled = o.vodSeriesEnabled,
            dispatcharrAccountProfileIds = o.accountProfileIds,
            dispatcharrIsStaff = o.isStaff,
            dispatcharrIsSuperuser = o.isSuperuser,
            dispatcharrCustomProperties = o.customProperties,
            dispatcharrCapabilitiesFetchedAt = o.capabilitiesFetchedAt,
            dispatcharrCapabilitiesSchema = o.capabilitiesSchema,
            dispatcharrCapabilitiesStale = o.capabilitiesStale,
        )
    }

    /** Move the per-user columns of [incoming] into the active profile and
     *  restore the stored (default profile) values on the row to write. */
    private suspend fun diverted(incoming: PlaylistEntity): PlaylistEntity {
        val overlay = ProfileScope.overlayFor(incoming.id)
        if (overlay == null) {
            // A row snapshot read while a non-default profile was active,
            // written after the switch back: never let that profile's login
            // replace the playlist's own.
            if (!ProfileScope.isForcedDefault() &&
                ProfileScope.isNonDefaultProfileUser(incoming.id, incoming.username)
            ) {
                return restoreRaw(incoming)
            }
            return incoming
        }
        // A stale snapshot of ANOTHER identity (read before the switch) must
        // not leak into this profile either: only adopt same-user writes.
        if (incoming.username != overlay.username) return restoreRaw(incoming)
        ProfileScope.updateOverlay {
            it.copy(
                apiKey = incoming.apiKey,
                password = incoming.password,
                userLevel = incoming.dispatcharrUserLevel,
                dvrAccess = incoming.dispatcharrDvrAccess,
                catchupEnabled = incoming.dispatcharrCatchupEnabled,
                vodMoviesEnabled = incoming.dispatcharrVodMoviesEnabled,
                vodSeriesEnabled = incoming.dispatcharrVodSeriesEnabled,
                accountProfileIds = incoming.dispatcharrAccountProfileIds,
                isStaff = incoming.dispatcharrIsStaff,
                isSuperuser = incoming.dispatcharrIsSuperuser,
                customProperties = incoming.dispatcharrCustomProperties,
                capabilitiesFetchedAt = incoming.dispatcharrCapabilitiesFetchedAt,
                capabilitiesSchema = incoming.dispatcharrCapabilitiesSchema,
                capabilitiesStale = incoming.dispatcharrCapabilitiesStale,
            )
        }
        return restoreRaw(incoming)
    }

    private suspend fun restoreRaw(incoming: PlaylistEntity): PlaylistEntity {
        val raw = delegate.byId(incoming.id)?.let {
            withContext(Dispatchers.Default) {
                it.copy(
                    apiKey = decryptCached(it.apiKey),
                    username = decryptCached(it.username),
                    password = decryptCached(it.password),
                )
            }
        } ?: return incoming
        return incoming.copy(
            apiKey = raw.apiKey,
            username = raw.username,
            password = raw.password,
            dispatcharrUserLevel = raw.dispatcharrUserLevel,
            dispatcharrDvrAccess = raw.dispatcharrDvrAccess,
            dispatcharrCatchupEnabled = raw.dispatcharrCatchupEnabled,
            dispatcharrVodMoviesEnabled = raw.dispatcharrVodMoviesEnabled,
            dispatcharrVodSeriesEnabled = raw.dispatcharrVodSeriesEnabled,
            dispatcharrAccountProfileIds = raw.dispatcharrAccountProfileIds,
            dispatcharrIsStaff = raw.dispatcharrIsStaff,
            dispatcharrIsSuperuser = raw.dispatcharrIsSuperuser,
            dispatcharrCustomProperties = raw.dispatcharrCustomProperties,
            dispatcharrCapabilitiesFetchedAt = raw.dispatcharrCapabilitiesFetchedAt,
            dispatcharrCapabilitiesSchema = raw.dispatcharrCapabilitiesSchema,
            dispatcharrCapabilitiesStale = raw.dispatcharrCapabilitiesStale,
            // Server-wide fields: another profile's refresh (its own, usually
            // smaller, channel list) must not overwrite the row's count or
            // last-refreshed time (2026-10-27).
            channelCount = raw.channelCount,
            lastRefreshedAt = raw.lastRefreshedAt,
        )
    }

    /** The stored row with no profile overlay (the default profile's login). */
    suspend fun byIdDefaultProfile(id: String): PlaylistEntity? =
        withContext(ProfileScope.defaultOnly()) { byId(id) }

    // Decrypt memo keyed on the stored ciphertext (perf 2026-10-03). There is
    // no updated-at column, but every write re-encrypts with a fresh GCM IV,
    // so an unchanged ciphertext is exactly an unchanged credential and a
    // changed one misses the memo. Saves a Keystore round trip per column on
    // every repeat read of the same row. Plaintext already lives in every
    // consumer's entity, so the memo holds nothing new; bounded anyway.
    private val plainByCipher = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun decryptCached(value: String?): String? {
        if (value.isNullOrEmpty()) return cipher.decrypt(value)
        plainByCipher[value]?.let { return it }
        val plain = cipher.decrypt(value) ?: return null
        if (plainByCipher.size >= DECRYPT_MEMO_CAP) plainByCipher.clear()
        plainByCipher[value] = plain
        return plain
    }

    // --- reads: decrypt on the way out ---

    // flowOn(Default): the credential decrypt is AES-GCM via the AndroidKeystore
    // (a TEE round-trip, not free). These Flows are collected on the main thread
    // (Compose), so without flowOn every playlists-table change would run the
    // decrypt on the main thread and risk a dropped-frame hitch. flowOn shifts
    // the upstream query + decrypt to a background dispatcher; the collector
    // still receives on main.
    // Profiles: re-emit on a profile switch (same rows, new overlay) so the
    // tab set and capability gates recompute the moment the switch lands.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeActive(): Flow<List<PlaylistEntity>> =
        ProfileScope.token.flatMapLatest { delegate.observeActive() }.map { list -> list.map { it.decrypted() } }
            .flowOn(Dispatchers.Default)

    // The one-shot reads are called from main-thread coroutines
    // (OnDemandViewModel, DvrViewModel, LiveStreamFailover, PlaylistRepository,
    // DispatcharrAuthBroker); Room moves the query off main by itself but the
    // decrypt ran on the caller's thread. Same Default hop as the flows above.
    override suspend fun firstActive(): PlaylistEntity? =
        delegate.firstActive()?.let { withContext(Dispatchers.Default) { it.decrypted() } }

    override suspend fun byId(id: String): PlaylistEntity? =
        delegate.byId(id)?.let { withContext(Dispatchers.Default) { it.decrypted() } }

    override suspend fun allOnce(): List<PlaylistEntity> =
        delegate.allOnce().let { rows -> withContext(Dispatchers.Default) { rows.map { it.decrypted() } } }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeAll(): Flow<List<PlaylistEntity>> =
        ProfileScope.token.flatMapLatest { delegate.observeAll() }.map { list -> list.map { it.decrypted() } }
            .flowOn(Dispatchers.Default)

    // --- writes: encrypt on the way in ---

    override suspend fun upsert(playlist: PlaylistEntity) = delegate.upsert(diverted(playlist).encrypted())

    override suspend fun update(playlist: PlaylistEntity) = delegate.update(diverted(playlist).encrypted())

    override suspend fun updateCredentials(
        id: String,
        apiKey: String?,
        username: String?,
        password: String?,
    ) {
        // A non-default profile's credentials never land on the playlist row.
        if (ProfileScope.overlayFor(id) != null) {
            ProfileScope.updateOverlay { it.copy(apiKey = apiKey, password = password) }
            return
        }
        delegate.updateCredentials(
        id,
        cipher.encrypt(apiKey),
        cipher.encrypt(username),
        cipher.encrypt(password),
        )
    }

    // No credential columns involved, so it passes straight through.
    override suspend fun updateChannelCount(id: String, count: Int) =
        delegate.updateChannelCount(id, count)

    override suspend fun updateLastEpgRefreshedAt(id: String, at: Long) =
        delegate.updateLastEpgRefreshedAt(id, at)

    override suspend fun updateEpgSourceFingerprint(id: String, fingerprint: String?) =
        delegate.updateEpgSourceFingerprint(id, fingerprint)

    override suspend fun updateCastAacProfileId(id: String, profileId: Int?) =
        delegate.updateCastAacProfileId(id, profileId)

    override suspend fun upsertAsActive(entity: PlaylistEntity) =
        delegate.upsertAsActive(diverted(entity).encrypted())

    // --- @Delete matches by primary key only; credential columns are ignored ---

    override suspend fun delete(playlist: PlaylistEntity) = delegate.delete(playlist)

    // --- pass-through: no credential columns touched ---

    override suspend fun setDisplayOrder(id: String, order: Int) =
        delegate.setDisplayOrder(id, order)

    override suspend fun applyDisplayOrder(orderedIds: List<String>) =
        delegate.applyDisplayOrder(orderedIds)

    override suspend fun switchActive(targetId: String) = delegate.switchActive(targetId)

    override suspend fun setAllInactive() = delegate.setAllInactive()

    override suspend fun setActiveById(id: String) = delegate.setActiveById(id)

    override suspend fun deleteById(id: String) = delegate.deleteById(id)

    override suspend fun clear() = delegate.clear()
}

private const val DECRYPT_MEMO_CAP = 64
