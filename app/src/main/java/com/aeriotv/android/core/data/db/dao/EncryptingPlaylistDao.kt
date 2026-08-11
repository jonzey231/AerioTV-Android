package com.aeriotv.android.core.data.db.dao

import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.security.CredentialCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

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
        apiKey = cipher.decrypt(apiKey),
        username = cipher.decrypt(username),
        password = cipher.decrypt(password),
    )

    // --- reads: decrypt on the way out ---

    // flowOn(Default): the credential decrypt is AES-GCM via the AndroidKeystore
    // (a TEE round-trip, not free). These Flows are collected on the main thread
    // (Compose), so without flowOn every playlists-table change would run the
    // decrypt on the main thread and risk a dropped-frame hitch. flowOn shifts
    // the upstream query + decrypt to a background dispatcher; the collector
    // still receives on main.
    override fun observeActive(): Flow<List<PlaylistEntity>> =
        delegate.observeActive().map { list -> list.map { it.decrypted() } }
            .flowOn(Dispatchers.Default)

    override suspend fun firstActive(): PlaylistEntity? = delegate.firstActive()?.decrypted()

    override suspend fun byId(id: String): PlaylistEntity? = delegate.byId(id)?.decrypted()

    override suspend fun allOnce(): List<PlaylistEntity> = delegate.allOnce().map { it.decrypted() }

    override fun observeAll(): Flow<List<PlaylistEntity>> =
        delegate.observeAll().map { list -> list.map { it.decrypted() } }
            .flowOn(Dispatchers.Default)

    // --- writes: encrypt on the way in ---

    override suspend fun upsert(playlist: PlaylistEntity) = delegate.upsert(playlist.encrypted())

    override suspend fun update(playlist: PlaylistEntity) = delegate.update(playlist.encrypted())

    override suspend fun updateCredentials(
        id: String,
        apiKey: String?,
        username: String?,
        password: String?,
    ) = delegate.updateCredentials(
        id,
        cipher.encrypt(apiKey),
        cipher.encrypt(username),
        cipher.encrypt(password),
    )

    // No credential columns involved, so it passes straight through.
    override suspend fun updateChannelCount(id: String, count: Int) =
        delegate.updateChannelCount(id, count)

    override suspend fun upsertAsActive(entity: PlaylistEntity) =
        delegate.upsertAsActive(entity.encrypted())

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
