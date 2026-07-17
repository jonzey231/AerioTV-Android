package com.aeriotv.android.core.cast.companion

import android.content.Context
import android.util.Log
import com.aeriotv.android.core.cast.CastControl
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side companion-remote client (GH #33 second-screen). Opens a WebSocket to a
 * discovered TV ([CompanionDiscovery.Tv]), performs the [CompanionProtocol] pairing
 * handshake (remembered token, else a 6-digit code the user reads off the TV), then
 * exposes the SAME command surface + state flows that [CastRemoteOverlay] already
 * consumes from the Cast sender -- so the exact same remote UI drives a paired TV.
 *
 * Player-control messages are [CastControl] `cmd` frames (channel / play-pause /
 * seek / audio / subtitle / speed / aspect); the TV pushes CMD_STATE + ~1Hz
 * CMD_POSITION back, which feed [remoteState] / [position] / [isPlaying].
 */
@Singleton
class CompanionRemoteController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed interface Conn {
        data object Idle : Conn
        data class Connecting(val name: String?) : Conn
        /** Connected but not paired: the TV is showing a code the user must enter. */
        data class NeedsPairing(val name: String?) : Conn
        data class Connected(val name: String?) : Conn
        data class Failed(val reason: String) : Conn
    }

    private companion object {
        const val TAG = "CompanionClient"
        const val PREFS = "aeriotv_companion_client"
        fun tokenKey(deviceId: String) = "token_$deviceId"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val client by lazy { HttpClient(OkHttp) { install(WebSockets) } }

    private var session: DefaultClientWebSocketSession? = null
    private var job: Job? = null
    private var current: CompanionDiscovery.Tv? = null
    private var deviceName: String? = null
    // Monotonic connection generation: each connect() bumps it, and a cancelled
    // job's tail only mutates shared state if it is still the current attempt.
    // Without this, cancelling job A (disconnect / switch to TV B) makes A's
    // suspended read throw CancellationException, whose onFailure/tail would
    // otherwise clobber B's Connecting/session with a stale Failed/null
    // (adversarial review 2026-07-15).
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    private val _connection = MutableStateFlow<Conn>(Conn.Idle)
    val connection: StateFlow<Conn> = _connection.asStateFlow()

    private val _remoteState = MutableStateFlow(CastControl.RemoteState())
    val remoteState: StateFlow<CastControl.RemoteState> = _remoteState.asStateFlow()

    private val _position = MutableStateFlow(CastControl.PositionSnapshot())
    val position: StateFlow<CastControl.PositionSnapshot> = _position.asStateFlow()

    private val _isPlaying = MutableStateFlow(true)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // Best-effort title of what the TV is playing, for the "Controlling <TV>" mini
    // card (GH #33): seeded from the hello frame, updated by every channel this
    // phone sends. Blank when unknown.
    private val _nowPlaying = MutableStateFlow("")
    val nowPlaying: StateFlow<String> = _nowPlaying.asStateFlow()

    // The last channel id this phone sent to the TV: dedups re-tunes (re-entering
    // the player for the same channel must not re-issue setChannel) and resolves
    // the card-tap re-entry target (GH #33).
    private val _currentChannelId = MutableStateFlow<String?>(null)
    val currentChannelId: StateFlow<String?> = _currentChannelId.asStateFlow()

    // ---- connection lifecycle ----

    fun connect(tv: CompanionDiscovery.Tv) {
        disconnect()
        current = tv
        val gen = generation.incrementAndGet()
        _connection.value = Conn.Connecting(tv.name)
        job = scope.launch {
            runCatching {
                val s = client.webSocketSession { url("ws://${tv.host}:${tv.port}/remote") }
                if (gen != generation.get()) { runCatching { s.close() }; return@launch }
                session = s
                // Authenticate immediately with a remembered token (blank on first pair);
                // the TV replies authOk, or authFail + shows a code -> NeedsPairing.
                s.send(Frame.Text(CompanionProtocol.auth(token = storedToken(tv.deviceId), code = null)))
                for (frame in s.incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    handleFrame(tv, json)
                }
            }.onFailure {
                // Only settle failure if this is still the current attempt: a
                // disconnect()/switch cancels the read (CancellationException),
                // whose tail must NOT overwrite the successor's state.
                if (gen == generation.get()) _connection.value = Conn.Failed(it.message ?: "connect failed")
            }
            if (gen == generation.get()) {
                session = null
                if (_connection.value is Conn.Connected || _connection.value is Conn.Connecting) {
                    _connection.value = Conn.Idle
                }
            }
        }
    }

    /** Submit the 6-digit code the user read off the TV (from a [Conn.NeedsPairing] state). */
    fun submitPairingCode(code: String) {
        val tv = current ?: return
        _connection.value = Conn.Connecting(deviceName ?: tv.name)
        send(CompanionProtocol.auth(token = null, code = code.trim()))
    }

    fun disconnect() {
        generation.incrementAndGet() // invalidate the in-flight attempt's tail
        job?.cancel()
        job = null
        val s = session
        session = null
        scope.launch { runCatching { s?.close() } }
        _remoteState.value = CastControl.RemoteState()
        _position.value = CastControl.PositionSnapshot()
        _isPlaying.value = true
        _nowPlaying.value = ""
        _currentChannelId.value = null
        if (_connection.value !is Conn.Failed) _connection.value = Conn.Idle
    }

    private fun handleFrame(tv: CompanionDiscovery.Tv, json: JSONObject) {
        when (CompanionProtocol.typeOf(json)) {
            CompanionProtocol.T_HELLO -> {
                deviceName = json.optString(CompanionProtocol.KEY_DEVICE_NAME).takeIf { it.isNotBlank() }
                json.optString(CompanionProtocol.KEY_NOW_PLAYING).takeIf { it.isNotBlank() }
                    ?.let { _nowPlaying.value = it }
            }
            CompanionProtocol.T_AUTH_OK -> {
                json.optString(CompanionProtocol.KEY_TOKEN).takeIf { it.isNotBlank() }
                    ?.let { storeToken(tv.deviceId, it) }
                _connection.value = Conn.Connected(deviceName ?: tv.name)
                Log.i(TAG, "companion authOk -> controlling ${deviceName ?: tv.name}")
                requestRemoteState()
            }
            CompanionProtocol.T_AUTH_FAIL -> {
                // Bad/absent token or wrong code: the TV is now showing a pairing code.
                _connection.value = Conn.NeedsPairing(deviceName ?: tv.name)
                Log.w(TAG, "companion authFail -> needs pairing (${deviceName ?: tv.name})")
            }
            else -> when (json.optString(CastControl.KEY_CMD)) {
                CastControl.CMD_STATE -> {
                    val s = CastControl.decodeState(json)
                    _remoteState.value = s
                    // Adopt the TV's live anchor: without this a fresh reconnect
                    // to an already-playing TV has no flip anchor, and a native
                    // TV-side flip leaves the optimistic value stale (GH #33).
                    s.channelId?.let { _currentChannelId.value = it }
                }
                CastControl.CMD_POSITION -> {
                    val p = CastControl.decodePosition(json)
                    _position.value = p
                    _isPlaying.value = p.isPlaying
                    p.channelId?.let { _currentChannelId.value = it }
                }
            }
        }
    }

    // ---- command surface (mirrors AerioCastSender; bound by CastRemoteOverlay) ----

    fun setRemoteChannel(channelId: String, title: String? = null) {
        title?.takeIf { it.isNotBlank() }?.let { _nowPlaying.value = it }
        _currentChannelId.value = channelId
        send(CastControl.command(CastControl.CMD_SET_CHANNEL) { put(CastControl.KEY_CHANNEL_ID, channelId) })
    }

    /** GH #33 companion VOD: play a movie/episode on the TV's VOD player. */
    fun playVod(videoId: String, isEpisode: Boolean, title: String? = null) {
        title?.takeIf { it.isNotBlank() }?.let { _nowPlaying.value = it }
        _currentChannelId.value = null // not a live channel; card tap won't re-tune
        send(
            CastControl.command(CastControl.CMD_PLAY_VOD) {
                put(CastControl.KEY_VIDEO_ID, videoId)
                put(CastControl.KEY_IS_EPISODE, isEpisode)
            },
        )
    }

    /** GH #33 companion DVR: play a recording / catch-up by resolved URL on the TV. */
    fun playRecording(url: String, title: String? = null) {
        title?.takeIf { it.isNotBlank() }?.let { _nowPlaying.value = it }
        _currentChannelId.value = null
        send(
            CastControl.command(CastControl.CMD_PLAY_RECORDING) {
                put(CastControl.KEY_URL, url)
                put(CastControl.KEY_TITLE, title ?: "")
            },
        )
    }

    fun togglePlayPause() = send(CastControl.command(CastControl.CMD_TOGGLE))
    fun play() = send(CastControl.command(CastControl.CMD_PLAY))
    fun pause() = send(CastControl.command(CastControl.CMD_PAUSE))
    fun goLiveRemote() = send(CastControl.command(CastControl.CMD_GO_LIVE))
    fun seekBy(deltaMs: Long) =
        send(CastControl.command(CastControl.CMD_SEEK_BY) { put(CastControl.KEY_DELTA_MS, deltaMs) })
    fun seekToWall(targetWallMs: Long) =
        send(CastControl.command(CastControl.CMD_SEEK_WALL) { put(CastControl.KEY_TARGET_WALL_MS, targetWallMs) })
    fun setRemoteAudioTrack(id: String) =
        send(CastControl.command(CastControl.CMD_SET_AUDIO) { put(CastControl.KEY_TRACK_ID, id) })
    fun setRemoteTextTrack(id: String?) =
        send(CastControl.command(CastControl.CMD_SET_TEXT) { put(CastControl.KEY_TRACK_ID, id ?: "") })
    fun setRemoteSpeed(speed: Float) =
        send(CastControl.command(CastControl.CMD_SET_SPEED) { put(CastControl.KEY_SPEED, speed.toDouble()) })
    fun setRemoteAspect(mode: CastControl.AspectMode) =
        send(CastControl.command(CastControl.CMD_SET_ASPECT) { put(CastControl.KEY_ASPECT, mode.key) })
    fun setRemoteAudioOnly(on: Boolean) =
        send(CastControl.command(CastControl.CMD_SET_AUDIO_ONLY) { put(CastControl.KEY_AUDIO_ONLY, on) })
    fun requestRemoteState() = send(CastControl.command(CastControl.CMD_GET_STATE))

    private fun send(text: String) {
        // GH #33 diagnostics: log the command name (not the full frame -- avoids
        // leaking ids into logs) so a user's captured log shows what the phone
        // sent. getState is the ~1Hz poll; skip it to keep the log readable.
        val cmd = runCatching { JSONObject(text).optString(CastControl.KEY_CMD) }.getOrNull()
        if (!cmd.isNullOrBlank() && cmd != CastControl.CMD_GET_STATE) {
            Log.i(TAG, "-> TV cmd: $cmd")
        }
        scope.launch { runCatching { session?.send(Frame.Text(text)) } }
    }

    // ---- per-TV pairing token store ----

    private fun storedToken(deviceId: String): String? = prefs.getString(tokenKey(deviceId), null)
    private fun storeToken(deviceId: String, token: String) {
        prefs.edit().putString(tokenKey(deviceId), token).apply()
    }
}
