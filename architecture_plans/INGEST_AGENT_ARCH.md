# Resource → Notes Ingest Agent — Architecture Plan

Files: embedder/ingest/{router,extract_av,extract_pdf,extract_web,keyframes,bundle,synthesize,publish,split_note,jobs}.py; Java com.obsidian.obsidian.ml.ResourceScanService, com.obsidian.obsidian.internalapi.InternalAgentController. Flows: embedder/ingest/FLOWS.md.

**STATUS (2026-06-13): IMPLEMENTED, stages 1–5.** One amendment to the plan below:
placement is **in-place injection**, not standalone notes (see "In-place placement").

**Core principle: pipeline-first, agent-last.** Extraction is 100% deterministic code (no LLM in the loop). The LLM appears only at the final synthesis step, constrained to schema-validated JSON with a capped retry budget. No open-ended agent loop, no tool browsing.

```
resource → Router → Extractor (deterministic) → Extraction Bundle (JSON contract)
        → Outline pass (LLM, 1 call) → Note writer (LLM, 1 call per note)
        → Validator (deterministic) → find_home_for_note → Java backend create_note
```

---

## Stage 1 — Router (deterministic)

Input: file path or URL. Decide by extension / MIME / URL pattern:

| Input | Route |
|---|---|
| `.pdf` | PDF extractor |
| `http(s)://` (non-YouTube) | Web extractor |
| YouTube URL (`youtube.com/watch`, `youtu.be`) | A/V extractor (captions-first) |
| `.mp4 .mkv .webm` | A/V extractor (whisper path; cue check via `.info.json` if yt-dlp-downloaded) |
| `.mp3 .m4a .wav .ogg` | A/V extractor (audio = video minus keyframes) |
| `.jpg .jpeg .png .webp` | Image extractor |

To change routing: `router.py → ROUTE_TABLE` [NOT IMPLEMENTED]

---

## Stage 2 — Extractors (deterministic, local, zero LLM tokens)

### PDF — `extract_pdf.py`
```
PyMuPDF open
  → per page: page.get_text("blocks") → text segments tagged with page number
  → page with no text layer (scanned): render page @150dpi → Tesseract OCR → segment
  → page.get_images() → export embedded images > 200×200px → media entries tagged with page
  → metadata: title from PDF metadata, fallback first-page heading heuristic
```

### URL — `extract_web.py`
```
trafilatura.fetch_url → trafilatura.extract(output="markdown", include_images=True)
  → main-content text (boilerplate/nav/ads stripped) → segments per heading section
  → image URLs in main content → download → media entries
  → title/author/date from trafilatura metadata
```

### Video / Audio — `extract_av.py`
Transcript acquisition, cheapest source first:
```
1. YouTube URL → yt-dlp --write-auto-subs --sub-lang en --skip-download
     → VTT captions (free, instant, timestamped) → parse to segments
     → also --write-info-json for title/chapters; chapters become section hints
2. No captions / local file / audio file → ffmpeg -vn extract 16kHz mono wav
     → faster-whisper (distil-large-v3, int8) → segments with start/end timestamps
3. YouTube captions exist BUT content is jargon-heavy (auto-subs garble technical
   terms): optional re-transcribe with whisper anyway — `FORCE_WHISPER=1` per job.
   Time is cheap (overnight jobs OK), quality is the priority.
```
Audio files stop here. Video continues to keyframes.

### Keyframes — `keyframes.py` (candidates → CLIP filter → dedupe)

Design: candidate generation is deliberately high-recall and sloppy; a pretrained
CLIP model does the precision work (semantic filtering + dedupe). This is the
standard published approach (LMSKE et al.) — pixel hashing alone cannot tell
"same slide, one more bullet" from "new slide".

