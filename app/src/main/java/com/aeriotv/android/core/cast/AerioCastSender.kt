package com.aeriotv.android.core.cast

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.core.cast.hlsproxy.CastHlsProxySession
import com.aeriotv.android.core.cast.hlsproxy.UnsupportedCodecException
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sender-side Google Cast controller (GH #33) for the phone/tablet build. Owns
 * the CastContext lifecycle, tracks whether a cast route is available/connected,
 * and turns "cast this channel/movie" into a Cast Connect load whose payload is a
 * channel/movie IDENTITY (not a resolved stream URL). The AerioTV Android-TV
 * receiver rebuilds the real /proxy/ts/ URL against its own effective base, so a
 * phone on cellular never hands the TV a home-LAN URL.
 *
 * Everything is gated on BuildConfig.CAST_RECEIVER_APP_ID being non-blank and
 * wrapped in runCatching: with no registered Cast App ID, or on a device without
 * Google Play services, the sender stays inert ([State.Unavailable]) and the UI
 * hides the Cast button.
 */
@Singleton
class AerioCastSender @Inject constructor(
    private val hlsProxy: CastHlsProxySession,
    private val nativeDevices: NativeCastDevices,
) {

    /** Sender connection state, surfaced to the player chrome. */
    sealed interface State {
        /** Cast disabled (no App ID / no GMS) or no devices on the network. */
        data object Unavailable : State
        /** A cast route exists; the Cast button should show, not yet connected. */
        data object Available : State
        /** A session is being established. */
        data class Connecting(val deviceName: String?) : State
        /** Connected; local playback should be suspended and content cast. */
        data class Connected(val deviceName: String?) : State
    }

    /** What the active player wants mirrored to the cast device. */
    data class Content(
        val mediaId: String,
        val kind: AerioCastReceiverController.Kind,
        val title: String,
        val subtitle: String?,
        val artUri: String?,
        /**
         * Web/Styled-receiver playback URL (GH #33). A directly-playable stream URL
         * for the devices Cast Connect can't launch the native app on: legacy
         * Chromecast dongles, Nest Hub, Google Home displays, any non-Android-TV
         * Cast target. Phase 1 of the casting rework: this is now the PHONE-LOCAL
         * HLS proxy playlist (http://<phoneLanIp>:<port>/demuxed.m3u8) built by
         * [CastHlsProxySession], which ingests the channel's raw MPEG-TS and
         * re-serves it as sliding-window live HLS with fMP4 segments. The previous
         * Dispatcharr progressive-fMP4 URL stuttered every 10-15 s on the styled
         * receiver because a progressive live stream has no manifest clock. When
         * set it rides in MediaInfo.contentUrl; the Android-TV Cast Connect receiver
         * IGNORES contentUrl and rebuilds its own raw-TS URL from customData/entity,
         * so this one payload serves both receiver types. See [webCastMime].
         */
        val webCastUrl: String? = null,
        /** Content-Type for [webCastUrl]: "application/x-mpegURL" for the proxy's
         *  HLS playlist, "video/mp4" for a progressive fMP4 (legacy path).
         *  Ignored when [webCastUrl] is null. */
        val webCastMime: String = "video/mp4",
    )

    private val _state = MutableStateFlow<State>(State.Unavailable)
    val state: StateFlow<State> = _state.asStateFlow()

    private companion object {
        const val TAG = "AerioCast"

        /** How long the sender waits for the receiver-type handshake answer
         *  (CMD_RECEIVER_INFO) before it gives up and assumes a web receiver.
         *
         *  Both receivers now ANSWER (the web receiver.html answers
         *  platform=web-receiver as of 2026-09-13), so this is a last resort for a
         *  receiver too old to answer at all, not the normal web path. It is long
         *  because the only thing it has to outlast is a Cast Connect cold start
         *  of the Android TV app on a slow device; while it runs the card stays in
         *  its connecting state, which is honest, and nothing is guessed. */
        const val TARGET_PROBE_MS = 12_000L

        /** Re-send interval for the hello probe inside that window. */
        const val PROBE_RETRY_MS = 1_000L

        /** Discriminator of the receiver's measured-capability message, and of
         *  the sender's explicit request for a fresh one. Receiver-side name;
         *  see receiver.html. */
        const val CMD_CAPS = "caps"

        /** How long the audio plan waits for caps that a web receiver has not
         *  volunteered yet (an explicit request is sent first). Short: this runs
         *  in front of the load, and the fallback is only a refusal of AC-3. */
        const val CAPS_REQUEST_WAIT_MS = 3_000L

        /** Poll interval while waiting for that answer. */
        const val CAPS_POLL_MS = 100L

        /** Grace after a proxy load before a receiver that fetched the playlist
         *  but no segment is declared stale and re-loaded once. */
        const val STALE_RECEIVER_MS = 10_000L
    }

    /**
     * MEASURED receiver MSE codec support for this session, reported by the
     * receiver web app on [CastControl.DEBUG_NAMESPACE] (see receiver.html).
     * Null until the first caps message arrives; cleared when the session ends.
     *
     * This replaced a model allow-list that said a Google TV Streamer decodes
     * AC-3. The allow-list was right about the platform players and wrong
     * about the web receiver's MSE in the MUXED shape: the receiver's own
     * Chromium answered isTypeSupported("video/mp4;
     * codecs=\"avc1.64002A,ac-3\") false (gtvlogs/session16, 2026-09-12
     * 15:52:40) and that load died with Shaka 3015.
     *
     * As of 2026-09-13 the receiver probes the DEMUXED shape instead, which
     * is what the sender now loads: isTypeSupported("audio/mp4;
     * codecs=\"ac-3\") is TRUE on that same Streamer, so AC-3 / E-AC-3
     * passes through in its own audio/mp4 SourceBuffer. The keys are
     * unchanged ("ac-3", "ec-3", "mp4a.40.2", "avc1.64002A"); only the MIME
     * the receiver measures them with changed.
     */
    private var receiverCaps: Map<String, Boolean>? = null

    /** Logged once per distinct measurement so the telemetry stream that
     *  repeats the caps does not repeat the line. */
    private var loggedCaps: Map<String, Boolean>? = null

    /** Reads the `mse` object from a receiver debug message (the dedicated
     *  `type:"caps"` message on READY, and the copy that rides every
     *  telemetry snapshot) and stores it for this session. */
    private fun noteReceiverCaps(json: JSONObject) {
        val mse = json.optJSONObject("mse") ?: return
        val parsed = buildMap {
            for (key in mse.keys()) put(key, mse.optBoolean(key, false))
        }
        if (parsed.isEmpty()) return
        receiverCaps = parsed
        if (loggedCaps == parsed) return
        loggedCaps = parsed
        fun cap(key: String) = if (parsed[key] == true) "yes" else "no"
        Log.i(
            TAG,
            "[Cast] receiver caps: ac-3=${cap("ac-3")} ec-3=${cap("ec-3")} " +
                "aac=${cap("mp4a.40.2")} h264=${cap("avc1.64002A")}",
        )
    }

    /** Held so a session that connects AFTER the user starts watching immediately
     *  loads what is on screen. Cleared when the session ends. */
    private var pending: Content? = null

    // The content currently being cast, exposed so an app-wide "Now Casting" mini
    // controller (GH #33) can render its title/art and re-enter the player remote
    // after the user leaves the player screen. Null when nothing is cast.
    private val _content = MutableStateFlow<Content?>(null)
    val content: StateFlow<Content?> = _content.asStateFlow()

    // --- Full-parity cast remote (GH #33). Transport (play/pause) rides
    // RemoteMediaClient; the receiver-only controls (audio/subtitle/speed/aspect)
    // ride the [CastControl] custom channel. Both reset when the session ends. ---

    private val _remoteState = MutableStateFlow(CastControl.RemoteState())
    /** Receiver-reported audio tracks / subtitle tracks / speed / aspect, rendered
     *  by the phone's cast-remote pickers. Empty until the receiver answers. */
    val remoteState: StateFlow<CastControl.RemoteState> = _remoteState.asStateFlow()

    private val _position = MutableStateFlow(CastControl.PositionSnapshot())
    /** ~1Hz live-rewind playhead + window pushed by the receiver, driving the
     *  cast-remote scrubber. Resets to a non-seekable default when idle. */
    val position: StateFlow<CastControl.PositionSnapshot> = _position.asStateFlow()

    private val _isPlaying = MutableStateFlow(true)
    /** Whether the cast receiver is currently playing (vs paused). */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _canSkip = MutableStateFlow(false)
    /**
     * Whether the 30 s skip buttons can do anything on THIS receiver
     * (Logan 2026-09-13: the phone showed them for the AerioTV Remote transport
     * only). True once the receiver reports a live seekable range over
     * RemoteMediaClient, or once the native TV receiver reports a rewind window
     * on the control channel. Reset when the session ends.
     */
    val canSkip: StateFlow<Boolean> = _canSkip.asStateFlow()

    private val _switchingTo = MutableStateFlow<String?>(null)
    /**
     * Title of the channel a WEB-RECEIVER flip is currently switching to, from
     * the moment the flip is requested until the receiver reports PLAYING.
     * Null at every other time (and always null on the Cast Connect path, where
     * the TV re-tunes itself in place).
     *
     * The cast card and the remote sheet render it as "Switching to <channel>"
     * in the slot that otherwise carries the channel title, because a web
     * flip now tears the old media down: the receiver is genuinely on nothing
     * for the couple of seconds the new proxy session needs to warm up, and the
     * card must not keep claiming the old channel is playing.
     */
    val switchingTo: StateFlow<String?> = _switchingTo.asStateFlow()

    /** Monotonic flip counter. A newer web flip supersedes an in-flight one:
     *  the older job is cancelled AND, if it somehow gets one more turn, its
     *  epoch no longer matches and it refuses to load. */
    private var flipEpoch = 0

    // ── Receiver type: native Android TV app vs web receiver ────────────────
    // Cast Connect is ON again (2026-09-13) because the web receiver's Chromium
    // renderer tops out near 46 fps on a Google TV Streamer while the native app
    // renders 60. The framework decides which receiver actually launched, but it
    // exposes that decision NOWHERE public (CastSession / ApplicationMetadata
    // both hide it), so the sender asks the receiver directly: CMD_HELLO on
    // connect, and only the AerioTV Android TV receiver answers CMD_RECEIVER_INFO
    // with platform=android-tv-app. No answer inside [TARGET_PROBE_MS] means the
    // web receiver, which needs the phone-local HLS proxy and a playable
    // contentUrl. A live tune that arrives while the answer is still pending is
    // HELD (deferredTune) rather than guessed: guessing web would start an
    // unnecessary proxy plus a server transcode profile on a device that can play
    // the raw TS natively, and guessing native would black-screen a dongle.
    enum class ReceiverTarget { UNKNOWN, ANDROID_TV_APP, WEB_RECEIVER }

    private val _receiverTarget = MutableStateFlow(ReceiverTarget.UNKNOWN)
    /** Which receiver the live session is talking to. UNKNOWN while the handshake
     *  is in flight and whenever no session is connected. */
    val receiverTarget: StateFlow<ReceiverTarget> = _receiverTarget.asStateFlow()

    /** Cast device ids that have answered the hello probe with
     *  platform=android-tv-app at least once, so the picker can list those TVs
     *  first under "AerioTV on TV". */
    val nativeDeviceIds: StateFlow<Set<String>> = nativeDevices.deviceIds

    /** Device id of the session currently attached, captured while the session
     *  still exposes its CastDevice (it is cleared on an involuntary drop). */
    private var currentDeviceId: String? = null

    /** A live tune held until the receiver type is known. */
    private data class DeferredTune(
        val base: Content,
        val rawTsUrl: String,
        val headers: Map<String, String>,
    )

    private var deferredTune: DeferredTune? = null
    private var targetProbeJob: Job? = null

    /** Start the receiver-type handshake for a freshly attached session. The probe
     *  is re-sent every second until an answer lands or the window expires: the
     *  Cast Connect receiver's message listener does not exist until its process
     *  has started, so a single probe sent at connect can simply be dropped. */
    private fun probeReceiverTarget() {
        _receiverTarget.value = ReceiverTarget.UNKNOWN
        targetProbeJob?.cancel()
        targetProbeJob = senderScope.launch {
            var waited = 0L
            while (_receiverTarget.value == ReceiverTarget.UNKNOWN && waited < TARGET_PROBE_MS) {
                sendControl(CastControl.command(CastControl.CMD_HELLO))
                kotlinx.coroutines.delay(PROBE_RETRY_MS)
                waited += PROBE_RETRY_MS
            }
            if (_receiverTarget.value == ReceiverTarget.UNKNOWN) {
                Log.i(
                    TAG,
                    "[Cast] receiver type handshake timed out after " +
                        "${TARGET_PROBE_MS / 1000}s with no answer",
                )
                resolveReceiverTarget(ReceiverTarget.WEB_RECEIVER, answered = false)
            }
        }
    }

    /** Latch the receiver type, log WHICH of the three outcomes happened, and
     *  release any held tune. [answered] distinguishes an explicit receiver answer
     *  from the timeout fallback. */
    private fun resolveReceiverTarget(target: ReceiverTarget, answered: Boolean = true) {
        if (_receiverTarget.value == target) return
        _receiverTarget.value = target
        if (target == ReceiverTarget.ANDROID_TV_APP) {
            // Remember this TV so the picker can list it under "AerioTV on TV"
            // from now on. A device only moves after its first native session.
            nativeDevices.remember(currentDeviceId)
            Log.i(TAG, "[Cast] target=android-tv-app, native playback (receiver answered)")
        } else if (answered) {
            Log.i(TAG, "[Cast] target=web-receiver (receiver answered)")
        } else {
            Log.i(TAG, "[Cast] target=web-receiver (no answer, handshake timed out)")
        }
        deferredTune?.let { held ->
            deferredTune = null
            dispatchLiveTune(held)
        }
    }

    /** Apply a receiver's own answer to the hello probe. An unrecognised platform
     *  is left UNKNOWN so the timeout decides rather than a bad guess. */
    private fun noteReceiverInfo(json: JSONObject) {
        // 2026-09-13 (Chromecast Ultra, iPhone log 02:38:56-02:39:02): the web
        // receiver sent its caps ONCE at READY on the debug namespace, and this
        // sender attached that channel after READY, so the audio plan ran with
        // "caps not received, assuming no AC-3" and refused the stream. The
        // hello reply is the one message that is always read before the plan,
        // so the measurement now rides along with it.
        noteReceiverCaps(json)
        when (json.optString(CastControl.KEY_PLATFORM)) {
            CastControl.VALUE_PLATFORM_ANDROID_TV ->
                resolveReceiverTarget(ReceiverTarget.ANDROID_TV_APP)
            CastControl.VALUE_PLATFORM_WEB ->
                resolveReceiverTarget(ReceiverTarget.WEB_RECEIVER)
        }
    }

    /** Route a live tune to the path the resolved receiver type needs. */
    private fun dispatchLiveTune(tune: DeferredTune) {
        when (_receiverTarget.value) {
            ReceiverTarget.UNKNOWN -> {
                deferredTune = tune
                // Show the channel on the card immediately so the UI is not dead
                // during the handshake; the load itself waits for the answer.
                pending = tune.base
                _content.value = tune.base
                Log.i(TAG, "[Cast] tune held: receiver type not yet known")
            }
            ReceiverTarget.ANDROID_TV_APP -> castLiveNative(tune.base)
            ReceiverTarget.WEB_RECEIVER ->
                if (tune.rawTsUrl.isNotBlank()) {
                    castLiveViaProxy(tune.base, tune.rawTsUrl, tune.headers)
                } else {
                    // No playable URL (event-style channel): identity-only load.
                    setContent(tune.base)
                }
        }
    }

    /**
     * Cast Connect path: hand the native Android TV receiver the channel IDENTITY
     * only and let it tune like a local tap (its own ExoPlayer, its own effective
     * base, AC-3 passthrough). Deliberately does NOT start the phone HLS proxy and
     * does NOT resolve a Dispatcharr output profile: nothing on the phone touches
     * this stream. Any proxy left over from a previous web session is torn down.
     */
    private fun castLiveNative(base: Content) {
        proxyLoadJob?.cancel()
        proxyLoadJob = null
        _switchingTo.value = null
        runCatching { hlsProxy.stop() }
        Log.i(
            TAG,
            "[Cast] target=android-tv-app, native playback: channel=${base.title} " +
                "id=${base.mediaId} proxy=none profile=none",
        )
        setContent(base)
        // Cast Connect only, unchanged: the initial load launches the receiver
        // via the entity deep link, but Cast Connect will not re-deliver a
        // second load() to an already-running receiver, so the channel also
        // goes over the reliable control channel, which re-tunes the TV in
        // place on every flip. The web receiver never sees this message any
        // more: it gets a real unload + re-load instead (see castLiveViaProxy).
        setRemoteChannel(base.mediaId)
    }

    private var controlSession: CastSession? = null

    private val controlChannel = Cast.MessageReceivedCallback { _, ns, message ->
        if (ns != CastControl.NAMESPACE) return@MessageReceivedCallback
        runCatching {
            val json = JSONObject(message)
            // The web receiver page spells the discriminator "type", the Android TV
            // receiver spells it "cmd" like every other frame on this namespace.
            if (json.optString(CastControl.KEY_TYPE) == CastControl.CMD_RECEIVER_INFO) {
                noteReceiverInfo(json)
                return@runCatching
            }
            when (json.optString(CastControl.KEY_CMD)) {
                CastControl.CMD_STATE -> _remoteState.value = CastControl.decodeState(json)
                CastControl.CMD_POSITION -> _position.value = CastControl.decodePosition(json)
                // Both receivers answer the hello probe and NAME themselves, so
                // neither target is ever inferred from silence.
                CastControl.CMD_RECEIVER_INFO -> noteReceiverInfo(json)
            }
        }
    }

    /**
     * Receiver DEBUG channel (2026-09-12). The receiver web app broadcasts a
     * JSON player-state snapshot on [CastControl.DEBUG_NAMESPACE]; this logs it
     * as ONE line per message. It exists because the receiver page's own CONSOLE
     * output never reaches logcat, so the 15:10 Google TV Streamer freeze (one
     * frame at 1920x1080, then position -58 ms / BUFFERING / rate 0.0 for 45 s)
     * left us unable to see the buffered ranges or the seek range and therefore
     * unable to tell a seek-outside-the-buffer from a starved buffer.
     *
     * A field the receiver did not send prints "?"; the callback never throws,
     * and logs nothing but this one line.
     */
    private val receiverDebugChannel = Cast.MessageReceivedCallback { _, ns, message ->
        if (ns != CastControl.DEBUG_NAMESPACE) return@MessageReceivedCallback
        runCatching {
            val j = JSONObject(message)
            noteReceiverCaps(j)
            // The dedicated caps message carries no player state; it is fully
            // handled above and must not print a row of "?" fields.
            if (j.optString("type") == "caps") return@runCatching
            fun str(key: String): String = if (j.has(key) && !j.isNull(key)) j.optString(key) else "?"
            fun num(key: String, decimals: Int): String =
                if (j.has(key) && !j.isNull(key)) {
                    String.format(java.util.Locale.US, "%.${decimals}f", j.optDouble(key))
                } else {
                    "?"
                }
            // buffered: array of [start, end] pairs -> [a-b][c-d]; an empty
            // array is the interesting case (nothing buffered at all), so it
            // prints "none" rather than an empty string.
            val buffered = if (j.has("buffered") && !j.isNull("buffered")) {
                val arr = j.optJSONArray("buffered")
                if (arr == null || arr.length() == 0) {
                    "none"
                } else {
                    (0 until arr.length()).joinToString("") { i ->
                        val r = arr.optJSONArray(i)
                        val a = r?.optDouble(0) ?: Double.NaN
                        val b = r?.optDouble(1) ?: Double.NaN
                        String.format(java.util.Locale.US, "[%.3f-%.3f]", a, b)
                    }
                }
            } else {
                "?"
            }
            val seek = if (j.has("seek")) {
                val r = j.optJSONArray("seek")
                if (r == null) {
                    "none"
                } else {
                    String.format(java.util.Locale.US, "[%.3f-%.3f]", r.optDouble(0), r.optDouble(1))
                }
            } else {
                "?"
            }
            val bw = if (j.has("bw") && !j.isNull("bw")) j.optLong("bw").toString() else "?"
            val err = if (j.has("err") && !j.isNull("err")) {
                j.optString("err").ifBlank { "none" }
            } else {
                "none"
            }
            // Anything the receiver adds later still reaches logcat: the known
            // keys keep their order and formatting, then every remaining
            // top-level key prints generically. "type"/"mse" belong to the caps
            // path above and would only repeat it here.
            val known = setOf(
                "ev", "t", "buffered", "ready", "state", "rate",
                "seek", "bufTime", "bw", "hist", "err", "type", "mse",
            )
            val extras = buildString {
                val it = j.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    if (key in known) continue
                    val v = j.opt(key)
                    val text = when {
                        v == null || v === JSONObject.NULL -> "null"
                        v is JSONObject || v is org.json.JSONArray -> v.toString()
                        else -> v.toString()
                    }
                    append(' ').append(key).append('=').append(text)
                }
            }
            var line = "[Cast] receiver: ev=${str("ev")} t=${num("t", 3)} buffered=$buffered " +
                "ready=${str("ready")} state=${str("state")} rate=${str("rate")} " +
                "seek=$seek bufTime=${num("bufTime", 2)} bw=$bw hist=${str("hist")} err=$err" +
                extras
            if (line.length > 1000) line = line.take(997) + "..."
            Log.i(TAG, line)
        }
    }

    /**
     * Logged player state of the receiver, so the card reflects what the TV's
     * player actually reports instead of a local guess. IDLE means the
     * receiver has no active playback: the transport button must re-issue the
     * load rather than call play() (which a receiver in IDLE ignores).
     */
    private val _receiverIdle = MutableStateFlow(true)
    /** True while the receiver's player is IDLE (nothing playing/paused). */
    val receiverIdle: StateFlow<Boolean> = _receiverIdle.asStateFlow()

    /** Last state logged, so a status burst does not spam identical lines. */
    private var lastLoggedPlayerState: Pair<Int, Int>? = null
    /** Loads re-issued because the receiver fell back to IDLE, per content. */
    private var idleReloadAttempts = 0
    private var lastLoadAtMs = 0L
    /** mediaId of the last load issued, so a channel flip gets a fresh
     *  idle-retry budget while a retry of the SAME load does not. */
    private var lastLoadedMediaId: String? = null

    private val remoteClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = currentSession()?.remoteMediaClient
            val status = runCatching { client?.mediaStatus }.getOrNull()
            val playerState = status?.playerState ?: MediaStatus.PLAYER_STATE_UNKNOWN
            val idleReason = status?.idleReason ?: MediaStatus.IDLE_REASON_NONE
            if (lastLoggedPlayerState != (playerState to idleReason)) {
                lastLoggedPlayerState = playerState to idleReason
                Log.i(
                    TAG,
                    "[Cast] remote player state=${playerStateName(playerState)} " +
                        "idleReason=${idleReasonName(idleReason)}",
                )
            }
            // Receiver-reported, not a local guess: BUFFERING counts as
            // playing (the TV is working on it), everything else is not.
            _isPlaying.value = playerState == MediaStatus.PLAYER_STATE_PLAYING ||
                playerState == MediaStatus.PLAYER_STATE_BUFFERING ||
                playerState == MediaStatus.PLAYER_STATE_LOADING
            _receiverIdle.value = playerState == MediaStatus.PLAYER_STATE_IDLE ||
                playerState == MediaStatus.PLAYER_STATE_UNKNOWN
            // "Switching to <channel>" ends the moment the receiver is actually
            // playing the new channel, not when the load was merely accepted.
            if (playerState == MediaStatus.PLAYER_STATE_PLAYING) _switchingTo.value = null
            // A live HLS receiver advertises what it can seek within; without a
            // range the skip buttons stay visible but disabled.
            _canSkip.value = runCatching { status?.liveSeekableRange != null }.getOrDefault(false)
            // Resumed-session content recovery: mediaInfo is often null at
            // onConnected and only lands with the first status update. No-op once
            // content is known (GH #33).
            currentSession()?.let { recoverContentFromSession(it) }
            // The load landed but the receiver dropped straight back to IDLE
            // (Logan 2026-09-12: the TV showed nothing while the proxy served
            // segments for five minutes). Re-issue the load once instead of
            // leaving a live proxy feeding a receiver that stopped asking.
            maybeReloadAfterIdle(playerState, idleReason)
        }

        /**
         * The receiver NAMING the failure. Added 2026-09-12: an IDLE with
         * idleReason=ERROR says only "the receiver could not play the
         * media", while MediaError carries the detailed error code (Cast
         * MEDIA_ERROR_MESSAGE / Shaka code) that distinguishes a manifest
         * parse failure from an MSE append failure from a decode failure.
         * Without it a cast diagnosis has to guess between them.
         */
        override fun onMediaError(error: com.google.android.gms.cast.MediaError) {
            Log.w(
                TAG,
                "[Cast] media error code=${error.detailedErrorCode} " +
                    "reason=${error.reason} type=${error.type}",
            )
        }
    }

    /** One automatic re-load when the receiver reports IDLE for a reason that
     *  means playback did not survive the load (ERROR / FINISHED / an
     *  un-started load). Bounded and time-gated so it can never loop. */
    private fun maybeReloadAfterIdle(playerState: Int, idleReason: Int) {
        if (playerState != MediaStatus.PLAYER_STATE_IDLE) {
            if (playerState == MediaStatus.PLAYER_STATE_PLAYING) idleReloadAttempts = 0
            return
        }
        val content = _content.value ?: return
        if (content.webCastUrl == null) return // Cast Connect: the app owns playback
        // A web flip STOPS the receiver on purpose; that IDLE is the flip, not a
        // failed load, and re-loading here would race the new proxy session.
        if (_switchingTo.value != null) return
        if (idleReloadAttempts >= 1) return
        if (System.currentTimeMillis() - lastLoadAtMs < 5_000L) return
        val session = currentSession() ?: return
        idleReloadAttempts++
        Log.i(TAG, "[Cast] receiver idle after load; re-issuing load (attempt $idleReloadAttempts)")
        loadOnSession(session, content)
    }

    private fun playerStateName(state: Int): String = when (state) {
        MediaStatus.PLAYER_STATE_IDLE -> "IDLE"
        MediaStatus.PLAYER_STATE_PLAYING -> "PLAYING"
        MediaStatus.PLAYER_STATE_PAUSED -> "PAUSED"
        MediaStatus.PLAYER_STATE_BUFFERING -> "BUFFERING"
        MediaStatus.PLAYER_STATE_LOADING -> "LOADING"
        else -> "UNKNOWN"
    }

    private fun idleReasonName(reason: Int): String = when (reason) {
        MediaStatus.IDLE_REASON_NONE -> "NONE"
        MediaStatus.IDLE_REASON_FINISHED -> "FINISHED"
        MediaStatus.IDLE_REASON_CANCELED -> "CANCELED"
        MediaStatus.IDLE_REASON_INTERRUPTED -> "INTERRUPTED"
        MediaStatus.IDLE_REASON_ERROR -> "ERROR (the receiver could not play the media)"
        else -> "reason $reason"
    }

    /**
     * Plain-English meaning for the Cast session end codes actually observed
     * on Logan's devices; [com.google.android.gms.cast.CastStatusCodes]
     * stringifies most of the 20xx range as a bare number.
     */
    private fun castStatusMeaning(code: Int): String = when (code) {
        2055 -> "the receiver application stopped or was replaced " +
            "(receiver-side error or the Cast page went away)"
        2155 -> "the receiver could not be reached (network loss or the device went away)"
        2161 -> "the session was ended deliberately (Stop Casting)"
        else -> com.google.android.gms.cast.CastStatusCodes.getStatusCodeString(code)
    }

    /** Set by onSessionEnding, which the Cast SDK only calls for a deliberate,
     *  graceful teardown. An involuntary drop never sets it. */
    private var endingGracefully = false

    /** Friendly name of the most recently connected receiver, captured while the
     *  session still exposes its CastDevice (see onConnected). */
    private var lastDeviceName: String? = null
    private var warmed = false
    private var appContext: Context? = null
    private var mediaRouter: MediaRouter? = null
    private var discoveryCallback: MediaRouter.Callback? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** True when the build carries a registered Cast App ID. The UI can use this
     *  to avoid composing cast affordances at all on a Cast-disabled build. */
    val castConfigured: Boolean get() = BuildConfig.CAST_RECEIVER_APP_ID.isNotBlank()

    private val castStateListener = CastStateListener { st -> onCastState(st) }

    /** One involuntary session end: the cast dropped without the user stopping
     *  it (network loss, receiver died). CastNotificationController renders it
     *  as a heads-up notification so a backgrounded phone still tells the user
     *  why the TV fell back to the idle screen. */
    data class InvoluntaryEnd(val deviceName: String?, val contentTitle: String?)
    private val _involuntaryEnd = kotlinx.coroutines.flow.MutableSharedFlow<InvoluntaryEnd>(extraBufferCapacity = 4)
    val involuntaryEnd: kotlinx.coroutines.flow.SharedFlow<InvoluntaryEnd> = _involuntaryEnd

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onConnected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onConnected(session)
        override fun onSessionStarting(session: CastSession) {
            _state.value = State.Connecting(session.castDevice?.friendlyName)
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) {
            _state.value = State.Connecting(session.castDevice?.friendlyName)
        }
        // A true END clears the cast content (hides the Now-Casting controller).
        // A SUSPEND is transient (network blip / brief background) -- keep content
        // so the controller reappears on resume; the render gate on state==Connected
        // already hides it during the gap. onCastState below drops state to
        // Available/Unavailable during the suspend, which is the correct hide.
        override fun onSessionEnded(session: CastSession, error: Int) {
            // Kenton 2026-08-18 (log 20:14:19): a healthy cast ended from the
            // network side (route unselected reason=3, device gone from
            // discovery) while the app was backgrounded, and the only visible
            // effect was the ongoing notification silently vanishing. A
            // non-zero error means the user did NOT stop this cast; say so.
            val graceful = endingGracefully
            endingGracefully = false
            Log.i(TAG, "cast session ended: error=$error graceful=$graceful")
            if (!graceful && error != com.google.android.gms.cast.CastStatusCodes.SUCCESS) {
                val device = session.castDevice?.friendlyName ?: lastDeviceName
                val what = _content.value?.title
                Log.w(TAG, "cast session ended involuntarily: error=$error " +
                    castStatusMeaning(error))
                _involuntaryEnd.tryEmit(InvoluntaryEnd(device, what))
                surfaceCastFailure(
                    if (device.isNullOrBlank()) "Casting disconnected"
                    else "Casting to $device disconnected",
                )
            }
            endCleanup()
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) = refreshFromContext()
        override fun onSessionStartFailed(session: CastSession, error: Int) = endCleanup()
        override fun onSessionResumeFailed(session: CastSession, error: Int) = endCleanup()
        override fun onSessionEnding(session: CastSession) {
            // The SDK announces a GRACEFUL end here before onSessionEnded. An
            // abrupt loss (receiver died, network dropped) skips this callback
            // entirely, which is the only reliable way to tell the two apart:
            // measured on a Z Fold 5 against a Google TV Streamer, a deliberate
            // Stop Casting ends with error=2161 while a killed receiver ends
            // with 2155/2055, so the error code alone cannot discriminate.
            endingGracefully = true
            Log.i(TAG, "cast session ending gracefully (user-initiated)")
        }
    }

    /** Initialise CastContext + listeners. Idempotent; no-op on a Cast-disabled
     *  build or a device without Google Play services. */
    fun warm(context: Context) {
        if (warmed || !castConfigured) return
        appContext = context.applicationContext
        val ok = runCatching {
            val cc = CastContext.getSharedInstance(context.applicationContext)
            cc.addCastStateListener(castStateListener)
            cc.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
            onCastState(cc.castState)
            Log.i(TAG, "warm ok; castState=${cc.castState} appId=${BuildConfig.CAST_RECEIVER_APP_ID}")
        }.isSuccess
        if (!ok) Log.w(TAG, "warm FAILED (no Play services, or invalid app id?)")
        warmed = ok
        if (!ok) return
        // Drive route discovery while the app is foregrounded so CastState
        // reflects device availability (and the Cast button appears). Merely
        // adding a CastStateListener does NOT start the mDNS scan; a MediaRouter
        // callback with REQUEST_DISCOVERY does. Tie it to the process lifecycle
        // so we are not scanning in the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = startDiscovery()
            override fun onStop(owner: LifecycleOwner) = stopDiscovery()
        })
        startDiscovery()
    }

    private fun startDiscovery() {
        if (!warmed || discoveryCallback != null) return
        val ctx = appContext ?: return
        // Disable the Wi-Fi hardware multicast filter (device-wide while held) so
        // inbound mDNS (_googlecast._tcp) advertisements are delivered. Samsung
        // and many OEMs drop multicast by default even with the permission, so
        // GMS/mediarouter discovery silently finds nothing. The lock affects the
        // radio globally, so GMS's own discovery process benefits too.
        if (multicastLock == null) {
            val acquired = runCatching {
                val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifi?.createMulticastLock("aeriotv-cast-discovery")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
            multicastLock = acquired
            Log.i(TAG, "multicastLock acquired=${acquired?.isHeld == true}")
        }
        val router = runCatching { MediaRouter.getInstance(ctx) }.getOrNull() ?: return
        val selector = routeSelector() ?: return
        val cb = object : MediaRouter.Callback() {
            override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = syncState()
            override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = syncState()
            override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = syncState()
        }
        // Passive discovery (REQUEST_DISCOVERY, not PERFORM_ACTIVE_SCAN): enough to
        // keep CastState current app-wide while foreground without the battery cost
        // of a continuous active scan. The route chooser dialog does its own active
        // scan while open.
        val ok = runCatching {
            router.addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        }.isSuccess
        if (ok) {
            mediaRouter = router
            discoveryCallback = cb
        }
    }

    private fun stopDiscovery() {
        val router = mediaRouter
        val cb = discoveryCallback
        if (router != null && cb != null) runCatching { router.removeCallback(cb) }
        discoveryCallback = null
        multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        multicastLock = null
    }

    /** Recompute state from CastContext (called when routes change). */
    private fun syncState() {
        runCatching { CastContext.getSharedInstance()?.castState?.let { onCastState(it) } }
    }

    /** The MediaRouteSelector for AerioTV's receiver, used by the Compose route
     *  chooser to discover cast devices. Null when Cast is unavailable. */
    fun routeSelector(): MediaRouteSelector? =
        runCatching { CastContext.getSharedInstance()?.mergedSelector }.getOrNull()

    /** Set (or clear, with null) the content mirrored to the cast device. When a
     *  session is already connected the load fires immediately; otherwise it is
     *  held until one connects. */
    fun setContent(content: Content?) {
        pending = content
        _content.value = content
        val session = currentSession() ?: return
        if (content != null) loadOnSession(session, content)
    }

    /**
     * End the current cast session (returns local playback to the phone).
     *
     * The media session is stopped FIRST, and only then is the Cast session
     * ended. Ending the session alone tears the receiver app down out from
     * under a playing HTMLMediaElement: the Google TV Streamer log for the
     * card's X (2026-09-12 02:29:26) shows CastV2.Receiver.Stop.In and
     * "Stopping app" with no preceding media request of type STOP anywhere in
     * the session, and the audio pipeline outlived the video surface long
     * enough for Logan to hear it ("video stopped but audio still playing in
     * the background"). A MEDIA STOP makes the receiver unload the element
     * and release its decoders in the normal order before the app goes away.
     */
    fun stopCasting() {
        endingGracefully = true
        // Best effort and deliberately not awaited: the session teardown below
        // must happen even if the receiver never answers (a wedged receiver is
        // exactly when the user reaches for the X).
        runCatching { currentSession()?.remoteMediaClient?.stop() }
        runCatching { CastContext.getSharedInstance()?.sessionManager?.endCurrentSession(true) }
    }

    /**
     * Stop what is playing on the receiver but KEEP the Cast session (iOS parity,
     * 2026-09-25): the playing card's X and the sheet's Stop casting return the
     * card to its idle "Select a Channel" state instead of disconnecting. Nothing
     * resumes locally. The proxy (ingest, socket, foreground service) is torn down
     * with the media, since nothing is being served any more; the next channel
     * tap starts a fresh proxy session exactly like the first cast did.
     */
    fun stopPlayback() {
        Log.i(TAG, "[Cast] stop: playback ended, session kept (idle card), no local resume")
        // Receiver content recovery must not resurrect the media we just stopped
        // from a status update that still carries its MediaInfo.
        recoverySuppressed = true
        pending = null
        _content.value = null
        _switchingTo.value = null
        flipEpoch++
        proxyLoadJob?.cancel()
        proxyLoadJob = null
        deferredTune = null
        lastLoadedMediaId = null
        idleReloadAttempts = 0
        staleReceiverJob?.cancel()
        runCatching { currentSession()?.remoteMediaClient?.stop() }
        senderScope.launch(Dispatchers.IO) { runCatching { hlsProxy.stop() } }
    }

    /** Stale-receiver watchdog armed by each proxy load. */
    private var staleReceiverJob: kotlinx.coroutines.Job? = null
    /** mediaId whose load the watchdog already re-issued: once per channel. */
    private var staleReloadDoneFor: String? = null

    /**
     * Stale receiver page (iOS incident 2026-09-25): a session attached to a
     * receiver page that was already running fetched the playlist ONCE and then
     * never asked for a segment, so the TV sat on a black screen while the
     * proxy served nothing. After each proxy load, if the receiver has fetched
     * a playlist but requested no segment within [STALE_RECEIVER_MS], the load
     * is re-issued once for that channel and the decision is logged.
     */
    private fun armStaleReceiverWatchdog(content: Content, countersAtLoad: Pair<Int, Int>) {
        staleReceiverJob?.cancel()
        if (content.webCastUrl == null) return
        val (playlistsAtLoad, segmentsAtLoad) = countersAtLoad
        staleReceiverJob = senderScope.launch {
            kotlinx.coroutines.delay(STALE_RECEIVER_MS)
            if (_content.value?.mediaId != content.mediaId) return@launch
            val (playlists, segments) = hlsProxy.fetchCounters()
            val newPlaylists = playlists - playlistsAtLoad
            val newSegments = segments - segmentsAtLoad
            if (newSegments > 0) {
                Log.i(
                    TAG,
                    "[Cast] receiver healthy ${STALE_RECEIVER_MS / 1000}s after load: " +
                        "playlists=$newPlaylists segments=$newSegments",
                )
                return@launch
            }
            if (newPlaylists == 0) {
                Log.w(
                    TAG,
                    "[Cast] receiver fetched NOTHING ${STALE_RECEIVER_MS / 1000}s after load " +
                        "(no playlist, no segment); leaving it to the idle reload",
                )
                return@launch
            }
            if (staleReloadDoneFor == content.mediaId) {
                Log.w(
                    TAG,
                    "[Cast] stale receiver again: playlists=$newPlaylists segments=0 " +
                        "after the re-issued load; not retrying",
                )
                return@launch
            }
            val session = currentSession() ?: return@launch
            staleReloadDoneFor = content.mediaId
            Log.w(
                TAG,
                "[Cast] stale receiver: fetched the playlist ($newPlaylists) but no segment " +
                    "${STALE_RECEIVER_MS / 1000}s after load; re-issuing the load once",
            )
            loadOnSession(session, content)
        }
    }

    /** Set by [stopPlayback] so a trailing status update carrying the stopped
     *  media's MediaInfo is not recovered as live content; cleared by the next
     *  load or a new session. */
    private var recoverySuppressed = false

    // --- Cast remote controls (GH #33), all no-ops when not connected. ---

    /**
     * Start playback on the receiver. A receiver whose player is IDLE has
     * nothing to resume and IGNORES play() (Logan 2026-09-12: the card's Play
     * button did nothing visible while the proxy was serving segments), so in
     * that state this re-issues the load with autoplay instead.
     */
    fun play() {
        val session = currentSession() ?: return
        val content = _content.value
        if (_receiverIdle.value && content != null) {
            Log.i(TAG, "[Cast] play pressed while receiver idle; re-issuing load")
            loadOnSession(session, content)
            return
        }
        runCatching { session.remoteMediaClient?.play() }
    }

    fun pause() { runCatching { currentSession()?.remoteMediaClient?.pause() } }

    fun togglePlayPause() {
        val rmc = currentSession()?.remoteMediaClient ?: return
        // Drive off the receiver's reported player state, not rmc.isPlaying
        // alone: an IDLE receiver is neither playing nor pausable.
        if (_receiverIdle.value) {
            play()
            return
        }
        runCatching { if (rmc.isPlaying) rmc.pause() else play() }
    }

    /** Ask the receiver to (re)send its full audio/subtitle/speed/aspect snapshot. */
    fun requestRemoteState() = sendControl(CastControl.command(CastControl.CMD_GET_STATE))

    /** Re-tune the receiver to [channelId] over the reliable control channel. Used
     *  for channel changes while casting, since Cast Connect's load() path does not
     *  re-deliver to an already-running receiver (GH #33). */
    fun setRemoteChannel(channelId: String) =
        sendControl(CastControl.command(CastControl.CMD_SET_CHANNEL) { put(CastControl.KEY_CHANNEL_ID, channelId) })

    /** GH #47: mirror a LIVE channel to the active cast session in place.
     *
     *  The single shared tune path for "the TV should now show [channelId]"
     *  while casting - used both by PlayerScreen's cast mode and by the channel
     *  list's tap handler (which stays on the list instead of opening the
     *  player). Applies the same dedup guard as PlayerScreen historically did:
     *  re-selecting the channel the TV is already on must NOT re-issue a load
     *  (needless receiver reload/flicker), including the resumed-cast case
     *  where the receiver only recovered the channel TITLE as mediaId.
     *
     *  Returns false when no session is connected (caller should fall back to
     *  local playback / navigation); true when the cast now owns the tune.
     */
    fun tuneLiveChannel(
        channelId: String,
        title: String,
        subtitle: String? = null,
        artUri: String? = null,
        localUrl: String = "",
        /** HTTP headers the local player would send for [localUrl]; the
         *  proxy's ingest must present the same identity to the provider. */
        headers: Map<String, String> = emptyMap(),
    ): Boolean {
        if (state.value !is State.Connected) return false
        val cc = content.value
        val alreadyCastingThisChannel = cc?.mediaId == channelId || cc?.mediaId == title
        if (!alreadyCastingThisChannel) {
            val base = Content(
                mediaId = channelId,
                kind = AerioCastReceiverController.Kind.LIVE,
                title = title,
                subtitle = subtitle,
                artUri = artUri,
            )
            // Route by receiver type (2026-09-13): the native Android TV app gets
            // the identity and tunes itself (no proxy, no output profile); a web
            // receiver gets the phone-local HLS proxy playlist. While the
            // handshake is still in flight the tune is HELD, never guessed.
            // The Cast Connect setChannel push now lives in castLiveNative, so
            // it fires for the Android TV app on every flip exactly as before
            // and never for a web receiver, whose flip is a full unload + load.
            dispatchLiveTune(DeferredTune(base, localUrl, headers))
        }
        return true
    }

    /** In-flight "wait for the proxy, then load" task; superseded by every
     *  channel flip and cancelled when the session ends. */
    private var proxyLoadJob: Job? = null
    private val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Casting rework P1: start (or re-point) the local HLS proxy at
     * [rawTsUrl], wait until the playlist has at least two segments, then
     * fire the actual load with MediaInfo.contentUrl set to the proxy
     * playlist. The delay matters: the styled receiver fetches the
     * playlist the moment load() lands, and an empty live playlist is a
     * hard receiver error, not a retry.
     *
     * The mini controller shows the channel immediately ([_content] is
     * set before the wait) so the UI does not appear dead during the
     * segment warm-up (~6 s of video plus provider join latency).
     *
     * On refusal (unsupported codec) or warm-up timeout the user gets the
     * same Toast surface the cast tap paths already use, and the proxy is
     * torn down; the content stays visible so Stop Casting still works.
     */
    private fun castLiveViaProxy(base: Content, rawTsUrl: String, headers: Map<String, String>) {
        // A channel change on the web receiver is a FRESH CAST, not a splice
        // (2026-09-13). Re-pointing the proxy at a new ingest generation inside
        // the same HLS stream made a Chromecast Ultra's decoder chew on the
        // splice for ~5 s: BUFFERING/PLAYING toggling, the playhead creeping in
        // 0.1 s stall-skip steps, then a gap jump, all while the OLD channel
        // was still on screen. So the old media is stopped, the old proxy
        // session is torn all the way down (ingest cancelled, port freed), and
        // a brand-new proxy session feeds a brand-new load with the same
        // contract as the initial cast. The receiver unloads and shows its own
        // loading state for the new channel, which is honest.
        val previous = _content.value
        val isFlip = previous != null && previous.mediaId != base.mediaId
        pending = base
        _content.value = base
        proxyLoadJob?.cancel()
        val epoch = ++flipEpoch
        if (isFlip) {
            _switchingTo.value = base.title
            Log.i(
                TAG,
                "[Cast] web flip ${previous?.title} -> ${base.title}: " +
                    "unload + new proxy session (epoch=$epoch)",
            )
        }
        proxyLoadJob = senderScope.launch {
            if (isFlip) {
                // Unload the receiver FIRST so it is not fetching from a proxy
                // that is about to disappear, then free the socket and the
                // provider connection before the new session binds.
                runCatching { currentSession()?.remoteMediaClient?.stop() }
                kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { hlsProxy.stop() } }
                lastLoadedMediaId = null
                idleReloadAttempts = 0
            }
            // Cast audio (Logan 2026-09-13): AC-3 / E-AC-3 PASSES THROUGH to
            // the web receiver and the Dispatcharr output-profile path is
            // gone. Nothing server-side is asked for, nothing is transcoded
            // on the phone, and local playback was never involved.
            //
            // What changed: the proxy now serves a DEMUXED master (separate
            // video and audio renditions, one SourceBuffer each), and a
            // Google TV Streamer answers
            //   isTypeSupported('audio/mp4; codecs="ac-3"') -> true
            // for exactly that shape, while the old muxed video/mp4 form
            // answered false (the measurement that used to force the AAC
            // profile). Emby plays AC-3 through the same audio/mp4 path.
            //
            // So the ingest is ALWAYS the plain stream URL, and the only
            // decision left is whether this receiver may have the AC-3
            // bitstream: [receiverCaps] ac-3 / ec-3, measured by the
            // receiver itself. False plus an AC-3 source is refused by name
            // rather than transcoded. AAC sources pass through as before
            // (a channel_configuration 0 layout is still refused).
            val caps = awaitReceiverCaps()
            if (caps == null) {
                Log.i(TAG, "[Cast] caps not received, assuming no AC-3")
            }
            val ac3Ok = caps != null && (caps["ac-3"] == true || caps["ec-3"] == true)
            val receiverName = lastDeviceName ?: (state.value as? State.Connected)?.deviceName
            val receiverModel = runCatching {
                currentSession()?.castDevice?.modelName
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: receiverName ?: "unknown"
            Log.i(
                TAG,
                "[Cast] audio plan: receiver=$receiverModel " +
                    "caps=${if (caps == null) "none" else "measured"} " +
                    "ac-3=${if (caps?.get("ac-3") == true) "yes" else "no"} " +
                    "ec-3=${if (caps?.get("ec-3") == true) "yes" else "no"} " +
                    "aac=${if (caps?.get("mp4a.40.2") == true) "yes" else "no"} " +
                    "-> ingest=plain audio=${if (ac3Ok) "passthrough" else "aac-only"}",
            )
            val started = try {
                hlsProxy.startChannel(
                    rawTsUrl = rawTsUrl,
                    headers = headers,
                    allowAc3Passthrough = ac3Ok,
                    onNotice = { message -> surfaceCastFailure(message) },
                )
            } catch (e: com.aeriotv.android.core.cast.hlsproxy.IngestConnectionLimitException) {
                Log.w(TAG, "[Cast] load channel=${base.title} refused: ${e.notice.message}")
                surfaceCastFailure("${e.notice.title}. ${e.notice.message}")
                null
            } catch (e: UnsupportedCodecException) {
                Log.w(
                    TAG,
                    "[Cast] load channel=${base.title} " +
                        "audio=${e.codecName} mode=refused",
                )
                surfaceCastFailure(describeRefusal(e))
                null
            } catch (e: TimeoutCancellationException) {
                surfaceCastFailure(
                    "Dispatcharr did not send any data for this channel within " +
                        "${CastHlsProxySession.READY_TIMEOUT_MS / 1000} seconds.",
                )
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Kenton 2026-08-18 (log 19:55:07): a second channel flip
                // cancels this job via proxyLoadJob?.cancel(); that is
                // supersession, not failure, and the flip that cancelled us
                // goes on to cast fine. Swallowing the CancellationException
                // in the Throwable arm below toasted "Can't cast this channel
                // right now" over a working cast AND broke structured
                // cancellation. Rethrow.
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "cast proxy start failed: $t")
                surfaceCastFailure("Can't cast this channel right now")
                null
            } ?: run {
                // Nothing is going to start: stop claiming a switch is under way.
                if (epoch == flipEpoch) _switchingTo.value = null
                return@launch
            }
            // A newer flip already owns the receiver; this one must not load
            // over it even if it got one more turn before its cancellation.
            if (epoch != flipEpoch) {
                Log.i(TAG, "[Cast] flip epoch=$epoch superseded by $flipEpoch; not loading")
                return@launch
            }
            Log.i(
                TAG,
                "[Cast] load channel=${base.title} " +
                    "audio=${started.audioCodec.ifBlank { "unknown" }} mode=passthrough",
            )
            // The DEMUXED master is the only playlist the proxy serves:
            // the audio rendition declares ac-3 / ec-3 honestly in its own
            // audio/mp4 SourceBuffer.
            val ready = base.copy(
                webCastUrl = started.demuxedPlaylistUrl,
                webCastMime = "application/x-mpegURL",
            )
            pending = ready
            _content.value = ready
            currentSession()?.let { loadOnSession(it, ready) }
        }
    }

    /**
     * The specific reason this channel cannot be cast (Logan: "cannot cast
     * this channel" was not detailed enough). Video is never re-encoded
     * and audio is never transcoded, so the video arm names the codec and
     * the audio arm names the fix the user can make in Dispatcharr.
     */
    private fun describeRefusal(e: UnsupportedCodecException): String {
        val codec = e.codecName.removeSuffix(" audio").removeSuffix(" video")
        if (e.isVideo) {
            return "This channel's video is $codec, which Google Cast receivers cannot play."
        }
        if (codec.startsWith("AC-3") || codec.startsWith("E-AC-3")) {
            return "This receiver cannot decode this channel's surround audio (AC-3)."
        }
        return "This channel's audio is $codec, which the receiver cannot decode."
    }

    private fun surfaceCastFailure(message: String) {
        val ctx = appContext ?: return
        runCatching {
            android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun setRemoteAudioTrack(id: String) =
        sendControl(CastControl.command(CastControl.CMD_SET_AUDIO) { put(CastControl.KEY_TRACK_ID, id) })

    /** [id] == null selects "Off" (disables subtitles on the receiver). */
    fun setRemoteTextTrack(id: String?) =
        sendControl(CastControl.command(CastControl.CMD_SET_TEXT) { put(CastControl.KEY_TRACK_ID, id ?: "") })

    fun setRemoteSpeed(speed: Float) =
        sendControl(CastControl.command(CastControl.CMD_SET_SPEED) { put(CastControl.KEY_SPEED, speed.toDouble()) })

    fun setRemoteAspect(mode: CastControl.AspectMode) =
        sendControl(CastControl.command(CastControl.CMD_SET_ASPECT) { put(CastControl.KEY_ASPECT, mode.key) })

    /** Toggle audio-only (drop the TV's video track) on the receiver. */
    fun setRemoteAudioOnly(on: Boolean) =
        sendControl(CastControl.command(CastControl.CMD_SET_AUDIO_ONLY) { put(CastControl.KEY_AUDIO_ONLY, on) })

    // GH #33 live-rewind controls: drive the receiver's timeshift buffer. The
    // receiver clamps to its own rewind window and echoes a fresh state snapshot.
    /** Skip the TV's live-rewind playhead by a signed delta (the Skip Intervals ms). */
    fun seekBy(deltaMs: Long) =
        sendControl(CastControl.command(CastControl.CMD_SEEK_BY) { put(CastControl.KEY_DELTA_MS, deltaMs) })

    /**
     * The remote sheet's Skip Intervals skip, for whichever receiver is live.
     *
     *  - Cast Connect (native Android TV app): the existing [CMD_SEEK_BY] control
     *    command, which drives the TV's own Live Rewind timeshift buffer.
     *  - Web receiver: a RemoteMediaClient seek off the approximate stream
     *    position, clamped to the reported live seekable range. With no range
     *    reported, forward is a no-op and back still attempts the seek.
     */
    fun skipBy(deltaMs: Long) {
        if (_receiverTarget.value == ReceiverTarget.ANDROID_TV_APP) {
            seekBy(deltaMs)
            return
        }
        val client = currentSession()?.remoteMediaClient ?: return
        val status = runCatching { client.mediaStatus }.getOrNull()
        val range = runCatching { status?.liveSeekableRange }.getOrNull()
        val current = runCatching { client.approximateStreamPosition }.getOrDefault(0L)
        var target = current + deltaMs
        if (range != null) {
            val lo = minOf(range.startTime, range.endTime)
            val hi = maxOf(range.startTime, range.endTime)
            if (deltaMs > 0 && current >= hi) return
            target = target.coerceIn(lo, hi)
        } else if (deltaMs > 0) {
            return
        }
        val position = target.coerceAtLeast(0L)
        Log.i(TAG, "[Cast] skip ${deltaMs / 1000}s -> ${position}ms range=${range != null}")
        runCatching {
            client.seek(MediaSeekOptions.Builder().setPosition(position).build())
        }
    }

    /** Seek the TV's live-rewind playhead to an absolute wall-clock target (scrubber). */
    fun seekToWall(targetWallMs: Long) =
        sendControl(CastControl.command(CastControl.CMD_SEEK_WALL) { put(CastControl.KEY_TARGET_WALL_MS, targetWallMs) })

    /** Jump the TV back to the live edge. */
    fun goLiveRemote() =
        sendControl(CastControl.command(CastControl.CMD_GO_LIVE))

    private fun sendControl(message: String) {
        val session = currentSession() ?: return
        runCatching { session.sendMessage(CastControl.NAMESPACE, message) }
    }

    /** Ask the receiver for a FRESH capability measurement. Sent on both
     *  namespaces because a page old enough to answer hello without `mse` may
     *  only listen for this on the debug channel. */
    private fun requestReceiverCaps() {
        val session = currentSession() ?: return
        val message = CastControl.command(CMD_CAPS)
        runCatching { session.sendMessage(CastControl.NAMESPACE, message) }
        runCatching { session.sendMessage(CastControl.DEBUG_NAMESPACE, message) }
    }

    /** Caps for the audio plan. Returns what the receiver already told us, and
     *  otherwise ASKS and waits up to [CAPS_REQUEST_WAIT_MS] before giving up:
     *  on a Chromecast Ultra the only caps message was the one at READY, which
     *  this sender's channel was attached too late to see. */
    private suspend fun awaitReceiverCaps(): Map<String, Boolean>? {
        receiverCaps?.let { return it }
        requestReceiverCaps()
        var waited = 0L
        while (waited < CAPS_REQUEST_WAIT_MS) {
            kotlinx.coroutines.delay(CAPS_POLL_MS)
            waited += CAPS_POLL_MS
            receiverCaps?.let { return it }
        }
        return null
    }

    private fun onConnected(session: CastSession) {
        // Remember the name while the session still has its CastDevice. By the
        // time onSessionEnded fires on an involuntary drop the device reference
        // is already cleared, so reading it there yields null and the alert
        // degrades to a bare "Casting disconnected" (observed on a Z Fold 5
        // against a Google TV Streamer, 2026-08-19).
        session.castDevice?.friendlyName?.let { lastDeviceName = it }
        currentDeviceId = session.castDevice?.deviceId
        _state.value = State.Connected(session.castDevice?.friendlyName)
        attachControl(session)
        // Name the load decision. In the 15:10 window the Nothing Phone showed
        // a cast card and never loaded anything at all (no proxy start, no
        // media LOAD reached the receiver) and the sender log said nothing
        // about why: this branch is the decision, and it printed nothing. The
        // iPhone logs an equivalent line. Logging only; the behaviour below is
        // unchanged.
        Log.i(
            TAG,
            "[Cast] session started: pendingChannel=${pending?.mediaId ?: "none"} " +
                "willLoad=${pending != null}",
        )
        pending?.let { loadOnSession(session, it) }
        // Session RESUMED after an app force-close / reinstall: `pending` is null
        // (this @Singleton sender was recreated with the process), so nothing set
        // _content and the Now-Casting mini controller + its Stop would stay hidden
        // until the user re-selected a channel. Recover the content from whatever
        // the receiver is already playing so the indicator reappears immediately
        // (GH #33). mediaInfo may not be populated yet on resume, so this is also
        // retried from the RemoteMediaClient status callback below.
        if (pending == null) recoverContentFromSession(session)
    }

    /** Rebuild [Content] from the session's currently-loaded media so a resumed
     *  cast (app restarted) still drives the mini controller. Idempotent: no-op
     *  once content is known. */
    private fun recoverContentFromSession(session: CastSession) {
        if (_content.value != null || pending != null) return
        if (recoverySuppressed) return
        val info = runCatching { session.remoteMediaClient?.mediaInfo }.getOrNull() ?: return
        val custom = info.customData
        val mediaId = custom?.optString(AerioCastReceiverController.KEY_MEDIA_ID)?.takeIf { it.isNotBlank() }
            ?: info.contentId?.takeIf { it.isNotBlank() }
            ?: return
        val kind = if (custom?.optString(AerioCastReceiverController.KEY_KIND) ==
            AerioCastReceiverController.VALUE_KIND_VOD
        ) {
            AerioCastReceiverController.Kind.VOD
        } else {
            AerioCastReceiverController.Kind.LIVE
        }
        val meta = info.metadata
        _content.value = Content(
            mediaId = mediaId,
            kind = kind,
            title = meta?.getString(MediaMetadata.KEY_TITLE)?.takeIf { it.isNotBlank() } ?: mediaId,
            subtitle = meta?.getString(MediaMetadata.KEY_SUBTITLE),
            artUri = runCatching { meta?.images?.firstOrNull()?.url?.toString() }.getOrNull(),
        )
    }

    /** Bind the custom control channel + RemoteMediaClient callback to a freshly
     *  connected session and pull the receiver's initial control snapshot. */
    private fun attachControl(session: CastSession) {
        if (controlSession === session) return
        detachControl()
        controlSession = session
        runCatching { session.setMessageReceivedCallbacks(CastControl.NAMESPACE, controlChannel) }
        // Second, diagnostic-only channel: the receiver's player-state
        // snapshots (see [receiverDebugChannel]). Registered and removed
        // exactly like the control channel, and a receiver that does not
        // broadcast on it simply never delivers a message.
        runCatching { session.setMessageReceivedCallbacks(CastControl.DEBUG_NAMESPACE, receiverDebugChannel) }
        runCatching { session.remoteMediaClient?.registerCallback(remoteClientCallback) }
        // Ask the receiver what it is before any load decision is made.
        probeReceiverTarget()
        val state = runCatching { session.remoteMediaClient?.mediaStatus?.playerState }.getOrNull()
        _isPlaying.value = state == MediaStatus.PLAYER_STATE_PLAYING ||
            state == MediaStatus.PLAYER_STATE_BUFFERING
        _receiverIdle.value = state == null ||
            state == MediaStatus.PLAYER_STATE_IDLE ||
            state == MediaStatus.PLAYER_STATE_UNKNOWN
        requestRemoteState()
    }

    private fun detachControl() {
        val s = controlSession ?: return
        runCatching { s.removeMessageReceivedCallbacks(CastControl.NAMESPACE) }
        runCatching { s.removeMessageReceivedCallbacks(CastControl.DEBUG_NAMESPACE) }
        runCatching { s.remoteMediaClient?.unregisterCallback(remoteClientCallback) }
        controlSession = null
        _switchingTo.value = null
        targetProbeJob?.cancel()
        targetProbeJob = null
        _receiverTarget.value = ReceiverTarget.UNKNOWN
        deferredTune = null
        receiverCaps = null
        loggedCaps = null
        _remoteState.value = CastControl.RemoteState()
        _position.value = CastControl.PositionSnapshot()
        _canSkip.value = false
    }

    private fun refreshFromContext() {
        pending = null
        detachControl()
        runCatching { CastContext.getSharedInstance()?.castState?.let { onCastState(it) } }
    }

    /** Terminal teardown: the session truly ended (not a transient suspend), so
     *  drop the cast content that drives the Now-Casting mini controller too.
     *  The HLS proxy's lifetime is the cast session's: kill the ingest here so
     *  the provider connection is released the moment casting stops. A transient
     *  SUSPEND deliberately does NOT stop the proxy (refreshFromContext): the
     *  receiver keeps fetching segments across the blip and resumes cleanly. */
    private fun endCleanup() {
        recoverySuppressed = false
        staleReceiverJob?.cancel()
        _content.value = null
        _switchingTo.value = null
        flipEpoch++
        _receiverIdle.value = true
        lastLoggedPlayerState = null
        lastLoadedMediaId = null
        idleReloadAttempts = 0
        proxyLoadJob?.cancel()
        proxyLoadJob = null
        deferredTune = null
        hlsProxy.stop()
        refreshFromContext()
    }

    private fun onCastState(castState: Int) {
        Log.i(TAG, "castState -> $castState (1=NO_DEVICES 2=NOT_CONNECTED 3=CONNECTING 4=CONNECTED)")
        // Preserve a live Connected/Connecting state; CastState only distinguishes
        // "devices available or not" versus connection, and the session listener
        // is the authority on the connected transitions.
        _state.value = when (castState) {
            CastState.CONNECTED -> State.Connected(currentSession()?.castDevice?.friendlyName)
            CastState.CONNECTING -> State.Connecting(currentSession()?.castDevice?.friendlyName)
            CastState.NOT_CONNECTED -> State.Available
            else -> State.Unavailable // NO_DEVICES_AVAILABLE
        }
    }

    private fun currentSession(): CastSession? =
        runCatching { CastContext.getSharedInstance()?.sessionManager?.currentCastSession }.getOrNull()

    private fun loadOnSession(session: CastSession, content: Content) {
        val client = session.remoteMediaClient ?: return
        val isVod = content.kind == AerioCastReceiverController.Kind.VOD
        val metadata = MediaMetadata(
            if (isVod) MediaMetadata.MEDIA_TYPE_MOVIE else MediaMetadata.MEDIA_TYPE_GENERIC,
        ).apply {
            putString(MediaMetadata.KEY_TITLE, content.title)
            content.subtitle?.takeIf { it.isNotBlank() }
                ?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            content.artUri?.takeIf { it.isNotBlank() }
                ?.let { art -> runCatching { addImage(WebImage(Uri.parse(art))) } }
        }
        val custom = JSONObject().apply {
            put(AerioCastReceiverController.KEY_MEDIA_ID, content.mediaId)
            put(
                AerioCastReceiverController.KEY_KIND,
                if (isVod) AerioCastReceiverController.VALUE_KIND_VOD
                else AerioCastReceiverController.VALUE_KIND_LIVE,
            )
        }
        // Cast Connect load handoff (GH #33). WargLoadHandler owns EVERY load on
        // the Android-TV receiver: with a valid MediaInfo.entity it deep-links
        // (launch intent) into the channel; without one it queues a "cast load
        // intent" that only fires on the next relaunch (device-observed: a channel
        // change didn't apply until Back). So ALWAYS set the entity to the app's
        // own deep link -- it matches the aeriotv://channel|vod/<id> scheme
        // MainActivity parses, and re-tunes the persistent player each load. The
        // relaunch's transient window-Hidden used to trip the PiP-X-dismiss pop;
        // that is now suppressed while a cast sender is connected (see
        // AerioCastReceiverController.isReceiving + PlayerScreen's pop guard).
        val entity: String =
            if (isVod) "aeriotv://vod/${content.mediaId}" else "aeriotv://channel/${content.mediaId}"
        // contentId carries the identity too (fallback if customData is stripped);
        // the receiver treats it only as an identifier, never as a playable URL.
        val builder = MediaInfo.Builder(content.mediaId)
            .setStreamType(
                if (isVod) MediaInfo.STREAM_TYPE_BUFFERED else MediaInfo.STREAM_TYPE_LIVE,
            )
            .setEntity(entity)
            .setMetadata(metadata)
            .setCustomData(custom)
        if (content.webCastUrl != null) {
            // Web/Styled receiver path (legacy Chromecast, Nest Hub, any non-ATV):
            // they can't launch the native app, so they need a directly-playable
            // URL. Casting rework P1: the URL is the phone-local HLS proxy playlist
            // (live HLS + fMP4/CMAF segments); declare the segment container so the
            // receiver's HLS stack skips container sniffing.
            builder.setContentUrl(content.webCastUrl)
                .setContentType(content.webCastMime)
            if (content.webCastMime == "application/x-mpegURL") {
                builder.setHlsSegmentFormat(HlsSegmentFormat.FMP4)
                    .setHlsVideoSegmentFormat(com.google.android.gms.cast.HlsVideoSegmentFormat.FMP4)
            }
        } else {
            // Cast Connect only: identity in customData, no playable URL.
            builder.setContentType(if (isVod) "video/mp4" else "video/mp2t")
        }
        val info = builder.build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            // Autoplay: a channel tap must start playback on the TV with no
            // second gesture. The receiver still reports its own player state
            // (see remoteClientCallback) and an IDLE fallback re-loads once.
            .setAutoplay(true)
            .build()
        if (lastLoadedMediaId != content.mediaId) {
            lastLoadedMediaId = content.mediaId
            idleReloadAttempts = 0
            staleReloadDoneFor = null
        }
        lastLoadAtMs = System.currentTimeMillis()
        recoverySuppressed = false
        _receiverIdle.value = true // until the receiver says otherwise
        Log.i(
            TAG,
            "[Cast] load sent channel=${content.title} autoplay=true " +
                "contentUrl=${if (content.webCastUrl != null) "proxy" else "none"} " +
                "mime=${content.webCastMime}",
        )
        val countersAtLoad = hlsProxy.fetchCounters()
        runCatching {
            client.load(request).setResultCallback { result ->
                val status = result.status
                if (status.isSuccess) {
                    Log.i(TAG, "[Cast] load accepted by the receiver")
                    armStaleReceiverWatchdog(content, countersAtLoad)
                } else {
                    Log.w(
                        TAG,
                        "[Cast] load REJECTED code=${status.statusCode} " +
                            castStatusMeaning(status.statusCode),
                    )
                }
            }
        }
    }
}

// Cast audio, 2026-09-13: there is no Dispatcharr output profile any more.
// The cast session ingests the PLAIN stream URL and the proxy's demuxed
// audio rendition hands AC-3 / E-AC-3 straight to a receiver whose MSE
// measured audio/mp4 support for it. The phone transcodes nothing and the
// server is asked for nothing.
//
// Casting rework P1: the Dispatcharr progressive-fMP4 helper
// (webReceiverCastUrl + CAST_WEB_OUTPUT_PROFILE_ID) that used to live here is
// gone. The styled receiver stuttered on that URL every 10-15 s because a
// progressive live stream has no manifest clock; the phone-local HLS proxy
// (core/cast/hlsproxy) replaces it and works for NON-Dispatcharr sources too.
