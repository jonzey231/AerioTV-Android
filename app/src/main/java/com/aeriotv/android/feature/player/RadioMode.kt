package com.aeriotv.android.feature.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.playback.AerioMediaPlaybackService
import com.aeriotv.android.feature.main.MainScaffoldEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Radio Mode (Android#127 follow-up, Apple parity): a live channel's audio
 * keeps playing with no video on screen.
 *
 * Runs on the app-scoped live holder, the same instance the full-screen
 * player and the mini use, so returning to the channel never re-tunes: the
 * video track is disabled through the holder's sticky Audio Only flag (the
 * no-video-frame net already ignores a deliberately disabled video track),
 * the persistent video window is hidden, and the foreground media service
 * keeps the process alive in the background with its MediaSession
 * notification (logo, name, Play/Pause, Stop).
 *
 * All calls are main-thread (ExoPlayer and window state).
 */
object RadioMode {
    data class Session(val channel: M3UChannel)

    private val _state = MutableStateFlow<Session?>(null)
    val state: StateFlow<Session?> = _state.asStateFlow()
    val isActive: Boolean get() = _state.value != null

    /** The active playlist's stream headers, kept current by the nav layer
     *  (menus that start Radio Mode have no playlist of their own). */
    /** uptime ms of the last [start] (the TV banner's OK guard). */
    @Volatile var startedAtMs: Long = 0L

    @Volatile var headersProvider: (() -> Map<String, String>)? = null

    /** Neighbour in the current channel order (+1 next, -1 previous), kept
     *  current by the nav layer, for Channel Up / Down in Radio Mode. */
    @Volatile var neighborProvider: ((M3UChannel, Int) -> M3UChannel?)? = null

    /** Channel Up / Down while in Radio Mode. True when handled. */
    fun step(context: Context, delta: Int): Boolean {
        val cur = _state.value?.channel ?: return false
        val next = neighborProvider?.invoke(cur, delta) ?: return false
        switchTo(context, next, headersProvider?.invoke() ?: entry(context).exoPlayerHolder().httpHeaders, "")
        return true
    }

    private val _returnRequests = MutableSharedFlow<M3UChannel>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** "Return to Channel" (card tap, TV banner): the nav layer re-opens the
     *  player for this channel; the holder is still playing it. */
    val returnRequests: SharedFlow<M3UChannel> = _returnRequests.asSharedFlow()

    /** TV guide focus anchor (Apple TV parity, Streamer 2026-10-15): the guide
     *  registers a capture of its focused cell; stop triggers call it at the
     *  key press-down, before the press can move the grid. */
    @Volatile var captureGuideFocus: (() -> Unit)? = null

    /** Reason of each completed [stop]; the TV guide restores its cell. */
    private val _stops = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val stops: SharedFlow<String> = _stops.asSharedFlow()

    private fun entry(context: Context) = EntryPointAccessors.fromApplication(
        context.applicationContext,
        MainScaffoldEntryPoint::class.java,
    )

    /**
     * Enter Radio Mode on the channel the full-screen player is showing. The
     * caller pops the player route afterwards; PlayerScreen's dispose reads
     * [isActive] and treats the close like a minimize (no Keep Live demote,
     * the holder keeps playing).
     */
    fun start(context: Context, channel: M3UChannel) {
        val e = entry(context)
        val holder = e.exoPlayerHolder()
        _state.value = Session(channel)
        startedAtMs = android.os.SystemClock.uptimeMillis()
        // Streamer 2026-11-01 19:05:03: disabling the video track in place
        // destroyed the HEVC decoder while AC-3 kept running and the vendor
        // codec HAL crashed. The holder stops, waits for the decoder release,
        // and re-prepares audio-only instead (sequential teardown).
        val wall = if (holder.isTimeshifting) runCatching { positionWallMs(context) }.getOrNull() else null
        holder.enterRadioModeOnPlaying(wall)
        e.exoWindowState().hide()
        if (com.aeriotv.android.core.pip.PipState.nowPlayingTitle != channel.name) notifyNowPlaying(channel, "")
        Log.i(TAG, "[RADIO] start ${channel.name}")
        AerioMediaPlaybackService.startBackground(context)
        Log.i(TAG, "[RADIO] service started")
    }