```
Stage A — candidate generation (high recall, cheap):
  1. scene cuts: PySceneDetect ContentDetector(threshold=27)
     → take the frame ~0.5s BEFORE each cut (a slide being replaced is
       captured fully built — handles incremental bullet builds)
  2. periodic safety net: 1 frame / 15s (catches slow fades scene detect misses)
  3. transcript cues (the cross-reference; zero tokens, pure regex):
       "look at", "as you can see", "this diagram", "this slide", "on the
       board", "shown here", "this chart", "this graph", "right here", "this figure"
     → frame at cue_timestamp + 1.5s; media entry carries cue_text

Stage B — semantic filter (pretrained CLIP ViT-B/32, ONNX, ~350MB, local):
  embed every candidate frame
  → zero-shot keep/drop via text-prompt cosine:
      KEEP:  "a presentation slide", "a diagram or chart", "a whiteboard with
             writing", "code on a computer screen", "a table of data"
      DROP:  "a person talking to the camera", "an audience", "a room"
  → cue-triggered frames BYPASS the drop filter (speaker said it matters)

Stage C — semantic dedupe:
  sort survivors by timestamp → greedy: drop frame if CLIP cosine > 0.92
  vs any kept frame; within a duplicate run keep the LATEST frame
  (final state of a built-up slide)
  → cap at MAX_FRAMES (default 40), cue-triggered prioritized
  → save as {source-slug}-{t}.jpg
```
To change cue list: `keyframes.py → CUE_PATTERNS`. Scene threshold: `SCENE_THRESHOLD`.
Keep/drop prompts: `KEEP_PROMPTS / DROP_PROMPTS`. Dedupe: `CLIP_SIM_THRESHOLD`. [NOT IMPLEMENTED]

### Image — reuse existing pipeline
Single JPEG → Tesseract OCR locally; if OCR yield is poor (< 20 words), route through existing host-wrapper VLM (see ML_ARCH.md image pipeline). Result is one segment + the image itself as media.

---

## Stage 3 — Extraction Bundle (the contract)

One JSON shape regardless of source. This is what makes synthesis stable — the LLM never sees raw files:

```json
{
  "source": { "type": "video", "ref": "https://youtu.be/…", "title": "…",
              "duration_s": 1880, "chapters": [{"t": 0, "title": "Intro"}] },
  "segments": [
    { "loc": { "t_start": 12.4, "t_end": 31.0 }, "text": "…" },
    { "loc": { "page": 3 }, "text": "…" }
  ],
  "media": [
    { "path": "attachments/lec5-0412.jpg", "loc": { "t": 412.5 },
      "trigger": "cue", "cue_text": "if you look at this diagram" }
  ]
}
```

`loc` is timestamps for A/V, page numbers for PDF, heading anchor for web. Media placement in notes is **deterministic** — interleaved by `loc` against segment ranges, never chosen by the LLM.

---

## Stage 4 — Synthesis (LLM, constrained)

```
bundle → deterministic chunking along segment boundaries (~4k-token windows)

Pass 1 — OUTLINE (one LLM call, map-reduce if bundle > context):
  input: segment texts + media cue_texts (NOT the images — text only, cheap)
  output (JSON, schema-validated):
    { "notes": [ { "title": "…", "segment_ids": […], "media_ids": […],
                   "tags": […], "summary_hint": "…" } ] }
  → this is where "1 or more notes" is decided: one lecture can split into
    N concept notes along chapter/topic boundaries

Pass 2 — WRITE (one LLM call per planned note):
  input: that note's segments + media cue_texts; template enforced in prompt
  output: markdown body only — frontmatter, source link with timestamp/page
    backlinks, and ![[image]] embed positions are injected DETERMINISTICALLY
    around it from the bundle

Retry budget: schema validation failure → re-prompt with the validation error,
max 2 retries, then fail the job loudly. That is the entire "agent loop".
```

Note template (deterministic wrapper):
```markdown
---
source: <url or file ref>
created: <date>
tags: [from outline pass]
---
# <title>
<LLM body, with ![[frame.jpg]] lines inserted at segment boundaries matching media loc>

## Source
<link>  ·  [12:34](url&t=754) style timestamp backlinks for video / p. 3 refs for PDF
```

---

## In-place placement (amends Stage 5) — ✅ DECIDED (user, 2026-06-13)

The primary use case is a note that **embeds a resource**: `![[lecture.mp4]]`,
`![[talk.mp3]]`, `![[paper.pdf]]`. Raw A/V/PDF embeds are invisible to the chunker
(`MarkdownPreprocessor` strips `![[…]]`), so that content never gets embedded. Fix:

```
Java ResourceScanService (hooked into ImageScanService.registerImages — the
universal post-write chokepoint) finds resource embeds without an ingest marker
  → POST embedder /ingest {ref: embed, note_path}
  → extract → bundle → keyframes/figures → synthesize ONE block (## per outline
    plan; split into sections allowed) → inject DIRECTLY BELOW the embed in the
    SAME note (embed kept) wrapped in <!-- ingest:<base> sha=… --> … markers
  → publish.update_note via Java internal API → re-index + re-sync
The injected prose + ![[keyframe.jpg]] images are what the chunker now embeds;
the marker is an HTML comment (invisible render, chunker-stripped). Idempotent:
marker presence = done; the scanner re-fires until the marker lands; the embedder
de-dups concurrent (note, embed) jobs. sha= records the resource hash for a future
"resource changed → re-ingest" auto-diff [NOT IMPLEMENTED — delete marker to re-run].
```

