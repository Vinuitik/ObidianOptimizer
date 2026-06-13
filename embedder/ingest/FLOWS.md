# Ingest Module Flows — resource → notes

Files: router.py, extract_av.py, extract_pdf.py, extract_web.py, keyframes.py, clip_onnx.py, bundle.py, synthesize.py, publish.py, split_note.py, jobs.py
Architecture: architecture_plans/INGEST_AGENT_ARCH.md

Two output modes share one extraction+synthesis core:
- **in-place** (the common case): a note holds `![[lecture.mp4]]` → synthesize a
  note and inject it **directly below the embed, in the same file**. The embed is
  kept; the chunker indexes the injected text (the raw A/V/PDF embed is invisible
  to chunking). Fired automatically by Java `ResourceScanService`.
- **standalone**: a bare `{ref}` (e.g. a URL) → create new note(s) via find_home.

---

## Trigger (in-place) — Java → embedder, hands-off

```
note saved (create/update/patch/rename/chrono/sync)
  → ImageScanService.registerImages()           ← universal post-write chokepoint
  → ResourceScanService.scan(absPath, content)
      embedsNeedingIngest(): ![[*.mp4|mp3|pdf|…]] WITHOUT a <!-- ingest:… --> marker
      → POST embedder /ingest {ref: embed, note_path: rel}   (off-thread, best-effort)
```
Idempotent: an embed that already carries its marker is skipped. Re-fires on every
save until the marker lands; the embedder de-dups concurrent (note, embed) jobs.

## POST /ingest {ref, note_path?, embed_ref?, force_whisper?, extract_only?}

`main.ingest_submit()`:
```
in_place = bool(note_path)
target = embed_ref or ref
router.route(target) — 422 on unroutable input
local target → _resolve_embed(): path-as-written, else basename rglob under /vault
in_place → also resolve+verify note_path (404 if missing)
→ jobs.submit(...) → job id immediately; single worker thread runs it
```
`GET /ingest/{id}` → QUEUED | RUNNING | DONE | FAILED (+stage, error, notes_created)
`GET /ingest` → all jobs, newest first (dashboard reads this via StatsController).

`POST /ingest/split-note {note_path}` → synchronous splitter (see split_note.py).

## Job execution (jobs.py)

```
_worker_loop (daemon, MAX 1 concurrent — one model in VRAM at a time)
  → router.route(ref)
  → av/youtube → extract_av.extract() → bundle; video also → _attach_keyframes()
  → pdf → extract_pdf.extract();  web → extract_web.extract()
  → image → NotImplementedError (single images use the existing image pipeline)
  → bundle persisted to {MODEL_CACHE}/ingest_bundles/{id}.json
  → extract_only? stop here.
  → note_path set?  _synthesize_and_inject()   else  _synthesize_and_publish()
```
Jobs are in-memory — restart loses status (bundle files survive).

### _synthesize_and_inject (in-place)
```
_store_media(bundle)                    → keyframes/PDF figures via Java /api/internal/media
synthesize.outline(bundle)              → N plans (split into sections allowed)
synthesize.build_inplace_body(...)      → ONE block: ## per plan, media by loc, 1 source footer
publish.validate_embeds(block, stored)  → produced media must resolve
read note via _resolve_in_vault         → publish.inject_block(content, embed, block, sha)
publish.update_note(note_path, new)     → Java PUT /api/internal/notes (re-indexes, re-syncs)
```

### _synthesize_and_publish (standalone)
```
_store_media → outline → write_note() per plan (frontmatter + sr fields + #review)
→ publish.validate_note → find_home → publish.create_note (new file in find_home folder)
one bad note never sinks its siblings; all-fail raises.
```

## Extractors (deterministic, zero LLM)

```
extract_av : ffmpeg -vn 16kHz wav → faster-whisper (WHISPER_MODEL int8); YouTube → VideoManager /subs VTT
extract_pdf: PyMuPDF text blocks; <20 words/page → Tesseract OCR; images >200px → _keep_diagrams()
extract_web: trafilatura markdown, per-heading segments; <200 chars → loud fail (SPA)
keyframes  : scene-cut + 1/15s + transcript-cue candidates → CLIP keep/drop → CLIP dedupe → ≤MAX_FRAMES
```
`extract_pdf._keep_diagrams()` drops logos/headshots via `keyframes.diagram_keep_mask()`
(same CLIP KEEP/DROP prompts as keyframes) — keeps real figures/charts only.

CLIP runs through `clip_onnx.py` — **pure onnxruntime, no torch/open_clip**.
`encode_text()` / `encode_image()` load pre-exported ViT-L/14 ONNX from the Hub
(`CLIP_ONNX_REPO`) once, cache the sessions, and return L2-normalised features;
callers dot-product them. GPU via CUDAExecutionProvider, CPU fallback automatic.

## synthesize.py — the ONLY LLM in the pipeline

```
outline(bundle)  : numbered segments → JSON note plans (schema-validated, retries ≤ 2)
_write_body()    : one WRITE call → markdown body (shared by write_note + build_inplace_body)
build_inplace_body(): plans → ## sections + media + source footer, NO frontmatter
assemble()       : standalone note wrapper (frontmatter, sr-due/interval/ease, #review)
```
All `/complete` calls go through host-wrapper (free providers first, claude-cli last).

