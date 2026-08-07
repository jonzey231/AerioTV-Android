package com.aeriotv.android.core.tv

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.aeriotv.android.core.data.db.dao.ChannelSnapshotDao
import com.aeriotv.android.core.data.db.dao.FavoriteChannelDao
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import com.aeriotv.android.core.data.db.entity.ChannelSnapshotEntity
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity
import com.aeriotv.android.core.preferences.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android TV home-screen ("channels") publisher, audit task #47. The Android
 * twin of the tvOS Top Shelf (TopShelfExtension/ContentProvider.swift):
 *
 *  - A "Top Channels" preview channel: up to [MAX_CHANNEL_CARDS] cards,
 *    recently-watched channels first (the Android proxy for the tvOS
 *    watch-count ranking) padded with favorites and then the playlist's first
 *    logo-bearing channels so a fresh install still fills the row. Cards deep
 *    link `aeriotv://channel/<id>` straight into fullscreen playback.
 *  - The launcher's Watch Next row: unfinished VOD/episode progress rows
 *    (tvOS "Continue Watching" section), deep linking
 *    `aeriotv://vodplay/<videoId>?episode=0|1` so a click RESUMES playback
 *    rather than landing on the detail page.
 *
 * TV-only: [start] is a no-op without FEATURE_LEANBACK. Publishing re-runs
 * (debounced) whenever the active playlist, recents, favorites, or watch
 * progress change; the launcher-initiated INITIALIZE_PROGRAMS broadcast also
 * lands on [publishNow] via [HomeChannelsInitReceiver].
 *
 * Divergence from tvOS, on purpose: no "name — current programme" subtitle on
 * channel cards. The EPG multi-key channel<->programme match
 * (project_aeriotv_epg_match_model) is too heavy to re-run in a background
 * publisher for six cards, and the launcher already renders the channel name
 * under the art.
 */
