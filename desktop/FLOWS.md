# Desktop Shell (Electron) — FLOWS
Files: main.js, quotes.json, package.json, icon.png

The desktop app is a **thin Electron shell** around the live site. It exists for ONE
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

## Change Index
| Thing | Where |
|---|---|
| Site the shell loads | `main.js` `APP_URL` / `OBSOPT_URL` env var |
| Close-guard duty rule | `main.js` `onCloseAttempt` (mirror of `frontend/src/utils/dailyDuty.js`) |
| Nag copy / buttons | `main.js` `dialog.showMessageBox` |
| Quote list | `desktop/quotes.json` (copy of `frontend/src/utils/quotes.js`) |
| Window size / theme | `main.js` `createWindow` (`width/height/backgroundColor`) |
| External-link handling | `main.js` `setWindowOpenHandler` → `shell.openExternal` |
| Windows build target / installer opts | `package.json` `build.win` + `build.nsis` |
| App icon | `desktop/icon.png` (copied from `frontend/public/icons/icon-512.png`) |
| Build command / wine image | this file, "Build" section; log at `desktop/build.log` |
| Installer served to users | nginx `/download/` (`frontend/nginx.conf.template`) ← `downloads/` mount (`docker-compose.yml`) |
| In-app install/download UI | `frontend/src/pages/GetAppPage.jsx` (nav `/get-app`); PWA prompt capture `frontend/src/pwa/installPrompt.js` |
| Publish a new .exe | rebuild → `cp desktop/release/*.exe downloads/ObsidianOptimizer-Setup.exe` (live, no rebuild) |
| Enable code signing | electron-builder `CSC_LINK` + `CSC_KEY_PASSWORD` with a paid cert (currently unsigned by choice) |
