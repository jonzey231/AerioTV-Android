package com.aeriotv.android.feature.multiview

import android.media.MediaCodecList
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.aeriotv.android.feature.player.ContentFpsMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Multiview hardware decode budget (Logan 2026-11-06, Streamer: two 4K tiles
 * "stuttery").
 *
 * Measured on the Google TV Streamer (mt8696): with ESPN UHD and ESPN 2 HD in
 * a two-tile grid both tiles opened as 3840x2160 HEVC HDR on
 * c2.mtk.hevc.decoder and dropped frames in lockstep (+467/+470 in the first
 * 15 s, render 7.4 and 12.1 fps, then +400/+400, +250/+200, +100/+150 per 15 s
 * and 36 to 54 fps, one tile stalled 12 s with bytes still arriving). The
 * decoder declares one performance point, 3840x2160@60, and a
 * blocks-per-second limit of 2,211,840 (16x16 blocks): one 4K60 stream is
 * 1,944,000 (88 percent of the budget), two are 176 percent. The same session
 * with a 1280x720 AVC second tile (98 percent in total) rendered 54 to 59 fps.
 *
 * Rule: every tile reports its video format (and measured rate) here; tiles
 * are admitted in priority order (audio-focused tile first, then the lightest
 * streams first) until the next one would push the summed decoder load over
 * [LOAD_LIMIT]. Tiles past the limit have their video track disabled (audio
 * stays, so the tile keeps its stream and its place; the grid shows a card)
 * and are re-admitted when the focus or the tile set changes. Main thread
 * only, like [MultiviewTilePlayers].
 */
@OptIn(UnstableApi::class)
object MultiviewDecodeBudget {
    private const val TAG = "AerioMV"

    /**
     * Summed load (fraction of the declared blocks-per-second per codec) past
     * which the next tile parks. Measured: 0.98 (4K60 HEVC plus 720p60 AVC)
     * played at 54 to 59 fps, 1.76 (two 4K60 HEVC) at 7 to 54 fps; the band
     * between is unmeasured, so the limit sits at the declared budget.
     */
    private const val LOAD_LIMIT = 1.0

    /** UHD broadcast is 50 or 60 fps; until the stream's rate is measured a
     *  2160-line tile is costed at 60, anything smaller at 30. */
    private fun assumedFps(height: Int): Float = if (height >= 2160) 60f else 30f

    private class Entry(val player: ExoPlayer, val name: String) {
        var focused = false
        var width = 0
        var height = 0
        var formatFps = 0f
        var measuredFps = 0f
        var mime: String? = null
        var parked = false
        var listener: AnalyticsListener? = null
        var meterJob: Job? = null
        val load: Double
            get() {
                if (width <= 0 || height <= 0) return 0.0
                val fps = when {
                    measuredFps > 0f -> measuredFps
                    formatFps > 0f -> formatFps
                    else -> assumedFps(height)
                }
                val blocks = ((width + 15) / 16).toLong() * ((height + 15) / 16).toLong()
                val budget = budgetFor(mime ?: return 0.0) ?: return 0.0
                return blocks * fps.toDouble() / budget
            }
    }

    private val entries = LinkedHashMap<ExoPlayer, Entry>()
    private val budgets = HashMap<String, Long?>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val _parkedNames = MutableStateFlow<Set<String>>(emptySet())

    /** Tile display names whose video is parked right now. */
    val parkedNames: StateFlow<Set<String>> = _parkedNames