@Singleton
class HomeChannelsPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistDao: PlaylistDao,
    private val channelSnapshotDao: ChannelSnapshotDao,
    private val favoriteChannelDao: FavoriteChannelDao,
    private val watchProgressDao: WatchProgressDao,
    private val appPreferences: AppPreferences,
) {

    private val isTv: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Begin observing the inputs and republishing on change. Call once from
     *  MainActivity; safe to call on any form factor (no-op off-TV). */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (!isTv) return
        scope.launch(Dispatchers.IO) {
            combine(
                playlistDao.observeActive(),
                appPreferences.recentChannelIds,
                favoriteChannelDao.observeAll(),
                watchProgressDao.observeRecent(WATCH_NEXT_LIMIT * 2),
            ) { playlists, recents, favorites, progress ->
                PublishInputs(
                    activePlaylistId = playlists.firstOrNull()?.id,
                    recentIds = recents,
                    favoriteIds = favorites.map { it.channelId },
                    progress = progress,
                )
            }
                // The player upserts progress every few seconds; one publish
                // after things settle is plenty for a launcher row.
                .debounce(3_000)
                .collect { inputs ->
                    runCatching { publish(inputs) }
                        .onFailure { android.util.Log.w(TAG, "publish failed", it) }
                }
        }
    }

    /** One-shot publish with freshly-read inputs (INITIALIZE_PROGRAMS path). */
    suspend fun publishNow() {
        if (!isTv) return
        val inputs = PublishInputs(
            activePlaylistId = playlistDao.firstActive()?.id,
            recentIds = emptyList(),
            favoriteIds = favoriteChannelDao.allOnce().map { it.channelId },
            progress = watchProgressDao.allOnce().take(WATCH_NEXT_LIMIT * 2),
        )
        runCatching { publish(inputs) }
            .onFailure { android.util.Log.w(TAG, "publishNow failed", it) }
    }

    private data class PublishInputs(
        val activePlaylistId: String?,
        val recentIds: List<String>,
        val favoriteIds: List<String>,
        val progress: List<WatchProgressEntity>,
    )

    private suspend fun publish(inputs: PublishInputs) {
        val helper = PreviewChannelHelper(context)
        val channelRows = inputs.activePlaylistId
            ?.let { rankChannels(it, inputs.recentIds, inputs.favoriteIds) }
            .orEmpty()
        publishTopChannels(helper, channelRows)
        publishWatchNext(helper, inputs.progress)
    }

    /** Recents first (freshest first), then favorites in display order, then
     *  the first logo-bearing channels, capped at [MAX_CHANNEL_CARDS] --
     *  the tvOS syncTopChannels ranked-then-padded shape. */
    private suspend fun rankChannels(
        playlistId: String,
        recentIds: List<String>,
        favoriteIds: List<String>,
    ): List<ChannelSnapshotEntity> {
        val snapshot = channelSnapshotDao.forPlaylist(playlistId)
        if (snapshot.isEmpty()) return emptyList()
        val byId = snapshot.associateBy { it.channelId }
        val ranked = LinkedHashMap<String, ChannelSnapshotEntity>()
        for (id in recentIds) byId[id]?.let { ranked.putIfAbsent(it.channelId, it) }
        for (id in favoriteIds) {
            if (ranked.size >= MAX_CHANNEL_CARDS) break
            byId[id]?.let { ranked.putIfAbsent(it.channelId, it) }
        }
        if (ranked.size < MAX_CHANNEL_CARDS) {
            for (row in snapshot) {
                if (ranked.size >= MAX_CHANNEL_CARDS) break
                if (row.tvgLogo.isNotBlank()) ranked.putIfAbsent(row.channelId, row)
            }
        }
        return ranked.values.take(MAX_CHANNEL_CARDS).toList()
    }

    private fun publishTopChannels(
        helper: PreviewChannelHelper,
        rows: List<ChannelSnapshotEntity>,
    ) {
        val existing = helper.allChannels.firstOrNull { it.internalProviderId == CHANNEL_KEY }
        val channelId: Long
        if (existing == null) {
            if (rows.isEmpty()) return // nothing to show; don't create an empty row
            val channel = PreviewChannel.Builder()
                .setDisplayName(CHANNEL_DISPLAY_NAME)
                .setInternalProviderId(CHANNEL_KEY)
                .setAppLinkIntentUri(Uri.parse("aeriotv://channel"))
                .setLogo(appIconBitmap())
                .build()
            channelId = helper.publishChannel(channel)
            // Ask the launcher to surface the row without a trip through its
            // "customize channels" settings. One-time; the user keeps control.
            TvContractCompat.requestChannelBrowsable(context, channelId)
        } else {
            channelId = existing.id
        }

        // Programs are tiny (<= MAX_CHANNEL_CARDS): wipe and re-insert rather
        // than diffing.
        context.contentResolver.delete(
            TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null,
        )
        rows.forEach { row ->
            val logo = row.tvgLogo.takeIf { it.isNotBlank() }?.let(Uri::parse)
            val builder = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                .setTitle(row.name)
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntentUri(Uri.parse("aeriotv://channel/${Uri.encode(row.channelId)}"))
                .setInternalProviderId(row.channelId)
            if (logo != null) builder.setPosterArtUri(logo)
            row.channelNumber?.takeIf { it.isNotBlank() }
                ?.let { builder.setEpisodeTitle("Channel $it") }
            helper.publishPreviewProgram(builder.build())
        }
        android.util.Log.d(TAG, "published ${rows.size} channel cards")
    }

    private fun publishWatchNext(helper: PreviewChannelHelper, progress: List<WatchProgressEntity>) {
        val wanted = progress
            .asSequence()
            .filter { !it.isFinished && it.positionMs > 0 && it.durationMs > 0 }
            // DVR-recording playback rows store their URL as the videoId;
            // those aren't VOD deep-linkable, so keep them off the launcher.
            .filter { "://" !in it.videoId }
            .sortedByDescending { it.updatedAt }
            .take(WATCH_NEXT_LIMIT)
            .associateBy { it.videoId }

        // Enumerate our existing Watch Next rows so removals (finished /
        // deleted progress) leave the launcher too.
        val existing = mutableMapOf<String, Long>() // internalProviderId -> row id
        context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            WatchNextProgram.PROJECTION, null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val program = WatchNextProgram.fromCursor(cursor)
                program.internalProviderId?.let { existing[it] = program.id }
            }
        }

        for ((videoId, rowId) in existing) {
            if (videoId !in wanted) {
                context.contentResolver.delete(
                    TvContractCompat.buildWatchNextProgramUri(rowId), null, null,
                )
            }
        }

        for (entry in wanted.values) {
            val isEpisode = entry.vodType == "episode"
            val builder = WatchNextProgram.Builder()
                .setType(
                    if (isEpisode) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
                    else TvContractCompat.WatchNextPrograms.TYPE_MOVIE,
                )
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(entry.updatedAt)
                .setTitle(entry.title)
                .setLastPlaybackPositionMillis(entry.positionMs.toInt())
                .setDurationMillis(entry.durationMs.toInt())
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                .setIntentUri(
                    Uri.parse(
                        "aeriotv://vodplay/${Uri.encode(entry.videoId)}" +
                            "?episode=${if (isEpisode) 1 else 0}",
                    ),
                )
                .setInternalProviderId(entry.videoId)
            entry.posterUrl?.takeIf { it.isNotBlank() }
                ?.let { builder.setPosterArtUri(Uri.parse(it)) }
            if (isEpisode) {
                if (entry.seasonNumber > 0) builder.setSeasonNumber(entry.seasonNumber)
                if (entry.episodeNumber > 0) builder.setEpisodeNumber(entry.episodeNumber)
            }
            val program = builder.build()
            val existingId = existing[entry.videoId]
            if (existingId != null) {
                helper.updateWatchNextProgram(program, existingId)
            } else {
                helper.publishWatchNextProgram(program)
            }
        }
        android.util.Log.d(TAG, "watch next: ${wanted.size} rows (removed ${existing.keys.count { it !in wanted }})")
    }

    /** Launcher-icon bitmap for the PreviewChannel logo (adaptive icons can't
     *  go through BitmapFactory.decodeResource). */
    private fun appIconBitmap(): Bitmap {
        val drawable = context.packageManager.getApplicationIcon(context.applicationInfo)
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    companion object {
        private const val TAG = "HomeChannels"
        private const val CHANNEL_KEY = "aeriotv.top_channels"
        private const val CHANNEL_DISPLAY_NAME = "Top Channels"
        /** tvOS Top Shelf shows 6 channel cards; match it. */
        private const val MAX_CHANNEL_CARDS = 6
        /** tvOS syncs up to 10 continue-watching entries; match it. */
        private const val WATCH_NEXT_LIMIT = 10
    }
}
