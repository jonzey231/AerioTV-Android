package com.aeriotv.android.feature.player

import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlin.math.abs

/**
 * tvOS-style content frame-rate matching for live TV (the UHD "super stuttery"
 * fix).
 *
 * Dispatcharr's MPEG-TS feed for UK UHD channels (e.g. Sky Sports Main Event
 * UHD, ch 36) runs at 50fps but does NOT signal its frame rate in the container
 * (Format.frameRate == -1). With nothing to match, ExoPlayer / Google TV leave
 * the panel pinned at 60Hz and 50fps-on-60Hz pulldown produces constant judder.
 *
 * We measure the real frame rate from rendered-frame presentation timestamps
 * and request the matching display mode via preferredDisplayModeId. The switch
 * is non-seamless (a brief HDMI re-handshake on channel start), the same
 * tradeoff tvOS makes for smooth playback. Measurement is continuous, so a flip
 * to a different-cadence stream re-matches while same-cadence flips don't
 * re-switch (no extra flash). TV-only; the mode is reset on teardown.
 */
object DisplayFrameRateMatcher {
    private const val TAG = "AerioFpsMatch"

    /**
     * Start measuring [player]'s frame rate and switch [activity]'s display mode
     * to match. Returns an opaque handle to pass back to [detach]; null if the
     * platform is too old to enumerate display modes.
     */
    @OptIn(UnstableApi::class)
    fun attach(player: ExoPlayer, activity: Activity): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val deltasUs = ArrayDeque<Long>()
        var lastPtsUs = -1L
        var lastIdeal = 0f
        var sinceSwitch = 0
        val listener = VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            if (lastPtsUs >= 0L) {
                val d = presentationTimeUs - lastPtsUs
                if (d < 0L || d > 1_000_000L) {
                    deltasUs.clear() // stream discontinuity / channel flip
                } else if (d in 4_000L..210_000L) {
                    deltasUs.addLast(d)
                    if (deltasUs.size > 60) deltasUs.removeFirst()
                }
            }
            lastPtsUs = presentationTimeUs
            sinceSwitch++
            if (deltasUs.size >= 30 && sinceSwitch >= 30) {
                val sorted = deltasUs.sorted()
                val fps = 1_000_000.0 / sorted[sorted.size / 2]
                val ideal = idealRefreshFor(fps)
                if (ideal != lastIdeal) {
                    lastIdeal = ideal
                    sinceSwitch = 0
                    activity.runOnUiThread { applyMode(activity, fps, ideal) }
                }
            }
        }
        player.setVideoFrameMetadataListener(listener)
        return listener
    }

    @OptIn(UnstableApi::class)
    fun detach(player: ExoPlayer?, handle: Any?, activity: Activity?) {
        (handle as? VideoFrameMetadataListener)?.let { l ->
            runCatching { player?.clearVideoFrameMetadataListener(l) }
        }
        // Release our preferred mode so the launcher / guide return to default.
        activity?.let { act ->
            val attrs = act.window.attributes
            if (attrs.preferredDisplayModeId != 0) {
                act.window.attributes = attrs.apply { preferredDisplayModeId = 0 }
            }
        }
    }

    private fun applyMode(activity: Activity, fps: Double, ideal: Float) {
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            activity.windowManager.defaultDisplay
        } ?: return
        val current = display.mode ?: return
        // Same resolution as the current mode, refresh rate closest to ideal.
        val target = display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .minByOrNull { abs(it.refreshRate - ideal) }
            ?: return
        // Only switch if it's a meaningfully better match than what's active.
        if (target.modeId != current.modeId &&
            abs(target.refreshRate - ideal) + 0.5f < abs(current.refreshRate - ideal)
        ) {
            Log.w(
                TAG,
                "content ${"%.2f".format(fps)}fps -> ${target.physicalWidth}x" +
                    "${target.physicalHeight}@${"%.2f".format(target.refreshRate)} " +
                    "(was @${"%.2f".format(current.refreshRate)})",
            )
            activity.window.attributes = activity.window.attributes.apply {
                preferredDisplayModeId = target.modeId
            }
        }
    }

    /** Map a measured content fps to the ideal panel refresh: 50/25 -> 50Hz,
     *  60/30 -> 60Hz. Live feeds are one of these; film cadence falls back to
     *  60 (standard pulldown). */
    private fun idealRefreshFor(fps: Double): Float = when {
        fps in 47.0..52.5 || fps in 23.5..26.5 -> 50f
        fps in 57.0..62.0 || fps in 28.5..31.5 -> 60f
        else -> 60f
    }
}
