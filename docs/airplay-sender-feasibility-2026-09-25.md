# AirPlay video from AerioTV on Android: feasibility (2026-09-25)

## Bottom line

Technically possible, not recommended to commit to yet. Build a narrow probe first.

- The media side already exists: the Android cast proxy (`core/cast/hlsproxy/`) serves a LAN HLS playlist of fMP4 segments that an AirPlay receiver can fetch exactly as a Chromecast does.
- The missing part is the AirPlay control plane, and no maintained Java/Kotlin/NDK AirPlay *sender* exists. The only complete open-source sender that plays URLs on current Apple TVs is pyatv (Python, MIT), which is a protocol reference to port, not a dependency to ship.
- Apple TV (tvOS 15+) requires HomeKit-style pairing: SRP-6a pair-setup, Ed25519/X25519 pair-verify, then a ChaCha20-Poly1305 encrypted channel before any `/play`. The old unauthenticated `POST /play` with `Content-Location` is not a reliable path on a current Apple TV 4K. pyatv's own history shows the fragility (`play_url` broke and was restored in 0.13.3; issue #2912 shows `/play` returning 453 after a successful pairing).
- Roku's AirPlay receiver is an Apple-certified AirPlay 2 receiver and most likely needs the same pairing; its "Require code" setting only controls whether a PIN is shown. No source confirms Roku accepts unauthenticated legacy `/play`. Must be tested on a real Roku before assuming a "Roku-only is easy" shortcut.
- For Roku specifically, Roku ECP and DIAL are documented, need no pairing, and are far less brittle. If the real goal is Android-to-Roku, that path is cheaper and safer than AirPlay.

## Recommendation

1. Do not commit to shipping AirPlay-from-Android yet.
2. Spend 1-2 days probing with a desktop pyatv against the Apple TV (tvOS 26) and the Roku, pointing `play_url` at a URL the Android proxy serves.
3. If both play, port the needed pieces to Kotlin (plan below).
4. If Roku is the priority, evaluate Roku ECP/DIAL instead.

## Findings

**Senders available.** pyatv (Python) is the only complete one. All Android AirPlay projects (UxPlay-based, ExoPlayer AirPlay Receiver, android-airplay-server forks) are receivers; useful only for reading the server side of `/play`, `/scrub`, `/rate`, `/playback-info`. AirPlay-1-era Java senders lack HAP pairing and fail against tvOS 15+. FairPlay-SAP is not needed for URL playback.

**Auth on Apple TV.** Request format is unchanged (`/play` with `Content-Location` and `Start-Position`, then `/scrub`, `/rate`, `/stop`, poll `/playback-info`) but must run over a HAP-verified encrypted connection. "Allow Access: Everyone / Same Network" plus the required-code setting decides whether a PIN appears; "Only People Sharing This Home" will likely block a non-Apple sender entirely. Device auth has been enforced since tvOS 10.2.

**Licensing.** Apple licenses no software AirPlay senders (MFi is hardware receivers only), so any Android sender is reverse-engineered protocol. Commercial Android apps have shipped this for years without widely reported action; not a guarantee. pyatv is MIT, so porting into the GPL-3.0 app is fine with attribution. Avoid trademark wording implying Apple endorsement ("Send to Apple TV (AirPlay-compatible)"). Google Play has no specific rule against it. Apple can break it at any time.

**Reuse of the Android proxy.** `CastHlsProxySession.startChannel` returns a LAN URL; `CastHlsProxyServer` serves the demuxed master, video/audio playlists, init and media segments; `CastHlsProxyService` is the foreground service. Audio differs from iOS: since 2026-09-12 Android does no audio transcoding (AAC via the server's "Web Player (AAC Audio)" profile, AC-3/E-AC-3 passthrough). Fine for Apple TV; for Roku request the AAC profile as for Chromecast. Apple's player is stricter about HLS than Chromecast's (target duration, CODECS, sliding window); playlist output should be checked against the iOS remuxer's, which is known to work on Apple receivers.

## Effort (engineer-days)

| Work | Estimate |
|---|---|
| Probe with pyatv against both devices | 1-2 |
| Roku-only, if Roku accepts unauthenticated `/play` | 4-6 |
| Roku-only, if Roku also needs HAP pairing | same as Apple TV |
| Apple TV: mDNS discovery, SRP/HAP pair-setup and verify, encrypted HTTP channel, play/scrub/rate/stop/playback-info, PIN UI, stored credentials, UI integration | 12-20 |
| Testing and HLS compatibility fixes | 5 |

Maintenance risk: high. Expect days of fixes after some major tvOS releases, with no warning and nothing official to debug against.

## Implementation plan (only if the probe passes)

1. Discovery: `core/cast/airplay/AirPlayDiscovery.kt` using `NsdManager` for `_airplay._tcp`, parsing TXT (features, pk, model); list devices alongside existing ones in `NativeCastDevices.kt`, `CastControl.kt`, and the cast picker.
2. Crypto: `core/cast/airplay/HapPairing.kt`: SRP-6a (3072-bit group), HKDF-SHA512, Ed25519/X25519, ChaCha20-Poly1305 via BouncyCastle or Tink; port pyatv `auth/hap_srp.py` and `auth/hap_pairing.py`.
3. Credentials: `AirPlayCredentialStore.kt` (Android Keystore encrypted) plus a PIN dialog.
4. Encrypted channel: `HapSession.kt` wrapping an HTTP/1.1 socket in HAP frame encryption.
5. Control client: `AirPlayVideoClient.kt`: `/play` (binary plist with `Content-Location` = proxy master playlist URL), `/rate`, `/scrub`, `/stop`, `playback-info` poller, keep-alive.
6. Sender: `AirPlaySender.kt` shaped like `AerioCastSender.kt`, reusing `CastHlsProxySession.startChannel`, `CastHlsProxyService.start/stop`, `CastNotificationController.kt`, and implementing `CastControl` so the existing remote UI works unchanged.
7. HLS compatibility in `CastHlsProxyServer.kt`: match the iOS remuxer's tags and CODECS; validate `TsToFmp4Remuxer.kt` output with Apple's HLS tools.
8. Test matrix: Apple TV 4K on tvOS 26 in every Allow Access mode; Roku in each "Require code" mode.

## Sources

- https://pyatv.dev/documentation/supported_features/
- https://pyatv.dev/documentation/protocols/
- https://pyatv.dev/development/scan_pair_and_connect/
- https://github.com/postlund/pyatv/issues/2912
- https://github.com/warren-bank/Android-ExoPlayer-AirPlay-Receiver
- https://github.com/jqssun/android-airplay-server
- https://castbrowser.tv/guides/cast-to-roku
