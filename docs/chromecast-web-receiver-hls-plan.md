# Chromecast: legacy-device (web receiver + HLS) support plan

Status: DESIGN + SCAFFOLD IN PLACE. Blocked on one external dependency (Dispatcharr
emitting HLS). This document is the activation guide for when that lands.

Related: GH #33. The Cast Connect (Android TV receiver) path for raw MPEG-TS is
already built and covers Chromecast-with-Google-TV / Android TV targets. This
document covers the OTHER cast targets that can't run our app.

## TL;DR

- Legacy Chromecast dongles (1st/2nd/3rd gen, Ultra, Audio) and Nest Hub / smart
  displays are NOT Android TV, so Cast Connect can't launch our app on them. They
  only run the Cast web receiver, which cannot play raw MPEG-TS.
- The web receiver CAN play HLS. So supporting these devices needs exactly two
  things: (a) Dispatcharr producing an HLS URL for a channel, and (b) the app
  putting that URL in the cast load. That's it.
- We do NOT need to build or host a custom web receiver. The Styled Media
  Receiver we already registered (App ID `CFFD302F`) plays HLS out of the box.
- One cast load payload serves BOTH receiver types with no device detection:
  channel identity in `customData` (Android TV receiver rebuilds its own raw-TS
  URL) plus an HLS `contentUrl` (web receiver plays that). The Android TV receiver
  ignores `contentUrl`; the web receiver ignores `customData`.

## The only blocker: Dispatcharr HLS output

None of this works until Dispatcharr can hand out an HLS playlist for a live
channel. Concretely we need a per-channel URL of the form:

```
{base}/proxy/hls/stream/{channelUuid}.m3u8   (or whatever shape Dispatcharr ships)
```

Requirements for that HLS to actually play on Cast web receivers:

1. Container/codec: H.264 video + AAC audio is the universally safe baseline that
   EVERY Cast device (including the oldest dongles) can play. Anything else is
   device-dependent (see the matrix below). If Dispatcharr can only remux (not
   transcode), the source codec passes through and compatibility varies; if it can
   transcode to H.264/AAC, we get maximum reach.
2. Segment format: standard MPEG-TS segments or fMP4/CMAF are both fine for the
   Cast receiver. If it matters we can hint via `MediaInfo.setHlsSegmentFormat` /
   `setHlsVideoSegmentFormat`.
3. Reachability from the cast device: the receiver fetches the URL itself, so the
   HLS host must be reachable from the Chromecast's network position (same LAN as
   our live proxy is fine). This is the same LAN/WAN concern as everywhere else.
4. TLS / mixed content: the Cast web receiver is a Chromium page. It generally
   requires HTTPS for media URLs, with a documented exception for private/local
   IP addresses. If Dispatcharr serves HLS over plain HTTP on a LAN IP, it usually
   works; over a public hostname it will need HTTPS. Verify per deployment.
5. CORS: the receiver may issue a CORS-preflighted request for the playlist and
   segments; the HLS host should return permissive CORS headers
   (`Access-Control-Allow-Origin: *` or the receiver origin).

## Codec / device support matrix (web receiver, HLS)

| Codec in HLS | Legacy dongle (1/2/3 gen) | Chromecast Ultra | Nest Hub | Notes |
|---|---|---|---|---|
| H.264 + AAC | Yes | Yes | Yes | The safe baseline. Target this. |
| H.264 + AC-3/E-AC-3 | No | Only w/ HDMI passthrough | No | The web receiver has no ffmpeg AC-3 fallback (unlike our ExoPlayer). Dolby-audio channels degrade or fail. |
| HEVC + AAC | No | 4K models only | No | HEVC in HLS is gated to capable hardware; not the dongles. |

Takeaway: for the web-receiver path, target H.264 + AAC. Channels that are
HEVC or AC-3-only will not play on legacy devices via HLS. That is a hard limit of
the web receiver, not of our app, and is exactly why Cast Connect (our own
ExoPlayer + ffmpeg) is the better path wherever the target is an Android TV.

## What's already scaffolded in the app

`AerioCastSender.Content` has an optional `hlsUrl: String?` (default null). When
null (today), the cast load carries only the channel identity (Cast Connect only).
When set, `loadOnSession` populates `MediaInfo.setContentUrl(hlsUrl)` +
`setContentType("application/x-mpegURL")`, so the web receiver can play it, while
the Android TV receiver still ignores it and rebuilds its own raw-TS URL from
`customData`. No behavior change until a non-null `hlsUrl` is supplied.

## Activation checklist (when Dispatcharr HLS ships)

1. Add an HLS URL builder next to the existing raw-TS builder, e.g.
   `DispatcharrClient.hlsUrl(base, channelUuid)` returning the `.m3u8` URL, plus a
   per-server/per-playlist capability flag (does this server expose HLS?). Detect
   it once at sync time (probe the endpoint or read a Dispatcharr capability field)
   and persist it on the playlist entity, mirroring how other server capabilities
   are stored.
2. In the live cast path (`PlayerScreen`, where it builds `AerioCastSender.Content`
   for the current channel), pass `hlsUrl = if (server.supportsHls) dispatcharrClient.hlsUrl(base, uuid) else null`.
   Rebuild the URL against the same effective base used for the raw-TS URL.
3. VOD: VOD is already an absolute file URL, so the VOD cast path can set `hlsUrl`
   (or a direct MP4 `contentUrl`) unconditionally once VOD casting is built; VOD
   plays on the web receiver today if the URL is H.264/AAC MP4 or HLS.
4. Nothing else changes: the receiver side, the session swap, the button, and
   discovery are all shared and already done.

## Testing plan

- Legacy Chromecast dongle (borrow/emulate) + Nest Hub as targets: cast an H.264
  channel, confirm the Styled receiver plays the HLS.
- Confirm an Android TV target STILL uses Cast Connect (raw TS) with the same
  build, i.e. the added `contentUrl` did not regress the ATV path (it should be
  ignored there).
- Confirm an AC-3 / HEVC channel degrades gracefully on the dongle (clear error or
  audio-only), and document the limitation in the UI if worthwhile.

## Security notes

- If the HLS URL embeds credentials (Xtream-style user/pass in the path) it is
  going over the cast channel and to the receiver. Prefer Dispatcharr HLS URLs
  that are keyed by channel UUID + a short-lived token over ones that embed the
  raw account password. This mirrors the no-raw-creds-over-the-wire stance from
  the live Cast Connect path.
- Keep the HTTPS requirement in mind: a public HLS host must be TLS, and we should
  not downgrade to cleartext for a public host just to make casting work.

## Why this is small

The original scoping assumed "build and host a custom web receiver." That turned
out to be unnecessary: the Styled Media Receiver (already registered) is a
first-class HLS player, and the dual-payload trick means the same load works for
both receiver types. So the entire legacy-device feature reduces to "produce an
HLS URL and put it in the load," gated on Dispatcharr. The app scaffolding for the
"put it in the load" half is already in place.
