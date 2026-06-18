# Obsidian Optimizer — Browser Extension

A tiny Manifest V3 extension with two buttons:

1. **New note** — clip the current page (title + your selection) straight into the
   vault as a note that enters FSRS review, without opening the app.
2. **Download** — queue a YouTube video / MIT OCW playlist / uni lecture for offline
   viewing. yt-dlp runs inside the embedder; the backend proxies it.

## Install (unpacked)

**Chrome / Edge / Brave** (Chromium, uses `manifest.json` with a service-worker background):
1. `chrome://extensions` → enable **Developer mode**.
2. **Load unpacked** → select this `extension/` folder.
3. Reload the extension (↻ on its card) after editing files.

**Firefox** (MV3 uses an event-page background, so it needs a different manifest):
1. From the repo root run `pwsh ./build-firefox-extension.ps1` → generates `extension-firefox/`.
2. `about:debugging#/runtime/this-firefox` → **Load Temporary Add-on** → select
   `extension-firefox/manifest.json`. (Temporary = removed on Firefox restart; re-run
   the script + reload after edits.) Needs Firefox 121+.

The JavaScript is identical for both — it uses a `browser ?? chrome` shim
(`config.js`) so the promise-based APIs work in either engine. Only the manifest's
background declaration differs.

Then in either browser, click the toolbar icon → **⚙ Settings**:
- Set **ObsidianOptimizer API base** (default: the tunnel domain — see note below).
- **Sign in** with your app credentials.

Both features talk to this one backend. Downloads are handled by yt-dlp **inside the
embedder** (`embedder/download/`), reached through the backend's `/download` proxy —
no separate downloader service to run. Files land in the embedder's `DOWNLOAD_DIR`
(host `${HOST_DOWNLOAD_PATH:-./downloads}`).

## Why the tunnel domain by default
Service workers / extension fetches can't accept the local stack's **self-signed**
`:8443` certificate, so the default points at the Cloudflare tunnel (real cert).
For same-machine dev against the Vite proxy, set the API base to `http://localhost:8082`.

See `FLOWS.md` for architecture, data flows, and the full constraints list.
