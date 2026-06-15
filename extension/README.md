# Obsidian Optimizer — Browser Extension

A tiny Manifest V3 extension with two buttons:

1. **New note** — clip the current page (title + your selection) straight into the
   vault as a note that enters FSRS review, without opening the app.
2. **Download** — queue a YouTube video / MIT OCW playlist / uni lecture for offline
   viewing via the VideoManager *lite* downloader (yt-dlp).

## Install (unpacked)
1. `chrome://extensions` → enable **Developer mode**.
2. **Load unpacked** → select this `extension/` folder.
3. Click the toolbar icon → **⚙ Settings**:
   - Set **ObsidianOptimizer API base** (default: the tunnel domain — see note below).
   - Set **VideoManager (lite) base** (default `http://localhost:8001`).
   - **Sign in** with your app credentials.

## Run the downloader it talks to
```
cd ../VideoManager
docker compose -f docker-compose.lite.yml up --build   # serves on http://localhost:8001
```
This is the lightweight half of VideoManager — yt-dlp only, no Ollama/Chroma/agent.

## Why the tunnel domain by default
Service workers / extension fetches can't accept the local stack's **self-signed**
`:8443` certificate, so the default points at the Cloudflare tunnel (real cert).
For same-machine dev against the Vite proxy, set the API base to `http://localhost:8082`.

See `FLOWS.md` for architecture, data flows, and the full constraints list.
