# Live TV guide semantics (shared, both platforms)

Status: adopted 2026-09-01 for the Android guide rebuild (feature/guide-rebuild).
Source of truth for these rules is the Apple implementation in
`Features/LiveTV/EPGGuideView.swift` (GuideStore + the tvOS guide); this note
restates them so a change is made to a rule first, then to two implementations.
Where Android deliberately differs, the difference is marked ANDROID.

## 1. Channel identity

Every channel row has ONE canonical id. It is never a tvg-id, a channel number
or a bare integer.

| Source | Canonical id |
|---|---|
| Dispatcharr | the server channel uuid (`disp:<uuid>`). Apple uses the integer id as a string; both are server-stable. |
| Xtream Codes | the panel stream id (`m3u:<hash of stream url>` on Android) |
| Plain M3U / file | a hash of the stream url; repeated urls get `-1`, `-2` suffixes (Apple) / are distinct rows by url (Android) |

Identity-bearing side fields: declared tvg-id; Dispatcharr uuid; Dispatcharr
integer channel id (recordings only, never EPG matching); the Dispatcharr
`epg_data` binding (`effective_epg_data_id ?? epg_data_id`), which is the
server-declared route from a channel to the tvg-id its grid rows carry.

## 2. Match maps (built once per fetch)

1. `tvgIdToChannels`: lower-cased tvg-id -> SET of canonical ids. ONE-TO-MANY:
   a shared tvg-id attaches its programmes to every channel that declares it.
   Dispatcharr rows contribute BOTH their own tvg-id and the epg-data-bridged
   tvg-id (`epgdata[id].tvg_id`) when they differ; both keys point at the row.
2. `intIdToChannel` (grid only): Dispatcharr integer channel id -> canonical id.
   Consulted ONLY when a grid row has no tvg-id at all (the grid's `channel`
   field). Never after a tvg-id miss. A numeric tvg-id can therefore never be
   confused with an integer channel id.
3. `numberToChannel` (XMLTV only): channel number -> canonical id, first wins.
   Consulted only for XMLTV programmes, after a tvg-id miss, because
   Dispatcharr's `/output/epg` keys programmes by channel number when the
   source has no tvg-id. Never consulted for grid rows.
4. `uuidToChannel`: lower-cased Dispatcharr uuid -> canonical id (dummy-EPG
   rows carry the uuid as their tvg-id).

Precedence:
- Grid row: tvg-id present -> `tvgIdToChannels`, else `uuidToChannel`, else DROP.
  tvg-id absent -> `intIdToChannel`, else DROP.
- XMLTV row: `tvgIdToChannels` -> `numberToChannel` -> `uuidToChannel` -> DROP.

Unmatched programmes are dropped and counted (Android logs a sample). A feed
whose programmes match NOTHING is a failure: it is not committed and the
existing guide is left untouched.

## 3. Sources and merge order (Dispatcharr playlists)

1. The user's custom XMLTV url on the playlist, first.
2. The grid (`/api/epg/grid/`) merged on top.
3. Category enrichment for now-airing programmes, asynchronously.
4. Upstream XMLTV sources discovered on the server, last and asynchronously,
   each scoped to the channels the server sourced FROM that feed
   (`/api/epg/epgdata/` `epg_source`); if that scoping cannot be fetched the
   whole pass is skipped (unscoped layering is how cross-feed contamination
   happened).

Span ownership (ANDROID restatement of the same outcomes): the grid owns now and
future for its channels and never rewrites history; the user's own XMLTV and a
playlist's own XMLTV own everything they cover; upstream feeds are additive and
may only fill history before the grid's earliest row.

Duplicate detection when merging one programme onto a channel: same title AND
starts within 60 s, OR the overlap is more than 80 % of the NEW programme's
duration. On a duplicate the FIRST-inserted programme keeps identity, title and
times; the longer description wins; an existing non-empty category wins; ids
and season/episode fill only if missing; the boolean flags OR together.