    /**
     * Radio Mode from a channel menu (guide, list): tune [channel] audio-only
     * on the shared holder, or keep the stream it is already decoding, with
     * no player on screen. Any mini player is dismissed.
     */
    fun startFromMenu(
        context: Context,
        channel: M3UChannel,
        headers: Map<String, String>,
        subtitle: String,
    ) {
        val e = entry(context)
        e.exoPlayerHolder().radioVideoOff = true
        _state.value = Session(channel)
        switchTo(context, channel, headers, subtitle, log = false)
        start(context, channel)
    }

    /** Another channel picked while in Radio Mode: switch the audio, no player. */
    fun switchTo(
        context: Context,
        channel: M3UChannel,
        headers: Map<String, String>,
        subtitle: String,
        log: Boolean = true,
    ) {
        if (!isActive) return
        val holder = entry(context).exoPlayerHolder()
        if (holder.currentChannelId != channel.id || holder.isIdle()) {
            // Read before playUrl resets it: a failed outgoing channel is
            // released, never handed to Keep Recent Channels Live.
            val previousFailed = holder.streamUnavailable.value ||
            holder.failedChannelId != null
            holder.httpHeaders = headers
            holder.markTunePress(channel.name)
            holder.playUrl(
                url = channel.url,
                title = channel.name,
                subtitle = subtitle,
                artworkUri = channel.tvgLogo.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Uri.parse(it) }.getOrNull() },
                drmLicenseType = channel.drmLicenseType,
                drmLicenseKey = channel.drmLicenseKey,
                channelId = channel.id,
            )
            holder.currentChannelId = channel.id
            // playUrl re-enables video unless the sticky flag says otherwise;
            // set it again in case a re-prime raced the switch.
            holder.setVideoTrackEnabled(false)
            // Live Rewind for the radio stream, exactly as a full-screen
            // tune does (the controller checks Pause and Rewind Live TV and
            // demotes the previous channel under Keep Recent rules).
            val ts = entry(context).timeshiftController()
            if (holder.canBufferLiveRewind(channel.url)) {
                ts.currentPlayUrlProvider = { holder.currentPlayUrl }
                ts.onFullscreenLiveStarted(
                    channel.id, channel.name, channel.url, headers, logoUrl = channel.tvgLogo,
                    keepPrevious = !previousFailed,
                )
            } else {
                ts.onFullscreenLiveStopped()
            }
            livePauseWallMs = 0L
        }
        _state.value = Session(channel)
        notifyNowPlaying(channel, subtitle)
        // Re-post the service's notification so its title follows the switch
        // (the placeholder reads PipState; Nothing Phone kept the old name).
        if (log) AerioMediaPlaybackService.startBackground(context)
        if (log) Log.i(TAG, "[RADIO] switch -> ${channel.name}")
    }

    /** Leave Radio Mode with video back on; the caller opens the player. */
    fun requestReturn(context: Context) {
        val s = _state.value ?: return
        val holder = entry(context).exoPlayerHolder()
        // A Radio Mode tune drops a declared video track on the first audio
        // sample (no video wait); restoring it then needs a re-prepare.
        val wall = if (holder.isTimeshifting) runCatching { positionWallMs(context) }.getOrNull() else null
        holder.restoreVideoAfterRadio(wall)
        _state.value = null
        Log.i(TAG, "[RADIO] return to ${s.channel.name}")
        _returnRequests.tryEmit(s.channel)
    }

    /** End Radio Mode and its audio. Keep Recent Channels Live decides as for
     *  the mini player's close (the player dispose stored the candidate). */
    fun stop(context: Context, reason: String) {
        val s = _state.value ?: return
        val e = entry(context)
        val holder = e.exoPlayerHolder()
        // Read before holder.stop(): a channel that failed and has not
        // played since is released, never kept live.
        val failed = holder.streamUnavailable.value || holder.failedChannelId != null
        _state.value = null
        holder.radioVideoOff = false
        runCatching { e.exoWindowState().hide() }
        runCatching { holder.stop() }
        AerioMediaPlaybackService.stop(context)
        Log.i(TAG, "[RADIO] service stopped")
        // Release the buffer with the full-screen close rules (Keep Recent
        // Channels Live decides), the same call the player's X makes.
        livePauseWallMs = 0L
        runCatching {
            e.timeshiftController().onFullscreenLiveStopped(
                keepIfEnabled = !holder.lastTuneWasForcedHls,
                streamFailed = failed,
            )
        }
        Log.i(TAG, "[RADIO] stop ($reason) ${s.channel.name}")
        _stops.tryEmit(reason)
    }

    /** Wall time a buffered live pause began (0 = not paused on live). */
    @Volatile private var livePauseWallMs = 0L

    /**
     * Play / Pause with the full-screen player's Live Rewind rules: a pause
     * on live freezes the stream while the filler keeps the buffer growing;
     * a resume within 6 s continues live, a longer one switches onto the
     * buffer at the pause point. Without a buffer it is a plain toggle.
     */
    fun togglePlayPause(context: Context) {
        val e = entry(context)
        val holder = e.exoPlayerHolder()
        val ts = e.timeshiftController()
        val buffering = ts.state.value.buffering
        when {
            holder.isTimeshifting || !buffering -> holder.setPaused(!holder.isPaused())
            !holder.isPaused() -> {
                livePauseWallMs = System.currentTimeMillis()
                holder.setPaused(true)
                ts.onLivePaused()
            }
            livePauseWallMs > 0 -> {
                if (System.currentTimeMillis() - livePauseWallMs <= 6_000) {
                    holder.setPaused(false)
                    ts.onLiveResumedAtEdge()
                } else {
                    holder.setPaused(false)
                    holder.playTimeshift(livePauseWallMs - 1_000)
                }
                livePauseWallMs = 0L
            }
            else -> holder.setPaused(!holder.isPaused())
        }
    }

    /** Current playhead on the wall clock (the buffer head when live). */
    fun positionWallMs(context: Context): Long {
        val e = entry(context)
        // The window in the controller's state only moves when someone asks
        // (the full-screen player polls it); Radio has no player, so refresh
        // here. Without it a -10 s skip read a head minutes old and landed
        // at the buffer tail (Nothing Phone 2026-10-13).
        e.timeshiftController().refreshWindow()
        val st = e.timeshiftController().state.value
        val holder = e.exoPlayerHolder()
        return if (st.timeshifting) st.baseWallMs + (holder.player?.currentPosition ?: 0L) else st.headWallMs
    }

    /** Seek on the buffer, the full-screen player's commit rule: within 5 s
     *  of the head goes live, otherwise into the buffer at [target]. */
    fun seekToWall(context: Context, target: Long) {
        val e = entry(context)
        val ts = e.timeshiftController()
        val holder = e.exoPlayerHolder()
        val w = ts.activeWriter
        val head = w?.headWallMs ?: ts.state.value.headWallMs
        val tail = w?.tailWallMs ?: ts.state.value.tailWallMs
        livePauseWallMs = 0L
        if (target >= head - 5_000) holder.goLive() else holder.playTimeshift(target.coerceAtLeast(tail))
        Log.i(TAG, "[RADIO] seek to ${target} (window $tail..$head)")
    }

    fun goLive(context: Context) {
        livePauseWallMs = 0L
        entry(context).exoPlayerHolder().goLive()
    }

    private fun notifyNowPlaying(channel: M3UChannel, subtitle: String) {
        com.aeriotv.android.core.pip.PipState.nowPlayingTitle = channel.name
        com.aeriotv.android.core.pip.PipState.nowPlayingSubtitle = subtitle
        com.aeriotv.android.core.pip.PipState.nowPlayingLogo = channel.tvgLogo.takeIf { it.isNotBlank() }
    }

    private const val TAG = "AerioTV"
}
