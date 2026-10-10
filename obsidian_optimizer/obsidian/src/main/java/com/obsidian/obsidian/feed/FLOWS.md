# Passive Feed — Backend (P1a/P2, IMPLEMENTED)

Files: SourceRepository.java, FeedRepository.java, FeedService.java, FeedController.java, SourcePollWorker.java
Tests: SourceRepositoryIT.java, FeedRepositoryIT.java, FeedAcceptRestoreIT.java, SourcePollWorkerTest.java
Embedder: embedder/subscriptions/discover.py (`youtube_feed_url`, `discover_feed`, `discover`)
Plan: FLOWS_feed.md (design doc — §0 P0 findings, §2 data model, "### P1a"/"### P2" scope/contract)
Related: tracks/FLOWS.md (Passive track via `TrackRepository.getOrCreatePassive()`, Phase 1b tagging),
capture/FLOWS.md (`CaptureRepository.enqueue`/`cancelQueued`), settings/FLOWS.md (`feedAutoAccept`),
pwa/FLOWS.md (backend offline export/mailbox — P2), frontend/src/pwa/FLOWS.md (phone offline feed — P2)

Frontend (P1b): built — `frontend/src/components/organisms/FeedView.jsx` + `FeedSources.jsx`,
`frontend/src/api/feed.js`, `LearnPage.jsx` Feed/Inbox switch. See frontend/src/pages/FLOWS.md,
frontend/src/components/FLOWS.md.

Offline (P2): built — the Stack's pending queue + Undo-of-the-last-decision work with the
laptop off; Bin tab + Sources management stay online-only (not exported). See "Offline feed
(P2)" below + pwa/FLOWS.md + frontend/src/pwa/FLOWS.md.

---

## Poll flow

```
SourcePollWorker.tick() (@Scheduled, subscriptions.poll.tick-ms, default 5min)
  → lane.trigger(drain)   (WorkerLane "feed-poll", same convention as other workers)
  → drain(): for each SourceRepository.listActive() source
       due = lastCheckedAt == null || now > lastCheckedAt + SettingsRepository.getSubscriptionPollIntervalMs()
       → pollSource(s)
            target = s.feedUrl() ?? s.url()
            TrackAgentClient.discover(target, discoverType(s))   -- "youtube_channel" | "feed"
            !res.ok() → return, lastCheckedAt untouched (retried next tick)
            → ingestCandidates(s, candidates, backlog = s.lastCheckedAt()==null)
  → ingestCandidates(): per candidate
       url = normalizeUrl(c.itemUrl())                  -- YouTube variants → watch?v=ID, strip utm_*
       FeedRepository.insertIfNew(sourceId, url, title, thumbUrl, durationS, publishedAt)
            ON CONFLICT(item_url) DO NOTHING             -- dedup; binned/accepted items never resurface
       auto = !backlog && (s.autoAccept() ?? settingsRepo.isFeedAutoAccept())
       inserted-new && auto → FeedService.accept(id)      -- first-poll backlog is NEVER auto-accepted
       sourceRepo.markChecked(s.id(), now)                -- advances even for a valid-but-empty feed
```
To change poll cadence: `SettingsRepository.getSubscriptionPollIntervalMs()` (`app_settings.subscriptionsPollIntervalMs`, default 6h).
To change tick frequency: `subscriptions.poll.tick-ms` (default 5min; just the due-check granularity, not the per-source interval).
To change global default triage: setting `feedAutoAccept` (`SettingsRepository.isFeedAutoAccept()`, default false).

## Swipe flow (accept / bin / restore)

```
right (accept): POST /feed/items/{id}/accept → FeedController.accept() → FeedService.accept(itemId)
  → FeedRepository.claimAccepted(id, captureId)   -- atomic pending|binned → accepted, WHERE status <> 'accepted'
       false (already accepted, incl. concurrent 2nd call) → return the existing captureId (idempotent)
  → true → CaptureRepository.enqueue(captureId, classifyUrl(url), url, null, title)
         + CaptureRepository.setTrackId(captureId, targetTrackId ?? TrackRepository.getOrCreatePassive().id())
         + CaptureIngestWorker.nudge()             -- same ingest path the extension/PWA already use
       enqueue throws → FeedRepository.markPending(id)   -- don't strand an 'accepted' row with no capture

left (bin):   POST /feed/items/{id}/bin     → FeedRepository.bin()   -- idempotent, only WHERE status='pending'

restore:      POST /feed/items/{id}/restore → FeedService.restore(itemId)
  → item.status == 'accepted' && capture still 'queued'? CaptureRepository.cancelQueued() (atomic) → markPending
  → capture already left QUEUED (claimed by CaptureIngestWorker) → FeedService.CaptureStartedException → HTTP 409
  → pending/binned item → markPending unconditionally
```
Undo is client-side `restore` of the last decision — no server undo stack (per plan).

