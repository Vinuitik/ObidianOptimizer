# Frontend Flows — Navigation Index

Subsystem docs live next to the code they describe.

| Subsystem | FLOWS.md | Key files |
|---|---|---|
| **store** | [src/store/FLOWS.md](src/store/FLOWS.md) | useStore.js — all Zustand state, tab management, sync/cancel |
| **api** | [src/api/FLOWS.md](src/api/FLOWS.md) | notes.js, stats.js — all API calls, ApiError, endpoint table |
| **utils** | [src/utils/FLOWS.md](src/utils/FLOWS.md) | diff.js, frontmatter.js, markdown.js (markdown-it pipeline), media.js |
| **editor** | [src/editor/FLOWS.md](src/editor/FLOWS.md) | CmEditor (CodeMirror 6), livePreview, wikiLinks, embeds, math, tables, hashtags, codeBlocks |
| **components** | [src/components/FLOWS.md](src/components/FLOWS.md) | FolderTree, TabBar, SplitLayout, NavBar, LoginModal |
| **pages** | [src/pages/FLOWS.md](src/pages/FLOWS.md) | MainPage startup, SettingsPage SECTIONS pattern, Chrono panel |

## Config

`src/env.js` — single source of truth for API base, ports, feature flags, limits.

```js
ENV.API_BASE    — base path for all API calls (default '/api')
ENV.PORTS       — FRONTEND:8083, BACKEND:8084, DEV:5173
ENV.FEATURES    — REVIEW_PANEL, TRASH_RESTORE, CROSS_FILE_RENAME
ENV.LIMITS      — ITEMS_PER_PAGE
```

## Infrastructure

`frontend/Dockerfile` — Node 20 build → Nginx alpine serve  
`frontend/nginx.conf.template` — **single plain-HTTP server block on `:8081`**. The app is reachable
**only through the Cloudflare tunnel** (cloudflared container → `frontend:8081` over the docker
network); Cloudflare terminates real TLS at its edge. **No host ports, no local door, no
self-signed certs** — the old `:80` redirect + `:443` self-signed edge were removed (over-engineered
for a single-user tunnel-only deploy, and the source of the port/redirect confusion).
- `/api/*` → `backend:8084` (docker service name); `/` → SPA
- `/mcp` → `embedder:8000` — see **MCP public exposure** below
- security headers (CSP, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy)
- rate limits: `/api/login` 5/min per IP (brute-force), `/api/*` 30/s
- `client_max_body_size 100m` — matches Spring multipart limit
- `X-Forwarded-Proto https` hardcoded — all traffic arrives via Cloudflare HTTPS, so the session cookie stays Secure
- **trade-off:** if the tunnel is down, the app is unreachable (no local fallback — tunnel-only by design)

**Why `.template`, not `.conf`**: the Dockerfile copies it to `/etc/nginx/templates/`, and the
nginx entrypoint runs `envsubst` on it at container start → `/etc/nginx/conf.d/default.conf`.
`NGINX_ENVSUBST_FILTER=MCP_API_TOKEN` (set on the `frontend` service in `docker-compose.yml`) pins
substitution to that ONE var, so nginx's own `$host`/`$uri`/`$binary_remote_addr` survive untouched.
To edit routing: `frontend/nginx.conf.template`. To validate: render + `nginx -t` on the compose
network (see linux_scripts/FLOWS.md log/verify commands) — a bare `nginx -t` off-network fails on
unresolvable `backend`/`embedder` upstreams, which is NOT a syntax error.

### MCP public exposure — query-token auth (no OAuth)
`location /mcp` → `embedder:8000` makes the vault search / MCP engine reachable from the internet at
`https://obsidianoptimizer.uk/mcp` (MCP streamable HTTP, `stateless_http + json_response`, so plain
JSON-RPC — not SSE).

**Connect URL (this is the whole trick):**
```
https://obsidianoptimizer.uk/mcp?token=<MCP_API_TOKEN>
```
Add it to the **claude.ai** website as a custom connector with **No authentication**.

