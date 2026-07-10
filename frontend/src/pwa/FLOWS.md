# PWA / Mobile — FLOWS
Files: ResponsiveApp.jsx, MobileApp.jsx, MobileLayout.jsx, BottomNav.jsx, SyncPage.jsx, CapturePage.jsx, useMediaQuery.js (re-exports ../utils/useMediaQuery), useOffline.js, connectivity.js, db.js, outbox.js, syncOffline.js, offlineApi.js, reviewPlan.js, drivePull.js, registerSW.js, vite-pwa.config.js, ../../public/sw.js, ../../public/manifest.webmanifest
> Unused (dropped from the narrow PWA, kept in-tree): MobileNotesPage.jsx, MobileSearchPage.jsx.

> The installed PWA is a **narrow, offline-capable app** — three jobs: **Review**
> (flashcards), **Learn** (ingest triage), **Capture** (share-sheet → ingest) + a
> **Sync** tab. It is NOT the whole website; everything else (editor, folder tree,
> search, dashboard, settings) lives on the **full responsive site**, opened as a
> link in a browser. Both are ONE codebase — a fix in a reused leaf lands on both.
> Plan of record: `architecture_plans/PWA_MOBILE_ARCH.md`.
> **Status (2026-07-05): ACTIVATED.** `main.jsx` renders `ResponsiveApp` + registers
> the SW. **Still pending:** the offline **read/grade** seam (store/ReviewPage → `offlineApi`)
> is NOT wired — online review works in the PWA, but true offline review needs that
> swap **plus** consistent outbox-flush so desktop doesn't silently queue grades. The
> offline **Learn** lane (`/api/learn/bundle`) is also unbuilt. See arch plan §P3/§P3b.

## The seam — installed app vs. browser tab (NOT viewport width)
`main.jsx` → `ResponsiveApp` picks by **how the app was opened**:
- `matchMedia('(display-mode: standalone)')` (or iOS `navigator.standalone`) → installed
  from the home screen → **`PwaApp`** (the narrow app, `MobileApp.jsx`).