## Source lifecycle

```
POST /feed/sources {url, title?, autoAccept?, targetTrackId?}  → FeedController.createSource()
  existing source for this exact url → return it (idempotent)
  else: isYoutube(url) picks the probe type ("youtube_channel" | "feed")
        TrackAgentClient.discover(url, type) → !ok → 422 "could not read a feed from that URL"
        kind = youtube (either the url or the resolved feedUrl is a youtube.com/youtu.be host) else rss
        SourceRepository.create(...) then SourcePollWorker.ingestCandidates(s, candidates, backlog=true)
             -- the current feed contents become the pending backlog, never auto-accepted here

GET    /feed/sources                → SourceRepository.list()  (includes live pendingCount per source)
PATCH  /feed/sources/{id} {autoAccept?, targetTrackId?, status?, title?}
  -- explicit JSON null for autoAccept/targetTrackId clears to "inherit global" / "inherit Passive track"
DELETE /feed/sources/{id}           → ON DELETE CASCADE drops its feed_items; already-ingested notes untouched
POST   /feed/sources/{id}/poll      → SourcePollWorker.pollNowAsync(id)  -- 202, bypasses the interval gate
```

## Offline feed (P2)

```
SourcePollWorker.ingestCandidates()  -- inserted > 0 (new items landed, any source)
  → OfflineExportService.exportFeed()           -- best-effort, same try/catch as everywhere
       feedRepo.list("pending", 200) → _offline/feed.json.enc
       text-only: id/sourceId/sourceTitle/itemUrl/title/publishedAt/status — NO thumbUrl/
       durationS (no offline thumbnail cache yet; FeedCard already renders fine without one)
  → drivePull.pullReviewFromDrive() (phone, same pull as review/cards/inbox)
       → setMeta('feedItems', items)             -- offlineApi.js reads this when !online

Swipe offline (FeedView → store.decideFeed/restoreFeedItem → offlineApi.js):
  acceptFeedOffline/binFeedOffline/restoreFeedOffline
    online+reachable → straight POST (unchanged)
    offline/unreachable → outbox.enqueueFeedAccept/Bin/Restore(itemId) + drop/re-add in
       meta.feedItems (so a reload mid-offline-session still reflects the decision)

Reconnect — TWO replay paths (same split as every other offline write):
  outbox.flush()      → server-direct POST  /feed/items/{id}/accept|bin|restore
  mailbox.pushMailbox → Drive _mailbox/*.enc → MailboxConsumeService
       "feed_accept"/"feed_bin"/"feed_restore" → FeedService.accept/bin/restore(itemId)
       idempotent via eventId (consumed_events) — same file consumed twice ⇒ no 2nd capture
```
`feed_restore` against an item whose capture already left QUEUED throws `CaptureStartedException`
(409, same as the online contract) — a genuine conflict, not a transient failure, so that event
(and its mailbox file, or its outbox entry) is stuck until manually resolved (discard the note
from Inbox). This is the SAME documented mailbox-poison-event gap as every other permanently-
failing kind (pwa/FLOWS.md Technology Notes) — no special-casing added for feed.
To change the export cap: `OfflineExportService.FEED_ITEM_LIMIT` (200).
To add an offline feed field: `OfflineExportService.exportFeed()` (server) + whatever reads
`meta.feedItems` client-side (offlineApi.js `pulledFeed()`).

## URL normalization (dedup key)

`SourcePollWorker.normalizeUrl()`:
- `youtu.be/ID`, `/shorts/ID`, `/embed/ID`, `/live/ID`, `/v/ID`, `?v=ID` on any youtube.com host →
  `https://www.youtube.com/watch?v=ID` (Shorts are normalized, not filtered — they still show as cards).
