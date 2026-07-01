# Deploy / Boot FLOWS
Files: start.sh, obsidian-optimizer.service, install-service.sh, redeploy.sh

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

## Technology Notes
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
