# PWA / Mobile — FLOWS
Files: ResponsiveApp.jsx, MobileApp.jsx, MobileLayout.jsx, BottomNav.jsx, MobileNotesPage.jsx, MobileSearchPage.jsx, CapturePage.jsx, useMediaQuery.js, useOffline.js, connectivity.js, db.js, outbox.js, syncOffline.js, offlineApi.js, registerSW.js, vite-pwa.config.js, ../../public/sw.js, ../../public/manifest.webmanifest

> Turns the existing React app into an installable, offline-capable Android PWA by
> reusing every leaf component and adding only a shell + an offline data seam.
> Plan of record: `architecture_plans/PWA_MOBILE_ARCH.md`. Whole feature is
> **additive** — desktop code is untouched until you flip the two wiring lines below.

## Activation (the only edits to existing files needed to go live)
`src/main.jsx` — swap the root + register the SW:
```js
import ResponsiveApp from './pwa/ResponsiveApp';
import { registerServiceWorker } from './pwa/registerSW';
registerServiceWorker();
createRoot(...).render(<StrictMode><ResponsiveApp /></StrictMode>);
```
Optional (full offline review, P3): point the store's review calls at the offline
seam — in `src/store/useStore.js` import `fetchReviewOffline` / `fetchNoteContentOffline`
/ `gradeNoteOffline` from `./pwa/offlineApi` instead of `../api/notes`.
To change the desktop/mobile breakpoint: `ResponsiveApp.jsx` `useMediaQuery('(max-width: 768px)')`.

## Flow — layout switch
`main.jsx` → `ResponsiveApp` → `useMediaQuery` → desktop `App` (unchanged) **OR** `MobileApp`
- `MobileApp` = `BrowserRouter` → `MobileLayout` (shell + `BottomNav`) → route → leaf page.
- To add a tab: `BottomNav.TABS` entry + `MobileApp` `<Route>`.

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
**Share sheet:** Android share → PWA `share_target` (`manifest.webmanifest`) → `POST /share-target` → `public/sw.js handleShareTarget()` → extract url → `POST /api/capture` → 303 redirect `/capture?shared=…`.
**Manual:** `CapturePage` form → `offlineApi.captureUrl()` → `POST /api/capture`.
Backend: `CaptureController.capture()` → embedder `POST /ingest {ref:url}` (standalone, `find_home`).
- Offline / 401 → `outbox.enqueueCapture()`; flushed with grades.
- To change share params: `manifest.webmanifest` + `vite-pwa.config.js` `share_target`.

## Technology Notes (constraints / failure modes)
- **Service workers need a secure context.** Real HTTPS or `localhost` only. The stack's self-signed `:8443` will BLOCK SW registration in Chrome — `registerSW.js` no-ops via `window.isSecureContext`. Install + first sync MUST be done online over the Cloudflare tunnel (`obsidianoptimizer.uk`, real cert). After that, offline runs from cache.
- **Hand-written SW, not Workbox.** `public/sw.js` precaches only the shell URLs; hashed JS/CSS are cached lazily (stale-while-revalidate) on first online visit — so a cold-install that immediately goes offline before assets load can fail. First online launch is required. Upgrade path: `vite-pwa.config.js` (Workbox `injectManifest`).
- **Storage is sandboxed + evictable.** PWA can't roam the phone filesystem. Offline data lives in IndexedDB (`obsopt-offline`) + Cache Storage (`obsopt-shell-v1`, `obsopt-media`). `navigator.storage.persist()` is requested but the browser may still deny; under pressure Android can evict the offline set. No quota guard on media — video is excluded by default (`syncForOffline includeMedia` caches images/PDF only).
- **The SW duplicates the IDB outbox schema** (it writes captures while a client may be closed). `public/sw.js` `openDB()` MUST stay in sync with `db.js` (`DB_NAME='obsopt-offline'`, `DB_VERSION=1`, stores reviewNotes/outbox/meta). Bump both together.
- **Session cookie in PWA context.** Capture/grade rely on the same-origin Spring session cookie surviving the installed-PWA context. If it doesn't, requests 401 → everything queues until re-login. CSP `connect-src 'self'` is fine (same-origin only).
- **`navigator.onLine` is a hint, not truth.** It only knows the device has *a* network, not that the server is reachable; the offline layer still falls back to IDB whenever a `fetch` actually throws.
- **Icons are SVG** (`/icons/icon.svg`, `icon-maskable.svg`). Modern Chrome accepts SVG for installability; older engines want 192/512 PNG. If install is refused, add PNGs and update `manifest.webmanifest` + `vite-pwa.config.js`.
- **Mobile is view-only (v1).** No Milkdown editing on phone — `MobileNotesPage` renders read-only via `utils/markdown.renderMarkdown()`. Editing stays desktop (deliberate scope cut).
- **nginx strips `/api/`** (trailing-slash `proxy_pass`), so backend mappings are relative (`capture`, `review/bundle`) — matches NotesController.

## Change Index
| Touch this | Where |
|---|---|
| Desktop/mobile breakpoint | `ResponsiveApp.jsx` → `useMediaQuery('(max-width: 768px)')` |
| Add a mobile tab | `BottomNav.jsx` `TABS` + `MobileApp.jsx` `<Route>` |
| Activate PWA | `src/main.jsx` (import `ResponsiveApp` + `registerServiceWorker()`) |
| Activate offline review | `src/store/useStore.js` import from `pwa/offlineApi` |
| Shell precache list | `public/sw.js` `SHELL_URLS` |
| Media cache name | `public/sw.js` + `syncOffline.js` `MEDIA_CACHE = 'obsopt-media'` |
| IndexedDB schema | `db.js` + mirror in `public/sw.js` `openDB()` |
| Offline subset size / media policy | `syncOffline.js` `syncForOffline({ limit, includeMedia })` |
| Share-target params | `manifest.webmanifest` + `vite-pwa.config.js` `share_target` |
| Capture → ingest behavior | `CaptureController.capture()` (backend) |
| Offline review bundle | `CaptureController.bundle()` → `GET /api/review/bundle` |
| Embedder URL | backend `embedder.url` (`${embedder.url:http://embedder:8000}`) |
| Switch to Workbox | install `vite-plugin-pwa`, merge `vite-pwa.config.js` |
| Persist-storage / SW register | `registerSW.js` |
