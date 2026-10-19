# Passive Feed — Backend (P1a/P2/P3, IMPLEMENTED)

Files: SourceRepository.java, FeedRepository.java, FeedService.java, FeedController.java, SourcePollWorker.java
Tests: SourceRepositoryIT.java, FeedRepositoryIT.java, FeedAcceptRestoreIT.java, SourcePollWorkerTest.java, FeedControllerTest.java
Embedder: embedder/subscriptions/discover.py (`youtube_feed_url`, `discover_feed`, `discover_page_watch`, `discover_youtube_posts`, `discover`)
Plan: FLOWS_feed.md (design doc — §0 P0 findings, §2 data model, §3 subscribe flow, "### P1a"/"### P3" scope/contract)
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
  else: FeedController.resolve(url) — see "Resolve (P3)" below
        !ok → 422 "could not read a feed from that URL"
        SourceRepository.create(...) then SourcePollWorker.ingestCandidates(s, candidates, backlog=true)
             -- the current feed contents become the pending backlog, never auto-accepted here

GET    /feed/sources                → SourceRepository.list()  (includes live pendingCount per source)
POST   /feed/sources/resolve {url}  → FeedController.resolveSource()  -- preview only, see below
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

## Resolve (P3) — subscribe from any URL

```
FeedController.resolve(url) — shared by createSource() (persists) and resolveSource() (preview only):
  isYoutube(url) && url contains "/posts" → discover(url, "youtube_posts")
       ok → kind="youtube_posts", feedUrl=null   (Community tab, no RSS — see below)
  else isYoutube(url) → discover(url, "youtube_channel")
       ok → kind="youtube", feedUrl=resolvedFeedUrl ?? url
       !ok → 422 (a broken YouTube URL does NOT fall back to page_watch)
  else → discover(url, "feed")   -- includes <link rel=alternate> autodiscovery, see P1a section below
       ok → kind="rss" (or "youtube" if the resolved feed itself turns out to be a youtube.com host)
       !ok → discover(url, "page_watch")
            ok → kind="page_watch", feedUrl=null
            !ok → 422 (page itself unreachable — true failure, e.g. Kaggle's JS shell often
                  resolves here with an empty/near-empty preview rather than a hard 422)

POST /feed/sources/resolve {url} → FeedController.resolveSource()
  → resolve(url) + defaultTitle(url); preview = first 3 candidates (title/itemUrl/publishedAt/
    thumbUrl/durationS); NOTHING is persisted (no Source row, no backlog, no polling kicked off)
  → used by: extension popup Subscribe (preview before confirm), any future preview UI
```
To change the resolution order: `FeedController.resolve()`. To change preview size: `resolveSource()`
`.limit(3)`.

### page_watch (same-site link diff)

No feed, no RSS — embedder `discover_page_watch()` regex-extracts every same-site `<a href>` on
the page (same low-dependency convention as RSS autodiscovery, no JS rendering) and returns the
full current snapshot every time. There's no separate "diff" step: `FeedRepository.insertIfNew`'s
existing `item_url UNIQUE` dedup IS the diff — poll #1 inserts every link as backlog, poll #2 of
an unchanged page re-offers the same URLs, all already present → 0 new rows. Junk links (nav/
footer) ride along; swipe absorbs it (see Technology Notes).

### youtube_posts (YouTube Community tab)