    fun register(player: ExoPlayer, name: String, focused: Boolean) {
        if (entries.containsKey(player)) return
        val e = Entry(player, name)
        e.focused = focused
        val listener = object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                e.width = format.width
                e.height = format.height
                e.formatFps = format.frameRate
                e.mime = format.sampleMimeType
                scheduleEvaluate("format ${e.name} ${format.width}x${format.height}")
            }
            override fun onMediaItemTransition(
                eventTime: AnalyticsListener.EventTime,
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                // A new stream: forget the old size so it is not costed
                // against the budget until the new one reports.
                e.width = 0; e.height = 0; e.formatFps = 0f; e.measuredFps = 0f; e.mime = null
                scheduleEvaluate("transition ${e.name}")
            }
        }
        e.listener = listener
        player.addAnalyticsListener(listener)
        player.videoFormat?.let { f ->
            e.width = f.width; e.height = f.height; e.formatFps = f.frameRate; e.mime = f.sampleMimeType
        }
        e.meterJob = scope.launch {
            ContentFpsMeter.flowFor(player).collect { fps ->
                if (fps > 0f && kotlin.math.abs(fps - e.measuredFps) > 0.5f) {
                    e.measuredFps = fps
                    scheduleEvaluate("rate ${e.name} $fps")
                }
            }
        }
        entries[player] = e
        scheduleEvaluate("register ${e.name}")
    }

    fun setFocused(player: ExoPlayer, focused: Boolean) {
        val e = entries[player] ?: return
        if (e.focused == focused) return
        e.focused = focused
        scheduleEvaluate("focus ${e.name}=$focused")
    }

    fun unregister(player: ExoPlayer) {
        val e = entries.remove(player) ?: return
        e.listener?.let { player.removeAnalyticsListener(it) }
        e.meterJob?.cancel()
        scheduleEvaluate("unregister ${e.name}")
    }

    private var pendingReason: String? = null
    private val evaluateRunnable = Runnable {
        val reason = pendingReason ?: "tick"
        pendingReason = null
        evaluate(reason)
    }

    /** Coalesces bursts (every tile reports its format within a few ms). */
    private fun scheduleEvaluate(reason: String) {
        if (pendingReason == null) handler.postDelayed(evaluateRunnable, 150L)
        pendingReason = reason
    }

    private fun evaluate(reason: String) {
        if (entries.isEmpty()) {
            if (_parkedNames.value.isNotEmpty()) _parkedNames.value = emptySet()
            return
        }
        // Focused tile first, then the lightest streams first, so a focused
        // 4K tile keeps its picture and the smaller tiles ride along.
        val ordered = entries.values.sortedWith(
            compareByDescending<Entry> { it.focused }.thenBy { it.load },
        )
        var sum = 0.0
        val park = HashSet<Entry>()
        for (e in ordered) {
            val load = e.load
            if (load <= 0.0) continue
            if (sum + load > LOAD_LIMIT && sum > 0.0) {
                park += e
            } else {
                sum += load
            }
        }
        var changed = false
        for (e in entries.values) {
            val shouldPark = e in park
            if (shouldPark != e.parked) {
                e.parked = shouldPark
                changed = true
                runCatching {
                    e.player.trackSelectionParameters = e.player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, shouldPark)
                        .build()
                }
                Log.i(
                    TAG,
                    "[MV-BUDGET] ${if (shouldPark) "park" else "resume"} video tile=${e.name} " +
                        "${e.width}x${e.height} load=${"%.2f".format(e.load)} reason=$reason",
                )
            }
        }
        if (changed || reason.startsWith("register") || reason.startsWith("unregister")) {
            val summary = entries.values.joinToString(" ") {
                "${it.name}:${it.width}x${it.height}@${"%.0f".format(if (it.measuredFps > 0f) it.measuredFps else if (it.formatFps > 0f) it.formatFps else assumedFps(it.height))}=${"%.2f".format(it.load)}${if (it.focused) "*" else ""}${if (it.parked) "(parked)" else ""}"
            }
            Log.i(TAG, "[MV-BUDGET] admitted=${"%.2f".format(sum)} limit=$LOAD_LIMIT $summary")
        }
        if (changed) _parkedNames.value = entries.values.filter { it.parked }.map { it.name }.toSet()
    }

    /**
     * Declared blocks-per-second budget of the device's hardware decoder for
     * [mime]: the 16x16 block count of its largest supported frame times the
     * frame rate it declares for that frame. Null when no hardware decoder
     * exists or the query fails, in which case nothing is ever parked.
     */
    private fun budgetFor(mime: String): Long? = budgets.getOrPut(mime) {
        runCatching {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            val info = infos.firstOrNull { ci ->
                !ci.isEncoder && !ci.isSoftwareOnly && ci.isHardwareAccelerated &&
                    ci.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            } ?: return@runCatching null
            val caps = info.getCapabilitiesForType(mime).videoCapabilities ?: return@runCatching null
            val w = caps.supportedWidths.upper
            val h = caps.getSupportedHeightsFor(w).upper
            val fps = caps.getSupportedFrameRatesFor(w, h).upper
            val blocks = ((w + 15) / 16).toLong() * ((h + 15) / 16).toLong()
            val budget = (blocks * fps).toLong()
            Log.i(TAG, "[MV-BUDGET] decoder ${info.name} $mime max ${w}x$h@${"%.1f".format(fps)} blocks/s=$budget")
            budget
        }.getOrNull()
    }
}
