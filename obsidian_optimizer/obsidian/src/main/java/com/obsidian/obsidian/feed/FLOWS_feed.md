# Passive Feed — subscriptions → swipe → Learn (DESIGN / PLAN)

Files (shipped, to be reworked): tracks/SubscriptionPollWorker.java, tracks/TrackAgentClient.java, tracks/TrackRepository.java, capture/CaptureRepository.java, frontend/src/pages/TracksPage.jsx, extension popup Subscribe, pwa/CapturePage.jsx Subscribe
Files (planned): feed/SourceRepository.java `[NEW]`, feed/FeedRepository.java `[NEW]`, feed/FeedController.java `[NEW]`, feed/SourcePollWorker.java `[MOVED from tracks/SubscriptionPollWorker]`, pwa/OfflineExportService.exportFeed `[NEW]`, pwa/MailboxConsumeService feed kinds `[NEW]`, frontend FeedView.jsx `[NEW]`, frontend pwa/mailbox.js + drivePull.js feed kinds `[NEW]`
Related: tracks/FLOWS.md (scheduler), inbox/FLOWS.md (Learn triage), sync/FLOWS.md (backup + encryption), pwa/FLOWS.md + frontend/src/pwa/FLOWS.md (offline), capture/FLOWS.md (ingest queue)

> **Goal:** stop hunting for content. Subscribe once to a channel/feed/page → new items
> show up as cheap swipe cards → right = ingest into Learn, left = Bin (recoverable).
> Tracks stop owning subscriptions and become purely the "what do I learn today" scheduler.

---

## 0. Current state (2026-10-10)

- Subscriptions **BUILT 2026-08-26, never used** (0 rows live). A subscription is a track
  with `type='subscription'` + `source_url`. Undocumented in tracks/FLOWS.md.
- `SubscriptionPollWorker` → `TrackAgentClient.discover()` (YouTube channel / RSS-Atom) →
  every new URL **immediately enqueued for ingest** (= auto-accept, no triage) → Inbox.
- Dedup = `CaptureRepository.existsForSource(url)` (permanent).
- Poll: 5-min tick checks which sources are due; per-source interval `subscriptionsPollIntervalMs`
  code default = 6h (P0, was 1h) — **but the live `app_settings` row seeded on 2026-08-26 still
  holds `3600000`** (`insertDefault` is `ON CONFLICT DO NOTHING`), so live effective = 1h until
  that row is updated/deleted. Not exposed in `GET /settings` either.

### P0 findings — URL-shape matrix
Probed 2026-10-10 (a Saturday) via `POST embedder:8000/subscriptions/discover` (discover-only, nothing
enqueued). `source_type` is NOT detected server-side — the UI pre-picks it by regex
(`youtube.com|youtu.be` → `youtube_channel`, else `feed`); the table uses that pick.

| URL | type sent | ok? | #candidates (= first-subscribe backlog) | title | itemUrl | publishedAt | thumb | duration | note / error |
|---|---|---|---|---|---|---|---|---|---|
| `youtube.com/@veritasium` | youtube_channel | ok but **wrong** | 2 | Y | Y | – | – | – | candidates are the channel **tabs** (`…/videos`, `…/shorts`), not videos |
| `youtube.com/channel/UCHnyf…` | youtube_channel | ok but **wrong** | 2 | Y | Y | – | – | – | same: tab URLs |
| `youtube.com/@veritasium/videos` (extra) | youtube_channel | ok | 20 (hard cap `limit=20`) | Y | Y | – | – | – | real `watch?v=` URLs; same for `/channel/UC…/videos` |
| `youtube.com/feeds/videos.xml?channel_id=UC…` (extra) | feed | ok | 15 | Y | Y | Y | – | – | RSS includes Shorts (`/shorts/…`) |
| `youtube.com/@veritasium` (extra) | feed | 422 | – | | | | | | "no feed found" — autodiscovery doesn't see the channel's RSS link |
| `youtube.com/watch?v=JsBZOcqZerk` | youtube_channel | 422 | – | | | | | | "not a playlist (no entries)" |
| `rss.arxiv.org/rss/cs.LG` | feed | 422 | – (0 entries) | | | | | | valid feed, 0 items on weekends → treated as failure → homepage fallback → "no feed found" |
| `arxiv.org/list/cs.LG/recent` | feed | 422 | – | | | | | | listing HTML has no `<link rel=alternate>` |
| `lilianweng.github.io/` | feed | ok | 53 (whole archive) | Y | Y | Y | – | – | resolved → `/index.xml` |
| `kaggle.com/learn-guide/5-day-genai` | feed | 422 | – | | | | | | Kaggle = JS SPA shell, no feed; whitepapers live at `kaggle.com/whitepaper-<slug>` (e.g. `whitepaper-agents`, also 422); no listing page found (`/whitepapers` is an unrelated profile) |

