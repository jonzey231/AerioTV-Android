package com.aeriotv.android.core.cast.multiview

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.aeriotv.android.feature.multiview.MultiviewTile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phone-side Multiview composite for the Cast WEB receiver (Logan
 * 2026-10-06): 2 to 4 live tiles, each decoded by its own ExoPlayer into an
 * off-screen SurfaceTexture, drawn with OpenGL ES into one 1280x720 frame at
 * 30 fps ([MultiviewCompositeLayout]: the local Multiview's grid shapes,
 * letterboxed tiles, thin borders, white highlight on the audio-focused
 * tile), encoded with the platform H.264 encoder (MediaCodec, input
 * Surface), and muxed with the focused tile's audio (AAC-LC, MediaCodec)
 * into an MPEG-TS stream for the cast proxy ([MultiviewTsMuxer] ->
 * [output]).
 *
 * Key frames: the encoder's own interval is 2 s, and an IDR is also forced
 * at every 3 s of media, the proxy segmenter's target, so each segment cut
 * lands on a key frame at ~3 s exactly as the on-phone transcoder does.
 *
 * Threads: GL + render tick on "MV-CAST-gl", encoder drain + stats +
 * behind-watchdog on "MV-CAST-enc", AAC on "MV-CAST-aac", players on main.
 * [onFatal] is posted to the main thread with the stop reason.
 */
