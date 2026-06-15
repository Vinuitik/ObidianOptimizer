# Browser Extension — FLOWS
Files: manifest.json, background.js, popup.html, popup.css, popup.js, config.js

> MV3 extension with two jobs: (1) clip a note to the vault from any page, (2) queue
> a video/playlist for offline download. Deliberately **vanilla** (no React build) so
> it loads unpacked with zero `npm install` — it reuses the backend API *contracts*
> and the app's design *tokens*, not the React component tree. Upgrade path to the
> shared-component approach is in `architecture_plans/EXTENSION_ARCH.md` (@crxjs).

## Load it
`chrome://extensions` → Developer mode → **Load unpacked** → select this `extension/` folder.
Then open the popup → ⚙ Settings → set endpoints + sign in.

## Architecture (why a background worker)
```
popup.js (UI)  ──chrome.runtime.sendMessage──▶  background.js (service worker)  ──fetch──▶  backend
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
POST {videoApi}/api/v1/download {url}  → { job_id }
poll GET {videoApi}/api/v1/jobs/{id}   every 1s → progress/speed/eta → done|error
```
- Talks to the **VideoManager LITE** downloader (`../VideoManager/docker-compose.lite.yml`, host :8001) — yt-dlp only, no agent. To change poll cadence: `popup.js` `setInterval(…, 1000)`.
- Playlists: yt-dlp expands a playlist URL into N downloads server-side; the job reports the last file. Files land in VideoManager's `VIDEOS_DIR` (`C:/Users/ACER/Desktop/YT-Videos`).

## Flow — auth + config (Settings tab)
`config.js` holds editable endpoints in `chrome.storage.local`. Sign-in → `send('login')` → `POST /login` (form-encoded) sets the Spring session cookie for `obsidianApi`'s origin; subsequent calls reuse it. `refreshAuthLine()` hits `GET /me`.

## Technology Notes (constraints / failure modes)
- **Self-signed cert blocks the extension.** A background `fetch` to the local `:8443` self-signed cert fails (no UI to accept the cert). Default `obsidianApi` is therefore the **Cloudflare tunnel domain** (real cert). For same-machine dev, point it at the Vite proxy `http://localhost:8082`. Set in ⚙ Settings.
- **Session cookie, not a token.** Auth relies on the Spring session cookie surviving in the extension's fetch context for the `obsidianApi` origin. If the backend sets `SameSite=Strict`/`Secure` in a way the extension origin can't carry, login will appear to succeed but calls 401. The tunnel (HTTPS) is the reliable path. CSRF is disabled server-side (`SecurityConfig`), so form-login + JSON writes work without a token.
- **`host_permissions` are broad** (`http://localhost/*`, `https://*/*`) so the configurable endpoints work. Tighten to your exact hosts before sharing the extension with anyone.
- **No icons declared** (optional in MV3) → Chrome shows a default puzzle icon. Add `icons` + `action.default_icon` (PNG; SVG is unreliable for the toolbar) when you want branding.
- **`chrome.scripting` can't read every page** (`chrome://`, the Web Store, PDF viewer) → selection prefill silently skips; the note still saves.
- **Downloader must be running and reachable.** The popup surfaces "can't reach downloader" if `:8001` is down. The downloader binds localhost by default — to download from another network, expose it (tunnel) and update `videoApi`.
- **Vanilla, not the shared React components.** This is a deliberate scope cut for a zero-build prototype; it duplicates the design tokens (`popup.css`) and the API shapes. If those backend contracts change, update `background.js`. The `@crxjs` React reuse path is the documented upgrade.
- **Manifest V3 / Chromium only** here. Firefox needs `browser_specific_settings` + an `action`/`browser_action` shim; not done.

## Change Index
| Touch this | Where |
|---|---|
| Default endpoints | `config.js` `DEFAULTS` |
| API base override | popup ⚙ Settings → persisted in `chrome.storage.local` |
| Note template / layout | `background.createNote()` `parts` array |
| Filename sanitization | `background.sanitizeName()` |
| Add a backend action | `background.HANDLERS` + `popup.send('name')` |
| Download poll cadence | `popup.js` `setInterval(pollJob, 1000)` |
| Downloader endpoint | `config.js` `videoApi` (VideoManager lite :8001) |
| Permissions / hosts | `manifest.json` `permissions` + `host_permissions` |
| Look & feel | `popup.css` (tokens mirrored from `frontend/src/styles/tokens.css`) |
| Icons / branding | `manifest.json` `icons` + `action.default_icon` |