The original Stage-5 standalone path (find_home + create new note) remains for
bare-ref ingest (URLs) and is what `split_note.py` uses. Decision: keep both;
`note_path` present ⇒ in-place, absent ⇒ standalone.

PDF figures additionally pass `keyframes.diagram_keep_mask()` (same CLIP KEEP/DROP
prompts as video keyframes) so logos/headshots are dropped and real diagrams kept —
the user's "extract important diagrams" requirement.

## Stage 5 — Validation & placement (deterministic, standalone path)

```
generated note
  → frontmatter parses, all ![[…]] embeds resolve to written files
  → find_home_for_note (existing MCP tool: embed title → pgvector → folder ranking)
  → POST to Java backend notes API (NOT direct vault write — keeps notes index,
    embedding queue, and sync queue consistent; same reason MCP has no create_note)
  → existing EmbeddingService picks it up → searchable
```

---

## Deployment

Lives in the **embedder container** (already Python + FastAPI + GPU + MCP host):
- new module `embedder/ingest/`, exposed two ways:
  - `POST /ingest {ref, options}` → job id; `GET /ingest/{id}` → status/result (worker thread, jobs are minutes-long)
  - MCP tool `ingest_resource` so Claude/chat clients can trigger it
- new binaries in image: `ffmpeg`; new deps: `pymupdf`, `trafilatura`, `faster-whisper`, `scenedetect[opencv]`, `open_clip` (or CLIP exported to ONNX, same runtime as mxbai), `pytesseract` + tesseract-ocr. yt-dlp is NOT here — downloads/subs go through the VideoManager container (decision 3).
- vault mount must become **read-write for the attachments dir only** (currently read-only `/vault`) — or frames are returned to the Java backend which writes them. Prefer the latter: keeps the "all vault writes go through Java" invariant.
- YouTube *downloads* (when full video needed, not just captions) go through the in-process `embedder/download/` module (yt-dlp salvaged from the removed VideoManager — see decision 3 + `embedder/download/FLOWS.md`); captions-only path needs no download at all.

### Resource policy — sequential, low-priority, overnight-friendly

Time is cheap, VRAM is not. Ingest stages run **sequentially per job, one model
loaded at a time** (load → run stage → unload), so each model gets the full 4GB
minus mxbai-embed's resident 670MB. One ingest job at a time (`MAX_CONCURRENT_JOBS=1`).
"Not at full capacity": ffmpeg/whisper CPU threads capped via `OMP_NUM_THREADS=4`
and docker-compose `cpus:` limit on the embedder, so the machine stays usable
while a job grinds in the background.

VRAM per stage (GTX 1650, 4GB, mxbai 670MB always resident → ~3.3GB free):
| Stage | Model | VRAM |
|---|---|---|
| transcribe | faster-whisper **distil-large-v3** int8 (default) | ~1.5GB |
| transcribe (multilingual) | faster-whisper large-v3 int8 (`WHISPER_MODEL`) | ~2.6GB |
| frame filter/dedupe | CLIP **ViT-L/14** fp16 (default; B/32 as fast option) | ~0.9GB |

---

## Decision points

1. **Synthesis LLM** — ✅ DECIDED (user, 2026-06-11): Claude API, **Haiku 4.5 default** (`claude-haiku-4-5`), Sonnet allowed via `SYNTH_MODEL` env var for hard sources. Budget guard: bundle text only, 2 passes, no images in context. Local LLM ruled out — 4GB GPU writes worse notes than Haiku.
   *AMENDED by user 2026-06-13: NO direct Anthropic API — be resourceful. Synthesis routes through host-wrapper `POST /complete` (full router chain: free providers first, **claude-cli subscription credits** dead last), same as the flashcard agent. Prompts are provider-agnostic; schema validation + retry budget normalizes whichever model the router lands on. `SYNTH_MODEL` applies only when claude-cli is reached.*
