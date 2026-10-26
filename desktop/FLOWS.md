# Desktop Shell (Electron) — FLOWS
Files: main.js, preload.js, lib/{config,envgen,compose,version,updater,paths,registry,docker,launcher}.js, wizard/{wizard,status}.{html,js}, test/lib.test.js, quotes.json, package.json, icon.png, ../.github/workflows/launcher-release.yml

> UPDATED (self-host bundle M3): the shell is now also the **launcher** that runs the user's own docker
> stack and loads it from `http://127.0.0.1:<OO_LOCAL_PORT>`. The quit-breaker below is unchanged. Set
> `OBSOPT_URL` to bypass the launcher and load a remote site (old behaviour).

The desktop app was a **thin Electron shell** around the live site. It exists for ONE
capability a browser denies: intercepting the real window-close to run the "quit-breaker".
All product logic stays in the web app (auto-updates every deploy) — this shell is stable.

## Flow — launch + close-guard
`app.whenReady()` → `createWindow()` → `win.loadURL(APP_URL)` → user works → `win.on('close', onCloseAttempt)`:
`onCloseAttempt` → `e.preventDefault()` → `webContents.executeJavaScript(dutyProbe)` →
- **duty done** (learn item processed today AND no review due) → `allowClose = true` → `win.close()` (quits silently)
- **duty unfinished** → `dialog.showMessageBox` with a random quote + "Are you a quitter?" →
  - "No — back to work" → stays open
  - "I'm a quitter" → `allowClose = true` → `win.close()`

The duty probe MIRRORS the web `utils/dailyDuty.js`: `localStorage['obsOpt_learnDoneDate'] === today`
AND `GET /api/review?limit=1` empty. Runs IN the page so it shares the session cookie/origin.
To change the rule: `main.js onCloseAttempt` (keep in sync with `frontend/src/utils/dailyDuty.js`).

## Flow — launcher boot
`main.js boot()` → `launcher.isInstalled()` (state.json + .env + compose.yml in data dir) →
- **no** → `wizard/wizard.html` → submit → IPC `oo:install` → `docker.detectDocker()` → `launcher.install()`:
  `envgen.validateInputs` → `registry.claim()` (POST /v1/claim) → `fetchManifest()` → `docker.detectNvidia()` →
  download compose.yml (+compose.gpu.yml if GPU) → `envgen.buildEnv()` → write `.env` (0600) + `state.json` → `startStack()`
- **yes** → `startStack()` → `ensureDocker()` (`docker.detectDocker()`; fail → `status.html` with Docker Desktop link + retry) →
  `launcher.up()` (`docker compose -p obsopt -f compose.yml [-f compose.gpu.yml] --env-file .env up -d`) →
  `waitUntilHealthy()` (poll `/api/health` then `/login` on the local port) → `win.loadURL(localUrl())`; timeout → status page message.
To change data dir: `paths.dataDir()`. To change the .env variable list: `config.ENV_VARS` (+ `GENERATED`, `KEY_LINKS`, `DEFAULTS`).
To change compose commands: `compose.cmd.*` / `composeArgs()`.

## Flow — tray
`app.whenReady()` → `Tray` → `refreshTray()` (every 30s + after each action; `launcher.isOnline()` = `compose ps --format json` has a running service) →
Online/Offline toggle (`startStack()` / `goOffline()` = `compose stop`), Open app, Check for updates (`runUpdateFlow()`), Quit (containers keep running).

## Flow — update + rollback
Tray → `runUpdateFlow()` → `launcher.update()` → `updater.runUpdate(deps)`:
manifest newer than `state.version`? (`version.isNewer`) → `minLauncher` ok? (`version.launcherOk`) → `snapshot()` (compose/.env/state → `prev/`) →
`backup()` (`compose exec -T postgres pg_dump` → `backups/db-<ts>.sql`; empty dump = abort) → `applyManifest()` (new compose files + `OO_VERSION` bump) →
`pull` → `up -d` → `waitUntilHealthy()` → `saveVersion()`. Any failure after backup → `restoreSnapshot()` → `up -d` → health → status `rolled-back` / `rollback-failed`.
The DB dump is kept but NOT auto-restored on rollback. To change: `updater.runUpdate`, deps in `launcher.update()`; manifest URL: `config.MANIFEST_URL`.

## Flow — build / release
`npm test` (node:test, no Docker). `npm run dist:win|dist:linux|dist:mac`. Tag `v*` → `.github/workflows/launcher-release.yml`
builds all three on native runners and attaches to the Release (mac dmg cannot be built on Linux).
mac: ad-hoc signed (`mac.identity: "-"`), no notarization → user right-clicks → Open once; launcher self-update is manual (images still update).
mac docker lookup: `paths.extendedPath()` adds /usr/local/bin, /opt/homebrew/bin to PATH for every docker spawn; `detectNvidia()` is false on mac.

