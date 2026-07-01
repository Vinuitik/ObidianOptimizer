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
