# AerioTV for Android: working notes for Claude

## 1. What this is
- Native IPTV client, Kotlin + Jetpack Compose, one package for phones, tablets and Android TV / Google TV:
  `com.aeriotv.android` (namespace and applicationId). User-facing name is **AerioTV**; internal names may stay "Aerio".
- Backends: Dispatcharr (admin login or API key), Xtream Codes, plain M3U. Live TV + EPG guide, movies/series (VOD),
  DVR (server or on-device), Multiview, reminders, Google Drive sync, Chromecast sender, Cast Connect receiver on TV.
- Sibling of the Apple app (Swift, repo `~/Developer/AerioTV`). The two must stay in parity (see rules).

## 2. Build
- Always first: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- Flavors (dimension `distribution`): `play` (UPDATER_ENABLED=false, no self-updater, no REQUEST_INSTALL_PACKAGES)
  and `github` (sideload, in-app updater). Build types: `debug`, `release` (R8 + shrink, release signing only if
  configured), `perf` (release settings signed with the debug key; installs over a debug install; use for perf measurements).
- Debug: `./gradlew :app:assemblePlayDebug` or `./gradlew :app:assembleGithubDebug`
- APKs: `app/build/outputs/apk/play/debug/app-play-debug.apk`, `app/build/outputs/apk/github/debug/app-github-debug.apk`
- Perf: `./gradlew :app:assembleGithubPerf` (or `assemblePlayPerf`).
- Unit tests: `./gradlew :app:testPlayDebugUnitTest` (JVM, `app/src/test`; cast proxy, remuxer, transcode plan,
  guide merge/identity, Dispatcharr capability tests live there).
- Release: `:app:assembleGithubRelease` (GitHub APK) and `:app:bundlePlayRelease` (Play AAB). Release signing, AAB and
  version bumps happen ONLY when the owner says so.
- Cast receiver app id comes from `CAST_RECEIVER_APP_ID` in `local.properties` (empty = Cast disabled).

## 3. Devices and adb
1. `adb devices -l` first, every time; pick the serial.
2. `adb -s <serial> logcat -G 32M` before EVERY playback session (the default buffer wraps in about 90 s).
3. `adb -s <serial> logcat -c` to clear before a repro; `adb -s <serial> logcat -d > file.txt` to pull.
4. Install: `adb -s <serial> install -r <apk>`. Install replaces a running app in place; to be sure the new code runs,
   `adb -s <serial> shell am force-stop com.aeriotv.android` then relaunch.
5. Phone launch: `adb shell monkey -p com.aeriotv.android -c android.intent.category.LAUNCHER 1`.
   TV launch uses `-c android.intent.category.LEANBACK_LAUNCHER`.
6. Install fails on signature mismatch: uninstall, reinstall. Flaky adb: `adb kill-server`.
- Key log tags: `AerioCast` (`[Cast]` sender lines), `CAST-HLS` (proxy session), `CastCard` (card UI),
  `CastReceiver` (TV Cast Connect), `CompanionHost` / `CompanionClient` / `CompanionDiscover` (AerioTV Remote).

## 4. Architecture map
Root: `app/src/main/java/com/aeriotv/android/` (`MainActivity.kt`, `Navigation.kt`, `AerioTVApplication.kt`),
`core/` (app, cast, data, guide, network, playback, preferences, remote, sync, tv, ...) and `feature/` (cast, channels,
dvr, livetv, main, miniplayer, movies, multiview, ondemand, player, settings, ...).

Cast sender (phone)
- `core/cast/AerioCastSender.kt`: session lifecycle; sends `hello` on `urn:x-cast:com.aeriotv.control` and latches
  `ANDROID_TV_APP` (native, no proxy) or `WEB_RECEIVER` (phone HLS proxy) from the `receiverInfo` answer or timeout.
- `core/cast/CastControl.kt`: custom-namespace command shapes (getState, setAudio, setText, setChannel, seekBy, goLive...).
- `core/cast/NativeCastDevices.kt`: the "AerioTV on TV" section of the picker.
- `feature/cast/CastControls.kt`: `CastRouteChooserDialog` (the picker). `feature/cast/CastTransportCard.kt` + 
  `feature/miniplayer/CastMiniController.kt`: the cast card above the nav bar. `feature/cast/CastRemoteSheet.kt`: the
  controls sheet (tap the card). Card/sheet placement is in `feature/main/MainScaffold.kt`.