A YouTube channel's `/posts` tab has no RSS. Embedder `discover_youtube_posts()` fetches the tab's
static HTML with a `CONSENT=YES+1` cookie (bypasses YouTube's EU consent-redirect — no login, no
JS) and parses the embedded `ytInitialData` JSON for `backstagePostRenderer` blocks (spiked
2026-10-05 against a live channel before building this — see Technology Notes for the failure
mode). Each post becomes a candidate with `item_url = youtube.com/post/<postId>` and a `body`
field (the post's full text) — the only candidate kind that carries one.
`FeedRepository.insertIfNew(..., body)` / `feed_items.body` persist it; `FeedService.accept()`
branches on `item.body() != null` and calls `FeedService.storePostText()` (writes
`resources/files/<captureId>.md` under the vault) then `captureRepo.enqueue(id, "text", title, path, title)`
— the same durable queue + row shape as pasted text; `CaptureIngestWorker.submit()` reads the file
back for `source_type='text'`. No URL fetch, no feed → CaptureController call for text.

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
             -- kind: 'youtube' | 'rss' | 'page_watch' | 'youtube_posts' (P3; free text, not an enum/CHECK)
feed_items  (id, source_id → sources ON DELETE CASCADE, item_url UNIQUE, title, thumb_url, duration_s,
             published_at, status 'pending'|'binned'|'accepted', decided_at, capture_id, created_at,
             body  -- [P3] full text of a youtube_posts item; NULL for every other kind
            )
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
POST   /feed/sources/resolve {url} → {kind, feedUrl, title, preview:[{title,itemUrl,publishedAt,thumbUrl,durationS}]}  [P3, preview only]
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
- **Mailbox/offline hooks don't exist yet** — `OfflineExportService.exportFeed()` and offline swipe
  mailbox kinds are P2 scope. `[NOT IMPLEMENTED]`
- **`CONSENT=YES+1` is an unofficial YouTube cookie trick** (P3, `discover_youtube_posts`) — bypasses
  the EU consent-redirect that otherwise 302s a cookie-less GET away from `ytInitialData` entirely.
  Confirmed live 2026-10-05 against a real channel before building on it (not blocked, no login
  needed). If YouTube changes this page's shape or starts blocking it outright, `discover_youtube_posts`
  raises ValueError ("no ytInitialData found") → `discover` not `ok` → same silent-retry-forever/
  no-alert failure mode as YouTube RSS above. **Do not "fix" a persistent failure here by adding a
  JS-rendering dependency** (Selenium/Playwright) — report it instead, per the P3 plan's own
  constraint. `[NOT IMPLEMENTED: alerting]`
- **youtube_posts dates are unusable.** `publishedTimeText` in `ytInitialData` is relative ("3 days
  ago"), not an ISO/RFC timestamp — `discover_youtube_posts` always returns `published_at: None`.
  Cards sort by insertion order (published_at NULLS LAST), not true recency.
- **page_watch catches ALL same-site links, including nav/footer chrome** — there's no content-vs-
  navigation heuristic. A page with a large shared nav can backlog dozens of irrelevant cards on
  first subscribe; swipe absorbs it (per plan), but auto-accept on a page_watch source is risky
  (also called out in FLOWS_feed.md's own Technology Notes).
- **Kaggle is explicitly out of scope** (user decision 2026-10-10) but not special-cased: a Kaggle
  URL still falls through to `page_watch` rather than hard-failing, since its JS-shell HTML simply
  yields few/no same-site links in the static response — an empty-ish source, not an error. No
  Selenium/Playwright was added to do better, by design.

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
| Source create / resolve kind | `FeedController.createSource()` / `resolve()` / `isYoutube()` |
| Subscribe-preview endpoint | `FeedController.resolveSource()` (`POST /feed/sources/resolve`) |
| YouTube → channel RSS resolution | embedder `discover.py` `youtube_feed_url()` |
| Empty-feed-is-ok | embedder `discover.py` `discover_feed()` |
| Candidate thumb/duration/body fields | embedder `discover.py` dicts → `TrackAgentClient.Candidate` |
| Schema | `SourceRepository.initSchema()` / `FeedRepository.initSchema()` (`feed_items.body`) |
| Frontend (Feed tab, swipe UI) | `frontend/src/components/organisms/FeedView.jsx` + `FeedSources.jsx` (P1b) |
| "Subscribe to this channel" on a card | `FeedView.jsx FeedCard` → `useStore.subscribeToChannel()` (P3) |
| Extension Subscribe preview | `extension/popup.js` `#sub-preview-btn` → `background.js resolveSource()` (P3) |
| page_watch link-diff discovery | embedder `discover.py` `discover_page_watch()` (P3) |
| youtube_posts discovery + text ingest | embedder `discover.py` `discover_youtube_posts()`; `FeedService.accept()` → `storePostText()` + `captureRepo.enqueue("text")` (P3); vault root from `ActiveVault.rootFor(vaultId)` |
| Offline export (pending, text-only, cap 200) | `OfflineExportService.exportFeed()` (P2) |
| Offline export trigger | `SourcePollWorker.ingestCandidates()` (`inserted > 0`) |
| Offline swipe mailbox kinds | `MailboxConsumeService.consumeFile` (`feed_accept`/`feed_bin`/`feed_restore`) |
| Offline swipe outbox kinds | `pwa/outbox.js` `enqueueFeedAccept`/`enqueueFeedBin`/`enqueueFeedRestore` + `flush()` |
| Offline swipe cache (phone) | `pwa/offlineApi.js` `pulledFeed()`/`dropFromFeed()` → IDB meta `feedItems` |
| Vault scoping (M3) | `SourceRepository`/`FeedRepository` take `long vaultId` first; `FeedService.accept/bin/restore(vaultId, …)`; `SourcePollWorker.drain()` pins `activeVault.id()` (scanner) and passes it down; `storePostText` root = `ActiveVault.rootFor(vaultId)` (replaces `SettingsRepository.getVaultPath()`) |
