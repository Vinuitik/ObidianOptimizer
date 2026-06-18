# Browser Extension — FLOWS
Files: manifest.json, manifest.firefox.json, background.js, popup.html, popup.css, popup.js, config.js, ../build-firefox-extension.ps1

> MV3 extension with two jobs: (1) clip a note to the vault from any page, (2) queue
> a video/playlist for offline download. Deliberately **vanilla** (no React build) so
> it loads unpacked with zero `npm install` — it reuses the backend API *contracts*
> and the app's design *tokens*, not the React component tree. Upgrade path to the
> shared-component approach is in `architecture_plans/EXTENSION_ARCH.md` (@crxjs).

## Load it
- **Chromium** (Chrome/Edge/Brave): `chrome://extensions` → Developer mode → **Load
  unpacked** → select this `extension/` folder.
- **Firefox**: `pwsh ../build-firefox-extension.ps1` → `about:debugging` → **Load
  Temporary Add-on** → `extension-firefox/manifest.json` (FF 121+).

Then open the popup → ⚙ Settings → set endpoints + sign in.

## Cross-browser (Chromium + Firefox)
One codebase, one diverging file: the **background declaration**. Chrome MV3 requires
`background.service_worker` (`manifest.json`); Firefox MV3 uses an event page
`background.scripts` (`manifest.firefox.json`). `build-firefox-extension.ps1` copies
`extension/` → `extension-firefox/` with the Firefox manifest dropped in as
`manifest.json` (Firefox's "Load Temporary Add-on" always reads `manifest.json`).
The JS is shared verbatim: `config.js` exports `api = browser ?? chrome`, so every
`await api.*` call is promise-based in both engines (Firefox only promisifies
`browser.*`, not `chrome.*`) — no webextension-polyfill needed. To add a WebExtension
API call, use `api.*` (imported from `config.js`), never `chrome.*` directly.

## Architecture (why a background worker)
```
popup.js (UI)  ──api.runtime.sendMessage──▶  background.js (worker / event page)  ──fetch──▶  backend
```
All `fetch` lives in `background.js`: it runs with the extension's `host_permissions`,
so the page's CORS/CSP/mixed-content rules don't apply, and the ObsidianOptimizer
session cookie is sent (`credentials:'include'`). The popup never calls the network directly.
To add an action: add a handler to `background.js HANDLERS` + a `send('name', …)` call in `popup.js`.

## Flow — clip a note (New note tab)
`popup.loadPageContext()` prefills title=tab.title, body=`window.getSelection()` (via `chrome.scripting`).
On Save → `send('createNote')` → `background.createNote()`:
```
GET  /children                    → vault root (default folder) or chosen folder
POST /notes {folder, name}        → backend writes sr-due frontmatter + #review → { path }
GET  /text?noteName=path          → read template (keep its frontmatter)
PUT  /notes {path, content}       → template + "# title" + "Source: url" + body
```
- Reuses the SAME endpoints as `frontend/src/api/notes.js` (createNote→updateNote) so a clipped note enters FSRS review exactly like an app-made one.
- Title is sanitized for the filesystem in `background.sanitizeName()` (Obsidian-illegal chars stripped). To change the note template/layout: `background.createNote()` `parts` array.
- 401 → popup tells the user to sign in via Settings.

## Flow — download a resource (Download tab)
URL prefilled from the active tab. On Download → `send('startDownload')`:
```
POST {obsidianApi}/download {url}   → { id, status, … }     (backend → embedder)
poll GET {obsidianApi}/download/{id} every 1s → progress/speed/eta → done|error
```
- The yt-dlp downloader now lives **in the embedder** (`embedder/download/`), salvaged from the deleted VideoManager app. The embedder is loopback-only, so the Java backend proxies it: `CaptureController` `POST /api/download` + `GET /api/download/{id}`. The extension only ever talks to the one backend (`obsidianApi`). To change poll cadence: `popup.js` `setInterval(…, 1000)`.
- Playlists: yt-dlp expands a playlist URL into N downloads server-side; the job reports the last file. Files land in the embedder's `DOWNLOAD_DIR` (host `${HOST_DOWNLOAD_PATH:-./downloads}`).

## Flow — auth + config (Settings tab)
`config.js` holds editable endpoints in `chrome.storage.local`. Sign-in → `send('login')` → `POST /login` (form-encoded) sets the Spring session cookie for `obsidianApi`'s origin; subsequent calls reuse it. `refreshAuthLine()` hits `GET /me`.

## Technology Notes (constraints / failure modes)
- **Self-signed cert blocks the extension.** A background `fetch` to the local `:8443` self-signed cert fails (no UI to accept the cert). Default `obsidianApi` is therefore the **Cloudflare tunnel domain** (real cert). For same-machine dev, point it at the Vite proxy `http://localhost:8082`. Set in ⚙ Settings.
- **Session cookie, not a token.** Auth relies on the Spring session cookie surviving in the extension's fetch context for the `obsidianApi` origin. If the backend sets `SameSite=Strict`/`Secure` in a way the extension origin can't carry, login will appear to succeed but calls 401. The tunnel (HTTPS) is the reliable path. CSRF is disabled server-side (`SecurityConfig`), so form-login + JSON writes work without a token.
- **`host_permissions` are broad** (`http://localhost/*`, `https://*/*`) so the configurable endpoints work. Tighten to your exact hosts before sharing the extension with anyone.
- **No icons declared** (optional in MV3) → Chrome shows a default puzzle icon. Add `icons` + `action.default_icon` (PNG; SVG is unreliable for the toolbar) when you want branding.
- **`chrome.scripting` can't read every page** (`chrome://`, the Web Store, PDF viewer) → selection prefill silently skips; the note still saves.
- **Downloader rides on the backend now.** Downloads go through `obsidianApi` → backend → embedder; there's no separate downloader service/port to configure. If the embedder is down the backend proxy returns 502 and the popup shows "can't reach downloader". Large playlists fill `DOWNLOAD_DIR` (no quota guard).
- **Vanilla, not the shared React components.** This is a deliberate scope cut for a zero-build prototype; it duplicates the design tokens (`popup.css`) and the API shapes. If those backend contracts change, update `background.js`. The `@crxjs` React reuse path is the documented upgrade.
- **Manifest V3 / Chromium only** here. Firefox needs `browser_specific_settings` + an `action`/`browser_action` shim; not done.

## Change Index
| Touch this | Where |
|---|---|
| Default endpoint | `config.js` `DEFAULTS.obsidianApi` |
| API base override | popup ⚙ Settings → persisted in `chrome.storage.local` |
| Note template / layout | `background.createNote()` `parts` array |
| Filename sanitization | `background.sanitizeName()` |
| Add a backend action | `background.HANDLERS` + `popup.send('name')` |
| Download poll cadence | `popup.js` `setInterval(pollJob, 1000)` |
| Download endpoint | backend `capture/CaptureController` `/download` → embedder `embedder/download/` |
| Permissions / hosts | `manifest.json` `permissions` + `host_permissions` |
| Look & feel | `popup.css` (tokens mirrored from `frontend/src/styles/tokens.css`) |
| Icons / branding | `manifest.json` `icons` + `action.default_icon` |
