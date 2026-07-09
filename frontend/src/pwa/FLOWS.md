# PWA / Mobile — FLOWS
Files: ResponsiveApp.jsx, MobileApp.jsx, MobileLayout.jsx, BottomNav.jsx, SyncPage.jsx, CapturePage.jsx, useMediaQuery.js (re-exports ../utils/useMediaQuery), useOffline.js, connectivity.js, db.js, outbox.js, syncOffline.js, offlineApi.js, registerSW.js, vite-pwa.config.js, ../../public/sw.js, ../../public/manifest.webmanifest
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

## Technology Notes (constraints / failure modes)
- **Service workers need a secure context.** Real HTTPS or `localhost` only. The stack's self-signed `:8443` will BLOCK SW registration in Chrome — `registerSW.js` no-ops via `window.isSecureContext`. Install + first sync MUST be done online over the Cloudflare tunnel (`obsidianoptimizer.uk`, real cert). After that, offline runs from cache.
- **Hand-written SW, not Workbox.** `public/sw.js` precaches only the shell URLs; hashed JS/CSS are cached lazily (stale-while-revalidate) on first online visit — so a cold-install that immediately goes offline before assets load can fail. First online launch is required. Upgrade path: `vite-pwa.config.js` (Workbox `injectManifest`).
- **Storage is sandboxed + evictable.** PWA can't roam the phone filesystem. Offline data lives in IndexedDB (`obsopt-offline`) + Cache Storage (`obsopt-shell-v1`, `obsopt-media`). `navigator.storage.persist()` is requested but the browser may still deny; under pressure Android can evict the offline set. No quota guard on media — video is excluded by default (`syncForOffline includeMedia` caches images/PDF only).
- **The SW duplicates the IDB outbox schema** (it writes captures while a client may be closed). `public/sw.js` `openDB()` MUST stay in sync with `db.js` (`DB_NAME='obsopt-offline'`, `DB_VERSION=1`, stores reviewNotes/outbox/meta). Bump both together.
- **Session cookie in PWA context.** Capture/grade rely on the same-origin Spring session cookie surviving the installed-PWA context. If it doesn't, requests 401 → everything queues until re-login. CSP `connect-src 'self'` is fine (same-origin only).
- **`navigator.onLine` is a hint, not truth.** It only knows the device has *a* network, not that the server is reachable; the offline layer still falls back to IDB whenever a `fetch` actually throws.
- **Icons are SVG** (`/icons/icon.svg`, `icon-maskable.svg`). Modern Chrome accepts SVG for installability; older engines want 192/512 PNG. If install is refused, add PNGs and update `manifest.webmanifest` + `vite-pwa.config.js`.
- **The installed app is narrow by design.** No note-browsing/search/editor in the PWA — those are on the full site (open the link). `MobileNotesPage`/`MobileSearchPage` are the vestige of the old "whole-app-on-mobile" scaffold; unused, kept in-tree.
- **`display-mode` is the switch.** `matchMedia('(display-mode: standalone)')` is reliable for installed-PWA-launch vs browser-tab; iOS uses `navigator.standalone`. jsdom has no `matchMedia` → `ResponsiveApp` renders `App` under test.
- **Sync tab needs the tunnel.** `SyncPage` "Download for offline" (`syncForOffline`) runs only online and only where the SW is active — the self-signed `:8443` blocks the SW, so download over the real-cert tunnel domain.
- **nginx strips `/api/`** (trailing-slash `proxy_pass`), so backend mappings are relative (`capture`, `review/bundle`) — matches NotesController.

## Change Index
| Touch this | Where |
|---|---|
| App vs full-site seam | `ResponsiveApp.jsx` → `matchMedia('(display-mode: standalone)')` (+ `?pwa=` override) |
| App tabs / scope | `BottomNav.jsx` `TABS` + `MobileApp.jsx` `<Route>` |
| PWA activation (live) | `src/main.jsx` (`ResponsiveApp` + `registerServiceWorker()`) |
| Download-for-offline / sync UI | `SyncPage.jsx` (`syncForOffline`, `flushOutbox`) |
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
