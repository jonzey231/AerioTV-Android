package com.aeriotv.android.core.playback

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * A LoadControl whose learned live start gate can change on a RUNNING player.
 *
 * DefaultLoadControl fixes its buffer bounds at build time, so a channel with a
 * different learned hold-back used to force a full player rebuild inside
 * playUrl: ExoPlayer.release() on the main thread (waits for the playback
 * thread and the codec) plus a fresh build, all before the new stream was
 * requested. That stretched a guide tune with the mini player running to
 * seconds before playUrl (Streamer, playUrl=3738).
 *
 * This keeps one DefaultLoadControl per gate, all sharing ONE allocator, and
 * forwards every call to the active one. [requestGate] (main thread) only
 * records the wanted gate; the swap happens on the playback thread at the next
 * callback, where the new delegate is prepared and handed the last track
 * selection so its target buffer size is right before the new period's own
 * onTracksSelected arrives. Retired delegates are never told onStopped /
 * onReleased, so they can never reset the shared allocator under the active one.
 */
@UnstableApi
class GateSwitchingLoadControl(
    initialGateMs: Int,
    private val build: (gateMs: Int, allocator: DefaultAllocator) -> DefaultLoadControl,
) : LoadControl {

    private val allocator = DefaultAllocator(/* trimOnReset = */ true, /* individualAllocationSize = */ 64 * 1024)
    private val delegates = HashMap<Int, DefaultLoadControl>()

    @Volatile private var requestedGateMs: Int = initialGateMs
    private var activeGateMs: Int = initialGateMs
    private var active: DefaultLoadControl = delegateFor(initialGateMs)

    // Last lifecycle inputs, replayed into a newly activated delegate.
    private var preparedPlayerId: PlayerId? = null
    private var lastTracks: TracksArgs? = null

    private class TracksArgs(
        val playerId: PlayerId,
        val timeline: Timeline,
        val periodId: MediaSource.MediaPeriodId,
        val renderers: Array<Renderer>,
        val groups: TrackGroupArray,
        val selections: Array<ExoTrackSelection>,
    )

    /** Main thread: the gate the next stream should start with. */
    fun requestGate(gateMs: Int) {
        requestedGateMs = gateMs
    }

    private fun delegateFor(gateMs: Int): DefaultLoadControl =
        delegates.getOrPut(gateMs) { build(gateMs, allocator) }

    /** Playback thread: swap to the requested gate's delegate if it changed. */
    private fun current(): DefaultLoadControl {
        val want = requestedGateMs
        if (want != activeGateMs) {
            val next = delegateFor(want)
            preparedPlayerId?.let { id ->
                next.onPrepared(id)
                lastTracks?.let {
                    next.onTracksSelected(it.playerId, it.timeline, it.periodId, it.renderers, it.groups, it.selections)
                }
            }
            active = next
            activeGateMs = want
        }
        return active
    }

    override fun onPrepared(playerId: PlayerId) {
        preparedPlayerId = playerId
        current().onPrepared(playerId)
    }

    override fun onTracksSelected(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection>,
    ) {
        lastTracks = TracksArgs(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
        current().onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
    }

    override fun onStopped(playerId: PlayerId) {
        current().onStopped(playerId)
        preparedPlayerId = null
        lastTracks = null
    }

    override fun onReleased(playerId: PlayerId) {
        current().onReleased(playerId)
        preparedPlayerId = null
        lastTracks = null
    }

    override fun getAllocator(): Allocator = allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = current().getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        current().retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        current().shouldContinueLoading(parameters)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        current().shouldStartPlayback(parameters)
}
