# Self-Host Bundle Plan

Goal: any non-technical user installs one desktop app, picks a name, and gets their OWN full
stack running on THEIR machine, reachable at `<name>.obsidianoptimizer.uk`. The owner provides
only: the domain, a Cloudflare-hosted registry (Worker, free tier), and updates. Owner's box,
GPU, RAM and tokens are never used by other users.

No multi-tenant backend rewrite: every install stays single-user. Isolation = separate machines.

## Architecture

```
user's PC ── Desktop launcher (Electron) ── docker compose (user bundle)
                 │                              postgres · rabbitmq · embedder · backend
                 │                              frontend · llm (containerized host-wrapper)
                 │                              cloudflared (tunnel token from registry)
                 │
                 └─ HTTPS ─► registry Worker (registry.obsidianoptimizer.uk, CF Workers + KV)
                               claim name → CF API: create tunnel + DNS CNAME + Access app
                               /oauth/google/callback → relay code to <name>.obsidianoptimizer.uk
phone / browser ─► <name>.obsidianoptimizer.uk ─► CF edge (Access gate) ─► tunnel ─► user's frontend
```

## Decisions (defaults taken 2026-10-26 — user may override)
- D1 Compose under the hood (Docker Desktop required), native bundle later.
- D2 PWA served per-user from `<name>.obsidianoptimizer.uk` (same-origin, no CORS).
- D3 Unsigned builds for now (SmartScreen/Gatekeeper click-through). Targets: Windows NSIS + Linux AppImage; mac later.
- D4 Cloudflare Access email-OTP gate on every user subdomain; `/mcp` path bypasses Access (token-auth only).
- D5 Images public on GHCR (`ghcr.io/vinuitik/obsidian-optimizer-*`) — repo is already public.
- D6 LLM keys stay in the launcher-generated `.env` for v1 (no per-user DB settings rewrite).
- D7 host-wrapper containerized as service `llm` in the USER bundle only; owner's dev/prod compose untouched. `claude-cli` provider disabled in the container (no personal subscription sharing).
- D8 GPU optional: base user compose is CPU-only; `compose.gpu.yml` overlay adds the nvidia reservation when the launcher detects an NVIDIA GPU.

## Interface contracts (fixed — all milestones code against these)

### Registry Worker API (`https://registry.obsidianoptimizer.uk`)
- `POST /v1/claim` `{ name, email }` → `201 { name, hostname, tunnelToken, installSecret }`
  - name: `^[a-z][a-z0-9-]{2,30}$`, reserved list (www, app, registry, api, admin, mail, ...).
  - `409` if taken. Creates CF tunnel (ingress `http://frontend:8081`), proxied CNAME
    `<name>` → `<tunnelId>.cfargotunnel.com`, Access app on `<name>.obsidianoptimizer.uk`
    allowing `email`, plus a bypass Access app on `<name>.obsidianoptimizer.uk/mcp`.
  - `installSecret` (random 32B hex) is stored hashed in KV; proves ownership later.
- `POST /v1/rotate` `{ name, installSecret }` → `200 { tunnelToken }` (reinstall / lost token).
- `DELETE /v1/claim` `{ name, installSecret }` → `204` (deletes tunnel, DNS, Access apps, KV).
- `GET /v1/resolve?name=` → `200 { hostname }` / `404` (used by extension + landing page).
- `GET /oauth/google/callback?code&state` — `state` = `<name>.<nonce>`; 302 to
  `https://<name>.obsidianoptimizer.uk/api/sync/oauth/callback?code&state=<nonce>`.
  Google OAuth client registers ONLY `https://registry.obsidianoptimizer.uk/oauth/google/callback`.
- Rate limit: 5 claims / IP / day (KV counter).
- Secrets (wrangler secrets): `CF_API_TOKEN`, `CF_ACCOUNT_ID`, `CF_ZONE_ID`. KV: `REGISTRY`.

### User bundle (`bundle/`)
- `bundle/compose.yml` — image-only (no `build:`), tags `${OO_VERSION}`; services as in the diagram.
  Vault on a named volume OR `${HOST_VAULT_PATH}` bind (launcher decides; default bind to
  `~/ObsidianOptimizer/vault`). Postgres/embedder/backend ports NOT published to host except
  frontend on `127.0.0.1:${OO_LOCAL_PORT:-8090}` (desktop window loads it locally).
- `bundle/compose.gpu.yml` — nvidia reservation overlay for embedder.
- `bundle/.env.template` — every var the launcher must fill, with comments.
- `bundle/manifest.json` published per release: `{ version, compose, composeGpu, images: [...], minLauncher }`.

### Launcher ↔ bundle
- Launcher data dir: `%APPDATA%/ObsidianOptimizer` / `~/.config/ObsidianOptimizer` holds
  `compose.yml`, `compose.gpu.yml`, `.env`, `state.json { name, hostname, installSecret, version }`.
- Commands it runs: `docker compose -p obsopt -f compose.yml [-f compose.gpu.yml] --env-file .env {pull|up -d|stop|ps --format json}`.
- Update: fetch latest `manifest.json` from GitHub Releases → backup (`docker compose exec backend` DB dump
  endpoint or pg_dump via postgres container) → write new compose → pull → up -d → health check
  `http://127.0.0.1:${OO_LOCAL_PORT}/api/health` (or `/login` 200) → on failure revert to previous version.

## Milestones

Wave 1 (parallel, worktrees):
- **M1 Distributable images + user bundle** — `bundle/` files above; Dockerfile for `host-wrapper`
  (claude-cli off); GitHub Actions `release.yml`: on tag `v*` build+push multi-arch (amd64+arm64)
  images for backend, frontend, embedder, llm to GHCR + attach `manifest.json`/compose files to
  the GitHub Release. Embedder CPU-friendly (no CUDA hard requirement). No secrets baked in.
  Gate: `docker compose -f bundle/compose.yml config` valid; images build locally for amd64;
  owner's root `docker-compose.yml` unchanged.
- **M2 Registry Worker** — `registry/` (Wrangler project, TypeScript), implements the API above,
  unit tests with mocked Cloudflare API (vitest + miniflare/workers pool). No deploy.
  Gate: `npx vitest run` green; `wrangler deploy --dry-run` builds.
- **M3 Launcher** — extend `desktop/` into launcher: first-run wizard (name claim via registry,
  password, vault folder, optional key paste with deep links), Docker detection (+ link to Docker
  Desktop install), start/stop tray toggle, update flow, then load UI from local port. Keeps the
  existing quitter nag. Builds Win NSIS + Linux AppImage.
  Gate: unit tests for env generation/update/rollback logic; `electron-builder --linux` builds.

Wave 2 (after wave 1 merged):
- **M4 Backend hardening** — login rate limit; first-run strong-password rule; OAuth relay
  (`state` nonce, redirect URI from env `GOOGLE_OAUTH_REDIRECT_URI` = registry callback);
  `/api/health` public endpoint if missing.
- **M5 Extension** — server-URL setting resolved via `/v1/resolve`; publish to AMO + Chrome store.
- **M6 Import** — Notion export zip normalizer + Obsidian folder import + raw files → ingest.

Wave 3: live smoke test — owner deploys Worker, cuts `v0.1.0` release, installs on a clean VM.

## Out of scope (v1)
Native (Docker-free) bundle · mac signing/notarization · per-user DB-stored keys · shared owner GPU.