Implications:
- **P3 must normalise YouTube URLs** (`@handle`/`/channel/UC…` → `/videos` tab, or → `feeds/videos.xml?channel_id=`); today the natural URL a user pastes subscribes to two tab URLs that then get enqueued for ingest.
- **P1a card fields**: `thumbnail` and `duration` are never returned by any branch; `publishedAt` is null for every yt-dlp YouTube candidate (only RSS has it). P1a needs either the YouTube RSS path or a flat-entry field passthrough (yt-dlp flat entries carry `duration`/`thumbnails`) — `TrackAgentClient.Candidate` also drops anything beyond 3 fields.
- **An empty feed must not be an error** (arXiv weekends): `discover()` treats 0 entries as "not a feed" and 422s; P1a should return `[]`.
- **Backlog on first subscribe is unbounded for feeds** (53 for one blog, 20/15 for YouTube) → P1a's "pending, not auto-ingested" default is essential; arXiv listing + Kaggle need `page_watch` (P3), Kaggle likely needs JS rendering.

## 1. Target pipeline

```
Source (youtube | rss | page_watch)
  → SourcePollWorker every 6h → discover → feed_items INSERT ... ON CONFLICT(item_url) DO NOTHING
      → autoAccept(source ?? global) ? accept() : status='pending'
  → Feed (Learn ▸ Feed tab): card = title · source · duration · thumbnail · blurb — NOTHING ingested yet
      → right: FeedController.accept() → CaptureRepository.enqueue + setTrackId(source.targetTrack)
                → existing ingest → Inbox (Learn ▸ Inbox) → file → FSRS
      → left:  status='binned'  (Bin tab, never expires, "Ingest anyway" = accept())
      → undo:  last decision → 'pending' (accept-undo only while capture still QUEUED)
  → Today (tracks scheduler): accepted items' notes are track items (Phase 1b tagging, existing)
      → TodayPlanService allocates by capacity/priority — unchanged
```
To change poll cadence: `subscriptionsPollIntervalMs` (default → **6h**, was 1h).
To change default triage: setting `feedAutoAccept` (default **false** = swipe).

## 2. Data model — `SourceRepository.initSchema()` / `FeedRepository.initSchema()`

```
sources     (id, kind, url, feed_url, title, auto_accept BOOL NULL /*null=inherit global*/,
             target_track_id NULL, last_checked_at, created_at, status active|paused)
feed_items  (id, source_id → sources ON DELETE CASCADE, item_url UNIQUE, title, thumb_url,
             duration_s, published_at, status pending|accepted|binned, decided_at, capture_id NULL)
```
- `feed_items.item_url UNIQUE` replaces `existsForSource` as the subscription dedup (binned
  items stay → never re-shown). Manual captures keep their own dedup.
- Migration: drop `tracks.type='subscription'` + `source_*` columns (0 live rows → no data move).
- Default `target_track_id` = an auto-created "Passive" track (no deadline, priority `could`).

## 3. Subscribe flow (simplified) `[NOT IMPLEMENTED]`

```
any URL (Feed "+" box | extension Subscribe | PWA Capture Subscribe | card "subscribe to channel")
  → POST /feed/sources/resolve {url} → embedder discover:
       youtube video/channel/@handle → channel feed
       any page → <link rel="alternate" type="rss|atom"> autodiscovery
       nothing found → kind=page_watch (diff outbound links on that page)   [NEW kind]
  → preview {title, kind, 3 latest items} → confirm → sources row
  → backlog (current feed contents) inserted as 'pending' — never auto-accepted, even if source is auto
```

## 4. Integration with existing systems