- otherwise (any browser tab, phone or desktop) → **`App`** (the full responsive site).
- Test override: **`?pwa=1`** forces the app shell, **`?pwa=0`** forces the full site,
  on any device (you can't reinstall to flip modes while developing).
Why display-mode, not `max-width`: the phone needs BOTH — the focused offline app via
the icon AND the full site via a link. Width can't tell them apart; launch mode can.

## Flow — the narrow app
`ResponsiveApp` → `PwaApp` = `BrowserRouter` → `MobileLayout` (shell + offline banner +
`BottomNav` + `LoginModal`) → route → leaf page:
- `/` → redirect `/review`; `/review` → `ReviewPage`; `/learn` → `LearnPage`;
  `/capture` → `CapturePage`; `/settings` → `SyncPage`.
- `ReviewPage`/`LearnPage` are the **same** desktop components (reused). `LearnPage`'s
  `InboxReview` renders its **mobile single-view** ([Source | Note] toggle) automatically
  (`LearnLayout` branches on `useIsMobile`) — see components/FLOWS.md.
- To add a tab: `BottomNav.TABS` entry + `MobileApp` `<Route>`.
- `MobileLayout` flushes the outbox on reconnect (`online` → `flushOutbox()`); harmless
  no-op until the grade seam lands (only captures queue today).
- **Auto sign-in prompt:** the installed PWA has no visible "Sign in" button, so
  `MobileLayout`'s bootstrap effect calls `checkAuth()` and, if it comes back
  unauthenticated **while online**, auto-opens `LoginModal` via `setShowLogin(true)`
  (re-checked on window focus). Offline it's skipped — `/login` is unreachable and the
  downloaded set still works. Dismissible (Cancel/overlay); reappears on next focus if
  still signed out. To change: `MobileLayout.jsx` `gate()` effect.

## Flow — install + offline shell (P1)
`registerServiceWorker()` → `navigator.serviceWorker.register('/sw.js')` → `requestPersistentStorage()`
- `public/sw.js` `install` precaches the shell; `fetch` navigations are network-first → fall back to cached `/index.html` so the app opens offline.
- Secure-context gate: SW refuses on the self-signed `:8443` cert. Install over the Cloudflare tunnel domain (real cert). To change shell precache list: `public/sw.js` `SHELL_URLS`.

## Flow — download for offline (P3)
Settings/Review action → `syncForOffline()` → `fetchReview()` + `fetchNoteContent()` per note → `putReviewNotes()` (IndexedDB) → `caches.open('obsopt-media').add()` per `![[embed]]`.
- To change subset size / media policy: `syncForOffline({ limit, includeMedia })` args.
- One-round-trip alternative: backend `GET /api/review/bundle?limit=N` (CaptureController) returns notes+text+media together.

## Flow — offline review (P3)
`ReviewPage`/`FlashcardSession` → (store) → `offlineApi.fetchReviewOffline` / `fetchNoteContentOffline` / `gradeNoteOffline`
- online → network (`api/notes`); offline → IndexedDB (`db.getAllReviewNotes` / `getReviewNote`).
- grade offline → `outbox.enqueueGrade()` (optimistic). Reconnect → `useOffline` true → `offlineApi.flushOutbox()` replays to `/api/reviews/grade`. 401 → leave queued, prompt `LoginModal`, flush again.
- To change connectivity logic: `connectivity.js`.

## Flow — hybrid review split (flashcard vs read tracks)
Each day the due set is split into a **flashcard track** (auto-graded mini-tests) and a
**read track** (read the note inline + press an FSRS band). Two caps bound it, both
server-synced via `/settings`: `maxDailyReviews` (total/day, doubles as the chrono spread
target) and `maxDailyFlashcards` (of those, how many are flashcards).

`store.fetchReviewNotes(offset)` → `offlineApi.fetchReviewOffline(offset, totalMax)` →
`{ notes:[{path,hasCards}], hasMore }` → `reviewPlan.allocateTracks(notes, {flashcardMax,
totalMax, flashcardsDoneToday})` → `buildReviewList` (carries `track`) → `reviewNotes`.
- `allocateTracks` (in `reviewPlan.js`) is the **single source of truth**, run identically
  online and offline — walks oldest-due-first, gives flashcard slots to card-bearing notes
  until the budget (`flashcardMax − flashcardsDoneToday`) is spent, everything else `read`,
  stops at `totalMax`. To change split rules: `reviewPlan.allocateTracks()`.
- `hasCards`: online from `/review` (EXISTS against active `cards`); offline from a prebuilt
  assignment existing (`db.getAllAssignments` → set of notePaths in `offlineApi.localReviewPage`).
- **Carryover is emergent, not stored:** a note leaves the queue only when graded, so a
  skipped note stays due and ages → sorts first tomorrow → wins a flashcard slot ahead of
  newly-due notes ("flashcards-first"). No per-day plan table.
- **Daily flashcard counter:** `getReviewSession()`/`bumpFlashcardsDone()` in `store` keep
  `{date, offset, flashcardsDone}` in `localStorage` (`obsOpt_reviewSession`), reset on date
  change. `FlashcardSession` calls `store.recordFlashcardDone()` on completion so a mid-day
  reload can't re-offer flashcard slots past the cap.
- **Routing:** `ReviewPage.startSession(note)` → `track==='flashcard'` (and flashcards usable)
  → `FlashcardSession`; else `handleReviewNote(path, true)` → `InlineNoteReview` (read + bands).
- Global `flashcardsEnabled=false` → store passes `flashcardMax=0` → whole day is read-track.

## Flow — capture (P4)
Three ways in, all landing in the Learn inbox via the ingest pipeline. `CapturePage` has a
**Link | Note** toggle:
- **Link (manual):** `CapturePage` → `offlineApi.captureUrl()` → `POST /api/capture {url}`.
- **Note (manual, raw brain-dump):** `CapturePage` → `offlineApi.captureText(text,title)` →
  `POST /api/capture {text,title}` → the text becomes its OWN source + is ingested to `_inbox`
  (MCP text route: <700 words staged as-is, longer split). The "I'll definitely come back to it"
  path for rough notes. Offline/401 → `outbox.enqueueCaptureText()`.
- **Share sheet:** Android share → PWA `share_target` (`manifest.webmanifest`) →
  `POST /share-target` → `public/sw.js handleShareTarget()`:
    - a shared **file** (PDF / video / audio, `accept` in the manifest) → `handleShareFile()` →
      multipart `POST /api/capture/file` → stored under `resources/files/` + standalone capture.
    - else a shared **link** (url or extracted from text) → `POST /api/capture {url}`.
  → 303 redirect `/capture?shared=…`.
Backend: `CaptureController.capture()` (url/text) / `captureFile()` (multipart) → capture queue →
embedder `/ingest` (standalone, `find_home`).
- Offline / 401 → `outbox.enqueueCapture()` (link) / `enqueueCaptureText()` (note) /
  SW `enqueueOutbox({kind:'captureFile', blob})` (file); all replayed server-direct by
  `outbox.flush()` on reconnect (ingestion needs the server anyway — no Drive mailbox kind).
- To change share params: `manifest.webmanifest` + `vite-pwa.config.js` `share_target`.

## Flow — Drive-link + auto-sync (server-independent offline)
`setup.js`, `drivePull.js`, `autoSync.js`, `mailbox.js`, `drive.js`, `crypto.js`.
- **Link once:** `SyncPage` "Link this device" → `linkDevice()` → `GET /api/pwa/setup`
  (`PwaController`) → stores `{clientId, clientSecret, refreshToken, driveFolderId,
  passphrase, deviceId}` in IndexedDB `meta.driveCreds`. Thereafter the phone reads Drive
  **directly** (same OAuth client) — no server needed.
- **Creds re-read (self-heal):** `refreshCreds()` re-pulls `/api/pwa/setup` and MERGES fresh
  fields; only overwrites on a clean 200 (offline/401/409 keeps the current blob). Fixes a
  device that linked BEFORE the server created its Drive folder (blank `driveFolderId` →
  "No Drive folder yet"). Called (a) just-in-time in `pullReviewFromDrive()` when folder id
  is blank + online, and (b) each stale auto-sync. Backend also 409s `/pwa/setup` while the
  folder id is blank (`PwaController`) so a blank can't be cached in the first place.
- **Auto-sync (cron-like):** `MobileLayout` → `autoSync.maybeAutoSync()` on launch, reconnect
  (`online`→true), tab-focus (`visibilitychange`), and a 30-min interval. Self-gates on
  `FRESH_MS` (6h since `meta.lastSync`) so triggers are cheap; when stale → `refreshCreds` →
  `pushMailbox` (grades up) → `refreshAndPull` (rebuild bundle on server if up, else pull the
  existing one) → IndexedDB. Best-effort, never throws — a fail leaves the last-good set.
- To change freshness window: `autoSync.js` `FRESH_MS`. Interval: `MobileLayout` `setInterval`.

## Flow — offline media warm (images + A/V, direct from server)
`warmMedia.js`, `../utils/noteMedia.js`, `drivePull.js`, `../../public/sw.js`.
- **Why direct, not Drive:** the warm only runs while ONLINE/server-up, so heavy blobs skip
  Drive entirely — `refreshAndPull()` fetches them straight from the server after the note
  bundle lands. Note TEXT still comes via the encrypted Drive bundle (works server-down); only
  MEDIA is direct.
- **Scope:** `noteMedia.mediaUrlsForNote(content)` = image embeds `![[name]]` → `/api/images/name`
  + the local A/V source from the `## Source` footer (`resources/…` → `/vault-media/…`,
  `_workspace/…` → `/workspace/…`). Union over review notes (`getAllReviewNotes`) + Learn
  inbox (`meta.inboxItems`). PDFs excluded — offline they render via server `/pdf-page`, not a blob.
- **Warm + retention:** `warmReviewMedia()` `cache.add()`s missing URLs into `obsopt-media`
  (one-by-one so a 404/oversize doesn't abort), then EVICTS managed entries (`/vault-media|
  workspace|api/images/`) not in the current scope → the phone store stays lean.
- **Serve offline:** `sw.js isMedia()` already cache-firsts these paths (extension + `/api/images/`).
- To change what's warmed: `noteMedia.mediaUrlsForNote()`. To warm PDFs offline: not done —
  would need to cache `/pdf-page` PNG renders per page. Renditions (lower-res video): [NOT IMPLEMENTED].

## Technology Notes (constraints / failure modes)
- **Cache Storage has no HTTP Range (206):** a cached `<video>`/`<audio>` served by the SW
  comes back as a full `200`, so the browser must load the WHOLE file before it can seek —
  fine for short clips, memory-heavy for long lectures, and **Safari may refuse to play** a
  ranged media element from cache. This is why server-side low-res/audio-only **renditions**
  (smaller files) are the right next lever, not on-device compression (no browser encoder API).
- **No true overnight cron on a phone:** `periodicSync` is Chrome/Android-only for installed
  PWAs and ~12h-throttled; iOS Safari has none. "Overnight refresh" really means "next
  app-open on wifi" (`autoSync` foreground triggers). Media warm rides the same triggers.
- **PWA auto-sync is FOREGROUND-only.** `autoSync` runs on launch/focus/interval while the app
  is OPEN — it is NOT a true background cron. Periodic Background Sync (`registration.periodicSync`)
  would run closed, but it's **Chrome/Android-only**, needs the `periodic-background-sync`
  permission, and the browser throttles it to ~12h — so it's a future opt-in, not the default.
  Consequence: the set refreshes when you next open the app, not silently overnight.
- **`refreshCreds` needs a live session.** `/pwa/setup` is session-gated; signed out → 401 → it
  keeps the existing creds (no clobber). So a rotated refresh token only heals while signed in
  on the tunnel domain. Blank-folder heal likewise needs one online pull to run.
- **Service workers need a secure context.** Real HTTPS or `localhost` only. The stack's self-signed `:8443` will BLOCK SW registration in Chrome — `registerSW.js` no-ops via `window.isSecureContext`. Install + first sync MUST be done online over the Cloudflare tunnel (`obsidianoptimizer.uk`, real cert). After that, offline runs from cache.
- **Hand-written SW, not Workbox.** `public/sw.js` precaches only the shell URLs; hashed JS/CSS are cached lazily (stale-while-revalidate) on first online visit — so a cold-install that immediately goes offline before assets load can fail. First online launch is required. Upgrade path: `vite-pwa.config.js` (Workbox `injectManifest`).
- **Storage is sandboxed + evictable.** PWA can't roam the phone filesystem. Offline data lives in IndexedDB (`obsopt-offline`) + Cache Storage (`obsopt-shell-v1`, `obsopt-media`). `navigator.storage.persist()` is requested but the browser may still deny; under pressure Android can evict the offline set. No quota guard on media — video is excluded by default (`syncForOffline includeMedia` caches images/PDF only).
- **The SW duplicates the IDB outbox schema** (it writes captures while a client may be closed). `public/sw.js` `openDB()` MUST stay in sync with `db.js` (`DB_NAME='obsopt-offline'`, `DB_VERSION=1`, stores reviewNotes/outbox/meta). Bump both together.
- **Session cookie in PWA context.** Capture/grade rely on the same-origin Spring session cookie surviving the installed-PWA context. If it doesn't, requests 401 → everything queues until re-login. CSP `connect-src 'self'` is fine (same-origin only).
- **`navigator.onLine` is a hint, not truth.** It only knows the device has *a* network, not that the server is reachable; the offline layer still falls back to IDB whenever a `fetch` actually throws.
- **Hybrid split runs CLIENT-side, one function, so desktop and phone always agree.** `reviewPlan.allocateTracks` is fed raw materials (due notes + `hasCards`) by each mode rather than the split being baked server-side into the bundle — avoids re-deriving the same logic in Java and re-running it on the 6-hourly export cron. Cost: the caps must reach the client. Online = `/settings`; **offline = the Drive bundle** (`OfflineExportService` → `bundle.settings` → `drivePull` → IDB meta `reviewCaps` → `store.fetchReviewNotes` in Drive mode). If the bundle predates this field (old export), the phone falls back to store/defaults (50/20) — re-export to fix.
- **The flashcard "done today" counter is `localStorage`, per-device, per-calendar-day.** `obsOpt_reviewSession {date, offset, flashcardsDone}` resets on date change (local midnight) and does NOT sync — phone and desktop keep separate daily counts, and clearing site data resets the budget. It only guards against a *reload* re-offering flashcard slots; it is not authoritative scheduling (a completed note leaves the due queue regardless).
- **Carryover has no backing store.** "Unfinished flashcards come back first tomorrow" is emergent from `sr_due ASC` ordering (skipped = still due = ages to the front), not a persisted plan. If the due ordering ever changes, carryover priority changes with it.
- **Icons are SVG** (`/icons/icon.svg`, `icon-maskable.svg`). Modern Chrome accepts SVG for installability; older engines want 192/512 PNG. If install is refused, add PNGs and update `manifest.webmanifest` + `vite-pwa.config.js`.
- **The installed app is narrow by design.** No note-browsing/search/editor in the PWA — those are on the full site (open the link). `MobileNotesPage`/`MobileSearchPage` are the vestige of the old "whole-app-on-mobile" scaffold; unused, kept in-tree.
- **`display-mode` is the switch.** `matchMedia('(display-mode: standalone)')` is reliable for installed-PWA-launch vs browser-tab; iOS uses `navigator.standalone`. jsdom has no `matchMedia` → `ResponsiveApp` renders `App` under test.
- **Sync tab needs the tunnel.** `SyncPage` "Download for offline" (`syncForOffline`) runs only online and only where the SW is active — the self-signed `:8443` blocks the SW, so download over the real-cert tunnel domain.
- **nginx strips `/api/`** (trailing-slash `proxy_pass`), so backend mappings are relative (`capture`, `review/bundle`) — matches NotesController.

## Change Index
| Touch this | Where |
|---|---|
| Hybrid split rules (flashcard vs read) | `pwa/reviewPlan.js` `allocateTracks()` |
| Review caps (total / flashcards per day) | `/settings` (`maxDailyReviews`, `maxDailyFlashcards`); offline via Drive bundle `settings` → IDB meta `reviewCaps` |
| Offline caps propagation | `OfflineExportService.exportReviewBundle` (`bundle.settings`) → `drivePull.js` (`setMeta('reviewCaps')`) → `store.fetchReviewNotes` (Drive-mode overlay) |
| `hasCards` per note | online: `/review` (`getReviewNotesPagedWithCards`); offline: `db.getAllAssignments` in `offlineApi.localReviewPage` |
| Daily flashcard counter | `store` `getReviewSession`/`bumpFlashcardsDone` (`localStorage obsOpt_reviewSession`); bumped by `FlashcardSession` → `recordFlashcardDone()` |
| Review-track routing | `ReviewPage.startSession()` (`track==='flashcard'` → `FlashcardSession`, else `InlineNoteReview`) |
| App vs full-site seam | `ResponsiveApp.jsx` → `matchMedia('(display-mode: standalone)')` (+ `?pwa=` override) |
| App tabs / scope | `BottomNav.jsx` `TABS` + `MobileApp.jsx` `<Route>` |
| PWA activation (live) | `src/main.jsx` (`ResponsiveApp` + `registerServiceWorker()`) |
| Download-for-offline / sync UI | `SyncPage.jsx` (`syncForOffline`, `flushOutbox`) |
| PWA auto-sync cron (launch/focus/interval) | `MobileLayout.jsx` effect → `autoSync.maybeAutoSync()` |
| Auto-sync freshness window | `autoSync.js` `FRESH_MS` (6h) |
| Creds re-read / blank-folder heal | `setup.js` `refreshCreds()` (+ `drivePull.js` just-in-time call) |
| `/pwa/setup` blank-folder guard | `PwaController.setup()` (409 while `folderId` blank) |
| Activate offline review [PENDING] | swap store `fetchReview`/`fetchNoteContent` + `ReviewPage`/`ReviewRating` `gradeNote` → `pwa/offlineApi`, AND flush outbox in `App` too |
| Shared viewport hook | `src/utils/useMediaQuery.js` (`useIsMobile`, `MOBILE_QUERY`) |
| Shell precache list | `public/sw.js` `SHELL_URLS` |
| Media cache name | `public/sw.js` + `syncOffline.js` `MEDIA_CACHE = 'obsopt-media'` |
| IndexedDB schema | `db.js` + mirror in `public/sw.js` `openDB()` |
| Offline subset size / media policy | `syncOffline.js` `syncForOffline({ limit, includeMedia })` |
| Share-target params (incl. file `accept`) | `manifest.webmanifest` + `vite-pwa.config.js` `share_target` |
| Raw-note (text) capture | `CapturePage` Note mode → `offlineApi.captureText()` → `POST /api/capture {text,title}` |
| Shared-file (PDF/av) capture | `public/sw.js handleShareFile()` → `POST /api/capture/file` (multipart) |
| Offline capture queue kinds | `outbox.js` `enqueueCaptureText` / `captureFile` + `flush()` handlers (server-direct) |
| Capture → ingest behavior | `CaptureController.capture()` / `captureFile()` (backend) |
| Offline review bundle | `CaptureController.bundle()` → `GET /api/review/bundle` |
| Embedder URL | backend `embedder.url` (`${embedder.url:http://embedder:8000}`) |
| Switch to Workbox | install `vite-plugin-pwa`, merge `vite-pwa.config.js` |
| Persist-storage / SW register | `registerSW.js` |
| **Offline boot on origin-down (Cloudflare 1033)** | `public/sw.js handleNavigate()` — network-first with timeout, falls back to cached shell on thrown error, timeout, OR non-ok RESPONSE (a down tunnel returns a real 5xx, so a plain `.catch()` leaked the error page). Bump `VERSION` to force re-cache. Needs ONE online load to install the new SW |