2. **Send keyframes to VLM for captions, or embed raw?** — ✅ DECIDED (2026-06-13, recommendation accepted): embed raw, no captions at ingest time. The existing pending_image_jobs pipeline will caption/index them lazily anyway. Zero extra cost, no duplicate path.
3. **Video downloads** — ✅ DECIDED 2026-06-11, **REVERSED 2026-06-16**: originally yt-dlp lived in a separate **VideoManager** container called over HTTP. VideoManager has since been removed; its yt-dlp core was salvaged **in-process** into `embedder/download/` (`downloader.fetch_subs` for the captions-fast-path, `download_sync` for full downloads, exposed at embedder `/subs` + `/download`). yt-dlp IS now in the embedder image. See `embedder/download/FLOWS.md`.

---

## Note splitter — post-factum breakup of oversized notes (added 2026-06-13)

User-triggered harness, NOT part of the ingest pipeline run: an existing note grew
too big → split it into N concept notes + rewrite the original as a hub.

```
POST /ingest/split-note {note_path}   (embedder; also MCP tool split_note)
  → read note from /vault (read-only)
  → MarkdownPreprocessor-style sectioning (headings → segments) — deterministic
  → reuse OUTLINE pass (same prompt/schema as ingest): segments → N note plans
  → reuse WRITE pass per planned note
  → original becomes a hub: title + 1-line summary per child + [[links]] (deterministic template)
  → all writes via Java backend API (create children, PATCH original) — index/sync stay consistent
Guard: refuse if note < SPLIT_MIN_CHARS (default 6000) — splitting small notes is noise.
```

Shares `synthesize.py` outline/write code paths — the splitter is "ingest where the
extractor is the note itself".

---

## Execution plan — v1 stages (2026-06-13)

Priority: user's immediate pain is **audio/video files sitting in notes untranscribed**.

| Stage | Builds | Proves |
|---|---|---|
| **1. A/V spine** | ffmpeg + faster-whisper in embedder image; `ingest/router.py`, `extract_av.py` (local files first, YouTube captions path stubbed to VideoManager), bundle contract, async job API (`POST /ingest`, `GET /ingest/{id}`) | local .mp3/.mp4 → timestamped transcript bundle |
| **2. Synthesis** | `bundle.py` windowing, `synthesize.py` outline+write via host-wrapper `/complete`, `validate.py`, find_home_for_note, create via Java backend | bundle → real vault notes, end to end |
| **3. PDF + web** | `extract_pdf.py` (PyMuPDF + Tesseract fallback), `extract_web.py` (trafilatura) | PDF/article → notes |
| **4. Keyframes** | `keyframes.py` (PySceneDetect → CLIP filter/dedupe), frames returned to Java for vault write | video slides land as ![[frames]] in notes |
| **5. Splitter + UI** | `/ingest/split-note` + MCP tools (`ingest_resource`, `split_note`), dashboard "Video & Resource Queue" card wired to `/ingest` jobs | full loop visible in UI |

VideoManager subs-only endpoint (`POST /api/v1/subs`) lands with stage 1 (it is ~20 lines around existing yt-dlp plumbing).

**GPU caveat (stage 1):** ctranslate2 ≥ 4.5 requires cuDNN 9; the embedder base image is cudnn8. Pin `ctranslate2<4.5` (or run whisper int8 on CPU — acceptable for overnight jobs) — decide at implementation against what actually resolves in the image.

---

## Technology Notes

