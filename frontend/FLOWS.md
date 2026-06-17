# Frontend Flows — Navigation Index

Subsystem docs live next to the code they describe.

| Subsystem | FLOWS.md | Key files |
|---|---|---|
| **store** | [src/store/FLOWS.md](src/store/FLOWS.md) | useStore.js — all Zustand state, tab management, sync/cancel |
| **api** | [src/api/FLOWS.md](src/api/FLOWS.md) | notes.js, stats.js — all API calls, ApiError, endpoint table |
| **utils** | [src/utils/FLOWS.md](src/utils/FLOWS.md) | diff.js, frontmatter.js, markdownCleanup.js, all Milkdown plugins |
| **components** | [src/components/FLOWS.md](src/components/FLOWS.md) | MilkdownEditor, FolderTree, TabBar, SplitLayout, NavBar, LoginModal |
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
`frontend/nginx.conf` — **single plain-HTTP server block on `:8081`**. The app is reachable
**only through the Cloudflare tunnel** (cloudflared container → `frontend:8081` over the docker
network); Cloudflare terminates real TLS at its edge. **No host ports, no local door, no
self-signed certs** — the old `:80` redirect + `:443` self-signed edge were removed (over-engineered
for a single-user tunnel-only deploy, and the source of the port/redirect confusion).
- `/api/*` → `backend:8084` (docker service name); `/` → SPA
- security headers (CSP, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy)
- rate limits: `/api/login` 5/min per IP (brute-force), `/api/*` 30/s
- `client_max_body_size 100m` — matches Spring multipart limit
- `X-Forwarded-Proto https` hardcoded — all traffic arrives via Cloudflare HTTPS, so the session cookie stays Secure
- **trade-off:** if the tunnel is down, the app is unreachable (no local fallback — tunnel-only by design)

**Caching disabled**: `nginx.conf Cache-Control: no-store` + `cache: 'no-store'` on all fetch calls.

## Routing

| Path | Page |
|---|---|
| `/` | `MainPage` — 3-panel note editor |
| `/learn` | `LearnPage` — split view: ResourcePanel (pdf/video) + NotePanel |
| `/review` | `ReviewPage` — due list + FlashcardSession (tests) or SlideshowReview (self-rate), per `flashcardsEnabled` |
| `/dashboard` | `DashboardPage` — live processing stats, polls `/api/stats` every 3s |
| `/settings` | `SettingsPage` — vault path, review settings, chrono |

Route transitions: `AnimatePresence mode="wait"` + Framer Motion 180ms opacity fade.  
To add a route: `App.jsx <Routes>` + `NavBar.jsx NAV_ITEMS`

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
