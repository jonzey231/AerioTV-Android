package com.aeriotv.android.core.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.bridgeChannelIds
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.core.data.db.dao.FavoriteChannelDao
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.feature.playlist.nowPlaying
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Auto browse tree + playback resolution, mirroring the iOS CarPlay
 * design (App/CarPlaySceneDelegate.swift):
 *
 *   root  (Android Auto renders root children as the horizontal top tabs)
 *     - Favorites                       (tab; only when favorites exist)
 *     - Groups -> <group> -> channels   (always; the sole tab when none)
 *
 * Individual groups live UNDER the Groups tab as a vertical list, never as
 * root children: root children become Android Auto's top tabs, so putting the
 * groups there turned them INTO the tabs (Sports/General/News across the top)
 * instead of the scrollable Group > Channel drill-down CarPlay shows. CarPlay
 * uses a Favorites/Groups CPTabBarTemplate with each group pushed as its own
 * list; one "Groups" tab wrapping the vertical group list is the Android-
 * idiomatic equivalent.
 *
 * Each channel row shows the channel name, the now-playing programme as the
 * subtitle, and the channel logo as artwork -- the same fields CarPlay renders.
 *
 * It is Activity-less by design (Android Auto BINDS the MediaLibraryService
 * with no guaranteed UI). It paints from the Room disk caches
 * ([PlaylistRepository.loadCachedChannels] / [loadCachedEpg]) so a cold
 * car connect works without the phone app ever having run this session; if the
 * cache is empty it falls back to a one-shot network refresh.
 *
 * Playback is audio-only on the car (the service drops the video track); this
 * class only resolves the browse selection into a playable timeline. Channel
 * "flip" (the head unit's next/previous buttons) works because a play request
 * loads the whole sibling list (the selected channel's group, or the full list
 * when it has no group) as the player's timeline, positioned at the pick.
 */
@Singleton
class AutoBrowseTree @Inject constructor(
    private val repository: PlaylistRepository,
    private val favoriteDao: FavoriteChannelDao,
    private val dispatcharrClient: DispatcharrClient,
) {

    /** Result of resolving a browse mediaId into something the player can load. */
    data class PlaybackInfo(
        val items: List<MediaItem>,
        val startIndex: Int,
        val headers: Map<String, String>,
    )

    // ---- Browse tree -------------------------------------------------------

    fun rootItem(): MediaItem = browsable(ROOT_ID, "AerioTV")

    /** Children of a browsable node. [parentId] is always a node id. */
    suspend fun children(parentId: String): List<MediaItem> {
        // No source on this device (fresh install, or a Drive-restored device
        // before the user signs the server in): show a clear, non-playable row
        // rather than a blank car screen. Android Auto's review harness opens
        // a fresh install with no account and checks for exactly this graceful
        // empty state instead of an unexplained empty list. We never bundle a
        // source, so this message is the honest car-side equivalent of the
        // phone's onboarding prompt.
        val playlist = repository.activePlaylist() ?: return listOf(emptyStateItem())
        val channels = loadChannels(playlist)
        if (channels.isEmpty()) return listOf(emptyStateItem())
        val nowByTvg = nowPlayingByChannelKey(playlist)

        return when {
            parentId == ROOT_ID -> {
                val hasFavorites = runCatching { favoriteDao.observeCount().first() }.getOrDefault(0) > 0
                if (hasFavorites) {
                    listOf(
                        browsable(FAVORITES_ID, "Favorites"),
                        browsable(GROUPS_ID, "Groups"),
                    )
                } else {
                    // No favorites: a single "Groups" tab. Its contents (one
                    // level down, via GROUPS_ID) are the vertical group list,
                    // so the groups are a scrollable Group > Channel drill-down
                    // rather than becoming the top tabs themselves. CarPlay
                    // parity: with no favorites, Groups is the whole experience.
                    listOf(browsable(GROUPS_ID, "Groups"))
                }
            }
            parentId == FAVORITES_ID -> {
                val favIds = runCatching { favoriteDao.observeAll().first() }.getOrDefault(emptyList())
                    .map { it.channelId }
                val byId = channels.associateBy { it.id }
                favIds.mapNotNull { byId[it] }.map { channelItem(it, nowByTvg) }
            }
            parentId == GROUPS_ID -> groupNodes(channels)
            parentId.startsWith(GROUP_PREFIX) -> {
                val group = parentId.removePrefix(GROUP_PREFIX)
                channels.filter { it.groupTitle.equals(group, ignoreCase = true) }
                    .map { channelItem(it, nowByTvg) }
            }
            else -> emptyList()
        }
    }

    /** A single item by id (node or channel). */
    suspend fun item(mediaId: String): MediaItem? {
        if (mediaId == ROOT_ID) return rootItem()
        if (mediaId == FAVORITES_ID) return browsable(FAVORITES_ID, "Favorites")
        if (mediaId == GROUPS_ID) return browsable(GROUPS_ID, "Groups")
        if (mediaId.startsWith(GROUP_PREFIX)) {
            return browsable(mediaId, mediaId.removePrefix(GROUP_PREFIX))
        }
        val playlist = repository.activePlaylist() ?: return null
        val channels = loadChannels(playlist)
        val ch = channels.firstOrNull { it.id == mediaId } ?: return null
        return channelItem(ch, nowPlayingByChannelKey(playlist))
    }

    // ---- Playback resolution ----------------------------------------------

    /**
     * Resolve a channel mediaId into a playable timeline. Returns the sibling
     * list (same group, or the whole list when groupless) so the head unit's
     * next/previous flips channels, positioned at the selected channel, plus
     * the auth headers for the active source.
     */
    suspend fun resolveForPlayback(mediaId: String): PlaybackInfo? {
        val playlist = repository.activePlaylist() ?: return null
        val channels = loadChannels(playlist)
        val selected = channels.firstOrNull { it.id == mediaId } ?: return null
        val siblings = if (selected.groupTitle.isBlank()) {
            channels
        } else {
            channels.filter { it.groupTitle.equals(selected.groupTitle, ignoreCase = true) }
        }.ifEmpty { listOf(selected) }

        val nowByTvg = nowPlayingByChannelKey(playlist)
        val base = repository.effectiveBaseUrl(playlist)
        val items = siblings.map { ch -> playableItem(ch, playlist, base, nowByTvg) }
        val startIndex = siblings.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
        return PlaybackInfo(items, startIndex, headersFor(playlist))
    }

    // ---- Helpers -----------------------------------------------------------

    private suspend fun loadChannels(playlist: PlaylistEntity): List<M3UChannel> {
        val cached = repository.loadCachedChannels(playlist.id)
        if (cached.isNotEmpty()) return cached
        // Cold car connect with no cache: one network refresh.
        return repository.refresh(playlist).getOrDefault(emptyList())
    }

    /**
     * Map of channel EPG key (canonical [guideMatchKey]) -> the now-playing
     * programme. Cached programmes get bridged onto each channel's canonical
     * key first so Dispatcharr `/output/epg` (channel-number keyed) and Dummy
     * EPG (UUID keyed) feeds still match a channel whose `tvgID` is blank --
     * Android Auto otherwise shows the channel name with no subtitle for
     * those sources. iOS parity: EPGGuideView.swift lines 1395-1414.
     */
    private suspend fun nowPlayingByChannelKey(playlist: PlaylistEntity): Map<String, EPGProgramme> {
        val epgRaw: List<EPGProgramme> = runCatching { repository.loadCachedEpg(playlist.id) }
            .getOrDefault(emptyList())
        if (epgRaw.isEmpty()) return emptyMap()
        val channels = runCatching { repository.loadCachedChannels(playlist.id) }
            .getOrDefault(emptyList())
        val epg = bridgeChannelIds(epgRaw, channels)
        return epg.groupBy { it.channelId }
            .mapNotNull { (key, list) -> list.nowPlaying()?.let { key to it } }
            .toMap()
    }

    /**
     * Channel-row secondary line, mirroring CarPlay's programDetail
     * (App/CarPlaySceneDelegate.swift): "<programme> · <time left> · <desc>"
     * joined with a middle dot, so the most glanceable bits lead and the head
     * unit's width truncation trims the description first, never the programme
     * or the time. Falls back to the group name when there is no EPG. Built at
     * browse time, so the time-left can lag a few minutes between refreshes
     * (same as CarPlay, which is not on a per-second ticker either).
     */
    private fun programDetail(channel: M3UChannel, programme: EPGProgramme?): String? {
        val parts = mutableListOf<String>()
        programme?.title?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        programme?.endMillis?.let { timeRemaining(it) }?.let { parts.add(it) }
        programme?.description?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        if (parts.isEmpty()) return channel.groupTitle.takeIf { it.isNotBlank() }
        return parts.joinToString(" · ")
    }

    /**
     * Human "time left" in the current programme, or null when it has
     * effectively ended (30s or less remaining, or already past). CarPlay
     * parity (timeRemaining(until:)).
     */
    private fun timeRemaining(endMillis: Long, now: Long = System.currentTimeMillis()): String? {
        val secondsLeft = (endMillis - now) / 1000.0
        if (secondsLeft <= 30) return null
        val minutesLeft = (secondsLeft / 60.0).roundToInt()
        if (minutesLeft < 60) return "$minutesLeft min left"
        val hours = minutesLeft / 60
        val mins = minutesLeft % 60
        return if (mins == 0) "$hours hr left" else "${hours}h ${mins}m left"
    }

    private fun groupNodes(channels: List<M3UChannel>): List<MediaItem> =
        channels.asSequence()
            .map { it.groupTitle }
            .filter { it.isNotBlank() }
            .distinct() // encounter order (CarPlay parity, NOT alphabetical)
            .map { group ->
                val count = channels.count { it.groupTitle.equals(group, ignoreCase = true) }
                browsable(
                    id = GROUP_PREFIX + group,
                    title = group,
                    subtitle = if (count == 1) "1 channel" else "$count channels",
                )
            }
            .toList()

    /** Non-playable, non-browsable row shown when the car has no source yet. */
    private fun emptyStateItem(): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle("No playlist configured")
            .setSubtitle("Open AerioTV on your phone to add a server")
            .setIsBrowsable(false)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()
        return MediaItem.Builder().setMediaId(EMPTY_ID).setMediaMetadata(meta).build()
    }

    private fun browsable(id: String, title: String, subtitle: String? = null): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(meta).build()
    }

    /** Browse-display channel item (no URI; resolved at play time). */
    private fun channelItem(channel: M3UChannel, nowByTvg: Map<String, EPGProgramme>): MediaItem {
        val subtitle = programDetail(channel, nowByTvg[channel.guideMatchKey])
        val meta = MediaMetadata.Builder()
            .setTitle(channel.name)
            .setSubtitle(subtitle)
            .setArtist(subtitle)
            .setArtworkUri(channel.tvgLogo.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_TV_CHANNEL)
            .build()
        return MediaItem.Builder().setMediaId(channel.id).setMediaMetadata(meta).build()
    }

    /** Playable channel item WITH the resolved stream URI for the timeline. */
    private fun playableItem(
        channel: M3UChannel,
        playlist: PlaylistEntity,
        effectiveBase: String,
        nowByTvg: Map<String, EPGProgramme>,
    ): MediaItem {
        val url = streamUrlFor(channel, playlist, effectiveBase)
        val subtitle = programDetail(channel, nowByTvg[channel.guideMatchKey])
        val meta = MediaMetadata.Builder()
            .setTitle(channel.name)
            .setDisplayTitle(channel.name)
            .setSubtitle(subtitle)
            .setArtist(subtitle)
            .setArtworkUri(channel.tvgLogo.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_TV_CHANNEL)
            .build()
        return MediaItem.Builder()
            .setMediaId(channel.id)
            .setUri(url)
            .setMediaMetadata(meta)
            .build()
    }

    /**
     * Rebuild the Dispatcharr stream URL from the CURRENT effective base
     * (LAN/WAN) rather than trusting the cached `channel.url`, which was baked
     * with whatever base was active at last fetch. This is the Android analog
     * of the CarPlay commit's "invalidate the stale LAN probe" fix: a car on
     * cellular must not get a home-LAN URL. Non-Dispatcharr URLs are absolute
     * and used as-is.
     */
    private fun streamUrlFor(channel: M3UChannel, playlist: PlaylistEntity, effectiveBase: String): String {
        val isDispatcharr = playlist.sourceType == SourceType.DispatcharrApiKey.name ||
            playlist.sourceType == SourceType.DispatcharrUserPass.name
        return if (isDispatcharr && channel.id.startsWith(DISPATCHARR_ID_PREFIX)) {
            dispatcharrClient.streamUrl(effectiveBase, channel.id.removePrefix(DISPATCHARR_ID_PREFIX))
        } else {
            channel.url
        }
    }

    private fun headersFor(playlist: PlaylistEntity): Map<String, String> {
        val isDispatcharr = playlist.sourceType == SourceType.DispatcharrApiKey.name ||
            playlist.sourceType == SourceType.DispatcharrUserPass.name
        val key = playlist.apiKey?.takeIf { it.isNotBlank() }
        return if (isDispatcharr && key != null) {
            mapOf("X-API-Key" to key, "Authorization" to "ApiKey $key")
        } else {
            emptyMap()
        }
    }

    companion object {
        const val ROOT_ID = "aerio_auto_root"
        private const val FAVORITES_ID = "aerio_auto_favorites"
        private const val GROUPS_ID = "aerio_auto_groups"
        private const val GROUP_PREFIX = "aerio_auto_grp:"
        private const val DISPATCHARR_ID_PREFIX = "disp:"
        private const val EMPTY_ID = "aerio_auto_empty"
    }
}