Local HLS proxy for the web receiver (`core/cast/hlsproxy/`)
- `CastHlsProxyServer` (LAN HTTP server, keep-alive) + `CastHlsProxyService` (foreground service).
- `CastHlsProxySession`: ingests the upstream TS (OkHttp, 15 s read timeout), feeds the remuxer.
- `TsToFmp4Remuxer`, `CastAudioFramer`: TS to fMP4 HLS segments.
- `CastVideoPlan.decide()`: passthrough vs on-phone MediaCodec transcode, from the receiver's capability answers.
- `CastVideoTranscoder`: platform MediaCodec encode; HDR kept when the receiver presents HDR, otherwise tone mapped;
  logs a per-stage timing line beside the encoded fps line.

Cast Connect receiver (TV): `core/cast/AerioCastReceiverController.kt`, `AerioReceiverOptionsProvider.kt`; validates
the load against the TV's own playlist, never receives credentials. Full notes: `docs/cast-connect.md`.

Web receiver: `receiver.html` on this repo's `gh-pages` branch (edited and served live), Cast app id `46B79062`
(same id as Cast Connect).

AerioTV Remote companion: `core/cast/companion/` (discovery, protocol, host/remote controllers, service) and
`feature/cast/companion/CompanionPairingOverlay.kt`.

Playback: Media3 / ExoPlayer in `core/playback/` (`AerioExoPlayerHolder`, `AerioMediaPlaybackService`,
`LiveStreamFailover`, `ContinuousTsExtractor`, Dispatcharr 503 / connection-limit handling) and `feature/player/`.
Backends: `core/network/` (`DispatcharrClient`, `DispatcharrAuthBroker`, `XtreamCodesApi`, `PlaylistFetcher`,
`TMDBService` + `TmdbArtCache`); data in `core/data/` (repository, db, vod, capability).
Guide/EPG: `core/guide/` + `feature/livetv/` (grid under `feature/livetv/grid`); semantics shared with Apple, including
the TV guide focus model, in `docs/guide-semantics.md`. DVR: `feature/dvr/`. VOD: `feature/ondemand/`, `feature/movies/`.
Settings: `feature/settings/` (`SettingsScreen`, `SettingsRoute`, `LicensesScreen`, `DeveloperSettingsScreen`, ...).
TV helpers: `core/tv/`, remote key mapping `core/remote/`.

Other docs: `docs/roku-ecp-cast-plan-2026-09-27.md` (Roku, parked), `docs/airplay-sender-feasibility-2026-09-25.md`
(AirPlay sender: probe only, not committed), `docs/chromecast-web-receiver-hls-plan.md`, `docs/android-debugging.md`.

## 5. Standing rules (from the owner)
1. Never em dashes (U+2014), anywhere: code, comments, copy, commits.
2. No emojis in GitHub-facing content (commits, PRs, issues, release notes, README).
3. US spelling in user-facing copy: "program", never "programme" (XMLTV element names exempt).
4. User-facing copy says AerioTV. No third-party IPTV app names in copy.
5. No server-side (Dispatcharr) fixes or profile/setting changes as a fix; the app tolerates what the server sends.
6. Full cross-platform parity with the Apple app: identical menus, wording, cards, sheets, gestures. Platform-native
   controls only where the platform dictates. Every fix lands on all applicable platforms.
7. Hard data only: measure first, never assume, never blame the user or their setup.
8. Codec patent notice stays in README and the Licenses screen. Platform decoders/encoders only for Cast transcode.
9. Never publish or sideload anything on a Roku. Roku casting from Android is parked: no stock Roku path plays a URL
   (measured 2026-09-27).
10. Android Auto stays in the app but is never announced (README, notes, release copy).
11. Commit messages explain why, and end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
    Every commit is pushed.
12. Releases, version bumps, signing and store uploads only when the owner says. Play release notes body under
    about 420 characters.
