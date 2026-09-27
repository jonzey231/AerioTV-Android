# Roku ECP casting from AerioTV Android: research and plan (2026-09-27)

Status: research only, nothing coded. Device: Roku Streaming Stick 3840R, 192.168.50.69, developer mode on.
Every claim below is tagged SOURCE (documented), KNOWN (widely used by third-party projects, not in Roku docs) or PROBE (must be measured on the stick before coding).

## Bottom line

1. ECP is plain HTTP on port 8060, no pairing, discovered by SSDP `ST: roku:ecp`. SOURCE.
2. Stock-player paths (Play on Roku 15985, Roku Media Player 2213) are undocumented for URL playback, give no hold-back control, no channel-change without relaunch, and poor stats. Good for a one-day probe, not for a shipping feature.
3. The reliable path is our own small Roku channel (SceneGraph Video node, HLS), launched with `POST /launch/<id>?contentId=...&mediaType=live` and fed follow-up commands with `POST /input?...`. Both are documented deep-link paths and are the ones Roku intends mobile apps to use.
4. Normal users can only get that channel from the Roku Channel Store (or a private/non-certified channel link, see item 2). Sideload (`dev`) is only for us.
5. The proxy should serve the Roku a muxed MPEG-TS HLS playlist with H.264 + AAC-LC stereo, keep-alive on, the same shape the Apple side already proved works on this stick over AirPlay.

## 1. Launch paths with no AerioTV channel installed

| Path | Request | Status |
|---|---|---|
| Play on Roku (channel 15985) | `POST http://ROKU:8060/input/15985?t=v&u=<urlencoded m3u8>&videoName=AerioTV&videoFormat=hls` | KNOWN (used by Roku Media Player casting scripts, Home Assistant/roku-cli style tools). Not in Roku docs. PROBE |
| Roku Media Player (2213) | `POST http://ROKU:8060/launch/2213?contentId=<url>&mediaType=movie` or `?u=<url>&t=v` | KNOWN only anecdotally (Roku community "ECP Control of Media Player?" thread has no official answer). PROBE |
| Generic deep link | `POST /launch/<channelId>?contentId=<id>&mediaType=<movie|episode|live|...>` | SOURCE, but the target channel decides what `contentId` means; 2213 is not documented to accept a URL |

Codec and container limits (SOURCE: Roku "Audio and video support" and "Streaming specifications" pages):
1. HLS with MPEG-TS segments, H.264 video, AAC-LC audio: supported on all current devices.
2. HLS with fMP4 (CMAF) segments: supported by the Roku player in channels. Demuxed audio renditions are supported in channels via `EXT-X-MEDIA`, but the stick rejected our demuxed master over AirPlay on 2026-09-26, so do not assume it; PROBE through our own channel only.
3. HEVC and 4K HDR10/Dolby Vision: the 3840R is the 4K Streaming Stick (HEVC Main10, HDR10, HDR10+, Dolby Vision per spec sheet). `query/device-info` fields `supports-...` do not list codecs; read them from our channel with `roDeviceInfo.CanDecodeVideo({Codec:"hevc", Profile:"main10", ...})`.
4. AC-3/E-AC-3: decoded or passed through by the Roku player (SOURCE). Observed decoded over AirPlay 9/26. For stock Play on Roku, unknown; PROBE.
5. Live HLS (no EXT-X-ENDLIST): works in channels. Stock players: KNOWN to treat many live URLs as unplayable or to stop at the window end; PROBE.
6. Roku OS 14/15: no Roku statement that 15985 URL play was removed; community reports are mixed. The only honest answer is PROBE on this stick.

## 2. Own AerioTV Roku channel (recommended)

Why needed:
1. Hold-back control: `Video.content.live = true` plus our own playlist pacing; the stock players do neither.
2. Channel change without relaunch: `POST /input?contentId=<new url or token>&mediaType=live` delivers a `roInputEvent` to the running channel (SOURCE: Roku "Deep linking" and "roInput" docs). With stock players every change is a full relaunch.
3. Underrun behavior: the stick quit on underrun over AirPlay. In a channel we observe `Video.state = "buffering"` and can retry instead of exiting.
4. Stats to the phone: the channel can report state by HTTP `POST` back to the phone proxy (`roUrlTransfer` to `http://<phone>:<port>/roku/state`), carrying `position`, `bufferingStatus`, `streamingSegment`, `videoFormat`, `audioFormat`, errors. ECP `query/media-player` also works for our channel (SOURCE) as a fallback.

