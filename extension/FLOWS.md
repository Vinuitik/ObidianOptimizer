# Browser Extension — FLOWS
Files: manifest.json, manifest.firefox.json, background.js, popup.html, popup.css, popup.js, config.js, onboarding.html, ../linux_scripts/build-firefox-extension.sh

> One job: **capture anything worth reviewing into the Learn queue** without opening
> the app. Paste text/markdown/a URL, drop a file, or right-click a selection/link/page.
> The extension figures out the type and routes it so it ends up in the Learn page —
> media to watch/read in `_workspace/`, generated notes in the Inbox to triage.
> Deliberately **vanilla** (no React build) so it loads unpacked with zero `npm install`.

## Load it
- **Chromium** (Chrome/Edge/Brave): `chrome://extensions` → Developer mode → **Load
  unpacked** → select this `extension/` folder.
- **Firefox**: `bash ../linux_scripts/build-firefox-extension.sh` → `about:debugging`
  → **Load Temporary Add-on** → `extension-firefox/manifest.json` (FF 121+).

On first install a welcome tab (`onboarding.html`) opens explaining how to pin the
icon + sign in. Then open the popup → ⚙ Settings → sign in.

## Cross-browser (Chromium + Firefox)
One codebase, one diverging file: the **background declaration**. Chrome MV3 requires
`background.service_worker` (`manifest.json`); Firefox MV3 uses an event page
`background.scripts` (`manifest.firefox.json`). `build-firefox-extension.sh` copies
`extension/` → `extension-firefox/` with the Firefox manifest dropped in as
`manifest.json`. The JS is shared verbatim: `config.js` exports `api = browser ?? chrome`,
so every `await api.*` call is promise-based in both engines. To add a WebExtension API
call, use `api.*` (imported from `config.js`), never `chrome.*` directly.

## Architecture (why a background worker)
```
popup.js (UI) ─┐
context menu  ─┼─ api.runtime / onClicked ──▶ background.js ──fetch──▶ backend
               ┘                              (routeText / uploadFile)
```
All `fetch` lives in `background.js`: it runs with the extension's `host_permissions`,
so the page's CORS/CSP/mixed-content rules don't apply, and the ObsidianOptimizer session
cookie is sent (`credentials:'include'`). The popup never calls the network directly.

## Flow — capture (the one box)
`popup.js submit()` (or a context-menu click) → `send('routeText', {text})` →
`background.routeText(text)` classifies and routes:

| Input (`classify()`) | Route | Lands in |
|---|---|---|
| Plain text / markdown | `POST /notes` create+update (sr-due + #review) | a note in review |
| Web page URL | `POST /capture` → embedder ingest | synthesized note → **Inbox** |
| Media-file URL (`.pdf/.mp4/.mp3…`) | `POST /capture` **and** `POST /workspace/save` | notes + watchable file |
| Video platform (youtube/vimeo/…) | `POST /capture` **and** `POST /download` (yt-dlp) | notes + offline video |

- Dropped file: `popup.js` reads it to base64 → `send('uploadFile')` →
  `background.uploadFile()` → `POST /workspace/upload` (multipart) → then `POST /capture`
  on the returned `_workspace/<file>` path so it's both viewable AND ingested.
- **Fire-and-forget**: the popup shows "queued ✓" with a detected-type detail line and
  does NOT poll. Long jobs (ingest/whisper, yt-dlp) finish server-side; results show up
  in Learn. To change routing rules: `background.classify()` + `routeText()`.
- The live "what will happen" hint under the textarea: `popup.detectLabel()` (mirrors
  `classify()` — keep them in sync).

## Flow — context menu (no popup)
`background.installMenus()` (on install + startup) creates 3 items (selection / link /
page). `contextMenus.onClicked` → `routeText(selectionText|linkUrl|pageUrl)` →
`flashBadge()` paints a ✓/! on the toolbar icon (fire-and-forget feedback, no popup).

## Flow — auth + config (⚙ Settings)
`config.js` holds the one editable endpoint (`obsidianApi`, default
`https://obsidianoptimizer.uk/api`) in `chrome.storage.local`. Sign-in →
`send('login')` → `POST /login` (form-encoded) sets the Spring session cookie;
`refreshAuthLine()` hits `GET /me`.

## Technology Notes (constraints / failure modes)
- **Cross-browser: done.** Chromium (service worker) + Firefox 121+ (event page via the
  build script + `browser_specific_settings`) both supported. Earlier docs said
  "Chromium only" — that is no longer true.
- **Self-signed cert blocks the extension.** A background `fetch` to the local `:8443`
  self-signed cert fails (no UI to accept it). Default `obsidianApi` is the Cloudflare
  tunnel domain (real cert). For same-machine dev, point it at the Vite proxy
  `http://localhost:8082` in ⚙ Settings.
- **Session cookie, not a token.** Auth relies on the Spring session cookie surviving in
  the extension's fetch context for the `obsidianApi` origin. The tunnel (HTTPS) is the
  reliable path. CSRF is disabled server-side (`SecurityConfig`).
- **`host_permissions` are broad** (`http://localhost/*`, `https://*/*`) so the
  configurable endpoint works. Tighten to your exact hosts before sharing.
- **Media is fetched twice for video-platform/media URLs** (once by ingest to
  transcribe, once kept as a watchable file). Accepted for a personal tool; it's the
  cost of "notes AND a copy to watch."
- **Upload size cap is the backend's**, not the extension's: Spring multipart is 100MB
  (`application.properties`), below WorkspaceController's 512MB URL-save cap. A dropped
  file >100MB is rejected by Spring before the controller runs.
- **No keyboard shortcut** (deliberate — context menu + popup cover it). Add a
  `commands` block to both manifests if you want one.
- **Vanilla, not the shared React components** — duplicates design tokens (`popup.css`)
  and API shapes. If the backend contracts change, update `background.js`.

## Change Index
| Touch this | Where |
|---|---|
| Default endpoint | `config.js` `DEFAULTS.obsidianApi` |
| API base override | popup ⚙ Settings → `chrome.storage.local` |
| Input classification / routing | `background.classify()` + `routeText()` |
| Live detection hint | `popup.detectLabel()` (mirror of `classify()`) |
| Raw-text note template | `background.saveText()` |
| File-upload path | `background.uploadFile()` → backend `/workspace/upload` |
| Context-menu items | `background.MENU` + `installMenus()` |
| Onboarding copy | `onboarding.html` |
| Icons / branding | `icons/` + `manifest*.json` `icons` / `action.default_icon` |
| Permissions / hosts | `manifest*.json` `permissions` + `host_permissions` |
| Look & feel | `popup.css` (tokens mirrored from `frontend/src/styles/tokens.css`) |
