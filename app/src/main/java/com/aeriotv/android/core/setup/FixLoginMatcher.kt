package com.aeriotv.android.core.setup

import com.aeriotv.android.core.network.DispatcharrRequestGate
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.core.sync.SyncStamps

/**
 * This phone's side of the Fix Login card rule ([FixLogin.matchPlaylist]):
 * for each Fix Login advert in [targets], the playlist here whose sign-in it
 * may send, keyed by [identity]. Adverts with no match are left out (no
 * card). Reads the playlists' default-profile rows; never logs a key.
 */
suspend fun fixLoginMatches(targets: List<SetupTarget>, profiles: ProfileManager): Map<String, String> {
    val fix = targets.filter { it.isFixLogin }
    if (fix.isEmpty()) return emptyMap()
    val locals = profiles.defaultPlaylistRows().map { r ->
        val key = r.apiKey?.trim()?.takeIf { it.isNotEmpty() }
        FixLogin.Local(
            id = r.id,
            isDispatcharr = r.sourceType.startsWith("Dispatcharr"),
            account = r.username?.takeIf { it.isNotBlank() }
                ?: profiles.setupSources(r.id).firstOrNull { it.profile.isDefault }?.profile?.username?.takeIf { it.isNotBlank() },
            hasCredential = key != null || !r.password.isNullOrBlank(),
            keyHeld = DispatcharrRequestGate.isPlaylistRejected(r.id) ||
                (key != null && DispatcharrRequestGate.isKeyRejected(key)),
        )
    }
    val out = HashMap<String, String>()
    for (t in fix) {
        val learned = t.playlistId?.let { SyncStamps.learnedAccount(it) }
        FixLogin.matchPlaylist(t.playlistId, locals, learned)?.let { out[t.identity] = it.id }
    }
    return out
}
