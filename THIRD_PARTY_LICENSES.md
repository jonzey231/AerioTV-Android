# Third-party licenses

AerioTV for Android is licensed under the GNU General Public License v3.0 or
later (see [LICENSE](LICENSE) and [LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md)).
This file lists the third-party components it distributes or links, and the terms
they carry.

The same information is available inside the app under
**Settings > About > Open Source Licenses**, together with the full text of each
license.

## FFmpeg (LGPL-2.1-or-later)

`app/libs/media3-decoder-ffmpeg.aar` bundles a build of FFmpeg. It is wired in as
the fallback software audio renderer and supplies AAC, AC-3, E-AC-3, DTS, TrueHD
and MP2 decoding on devices with no hardware MediaCodec for those codecs, or
whose platform decoder offers them only as a passthrough bitstream.

| | |
|---|---|
| Upstream | https://ffmpeg.org |
| Version | FFmpeg `release/6.0` (libavcodec 60.3.100) |
| Source | https://github.com/FFmpeg/FFmpeg/tree/release/6.0 |
| Modifications | None. Upstream source is used unmodified. |
| License | GNU Lesser General Public License, version 2.1 or later |

The library is configured through the Media3 `decoder_ffmpeg` extension's
`build_ffmpeg.sh` **without** `--enable-gpl`, enabling only these decoders:

```
aac ac3 eac3 dca truehd mlp mp2 mp3 flac alac
```

Those ten cover, in order: AAC (including HE-AAC / AAC+), AC-3, E-AC-3 (Dolby
Digital Plus), DTS / DTS-HD core, TrueHD, MLP, MPEG audio layers II and III,
FLAC and Apple Lossless.

All of the above are LGPL-clean. No GPL-only component (x264, x265, libpostproc)
is linked in. `eac3`, `dca`, `truehd` and `mlp` were removed on 2026-09-11, and
`aac` on 2026-09-12; all five were restored on 2026-09-14, matching the decoder
set that VLC and Kodi ship. Without them, a device whose platform decoder exposes
E-AC-3 for bitstream only played those tracks silent whenever surround
passthrough was off, and HE-AAC (AAC+ / SBR) on-demand recordings had no fallback
for the hardware AAC decoders that fail on them. See the
Patents section of [README.md](README.md) for the patent notice that accompanies
these decoders. The complete, reproducible build steps are recorded in
[app/libs/README.md](app/libs/README.md).

### Relinking

FFmpeg is loaded as a dynamically linked JNI shared object (`libffmpegJNI.so`),
so it can be replaced with a modified build, as section 6 of the LGPL requires.
To do so: rebuild the AAR following `app/libs/README.md`, replace
`app/libs/media3-decoder-ffmpeg.aar`, and rebuild the app.

If you would rather receive the corresponding FFmpeg source directly, open an
issue at https://github.com/jonzey231/AerioTV-Android/issues and it will be
provided.

## Apache License 2.0

The following are used under the Apache License, Version 2.0. Full text:
https://www.apache.org/licenses/LICENSE-2.0

**AndroidX and Jetpack Compose** (The Android Open Source Project)
`androidx.core`, `androidx.lifecycle`, `androidx.activity`, `androidx.compose.*`
(UI, Material 3, Material Icons, window size class), `androidx.navigation`,
`androidx.tv:tv-material`, `androidx.tvprovider`, `androidx.room`,
`androidx.datastore`, `androidx.documentfile`, `androidx.profileinstaller`,
`androidx.credentials`, `androidx.work`, `androidx.hilt`, `androidx.media`,
`androidx.media3` (ExoPlayer, HLS, DASH, extractor, session, UI, OkHttp
datasource), `androidx.mediarouter`, `androidx.camera` (CameraX core, Camera2,
lifecycle, view)

**Kotlin and JetBrains** (JetBrains s.r.o.)
Kotlin standard library, `kotlinx.coroutines`, `kotlinx.serialization`, Ktor
(client and server)

**Others**
- Dagger and Hilt (Google) https://github.com/google/dagger
- OkHttp (Square) https://github.com/square/okhttp
- Okio (Square) https://github.com/square/okio
- Coil 3 (Coil Contributors) https://github.com/coil-kt/coil
- ZXing Core (ZXing Authors) https://github.com/zxing/zxing
- Reorderable (Calvin Liang) https://github.com/Calvin-LL/Reorderable

## Google Play services (proprietary)

`play-services-cast-framework`, `play-services-cast-tv`, `play-services-auth`,
and the Google Identity library are **not** open source. They are distributed
under the Android Software Development Kit License Agreement and the Google APIs
Terms of Service, are linked from Google's Maven repository, and are not
redistributed in source form here.

Because those terms are not GPL-compatible, the copyright holder grants an
additional permission under GPL section 7 to permit linking them with this
program. See [LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md).

## Test-only dependencies

JUnit (Eclipse Public License 1.0) is used at test scope only and is not part of
any shipped binary.

---

Keeping this current: when a dependency is added, removed, or swapped in
`gradle/libs.versions.toml`, update this file and the matching entry in
`app/src/main/java/com/aeriotv/android/feature/settings/LicensesScreen.kt`.