- **Why a URL query token, not a header:** the claude.ai website connector can't send custom headers,
  and if `/mcp` returns a **401** the website falls into an **OAuth 2.0** flow we don't implement —
  it fetches a nonexistent `/authorize` and the callback fails. So nginx gates on the `?token=` query
  param instead: `if ($arg_token != "${MCP_API_TOKEN}") { return 401; }`. The website carries the full
  URL (query string included) on every request → always 200 → it **never sees a 401 → never starts
  OAuth**. This is the exact pattern from the sister **habitTracker** project's Caddy rule, ported to nginx.
- **nginx then swaps channels:** once the query token passes, nginx injects the `X-API-Key` **header**
  (`proxy_set_header X-API-Key "${MCP_API_TOKEN}"`) that the embedder's `ApiKeyMiddleware` requires.
  Same secret, two hops: query param (client→nginx) → header (nginx→embedder).
- **Secret handling:** `${MCP_API_TOKEN}` is filled by envsubst from `.env` at container start, never in
  git. **Trade-off:** the token rides in the URL, so it appears in nginx access logs and browser/app
  history. Accepted for single-user use. To rotate: change `.env` → `MCP_API_TOKEN`, redeploy, hand out
  the new URL.
- **Host validation:** the MCP SDK guards against DNS-rebinding, so the request `Host` must be in
  `MCP_ALLOWED_HOSTS` (`.env` → `obsidianoptimizer.uk`). A 400 with a host error = this is unset/stale.
- **Header-capable clients** (Claude Code/Desktop) can skip the query token and send the header directly:
  `claude mcp add --transport http obsidian "https://obsidianoptimizer.uk/mcp?token=<MCP_API_TOKEN>"`.

| Want to change | Where |
|---|---|
| MCP route / proxy tuning | `frontend/nginx.conf.template` → `location /mcp` |
| Auth token (query `?token=` + injected header) | `.env` → `MCP_API_TOKEN` (also passed to frontend via compose `environment`) |
| Allowed public host | `.env` → `MCP_ALLOWED_HOSTS` |
| Lock down harder | add Cloudflare Access on `/mcp`, or narrow the nginx `limit_req` |

**Caching disabled**: `nginx.conf Cache-Control: no-store` + `cache: 'no-store'` on all fetch calls.

## Routing

| Path | Page |
|---|---|
| `/` | `<Navigate to="/review">` — Review is the default (web + PWA start_url `/`; PwaApp also redirects `/` and `*` to `/review`) |
| `/notes` | `MainPage` — 3-panel note editor |
| `/sources` | `SourcesPage` — Feed · Subscriptions |
| `/learn` | `LearnPage` — split view: ResourcePanel (pdf/video) + NotePanel |
| `/review` | `ReviewPage` — due list + FlashcardSession (tests) or SlideshowReview (self-rate), per `flashcardsEnabled` |
| `/dashboard` | `DashboardPage` — live processing stats, polls `/api/stats` every 3s |
| `/settings` | `SettingsPage` — vault path, review settings, chrono |

Route transitions: `AnimatePresence mode="wait"` + Framer Motion 180ms opacity fade.  
To add a route: `App.jsx <Routes>` + `NavBar.jsx NAV_ITEMS`

## Tracks — add to track (note picker)
Files: TrackAddBox.jsx, TrackEditor.jsx (TrackItemsEditor), api/tracks.js (addTrackItem)
- Items MUST reference an existing note (backend 400s without notePath). `TrackAddBox` = debounced `searchNotes(q)` → click hit → `addTrackItem(trackId, title, notePath)`. No hit → "No matching notes", nothing addable. To change: `TrackAddBox.jsx`.
- URL/file intake removed from tracks (lives on Sources). `classifyInput`/`itemKind`/`ItemTypeChip` still exported (QuickCapture uses `classifyInput`).