How the URL arrives:
1. Sideload (us): `POST /launch/dev?contentId=<urlencoded proxy master>&mediaType=live`. `dev` is the sideloaded channel id (SOURCE).
2. Store build: `POST /launch/<storeId>?contentId=...&mediaType=live`. If not installed: `POST /install/<storeId>` opens the store page (SOURCE).
3. Running: `POST /input?contentId=...&mediaType=live` (channel change), plus custom keys such as `cmd=stop`.
4. `contentId` is a string; pass a short session token and let the channel fetch `http://<phone>/roku/session/<token>.json` to avoid URL length and encoding issues.

Channel Store (SOURCE: Roku "Channel certification" / publishing guidelines, brief):
1. Developer account (free), must pass certification: deep linking with `contentId`+`mediaType` required, launch time targets, Back must exit, no crashes, memory limits, 4K/HDR declared correctly.
2. "Channel that just plays user-supplied URLs" is a gray area in store policy; the listing should present it as the AerioTV companion receiver that requires the AerioTV phone app. Review can reject.
3. Beta channels (private access code, up to 20 testers, expire after 120 days) are fine for testing but not for users.
4. Non-certified private channels were retired by Roku in 2022; there is no longer an unlisted public path. So store publishing is the only way for normal users.

## 3. Discovery and state

Discovery (SOURCE):
1. SSDP M-SEARCH to `239.255.255.250:1900`, `ST: roku:ecp`, `MX: 3`. Reply has `LOCATION: http://<ip>:8060/` and `USN: uuid:roku:ecp:<serial>`.
2. Roku also answers DIAL (`ST: urn:dial-multiscreen-org:service:dial:1`), app URL `http://<ip>:8060/dial/`. DIAL adds nothing over ECP for us; skip it.
3. Android: plain `MulticastSocket` with a `WifiManager.MulticastLock` held during the scan. NsdManager is mDNS only and Roku does not advertise ECP over mDNS (it advertises `_airplay._tcp` for AirPlay only), so NsdManager is not an alternative. Manual IP entry as a fallback, same as other transports.
4. Validate each hit with `GET /query/device-info` (friendly name, model 3840R, `supports-...`, `developer-enabled`, `ecp-setting-mode` on newer OS). PROBE which fields this OS returns.

State for the cast card:
1. `GET /query/active-app`: which channel is foreground; if it is no longer ours, the user left on the Roku side.
2. `GET /query/media-player`: `state` (`play`, `pause`, `buffer`, `close`, `none`), `position`, `duration`, `is_live`, `buffering current/max`, `stream_segment` (bitrate, media sequence), `format audio/video`. SOURCE. Poll every 1 s while the card is shown.
3. Our channel's push reports (item 2) are the primary source once we have a channel.

## 4. Control

1. Play/Pause: `POST /keypress/Play` (toggle). SOURCE.
2. Seek: `POST /keypress/Fwd`, `/keypress/Rev` are coarse; for Back 5 s / Forward 60 s parity use `POST /input?cmd=seek&delta=-5000` to our channel.
3. Stop: `POST /input?cmd=stop` to our channel (clean), else `POST /keypress/Back` or `/keypress/Home`. `POST /exit-app/<id>` needs developer mode (SOURCE, 14.1 table) so do not rely on it.
4. User stopped on the Roku: `query/active-app` changes away from our id, or `query/media-player` state becomes `close`/`none`, or our channel posts `stopped`. End the phone session on any of these after 2 consecutive polls.

## 5. Permissions ("Control by mobile apps", Roku OS 14.1+)

SOURCE (ECP page): Enabled/Default allows keypress, keydown, keyup, launch, input, install, query/device-info, query/apps, query/active-app, query/media-player, query/icon. Developer mode is additionally required for query/chanperf, query/sgnodes, query/registry, exit-app, fwbeacons. The Roku docs also describe Limited and Permissive modes on newer OS where Limited restricts some commands to same-subnet senders and Permissive lifts network restrictions. Nothing in the plan needs Permissive. PROBE: run probe 1 with the setting at Default and confirm every command we use returns 200, not 403.

## 6. Implementation plan (Android)