- Everything else: lowercase host, strip URL fragment, drop any `utm_*` query param.
To change what counts as a duplicate: this method + `feed_items.item_url UNIQUE` (DB-level backstop
via `insertIfNew`'s `ON CONFLICT DO NOTHING`).

## Embedder discover (youtube/RSS resolution)

```
discover(source_url, source_type):
  youtube_channel | feed → youtube_feed_url(url):
       not a youtube.com/youtu.be host → None (fall through to normal feed/yt-dlp path below)
       /channel/UC... in path → https://www.youtube.com/feeds/videos.xml?channel_id=UC...  (no HTTP call)
       /playlist path → None (handled by the existing yt-dlp playlist path, unchanged)
       else (handle/@user/video URL) → yt_dlp 1-entry flat extract for channel_id → same RSS URL
            unresolvable → raise ValueError (→ 422; a video URL subscribes to its channel, not the video)
       resolved → discover_feed(that RSS URL) -- gives publishedAt + media:thumbnail, unlike yt-dlp flat extract
  (youtube_channel, no RSS resolved — playlist case) → discover_youtube_channel() (yt-dlp flat, limit=20)
  feed, no youtube match → discover_feed(source_url) directly
       raises ValueError (malformed / zero entries with no feed version) → find_feed_url() homepage
       autodiscovery fallback → discover_feed(resolved) retry
```
`discover_feed()`: a feed that parses with `version` set and zero entries returns `[]` (empty = ok, e.g.
arXiv on a weekend) — only bozo-with-zero-entries or no-feed-version-with-zero-entries raises.
Each entry carries `thumb_url` (`media:thumbnail`) and `duration_s` (parsed `itunes:duration`, `H:MM:SS` or
seconds) when the feed provides them; yt-dlp's flat playlist extract (channel-tabs/video-list path) never
has either — only the RSS path does.
To change the YouTube candidate cap on the non-RSS path: `discover_youtube_channel(limit=20)`.
To change what a candidate carries end-to-end: this file's dicts → `TrackAgentClient.Candidate` (5 fields:
itemUrl, title, publishedAt, thumbUrl, durationS).

## Data model

```sql
sources     (id, kind, url UNIQUE, feed_url, title, auto_accept BOOLEAN NULL /*null=inherit global*/,
             target_track_id, last_checked_at, created_at, status 'active'|'paused')
feed_items  (id, source_id → sources ON DELETE CASCADE, item_url UNIQUE, title, thumb_url, duration_s,
             published_at, status 'pending'|'binned'|'accepted', decided_at, capture_id, created_at)
```
Both tables init via `@PostConstruct initSchema()` (`CREATE TABLE IF NOT EXISTS`), same convention as
`TrackRepository`/`CaptureRepository`. `tracks.source_url`/`source_type`/`type='subscription'` columns from
the old implementation are left in place, unused (no destructive migration — 0 live rows at cutover).

## Context — API contract (frozen for P1b)

```
GET    /feed/items?status=pending|binned|accepted&limit=50
  → [{id, sourceId, sourceTitle, itemUrl, title, thumbUrl, durationS, publishedAt, status, decidedAt, captureId}]
POST   /feed/items/{id}/accept   → 200 {captureId}        (idempotent)
POST   /feed/items/{id}/bin      → 204                     (idempotent)
POST   /feed/items/{id}/restore  → 204 → pending; 409 if the capture already left QUEUED
GET    /feed/sources             → [{id, kind, url, feedUrl, title, autoAccept|null, targetTrackId, status, lastCheckedAt, pendingCount}]
POST   /feed/sources {url, autoAccept?, targetTrackId?} → Source
PATCH  /feed/sources/{id} {autoAccept?, targetTrackId?, status?, title?}
DELETE /feed/sources/{id}
POST   /feed/sources/{id}/poll   → 202
```
This is the exact contract P1b (frontend) codes against — do not change field names/shapes here without
updating the frontend in the same change.

## Technology Notes

- **YouTube RSS is undocumented** (`/feeds/videos.xml?channel_id=`) — stable for years, but if it ever
  breaks, `discover` stops being `ok` for every YouTube source and `last_checked_at` stops advancing
  (silent retry forever, no alert). `[NOT IMPLEMENTED: alerting]`
- **Polling is pull, bounded to ~15/20 newest items.** YouTube channel RSS returns only the ~15 most
  recent videos; the yt-dlp flat-extract fallback is hard-capped at 20. A channel posting faster than
  that between two polls silently loses the overflow — no backfill. Fine at the 6h default cadence.
- **`isYoutube()` in `FeedController` is host-string matching only** (`youtu.be` / `*.youtube.com`) —
  it only picks which probe type to send to discover; the embedder's own `youtube_feed_url()` is the
  actual source of truth for whether a URL resolves to YouTube RSS.
