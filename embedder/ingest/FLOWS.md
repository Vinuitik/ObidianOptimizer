# Ingest Module Flows — Stage 1 (A/V spine)

Files: router.py, extract_av.py, jobs.py
Architecture: architecture_plans/INGEST_AGENT_ARCH.md (stages 2–5 pending)

---

## POST /ingest {ref, force_whisper?} → {id, status, ...}

`main.ingest_submit()`:
```
ingest_router.route(ref) — fail fast (422) on unroutable input
local ref → mcp_server._resolve_in_vault (404 if missing/escaping)
→ jobs.submit() → job id immediately; work runs on the single worker thread
```

`GET /ingest/{id}` → status: QUEUED | RUNNING | DONE | FAILED (+stage, error, bundle_path)
`GET /ingest` → all jobs, newest first.

## Job execution (jobs.py)

```
_worker_loop (daemon thread, MAX 1 concurrent — one model in VRAM at a time)
  → router.route(ref)
  → av/youtube → extract_av.extract() → bundle dict
  → bundle saved to {MODEL_CACHE}/ingest_bundles/{id}.json   ← stage 2 reads this
  → pdf/web/image → NotImplementedError (stages 3+ / existing image pipeline)
```
Jobs are in-memory — container restart loses status (bundle files survive).

## extract_av.extract(ref, resolved_path, force_whisper)

```
local file: ffmpeg -vn → 16kHz mono wav (tempfile)
  → faster-whisper (WHISPER_MODEL, int8, cuda if ctranslate2 sees it else cpu)
  → segments [{loc:{t_start,t_end}, text}] → model freed (gc) before return
YouTube URL: POST {VIDEOMANAGER_URL}/api/v1/subs {url} → VTT
  → parse_vtt(): tag-strip + rolling-window dedupe (auto-subs repeat lines)
  → no VIDEOMANAGER_URL / no captions → loud failure
  → force_whisper for YouTube [NOT IMPLEMENTED — needs download path]
```

To change whisper model: `WHISPER_MODEL` env (docker-compose)
To change ffmpeg timeout: `FFMPEG_TIMEOUT_S` env
To change routing: `router.py → ROUTE_TABLE`

---

## Technology Notes

- **faster-whisper pulls CPU onnxruntime** (for its VAD) which clobbers
  onnxruntime-gpu files — Dockerfile force-reinstalls `onnxruntime-gpu` last.
  If embeddings fall back to CPU after a rebuild, check this first (`GET /health` → device).
- **ctranslate2 pinned <4.5**: 4.5+ requires cuDNN 9; base image is cudnn8.
- **Whisper weights** download on first job (~1.5GB for distil-large-v3) into the
  `/models` volume; first job on a fresh volume needs network + several minutes.
- **In-memory jobs dict**: restart loses job *status* but not extracted bundles.
- **VTT dedupe is prefix-based**: cues that repeat with mid-line edits (rare) survive as near-duplicates.

---

## Change Index

| Thing to change | Where |
|---|---|
| Routing rules | `router.py → ROUTE_TABLE` |
| Whisper model / device | `WHISPER_MODEL` env / `extract_av._pick_device()` |
| Job concurrency | `jobs.py` (single worker thread by design) |
| Bundle storage | `jobs.BUNDLE_DIR` (`{MODEL_CACHE}/ingest_bundles`) |
| Captions language | `extract_av._youtube_captions` → VideoManager `lang` param |
| VideoManager URL | `VIDEOMANAGER_URL` env (compose) |
| Subs endpoint (VideoManager side) | `VideoManager/backend/routers/download_poll.py → /subs` |