## Technology Notes (add-to-track)
- Search is server-side `searchNotes`; offline/aborted → empty dropdown, add impossible (no offline add).
- Legacy items with no notePath still render (chip "plain") but can't be created anymore.

## Tracks — one-step create + Advanced
Files: NewTrackForm.jsx, TrackEditor.jsx (TrackDetail), WeekPlan.jsx (drawer host), TrackEditor.test.jsx
- Create: `NewTrackForm.onSubmit()` → `createTrack(title,'book')` → deadline? `updateTrack({deadline})` : else `updateSchedule(id, DEFAULT_SCHEDULE)` (1/day, Mon-Sun; To change: `NewTrackForm.DEFAULT_SCHEDULE`) → `classifyInput(paste)`: urls → `captureResource(url, id)` each | text → `addTrackItem(id, line, null)` per line. Type defaults to book; change under detail Advanced.
- Detail: primary = Deadline + Items. One collapsed `<details>` "Advanced" = Type, Status, Show on Progress tab, Priority, Weekly schedule, Generate a course. Week toolbar (`WeekPlan`) holds + New track / Daily capacity (`CapacityPanel`) / Import (`ImportPanel`), each opening the side drawer.
- Label-only renames (API unchanged): Mini-course → Generate a course; Include in Progress → Show on Progress tab; Review import → Import preview.

| Change | Where |
|---|---|
| New-track form | `NewTrackForm` |
| Advanced contents | `TrackDetail` `<details>`; `ManageTab` `<details>` |
| URL/text detection | `classifyInput()` |
| Item chip | `itemKind()`, `ItemTypeChip` |

## Testing Quick Reference

```powershell
# From frontend/
npm test             # single pass (vitest run)
npm run test:watch   # watch mode
```

Test files sit next to the component or util they cover: `ComponentName.test.jsx` / `util.test.js`.

## Residual (not yet implemented)

- Frontmatter inline editing
- Trash UI (list + restore from `_trash/`)
- Math node inline editing
- Tab persistence across page refresh

## Learn — Today | Week (replaces /tracks page)
Files: LearnPage.jsx, TodayPlan.jsx, WeekPlan.jsx, TrackEditor.jsx, TracksPlan.module.css, api/tracks.js (fetchWeekPlan, mondayOf)
- `LearnPage` SegmentedTabs Today | Week (local `useState('today')`; settings `tracksEnabled=false` → Inbox only).
- Today: `TodayPlan` (GET /tracks/today → `fetchTodayPlan()`: mode toggle, overBudget banner, "N of M for today", reason lines, Mark done → `completeTrackItem()` + toast; "Add to spaced review" in localStorage `tracks:addToReview:<trackId>`) with `InboxReview` rendered FIRST (primary) and `TodayPlan` below it (Learn = pre-processing between Sources and notes). Skeleton until first fetch. Empty CTAs → switch to Week. To change: `TodayPlan.jsx`.
- Week: `WeekPlan` → `fetchWeekPlan(mondayOf())` (GET /tracks/week?start=<this Monday>) → Mon–Sun cards (chip per track + `done/planned` Badge, green ✓ when complete; day header `done/planned` Badge; past days = actual completions; "cap N"); refetches when store `tracks`/`trackCapacity` change and on drawer close. Click chip / "All tracks" chip → right drawer with `TrackDetail` (deadline, items, Advanced: type/status/priority/schedule/mini-course). Toolbar: + New track (`NewTrackForm`), Daily capacity, Import. To change: `WeekPlan.jsx`, `TrackEditor.jsx`.
- `/tracks` → `<Navigate to="/learn">` (App.jsx + pwa/MobileApp.jsx); nav entries removed. Old Progress tab dropped (store `trackProgress` still exists). Capture-time `trackPending` + track pickers removed.
- Feed lives on Sources (see "Sources" below), not Learn.
- Tests: `TodayPlan.test.jsx`, `WeekPlan.test.jsx`, `TrackEditor.test.jsx`, `pages/LearnPage.test.jsx`.