@OptIn(UnstableApi::class)
class MultiviewCompositor(
    private val context: Context,
    private val tiles: List<MultiviewTile>,
    private val headers: Map<String, String>,
    initialFocus: Int,
    private val output: (ByteArray) -> Unit,
    private val onFatal: (reason: String) -> Unit,
) : CompositeAudioTap {

    companion object {
        private const val TAG = "AerioCast"
        private const val W = MultiviewCompositeLayout.WIDTH
        private const val H = MultiviewCompositeLayout.HEIGHT
        private const val FPS = MultiviewCompositeLayout.FPS
        private const val FRAME_NANOS = 1_000_000_000L / FPS
        private const val BITRATE = 4_000_000
        private const val ENCODER_KEY_INTERVAL_S = 2
        private const val FORCED_KEY_NANOS = 3_000_000_000L
        /** Encoder (or render) behind by more than this stops the cast. */
        const val MAX_BEHIND_NANOS = 3_000_000_000L
        private const val STATS_NANOS = 10_000_000_000L
        private const val MAX_TILE_RETRIES = 10
        private const val TILE_RETRY_MS = 3_000L
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VS = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTex;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uTex * aTex).xy; }
        """
        private const val FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """
    }

    private class Tile(val index: Int, val source: MultiviewTile) {
        var texId = 0
        var surfaceTexture: SurfaceTexture? = null
        var surface: Surface? = null
        @Volatile var frameAvailable = false
        var hasFrame = false
        val texMatrix = FloatArray(16)
        @Volatile var videoWidth = 0
        @Volatile var videoHeight = 0
        @Volatile var pixelRatio = 1f
        var player: ExoPlayer? = null
        var retries = 0
    }

    private val count = tiles.size.coerceAtMost(MultiviewCompositeLayout.MAX_TILES)
    private val state = tiles.take(count).mapIndexed { i, t -> Tile(i, t) }
    private val rects = MultiviewCompositeLayout.tileRects(count)

    @Volatile var focused: Int = initialFocus.coerceIn(0, count - 1)
        private set

    private val clock = CompositeClock(System.nanoTime())
    private val muxLock = Any()
    private val muxer = MultiviewTsMuxer(output)
    private val normalizer = CompositePcmNormalizer()
    private val audio = CompositeAudioEncoder(
        clock,
        onFrame = { adts, ticks -> synchronized(muxLock) { muxer.writeAudio(adts, ticks) } },
        log = { Log.i(TAG, it) },
    )

    private val main = Handler(Looper.getMainLooper())
    private val glThread = HandlerThread("MV-CAST-gl", Process.THREAD_PRIORITY_DISPLAY)
    private lateinit var gl: Handler
    @Volatile private var running = false
    @Volatile private var fatalSent = false

    // EGL / GL (GL thread only)
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewSurface: Surface? = null
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTex = 0
    private val quadPos: FloatBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadTex: FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    // Encoder
    private var encoder: MediaCodec? = null
    private var encoderInput: Surface? = null
    private var drainThread: Thread? = null
    @Volatile private var parameterSets: ByteArray? = null
    private val submitted = ConcurrentHashMap<Long, Long>() // ptsUs -> submit nanos
    private var nextTickNanos = 0L
    private var nextKeyNanos = 0L

    // Stats (written on GL / drain threads, read by the drain thread)
    @Volatile private var lastTickNanos = 0L
    @Volatile private var lastOutputNanos = 0L
    @Volatile private var droppedTicks = 0
    @Volatile private var submittedFrames = 0L
    private var encodedSinceStats = 0
    private var encLatencySumNanos = 0L
    private var encLatencyCount = 0
    private var droppedAtLastStats = 0

    /** Builds GL, the encoders and the tile players. False on failure (the
     *  reason is logged; nothing is left running). */
    fun start(): Boolean {
        if (count < MultiviewCompositeLayout.MIN_TILES) return false
        glThread.start()
        gl = Handler(glThread.looper)
        val latch = CountDownLatch(1)
        var ok = false
        gl.post {
            ok = runCatching { setUpGl() }.onFailure { Log.w(TAG, "[MV-CAST] composite GL setup failed: $it") }.getOrDefault(false)
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS) || !ok) {
            releaseGl()
            return false
        }
        if (!audio.start()) {
            releaseGl()
            return false
        }
        running = true
        lastOutputNanos = System.nanoTime()
        drainThread = Thread({ drainLoop() }, "MV-CAST-enc").also {
            it.priority = Thread.MAX_PRIORITY - 1
            it.start()
        }
        nextTickNanos = System.nanoTime()
        nextKeyNanos = nextTickNanos
        gl.post(tick)
        main.post { state.forEach { startPlayer(it) } }
        Log.i(TAG, "[MV-CAST] composite start tiles=$count ${W}x$H@$FPS")
        return true
    }

    /** Audio focus and the highlight move to [index]; no restart. */
    fun setFocus(index: Int) {
        if (index !in 0 until count || index == focused) return
        Log.i(TAG, "[MV-CAST] composite focus ${state[focused].source.displayName} -> ${state[index].source.displayName}")
        focused = index
    }

    /** Mirror the composite onto a phone preview surface (the remote
     *  sheet's grid). One preview at a time. */
    fun attachPreview(surface: Surface) {
        if (!running) return
        gl.post {
            releasePreviewSurface()
            previewSurface = surface
            previewEglSurface = runCatching {
                EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
            }.getOrDefault(EGL14.EGL_NO_SURFACE)
        }
    }

    fun detachPreview(surface: Surface) {
        if (!running) return
        val latch = CountDownLatch(1)
        gl.post {
            if (previewSurface === surface) releasePreviewSurface()
            latch.countDown()
        }
        // The TextureView frees the surface right after this returns.
        latch.await(500, TimeUnit.MILLISECONDS)
    }

    fun release(reason: String) {
        if (!running && drainThread == null) return
        running = false
        Log.i(TAG, "[MV-CAST] composite stop reason=$reason")
        val playersDone = CountDownLatch(1)
        val releasePlayers = Runnable {
            state.forEach { t ->
                runCatching { t.player?.clearVideoSurface() }
                runCatching { t.player?.release() }
                t.player = null
            }
            playersDone.countDown()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) releasePlayers.run() else main.post(releasePlayers)
        playersDone.await(2, TimeUnit.SECONDS)
        audio.release()
        drainThread?.interrupt()
        runCatching { drainThread?.join(1_000) }
        drainThread = null
        releaseGl()
    }

    // ---- audio tap (tile playback threads) ----

    override fun wants(tile: Int): Boolean = running && tile == focused

    private var lastAudioTile = -1

    override fun onPcm(tile: Int, bytes: ByteArray, sampleRate: Int, channels: Int, pcmEncoding: Int, playoutNanos: Long) {
        val samples = synchronized(normalizer) {
            if (tile != lastAudioTile) {
                normalizer.reset()
                lastAudioTile = tile
            }
            normalizer.convert(bytes, sampleRate, channels, pcmEncoding)
        }
        audio.submit(samples, playoutNanos)
    }

    // ---- players (main thread) ----

    private fun startPlayer(t: Tile) {
        if (!running) return
        val surface = t.surface ?: return
        val ua = "AerioTV/${com.aeriotv.android.BuildConfig.VERSION_NAME} (Android; ${android.os.Build.MODEL})"
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent(headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: ua)
        if (headers.isNotEmpty()) http.setDefaultRequestProperties(headers)
        val renderers = com.aeriotv.android.core.playback.aerioRenderersFactory(
            context,
            audioPassthrough = false,
            tileAudioGate = com.aeriotv.android.core.playback.TileAudioGate(initiallyMuted = true),
            tileAudioSinkWrapper = { sink -> CompositeTapAudioSink(sink, t.index, this) },
        )
        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(5_000, 15_000, 1_000, 3_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .setHandleAudioBecomingNoisy(false)
            .build()
        player.setVideoSurface(surface)
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                t.videoWidth = videoSize.width
                t.videoHeight = videoSize.height
                t.pixelRatio = videoSize.pixelWidthHeightRatio
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "[MV-CAST] composite tile ${t.source.displayName} error ${error.errorCodeName}")
                if (!running || t.retries >= MAX_TILE_RETRIES) return
                t.retries++
                main.postDelayed({ if (running && t.player === player) player.prepare() }, TILE_RETRY_MS)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) t.retries = 0
            }
        })
        player.setMediaSource(
            com.aeriotv.android.feature.multiview.buildTileMediaSource(t.source.resolvedUrl, http),
        )
        player.playWhenReady = true
        player.prepare()
        t.player = player
        Log.i(TAG, "[MV-CAST] composite tile ${t.index} loading ${t.source.displayName}")
    }

    // ---- GL thread ----

    private fun setUpGl(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig" }
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }

        val enc = createEncoder() ?: return false
        encoder = enc
        encoderInput = enc.createInputSurface()
        encoderEglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, encoderInput, intArrayOf(EGL14.EGL_NONE), 0,
        )
        check(encoderEglSurface != EGL14.EGL_NO_SURFACE) { "encoder window surface" }
        check(EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)) { "eglMakeCurrent" }
        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTex = GLES20.glGetUniformLocation(program, "uTex")

        for (t in state) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            t.texId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t.texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val st = SurfaceTexture(t.texId)
            st.setOnFrameAvailableListener({ t.frameAvailable = true }, gl)
            t.surfaceTexture = st
            t.surface = Surface(st)
        }
        enc.start()
        return true
    }

    private fun createEncoder(): MediaCodec? {
        fun format(profile: Int?) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, ENCODER_KEY_INTERVAL_S)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (android.os.Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (profile != null) {
                setInteger(MediaFormat.KEY_PROFILE, profile)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
        }
        for (profile in listOf(
            MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            null,
        )) {
            val codec = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: return null
            val ok = runCatching { codec.configure(format(profile), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }.isSuccess
            if (ok) {
                Log.i(TAG, "[MV-CAST] composite encoder ${codec.name} profile=${when (profile) {
                    MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "main"
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "baseline"
                    else -> "default"
                }} ${BITRATE / 1000}kbps gop=${ENCODER_KEY_INTERVAL_S}s")
                return codec
            }
            runCatching { codec.release() }
        }
        Log.w(TAG, "[MV-CAST] composite: no H.264 encoder accepted ${W}x$H@$FPS")
        return null
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            lastTickNanos = now
            runCatching { renderFrame(now) }.onFailure {
                Log.w(TAG, "[MV-CAST] composite render failed: $it")
                fatal("render failed")
                return
            }
            nextTickNanos += FRAME_NANOS
            val after = System.nanoTime()
            if (nextTickNanos < after) {
                val missed = ((after - nextTickNanos) / FRAME_NANOS).toInt() + 1
                droppedTicks += missed
                nextTickNanos += missed * FRAME_NANOS
            }
            gl.postDelayed(this, ((nextTickNanos - after) / 1_000_000L).coerceAtLeast(0L))
        }
    }

    private fun renderFrame(now: Long) {
        for (t in state) {
            if (t.frameAvailable) {
                t.frameAvailable = false
                t.surfaceTexture?.let { st ->
                    st.updateTexImage()
                    st.getTransformMatrix(t.texMatrix)
                    t.hasFrame = true
                }
            }
        }
        val focus = focused
        // Preview first so the encoder swap (which can block on a full
        // encoder queue) never delays the phone's own grid.
        if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
            if (EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext)) {
                EGL14.eglSwapInterval(eglDisplay, 0)
                val w = IntArray(1)
                val h = IntArray(1)
                EGL14.eglQuerySurface(eglDisplay, previewEglSurface, EGL14.EGL_WIDTH, w, 0)
                EGL14.eglQuerySurface(eglDisplay, previewEglSurface, EGL14.EGL_HEIGHT, h, 0)
                draw(w[0], h[0], focus)
                EGL14.eglSwapBuffers(eglDisplay, previewEglSurface)
            }
        }
        EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)
        draw(W, H, focus)
        val elapsed = now - clock.t0Nanos
        if (now >= nextKeyNanos) {
            runCatching {
                encoder?.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            }
            nextKeyNanos += FORCED_KEY_NANOS
            if (nextKeyNanos < now) nextKeyNanos = now + FORCED_KEY_NANOS
        }
        EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, elapsed)
        submitted[elapsed / 1000] = now
        submittedFrames++
        EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)
    }

    /** One composite frame into the current surface of [w] x [h]. */
    private fun draw(w: Int, h: Int, focus: Int) {
        if (w <= 0 || h <= 0) return
        val sx = w / W.toFloat()
        val sy = h / H.toFloat()
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        for (t in state) {
            val cell = rects.getOrNull(t.index) ?: continue
            val isFocus = t.index == focus
            if (isFocus) GLES20.glClearColor(1f, 1f, 1f, 1f) else GLES20.glClearColor(0.25f, 0.25f, 0.25f, 1f)
            scissor(cell, sx, sy, h)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            scissor(MultiviewCompositeLayout.pictureArea(cell, isFocus), sx, sy, h)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quadPos)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex)
        for (t in state) {
            if (!t.hasFrame) continue
            val cell = rects.getOrNull(t.index) ?: continue
            val area = MultiviewCompositeLayout.pictureArea(cell, t.index == focus)
            val pic = MultiviewCompositeLayout.letterbox(area, t.videoWidth, t.videoHeight, t.pixelRatio)
            val vx = (pic.left * sx).toInt()
            val vw = (pic.width * sx).toInt()
            val vh = (pic.height * sy).toInt()
            val vy = h - (pic.top * sy).toInt() - vh
            GLES20.glViewport(vx, vy, vw, vh)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t.texId)
            GLES20.glUniformMatrix4fv(uTex, 1, false, t.texMatrix, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    private fun scissor(r: CompositeRect, sx: Float, sy: Float, h: Int) {
        val x = (r.left * sx).toInt()
        val w = (r.right * sx).toInt() - x
        val top = (r.top * sy).toInt()
        val rh = (r.bottom * sy).toInt() - top
        GLES20.glScissor(x, h - top - rh, w, rh)
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VS))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FS))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun releasePreviewSurface() {
        if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)
            EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
        }
        previewEglSurface = EGL14.EGL_NO_SURFACE
        previewSurface = null
    }

    private fun releaseGl() {
        if (!glThread.isAlive) return
        val latch = CountDownLatch(1)
        gl.removeCallbacksAndMessages(null)
        gl.post {
            runCatching { releasePreviewSurface() }
            for (t in state) {
                runCatching { t.surface?.release() }
                runCatching { t.surfaceTexture?.release() }
                t.surface = null
                t.surfaceTexture = null
            }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            encoder = null
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (encoderEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
            encoderEglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            eglDisplay = EGL14.EGL_NO_DISPLAY
            runCatching { encoderInput?.release() }
            encoderInput = null
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        glThread.quitSafely()
    }

    // ---- encoder drain, stats, watchdog ("MV-CAST-enc") ----

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        var statsAt = System.nanoTime()
        while (running) {
            val enc = encoder ?: break
            val idx = try {
                enc.dequeueOutputBuffer(info, 10_000)
            } catch (t: Throwable) {
                if (running) {
                    Log.w(TAG, "[MV-CAST] composite encoder failed: $t")
                    fatal("encoder failed")
                }
                break
            }
            val now = System.nanoTime()
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (parameterSets == null) {
                    val f = enc.outputFormat
                    val sps = f.getByteBuffer("csd-0")?.let(::bytesOf)
                    val pps = f.getByteBuffer("csd-1")?.let(::bytesOf)
                    if (sps != null) parameterSets = sps + (pps ?: ByteArray(0))
                }
            } else if (idx >= 0) {
                val buf = enc.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        parameterSets = bytes
                    } else {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        submitted.remove(info.presentationTimeUs)?.let { at ->
                            encLatencySumNanos += now - at
                            encLatencyCount++
                        }
                        lastOutputNanos = now
                        encodedSinceStats++
                        val ticks = clock.ticksForUs(info.presentationTimeUs)
                        synchronized(muxLock) { muxer.writeVideo(bytes, ticks, key, parameterSets) }
                    }
                }
                runCatching { enc.releaseOutputBuffer(idx, false) }
            }
            // Behind: frames submitted but nothing out for MAX_BEHIND, or the
            // oldest frame still inside the encoder is older than that, or
            // the render tick itself has stalled that long.
            val oldest = submitted.values.minOrNull()
            val behind = (oldest != null && now - oldest > MAX_BEHIND_NANOS && now - lastOutputNanos > MAX_BEHIND_NANOS) ||
                (lastTickNanos > 0 && now - lastTickNanos > MAX_BEHIND_NANOS)
            if (behind) {
                Log.w(TAG, "[MV-CAST] composite encoder behind by more than ${MAX_BEHIND_NANOS / 1_000_000_000}s")
                fatal("behind")
                break
            }
            // Frames the encoder silently dropped never come out; forget
            // them so they do not read as "behind" forever.
            if (oldest != null && now - oldest > MAX_BEHIND_NANOS) {
                submitted.entries.removeAll { now - it.value > MAX_BEHIND_NANOS }
            }
            if (now - statsAt >= STATS_NANOS) {
                val seconds = (now - statsAt) / 1e9
                val fps = encodedSinceStats / seconds
                val encMs = if (encLatencyCount > 0) encLatencySumNanos / encLatencyCount / 1_000_000 else 0
                val dropped = droppedTicks
                Log.i(
                    TAG,
                    "[MV-CAST] composite fps=${"%.1f".format(java.util.Locale.US, fps)} enc=$encMs " +
                        "dropped=${dropped - droppedAtLastStats}",
                )
                droppedAtLastStats = dropped
                encodedSinceStats = 0
                encLatencySumNanos = 0
                encLatencyCount = 0
                statsAt = now
            }
        }
    }

    private fun fatal(reason: String) {
        if (fatalSent) return
        fatalSent = true
        main.post { onFatal(reason) }
    }

    private fun bytesOf(b: ByteBuffer): ByteArray {
        val d = b.duplicate()
        d.position(0)
        return ByteArray(d.remaining()).also { d.get(it) }
    }

    private fun floatBuffer(vararg v: Float): FloatBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(v)
            position(0)
        }
}
