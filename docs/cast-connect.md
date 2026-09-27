# Cast Connect (native Android TV receiver)

When a phone (iPhone or Android) casts to a Google TV / Android TV device that
has AerioTV installed, Cast Connect launches the AerioTV Android TV app as the
receiver instead of the web receiver. The TV app tunes the channel itself with
ExoPlayer/Media3 and the device's hardware decoder (the route to 1080p60 and
4K50/60 on the Google TV Streamers; the web receiver's Chromium renderer tops
out near 46 fps). Devices without the app (older Chromecast dongles, Nest
displays) fall back to the web receiver automatically, and the sender keeps
its phone-local HLS proxy path for them.

Receiver app id: production `46B79062` (same id as the web receiver).
Android TV package: `com.aeriotv.android` (one package for phone and TV; the
receiver side only activates on leanback devices).

## Where it lives

Receiver (Android TV):

- `app/build.gradle.kts`: `play-services-cast-tv` dependency; the app id comes
  from `CAST_RECEIVER_APP_ID` in `local.properties` (or the env), exposed as
  `BuildConfig.CAST_RECEIVER_APP_ID`. Empty id = Cast disabled.
- `AndroidManifest.xml`: `com.google.android.gms.cast.tv.RECEIVER_OPTIONS_PROVIDER_CLASS_NAME`
  meta-data, and `MainActivity` intent filters for
  `com.google.android.gms.cast.tv.action.LAUNCH` and
  `com.google.android.gms.cast.tv.action.LOAD`.
- `core/cast/AerioReceiverOptionsProvider.kt`: `CastReceiverOptions` with the
  custom namespace `urn:x-cast:com.aeriotv.control`.
- `core/cast/AerioCastReceiverController.kt`:
  - `bootstrap()` (from `AerioTVApplication.onCreate`, leanback only):
    `CastReceiverContext.initInstance`, load callback, stop callback, custom
    message listener; `start()`/`stop()` follow `ProcessLifecycleOwner`.
  - `MainActivity` hands every launch/new intent to `handleIntent()` ->
    `MediaManager.onNewIntent`.
  - `LoadCallback.onLoad`: reads `customData.aerioMediaId` (falls back to
    `contentId`) and `customData.aerioKind`, validates the channel against the
    TV's OWN active playlist and effective base (`AutoBrowseTree.resolveForPlayback`),
    then emits a load request that `MainActivity` turns into the normal live
    channel route (same player, same overlays as a tune from the TV UI). A
    channel that does not resolve fails the load so the sender shows an error.
    VOD loads are rejected for now.
  - Media status: `AerioMediaPlaybackService` publishes its session token via
    `publishSessionToken()` -> `MediaManager.setSessionCompatToken`, so the
    sender card gets play/pause state and now-playing metadata.
  - STOP (sender card X): stops the holder, stops the media service, returns
    the TV to Live TV.
  - Custom namespace commands (same shapes as the web receiver, see
    `CastControl.kt`): `hello` -> `receiverInfo{platform: android-tv-app}`,
    `getState`, `setAudio`, `setText`, `setSpeed`, `setAspect`, `setChannel`
    (channel up/down and picker, re-tunes in place), `setAudioOnly`, `seekBy`,
    `seekWall`, `goLive`; replies with a `state` snapshot plus a ~1 Hz
    `position` tick.

Sender (phone, this repo):

- `core/cast/AerioCastOptionsProvider.kt`:
  `LaunchOptions.Builder().setAndroidReceiverCompatible(true)`; no
  `CredentialsData` (the TV validates the load against its own playlist, so the
  sender never ships credentials or a stream URL).
- `core/cast/AerioCastSender.kt`: the Cast SDK does not expose publicly whether
  the native app or the web receiver launched, so the sender sends `hello` on
  the custom namespace and waits for `receiverInfo`. The answer latches
  `ReceiverTarget.ANDROID_TV_APP` (native, no proxy) or `WEB_RECEIVER`
  (answer or timeout, phone HLS proxy). A tune that arrives before the answer
  is held, not guessed.

iOS sender (`AerioTV/App/AerioCastController.swift`, `start()`): already set:

```swift
let launchOptions = GCKLaunchOptions()
launchOptions.androidReceiverCompatible = true
options.launchOptions = launchOptions
```

It uses the same customData keys (`AerioCast.keyMediaID = "aerioMediaId"`,
`AerioCast.keyKind = "aerioKind"`) and the same control namespace.

## Log lines

- TV: `[Cast] android receiver: launched by <senderId> (<user agent>) load id=<mediaId> kind=<live|vod> -> channel <name>`
  (tag `CastReceiver`; `<not found>` when the channel does not resolve, in
  which case the load is failed back to the sender).
- Android sender: `[Cast] target=android-tv-app, native playback (receiver answered)`,
  or `[Cast] target=web-receiver (...)` for the fallback.

## Cast Developer Console (owner steps)

1. https://cast.google.com/publish > Applications > `46B79062` > Edit.
2. Under Android TV, add the package name `com.aeriotv.android` and turn on
   "Support for Cast Connect" (the wording in the console may differ slightly;
   it is the Android TV receiver section of the app). Save.
3. The app is already published, so the change applies to all devices once
   saved (allow a short propagation delay; reboot the TV if the first cast
   still opens the web receiver).
4. For a preview / unpublished receiver id (the Dev builds' preview receiver),
   repeat steps 1 and 2 on that id. Unpublished ids only launch on devices
   registered under Devices in the console, so register each test TV by
   serial number there and reboot it.
5. If `CAST_RECEIVER_APP_ID` or the iOS `AerioCast.receiverAppID` ever change,
   the new id needs the same Android TV package entry.

## Test steps

1. Build and install the same build on the phone and the Google TV device:
   `./gradlew :app:assembleGithubDebug` (flavors are `github` and `play`), with
   `CAST_RECEIVER_APP_ID=46B79062` in `local.properties`.
2. On the TV: open AerioTV once, sign in to the same playlist as the phone,
   then leave the app (home screen).
3. `adb logcat -G 32M` on both devices, then
   `adb logcat -s CastReceiver AerioCast`.
4. From the phone, cast a live channel to the TV. Expect: the TV launches
   AerioTV straight into the player on that channel; the TV logs the
   `android receiver: launched by ...` line; the phone logs
   `target=android-tv-app`.
5. On the phone card: pause/play, channel up/down, audio/subtitle picker,
   aspect, skip back/forward, Go Live; each should act on the TV and the card
   should reflect the state.
6. Tap X on the card: TV stops and returns to Live TV.
7. Repeat from an iPhone (1.8.x with the flag above).
8. Fallback: cast to a device without the app (or uninstall it): the web
   receiver should launch and the phone should log `target=web-receiver`.
9. Check Stream Info on the phone for 1080p60 / 2160p50-60 at the TV's native
   frame rate.
