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

### MCP public exposure  ⚠️ security trade-off — come back here to re-lock
`location /mcp` → `embedder:8000` makes the vault search / MCP engine reachable from the internet at
`https://obsidianoptimizer.uk/mcp` (MCP streamable HTTP, `stateless_http + json_response`, so plain
JSON-RPC — not SSE).

- **Why it's an OPEN endpoint (no client auth):** the **claude.ai website** connector only supports
  **OAuth 2.0** and has no field for a custom header — so a client-supplied `X-API-Key` can't be
  entered there. Workaround: nginx itself injects the key via
  `proxy_set_header X-API-Key "${MCP_API_TOKEN}"` (filled by envsubst from `.env`), and the connector
  is added as **"no authentication"**. The embedder still enforces the key; nginx just supplies it.
- **What protects it now:** only the obscure URL + nginx rate-limit. Anyone with the URL can query
  your notes (read-only). Accepted deliberately for single-user use.
- **The proper fix (Option 3, deferred):** implement OAuth 2.0 on the MCP server (auth-server +
  protected-resource metadata, dynamic client registration, token endpoint) so the website connects
  with real auth and the endpoint can require it. Then remove the injected header.
- **Cheaper re-lock without OAuth:** put **Cloudflare Access** in front of `/mcp`, OR stop injecting
  the header and use a header-capable client instead of the website
  (`claude mcp add --transport http obsidian https://obsidianoptimizer.uk/mcp --header "X-API-Key: …"`).
- **Host validation:** the MCP SDK guards against DNS-rebinding, so the request `Host` must be in
  `MCP_ALLOWED_HOSTS` (`.env` → `obsidianoptimizer.uk`). A 400 with a host error = this is unset/stale.

| Want to change | Where |
|---|---|
| MCP route / proxy tuning | `frontend/nginx.conf.template` → `location /mcp` |
| Injected token (make endpoint require client auth) | remove the `X-API-Key` `proxy_set_header` line |
| Token value | `.env` → `MCP_API_TOKEN` (also injected into frontend via compose `environment`) |
| Allowed public host | `.env` → `MCP_ALLOWED_HOSTS` |
| Re-lock without OAuth | Cloudflare Access on `/mcp`, or header-capable client |

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
