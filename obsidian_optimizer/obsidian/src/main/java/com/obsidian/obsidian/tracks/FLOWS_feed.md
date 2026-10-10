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
- Poll: 5-min tick checks which sources are due; per-source interval `subscriptionsPollIntervalMs` = 1h.

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

## 2. Data model `[NOT IMPLEMENTED]`

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
| Offline read on phone | **No** | `OfflineExportService.exportFeed()` → `_offline/feed.json.enc` (copy of `exportInbox`) → `drivePull` → IDB |
| Offline swipe on phone | **No** | mailbox kinds `feed_accept` / `feed_bin` / `feed_restore` → `MailboxConsumeService` switch (copy of `file`/`discard`); non-Drive path via `outbox` |
| Offline thumbnails | **No** | v1: text-only cards offline; SW caches thumbs seen online. `[NOT IMPLEMENTED]` |
| Phone freshness | **Partly** | export runs boot/03:30/manual only → call `exportFeed()` after a poll inserts items |

Offline accept only *enqueues*; ingest happens server-side when consumed — same as Inbox file.

## 5. Phasing

- **P0** — poll default 6h; smoke-test the existing flow (Veritasium + arXiv RSS); document it in tracks/FLOWS.md.
- **P1** — `feed/` package: tables, poll worker moved+rewired to `feed_items`, accept/bin/undo/restore API, `feedAutoAccept` setting; Learn gets `[Feed | Inbox]` modes, Feed = card stack (swipe on mobile, ←/→ + buttons on desktop), Bin tab.
- **P2** — offline: `exportFeed`, mailbox kinds, outbox path, drivePull → IDB. Text-only offline cards.
- **P3** — simplified subscribe: `/resolve` with RSS autodiscovery + `page_watch` kind (Kaggle) + "subscribe to channel" from a card.
- **P4** — Today plan surfaced inside Learn; Tracks page shrinks to track management.

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
| Offline export | `OfflineExportService.exportFeed()` `[NEW]` |
| Offline swipe events | `MailboxConsumeService.consumeFile` switch + `pwa/mailbox.js` `[NEW]` |
| Feed UI | `LearnPage` mode switch + `FeedView.jsx` `[NEW]` |
| Today allocation of passive items | `TodayPlanService.today()` (unchanged; via "Passive" track) |