| System | Free via cohesion? | What's needed |
|---|---|---|
| DB backup (`DbBackupService`, pg_dump whole DB) | **Yes** | nothing — new tables ride the dump |
| Vault file sync (Drive) | **Yes** | accepted items become notes → synced like any note; feed rows aren't files |
| Encryption (`VaultEncryptionService`) | **Yes, by reuse** | any new Drive artifact goes through `encrypt()` — no new crypto |
| Offline read on phone | **Yes (P2)** | `OfflineExportService.exportFeed()` → `_offline/feed.json.enc` (copy of `exportInbox`) → `drivePull` → IDB meta `feedItems` |
| Offline swipe on phone | **Yes (P2)** | mailbox kinds `feed_accept` / `feed_bin` / `feed_restore` → `MailboxConsumeService` switch (copy of `file`/`discard`); non-Drive path via `outbox` |
| Offline thumbnails | **No** | v1 (P2): text-only cards offline; SW caching thumbs seen online is still `[NOT IMPLEMENTED]` |
| Phone freshness | **Partly** | export runs boot/03:30/manual only → call `exportFeed()` after a poll inserts items |

Offline accept only *enqueues*; ingest happens server-side when consumed — same as Inbox file.

## 5. Phasing

Execution: one subagent per box, each gets ONLY its milestone section + listed context docs.
```
P0 ──► P1a (backend) ─┐                 ┌─► P2 (offline) ─┐
   └─► P1b (frontend) ┴─► P1v (verify) ─┤                 ├─► P4 (re-plan first)
                                        └─► P3 (subscribe)┘
```
Parallel pairs run in git worktrees. ⚠️ `.git/hooks/post-commit` restarts the live stack on
EVERY commit, worktree branches included → live verification only in P1v / after merge, never
while a sibling agent is committing.

### P0 — prove discovery, document what exists
- Scope: `SettingsRepository.getSubscriptionPollIntervalMs` default 1h → 6h (+ any UI/test/doc
  that hardcodes 1h); **discover-only** smoke test; document current subscriptions in tracks/FLOWS.md.
- Why discover-only: creating a real subscription today auto-ingests EVERY feed item (GPU/LLM
  cost + Inbox flood). Everything after `enqueue` is the same capture path the extension uses
  daily (already proven), so the only unproven link is `discover`.
- URL-shape matrix (grounding for P3): YouTube `@handle`, `/channel/UC…`, a video URL, arXiv
  RSS (`rss.arxiv.org/rss/cs.LG`), arXiv listing page, a blog homepage with `<link rel=alternate>`,
  the Kaggle whitepapers page.
