# AerioTV for Android

AerioTV for Android is a native IPTV streaming app for Android phones, tablets, and Google TV / Android TV, built from a single Jetpack Compose codebase. It connects to Dispatcharr (admin login or API key), Xtream Codes, and M3U playlist servers to deliver live TV, movies, and series with a full electronic program guide (EPG) when supplied by the user.

It is the Android port of [AerioTV for Apple platforms](https://github.com/jonzey231/AerioTV).

**Google Play**
- AerioTV is in closed testing on Google Play (invite only).

**Sideload**
- Download the latest `.apk` from [Releases](https://github.com/jonzey231/AerioTV-Android/releases) and install it (enable install from unknown sources for your browser or file manager, or run `adb install`). The sideload build is occasionally ahead of the Play track.

## Screenshots

Screenshots are coming soon. The groups below are ready to fill in (drop images into `docs/screenshots/`).

<details>
<summary><strong>Phone</strong></summary>
<br>
<em>Coming soon.</em>
<!-- <img src="docs/screenshots/phone/01.png" width="200" /> -->
</details>

<details>
<summary><strong>Tablet</strong></summary>
<br>
<em>Coming soon.</em>
<!-- <img src="docs/screenshots/tablet/01.png" width="320" /> -->
</details>

<details>
<summary><strong>Android TV</strong></summary>
<br>
<em>Coming soon.</em>
<!-- <img src="docs/screenshots/androidtv/01.png" width="420" /> -->
</details>

## Features

A native Android IPTV client. Stream live TV with a full EPG, browse on-demand movies and series, schedule recordings to a Dispatcharr server or to your device, and watch up to 9 channels at once with Multiview, across phone, tablet, and Google TV / Android TV, with optional Google Drive sync stitching it together.

Click each section for the full list:

<details>
<summary><strong>Live TV & Guide</strong> (Media3 / ExoPlayer playback, list + grid views, EPG, reminders)</summary>

- Hardware-accelerated playback on Media3 / ExoPlayer (HLS, DASH, and progressive streams; hardware decode including HEVC HDR)
- List view plus a full EPG guide (grid on TV and tablet, a List / Guide toggle on phone)
- Program titles, descriptions, and time slots
- Channel info card identifies the channel and program when a stream starts
- Sort channels by number, name, or favorites; reorder favorites by drag
- Tap to play; manage favorites and per-group visibility from the channel menu
- Long-press an upcoming program to set a reminder or schedule a recording
- EPG reminders fire before the program starts, as an in-app banner and a notification
- EPG window configurable from 6 hours up to the full available window (Settings, Network)
- Guide zoom (pinch or a discrete scale selector) and a jump-to-now control
- Channels without guide data are still selectable from the grid
- Pull to refresh the channel list; per-playlist Refresh EPG

</details>

<details>
<summary><strong>Multiview</strong> up to 9 streams at once.</summary>

- Watch up to 9 live channels at once in a dynamically sized grid
- Enter from any playing channel, or stage channels from the guide and launch into Multiview
- Layouts adapt to tile count, from 1 up to a full 3x3
- Only the most recently added stream plays audio; tap a tile (or press Select on the remote) to move audio focus
- Per-tile menu: Make Audio, Full-Screen tile, Audio Track, Subtitle Track, Remove
- Drag to reorder tiles; double-tap a tile for fullscreen
- Resource guards: a thermal watchdog, a soft tile limit, and low-memory handling stop new tiles before the device is overwhelmed

</details>

<details>
<summary><strong>DVR Recording</strong> server-side on Dispatcharr, local on every server type.</summary>

- Schedule live or upcoming programs from the Live TV guide
- **Dispatcharr server-side recording** continues even when AerioTV is closed (the server runs the recording), ideal for unattended recordings
- **Local recording on this device**, available for Dispatcharr, Xtream Codes, and M3U
- Per-recording pre-roll (start early) and post-roll (end late) buffers: None / 5 / 10 / 15 / 30 / 60 min or custom
- **Remove Commercials (Comskip)** runs server-side on Dispatcharr; toggle it at schedule time or run it after recording
- DVR tab appears automatically when there is at least one recording, split into Scheduled / Recording / Completed
- Auto-discovery of recordings scheduled outside the app (Dispatcharr web UI)
- Play completed recordings in the app, and save server-side recordings to the device
- A Clear All action for completed recordings
- Keep-device-awake toggle so a local recording is not interrupted by display sleep

</details>

<details>
<summary><strong>Movies & TV Shows</strong> (Dispatcharr & Xtream Codes) VOD library, Continue Watching, TMDB-rich metadata.</summary>

- Browse and filter on-demand content by category
- TMDB-rich metadata on Dispatcharr (backdrop, plot, cast, director, year, rating, runtime; per-episode air dates, ratings, IMDB IDs, and per-episode artwork)
- Provider-supplied metadata on Xtream Codes (poster, title, one-line plot)
- Continue Watching resumes movies and episodes, and advances automatically to the next episode in a series
- Watch progress syncs across devices when Google Drive sync is on
- Per-playlist VOD toggle in Settings, Edit Playlist
- Full-library pagination walks past the first page on large libraries
- Movie and series detail caches make second-opens instant

</details>

<details>
<summary><strong>Player</strong> chrome, Stream Info, Sleep Timer, PiP, refresh-rate matching, channel-flip.</summary>

- Tap to summon chrome; it auto-fades after a few seconds of inactivity
- Overflow menu (phone) or Options pills (Android TV) for all secondary controls
- Audio track and subtitle track selection
- Playback speed (0.5x to 2x) for on-demand content; not available on live streams
- Sleep Timer with a countdown
- Audio Only mode
- Picture-in-Picture, including auto-PiP when you leave the app during playback
- Aspect-ratio control
- **Stream Info overlay** reads codec, resolution, frame rate, decoder (hardware or software), dropped frames, and audio details live from the player, so you see what the device is actually decoding rather than what the upstream server reports
- **Channel-flip** by swiping up or down (phone) or pressing D-pad up or down (Android TV) during single-stream live playback
- MediaStyle media notification with the channel logo for lock-screen and Bluetooth controls, plus background audio
- Display refresh-rate matching for smooth live playback (for example, 50fps content on a 60Hz panel)

</details>

<details>
<summary><strong>Google Drive Sync</strong> opt-in, set-up-once, no account required to use the app.</summary>

- Server configurations, preferences, VOD watch progress, EPG reminders, favorites and their order, hidden groups, and accent color sync through a private Google Drive AppData folder
- One-tap Google sign-in
- Opt-in: AerioTV is fully functional without a Google account
- Set up once on one device and your other devices pick up the same data on launch

</details>

<details>
<summary><strong>Android TV</strong> D-pad focus model, mini-player, LAN/WAN switching.</summary>

- Full D-pad navigation with a tvOS-style focus model (focus cards and focus scaling)
- Options pills for audio, subtitles, speed, sleep timer, and stream info
- D-pad up or down changes channels during single-stream live playback
- A mini-player keeps the current stream alive in a corner while you browse the guide or settings
- Channels and logos sized for living-room viewing
- LAN / WAN switching: save your home Wi-Fi and a per-playlist LAN URL, and AerioTV routes to the local address at home and the remote address away

</details>

<details>
<summary><strong>Phone, tablet & foldable</strong> mini-player, gestures, adaptive layouts.</summary>

- A mini-player keeps the stream alive while you browse the guide, settings, or on-demand library
- Picture-in-Picture and swipe-to-dismiss on the phone player
- A portrait List / Guide toggle on phones
- Runs across phones, tablets, and foldables (tested on the Galaxy Z Fold 5)

</details>

## Supported Server Types

AerioTV connects to three different playlist types. Each unlocks a different set of features. If you are choosing between them, the short version is below.

Click each type for the full breakdown:

<details>
<summary><strong>Dispatcharr Direct Connect</strong> <em>(Recommended)</em> admin username and password, or an API key. <em>Most full-featured option.</em></summary>

[Dispatcharr](https://github.com/Dispatcharr/Dispatcharr) is a self-hosted IPTV middleware that gives users control over their IPTV services. See the linked GitHub.

**What Dispatcharr is uniquely good at**

- **Server-side DVR**: schedule recordings that keep running even when AerioTV is closed or the device is asleep. (Local recording to the device works on every server type, but Dispatcharr is the only one where the server itself runs the recording.)
- **Comskip (commercial-skip)**: toggle it when scheduling a server-side recording, or run it after the fact from the DVR tab. Comskip runs on the Dispatcharr server, so it is only available for server-side recordings.
- **Server-side recording playback**: stream completed server recordings directly, no auth headers needed.
- **Server-side stream failover via channel UUID**: when a primary stream dies mid-playback, Dispatcharr swaps to a backup provider transparently.
- **TMDB-enriched VOD metadata**: backdrops, plots, cast, director, year, rating, runtime, plus per-episode air dates, TMDB ratings, IMDB IDs, and per-episode artwork, pulled in by Dispatcharr's TMDB scraper. (Xtream Codes also has VOD, but its metadata is whatever the provider supplies, usually poster, title, and a one-line plot.)
- **Bulk EPG fetch**: one network call returns the grid for every channel via `/api/epg/grid/`. Xtream Codes uses per-stream EPG calls; M3U pulls XMLTV from a separate URL.
- **Per-server custom User-Agent override**: set it in Settings, Edit Playlist. It is sent on Dispatcharr API requests and on playback, so the value shows up in Dispatcharr's admin Stats panel. Not currently exposed for Xtream Codes or M3U.
- **External XMLTV URL override** <em>(advanced)</em>: point the EPG at a third-party XMLTV source while keeping channels from Dispatcharr's API.
- **Auto-discovery of recordings scheduled outside the app**: recordings you scheduled from the Dispatcharr web UI appear in AerioTV's DVR tab within a couple of minutes. Xtream Codes and M3U have no server-side scheduler to discover from.

</details>

<details>
<summary><strong>Xtream Codes</strong> source URL plus username and password. <em>No self-hosting required.</em></summary>

The Xtream Codes API is what most IPTV providers natively expose. You log in with the username and password your provider gave you, and AerioTV pulls live TV, movies, and series.

**Benefits**

- **No self-hosting.** Most IPTV providers expose an Xtream Codes endpoint by default. Paste the URL, type your credentials, done.
- **Live TV plus a VOD library.** Movies and series come from the same login as live channels, with no separate EPG or VOD URLs to manage.
- **Per-playlist VOD toggle.** Settings, Edit Playlist exposes the same "fetch VOD from this playlist" switch Dispatcharr has, so you can keep a secondary playlist's Live TV without loading its VOD library every launch.
- **Local DVR with pre-roll and post-roll.** Recordings on this device, with the same buffer pickers (None / 5 / 10 / 15 / 30 / 60 min or custom) Dispatcharr server-side recordings get. Recordings continue while the app stays running.
- **EPG via TVG ID matching.** Most provider EPGs key to Xtream's `tvg_id` field automatically. You can also point an external XMLTV URL at it.
- **Familiar auth model.** A single username and password.

**Drawbacks**

- **VOD metadata is provider-supplied, not TMDB-enriched.** You typically get the poster, title, and a one-line plot. Per-episode air dates, TMDB ratings, and per-episode artwork are not available.
- **No server-side DVR.** Recordings are local only, so AerioTV has to keep running for the recording duration. No comskip (Dispatcharr only).
- **EPG depth varies by provider.** Some give you a few days, some give you a few hours.

</details>

<details>
<summary><strong>M3U Playlist</strong> direct URL plus an optional XMLTV EPG. <em>Universal compatibility, simplest setup.</em></summary>

A plain M3U playlist is just a list of stream URLs in a text file. It works with any IPTV provider that gives you a playlist URL, including providers that do not support the Xtream Codes API.

**Benefits**

- **Universal compatibility.** If a provider hands you any URL ending in `.m3u` or `.m3u8`, AerioTV can play it.
- **Fastest setup.** Paste the URL and you are done. No credentials, no server, no scrape time.
- **No self-hosting.**
- **Local DVR with pre-roll and post-roll.** Recordings on this device, with the same buffer pickers Xtream Codes and Dispatcharr recordings get. Recordings continue while the app stays running.
- **Optional separate XMLTV URL** for EPG. Bring your own EPG source if your provider does not include one.

**Drawbacks**

- **No VOD library.** M3U is live TV only, no movies or series. If your provider has VOD, you will need their Xtream Codes endpoint (or a Dispatcharr instance pointed at them) to access it.
- **No server-side DVR.** Recordings are local only, so AerioTV has to be running for the recording to capture. No comskip (Dispatcharr only).
- **EPG depends on a separate XMLTV source.** You set the URL yourself, and EPG quality is whatever that source provides.
- **No stream failover.** When a stream URL stops working, the provider has to fix the playlist. There is no server-side swap like Dispatcharr has.
- **Large M3U files can be slow to parse** on first launch. A playlist with tens of thousands of channels takes a noticeable beat to ingest.

</details>

## Tech Stack

- Kotlin 2.3 + Jetpack Compose (Material 3)
- Compose for TV (`androidx.tv`) for Google TV / Android TV form factors
- Media3 / ExoPlayer for playback (HLS, DASH, progressive; hardware decode including HEVC HDR)
- Room (relational data) + DataStore (preferences)
- Ktor + kotlinx.serialization (Dispatcharr, Xtream Codes, M3U, XMLTV)
- Hilt (DI), Coroutines + Flow (async), Navigation Compose (routing)
- Coil (image loading), Google Drive AppData (optional settings and progress sync)

## Requirements for Development

- Android Studio (latest stable)
- JDK 17 or newer (the JDK bundled with Android Studio works)
- Android SDK 36 (compile and target), minSdk 26 (Android 8.0)

## Getting Started

Clone the repository and build the debug app:

```
git clone https://github.com/jonzey231/AerioTV-Android.git
cd AerioTV-Android
./gradlew :app:assembleDebug
```

Or open the project root in Android Studio and run the `app` configuration on a device or emulator.

Optional: Google Drive sync needs your own Google Cloud OAuth Web Client ID in `local.properties` as `GOOGLE_DRIVE_WEB_CLIENT_ID`. The app is fully functional without it; only Drive sync is disabled.

## Project Structure

```
app/src/main/java/com/aeriotv/android/
    MainActivity.kt, Navigation.kt, AerioTVApplication.kt
    core/
        network/        Ktor clients (Dispatcharr, Xtream Codes, M3U)
        parser/         M3U + XMLTV parsing
        playback/       ExoPlayer holder, tuning, refresh-rate matching
        data/           Room database + DAOs
        preferences/    DataStore settings
        sync/           Google Drive AppData sync
        pip/            Picture-in-Picture
        wifi/           LAN / WAN home-network detection
        security/       log sanitization, safe image URLs
        category/  debug/  di/  system/  tv/
    feature/
        livetv/  player/  multiview/  dvr/  ondemand/  channels/
        onboarding/  settings/  miniplayer/  splash/  favorites/
        reminders/  watchprogress/  whatsnew/  playlist/  main/
    ui/
        adaptive/  scale/  theme/  tv/  textfield/
```

## Configuration

- On first launch the app presents an onboarding flow where you add your server
- Server configurations can be carried to another device with Google Drive sync
- EPG data is cached locally (Room) for instant now-playing on relaunch
- The EPG window is configurable from 6 hours to the full window in Settings, Network
- A "Setting Up" screen shows during a slow first sync

## Sideloading

- Download the latest `.apk` from the [Releases](https://github.com/jonzey231/AerioTV-Android/releases) page
- Install it through your file manager (enable install from unknown sources) or with `adb install path/to/AerioTV.apk`
- Works on phones, tablets, and Google TV / Android TV

## Building for Release

- App bundle for Google Play: `./gradlew :app:bundleRelease` (requires a `keystore.properties` with your signing config; an absent keystore produces an unsigned release)
- Standalone APK: `./gradlew :app:assembleRelease`

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

## Support

To report bugs or request features, open an issue at [github.com/jonzey231/AerioTV-Android/issues](https://github.com/jonzey231/AerioTV-Android/issues).