## Flow — force reload (Ctrl/Cmd+R, F5)
`main.js` `before-input-event` on `win.webContents` → `reloadIgnoringCache()`. Bound
explicitly because `autoHideMenuBar` means there's no menu carrying Electron's default
reload accelerator. This is a fallback for a hung/unclickable page — the primary reload
path is the same `RefreshButton` the web app renders in `NavBar` (the shell loads the
live site, so it gets that button for free; see `frontend/src/pwa/FLOWS.md` "update
detection + manual refresh"). To change: `main.js` `before-input-event` handler.

## Config
- **`OBSOPT_URL`** env var — the site the shell loads. Default `https://obsidianoptimizer.uk` (the
  Cloudflare tunnel domain). The URL carries no `?pwa=1`, so it loads the full desktop site; append
  `?pwa=1` (or set OBSOPT_URL) to force the narrow phone-style app shell.
- **Quotes:** `quotes.json` — a COPY of `frontend/src/utils/quotes.js` (the shell can't import from the
  web bundle). Edit both, or they drift. To change nag copy: `main.js` `dialog.showMessageBox` `detail`.

## Build (Windows installer, from Linux)
No host toolchain — build in the dockerized builder that bundles wine:
```
cd desktop
docker run --rm -v "$PWD":/project -w /project electronuserland/builder:wine \
  sh -c "npm install && npm run dist:win"
```
Output → `desktop/release/*.exe` (NSIS installer, user-level, desktop shortcut). `build.log` holds
the last run's output. `node_modules/` + `release/` are gitignored.

## Distribution (in-app "Get App" view)
The built `.exe` is copied to repo `downloads/` (gitignored binary) and served by the frontend
nginx at **`/download/`** (compose mounts `./downloads:/downloads:ro`; `nginx.conf.template`
`location /download/`). The app's **Get App** view (`frontend/src/pages/GetAppPage.jsx`, nav
`/get-app`) links to `/download/ObsidianOptimizer-Setup.exe` and also offers the **PWA install**
button (native `beforeinstallprompt`, captured in `frontend/src/pwa/installPrompt.js`). To publish a
new desktop build: rebuild, then `cp desktop/release/*.exe downloads/ObsidianOptimizer-Setup.exe`
(no container rebuild — the mount is live).

## Code signing — [NOT IMPLEMENTED, deliberate]
The installer is **unsigned** → Windows SmartScreen warns "Windows protected your PC" on first run
(More info → Run anyway). Real signing needs a **paid CA cert** (OV/EV code-signing, ~$100–300/yr)
plus identity validation and, for instant SmartScreen trust, an **EV cert or reputation build-up** —
none of it self-serviceable in a build step. Decision: skip it; acceptable for a personal tool. The
`.exe` warning text is surfaced honestly in the Get App view. To add later: set `CSC_LINK`/
`CSC_KEY_PASSWORD` (electron-builder) with a real cert; nothing else in the pipeline changes.

## Technology Notes
- **Thin-client model:** the shell loads the REMOTE site, so it needs the server (the separate
  always-on box) reachable. It does NOT bundle the app — a deploy updates the desktop app with no
  re-install. The only reason to rebuild the shell is changing `main.js`/quotes/icon.
- **Close-guard reliability:** the duty probe is best-effort — any error (offline, server down, JSON
  parse) resolves to `unfinished = false`, so the app never traps you on an unverifiable state. The
  trade-off: if the server is unreachable at close time, it won't nag even if you owe work.
- **Electron size:** ~120–150 MB installed — it bundles its own Chromium. This was the deliberate
  cost of building the Windows `.exe` from a Linux box with zero extra infra (vs Tauri's ~5 MB, which
  needs a Windows machine or CI to build). Revisit Tauri if size matters.
- **Duty-rule duplication:** the close rule lives in BOTH `main.js` and `frontend/src/utils/dailyDuty.js`.
  They must stay in sync; there is no shared module (native shell vs web bundle). Flagged as a drift risk.
- **Auto-update of the SHELL itself:** [NOT IMPLEMENTED] — only the loaded web app auto-updates. A new
  `main.js`/quotes/icon needs a fresh installer. Wire electron-updater + a feed if that becomes a pain.