- DoD/metrics: matrix table (ok? / detected type / #candidates / fields present) written into §0
  below; backlog size per source recorded; live `GET /settings` effective interval = 6h (or report
  an explicit stored row overriding it — don't overwrite silently); `test.sh java-unit embedder` green.
- Context: this doc §0–1, tracks/FLOWS.md, settings/FLOWS.md, `TrackAgentClient.java`.

### P1a — feed backend (`feed/` package)
- Scope: `sources` + `feed_items` tables (§2; follow existing schema/migration convention), poll
  worker moved from `tracks/` and rewired to insert `feed_items`, `feedAutoAccept` setting, URL
  normalization (YouTube variants → `watch?v=ID`, strip `utm_*`), discover returns
  `thumbUrl`/`durationS` where the source has them (embedder change). Tracks' subscription code
  paths removed; **old `tracks` columns left in place** (no destructive migration).
- P0 fixes folded in (decided 2026-10-10): any YouTube channel URL (`@handle`, `/channel/UC…`,
  `/c/…`, `/user/…`) resolves to its RSS feed `feeds/videos.xml?channel_id=UC…` (gives
  publishedAt + `media:thumbnail`); a valid feed with 0 entries = ok/empty, NOT a failure
  (`last_checked_at` advances); Shorts are NOT filtered (user swipes them away); a video URL →
  subscribe to its channel. Default `feedAutoAccept=false` (swipe). Kaggle/JS pages out of scope.
- API contract (P1b codes against this; nginx strips `/api`):
  ```
  GET    /feed/items?status=pending|binned|accepted&limit=50   newest published first
         → [{id, sourceId, sourceTitle, itemUrl, title, thumbUrl, durationS, publishedAt, status, decidedAt, captureId}]
  POST   /feed/items/{id}/accept   → 200 {captureId}   idempotent (2nd call returns same captureId)
  POST   /feed/items/{id}/bin      → 204               idempotent
  POST   /feed/items/{id}/restore  → 204 → pending; 409 if its capture already left QUEUED
  GET    /feed/sources             → [{id, kind, url, feedUrl, title, autoAccept|null, targetTrackId, status, lastCheckedAt, pendingCount}]
  POST   /feed/sources {url, autoAccept?, targetTrackId?} → Source   (kind via existing discover; backlog → pending)
  PATCH  /feed/sources/{id} {autoAccept?, targetTrackId?, status?, title?}
  DELETE /feed/sources/{id}        → cascades feed_items; notes already ingested untouched
  POST   /feed/sources/{id}/poll   → 202
  ```
  Undo = client-side `restore` of the last decision (no server undo stack).
- Metrics: poll with autoAccept=false creates **0** capture rows; accept → exactly 1 capture tagged
  with target track (double accept still 1); re-poll same feed → 0 new rows; first poll (backlog)
  never auto-accepted even if source autoAccept=true; accept p95 < 300ms.
- Tests: `*IT` (Testcontainers) for both repos + accept/restore; unit for poll worker
  (autoAccept true/false/null-inherit, backlog, dedup, normalization). `test.sh java-unit java-it embedder`.
- Context: this doc §1–2 + P1a, capture/FLOWS.md, tracks/FLOWS.md (Phase 1b tagging), settings/FLOWS.md.

### P1b — feed frontend
- Scope: Learn `[Feed | Inbox]` mode switch; `FeedView` card stack (swipe on touch, ←/→ +
  buttons on desktop, optimistic UI), Bin tab (restore / "ingest anyway"), one-step Undo toast,
  sources list + add-by-URL + per-source autoAccept toggle, global `feedAutoAccept` in Settings.
  Repoint Subscribe buttons (PWA CapturePage, extension + extension-firefox popup) to
  `POST /feed/sources`; remove subscription creation from TracksPage.
- Metrics: next card visible < 100ms after a decision (no wait on server); failed request rolls the
  card back + toast; works at mobile width in the installed-PWA shell.
- Tests: vitest — swipe right/left, ←/→ keys, undo, bin restore, rollback on error, empty state.
  `test.sh frontend`.
- Context: this doc §1 + P1a contract, frontend/src/pages/FLOWS.md (Learn), components/FLOWS.md,
  store/FLOWS.md, pwa/FLOWS.md (mobile shell), extension/FLOWS.md.

### P1v — merge + live verify (DONE 2026-10-10)
- Merged P1a/P1b to master, all suites green (`java-unit java-it embedder frontend`). Live against
  Veritasium (`@veritasium`, autoAccept off): source create → 15-item backlog, 0 captures; accept
  idempotent (2nd call returns same `captureId`); bin 2 + restore 1; re-poll → 0 new rows; restore
  on an already-QUEUED capture → 409 (`FeedService.CaptureStartedException`, "undo after ingest
  started is not undo", matches Technology Notes). `browse` screenshots desktop (1280px) + mobile
  (390px), one swipe-accept exercised live on `FeedView.jsx`.
- DoD met: metrics observed live; data model flipped to real refs (§2); feed docs moved to
  `feed/FLOWS_feed.md`; root FLOWS.md indexes `feed/FLOWS.md` + this file.

### P2 — offline feed (DONE 2026-10-10)
- Scope: `OfflineExportService.exportFeed()` → `_offline/feed.json.enc` (pending, cap 200, text-only);
  export triggered after a poll inserts items; mailbox kinds `feed_accept`/`feed_bin`/`feed_restore`
  in `MailboxConsumeService` + phone `mailbox.js`; outbox path for non-Drive; `drivePull` → IDB → FeedView offline.
- Metrics: offline swipe → reconnect → server state matches after one consume (idempotency via
  `eventId`/`consumed_events`, same mechanism proved in P1v); same mailbox file consumed twice →
  no duplicate capture (`feedAcceptEvent_consumedTwice_noDuplicateCapture`); export decrypts
  round-trip (`OfflineExportServiceTest`, real AES-GCM). `feed_restore` after the capture already
  left QUEUED is a genuine 409, not a transient failure — folded into the existing documented
  mailbox-poison-event gap rather than special-cased.
- Explicitly out of scope (not exported/offline): Bin tab, Sources add/edit, thumbnails/duration.
- Tests: `MailboxConsumeIT` (+5 feed cases), `OfflineExportServiceTest` (new, plain unit),
  `SourcePollWorkerTest` (+3 export-trigger cases), vitest `offlineApi.test.js`/`outbox.test.js`/
  `drivePull.test.js` (+feed cases) + new `mailbox.test.js`. `test.sh java-unit java-it frontend` green.
- Context: this doc §4 + P2, pwa/FLOWS.md (backend), frontend/src/pwa/FLOWS.md, sync/FLOWS.md "Encryption Detail".

### P3 — subscribe from any URL
- Scope: `POST /feed/sources/resolve {url}` → `{kind, feedUrl, title, preview[3]}` (no row);
  RSS `<link rel=alternate>` autodiscovery; `page_watch` kind (snapshot same-site links, new ones
  = items); "subscribe to this channel" on a card; extension Subscribe shows the preview.
- Metrics: every row of the P0 matrix resolves; page_watch second poll of an unchanged page → 0 new items.
- YouTube **Posts** (channel community/text posts) as a source kind: no RSS exists → build our own: poll `/@handle/posts`, parse the embedded `ytInitialData` JSON, diff post IDs (spike first)
  (yt-dlp / page fetch); text posts ingest as text notes. Goal = maximum breadth (user 2026-10-10).
- Kaggle: OUT — treated as a manually managed opportunity (user 2026-10-10).
- Context: this doc §3 + P0 matrix, embedder discover module, extension/FLOWS.md.

### P4 — Today inside Learn
Re-plan after P1–P3 land (depends on how the Feed actually gets used).

## 6. Decoupling note (later redesign, not this plan)

Adding one domain to offline today = **5 touch points across 3 layers**: export method,
consume switch case, phone `mailbox.js` kind, `drivePull` reader, IDB store. This plan
**duplicates** the Inbox pattern for Feed (permitted). Future refactor candidate: an
`OfflineDomain` interface (`export()` + `handle(event)`) auto-registered as Spring beans, with
a matching frontend registry — so a feature plugs in/out in one place. `[NOT IMPLEMENTED]`

## Technology Notes

- **Polling is pull, not push.** YouTube channel RSS returns only the ~15 newest videos; a
  channel posting >15 between polls loses the overflow. At 6h this is a non-issue for normal channels.
- **YouTube RSS is undocumented** (`/feeds/videos.xml?channel_id=`) — works for years but
  could vanish; failure = `discover` not ok → `last_checked_at` untouched → silent retry each tick. No alert. `[NOT IMPLEMENTED]`
- **page_watch is heuristic** — link-diffing a page also catches nav/footer link churn → junk cards. Swipe absorbs it; auto-accept on a page_watch source is risky.
- **Dedup is by exact URL.** Same video under `youtu.be/` vs `youtube.com/watch` = two items unless URLs are normalized at insert.
- **Undo after ingest started is not undo** — the capture already ran (GPU/LLM spent); undo only re-binns the resulting Inbox note via discard.
- **Mailbox poison events** (existing ⚠️ in pwa/FLOWS.md): a permanently failing `feed_*` event blocks its mailbox file forever — no dead-letter yet.
- **Phone feed staleness:** without the post-poll export hook, the phone sees new items up to ~24h late.
- **Backup lag:** DB backup is nightly — feed decisions made after the last dump are lost on restore (same as all DB state).

## Change Index

| Thing | Where |
|---|---|
| Poll cadence | `subscriptionsPollIntervalMs` (SettingsRepository), tick `subscriptions.poll.tick-ms` |
| Global auto-accept | setting `feedAutoAccept` `[NEW]` |
| Per-source auto-accept / target track | `sources.auto_accept` / `sources.target_track_id` `[NEW]` |
| Source detection (youtube/rss/page) | embedder discover via `TrackAgentClient.discover()` → `/feed/sources/resolve` `[NEW]` |
| Accept → ingest | `FeedController.accept()` → `CaptureRepository.enqueue` + `setTrackId` `[NEW]` |
| Bin / restore / undo | `FeedController` `[NEW]` |
| Feed dedup | `feed_items.item_url UNIQUE` `[NEW]` |
| Offline export | `OfflineExportService.exportFeed()` (P2, implemented) |
| Offline swipe events | `MailboxConsumeService.consumeFile` switch + `pwa/mailbox.js`/`pwa/outbox.js` (P2, implemented) |
| Feed UI | `LearnPage` mode switch + `FeedView.jsx` `[NEW]` |
| Today allocation of passive items | `TodayPlanService.today()` (unchanged; via "Passive" track) |
