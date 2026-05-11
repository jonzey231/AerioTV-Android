# AerioTV for Android

Android port of [AerioTV](https://github.com/jonzey231/AerioTV) (iOS/tvOS IPTV app). Phone, tablet, and Google TV / Android TV from a single Compose codebase.

## Stack

- Kotlin 2.1 + Jetpack Compose
- Material 3 (Expressive when stable)
- Compose for TV (`androidx.tv:tv-material`) for TV form factors
- libmpv via `mpv-android` for playback
- Room (relational data) + DataStore (preferences)
- Ktor + kotlinx.serialization (Dispatcharr, XC, M3U, XMLTV)
- Hilt (DI), Coroutines + Flow (async), Navigation Compose (routing)
- Google Play Block Store (credentials) + Drive AppData (settings/progress sync) for iCloud parity

## Requirements

- Android Studio Ladybug (2024.2.1) or newer
- JDK 17
- Android SDK 35 (compile/target), minSdk 26 (Android 8.0)

## Building

Open the project root in Android Studio. First sync will download the Gradle wrapper distribution. Run the `app` configuration on a device or emulator.

From the command line (after first Studio sync has populated `gradle/wrapper/gradle-wrapper.jar`):

```
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## Module structure (planned)

The current scaffold is a single `:app` module. As features land, code will move into:

```
app/                    Compose UI, navigation, DI wiring
core/
  player/               mpv-android wrapper, MPV tuning (HEVC HDR, live probe)
  data/                 Room + DataStore + Drive/Block Store sync
  network/              Ktor clients (Dispatcharr, XC, M3U/XMLTV)
  parsers/              M3U, XMLTV (incl. .xml.gz streaming gunzip)
  epg/                  EPG matching, fuzzy match
feature/
  home/  guide/  player/  multiview/  dvr/  settings/  onboarding/
tv/                     TV-only entry points, D-pad focus tuning
```

## Sync model (iCloud parity)

| Data                                            | Backend                     |
|-------------------------------------------------|-----------------------------|
| Dispatcharr/XC credentials, JWT refresh tokens  | Google Play Block Store     |
| Watch progress, EPG overrides, preferences      | Google Drive AppData folder |
| Channels, recordings, EPG cache                 | Local Room DB               |

Sync is opt-in. App is fully functional without a Google account.

## Status

v0.1.0 scaffold. No features yet.