Cache: rows older than retention (default 7 days, 1..30) are pruned; present
and future rows for the server are replaced on save; past rows are kept and
deduped by (channel, start). Refusing to commit an all-empty snapshot is a rule.

## 4. Staleness

Both age AND identity:
- Age: the disk cache is fresh for `bgRefreshIntervalMins` (default 24 h).
- Identity: a fresh cache still refetches unless it holds at least one FUTURE
  programme keyed to a currently loaded channel id.
- Warm refresh: 30 minutes, on the foreground edge and on a 5-minute loop that
  is gated on no player being active.
ANDROID replaces the ratio heuristic ("fewer than 20 % of cached keys match")
with a hash of the loaded canonical id set: different hash -> refetch; same
hash -> paint from cache and refresh on the age rule.

## 5. Parsing

Streaming, never whole-file. Gzip by url suffix, content type, or magic bytes.
The known-channel filter is decided at the `<programme>` START tag from the
`channel` attribute; skipped programmes accumulate nothing. Episode-number
regexes are compiled once. First non-empty title/desc/sub-title wins; every
non-empty category is kept and joined with commas. A programme needs both dates
and a non-empty title. Marker elements (`new`, `live`, `premiere`,
`previously-shown`) are detected by presence.

Budgets: none inside the parser. Upstream layering has a per-source watchdog
and a whole-phase deadline; on Android both clock the parse only and the parse
runs at background thread priority.

## 6. Guide focus and navigation (TV)

- Window: `hoursBack = min(retentionDays * 24, 24)`, `hoursForward = epgWindowHours (default 36)`.
- Launch anchor: place NOW at `lead = 15 minutes` of past inside the left edge.
  Recomputed from the wall clock on every appear; re-applied only when the
  timeline is away from now by more than the slop of 30 minutes.
- Anchor column: `viewportAnchorTime = timeAtViewportLeft + 15 minutes`.
- UP/DOWN land on the cell in the next row that CONTAINS the anchor time. The
  timeline never moves on a vertical move. ANDROID drives this directly from
  grid state (row, anchorTime) instead of correcting the system focus engine
  afterwards.
- LEFT/RIGHT pan the timeline by half an hour per press and then retarget
  focus, on the same row, to the cell containing the anchor. The focus ring
  rides the viewport; it never slides off screen.
- Holes: Apple draws nothing and leaves focus where it was when the anchor
  falls in a hole. ANDROID synthesises a focusable "No info" cell for any hole
  of five minutes or more, so the D-pad can land on it and play the channel.
- Empty row (no visible programmes): a single focusable strip the width of the
  viewport, pinned on screen.
- Lane: the focused row is held at a stable upper lane; vertical scroll is
  continuous by row.
- Back / Menu ladder on the guide: timeline away from now -> restore now and
  top; else top channel; the tab hosts decide anything beyond that. Double
  press while a mini player is up = scroll to top (300 ms debounce); a single
  press expands the mini player.
- Hold-Left opens the group sidebar (0.32 s in sidebar mode, 0.5 s otherwise);
  hold-Right closes the mini player when so mapped; hold-Select opens program
  info (0.35 s). Page up/down step rows (default 5); timeline jump steps hours
  (default 2.5 h).

## 7. Rendering

- Cells clamp to the window; a cell that starts before the visible edge is
  clipped and its title aligns to the clipped edge; minimum drawn width keeps
  a hit target; below a text-width floor the text is omitted but the cell
  stays focusable.
- The now-line is a 2 pt live-red rule refreshed once a minute; cells do not
  re-render on a clock tick.
- No per-cell progress bar; the now-airing cell is tinted, not filled.
- Focus visual: flat (no scale), a translucent white fill plus a 4 pt white
  border, white text. ANDROID draws the border at 2 dp: TV density is 2x, so
  2 dp is the same on-screen weight as Apple's 4 pt at 1080p (Logan 2026-09-01).
- ANDROID draws each visible row's cells in one pass and keeps one focus owner
  (the grid); Apple keeps per-cell native focus with the corrective snap. Both
  must produce the behaviours in section 6.