## publish.py — write-through (all vault writes via Java internal API)

```
inject_block(content, embed, body, sha): insert/replace block below the embed line;
    marker <!-- ingest:<base> sha=… --> … <!-- /ingest:<base> --> (HTML comment = chunker-stripped)
validate_embeds / validate_note : produced ![[…]] must resolve to stored media
store_media / create_note / update_note : POST|PUT /api/internal/* with X-Internal-Token
find_home : mcp_server.find_home_for_note → folder, else INGEST_DEFAULT_FOLDER
```

---

## Technology Notes

- **In-place placement, not a separate note**: the synthesized block lives below
  the embed in the host note. The marker is an HTML comment so it renders
  invisibly AND is stripped by `MarkdownPreprocessor` (Java) along with the
  `![[video.mp4]]` embed itself — so only the synthesized prose + keyframe images
  reach the embedder. Re-ingest detection in v1 is **marker presence only**; the
  `sha=` in the marker is recorded for future "resource changed → re-ingest" but
  that auto-diff is [NOT IMPLEMENTED] — to force a re-run, delete the marker block.
- **Trigger is off-thread + best-effort**: `ResourceScanService` POSTs on a daemon
  single-thread pool with short timeouts and swallows all errors. A down/slow
  embedder never blocks a note write; the next save or a restart re-fires.
- **Embedder de-dups (note, embed) jobs** while QUEUED/RUNNING — required because
  the scanner re-fires on every save until the marker is written back.
- **Keyframes/PDF figures are embedded raw** (no caption at ingest). The existing
  `pending_image_jobs` pipeline captions/indexes them lazily — `ImageScanService`
  picks up the injected `![[frame.jpg]]` because they carry image extensions, while
  `![[lecture.mp4]]` does not, so the source embed never enters the image pipeline.
- **faster-whisper pulls CPU onnxruntime** (VAD) which clobbers onnxruntime-gpu —
  Dockerfile force-reinstalls `onnxruntime-gpu` last. `ctranslate2<4.5` (cuDNN 8).
- **Pure-ONNX inference, no torch/optimum/open_clip.** Both the text embedder
  (`model_runtime.py`) and CLIP (`clip_onnx.py`) run pre-exported ONNX as raw
  `onnxruntime.InferenceSession`s. This was deliberate: the torch CUDA wheel
  shipped its own ~3-4GB nvidia-* CUDA pip stack (cuDNN/cuBLAS/…) **on top of**
  the base image's CUDA — a duplicate download that pushed the image build to ~1h.
  Removing it cuts the build to ~10min and loses nothing, because onnxruntime-gpu
  already uses the base image's CUDA. Trade-off: if a future model has **no**
  pre-exported ONNX on the Hub, there is no longer an in-process exporter — export
  it offline (optimum-cli) and host the ONNX, or point `EMBED_ONNX_FILE` at it.
- **CLIP sessions are cached, not freed per job.** `clip_onnx._state` holds the
  ViT-L/14 sessions for the process lifetime (loaded lazily on first use). The text
  embedder (~650MB fp16) and CLIP (~1.2GB fp32) co-reside in VRAM — fine on a normal
  GPU; on a tiny one this could pressure memory (the old open_clip path freed CLIP
  after each job). To revert to load→free, clear `clip_onnx._state` after use.
- **In-memory jobs dict**: restart loses job *status* but not extracted bundles.

---

## Change Index

| Thing to change | Where |
|---|---|
| Resource embed extensions (trigger) | `ResourceScanService.RESOURCE_EMBED` (Java) |
| Ingest marker format | `publish.inject_block()` |
| In-place vs standalone branch | `jobs._run()` (`note_path` set ⇒ in-place) |
| (note, embed) job de-dup | `jobs.submit()` |
| Embed → file resolution | `main._resolve_embed()` (basename rglob fallback) |
| Routing rules | `router.py → ROUTE_TABLE` |
| Whisper model / device | `WHISPER_MODEL` env / `extract_av._pick_device()` |
| PDF diagram keep/drop | `keyframes.KEEP_PROMPTS / DROP_PROMPTS`, `extract_pdf._keep_diagrams()` |
| Keyframe tuning | `keyframes.py → SCENE_THRESHOLD / MAX_FRAMES / CLIP_SIM_THRESHOLD / CUE_PATTERNS` |
| CLIP model / ONNX source | `clip_onnx.py → CLIP_ONNX_REPO / CLIP_HF_MODEL` env |
| Text embed model / ONNX file | `model_runtime.py → EMBED_MODEL / EMBED_ONNX_FILE` env |
| Outline/write prompts | `synthesize.py → OUTLINE_PROMPT / WRITE_PROMPT` |
| Chunk window size | `bundle.py → WINDOW_TOKENS` (`INGEST_WINDOW_TOKENS` env) |
| Default standalone folder | `INGEST_DEFAULT_FOLDER` env |
| Synthesis model (claude-cli only) | `SYNTH_MODEL` env |
| VideoManager URL / subs endpoint | `VIDEOMANAGER_URL` env / `VideoManager …/subs` |
