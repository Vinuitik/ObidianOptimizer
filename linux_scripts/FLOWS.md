# Deploy / Boot FLOWS
Files: start.sh, obsidian-optimizer.service, install-service.sh, redeploy.sh, deploy-extension.sh, build-firefox-extension.sh, test.sh, ingest_live_e2e.py

Boot → always-online stack:

`systemd (boot)` → `obsidian-optimizer.service` → `start.sh` → host-wrapper respawn loop (bg) **+** `docker compose up --build` (fg)

- The service is installed once via `install-service.sh` (copies unit to `/etc/systemd/system`, `enable --now`). To reinstall after editing the unit: re-run `install-service.sh` — the copy is not live-linked.
- `Restart=always` (unit) → if `start.sh`/compose dies, systemd relaunches in 10s. To change: `obsidian-optimizer.service` `RestartSec`.
- On `systemctl stop`: SIGTERM hits the cgroup → `start.sh` EXIT trap kills the wrapper process group + `docker compose down`. Give it time via `TimeoutStopSec` (unit).

## Restart-on-crash coverage
- **Containers**: `restart: unless-stopped` in `docker-compose.yml` (all 5). Docker daemon restarts them on crash/boot. To change: per-service `restart:` key.
- **Host-wrapper** (python `main.py`, not a container): kept alive by the `setsid` respawn loop in `start.sh` (crash → restart in 3s). It is *not* its own systemd unit by design — non-critical (LLM/vision only; core UI/notes/search survive without it). To change cadence: the `sleep 3` in `start.sh`.

## Debugging — querying logs
Everything (wrapper + all containers) streams to journald under the one unit:
```bash
journalctl -u obsidian-optimizer -f                     # live, everything
journalctl -u obsidian-optimizer -b                     # since this boot
journalctl -u obsidian-optimizer -b | grep -i wrapper   # host-wrapper only
docker compose logs -f <service>                        # single container (postgres|embedder|backend|frontend|cloudflared)
```
Health at a glance:
```bash
systemctl is-enabled obsidian-optimizer   # -> enabled (auto-starts on boot)
systemctl is-active  obsidian-optimizer   # -> active
docker compose ps                         # all 5 Up; postgres/embedder healthy
```

## Firefox extension — self-hosted auto-update
`edit extension/` → `deploy-extension.sh` → `build-firefox-extension.sh` (copies `extension/` → `extension-firefox/`, shallow-merges `manifest.firefox.overlay.json` onto `manifest.json` — see extension/FLOWS.md "single source of truth") → `docker run node:20-alpine … web-ext sign --channel=unlisted` (AMO signs the unlisted `.xpi`) → copy `.xpi` + regenerate `updates.json` into `./ext-dist/` → nginx `/ext/` serves them → installed add-on polls `gecko.update_url` (`/ext/updates.json`) → self-upgrades.

