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
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class AerioCastSender @Inject constructor() {

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
         * Cast target. DEVICE-CONFIRMED (2026-07-15) that a Dispatcharr fMP4 stream
         * (H.264 + AAC via ?output_format=fmp4&output_profile=<aac>) plays on
         * Google's default web receiver over BOTH http (LAN) and https. NULL for a
         * non-Dispatcharr channel (a direct source has nothing to transcode). When
         * set it rides in MediaInfo.contentUrl; the Android-TV Cast Connect receiver
         * IGNORES contentUrl and rebuilds its own raw-TS URL from customData/entity,
         * so this one payload serves both receiver types. See [webCastMime].
         */
        val webCastUrl: String? = null,
        /** Content-Type for [webCastUrl]: "video/mp4" for fMP4, "application/x-mpegURL"
         *  for a future HLS manifest. Ignored when [webCastUrl] is null. */
        val webCastMime: String = "video/mp4",
    )

    private val _state = MutableStateFlow<State>(State.Unavailable)
    val state: StateFlow<State> = _state.asStateFlow()

    private companion object {
        const val TAG = "AerioCast"
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

    private var controlSession: CastSession? = null

    private val controlChannel = Cast.MessageReceivedCallback { _, ns, message ->
        if (ns != CastControl.NAMESPACE) return@MessageReceivedCallback
        runCatching {
            val json = JSONObject(message)
            when (json.optString(CastControl.KEY_CMD)) {
                CastControl.CMD_STATE -> _remoteState.value = CastControl.decodeState(json)
                CastControl.CMD_POSITION -> _position.value = CastControl.decodePosition(json)
            }
        }
    }

    private val remoteClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            _isPlaying.value = runCatching {
                currentSession()?.remoteMediaClient?.isPlaying
            }.getOrNull() ?: _isPlaying.value
            // Resumed-session content recovery: mediaInfo is often null at
            // onConnected and only lands with the first status update. No-op once
            // content is known (GH #33).
            currentSession()?.let { recoverContentFromSession(it) }
        }
    }

    private var warmed = false
    private var appContext: Context? = null
    private var mediaRouter: MediaRouter? = null
    private var discoveryCallback: MediaRouter.Callback? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** True when the build carries a registered Cast App ID. The UI can use this
     *  to avoid composing cast affordances at all on a Cast-disabled build. */
    val castConfigured: Boolean get() = BuildConfig.CAST_RECEIVER_APP_ID.isNotBlank()

    private val castStateListener = CastStateListener { st -> onCastState(st) }

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
        override fun onSessionEnded(session: CastSession, error: Int) = endCleanup()
        override fun onSessionSuspended(session: CastSession, reason: Int) = refreshFromContext()
        override fun onSessionStartFailed(session: CastSession, error: Int) = endCleanup()
        override fun onSessionResumeFailed(session: CastSession, error: Int) = endCleanup()
        override fun onSessionEnding(session: CastSession) {}
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

    /** End the current cast session (returns local playback to the phone). */
    fun stopCasting() {
        runCatching { CastContext.getSharedInstance()?.sessionManager?.endCurrentSession(true) }
    }

    // --- Cast remote controls (GH #33), all no-ops when not connected. ---

    fun play() { runCatching { currentSession()?.remoteMediaClient?.play() } }
    fun pause() { runCatching { currentSession()?.remoteMediaClient?.pause() } }
    fun togglePlayPause() {
        val rmc = currentSession()?.remoteMediaClient ?: return
        runCatching { if (rmc.isPlaying) rmc.pause() else rmc.play() }
    }

    /** Ask the receiver to (re)send its full audio/subtitle/speed/aspect snapshot. */
    fun requestRemoteState() = sendControl(CastControl.command(CastControl.CMD_GET_STATE))

    /** Re-tune the receiver to [channelId] over the reliable control channel. Used
     *  for channel changes while casting, since Cast Connect's load() path does not
     *  re-deliver to an already-running receiver (GH #33). */
    fun setRemoteChannel(channelId: String) =
        sendControl(CastControl.command(CastControl.CMD_SET_CHANNEL) { put(CastControl.KEY_CHANNEL_ID, channelId) })

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
    /** Skip the TV's live-rewind playhead by a signed delta (e.g. -30000/+30000). */
    fun seekBy(deltaMs: Long) =
        sendControl(CastControl.command(CastControl.CMD_SEEK_BY) { put(CastControl.KEY_DELTA_MS, deltaMs) })

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

    private fun onConnected(session: CastSession) {
        _state.value = State.Connected(session.castDevice?.friendlyName)
        attachControl(session)
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
        runCatching { session.remoteMediaClient?.registerCallback(remoteClientCallback) }
        _isPlaying.value = runCatching { session.remoteMediaClient?.isPlaying }.getOrNull() ?: true
        requestRemoteState()
    }

    private fun detachControl() {
        val s = controlSession ?: return
        runCatching { s.removeMessageReceivedCallbacks(CastControl.NAMESPACE) }
        runCatching { s.remoteMediaClient?.unregisterCallback(remoteClientCallback) }
        controlSession = null
        _remoteState.value = CastControl.RemoteState()
        _position.value = CastControl.PositionSnapshot()
    }

    private fun refreshFromContext() {
        pending = null
        detachControl()
        runCatching { CastContext.getSharedInstance()?.castState?.let { onCastState(it) } }
    }

    /** Terminal teardown: the session truly ended (not a transient suspend), so
     *  drop the cast content that drives the Now-Casting mini controller too. */
    private fun endCleanup() {
        _content.value = null
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
            // URL. DEVICE-CONFIRMED that a Dispatcharr fMP4 (H.264 + AAC) plays here
            // over http and https. The Cast Connect ATV receiver ignores contentUrl
            // and rebuilds its own raw-TS URL from customData/entity, so this one
            // payload serves both receiver types.
            builder.setContentUrl(content.webCastUrl)
                .setContentType(content.webCastMime)
        } else {
            // Cast Connect only: identity in customData, no playable URL.
            builder.setContentType(if (isVod) "video/mp4" else "video/mp2t")
        }
        val info = builder.build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .build()
        runCatching { client.load(request) }
    }
}

/**
 * Dispatcharr output-profile id that transcodes audio to AAC (H.264 video copied)
 * for the web/styled Cast receiver, which cannot DECODE AC-3 -- it only passes Dolby
 * through to an HDMI sink, so AC-3 is silent on Nest Hub / Home displays. Profile
 * ids are per-Dispatcharr-instance. TODO(GH#33): auto-detect via GET
 * /api/core/outputprofiles/ (pick the AAC profile) or expose a Cast output-profile
 * setting before shipping; hardcoded to the test server's "Web Player (AAC Audio)"
 * profile for the initial web-receiver integration.
 */
const val CAST_WEB_OUTPUT_PROFILE_ID = 2

/**
 * Build the web/styled-receiver cast URL from a channel's local proxy URL by asking
 * Dispatcharr for the fMP4 container + the AAC [outputProfileId]. Returns null when
 * [localUrl] is not a Dispatcharr /proxy/ts/stream URL (a direct source has nothing
 * to transcode), leaving Cast Connect as the only cast path for that channel.
 */
fun webReceiverCastUrl(localUrl: String, outputProfileId: Int = CAST_WEB_OUTPUT_PROFILE_ID): String? {
    if (!localUrl.contains("/proxy/ts/stream/", ignoreCase = true)) return null
    val sep = if (localUrl.contains('?')) '&' else '?'
    return "$localUrl${sep}output_format=fmp4&output_profile=$outputProfileId"
}
