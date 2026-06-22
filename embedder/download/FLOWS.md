# Download — FLOWS
Files: downloader.py, jobs.py, ../main.py (endpoints), ../ingest/extract_av.py (caller)

> yt-dlp captions + offline media download. Salvaged in-process from the former
> VideoManager sister app (which is now deleted from this repo) — only the yt-dlp
> core came over; the agent/RAG/MCP/Playwright escalation half did NOT.

## Two consumers
1. **Ingest captions fast-path** — `ingest/extract_av._youtube_captions()` → `downloader.fetch_subs(url)` → VTT → `parse_vtt`. force_whisper → `downloader.fetch_audio()` → `_whisper_transcribe`.
2. **Offline download feature** — browser extension → backend `CaptureController /download` (proxy) → embedder `POST /download` → `jobs.submit()`.

## Flow — captions (no download, synchronous, seconds)
`extract_av._youtube_captions(url)` → `downloader.fetch_subs(url, lang='en')`
```
yt-dlp {skip_download, writesubtitles, writeautomaticsub, subtitleslangs:[lang], subtitlesformat:vtt}
  → tmp/subs.*.vtt → {vtt, title, duration_s}   (raises if no .vtt produced)
```
To change caption language: `fetch_subs(url, lang=…)`. Manual subs preferred over auto (both requested; yt-dlp picks manual first).

## Flow — download (async, minutes)
`POST /download {url}` (main.py) → `jobs.submit(url)`
```
jobs.submit → make job {status:queued} → spawn daemon thread → public_view snapshot returned
thread → downloader.download_sync(url, hook)
  hook(d): status downloading → job.progress/speed/eta (via parse_progress)
           status finished    → job.filename
  success → job.status=done, progress=100   |   exception → job.status=error, job.error
GET /download/{id} → jobs.get(id)   (poll; status queued|downloading|done|error)
GET /download      → jobs.list_jobs()
```
- One thread per download (no concurrency cap — downloads are I/O-bound, not VRAM-bound like whisper). To cap: add a queue/worker like `ingest/jobs.py`.
- Playlists: `download_sync` expands them server-side and reports the LAST entry's file (`info["entries"][-1]`).
- Output dir: `DOWNLOAD_DIR` env (default `/workspace`, bind-mounted in `docker-compose.yml` → host `${HOST_VAULT_PATH}/_workspace`). Downloaded media therefore lands in the vault's `_workspace/` and shows up directly in the Learn page (same dir `WorkspaceController /workspace/files` lists and nginx serves). The old standalone `./downloads` mount was removed.
- Format/quality: `downloader.build_ydl_opts()` `format` key.

## Technology Notes (constraints / failure modes)
- **No agent escalation.** VideoManager fell back to a headless-browser + RAG agent when yt-dlp failed (login walls, JS-only streams). That heavy half (Ollama/Chroma/Playwright/MCP) was deliberately NOT salvaged. Here a yt-dlp failure is just `status:error` — fine for YouTube/MIT OCW/most uni lecture portals, which yt-dlp handles directly. Sites needing auth/scraping are out of scope now.
- **In-memory jobs.** `jobs._jobs` is a process-local dict — a container restart drops in-flight + finished jobs. Accepted: downloads are idempotent and re-triggerable (same as `ingest/jobs.py`). No persistence.
- **No auth on the embedder endpoints.** `/download`, `/subs` sit on the loopback-only embedder (port bound to 127.0.0.1 in compose) and are NOT behind the MCP `X-API-Key` (that guards only the `/mcp` mount). The browser-facing trust boundary is the Java `CaptureController` proxy, which IS session-authed. Never expose the embedder port publicly.
- **ffmpeg dependency.** `download_sync` merges bestvideo+bestaudio → mp4 via ffmpeg; `fetch_audio` + whisper also need it. ffmpeg is installed in the embedder image (already used by `extract_av._to_wav`).
- **Disk + quota.** `DOWNLOAD_DIR` is unbounded — a big playlist can fill the host disk. No cleanup/rotation. Unlike the URL-save path (`WorkspaceController /workspace/save`, 512 MB cap), yt-dlp has **no size guard**, and it now writes straight into the vault's `_workspace/` — a big playlist piles into the vault. The old `videos` list/delete endpoint was NOT salvaged; manage files on the host.
- **yt-dlp staleness.** YouTube breaks extractors periodically; bump `yt-dlp` in `requirements.txt` when downloads start failing site-wide.

## Change Index
| Touch this | Where |
|---|---|
| Download format / quality | `downloader.build_ydl_opts()` `format` |
| Output path / filename | `downloader.build_ydl_opts()` `outtmpl`; `DOWNLOAD_DIR` env |
| Caption language | `downloader.fetch_subs(url, lang=…)` |
| Progress fields | `jobs._run()` hook + `downloader.parse_progress()` |
| Concurrency model | `jobs.submit()` (thread-per-job → swap for a worker queue) |
| Endpoints | `../main.py` (`/download`, `/download/{id}`, `/subs`) |
| Browser-facing proxy | Java `capture/CaptureController` (`POST /api/download`, `GET /api/download/{id}`) |
| Host download folder | `docker-compose.yml` embedder `${HOST_VAULT_PATH}/_workspace:/workspace` + `DOWNLOAD_DIR=/workspace` |
| yt-dlp version | `requirements.txt` `yt-dlp` |
