package com.aeriotv.android.core.cast

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.support.v4.media.session.MediaSessionCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aeriotv.android.core.playback.AerioExoPlayerHolder
import com.aeriotv.android.core.playback.AutoBrowseTree
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.feature.player.AudioTrack
import com.aeriotv.android.feature.player.SubtitleTrack
import com.aeriotv.android.feature.player.applySpeed
import com.aeriotv.android.feature.player.readAudioTracks
import com.aeriotv.android.feature.player.readCurrentAid
import com.aeriotv.android.feature.player.readCurrentSid
import com.aeriotv.android.feature.player.readSpeed
import com.aeriotv.android.feature.player.readSubtitleTracks
import com.aeriotv.android.feature.player.selectAudioTrack
import com.aeriotv.android.feature.player.selectSubtitleTrack
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.cast.tv.media.MediaManager
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cast Connect RECEIVER controller (GH #33). Lives only on the receiver side:
 * when a phone/tablet sender casts to a Chromecast-with-Google-TV (or any
 * Android TV) that has AerioTV installed, the framework launches THIS app as the
 * receiver and this controller turns the incoming Cast load into ordinary
 * AerioTV playback, so the raw MPEG-TS is decoded by the app's own ExoPlayer
 * (TsExtractor + the ffmpeg AC-3 renderer) instead of the web receiver, which
 * cannot play raw TS.
 *
 * Flow:
 *  1. [bootstrap] (Application.onCreate, Android TV only) inits CastReceiverContext,
 *     registers the load callback, and starts/stops the receiver with the process
 *     foreground lifecycle.
 *  2. MainActivity forwards its launch/new intents to [handleIntent]; a Cast
 *     LAUNCH there makes MediaManager invoke [LoadCallback.onLoad].
 *  3. onLoad validates the channel against the RECEIVER's own playlist + effective
 *     base (via [AutoBrowseTree.resolveForPlayback], so a phone on cellular can't
 *     hand the TV a home-LAN URL) and emits a [CastLoadRequest]; MainActivity
 *     observes [loadRequests] and drives its normal fullscreen player, which
 *     mounts the persistent surface and plays with video enabled.
 *  4. AerioMediaPlaybackService publishes its MediaSession token via
 *     [publishSessionToken]; Cast Connect derives the sender-visible media status
 *     (play/pause, channel name, logo) from that session automatically.
 *
 * All Cast calls are wrapped in runCatching: on a device with no Google Play
 * services, or before the owner has registered a Cast App ID, none of this must
 * ever crash the app.
 */
@Singleton
class AerioCastReceiverController @Inject constructor(
    private val browseTree: AutoBrowseTree,
    private val holder: AerioExoPlayerHolder,
    private val prefs: AppPreferences,
) {

    /** What kind of content a cast load targets. Live reuses the channel player;
     *  VOD reuses the movie player. */
    enum class Kind { LIVE, VOD }

    /** A validated cast load the UI should present. */
    data class CastLoadRequest(val mediaId: String, val kind: Kind)

    // One-shot delivery via a Channel, NOT a replay SharedFlow: a completed cast
    // load must not be re-delivered when MainActivity's repeatOnLifecycle(STARTED)
    // collector restarts (foreground return / config-change / fold). A replay=1
    // cache would re-emit the last load on every re-subscription and yank the user
    // back into the previously-cast channel. The Channel buffers an unconsumed
    // load until the collector attaches, then it is gone for good.
    private val _loadRequests = Channel<CastLoadRequest>(Channel.BUFFERED)
    val loadRequests: Flow<CastLoadRequest> = _loadRequests.receiveAsFlow()

    // GH #33: an in-place channel-change request for a receiver that is ALREADY
    // on the live player. A cast channel flip arrives as a new LOAD -> deep link;
    // navigating to a new player route doesn't re-tune the persistent player and
    // the guide-pop workaround churned. Instead the Navigation layer (which knows
    // the current route) routes a flip-while-playing here, and the live
    // PlayerScreen observes it and moves its own currentIndex -> the existing
    // ExoPlayer re-primes to the new channel with no nav, no pop.
    private val _castChannelRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val castChannelRequest: kotlinx.coroutines.flow.StateFlow<String?> = _castChannelRequest

    /** Ask the on-screen live player to re-tune to [channelId] in place. */
    fun requestCastChannel(channelId: String) {
        _castChannelRequest.value = channelId
    }

    /** Clear the pending in-place channel request once the player has consumed it. */
    fun consumeCastChannelRequest() {
        _castChannelRequest.value = null
    }

    /**
     * GH #33 companion remote: open [mediaId] through the SAME validated
     * load-request -> deep-link path a cast LOAD uses. MainActivity's collector
     * works from ANY app state (guide, menus, idle, already playing) and the
     * Navigation layer decides navigate-vs-in-place-re-tune, so the companion
     * host doesn't need to know what's on the TV screen. Returns false when the
     * channel doesn't resolve to something playable (unknown id / hidden).
     */
    suspend fun requestOpenChannel(mediaId: String): Boolean {
        val playable = runCatching { browseTree.resolveForPlayback(mediaId) }.getOrNull() != null
        if (playable) _loadRequests.trySend(CastLoadRequest(mediaId, Kind.LIVE))
        return playable
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var initialized = false
    private var started = false
    private var mediaManager: MediaManager? = null

    /** Initialise the receiver. No-op on non-TV devices and idempotent. */
    fun bootstrap(context: Context) {
        if (initialized) return
        // Only an Android TV device can be launched as a Cast Connect receiver.
        // Skip entirely on phones/tablets to avoid the GMS receiver surface.
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            !context.packageManager.hasSystemFeature("android.software.leanback")
        ) {
            return
        }
        val app = context.applicationContext
        val ok = runCatching {
            CastReceiverContext.initInstance(app)
            val mgr = CastReceiverContext.getInstance().mediaManager
            mgr.setMediaLoadCommandCallback(LoadCallback())
            mediaManager = mgr
            // GH #33 full-parity cast remote: listen for the sender's audio /
            // subtitle / speed / aspect commands on the custom namespace and
            // apply them to this receiver's own ExoPlayer.
            CastReceiverContext.getInstance()
                .setMessageReceivedListener(CastControl.NAMESPACE, ControlListener())
        }.isSuccess
        if (!ok) return
        initialized = true

        // Start the receiver while the app is foreground, stop it when it leaves,
        // per the Cast Connect lifecycle contract.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = start()
            override fun onStop(owner: LifecycleOwner) = stop()
        })
    }

    private fun start() {
        if (!initialized || started) return
        if (runCatching { CastReceiverContext.getInstance().start() }.isSuccess) started = true
    }

    /**
     * True while a Cast sender is connected to this device, i.e. the app is
     * running as a Cast Connect receiver right now. The live PlayerScreen uses
     * this to suppress its PiP-X-dismiss recovery pop (GH #33): a channel change
     * arrives as a cast LOAD that relaunches MainActivity, and the transient
     * window-Hidden during that relaunch would otherwise trip the pop and bounce
     * the TV back to the guide instead of re-tuning in place. Always false on a
     * device that never became a receiver (phones), so their PiP recovery is
     * untouched.
     */
    fun isReceivingCast(): Boolean =
        runCatching { CastReceiverContext.getInstance().senders.isNotEmpty() }.getOrDefault(false)

    private fun stop() {
        if (!initialized || !started) return
        stopPositionTicker()
        runCatching { CastReceiverContext.getInstance().stop() }
        started = false
    }

    // GH #33 cast scrubber: broadcast a ~1Hz live-position tick to connected
    // senders so the phone scrubber crawls. Started lazily on the first control
    // message (avoids the version-fragile EventCallback API); self-heals off when
    // no sender remains, and is cancelled when the receiver backgrounds (stop()).
    private var positionTickerJob: Job? = null

    private fun ensurePositionTicker() {
        if (positionTickerJob?.isActive == true) return
        positionTickerJob = scope.launch {
            while (isActive) {
                val senders = runCatching { CastReceiverContext.getInstance().senders }
                    .getOrNull().orEmpty()
                if (senders.isEmpty()) break
                val win = runCatching { holder.rewindWindow() }.getOrNull()
                val msg = CastControl.positionMessage(
                    canSeek = win != null,
                    isLive = runCatching { holder.isAtLiveEdge() }.getOrDefault(true),
                    positionWallMs = runCatching { holder.currentRewindWallMs() }.getOrNull()
                        ?: (win?.get(1) ?: 0L),
                    windowStartMs = win?.get(0) ?: 0L,
                    windowEndMs = win?.get(1) ?: 0L,
                )
                senders.forEach { s ->
                    runCatching {
                        CastReceiverContext.getInstance()
                            .sendMessage(CastControl.NAMESPACE, s.senderId, msg)
                    }
                }
                delay(1_000L)
            }
            positionTickerJob = null
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    /**
     * Hand the AerioTV MediaSession token to Cast Connect so the sender sees live
     * play/pause state and now-playing metadata. Called by
     * AerioMediaPlaybackService once its MediaLibrarySession exists. The Media3
     * 1.4.1 [androidx.media3.session.MediaSession.getSessionCompatToken] returns
     * exactly the [MediaSessionCompat.Token] MediaManager expects, so no bridging
     * is needed.
     */
    fun publishSessionToken(token: MediaSessionCompat.Token) {
        if (!initialized) return
        runCatching { mediaManager?.setSessionCompatToken(token) }
    }

    /**
     * Forward a MainActivity launch/new intent to MediaManager. Returns true if
     * the intent was a Cast load (MediaManager will then invoke the load
     * callback), false otherwise so the caller can fall through to its normal
     * intent handling (deep links, leanback launch).
     */
    fun handleIntent(intent: Intent): Boolean {
        if (!initialized) return false
        return runCatching { mediaManager?.onNewIntent(intent) ?: false }.getOrDefault(false)
    }

    private inner class LoadCallback : MediaLoadCommandCallback() {
        override fun onLoad(
            senderId: String?,
            loadRequestData: MediaLoadRequestData,
        ): Task<MediaLoadRequestData> {
            val source = TaskCompletionSource<MediaLoadRequestData>()
            val info: MediaInfo? = loadRequestData.mediaInfo
            val mediaId = resolveMediaId(info)
            val kind = resolveKind(info)
            if (mediaId.isNullOrBlank()) {
                source.setException(IllegalArgumentException("Cast load missing media id"))
                return source.task
            }
            // Validate against THIS device's playlist + effective base before we
            // tell the sender the load succeeded. A load for a channel this
            // receiver isn't signed into (or that no longer exists) fails loudly
            // rather than black-screening.
            scope.launch {
                when (kind) {
                    Kind.LIVE -> {
                        // Validate against THIS device's playlist + effective base
                        // before telling the sender the load succeeded, so a channel
                        // this receiver isn't signed into fails loudly rather than
                        // black-screening.
                        val playable = runCatching { browseTree.resolveForPlayback(mediaId) }
                            .getOrNull() != null
                        if (playable) {
                            _loadRequests.trySend(CastLoadRequest(mediaId, kind))
                            source.setResult(loadRequestData)
                        } else {
                            source.setException(
                                IllegalStateException("Channel $mediaId not available on this device"),
                            )
                        }
                    }
                    Kind.VOD -> {
                        // VOD casting is not yet wired end-to-end: the receiver would
                        // land on the movie DETAIL screen (not auto-play), so the
                        // sender would report success while nothing plays. Reject
                        // cleanly until the VOD cast path (auto-play route + sender)
                        // is built, so the sender surfaces a real failure instead.
                        source.setException(
                            UnsupportedOperationException("VOD casting is not supported yet"),
                        )
                    }
                }
            }
            return source.task
        }
    }

    /**
     * The playable identity a sender ships. Prefer an explicit customData field
     * (so the receiver rebuilds its own URL) and fall back to contentId. The
     * sender must NOT rely on us honouring a raw stream URL in contentId; we only
     * ever treat it as a channel/movie identifier.
     */
    private fun resolveMediaId(info: MediaInfo?): String? {
        info ?: return null
        info.customData?.let { custom ->
            custom.optString(KEY_MEDIA_ID).takeIf { it.isNotBlank() }?.let { return it }
        }
        return info.contentId?.takeIf { it.isNotBlank() }
    }

    private fun resolveKind(info: MediaInfo?): Kind {
        val raw = info?.customData?.optString(KEY_KIND)?.lowercase()
        return if (raw == VALUE_KIND_VOD) Kind.VOD else Kind.LIVE
    }

    // ── Full-parity cast remote (GH #33): receiver side ──────────────────────
    // A sender drives audio/subtitle/speed/aspect over the [CastControl] custom
    // namespace; we apply each to this receiver's live ExoPlayer (the same shared
    // holder the on-TV player uses) and reply with a full state snapshot so the
    // phone's pickers mirror what the TV is actually doing. Transport (play/pause)
    // is NOT here -- it rides the MediaSession Cast Connect already bridges.

    private inner class ControlListener : CastReceiverContext.MessageReceivedListener {
        override fun onMessageReceived(namespace: String, senderId: String?, message: String) {
            if (namespace != CastControl.NAMESPACE) return
            val json = runCatching { JSONObject(message) }.getOrNull() ?: return
            // ExoPlayer is single-threaded (main); scope is Main.immediate.
            scope.launch {
                // A live sender is present -> start the position tick. Done inside
                // the Main-immediate scope so the check-then-start is serialized
                // across senders/messages (never double-launches the ticker).
                ensurePositionTicker()
                when (json.optString(CastControl.KEY_CMD)) {
                    CastControl.CMD_GET_STATE -> {} // just reply below
                    CastControl.CMD_SET_AUDIO ->
                        runCatching {
                            holder.player?.selectAudioTrack(
                                json.optString(CastControl.KEY_TRACK_ID).toIntOrNull(),
                            )
                        }
                    CastControl.CMD_SET_TEXT ->
                        // "" (or unparseable) -> null -> Off.
                        runCatching {
                            holder.player?.selectSubtitleTrack(
                                json.optString(CastControl.KEY_TRACK_ID).toIntOrNull(),
                            )
                        }
                    CastControl.CMD_SET_SPEED ->
                        runCatching {
                            holder.player?.applySpeed(
                                json.optDouble(CastControl.KEY_SPEED, 1.0).toFloat(),
                            )
                        }
                    CastControl.CMD_SET_ASPECT ->
                        runCatching {
                            prefs.setPlayerAspectMode(
                                CastControl.AspectMode.fromKey(json.optString(CastControl.KEY_ASPECT)).key,
                            )
                        }
                    CastControl.CMD_SET_CHANNEL -> {
                        // Channel change: re-tune the on-screen live player in place
                        // (the load() path can't re-deliver here). The player picks
                        // up new tracks async, so don't snapshot yet -- the sender
                        // re-requests state after the flip settles.
                        val id = json.optString(CastControl.KEY_CHANNEL_ID)
                        if (id.isNotBlank()) requestCastChannel(id)
                        return@launch
                    }
                    CastControl.CMD_SET_AUDIO_ONLY -> {
                        val on = json.optBoolean(CastControl.KEY_AUDIO_ONLY)
                        runCatching { holder.setVideoTrackEnabled(!on) }
                    }
                    // Live-rewind seeks drive the SAME timeshift buffer the on-TV
                    // chrome scrubs (holder.playTimeshift/goLive). Falls through to
                    // replyState so the phone gets the post-seek position echo.
                    CastControl.CMD_GO_LIVE -> runCatching { holder.goLive() }
                    CastControl.CMD_SEEK_BY -> runCatching {
                        val delta = json.optLong(CastControl.KEY_DELTA_MS)
                        // Base off the current playhead if rewound, else the live edge.
                        val base = holder.currentRewindWallMs() ?: holder.rewindWindow()?.get(1)
                        if (base != null) commitRewindSeek(base + delta)
                    }
                    CastControl.CMD_SEEK_WALL -> runCatching {
                        commitRewindSeek(json.optLong(CastControl.KEY_TARGET_WALL_MS))
                    }
                    else -> return@launch
                }
                senderId?.let { replyState(it) }
            }
        }
    }

    /** Clamp + apply a rewind seek to an absolute wall-clock target, reusing the
     *  on-TV chrome's commitScrubWall rule (PlayerScreen.commitScrubWall): read
     *  the window FRESH, snap to live within 5s of the head, else re-open the
     *  timeshift buffer at the clamped target. No-ops when no session is rolling. */
    private fun commitRewindSeek(target: Long) {
        val w = holder.rewindWindow() ?: return
        val tail = w[0]
        val head = w[1]
        if (target >= head - 5_000) holder.goLive()
        else holder.playTimeshift(target.coerceAtLeast(tail))
    }

    /** Read the live player + aspect pref and push a full snapshot to [senderId]. */
    private suspend fun replyState(senderId: String) {
        val msg = buildRemoteStateMessage()
        runCatching {
            CastReceiverContext.getInstance()
                .sendMessage(CastControl.NAMESPACE, senderId, msg)
        }
    }

    /**
     * Build the full encoded remote-state snapshot. Shared by the Cast receiver's
     * [replyState] AND the LAN companion host (GH #33 second-screen), so both
     * remotes render the identical tracks / speed / aspect / stream-info / rewind
     * snapshot from the same source of truth.
     */
    suspend fun buildRemoteStateMessage(): String {
        val p = holder.player
        val audio = runCatching { p?.readAudioTracks() }.getOrNull().orEmpty()
        val curAid = runCatching { p?.readCurrentAid() }.getOrNull()
        val subs = runCatching { p?.readSubtitleTracks() }.getOrNull().orEmpty()
        val curSid = runCatching { p?.readCurrentSid() }.getOrNull()
        val speed = runCatching { p?.readSpeed() }.getOrNull() ?: 1f
        val aspect = CastControl.AspectMode.fromKey(
            runCatching { prefs.playerAspectMode.first() }.getOrNull(),
        )
        val win = runCatching { holder.rewindWindow() }.getOrNull()
        val state = CastControl.RemoteState(
            audio = audio.map { CastControl.Track(it.id.toString(), audioLabel(it), it.id == curAid) },
            text = subs.map { CastControl.Track(it.id.toString(), subtitleLabel(it), it.id == curSid) },
            textOff = curSid == null,
            speed = speed,
            aspect = aspect,
            // Derive from the LIVE track selection, not a cached flag: a channel
            // flip / watchdog re-prime re-enables the video track without going
            // through CMD_SET_AUDIO_ONLY, so a cached bool would stick stale-true.
            audioOnly = runCatching { receiverVideoDisabled() }.getOrDefault(false),
            streamInfo = runCatching { composeStreamInfo() }.getOrDefault(""),
            // Live-rewind window + playhead for the phone's FF/RW + scrubber.
            canSeek = win != null,
            isLive = runCatching { holder.isAtLiveEdge() }.getOrDefault(true),
            positionWallMs = runCatching { holder.currentRewindWallMs() }.getOrNull() ?: (win?.get(1) ?: 0L),
            windowStartMs = win?.get(0) ?: 0L,
            windowEndMs = win?.get(1) ?: 0L,
        )
        return CastControl.encodeState(state)
    }

    // Compose the same one-line labels the on-device Audio Track / Subtitles
    // sheets render, so the phone's reused pickers read identically.
    private fun audioLabel(t: AudioTrack): String = buildString {
        append(t.title.ifBlank { "Track ${t.id}" })
        val meta = buildList {
            if (t.lang.isNotBlank()) add(t.lang)
            if (t.codec.isNotBlank()) add(t.codec)
            if (t.channels.isNotBlank()) add(t.channels)
        }
        if (meta.isNotEmpty()) append("  ·  ${meta.joinToString("  ·  ")}")
    }

    private fun subtitleLabel(t: SubtitleTrack): String = buildString {
        append(t.title.ifBlank { "Track ${t.id}" })
        if (t.lang.isNotBlank()) append("  ·  ${t.lang}")
    }

    /** True when the receiver's ExoPlayer currently has its video track disabled
     *  (audio-only). Read from the LIVE track params rather than a cached flag so
     *  the snapshot self-heals: a channel flip / watchdog re-prime re-enables the
     *  video track (holder.setVideoTrackEnabled(true)) without going through
     *  CMD_SET_AUDIO_ONLY, which would otherwise leave a cached bool stuck true. */
    private fun receiverVideoDisabled(): Boolean =
        holder.player?.trackSelectionParameters
            ?.disabledTrackTypes?.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO) == true

    /** A one-line decode summary of what the TV is actually playing, for the
     *  phone's Stream Info sheet. Reads the live ExoPlayer's selected formats. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun composeStreamInfo(): String {
        val p = holder.player ?: return ""
        val v = p.videoFormat
        val a = p.audioFormat
        val parts = buildList {
            v?.let { f ->
                if (f.width > 0 && f.height > 0) add("${f.width}x${f.height}")
                if (f.frameRate > 0f) add("${f.frameRate.toInt()}fps")
                (f.codecs ?: f.sampleMimeType)?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            a?.let { f ->
                (f.codecs ?: f.sampleMimeType)?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (f.channelCount > 0) add("${f.channelCount}ch")
                if (f.sampleRate > 0) add("${f.sampleRate / 1000}kHz")
            }
        }
        return parts.joinToString("  ·  ").ifBlank { "No stream details available" }
    }

    companion object {
        /** customData keys shared with the sender (see the sender's load builder). */
        const val KEY_MEDIA_ID = "aerioMediaId"
        const val KEY_KIND = "aerioKind"
        const val VALUE_KIND_LIVE = "live"
        const val VALUE_KIND_VOD = "vod"
    }
}
