# Vendored libraries

## media3-decoder-ffmpeg.aar

The Media3 FFmpeg audio-decoder extension. Google does not publish a prebuilt
artifact for it, so this AAR is built from source and vendored here. It provides
software decoding for audio codecs that many devices have no hardware MediaCodec
for, notably **AC-3 and MP2** carried by US ATSC broadcast channels. Without it,
ExoPlayer reports "no audio tracks" and plays silent on boxes like the
Chromecast with Google TV. It is wired in as the fallback audio renderer
(`EXTENSION_RENDERER_MODE_ON`) in `core/playback/AerioRenderers.kt`.

### Enabled decoders (2026-09-12)

```
ac3 mp2 mp3 flac alac
```

**Why this set.** Two trims got it here.

2026-09-11 dropped `eac3`, `dca`, `mlp` and `truehd`: those four are the ones
with live patent exposure (E-AC-3, DTS and TrueHD / MLP are all covered by
patents that have NOT expired), and shipping a software decoder for them in a
distributed binary is a licensing risk the app does not need to carry.

2026-09-12 dropped `aac` as well (Logan's decision). AAC's essential patents
have expired, but every Android device the app supports has a hardware AAC
decoder: AAC is mandatory in the Android CDD, so a software AAC decoder in the
binary was never reached on real hardware and only added size. The five that
remain are the ones a device may genuinely lack: AC-3 and MP2 on US ATSC
broadcast channels, and MP3 / FLAC / ALAC in on-demand files.

Behavioral consequences:

- E-AC-3, DTS and TrueHD have NO software decoder and fall through to the
  platform MediaCodec only. Devices with a hardware decoder for them (most TVs,
  most phones, Shield, Google TV Streamer) are unaffected. A device with
  neither hardware nor software support logs the unsupported audio group from
  the GH #8 diagnostic in `AerioExoPlayerHolder` and plays silent, which is the
  same outcome as any other codec the device cannot decode.
- AAC, including HE-AAC / AAC+ (SBR) on-demand recordings, now relies entirely
  on the device's hardware decoder. This is the GH #45 path: the on-demand
  renderer mode stays `EXTENSION_RENDERER_MODE_PREFER`, but with no software
  AAC in the build the FFmpeg renderer no longer advertises the MIME, so every
  AAC track falls through to MediaCodec. See the note in
  `core/playback/AerioRenderers.kt`.

`FfmpegLibrary.supportsFormat` is a runtime query against the native library,
so nothing in the app needed a MIME allow-list change; the Cast on-phone
transcoder (`CastAudioTranscoder`) likewise just stops finding an FFmpeg
fallback for these formats and refuses the session when the phone also has no
platform decoder.

### How it was built (reproducible)

- media3 checkout: tag `1.4.1` (matches the `media3` version in
  `gradle/libs.versions.toml`)
- ffmpeg: `release/6.0` (n6.0.x) cloned into
  `libraries/decoder_ffmpeg/src/main/jni/ffmpeg`
- NDK `25.1.8937393`, cmake `3.31.x`, nasm (for the x86_64 build)
- module `minSdkVersion` raised to 21 so every ABI links at >= 21 (the app's
  own minSdk is 26)
- `libraries/decoder_ffmpeg/src/main/jni/CMakeLists.txt` patched with
  `target_link_options(ffmpegJNI PRIVATE "-Wl,-z,max-page-size=16384")` so the
  .so links with 16 KB ELF page alignment (Android 15+/16 requirement; NDK r26
  and below default to 4 KB and Android 16 then runs the app in "page size
  compatible mode" with a launch warning). The ffmpeg static libs need no
  rebuild for this; alignment is fixed at the final shared-object link. Verify
  with `llvm-readelf -l libffmpegJNI.so`: every LOAD segment must show align
  `0x4000`.

```
# in the media3 checkout, from libraries/decoder_ffmpeg/src/main/jni
./build_ffmpeg.sh "<repo>/libraries/decoder_ffmpeg/src/main" \
  "$ANDROID_SDK/ndk/25.1.8937393" darwin-x86_64 21 \
  ac3 mp2 mp3 flac alac
# then, from the media3 checkout root
./gradlew :lib-decoder-ffmpeg:assembleRelease
# output: libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar
```

The .so is stripped, so verify the codec set with `strings` rather than `nm`:
`strings -a libffmpegJNI.so | grep -x ff_ac3_decoder` must hit, and
`ff_aac_decoder`, `ff_eac3_decoder`, `ff_dca_decoder`, `ff_mlp_decoder`,
`ff_truehd_decoder` must all miss, on each of the four ABIs.

Rebuild and replace this file when bumping the `media3` version so the extension
stays binary-compatible with the maven media3 artifacts. Keep the decoder list
above; do not re-add the patent-encumbered four, and do not re-add `aac`.