## Technology Notes (Week)
- `mondayOf()` uses the browser's local date; a wrong clock/timezone shifts the week. Week is not navigable (always this week) [NOT IMPLEMENTED: prev/next week].
- `done`/`planned` come from the backend; older backends omit them → badge falls back to `0/count`.

## Change Index (Learn/Week additions)
| Thing | Where |
|---|---|
| Week start date | `api/tracks.js mondayOf()` / `WeekPlan.load()` |
| Progress badges / done state | `WeekPlan.jsx isDone` |
| Learn Today order | `LearnPage.jsx` todayStack |
| Track item add (note-only) | `TrackAddBox.jsx pick()` |

## Sources — the only raw-data intake (`/sources`)
Files: SourcesPage.jsx, FeedView.jsx, FeedSources.jsx, CapturePage.jsx (pwa/), offlineApi.js, outbox.js, api/capture.js, api/feed.js
- Product flow: Sources → (auto ingest) Inbox/Learn → Tracks group existing notes → Review. No capture/feed carries a track id any more.
- `SourcesPage` (auth-gated) SegmentedTabs Feed | Subscriptions (local `useState('feed')`; Capture tab removed — global Capture button). Same page on web (`App.jsx`) and PWA (`pwa/MobileApp.jsx`, bottom tab "Sources" replaces "Capture").
- `FeedView` (no tabs of its own) shows Swipe or Subscriptions per `view` prop. Subscribe: `FeedSources` → store `resolveFeedSource`/`addFeedSource` → `POST /feed/sources {url}` (preview first). To change: `FeedSources.jsx`, `api/feed.js`.
- Capture tab → `pwa/CapturePage` modes Link · Note (idea/text) · File. Link/Note → `offlineApi.captureUrl(url)` / `captureText(text,title)` → `POST /api/capture` (offline/401 → `outbox.enqueueCapture*`, replay body has no track fields; old queued `trackOpts` ignored). File → `api/capture.captureFile(file)` (online only). To change: `CapturePage.jsx`, `offlineApi.js`.
- Global quick idea/URL/file capture stays in NavBar `QuickCapture` (web). `/capture` PWA route kept as share-target landing (SW 303 → `/capture?shared=`).
- Tests: `pages/SourcesPage.test.jsx`, `App.routes.test.jsx`, `__tests__/Feed*.test.jsx`, `pwa/shell.test.jsx`.

### Change Index (Sources / routing)
| Thing | Where |
|---|---|
| Default route `/` | `App.jsx` `<Route path="/">` Navigate; PWA `MobileApp.jsx` |
| Nav entries/order | `components/organisms/useNavItems.js` `NAV_ITEMS`; PWA `pwa/BottomNav.jsx` `TABS` |
| Sources tabs | `pages/SourcesPage.jsx` `TABS` |
| Feed load timeout / error flag | `store/useStore.js` `FEED_FETCH_TIMEOUT_MS`, `fetchFeed`, `feedError` |
| Capture modes | `pwa/CapturePage.jsx` `MODES` |
| Capture POST bodies | `offlineApi.captureUrl/captureText`, `outbox.flush`, `api/capture.js` |

### Technology Notes (Sources)
- Feed/swipe state is in-memory Zustand (`useStore` feed slices): lost on refresh; pending list is cached offline in IndexedDB meta `feedItems` only after a pull.
- Outbox is IndexedDB: queued captures survive reload but are per-device/browser profile; items queued before F1 may still hold `trackOpts` (silently dropped on replay).
- PWA `start_url` is `/`; the redirect to `/review` happens client-side (no server redirect), so first paint is the SPA shell. Moving Notes from `/` to `/notes` breaks old bookmarks to `/` (they now open Review).
- File capture is online-only (multipart, not queued) except the SW share-target path.
