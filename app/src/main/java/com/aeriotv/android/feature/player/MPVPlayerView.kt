package com.aeriotv.android.feature.player

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.util.Log
import `is`.xyz.mpv.BaseMPVView

/**
 * AerioTV's libmpv view. Ports iOS Aerio/App/MPVPlayerView.swift option sequence
 * (lines 3055-3800) to Android. Every option here has a source-line reference to
 * the iOS file so parity drift can be audited.
 *
 * @param isLive Whether this view will play live streams. Switches a few demuxer/cache
 *               options between live (low-latency) and VOD (smooth-resume) values.
 *               iOS MPVPlayerView.swift line 3194/3326.
 */
class MPVPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    var isLive: Boolean = true,
) : BaseMPVView(context, attrs) {

    private val tag = "MPVPlayerView"

    // NOTE: do NOT call setBackgroundColor on the SurfaceView itself --
    // Samsung's compositor (and several OEM-modified Android builds) treat
    // an opaque background on a SurfaceView as a signal to skip the
    // surface punch-through, which kills video rendering entirely (the
    // surface still gets frames internally but they're never composited
    // into the View tree). Symptom: the player chrome loads but the
    // stream never appears. The black "loading gap" is handled by the
    // parent Box in PlayerScreen / VODPlayerScreen instead -- that's a
    // regular Compose Box and has no punch-through semantics.

    /**
     * Absolute path to the Mozilla CA bundle copied from the AAR's assets/cacert.pem
     * to the app's filesDir by `Utils.copyAssets`. PlayerScreen sets this BEFORE
     * calling [initialize] so initOptions can wire it as the TLS root store.
     * Without it, mbedTLS rejects every HTTPS handshake - Android does not expose
     * its system trust store in a format libmpv can read.
     */
    var caFilePath: String? = null

    /**
     * Optional HTTP headers to attach to every fetch the underlying ffmpeg/mpv stream
     * stack makes (segment requests, manifests, etc.). Used for Dispatcharr API key
     * auth on `/proxy/ts/stream/...` and any future XC/server-specific custom headers.
     * Mirrors iOS MPVPlayerView.swift lines 3516-3527 (`user-agent` + `http-header-fields`).
     */
    var httpHeaders: Map<String, String> = emptyMap()

    /**
     * User-chosen stream buffer in milliseconds, from Settings -> Network -> Buffer Size.
     * Mirrors iOS MPVPlayerView.swift line 3681 -> 3705. Applied at postInitOptions as
     * `cache-secs` + `demuxer-readahead-secs`. Live streams enforce a 5s minimum
     * regardless of user choice to absorb audio-decoder underruns.
     */
    var cachingMs: Int = 1_000

    /**
     * True on Android TV / Google TV. Drives the 10-foot resilience tier (longer
     * live cache floor + larger audio buffer) since TV boxes are passively cooled
     * like the Apple TV, where iOS bumps these same values (cache 10s / audio-buffer
     * 2.5s on tvOS vs 5s / 1.0s elsewhere).
     */
    private val isTv: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION

    /**
     * Pre-init options (iOS lines 3055-3527, before mpv_initialize).
     * Order matters for some options (hwdec must be set before init).
     */
    override fun initOptions() {
        val m = mpv

        // Thermal mitigation on passively-cooled Android TV hardware. Default
        // mpv log-level emits dozens of "info" lines per second (vo resize,
        // VIDEO_RECONFIG, track changes, libplacebo shader compiles), each of
        // which goes through addLogObserver -> Log.i in PlayerScreen. On a
        // long session this measurably warms the chip and the user reports
        // sluggishness even after closing the app (thermal throttling lingers
        // ~minutes after the workload ends on the Google TV Streamer). Cap
        // at warn+error in debug + release; we still capture failure modes,
        // we just stop spending cycles on success chatter.
        m.setOptionString("msg-level", "all=warn")

        m.setOptionString("subs-match-os-language", "yes")  // iOS 3107
        m.setOptionString("subs-fallback", "yes")           // iOS 3108

        m.setOptionString("profile", "fast")                // iOS 3113 — disable expensive post-processing for mobile

        // HDR tone-map to SDR (iOS v1.7.3 commit 63ca580 port).
        //
        // mpv's libmpv GLES render path collapses BT.2020 / PQ / HLG
        // sources into the 8-bit RGB SurfaceView surface with NO gamut
        // or transfer conversion by default -- HDR channels (Sky Sports
        // Main Event UHD class) come out green and washed out, plus
        // libplacebo emits the `r16u` shader fallback warnings seen on
        // the Streamer with channel 38.
        //
        // Pinning the render target to BT.709 SDR makes mpv do the
        // BT.2020 -> 709 gamut map and HDR -> SDR tone-map itself. No-op
        // for SDR sources (709 -> 709), so this is safe to set
        // unconditionally with no HDR detection. iOS used bt.1886 for
        // target-trc (display EOTF; "bt.709" is rejected). Tone-mapping
        // algorithm is bt.2390, ITU-R BT.2390 hard-knee that's both fast
        // on weak GPUs and visually faithful for broadcast HDR.
        //
        // True HDR output stays deferred until a future architectural
        // path that can hand a hardware-decoded HDR surface directly to
        // SurfaceFlinger without GLES re-blit.
        m.setOptionString("target-prim", "bt.709")
        m.setOptionString("target-trc", "bt.1886")
        m.setOptionString("tone-mapping", "bt.2390")

        // Hardware decode. Android analog of iOS videotoolbox-copy (iOS 3157).
        // Copy path: decoder writes to a CPU-readable buffer, app uploads to GL.
        // Avoids the v1.7.x 10-bit HEVC blue-screen seen with zero-copy paths.
        m.setOptionString("hwdec", "mediacodec-copy")
        // iOS 3172. Allow 90 consecutive decode failures before falling back to software.
        // Live MPEG-TS mid-GOP joins can need ~3s at 30fps to hit the next keyframe.
        m.setOptionString("hwdec-software-fallback", "90")
        m.setOptionString("vd-lavc-threads", "1")           // iOS 3183 — parser thread safety

        // Cache pause behavior. iOS 3194.
        m.setOptionString("cache-pause-wait", if (isLive) "0" else "2")

        m.setOptionString("initial-audio-sync", "no")       // iOS 3235
        m.setOptionString("vd-lavc-fast", "yes")            // iOS 3236
        m.setOptionString("vd-lavc-skiploopfilter", "nonref") // iOS 3237

        // HTTP reconnect on transient drops. iOS 3240-3241.
        m.setOptionString(
            "stream-lavf-o",
            "reconnect=1,reconnect_streamed=1,reconnect_delay_max=2"
        )
        m.setOptionString("network-timeout", "30")          // iOS 3256 — cold-start TLS/OCSP headroom

        // TLS root store (Android-specific; iOS uses SecureTransport, no equivalent option).
        // mpv-android-lib bundles Mozilla cacert.pem in its AAR assets; Utils.copyAssets
        // places it in filesDir at init. Without this, mbedtls fails every HTTPS handshake.
        caFilePath?.let { path ->
            m.setOptionString("tls-ca-file", path)
            m.setOptionString("tls-verify", "yes")
        }

        // HTTP headers for the stream URL (iOS lines 3516-3527). User-Agent is its own
        // option; everything else goes as one CRLF-separated string under
        // `http-header-fields`. Crucial for Dispatcharr API-key proxy URLs and any
        // server that requires Authorization on /proxy/ts/stream/<uuid>.
        val userAgent = httpHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        if (userAgent != null) {
            m.setOptionString("user-agent", userAgent)
        }
        val otherHeaders = httpHeaders.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        if (otherHeaders.isNotEmpty()) {
            val packed = otherHeaders.entries.joinToString(separator = "\r\n") { (k, v) -> "$k: $v" }
            m.setOptionString("http-header-fields", packed)
        }

        if (isLive) {
            // Live-only demuxer tuning. iOS 3326-3332.
            // analyzeduration=1.5 (was 0.1; 100ms not enough for 30fps FPS lock — v1.7.0 fix).
            m.setOptionString("demuxer-lavf-analyzeduration", "1.5")
            // probesize=1MB (was 32K; needed for UHD HEVC SPS/PPS/VPS NALs).
            m.setOptionString("demuxer-lavf-probesize", "1048576")
            m.setOptionString("cache-pause-initial", "no")
            m.setOptionString("hls-bitrate", "max")
            m.setOptionString("video-latency-hacks", "yes")
            // Bound the live demuxer queue. iOS 3833+: without a cap, a faster-
            // than-realtime decoder lets the video packet queue grow unbounded
            // while audio starves -> A/V desync + the "too many packets in
            // demuxer queue" stutter. 8 MiB forward, no back-buffer, no donation.
            m.setOptionString("demuxer-max-bytes", "8MiB")
            m.setOptionString("demuxer-max-back-bytes", "0")
            m.setOptionString("demuxer-donate-buffer", "no")
        } else {
            // VOD: bigger queue for smooth seeking + a back-buffer for short
            // back-seeks. iOS 3832.
            m.setOptionString("demuxer-max-bytes", "50MiB")
            m.setOptionString("demuxer-max-back-bytes", "10MiB")
        }

        // Frame-drop + A/V sync. iOS 3373-3375.
        m.setOptionString("framedrop", "vo")
        m.setOptionString("video-sync", "audio")
        m.setOptionString("video-timing-offset", "0")

        // Black-flash mitigation. iOS 3422-3423.
        m.setOptionString("demuxer-lavf-o", "fflags=+discardcorrupt")

        // HDR -> SDR tone-map. The gpu-next/libplacebo path rendering into the
        // SurfaceView can't reliably emit HDR, so pin the render target to SDR
        // (bt.709) and let mpv tone-map (bt.2390). This makes UHD HEVC HDR (e.g.
        // Sky Sports UHD) render with correct, non-washed-out colors instead of
        // a green/grey cast. Unconditional: a no-op for SDR content. iOS 3495-3497.
        m.setOptionString("target-prim", "bt.709")
        m.setOptionString("target-trc", "bt.709")
        m.setOptionString("tone-mapping", "bt.2390")

        // ──────────────────────────────────────────────────────────────
        // Audio output buffering. mpv's default audio-buffer of 200 ms is
        // too aggressive for Android's AudioTrack scheduling on real HALs
        // (and especially on emulators where audio runs through QEMU's
        // translated audio pipe). Field-tested on the Pixel 10 Pro XL
        // emulator: sustained ESPN HD playback degenerates after ~10 min
        // into a tight underrun-restart loop ("Audio device underrun
        // detected" -> "restarting audio after underrun" every 100-300ms),
        // which the user hears as constant clicks/pops and which drags
        // video-sync=audio into syncing onto a constantly-restarting
        // clock -- exactly the "awful playback" symptom.
        //
        // audio-buffer=1.0: 5x the default headroom. Adds ~800ms of
        // tolerance against the audio thread getting preempted before
        // the next chunk lands. The user-facing cost is one extra
        // sentence of audio latency on a live stream, well under the
        // perception threshold for IPTV.
        //
        // audio-stream-silence=yes: when mpv would otherwise close
        // AudioTrack and reopen on demand, instead keep it open and
        // push silence frames. Eliminates the cascading reopen storm
        // that follows the first underrun. Costs marginal CPU (a memset
        // every frame's worth of samples) but completely closes the
        // feedback loop where each restart triggered the next underrun.
        // 2.5s on Android TV (passively cooled, like the Apple TV where iOS uses
        // 2.5; thermal throttling makes the audio thread miss deadlines more often),
        // 1.0s elsewhere.
        m.setOptionString("audio-buffer", if (isTv) "2.5" else "1.0")
        m.setOptionString("audio-stream-silence", "yes")

        Log.i(tag, "initOptions complete (isLive=$isLive)")
    }

    /**
     * Post-init options (iOS lines 3761-3800, after mpv_initialize via property API).
     * Properties can only be set on an initialized MPV instance.
     */
    override fun postInitOptions() {
        val m = mpv

        // iOS re-asserts demuxer options as properties after init (belt and suspenders).
        m.setPropertyString("demuxer-lavf-probe-info", "auto")          // iOS 3761
        m.setPropertyString("demuxer-lavf-analyzeduration", "1.5")      // iOS 3762
        m.setPropertyString("demuxer-lavf-probesize", "1048576")        // iOS 3763

        m.setPropertyString("hr-seek-framedrop", "yes")                 // iOS 3791

        // Upgrade frame-drop now that init is complete. iOS 3771.
        m.setPropertyString("framedrop", "decoder+vo")

        // Cache window. Mirrors iOS MPVPlayerView.swift:3679+ — user-chosen buffer
        // size (set via [cachingMs] before initialize()) is the floor, with a 5s
        // live-minimum enforcement so audio-output underruns don't freeze video.
        // Live cache floor: 10s on Android TV (passive cooling -> deeper buffer
        // against thermal hitches, matching tvOS), 5s elsewhere.
        val liveFloorMs = if (isTv) 10_000 else 5_000
        val effectiveMs = if (isLive) maxOf(cachingMs, liveFloorMs) else cachingMs
        val effectiveSecs = effectiveMs / 1000.0
        m.setPropertyString("cache", "yes")
        m.setPropertyString("demuxer-readahead-secs", String.format(java.util.Locale.US, "%.1f", effectiveSecs))
        m.setPropertyString("cache-secs", String.format(java.util.Locale.US, "%.1f", effectiveSecs))

        Log.i(tag, "postInitOptions complete")
    }

    /**
     * Properties to observe via MPV's property-change event stream.
     * iOS observes time-pos, duration, pause, volume, etc. for SwiftUI state binding.
     * Phase 1a: observe the minimum needed to verify playback.
     */
    override fun observeProperties() {
        val m = mpv
        // Format codes from libmpv mpv_format enum.
        // 1=NODE, 2=STRING, 3=OSD_STRING, 4=FLAG, 5=INT64, 6=DOUBLE
        val MPV_FORMAT_FLAG = 3
        val MPV_FORMAT_INT64 = 4
        val MPV_FORMAT_DOUBLE = 5
        val MPV_FORMAT_STRING = 1

        m.observeProperty("time-pos", MPV_FORMAT_DOUBLE)
        m.observeProperty("duration", MPV_FORMAT_DOUBLE)
        m.observeProperty("pause", MPV_FORMAT_FLAG)
        m.observeProperty("eof-reached", MPV_FORMAT_FLAG)
        m.observeProperty("video-format", MPV_FORMAT_STRING)
        m.observeProperty("audio-codec-name", MPV_FORMAT_STRING)
        m.observeProperty("width", MPV_FORMAT_INT64)
        m.observeProperty("height", MPV_FORMAT_INT64)

        Log.i(tag, "observeProperties complete")
    }
}