- **Dedup is exact-string after normalization, not fuzzy.** Two URLs that point at the same video but
  survive normalization differently (e.g. a tracking param not in the `utm_*` set) are two rows.
- **Auto-accept races the backlog boundary on `lastCheckedAt`.** `backlog` is computed as
  `s.lastCheckedAt() == null` at the start of `pollSource`; a source created via `POST /feed/sources`
  gets its backlog ingested with an explicit `backlog=true` call from `createSource()` itself (separate
  from the scheduled poll), so the very first scheduled `drain()` tick after creation already has a
  non-null `lastCheckedAt` and won't re-run the backlog path.
- **`feed_items.capture_id` has no FK to `capture`** — same soft-reference convention as
  `tracks.note_path`/`capture.source_path` (see tracks/FLOWS.md Technology Notes); a capture row deleted
  by retention cleanup leaves `capture_id` pointing at nothing, which only matters for `restore()`'s
  `captureRepo.get(captureId) != null` check (treated as "capture gone" → falls through to `markPending`).
- **`SourcePollWorker.pollNowAsync` is fire-and-forget** (`CompletableFuture.runAsync`, no executor
  injected — uses the common pool) — `POST /feed/sources/{id}/poll` returns 202 before the poll finishes;
  there's no per-call status to query, only the eventual `GET /feed/sources` `lastCheckedAt`/`pendingCount`.
- **Offline covers the swipe stack only, not Bin/Sources** — `OfflineExportService.exportFeed()`
  exports pending items only (§4/P2 scope decision); Bin tab + Sources management stay
  online-only. `[NOT IMPLEMENTED: offline bin/sources]`
- **Offline export is text-only** — no `thumbUrl`/`durationS` (no offline thumb cache yet).
  `[NOT IMPLEMENTED: offline thumbnail cache]`

## Change Index

| Thing | Where |
|---|---|
| Poll tick / due check | `SourcePollWorker.tick()` / `drain()` |
| Poll interval (per-source) | `SettingsRepository.getSubscriptionPollIntervalMs()` |
| Global auto-accept default | `SettingsRepository.isFeedAutoAccept()` (`app_settings.feedAutoAccept`) |
| Per-source auto-accept / target track | `sources.auto_accept` / `sources.target_track_id` |
| URL normalization / dedup key | `SourcePollWorker.normalizeUrl()` + `feed_items.item_url UNIQUE` |
| Backlog vs live-item auto-accept gate | `SourcePollWorker.ingestCandidates()` (`backlog` param) |
| Accept → ingest | `FeedService.accept()` → `CaptureRepository.enqueue`/`setTrackId` |
| Passive default track | `TrackRepository.getOrCreatePassive()` |
| Bin / restore / 409 | `FeedService.bin()`/`restore()` / `CaptureStartedException` |
| Restore cancels an unclaimed capture | `CaptureRepository.cancelQueued()` |
| REST surface | `FeedController` (items/sources endpoints) |
| Source create / resolve kind | `FeedController.createSource()` / `isYoutube()` |
| YouTube → channel RSS resolution | embedder `discover.py` `youtube_feed_url()` |
| Empty-feed-is-ok | embedder `discover.py` `discover_feed()` |
| Candidate thumb/duration fields | embedder `discover.py` dicts → `TrackAgentClient.Candidate` |
| Schema | `SourceRepository.initSchema()` / `FeedRepository.initSchema()` |
| Frontend (Feed tab, swipe UI) | `frontend/src/components/organisms/FeedView.jsx` (P1b) |
| Offline export (pending, text-only, cap 200) | `OfflineExportService.exportFeed()` (P2) |
| Offline export trigger | `SourcePollWorker.ingestCandidates()` (`inserted > 0`) |
| Offline swipe mailbox kinds | `MailboxConsumeService.consumeFile` (`feed_accept`/`feed_bin`/`feed_restore`) |
| Offline swipe outbox kinds | `pwa/outbox.js` `enqueueFeedAccept`/`enqueueFeedBin`/`enqueueFeedRestore` + `flush()` |
| Offline swipe cache (phone) | `pwa/offlineApi.js` `pulledFeed()`/`dropFromFeed()` → IDB meta `feedItems` |
| Subscribe-from-any-URL resolve preview, page_watch | `[NOT IMPLEMENTED]` — P3 |
