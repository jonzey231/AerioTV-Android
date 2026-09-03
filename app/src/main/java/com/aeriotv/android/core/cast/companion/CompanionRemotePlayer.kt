package com.aeriotv.android.core.cast.companion

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * A [Player] that plays nothing on the phone: it mirrors what the paired TV
 * reports over the companion socket (title, live/timeshift position,
 * playing state) and forwards transport commands back. Wrapped in a Media3
 * MediaSession by [CompanionRemoteService] so the phone gets a media
 * notification while controlling a TV (Logan 2026-09-02): play/pause,
 * rewind/fast-forward when the TV's live buffer allows it, and a tap that
 * reopens the remote.
 */
class CompanionRemotePlayer(
    private val controller: CompanionRemoteController,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    init {
        watch = combine(
            controller.nowPlaying, controller.position, controller.isPlaying, controller.currentChannelId,
            controller.details,
        ) { _, _, _, _, _ -> Unit }
            .onEach { invalidateState() }
            .launchIn(scope)
    }

    fun close() {
        watch?.cancel()
        watch = null
    }

    override fun getState(): State {
        val pos = controller.position.value
        val playing = controller.isPlaying.value
        val tvName = (controller.connection.value as? CompanionRemoteController.Conn.Connected)?.name
        val details = controller.details.value?.takeIf { it.channelId == controller.currentChannelId.value }
        val channelName = details?.channelName ?: controller.nowPlaying.value
        // Programme title on top, "<channel> on <TV>" beneath, channel logo as art.
        val title = details?.programmeTitle?.takeIf { it.isNotBlank() }
            ?: channelName.ifBlank { "Controlling TV" }
        val subtitle = listOfNotNull(
            channelName.takeIf { it.isNotBlank() && it != title },
            tvName?.let { "on $it" } ?: "on TV",
        ).joinToString(" ")
        val canSeek = pos.canSeek && pos.windowEndMs > pos.windowStartMs
        val durationMs = if (canSeek) pos.windowEndMs - pos.windowStartMs else C.TIME_UNSET
        val positionMs = if (canSeek) (pos.positionWallMs - pos.windowStartMs).coerceIn(0L, durationMs) else 0L
        // Rewind/forward are always offered (Logan 2026-09-02): the TV ignores
        // them until it has a live buffer, and hiding them made the
        // notification look like a plain play/pause tile. Scrubbing needs the
        // window, so it stays gated.
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
            )
            .apply { if (canSeek) add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }
            .build()
        val item = MediaItem.Builder()
            .setMediaId(controller.currentChannelId.value ?: "companion")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    // Logo goes through the service's padded bitmap loader so One
                    // UI does not paint it full-bleed across the card.
                    .setArtworkUri(details?.logoUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .setPlaylist(
                listOf(
                    MediaItemData.Builder(item.mediaId)
                        .setMediaItem(item)
                        .setIsSeekable(canSeek)
                        .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000)
                        .setIsDynamic(true)
                        .build(),
                ),
            )
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(positionMs)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.play() else controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val pos = controller.position.value
        when (seekCommand) {
            Player.COMMAND_SEEK_BACK -> controller.seekBy(-SEEK_STEP_MS)
            Player.COMMAND_SEEK_FORWARD -> controller.seekBy(SEEK_STEP_MS)
            else -> if (pos.canSeek) controller.seekToWall(pos.windowStartMs + positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.disconnect()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        close()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val SEEK_STEP_MS = 30_000L
    }
}