Shape, mirroring `core/cast/AerioCastSender.kt` and `core/cast/CastControl.kt`:
1. `core/cast/roku/RokuDiscovery.kt`: SSDP scan + device-info validation, exposes `StateFlow<List<RokuDevice>>`, merged into the existing device picker next to Cast.
2. `core/cast/roku/RokuEcpClient.kt`: OkHttp wrapper for launch, input, keypress, install, query endpoints; XML parse of media-player and active-app.
3. `core/cast/roku/RokuSender.kt`: same public surface as AerioCastSender (`setContent`, `tuneLiveChannel`, `play`, `pause`, `togglePlayPause`, `skipBy`, `stopCasting`, remote state flow) so the card and sheet stay identical (parity rule).
4. Protocol: reuse `CastControl` command names, encoded as `/input` query params on the way out and as the channel's JSON POST on the way back, so `CastControl.decodeState` can be shared.
5. Proxy: add a `rokuMuxedTs` mode to `CastHlsProxySession`/`CastHlsProxyServer`: single media playlist, MPEG-TS segments passed through (no fMP4 remux), AAC-LC stereo from the server "Web Player (AAC Audio)" profile, keep-alive, playlist pacing and hold-back as the Apple muxed path. Plus `/roku/session/<token>.json` and `/roku/state` endpoints.
6. Roku channel: new repo `AerioTV-Roku` (BrightScript + SceneGraph): MainScene with Video node, roInput handler, state reporter, minimal "Waiting for AerioTV" screen. GPL-3.0 like the others.

Effort (engineer-days):
| Work | Days |
|---|---|
| Probes below | 0.5 |
| Discovery + ECP client + sender + card/sheet wiring | 4 to 5 |
| Proxy muxed TS mode + session/state endpoints | 2 to 3 |
| Roku channel (sideloaded, device tested) | 4 to 6 |
| Store submission, certification fixes | 3 to 5 plus Roku review time |
| Total | about 14 to 20 |

Risks:
1. Store rejection for a URL-player channel; mitigate by requiring pairing with the phone app and clear listing copy.
2. Stock-player fallback may not exist on OS 15; do not promise it.
3. Underrun exits: must be handled in channel code, and the proxy must never let the live window run dry.
4. Multicast blocked on some Wi-Fi/VLAN setups; manual IP entry needed.
5. iOS parity: the same channel works from iOS with the same ECP client; plan it for Apple too (cross-platform rule).

## First three probes (run from the Mac on the same LAN)

1. Reachability and permissions at the Default setting:
```
curl -s http://192.168.50.69:8060/query/device-info
curl -s http://192.168.50.69:8060/query/active-app
curl -s http://192.168.50.69:8060/query/media-player
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://192.168.50.69:8060/keypress/Home
printf 'M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nST: roku:ecp\r\nMX: 3\r\n\r\n' | socat - UDP4-DATAGRAM:239.255.255.250:1900,broadcast
```
2. Stock-player URL play of a known-good muxed TS HLS (the Apple proxy's AirPlay URL, or a public test stream), recording what `query/media-player` reports for 60 s:
```
U=$(python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=""))' "http://<phone-ip>:<port>/<muxed>.m3u8")
curl -s -X POST "http://192.168.50.69:8060/input/15985?t=v&u=$U&videoName=AerioTV&videoFormat=hls"
curl -s -X POST "http://192.168.50.69:8060/launch/2213?contentId=$U&mediaType=movie"
for i in $(seq 60); do curl -s http://192.168.50.69:8060/query/media-player; sleep 1; done
```
3. Sideload a 40-line test channel (Video node, reads `contentId` from launch args and roInput) via the dev installer at `http://192.168.50.69` (port 80, digest auth rokudev), then:
```
curl -s -X POST "http://192.168.50.69:8060/launch/dev?contentId=$U&mediaType=live"
curl -s -X POST "http://192.168.50.69:8060/input?contentId=$U2&mediaType=live"
curl -s http://192.168.50.69:8060/query/media-player
```
Repeat 3 with the demuxed fMP4 master and with an AC-3 stream to settle item 1 limits.

## Sources

1. Roku External Control Protocol: https://developer.roku.com/docs/developer-program/dev-tools/external-control-api.md
2. Roku deep linking: https://developer.roku.com/docs/developer-program/discovery/implementing-deep-linking.md
3. Roku audio and video support: https://developer.roku.com/docs/specs/media/streaming-specifications.md
4. Roku community, ECP control of Media Player: https://community.roku.com/t5/Channel-Issues-Questions/ECP-Control-of-Media-Player/td-p/656082
5. Roku::ECP (CPAN): https://metacpan.org/pod/Roku::ECP
6. Legacy ECP guide mirror: https://github.com/tispratik/docs-1/blob/master/develop/guides/remote-api-ecp.md
7. Prior internal note: docs/airplay-sender-feasibility-2026-09-25.md
