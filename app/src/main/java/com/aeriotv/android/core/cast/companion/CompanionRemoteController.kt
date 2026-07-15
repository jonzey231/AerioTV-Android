package com.aeriotv.android.core.cast.companion

import android.content.Context
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

    private val _connection = MutableStateFlow<Conn>(Conn.Idle)
    val connection: StateFlow<Conn> = _connection.asStateFlow()

    private val _remoteState = MutableStateFlow(CastControl.RemoteState())
    val remoteState: StateFlow<CastControl.RemoteState> = _remoteState.asStateFlow()

    private val _position = MutableStateFlow(CastControl.PositionSnapshot())
    val position: StateFlow<CastControl.PositionSnapshot> = _position.asStateFlow()

    private val _isPlaying = MutableStateFlow(true)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // ---- connection lifecycle ----

    fun connect(tv: CompanionDiscovery.Tv) {
        disconnect()
        current = tv
        _connection.value = Conn.Connecting(tv.name)
        job = scope.launch {
            runCatching {
                val s = client.webSocketSession { url("ws://${tv.host}:${tv.port}/remote") }
                session = s
                // Authenticate immediately with a remembered token (blank on first pair);
                // the TV replies authOk, or authFail + shows a code -> NeedsPairing.
                s.send(Frame.Text(CompanionProtocol.auth(token = storedToken(tv.deviceId), code = null)))
                for (frame in s.incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    handleFrame(tv, json)
                }
            }.onFailure { _connection.value = Conn.Failed(it.message ?: "connect failed") }
            session = null
            if (_connection.value is Conn.Connected || _connection.value is Conn.Connecting) {
                _connection.value = Conn.Idle
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
        job?.cancel()
        job = null
        val s = session
        session = null
        scope.launch { runCatching { s?.close() } }
        _remoteState.value = CastControl.RemoteState()
        _position.value = CastControl.PositionSnapshot()
        _isPlaying.value = true
        if (_connection.value !is Conn.Failed) _connection.value = Conn.Idle
    }

    private fun handleFrame(tv: CompanionDiscovery.Tv, json: JSONObject) {
        when (CompanionProtocol.typeOf(json)) {
            CompanionProtocol.T_HELLO -> {
                deviceName = json.optString(CompanionProtocol.KEY_DEVICE_NAME).takeIf { it.isNotBlank() }
            }
            CompanionProtocol.T_AUTH_OK -> {
                json.optString(CompanionProtocol.KEY_TOKEN).takeIf { it.isNotBlank() }
                    ?.let { storeToken(tv.deviceId, it) }
                _connection.value = Conn.Connected(deviceName ?: tv.name)
                requestRemoteState()
            }
            CompanionProtocol.T_AUTH_FAIL -> {
                // Bad/absent token or wrong code: the TV is now showing a pairing code.
                _connection.value = Conn.NeedsPairing(deviceName ?: tv.name)
            }
            else -> when (json.optString(CastControl.KEY_CMD)) {
                CastControl.CMD_STATE -> _remoteState.value = CastControl.decodeState(json)
                CastControl.CMD_POSITION -> {
                    val p = CastControl.decodePosition(json)
                    _position.value = p
                    _isPlaying.value = p.isPlaying
                }
            }
        }
    }

    // ---- command surface (mirrors AerioCastSender; bound by CastRemoteOverlay) ----

    fun setRemoteChannel(channelId: String) =
        send(CastControl.command(CastControl.CMD_SET_CHANNEL) { put(CastControl.KEY_CHANNEL_ID, channelId) })

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
        scope.launch { runCatching { session?.send(Frame.Text(text)) } }
    }

    // ---- per-TV pairing token store ----

    private fun storedToken(deviceId: String): String? = prefs.getString(tokenKey(deviceId), null)
    private fun storeToken(deviceId: String, token: String) {
        prefs.edit().putString(tokenKey(deviceId), token).apply()
    }
}