- **yt-dlp auto-subs**: `--write-auto-subs --skip-download` fetches YouTube's ASR captions in ~2s with no video download. Quality is below Whisper (no punctuation in older videos, misheard jargon) but timestamps are excellent. Auto-subs VTT has overlapping rolling-window duplicates — parser must dedupe consecutive repeated lines.
- **faster-whisper** (CTranslate2): default **distil-large-v3** int8 — near large-v3 quality for English at ~1.5GB VRAM and ~6× the speed of large-v3; clearly better than `small` on technical jargon. English-only; for other languages set `WHISPER_MODEL=large-v3` (int8 ~2.6GB — fits only because models load sequentially). A 1h video ≈ 15–30 min on the 1650 with distil, fine for overnight jobs. Segment timestamps are reliable; word timestamps cost extra compute — not needed here.
- **PySceneDetect ContentDetector**: HSV-histogram frame diff, CPU-only, fast. Benchmarks put it at ~65% shot accuracy vs ~87% for the pretrained TransNetV2 network, and it struggles with gradual transitions (slide fades). Acceptable here ONLY because it's just a candidate generator — the periodic 1/15s sampler backstops its misses and CLIP prunes its false positives. **Upgrade path: TransNetV2** (pretrained, handles fades) if candidate quality proves to matter; not in v1 because the reference repo is TensorFlow — a heavy extra runtime in a torch/ONNX container.
- **CLIP** (pretrained, zero training needed): one model does both jobs — zero-shot frame classification via text prompts (no labeled data) and semantic dedupe via embedding cosine. Default **ViT-L/14** fp16 (~0.9GB, markedly better zero-shot than B/32; affordable because models load sequentially and frame count is small); `CLIP_MODEL=ViT-B/32` as the fast option. This is what makes "slide build with one more bullet" collapse to a single final frame where pHash sees two different images. Threshold 0.92 is a starting point — validate on 2–3 real lecture videos and tune. Also reusable later by the CNN-gatekeeper pipeline (ML_ARCH) as a feature extractor.
- **Cue regex vs a BERT classifier**: regex stays for v1 — cue phrases are a closed, literal class and regex is free and debuggable. If recall disappoints, the upgrade is embedding similarity against cue prototype sentences using the already-running mxbai embedder (no new model), not a fine-tuned classifier.
- **trafilatura**: best-in-class main-content extraction (beats readability/newspaper3k on benchmarks). Fails on heavily JS-rendered SPAs — those return near-empty text; detect `len < 200 chars` and fail loudly rather than producing a junk note. No headless browser in scope.
- **PyMuPDF is AGPL** — fine for a personal project, matters if this ever ships commercially. BSD alternative: pypdfium2 (worse text-block API). Scanned-PDF OCR via Tesseract is mediocre on handwriting/math — those pages route to host-wrapper VLM as fallback, same as images.
- **Tesseract**: solid on screenshots/print, useless on handwriting, poor on dark-mode UI screenshots (invert preprocessing helps: `cv2.bitwise_not` when mean luminance < 100).
- **Cue regex is English-only** as designed. Other languages need their own `CUE_PATTERNS` list — flagged, not implemented.
- **Jobs are long** (whisper on 1h video ≈ 8–10 min on this GPU). Hence async job API, not request-response. Embedder container restart kills in-flight jobs — jobs table is in-memory dict in v1; acceptable, the operation is idempotent and re-triggerable. [NOT IMPLEMENTED: persistent job queue]
- **Synthesis cost shape**: outline + N note writes over text-only bundle ≈ 10–30k input tokens for a 1h lecture. On Haiku 4.5 that is a few cents per lecture; even daily use is dollars per month. Images never enter the LLM context at ingest (cue_text stands in for them) — this is the token-cheap cross-referencing. If a Haiku-written note disappoints, re-run just synthesis with `SYNTH_MODEL=claude-sonnet-4-6` — the bundle is cached, extraction does not repeat.

---

## Change Index

| Thing to change | Where (planned) |
|---|---|
| Routing rules | `ingest/router.py → ROUTE_TABLE` |
| Cue phrase list | `ingest/keyframes.py → CUE_PATTERNS` |
| Scene-change sensitivity | `ingest/keyframes.py → SCENE_THRESHOLD` |
| Max frames per video | `ingest/keyframes.py → MAX_FRAMES` |
| Frame dedupe strictness | `ingest/keyframes.py → CLIP_SIM_THRESHOLD` |
| Frame keep/drop classes | `ingest/keyframes.py → KEEP_PROMPTS / DROP_PROMPTS` |
| Periodic sampling rate | `ingest/keyframes.py → PERIODIC_SAMPLE_S` |
| Cue frame time offset | `ingest/keyframes.py → CUE_OFFSET_S` |
| Whisper model size | `WHISPER_MODEL` env var (docker-compose, embedder; default distil-large-v3) |
| CLIP model size | `CLIP_MODEL` env var (default ViT-L/14) |
| Synthesis model | `SYNTH_MODEL` env var (default claude-haiku-4-5) |
| Force whisper over yt captions | per-job `FORCE_WHISPER` option |
| Job concurrency / CPU cap | `MAX_CONCURRENT_JOBS`, `OMP_NUM_THREADS`, compose `cpus:` |
| Note template / frontmatter | `ingest/synthesize.py → NOTE_TEMPLATE` |
| Outline/write prompts | `ingest/synthesize.py → OUTLINE_PROMPT / WRITE_PROMPT` |
| Synthesis retry budget | `ingest/synthesize.py → MAX_RETRIES` |
| Chunk window size | `ingest/bundle.py → WINDOW_TOKENS` |
| OCR-poor threshold (image → VLM) | `ingest/extract_pdf.py / extract_web.py → MIN_OCR_WORDS` |
| Synthesis LLM | decision point 1 above — env var `SYNTH_BACKEND` |
