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
 * letterboxed tiles, and the user's Multiview look from [style]: padding,
 * corners, audio focus indicator and channel logos), encoded with the platform H.264 encoder (MediaCodec, input
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
        /** Encoder GOP, matched to the composite's 1 s segment target
         *  (CastSegmentProfile.COMPOSITE, iOS 3515e92 round 6): with 3 s cuts
         *  a missed key frame made 4 s segments, TARGETDURATION 5 and a 15 s
         *  HOLD-BACK, and the receiver sat 13.6 s behind the composite. */
        internal const val ENCODER_KEY_INTERVAL_S = 1
        /** Forced segment key frames, spaced at least this far (on the
         *  frame PTS) from the previous one, never on a fixed grid, so every
         *  forced key frame is at or after the remuxer's 1 s cut target. */
        internal const val FORCED_KEY_NANOS = 1_000_000_000L
        /** Encoder (or render) behind by more than this stops the cast. */
        const val MAX_BEHIND_NANOS = 3_000_000_000L
        private const val STATS_NANOS = 10_000_000_000L
        private const val MAX_TILE_RETRIES = 10
        /** Preview notice on a tile that is retrying, and on one that gave up. */
        const val NOTICE_RECONNECTING = "Reconnecting"
        const val NOTICE_UNAVAILABLE = "Unavailable"
        /** Background proof-of-life log cadence ([MV-BG]). */
        private const val BG_STATS_NANOS = 60_000_000_000L
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VS = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTex;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uTex * aTex).xy; }
        """
        /** Rounded-rect coverage of the current fragment: uClip is
         *  (left, bottom, right, top) in window pixels, uRadius the corner. */
        private const val SDF = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            float cover(vec4 r, float rad) {
                vec2 c = (r.xy + r.zw) * 0.5;
                vec2 hs = (r.zw - r.xy) * 0.5;
                rad = min(rad, min(hs.x, hs.y));
                vec2 q = abs(gl_FragCoord.xy - c) - hs + vec2(rad);
                float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;
                return clamp(0.5 - d, 0.0, 1.0);
            }
        """
        /** Tile video, clipped to the tile's (rounded) shape. */
        private const val FS_VIDEO = """
            #extension GL_OES_EGL_image_external : require
        """ + SDF + """
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            uniform vec4 uClip;
            uniform float uRadius;
            void main() { gl_FragColor = texture2D(sTex, vTex) * cover(uClip, uRadius); }
        """
        /** Solid (premultiplied) color in a rounded rect; uStroke > 0 draws
         *  only a ring of that width inside the rect (a border). */
        private const val FS_SOLID = SDF + """
            uniform vec4 uClip;
            uniform float uRadius;
            uniform float uStroke;
            uniform vec4 uColor;
            void main() {
                float a = cover(uClip, uRadius);
                if (uStroke > 0.0) {
                    vec4 inner = uClip + vec4(uStroke, uStroke, -uStroke, -uStroke);
                    a = a * (1.0 - cover(inner, max(uRadius - uStroke, 0.0)));
                }
                gl_FragColor = uColor * a;
            }
        """
        /** A bitmap (premultiplied) times a premultiplied tint. */
        private const val FS_BITMAP = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sTex;
            uniform vec4 uColor;
            void main() { gl_FragColor = texture2D(sTex, vTex) * uColor; }
        """
        private val IDENTITY = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        /** Bitmaps are top-down; GL samples bottom-up. */
        private val FLIP_Y = FloatArray(16).also {
            android.opengl.Matrix.setIdentityM(it, 0)
            it[5] = -1f
            it[13] = 1f
        }
        /** Material "volume_up" (24 dp viewport), the local speaker icon. */
        private const val VOLUME_UP_PATH =
            "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z" +
                "M14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"
    }

    /** One of the [MultiviewCompositeLayout.MAX_TILES] fixed slots. [index]
     *  is the slot (and the audio-tap id); [source] is the channel it shows,
     *  null while the slot is free. Slots are reused when a tile is added in
     *  place (no encoder, pipe or receiver restart). */
    private class Tile(val index: Int, @Volatile var source: MultiviewTile?) {
        var texId = 0
        var surfaceTexture: SurfaceTexture? = null
        var surface: Surface? = null
        @Volatile var frameAvailable = false
        @Volatile var hasFrame = false
        val texMatrix = FloatArray(16)
        @Volatile var videoWidth = 0
        @Volatile var videoHeight = 0
        @Volatile var pixelRatio = 1f
        var player: ExoPlayer? = null
        var retries = 0
        /** Reached READY once; a later BUFFERING is a stall. */
        var wasReady = false
        var stalled = false
        /** Health counters (Apple a4bd790 parity): ingest bytes, picture
         *  updates and stalls since the tile joined its slot. */
        @Volatile var ingestBytes = 0L
        @Volatile var framesRendered = 0L
        @Volatile var stalls = 0
        /** Channel logo texture (GL thread) and its opaque aspect. */
        var logoTex = 0
        @Volatile var logoAspect = 0f
        @Volatile var logoBitmap: android.graphics.Bitmap? = null
        var logoRequested = false
        /** Set when the slot changes channel: the GL thread drops the old
         *  logo texture before the new one loads. */
        @Volatile var dropLogo = false
    }

    private val initialCount = tiles.size.coerceAtMost(MultiviewCompositeLayout.MAX_TILES)
    private val state = List(MultiviewCompositeLayout.MAX_TILES) { i -> Tile(i, tiles.getOrNull(i)?.takeIf { i < initialCount }) }

    /**
     * Display order: position -> slot index, for the slots that show right
     * now. A tile added or removed in place (Apple 6136daf: no composite
     * restart, no receiver reload) only edits this; a swap reorders it. One
     * entry draws full frame like a single-channel cast.
     */
    @Volatile private var shown: IntArray = IntArray(initialCount) { it }

    /** Slot indices showing, in position order. */
    val shownSlots: List<Int> get() = shown.toList()

    /**
     * Display position under a point in frame pixels, from the same geometry
     * [draw] uses for the next frame (shown order, [layoutMode], padding,
     * full frame for one tile), or -1 for a gap. The preview's taps resolve
     * through this so they always match the picture under the finger.
     */
    fun hitTestFrame(fx: Float, fy: Float): Int {
        val n = shown.size
        val rects = if (n == 1) {
            MultiviewCompositeLayout.tileRects(1)
        } else {
            MultiviewCompositeLayout.tileRects(n, style.padding, mode = layoutMode)
        }
        return MultiviewCompositeLayout.hitTest(fx, fy, rects)
    }

    /** Slot -> preview notice ([NOTICE_RECONNECTING], [NOTICE_UNAVAILABLE]);
     *  main thread. Each change is handed to [onTileNotices]. */
    private val notices = HashMap<Int, String>()
    /** Main thread: the slot notices changed (the controller maps them to
     *  display positions for the preview). */
    var onTileNotices: ((Map<Int, String>) -> Unit)? = null

    private fun setNotice(slot: Int, text: String?) {
        val changed = if (text == null) notices.remove(slot) != null else notices.put(slot, text) != text
        if (changed) onTileNotices?.invoke(notices.toMap())
    }

    @Volatile var focused: Int = initialFocus.coerceIn(0, (initialCount - 1).coerceAtLeast(0))
        private set

    /** The user's Multiview look; read on every frame, so a Settings change
     *  shows on the next one. Logos load when first turned on. */
    @Volatile var style: CompositeStyle = CompositeStyle()
        set(value) {
            field = value
            if (value.showLogos && running) main.post { loadLogos() }
        }
    /** Grid layout of the composite (the cast sheet's Layout row); invalid
     *  modes for the tile count draw Default. Read on every frame. A layout
     *  change does NOT re-show the audio-focus indicator (Apple f33f645:
     *  picking Default flashed it on the audio tile); a focus change and a
     *  tile rearrange do. */
    @Volatile var layoutMode: com.aeriotv.android.feature.multiview.MultiviewLayoutMode =
        com.aeriotv.android.feature.multiview.MultiviewLayoutMode.Auto

    /** When the focus last changed (the fading indicator's start). */
    @Volatile private var focusChangedAtNanos = System.nanoTime()
    /** Tap time of a focus change not yet on a composed frame; 0 when none. */
    @Volatile private var pendingFocusTapNanos = 0L
    @Volatile private var pendingFocusIndex = -1
    /** Tile whose next PCM re-anchors the composite audio; -1 when none. */
    @Volatile private var reanchorTile = -1

    /** App in the background (set by the controller). The composite keeps
     *  rendering, encoding and muxing exactly as in the foreground (the cast
     *  proxy's foreground service keeps the process alive); only the phone
     *  preview is skipped, since its window surface has no consumer while
     *  the UI is stopped and a swap into it could block the GL thread. */
    @Volatile var backgrounded = false
        set(value) {
            if (field == value) return
            field = value
            bgSinceNanos = System.nanoTime()
            bgFrames = 0
            bgAudioChunks = 0
            Log.i(TAG, "[MV-BG] composite ${if (value) "backgrounded, still rendering" else "foregrounded"}")
        }
    @Volatile private var bgSinceNanos = 0L
    @Volatile private var bgFrames = 0L
    @Volatile private var bgAudioChunks = 0L


    /** ARGB of the focused tile's border: the app's accent color. */
    @Volatile var focusArgb: Int = 0xFFFFFFFF.toInt()

    private val clock = CompositeClock(System.nanoTime())
    private val muxLock = Any()
    private val muxer = MultiviewTsMuxer(output)
    private val normalizer = CompositePcmNormalizer()
    private val audio = CompositeAudioEncoder(
        clock,
        onFrame = { adts, ticks ->
            synchronized(muxLock) {
                muxer.writeAudio(adts, ticks)
                noteFirstAudio(ticks)
            }
            if (backgrounded) bgAudioChunks++
            audioChunksTotal++
        },
        log = { Log.i(TAG, it) },
    )

    /** First video key frame and the first audio frame at or after it on
     *  the mux timeline (muxLock): a reader joins at that key frame, so this
     *  is the first segment's A/V alignment (Apple e1dcbd2 logged
     *  `seg=0 vpts=0.000 apts=0.299` before muxing audio at video time). */
    private var firstKeyTicks = -1L
    private var firstAvLogged = false

    private fun noteFirstAudio(ticks: Long) {
        if (firstAvLogged || firstKeyTicks < 0 || ticks < firstKeyTicks) return
        firstAvLogged = true
        val base = CompositeClock.BASE_TICKS
        Log.i(
            TAG,
            String.format(
                java.util.Locale.US,
                "[MV-CAST] composite first segment vpts=%.3f apts=%.3f offset=%d ms",
                (firstKeyTicks - base) / 90_000.0, (ticks - base) / 90_000.0, (ticks - firstKeyTicks) / 90,
            ),
        )
    }

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
    /** Preview surface size last logged (GL thread). */
    private var previewLoggedW = 0
    private var previewLoggedH = 0
    private class Prog(val id: Int) {
        val aPos = GLES20.glGetAttribLocation(id, "aPos")
        val aTex = GLES20.glGetAttribLocation(id, "aTex")
        val uTex = GLES20.glGetUniformLocation(id, "uTex")
        val uClip = GLES20.glGetUniformLocation(id, "uClip")
        val uRadius = GLES20.glGetUniformLocation(id, "uRadius")
        val uStroke = GLES20.glGetUniformLocation(id, "uStroke")
        val uColor = GLES20.glGetUniformLocation(id, "uColor")
    }
    private var videoProg: Prog? = null
    private var solidProg: Prog? = null
    private var bitmapProg: Prog? = null
    private var iconTex = 0
    private val quadPos: FloatBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadTex: FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    // Encoder
    private var encoder: MediaCodec? = null
    private var encoderInput: Surface? = null
    private var drainThread: Thread? = null
    @Volatile private var parameterSets: ByteArray? = null
    private val submitted = ConcurrentHashMap<Long, Long>() // ptsUs -> submit nanos
    private var nextTickNanos = 0L
    /** Frame PTS (ns) of the last forced segment key frame; -1 forces the
     *  first frame. */
    private var lastForcedKeyElapsed = -1L

    // Stats (written on GL / drain threads, read by the drain thread)
    @Volatile private var lastTickNanos = 0L
    @Volatile private var lastOutputNanos = 0L
    @Volatile private var droppedTicks = 0
    @Volatile private var submittedFrames = 0L
    /** Health line totals (read on the main thread every 10 s). */
    @Volatile private var encodedTotal = 0L
    @Volatile private var audioChunksTotal = 0L
    private var encodedSinceStats = 0
    private var encLatencySumNanos = 0L
    private var encLatencyCount = 0
    private var droppedAtLastStats = 0

    /** Builds GL, the encoders and the tile players. False on failure (the
     *  reason is logged; nothing is left running). */
    fun start(): Boolean {
        if (initialCount < MultiviewCompositeLayout.MIN_TILES) return false
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
        lastForcedKeyElapsed = -1L
        gl.post(tick)
        main.post { state.forEach { if (it.source != null) startPlayer(it) } }
        main.postDelayed(health, STATS_NANOS / 1_000_000)
        Log.i(TAG, "[MV-CAST] composite start tiles=$initialCount ${W}x$H@$FPS")
        return true
    }

    /** Audio focus and the highlight move to [index]; no restart. */
    fun setFocus(index: Int, tapNanos: Long = System.nanoTime()) {
        if (index !in shown || index == focused) return
        Log.i(TAG, "[MV-CAST] composite focus ${state[focused].source?.displayName} -> ${state[index].source?.displayName}")
        pendingFocusIndex = index
        pendingFocusTapNanos = tapNanos
        focusChangedAtNanos = System.nanoTime()
        focused = index
    }

    /**
     * Add [source] in place (Apple 6136daf, round 9 parity): a free slot takes
     * it and it joins the end of the display order. The encoder, the pipe,
     * the proxy and the receiver's load keep running, so the TV never
     * reloads; the next frame re-lays out the grid. Main thread. Returns the
     * slot, or -1 when every slot is taken or the composite is not running.
     */
    fun addTile(source: MultiviewTile): Int {
        if (!running) return -1
        val t = state.firstOrNull { it.source == null && it.index !in shown } ?: return -1
        t.player?.let { p -> t.player = null; runCatching { p.release() } }
        t.source = source
        t.frameAvailable = false
        t.hasFrame = false
        t.videoWidth = 0
        t.videoHeight = 0
        t.pixelRatio = 1f
        t.retries = 0
        t.wasReady = false
        t.stalled = false
        setNotice(t.index, null)
        // A new tile starts its health counters from zero: the slot's old
        // totals (and the health window baselines) belonged to the channel
        // that left it (device pass cc2c37c7: stalls survived remove + add).
        t.ingestBytes = 0L
        t.framesRendered = 0L
        t.stalls = 0
        healthBytes[t.index] = 0L
        healthFrames[t.index] = 0L
        healthStalls[t.index] = 0
        t.logoRequested = false
        t.logoAspect = 0f
        t.dropLogo = true
        shown = shown + t.index
        // A rearrange shows the audio-focus indicator again (Apple parity).
        focusChangedAtNanos = System.nanoTime()
        Log.i(TAG, "[MV-CAST] composite tiles changed: ${shown.size} (added ${source.displayName} in slot ${t.index}, same stream, no receiver reload)")
        startPlayer(t)
        return t.index
    }

    /**
     * Remove the tile in [slot] in place. With one tile left it draws full
     * frame (round 9: no padding, corner clip, logo or focus indicator) and
     * the receiver keeps the same stream. Audio moves to the first remaining
     * tile when the focused one leaves. Main thread.
     */
    fun removeTile(slot: Int) {
        if (slot !in shown || shown.size <= 1) return
        val t = state[slot]
        val name = t.source?.displayName
        shown = shown.filter { it != slot }.toIntArray()
        if (focused == slot) setFocus(shown[0])
        t.player?.let { p ->
            t.player = null
            runCatching { p.clearVideoSurface() }
            runCatching { p.release() }
        }
        t.source = null
        t.hasFrame = false
        t.dropLogo = true
        setNotice(slot, null)
        focusChangedAtNanos = System.nanoTime()
        Log.i(
            TAG,
            "[MV-CAST] composite tiles changed: ${shown.size} (removed $name" +
                (if (shown.size == 1) ", ${state[shown[0]].source?.displayName} full frame" else "") +
                ", same stream, no receiver reload)",
        )
    }

    /** New display order ([order] position -> slot), the same slots as now. */
    fun setOrder(order: List<Int>) {
        if (order.size != shown.size || order.sorted() != shown.sorted()) return
        shown = order.toIntArray()
        // A rearrange shows the audio-focus indicator again (Apple parity);
        // a layout change ([layoutMode]) does not.
        focusChangedAtNanos = System.nanoTime()
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
        main.removeCallbacks(health)
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

    // `[MV-CAST] audio` window stats (normalizer lock): the tile whose PCM
    // reached the tap, and the RMS of that PCM after normalization.
    private var pcmTileWindow = -1
    private var pcmSumSq = 0.0
    private var pcmSamples = 0L
    private var pcmFormatWindow = ""

    override fun onPcm(tile: Int, bytes: ByteArray, sampleRate: Int, channels: Int, pcmEncoding: Int, playoutNanos: Long) {
        val samples = synchronized(normalizer) {
            if (tile != lastAudioTile) {
                normalizer.reset()
                lastAudioTile = tile
            }
            normalizer.convert(bytes, sampleRate, channels, pcmEncoding).also { out ->
                pcmTileWindow = tile
                pcmFormatWindow = "${sampleRate}Hz/${channels}ch/enc$pcmEncoding"
                for (v in out) { val d = v.toDouble(); pcmSumSq += d * d }
                pcmSamples += out.size
            }
        }
        val reanchor = reanchorTile == tile
        if (reanchor) reanchorTile = -1
        audio.submit(samples, playoutNanos, if (reanchor) tile else -1)
    }

    // ---- players (main thread) ----

    private fun startPlayer(t: Tile) {
        if (!running) return
        val source = t.source ?: return
        val surface = t.surface ?: return
        val ua = "AerioTV/${com.aeriotv.android.BuildConfig.VERSION_NAME} (Android; ${android.os.Build.MODEL})"
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent(headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: ua)
        if (headers.isNotEmpty()) http.setDefaultRequestProperties(headers)
        http.setTransferListener(object : androidx.media3.datasource.TransferListener {
            override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
            override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                t.ingestBytes += bytesTransferred
            }
            override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
        })
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
                // The HTTP status and the start of the server's answer, so a
                // 503 (no free upstream, or a stop still settling on the
                // server) reads apart from a 4xx in the log.
                val bad = generateSequence(error.cause) { it.cause }
                    .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                val status = bad?.let { e ->
                    val body = runCatching { String(e.responseBody, Charsets.UTF_8) }.getOrDefault("")
                        .replace(Regex("\\s+"), " ").trim().take(160)
                    " http=${e.responseCode}${e.responseMessage?.let { " $it" } ?: ""}" +
                        (if (body.isNotEmpty()) " body=\"$body\"" else "")
                } ?: ""
                Log.w(TAG, "[MV-CAST] composite tile ${t.index} ${t.source?.displayName} error ${error.errorCodeName}$status")
                if (!running || t.player !== player) return
                if (t.retries >= MAX_TILE_RETRIES) {
                    Log.w(TAG, "[MV-CAST] composite tile ${t.index} ${t.source?.displayName}: giving up after $MAX_TILE_RETRIES retries")
                    setNotice(t.index, NOTICE_UNAVAILABLE)
                    return
                }
                t.retries++
                val delay = MultiviewCompositeLayout.tileRetryDelayMs(t.retries)
                Log.i(TAG, "[MV-CAST] composite tile ${t.index} ${t.source?.displayName}: retry ${t.retries}/$MAX_TILE_RETRIES in ${delay / 1000} s")
                setNotice(t.index, NOTICE_RECONNECTING)
                main.postDelayed({ if (running && t.player === player) restartSource(t, player, http) }, delay)
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // Which audio format each tile carries and whether any audio
                // renderer took it: an unsupported track means no PCM ever
                // reaches the composite tap (silence on the receiver).
                val audioGroups = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                val desc = if (audioGroups.isEmpty()) "none" else audioGroups.joinToString(", ") { g ->
                    val f = g.getTrackFormat(0)
                    "${f.sampleMimeType} ${f.channelCount}ch ${f.sampleRate}Hz supported=${g.isSupported} selected=${g.isSelected}"
                }
                Log.i(TAG, "[MV-CAST] composite tile ${t.index} audio tracks: $desc")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        if (t.retries > 0) Log.i(TAG, "[MV-CAST] composite tile ${t.index} ${t.source?.displayName}: playing after ${t.retries} retries")
                        t.retries = 0
                        setNotice(t.index, null)
                        if (t.stalled) {
                            t.stalled = false
                            // The tile's clock restarts where it stopped; the
                            // composite audio re-anchors to it on the next PCM
                            // so lip sync does not carry the stall.
                            Log.i(TAG, "[MV-CAST] composite tile ${t.index} resumed after stall")
                            reanchorTile = t.index
                        }
                        t.wasReady = true
                    }
                    Player.STATE_BUFFERING -> if (t.wasReady) {
                        t.stalled = true
                        t.stalls++
                        Log.i(TAG, "[MV-CAST] composite tile ${t.index} stalled (rebuffering)")
                    }
                    else -> Unit
                }
            }
        })
        player.setMediaSource(
            com.aeriotv.android.feature.multiview.buildTileMediaSource(source.resolvedUrl, http),
        )
        player.playWhenReady = true
        player.prepare()
        t.player = player
        Log.i(TAG, "[MV-CAST] composite tile ${t.index} loading ${source.displayName}")
        if (style.showLogos) loadLogo(t)
    }

    /**
     * A tile's ingest reconnects on a new connection (Apple f33f645 round 7,
     * iPhone log 2026-10-07 15:52 to 15:54: a Dispatcharr client restart
     * starts the new connection BEHIND the newest picture the tile already
     * held, and the old demux and decoder state then discarded every new
     * byte as old, so the tile froze on the TV). A plain prepare() would
     * reuse the old source; this one builds a fresh media source, so the
     * TS demux (and its timestamp anchor) and the video decoder start over
     * on the new connection, and the composite audio re-anchors to the
     * tile's restarted clock on its next PCM.
     */
    private fun restartSource(t: Tile, player: ExoPlayer, http: DefaultHttpDataSource.Factory) {
        val source = t.source ?: return
        Log.i(TAG, "[MV-CAST] composite tile ${t.index} ${source.displayName}: ingest restarted on a new connection, decoder and demux reset (retry ${t.retries})")
        t.wasReady = false
        t.stalled = false
        reanchorTile = t.index
        player.stop()
        player.setMediaSource(
            com.aeriotv.android.feature.multiview.buildTileMediaSource(source.resolvedUrl, http),
        )
        player.playWhenReady = true
        player.prepare()
    }

    private fun loadLogos() = state.forEach { if (it.source != null) loadLogo(it) }

    /** Fetch the tile's channel logo through the app's Coil loader (same
     *  cache as the guide and the local tiles), crop it to its opaque bounds
     *  exactly as the local tile does, and hand it to the GL thread. */
    private fun loadLogo(t: Tile) {
        val source = t.source ?: return
        val url = source.logoUrl
        if (!running || t.logoRequested || url.isBlank()) return
        t.logoRequested = true
        Thread({
            runCatching {
                val loader = coil3.SingletonImageLoader.get(context)
                val req = coil3.request.ImageRequest.Builder(context).data(url).build()
                val image = kotlinx.coroutines.runBlocking { loader.execute(req) }.image ?: return@runCatching
                val bmp = com.aeriotv.android.feature.multiview.TileLogoCrop.softwareBitmap(image) ?: return@runCatching
                val crop = com.aeriotv.android.feature.multiview.TileLogoCrop.cache.get(url)
                    ?: com.aeriotv.android.feature.multiview.TileLogoCrop.opaqueBounds(bmp)
                        .also { com.aeriotv.android.feature.multiview.TileLogoCrop.cache.put(url, it) }
                if (crop.width() <= 0 || crop.height() <= 0) return@runCatching
                val cut = android.graphics.Bitmap.createBitmap(bmp, crop.left, crop.top, crop.width(), crop.height())
                // The slot changed channel while this loaded: drop it.
                if (t.source !== source) return@runCatching
                t.logoAspect = crop.width().toFloat() / crop.height()
                t.logoBitmap = cut
            }.onFailure { Log.w(TAG, "[MV-CAST] composite logo ${t.index} failed: $it") }
        }, "MV-CAST-logo").start()
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
        videoProg = Prog(buildProgram(FS_VIDEO))
        solidProg = Prog(buildProgram(FS_SOLID))
        bitmapProg = Prog(buildProgram(FS_BITMAP))
        iconTex = uploadBitmap(speakerIcon())

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
                    t.framesRendered++
                }
            }
            if (t.dropLogo) {
                t.dropLogo = false
                if (t.logoTex != 0) GLES20.glDeleteTextures(1, intArrayOf(t.logoTex), 0)
                t.logoTex = 0
            }
            t.logoBitmap?.let { bmp ->
                t.logoBitmap = null
                if (t.logoTex != 0) GLES20.glDeleteTextures(1, intArrayOf(t.logoTex), 0)
                t.logoTex = uploadBitmap(bmp)
            }
        }
        val focus = focused
        // Preview first so the encoder swap (which can block on a full
        // encoder queue) never delays the phone's own grid.
        if (previewEglSurface != EGL14.EGL_NO_SURFACE && !backgrounded) {
            if (EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext)) {
                EGL14.eglSwapInterval(eglDisplay, 0)
                val w = IntArray(1)
                val h = IntArray(1)
                EGL14.eglQuerySurface(eglDisplay, previewEglSurface, EGL14.EGL_WIDTH, w, 0)
                EGL14.eglQuerySurface(eglDisplay, previewEglSurface, EGL14.EGL_HEIGHT, h, 0)
                if (w[0] != previewLoggedW || h[0] != previewLoggedH) {
                    previewLoggedW = w[0]
                    previewLoggedH = h[0]
                    Log.i(TAG, "[MV-CAST] preview surface ${w[0]}x${h[0]}")
                }
                draw(w[0], h[0], focus)
                EGL14.eglSwapBuffers(eglDisplay, previewEglSurface)
            }
        }
        EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)
        draw(W, H, focus)
        val elapsed = now - clock.t0Nanos
        if (lastForcedKeyElapsed < 0L || elapsed < lastForcedKeyElapsed ||
            elapsed - lastForcedKeyElapsed >= FORCED_KEY_NANOS
        ) {
            runCatching {
                encoder?.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            }
            lastForcedKeyElapsed = elapsed
        }
        EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, elapsed)
        submitted[elapsed / 1000] = now
        submittedFrames++
        EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)
        val tapAt = pendingFocusTapNanos
        if (tapAt > 0L && focus == pendingFocusIndex) {
            pendingFocusTapNanos = 0L
            Log.i(TAG, "[MV-CAST] focus applied in ${(System.nanoTime() - tapAt) / 1_000_000}ms")
        }
    }

    /** One composite frame into the current surface of [w] x [h]: per
     *  tile, its picture clipped to the tile shape, the channel logo, the
     *  speaker icon and the audio-focus border, as the local tile stacks
     *  them for the user's [style]. */
    private fun draw(w: Int, h: Int, focus: Int) {
        if (w <= 0 || h <= 0) return
        val sx = w / W.toFloat()
        val sy = h / H.toFloat()
        val st = style
        // Round 9 (Apple 6136daf): a session dropped to one tile draws it
        // full frame like a single-channel cast: no padding, corner clip,
        // logo or focus indicator. The other tiles' players are released.
        val order = shown
        val single = order.size == 1
        val rects = if (single) {
            MultiviewCompositeLayout.tileRects(1)
        } else {
            MultiviewCompositeLayout.tileRects(order.size, st.padding, mode = layoutMode)
        }
        val radius = if (single) 0f else MultiviewCompositeLayout.cornerRadius(st)
        val since = System.nanoTime() - focusChangedAtNanos
        val accent = focusArgb
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        for ((pos, slot) in order.withIndex()) {
            val t = state.getOrNull(slot) ?: continue
            val cell = rects.getOrNull(pos) ?: continue
            val isFocus = t.index == focus && !single
            val pic = MultiviewCompositeLayout.letterbox(cell, t.videoWidth, t.videoHeight, t.pixelRatio)
            if (t.hasFrame) {
                val vp = videoProg ?: continue
                GLES20.glUseProgram(vp.id)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t.texId)
                setClip(vp, cell, radius, sx, sy, h)
                quad(vp, pic, t.texMatrix, sx, sy, h)
            }
            if (!single && st.showLogos && t.logoTex != 0 && t.logoAspect > 0f) {
                val place = MultiviewCompositeLayout.logoPlacement(pic, t.logoAspect, st)
                solid(place.backdrop, 4 * MultiviewCompositeLayout.DP, 0f, 0x8C000000.toInt(), sx, sy, h)
                bitmap(t.logoTex, place.logo, 0xFFFFFFFF.toInt(), 1f, sx, sy, h)
            }
            if (isFocus) {
                val iconAlpha = MultiviewCompositeLayout.iconAlpha(st, since)
                if (iconAlpha > 0f && iconTex != 0) {
                    val size = MultiviewCompositeLayout.ICON_PX
                    val icon = CompositeRect(
                        cell.left + (cell.width - size) / 2, cell.top + (cell.height - size) / 2, size, size,
                    )
                    bitmap(iconTex, icon, accent, iconAlpha, sx, sy, h)
                }
                MultiviewCompositeLayout.focusBorderArgb(st, accent, since)?.let { argb ->
                    solid(cell, radius, MultiviewCompositeLayout.FOCUS_BORDER_PX, argb, sx, sy, h)
                }
            }
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Window-pixel (bottom-left origin) clip rect for [r]. */
    private fun setClip(p: Prog, r: CompositeRect, radius: Float, sx: Float, sy: Float, h: Int) {
        GLES20.glUniform4f(p.uClip, r.left * sx, h - r.bottom * sy, r.right * sx, h - r.top * sy)
        GLES20.glUniform1f(p.uRadius, radius * sx)
    }

    /** Draw the current program's quad over [r] with texture matrix [m]. */
    private fun quad(p: Prog, r: CompositeRect, m: FloatArray, sx: Float, sy: Float, h: Int) {
        val vx = (r.left * sx).toInt()
        val vw = (r.right * sx).toInt() - vx
        val top = (r.top * sy).toInt()
        val vh = (r.bottom * sy).toInt() - top
        if (vw <= 0 || vh <= 0) return
        GLES20.glViewport(vx, h - top - vh, vw, vh)
        GLES20.glEnableVertexAttribArray(p.aPos)
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, quadPos)
        GLES20.glEnableVertexAttribArray(p.aTex)
        GLES20.glVertexAttribPointer(p.aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex)
        GLES20.glUniformMatrix4fv(p.uTex, 1, false, m, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** A rounded rect fill ([stroke] 0) or ring of [argb]. */
    private fun solid(r: CompositeRect, radius: Float, stroke: Float, argb: Int, sx: Float, sy: Float, h: Int) {
        val p = solidProg ?: return
        GLES20.glUseProgram(p.id)
        setClip(p, r, radius, sx, sy, h)
        GLES20.glUniform1f(p.uStroke, stroke * sx)
        setColor(p, argb, 1f)
        quad(p, r, IDENTITY, sx, sy, h)
    }

    /** Bitmap texture [tex] over [r], tinted by [argb] at [alpha]. */
    private fun bitmap(tex: Int, r: CompositeRect, argb: Int, alpha: Float, sx: Float, sy: Float, h: Int) {
        val p = bitmapProg ?: return
        GLES20.glUseProgram(p.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        setColor(p, argb, alpha)
        quad(p, r, FLIP_Y, sx, sy, h)
    }

    /** Premultiplied [argb] at an extra [alpha]. */
    private fun setColor(p: Prog, argb: Int, alpha: Float) {
        val a = ((argb ushr 24) and 0xFF) / 255f * alpha
        GLES20.glUniform4f(
            p.uColor,
            ((argb shr 16) and 0xFF) / 255f * a,
            ((argb shr 8) and 0xFF) / 255f * a,
            (argb and 0xFF) / 255f * a,
            a,
        )
    }

    private fun uploadBitmap(bmp: android.graphics.Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // GLUtils uploads premultiplied, which the blend func expects.
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return ids[0]
    }

    /** The local speaker icon in white at its 48 dp size; tinted per frame. */
    private fun speakerIcon(): android.graphics.Bitmap {
        val size = MultiviewCompositeLayout.ICON_PX
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.scale(size / 24f, size / 24f)
        val path = androidx.core.graphics.PathParser.createPathFromPathData(VOLUME_UP_PATH)
        canvas.drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        return bmp
    }

    private fun buildProgram(fs: String): Int {
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
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
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
                        encodedTotal++
                        if (backgrounded) bgFrames++
                        val ticks = clock.ticksForUs(info.presentationTimeUs)
                        synchronized(muxLock) {
                            muxer.writeVideo(bytes, ticks, key, parameterSets)
                            if (key && firstKeyTicks < 0) firstKeyTicks = ticks
                        }
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
            if (backgrounded && now - bgSinceNanos >= BG_STATS_NANOS) {
                Log.i(
                    TAG,
                    "[MV-BG] composite still casting in background: frames=$bgFrames " +
                        "audioChunks=$bgAudioChunks lastMin (${(now - lastOutputNanos) / 1_000_000}ms since last frame)",
                )
                bgSinceNanos = now
                bgFrames = 0
                bgAudioChunks = 0
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

    // ---- health line (Apple a4bd790 parity), main thread, every 10 s ----

    private var healthAt = 0L
    private var healthEncoded = 0L
    private var healthDropped = 0
    private var healthAudio = 0L
    private var healthSilence = 0L
    private val healthBytes = LongArray(MultiviewCompositeLayout.MAX_TILES)
    private val healthFrames = LongArray(MultiviewCompositeLayout.MAX_TILES)
    private val healthStalls = IntArray(MultiviewCompositeLayout.MAX_TILES)

    private val health = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            if (healthAt == 0L) healthAt = now - STATS_NANOS
            val seconds = ((now - healthAt) / 1e9).coerceAtLeast(0.001)
            val tiles = shown.map { state[it] }.joinToString(" ") { t ->
                val bytes = t.ingestBytes
                val frames = t.framesRendered
                val bps = ((bytes - healthBytes[t.index]) / seconds).toLong()
                val fr = frames - healthFrames[t.index]
                // Stalls per window, like every other number on this line.
                val stallsTotal = t.stalls
                val st = stallsTotal - healthStalls[t.index]
                healthBytes[t.index] = bytes
                healthFrames[t.index] = frames
                healthStalls[t.index] = stallsTotal
                val buffered = runCatching { t.player?.totalBufferedDuration ?: -1L }.getOrDefault(-1L)
                "t${t.index}[in=${bps}B/s frames=$fr stalls=$st${if (t.stalled) "(now)" else ""} buf=${buffered}ms]"
            }
            val enc = encodedTotal
            val dropped = droppedTicks
            val audioChunks = audioChunksTotal
            Log.i(
                TAG,
                "[MV-CAST] health $tiles enc fps=${"%.1f".format(java.util.Locale.US, (enc - healthEncoded) / seconds)} " +
                    "dropped=${dropped - healthDropped} queue=${submitted.size} audioChunks=${audioChunks - healthAudio}",
            )
            // Audio diagnostic (2026-10-08, Nothing Phone composite cast with
            // no sound): which tile is focused, which tile's PCM actually
            // reached the tap, how loud it was (dBFS; -inf = no PCM or
            // digital silence), AAC frames per second, generated silence
            // blocks, and the encoder's csd-0.
            val (pcmTile, rmsDb, pcmFmt) = synchronized(normalizer) {
                val r = if (pcmSamples > 0) Math.sqrt(pcmSumSq / pcmSamples) / 32768.0 else 0.0
                val db = if (r > 0) "%.1f".format(java.util.Locale.US, 20 * Math.log10(r)) else "-inf"
                val out = Triple(pcmTileWindow, db, pcmFormatWindow)
                pcmTileWindow = -1; pcmSumSq = 0.0; pcmSamples = 0L; pcmFormatWindow = ""
                out
            }
            val silence = audio.silenceBlocks
            Log.i(
                TAG,
                "[MV-CAST] audio focused=$focused tap=$pcmTile pcm=${pcmFmt.ifEmpty { "none" }} rms=${rmsDb}dBFS " +
                    "aac=${"%.1f".format(java.util.Locale.US, (audioChunks - healthAudio) / seconds)}/s " +
                    "silenceBlocks=${silence - healthSilence} dropped=${audio.droppedChunks} csd0=${audio.csd0Hex.ifEmpty { "none" }}",
            )
            healthSilence = silence
            healthEncoded = enc
            healthDropped = dropped
            healthAudio = audioChunks
            healthAt = now
            main.postDelayed(this, STATS_NANOS / 1_000_000)
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