- The whole loop runs on the dev box (which *is* the server) → no GitHub Actions, no manual install after the first.
- `update_url` is baked into `extension/manifest.firefox.overlay.json` → the **first** signed `.xpi` (the one carrying it) must be installed once by hand; every later version auto-updates. To change the poll URL: that `gecko.update_url` (must match `BASE_URL` in `deploy-extension.sh` and the nginx `/ext/` route).
- Patch version auto-bumps each run (AMO rejects a duplicate version) — **this is a release counter for the overlay file only, independent of Chrome's `manifest.json` version**, which has no publish pipeline yet. Diverging version numbers between the two are expected, not a bug. To change the scheme: the `NEW=` line in `deploy-extension.sh`.
- `.xpi` + `updates.json` land in host `./ext-dist` (gitignored), mounted `:ro` at `/ext-dist` — new versions appear live, **no** frontend rebuild. Only editing the nginx `/ext/` block or the compose mount needs `systemctl restart obsidian-optimizer`.
- Requires `AMO_KEY`/`AMO_SECRET` (AMO JWT issuer/secret) exported. Signing JWT capped via `--timeout=240000` (< AMO's 5-min `exp`, sign-addon#1273).

## Testing — `test.sh` + `ingest_live_e2e.py`

`./linux_scripts/test.sh` (no args) runs the offline suites (host-wrapper/embedder/
java-unit/java-it/frontend). `./linux_scripts/test.sh live` runs the LIVE suites against
the real running stack instead — currently `drive-live` (real Google Drive, needs creds)
and `ingest-live` (`ingest_live_e2e.py`, no creds needed, self-cleaning).

`ingest_live_e2e.py` phases (each PASS/FAIL/SKIP, timed):
```
1 preflight        health + provider snapshot + backend login
2 video-captions   yt-dlp caption fast-path (no LLM)
3 page-web         trafilatura web extraction (no LLM)
4 ytdlp-download   yt-dlp FULL download proxy (heavy; SKIP_DOWNLOAD=1 to skip)
5 dedup-guard      backend /capture on an already-live URL → 409
6 synthesis        full ingest pipeline → _inbox note — SKIPs if every LLM
                   provider is cooling (environmental, not a defect)
7 journey          create → file → grade → sync, NO LLM involved so it never SKIPs:
                     journey-create             POST /notes lands a note in _inbox
                     journey-inbox-visible       shows up on GET /inbox
                     journey-file                POST /inbox/file → real folder
                     journey-grade               POST /reviews/grade →
                                                  note_reviews.due moves into the future
                     journey-frontmatter-mirror  fsrs-* fields land in the local .md
                     journey-sync-queued         sync_queue gets a PENDING row
```
Phase 7 exists because phase 6 depends on LLM provider quota and SKIPs often — it alone
can't be trusted to catch a break in create→review→sync. Phase 7 exercises the exact
same REST endpoints the UI calls (`/notes`, `/inbox`, `/inbox/file`, `/reviews/grade`),
never touches the real vault content (its own `_e2e_journey` folder + note, soft-deleted
via the normal `/notes`/`/folders` DELETE endpoints — same trash path a real delete
takes), and asserts against Postgres directly (`note_reviews.due`, `sync_queue.status`)
rather than trusting API response shape alone.
To change: `linux_scripts/ingest_live_e2e.py` `journey()`.

## Technology Notes
- **Firefox self-distribution (`--channel=unlisted`)**: AMO signs but does **not** host — you serve the `.xpi` yourself. Firefox polls `update_url` on its own cadence (~daily), not instantly; `about:addons → ⚙ → Check for Updates` forces it. An add-on installed from a build **without** `update_url` (e.g. the pre-auto-update 0.2.1) will never auto-update — reinstall once from a URL-carrying build. `drive.file`-style gotcha: the `.xpi` must be reachable over **real HTTPS** (the Cloudflare tunnel), not the self-signed `:8443`.
- **systemd `Restart=always` + `--build`**: every boot/restart re-runs `docker compose up --build`. A broken build (no network, bad Dockerfile) → the service crash-loops every 10s. Symptom: `journalctl` shows repeated build attempts. Not silent — check the log.
- **Host-wrapper respawn loop, not a unit**: a tight crash loop (wrapper fails instantly every time) will respawn every 3s forever with no backoff cap. Acceptable because the wrapper is non-critical; if it ever becomes load-bearing, promote it to its own `Restart=always` unit instead.
- **Orphan safety net**: if the trap's group-kill misses the python child, `cleanup()` in `start.sh` reaps whatever holds `PORT` (default 5500) on the next start via `lsof`. So a stale wrapper never blocks a restart.
- **Agent/SSH shell reaping**: running `start.sh` directly from this repo's agent shell gets reaped (see memory `deploy-sandbox-reaping`). Production path is systemd — unaffected. To start manually from such a shell, use `docker compose up -d` and skip the wrapper.
- **Not dockerized**: the host-wrapper runs on the host (port 5500) so it can reach host-only resources; containers reach it via `host.docker.internal` (`extra_hosts: host-gateway` on Linux). If `PORT`/`WRAPPER_URL` in `.env` drift apart, the backend/embedder can't reach it.

## Change Index
| Want to change | Where |
|---|---|
| Boot auto-start on/off | `systemctl enable/disable obsidian-optimizer` |
| Service restart delay | `obsidian-optimizer.service` → `RestartSec` |
| Graceful-stop timeout | `obsidian-optimizer.service` → `TimeoutStopSec` |
| Reinstall unit after edit | re-run `install-service.sh` |
| Container crash policy | `docker-compose.yml` → per-service `restart:` |
| Wrapper respawn delay | `start.sh` → `sleep 3` in the setsid loop |
| Wrapper port | `.env` → `PORT` (and matching `WRAPPER_URL`) |
| View logs | `journalctl -u obsidian-optimizer` / `docker compose logs` |
| Ship a new extension version | `./linux_scripts/deploy-extension.sh` (needs `AMO_KEY`/`AMO_SECRET`) |
| Extension auto-update poll URL | `extension/manifest.firefox.overlay.json` → `gecko.update_url` + nginx `/ext/` + `BASE_URL` in `deploy-extension.sh` |
| Extension version scheme | `deploy-extension.sh` → `NEW=` bump line |
| Where signed `.xpi`/`updates.json` live | host `./ext-dist` → nginx `/ext/` (compose `frontend` volume) |
| Signing timeout / JWT cap | `deploy-extension.sh` → `--timeout=240000` |
| Run live E2E suites | `./linux_scripts/test.sh live` |
| Create→file→grade→sync journey check | `ingest_live_e2e.py` → `journey()` |
| Live E2E suite list | `test.sh` → `LIVE_SUITES` |