- **Docker is the hard dependency:** no Docker/daemon → stack cannot start; launcher only shows help. Docker Desktop on Windows needs WSL2/virtualization; first `up` pulls multi-GB images (minutes, no progress bar, only log lines).
- **.env holds all secrets in plaintext** (0600 on POSIX; on Windows the user-profile ACL only). LLM keys live there (plan D6). Values containing a single quote are rejected.
- **Hardcoded:** registry URL `config.REGISTRY_URL` (env `OO_REGISTRY_URL`), releases repo slug `config.RELEASES_REPO` (wrong slug = update/install 404), pg_dump uses service `postgres`/user+db `obsidian` (rename in bundle compose breaks backups, so updates abort), project name `obsopt`, default port 8090.
- **Health probe** hits 127.0.0.1 only; a port clash on `OO_LOCAL_PORT` shows as "not healthy". Polling: 60 tries x 3s.
- **Tray:** needs a system tray (GNOME needs an AppIndicator extension; failure is caught, app still works). State refresh is polled, not event-driven.
- **Quit leaves containers running** (site stays online via tunnel); use Go Offline first to stop.
- **Registry claim is not idempotent from the launcher:** if install fails after a successful claim, retry hits 409 (name taken). `POST /v1/rotate` with installSecret is the recovery path [NOT IMPLEMENTED in launcher; installSecret is only saved after a full install].
- **mac unsigned/ad-hoc:** Gatekeeper right-click Open on first run; no auto-update of the launcher [NOT IMPLEMENTED].
- **Rollback** restores compose+.env only; if the new version ran DB migrations the old images may not like the migrated DB (restore from `backups/` manually).

## Change Index
| Thing | Where |
|---|---|
| Site the shell loads | `main.js` `APP_URL` / `OBSOPT_URL` env var |
| Close-guard duty rule | `main.js` `onCloseAttempt` (mirror of `frontend/src/utils/dailyDuty.js`) |
| Nag copy / buttons | `main.js` `dialog.showMessageBox` |
| Quote list | `desktop/quotes.json` (copy of `frontend/src/utils/quotes.js`) |
| Window size / theme | `main.js` `createWindow` (`width/height/backgroundColor`) |
| External-link handling | `main.js` `setWindowOpenHandler` → `shell.openExternal` |
| Force-reload shortcut | `main.js` `before-input-event` (Ctrl/Cmd+R, F5) → `reloadIgnoringCache()` |
| Windows build target / installer opts | `package.json` `build.win` + `build.nsis` |
| App icon | `desktop/icon.png` (copied from `frontend/public/icons/icon-512.png`) |
| Build command / wine image | this file, "Build" section; log at `desktop/build.log` |
| Installer served to users | nginx `/download/` (`frontend/nginx.conf.template`) ← `downloads/` mount (`docker-compose.yml`) |
| In-app install/download UI | `frontend/src/pages/GetAppPage.jsx` (nav `/get-app`); PWA prompt capture `frontend/src/pwa/installPrompt.js` |
| Publish a new .exe | rebuild → `cp desktop/release/*.exe downloads/ObsidianOptimizer-Setup.exe` (live, no rebuild) |
| Enable code signing | electron-builder `CSC_LINK` + `CSC_KEY_PASSWORD` with a paid cert (currently unsigned by choice) |
| .env var list / generated secrets / key links | `lib/config.js` `ENV_VARS`, `GENERATED`, `KEY_LINKS`; build in `lib/envgen.js` `buildEnv` |
| Password rule / name rule | `config.MIN_PASSWORD`, `config.NAME_RE`, `envgen.validateInputs` |
| Registry base URL | `config.REGISTRY_URL` / env `OO_REGISTRY_URL` |
| Manifest URL / release file base | `config.MANIFEST_URL`, `config.RELEASE_BASE`, env `OO_RELEASES_REPO` / `OO_MANIFEST_URL` |
| Compose command line | `lib/compose.js` `composeArgs`, `cmd` |
| Docker / NVIDIA detection | `lib/docker.js` `detectDocker`, `detectNvidia`; mac PATH `lib/paths.js` `extendedPath` |
| Update / rollback logic | `lib/updater.js` `runUpdate`; deps `lib/launcher.js` `update` |
| Version compare / minLauncher | `lib/version.js` |
| Wizard UI / status page | `wizard/wizard.html`, `wizard/wizard.js`, `wizard/status.html`, `wizard/status.js` |
| Tray menu | `main.js` `refreshTray` |
| Local port / health endpoints | `.env OO_LOCAL_PORT`, `launcher.probeHealth` |
| Linux / mac / Win targets | `package.json` `build.linux`, `build.mac`, `build.win`, scripts `dist:*` |
| CI launcher release | `.github/workflows/launcher-release.yml` |
