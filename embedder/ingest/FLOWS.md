# Ingest Module Flows — resource → notes

Files (v1, live): router.py, extract_av.py, extract_pdf.py, extract_web.py, extract_text.py, keyframes.py, clip_onnx.py, bundle.py, synthesize.py, publish.py, split_note.py, jobs.py
Files (v2, wired behind INGEST_V2 flag, default off): ir.py, extract_ir.py, extract_pdf_ir.py, extract_av_ir.py, segment.py, flagging.py, retention.py, locator.py, pipeline_v2.py (+ jobs.py `_*_v2`, synthesize.py `write_unit_body`/`build_inplace_body_v2`)
Architecture: architecture_plans/INGEST_AGENT_ARCH.md (v1), architecture_plans/INGESTION_V2_FLOWS.md (v2 design)

> **v1 is the default; v2 is wired but flag-gated (`INGEST_V2`, default off).** `jobs._run`
> now branches on `pipeline_v2.v2_enabled()`: OFF → the v1 `outline()`+`{loc}` path (unchanged,
> live); ON → v2 (bundle → SourceIR → `segment()` boundaries → `write_unit_body()` draft per
> Unit → same capture/publish path). The v2 branch is written + unit-tested but **has not run
> against a real PDF/video/LLM in Docker** — flip the flag in a runnable env to smoke-test
> before trusting it. Extraction still emits v1 `{loc}` (bridged to IR at the seam); the native
> `*_ir` extractors are built but not yet the live extract stage. See "## v2 scaffolding".

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
_worker_loop (daemon, MAX 1 concurrent; GPU VRAM arbitrated by ../GPU_MEMORY.md —
              whisper/CLIP claim the slot via gpu_slot.exclusive(), evicting the
              text embedder; freed on idle by _evict_models → gpu_slot.release_ingest)
  → router.route(ref)
  → av/youtube → extract_av.extract() → bundle; video also → _attach_keyframes()
  → pdf → extract_pdf.extract();  web → extract_web.extract()
  → image → NotImplementedError (single images use the existing image pipeline)
  → bundle persisted to {MODEL_CACHE}/ingest_bundles/{id}.json
  → extract_only? stop here.
  → note_path set?  _synthesize_and_inject()   else  _synthesize_and_publish()
```
Jobs are in-memory — restart loses status (bundle files survive).

`synthesize.outline()` writes a debug report (source + segments in, raw LLM per
window, planned notes out) via `embedder/agent_reports.py` →
`$AGENT_REPORTS_DIR/ingest-outline/`. See where a bad split came from without
re-running. (`AGENT_REPORTS_DIR` defaults to `/reports` = vault `_reports/`,
which is in the retrieval ignore lists; `AGENT_REPORTS=off` to disable.)

### _synthesize_and_inject (in-place)
```
_store_media(bundle)                    → keyframes/PDF figures via Java /api/internal/media
synthesize.outline(bundle)              → N plans (split into sections allowed)
synthesize.build_inplace_body(...)      → ONE block: ## per plan, media by loc, 1 source footer
publish.validate_embeds(block, stored)  → produced media must resolve
read note via _resolve_in_vault         → SNAPSHOT pre-rewrite content
capture_id = uuid[:12]
publish.create_capture(capture_id, note_path, snapshot)  → Java POST /api/internal/capture
     writes _inbox/_sources/{capture_id}.md + capture row (source_type='note',
     source_path=snapshot, status='processing')  — the NOTE is the single Capture source
publish.inject_block(content, embed, block, sha)         → rewrite in place (embed kept)
publish.stamp_capture(new, capture_id, seq=1)            → link note ↔ capture (durable FM)
publish.update_note(note_path, new)     → Java PUT /api/internal/notes (re-indexes, re-syncs)
```
The rewritten note stays live in its real folder and in FSRS. It surfaces in the Learn
**Inbox** as an `inPlace` item (found via `capture_id`, not a folder scan) for human
review; the user edits + **acknowledges** → `capture.status='filed'` + snapshot trashed.
See `../../obsidian_optimizer/.../inbox/FLOWS.md` and INGESTION_V2_FLOWS §7 (lifecycle).
*Why the note, not the embed:* a note can hold several embeds; a Capture always has
exactly one source, so the source is the pre-rewrite note itself.

### _synthesize_and_publish (standalone)
```
_store_media → outline → write_note() per plan (frontmatter + sr fields + #review)
→ publish.validate_note → publish.stamp_inbox(source, suggested=find_home)
→ publish.create_note in INBOX_FOLDER (_inbox staging, NOT the find_home folder)
one bad note never sinks its siblings; all-fail raises.
```
- **find_home is now only a SUGGESTION.** Standalone notes land in `_inbox/` (env
  `INGEST_INBOX_FOLDER`, default `_inbox`) with frontmatter `ingest-inbox: true` +
  `ingest-source` + `ingest-suggested-folder`. The Java `InboxController` lists them
  for the Learn **Inbox** triage view; the user edits + files them to a real folder
  (which moves them into the FSRS review queue). `NoteIndexRepository` keeps `_inbox/`
  out of the review query until then. To change the staging folder: `publish.INBOX_FOLDER`.

## Extractors (deterministic, zero LLM)

```
extract_av : ffmpeg -vn 16kHz wav → faster-whisper (WHISPER_MODEL int8); YouTube → download/downloader.fetch_subs() VTT (in-process yt-dlp); force_whisper → fetch_audio → whisper
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
create_capture(capture_id, source_ref, content): POST /api/internal/capture — snapshot the
    pre-rewrite note as the Capture source (in-place only), returns _inbox/_sources path
stamp_capture(content, capture_id, seq): inject capture-id/capture-seq frontmatter (durable
    note↔capture link, mirrored to notes.capture_id/seq). Shared with standalone captures.
stamp_inbox(content, source, folder): inject ingest-inbox/-source/-suggested-folder (standalone)
find_home : mcp_server.find_home_for_note → folder, else INGEST_DEFAULT_FOLDER
```

---

## v2 scaffolding (ir.py, segment.py)  [PARTIAL — not wired]

The v2 target pipeline (INGESTION_V2_FLOWS §2), with build status:
```
Acquire → Extract → SourceIR → Segment → Units → Draft → Place → Commit → Retain
  (v1)    (v1+bridge) ✅ir.py  ✅segment  ✅  ✅write_unit  ✅    ✅jobs   ✅plan
                     ✅flagging                _body (WRITE)  (v1)  publish  (no sweep yet)
      └── ✅pipeline_v2.run() chains these; ✅ jobs._run branches on INGEST_V2 (flagged) ──┘
```
✅ = built + unit-tested (pure, GPU-free). `pipeline_v2.run(ir)` chains segment →
draft(injectable) → retention → locator; `jobs._synthesize_and_{inject,publish}_v2` call it
behind `INGEST_V2` (default off) and publish via the existing v1 path. **Untested against a
real source/LLM** — flip the flag in Docker to smoke-test. Remaining seam: native `*_ir`
extractors aren't the live extract stage yet (v1 `{loc}` is bridged to IR), and the retention
plan is recorded on the job but the FS-sweep **executor** isn't wired (task below).

### ir.py — the SourceIR contract (§3)  ✅ built, tested
`raw bytes → ordered positioned Blocks`. No LLM, no concepts. Pure dataclasses +
JSON round-trip (bundles persist as JSON), zero heavy deps.
```
Medium(text_native | inferred_text)
Block { id, order_index, locator, type, text, level?, media_ref?, flags[] }
Locator = CharSpan{start,end} | PageBox{page_no,bbox} | TimeSpan{start_ms,end_ms}
Flag { code, severity=HARD|SOFT }        # §3c confidence gating (extraction not yet emitting)
SourceIR { medium, title, blocks[], anchors[], toc[], normalized_text, source_id }
  .has_structure() → toc or any heading   → Stage A picks its strategy off this
  .hard_flags()    → gates auto-commit
```
`order_index` is the spine → chronology + SEQUENTIAL links for free. *Change shape:* `ir.py`.

### segment.py — SourceIR → Units (§4)  ✅ built, tested
**LLM never chooses boundaries.** Structure + embedding topic-shift only.
```
segment(ir, embed_fn?) → list[Unit]
  Stage A (deterministic):  toc → group by leaf page ; elif headings → cut at level ≤ 2 ;
                            else → token windows (UNIT_WORDS_MAX)
  Stage B (bounded):        SPLIT > 600 words at topic-shift (embed_fn adjacency cosine,
                            TOPICSHIFT_DELTA below local mean) ; MERGE < 60 words forward
Unit { source_id, block_ids[], locator_span, order_index, raw_text, flags[] }
```
`embed_fn` is pluggable (`list[str]→vecs`); **None → deterministic word-balance split**
so segmentation runs GPU-free and is testable. Units inherit block flags (hardest wins).
Size band env: `INGEST_UNIT_WORDS_MIN/MAX/CEIL/FLOOR`, `INGEST_TOPICSHIFT_DELTA`,
`INGEST_SEGMENT_HEADING_LEVEL`. *Change strategy:* `segment.py`.

### extract_ir.py — native text-native extraction → SourceIR (§3b)  ✅ built, tested
The first **native** IR extractor, replacing the bridge for text/md/html with *real* char
offsets. `from_text(text,title)` / `from_html(ref)` → `from_markdown(md,title,medium)`:
line scanner → HEADING blocks (ATX, level parsed) + paragraph blocks, each `CharSpan` an
exact offset into the frozen `normalized_text`. **Invariant:** `normalized_text[span] ==
block.text` for every block (headings keep their raw `## ` prefix). Runs `flag_source`
before returning. `from_markdown` is pure/testable; `from_html` adds the trafilatura fetch.
*Not yet called by jobs.py* — the cutover swaps `extract_text`/`extract_web` for this.

### extract_pdf_ir.py — native PDF/EPUB IR extraction (§3a)  ✅ core built, tested
Block-level replacement for `extract_pdf.py`'s page mode: one Block per layout block with a
real `PageBox{page_no,bbox}` (v1 emitted one whole-page segment). **Split by testability:**
```
build_ir(pages[PageParse], toc, anchors, title, medium) → SourceIR   # PURE — unit-tested
  _body_size (char-weighted mode) → _heading_levels (font ≥ FONT_HEADING_RATIO×body → L1..6)
  layout HARD flags (§3c): READING_ORDER (x0 columns + order crosses bands), TABLE (bbox ∩
    find_tables rect), OCR (scanned page), LAYOUT (y runs backward in a column)
  then flag_source() adds IR-computable SOFT flags
from_pdf(ref, path) → SourceIR   # I/O: fitz spans→runs, find_tables, OCR fallback, lazy
                                 # page-shot anchors → build_ir. Needs PyMuPDF (not sandbox-run).
```
Clean single-column TOC'd PDF → no HARD flags → commits hands-off; messy layout → HARD flag →
Unit REVIEWING. *Tune headings:* `FONT_HEADING_RATIO`; *columns:* `INGEST_COLUMN_GAP_PTS`.
Tests: `tests/test_extract_pdf_ir.py` (pure core). *Not yet called by jobs.py.*

### extract_av_ir.py — native A/V IR extraction (§8a)  ✅ core+bridge built, tested
Transcript → SPEECH blocks (v1 emitted the same `{loc}` shape but untyped). Same pure/IO split:
```
build_ir(cues[Cue], chapters, title, medium, auto_captions) → SourceIR   # PURE — unit-tested
  Cue{start_s,end_s,text,avg_logprob?,no_speech_prob?} → SPEECH Block + TimeSpan{ms}
  §8d flags: ASR_LOW (HARD, avg_logprob < ASR_LOGPROB_MIN), NON_SPEECH (SOFT, no_speech_prob
    > NO_SPEECH_MAX), AUTO_CAPTIONS (SOFT); chapters → TOC (§8b prior); flag_source adds
    NO_STRUCTURE (no chapters) + LONG_UNBROKEN
from_bundle(v1_bundle) → SourceIR   # PURE — richer A/V bridge than ir_from_v1_bundle (SPEECH
                                    # + chapters TOC); the tested bridge replacement for A/V
from_av(ref, path, force_whisper) → SourceIR   # I/O: reuses extract_av whisper/captions but
                                    # KEEPS avg_logprob/no_speech_prob + word_timestamps. Unrun.
```
Diarization stays OFF (hook only, §8a). *Tune ASR gating:* `INGEST_ASR_LOGPROB_MIN` /
`INGEST_NO_SPEECH_MAX`. Tests: `tests/test_extract_av_ir.py`. *Not yet called by jobs.py.*

### retention.py — what survives commit (§6/§8c/§9h)  ✅ built, tested
**Pure planning, zero I/O.** `compute_retention(ir, units, kept_fragments?, source_blob?)`
→ `RetentionPlan{keep_paths, drop_paths, keep_transcript, referenced_pages}`. Delete-by-
default: only page shots for **referenced** pages, media owned by a **committed** Unit,
and user `KeptFragment`s survive; unreferenced shots/media + the raw blob drop; A/V keeps
the transcript. The actual FS sweep is a **separate executor** (Java internal API /
publish) — this module never deletes. *Change policy:* `retention.py`.

### locator.py — splice resolution for the consume layer (§7)  ✅ built, tested
`resolve_splice(unit, ir)` → `SpliceView`: a Unit's `locator_span` → a renderable source
region. text → `{start_char,end_char,quote}` (scroll+highlight); pdf → `{pages,page_images,
bbox_start,bbox_end}` (kept page-shots, cropped); av → `{start_ms,end_ms}` (play clip).
`resolve_all(units, ir)` returns descriptors in `order_index` order (learn-view walks a
source one-to-many without jumping). Pure — no file I/O. *Frontend learn-view consumes
this shape;* the 3-panel review UI / RSVP reader (§7) are still `[NOT IMPLEMENTED]`.

### flagging.py — extraction confidence, IR-computable subset (§3c/§8d)  ✅ built, tested
`flag_source(ir)` annotates `block.flags` in place with checks needing only the IR (no
fitz, no ML): `NO_STRUCTURE` (no toc+no headings → token-window only), `OVERSIZE_BLOCK`
(one block > 60% of source words), `LONG_UNBROKEN` (A/V speech run > 500 words). All SOFT.
Idempotent per code. Layout-specific HARD checks (READING_ORDER, TABLE, OCR, LAYOUT) need
`fitz` and belong in `extract_pdf.py` — not here. `INGEST_FLAG_MODE=off` disables.
Units inherit these via `segment._inherit_flags` (hardest wins). `[not yet called by any
extractor — jobs cutover wires it]`

### pipeline_v2.py — the flagged orchestrator (§2)  ✅ built, tested, wired (flagged)
Chains the pure stages into one call. **Wired into `jobs._run` behind `INGEST_V2` (default
off)** via `_synthesize_and_{inject,publish}_v2`; the live draft is `synthesize.write_unit_body`
and the live embedder is `model_runtime.embed_texts`. `v2_enabled()` selects the path; `guard()`
is available for any caller that wants a hard fail when off.
```
run(ir, draft_fn?, embed_fn?, kept_fragments?, source_blob_path?) → PipelineResult
  segment(ir, embed_fn)            → Units          (LLM never picks boundaries)
  per Unit: draft_fn(unit,title)   → body           (the ONLY LLM; None → echo raw_text)
            locator.resolve_splice → splice dict     (consume-layer region)
            HARD flag → REVIEWING else DRAFT (§3c/§7 lifecycle)
  compute_retention over DRAFT (auto-committable) Units only → RetentionPlan
PipelineResult{ ir, units, notes[DraftedNote], retention }.needs_review  # any HARD flag
```
`draft_fn: (Unit,title)→body` and `embed_fn: list[str]→vecs` are injected — the live wiring
passes `synthesize._write_body()` + `model_runtime.embed`; tests pass pure stubs so the whole
chain runs LLM/GPU-free. Feature flag `INGEST_V2` (default off); `v2_enabled()`/`guard()` are
what the future `jobs.py` caller checks. Convenience: `run_text` / `run_html` / `run_v1_bundle`
build the IR (via `extract_ir` / bridge) then `run()`. *Change the chain:* this file; *a stage:*
its module. Tests: `tests/test_pipeline_v2.py`.

### Wiring that remains (the v1→v2 seam)  [NOT IMPLEMENTED]
1. **Extractors emit SourceIR** — text/md/html ✅ **done natively** (`extract_ir.py`,
   real char offsets); pdf/epub ✅ **core done natively** (`extract_pdf_ir.py`, block/bbox +
   HARD flags — the `from_pdf` fitz wrapper is written but sandbox-unrun). Remaining:
   av transcript→speech blocks ✅ **core+bridge done natively** (`extract_av_ir.py`: SPEECH
   blocks, chapters TOC, ASR/NON_SPEECH/AUTO_CAPTIONS flags — the `from_av` whisper wrapper is
   written but sandbox-unrun). `ir.ir_from_v1_bundle()` remains only as the generic fallback;
   `extract_av_ir.from_bundle()` is the richer A/V bridge. `[text+pdf+av core done; live IO unrun]`
2. **jobs.py calls pipeline_v2** ✅ **wired (flagged).** `jobs._run` branches on
   `pipeline_v2.v2_enabled()`; `_synthesize_and_{inject,publish}_v2` build the IR
   (`_ir_from_bundle`: native `extract_av_ir.from_bundle` for A/V, `ir_from_v1_bundle` bridge
   for pdf/text), run `pipeline_v2.run(ir, embed_fn=model_runtime.embed_texts, draft_fn=
   synthesize.write_unit_body)`, and publish each Unit via the v1 capture/inbox path.
   `outline()`'s boundary role is retired on the v2 path. **Remaining:** make the native
   `*_ir` extractors the live *extract* stage (so bbox/char/ASR precision is real, not bridged);
   route HARD-flagged Units to REVIEWING instead of auto-injecting; **smoke-test in Docker.**
3. **retention.py** (§6) — planner ✅ built + tested; the FS-sweep **executor** that
   consumes a `RetentionPlan` at commit is not wired. **locator.py** (learn-view splice,
   §7) — splice resolver ✅ built + tested; the **frontend learn-view** that renders a
   `SpliceView` (+ 3-panel review UI, RSVP reader) is not built. `[resolver done; UI NEW]`
4. **Extraction flagging** (§3c) — Flag type exists in ir.py; no check populates it yet.

---

## Technology Notes

- **Ingest infinite-loop guard.** An approved v2 note is filed → re-scanned by Java
  `ResourceScanService` at the post-write chokepoint. If it carried a live `![[clip.mp4]]`
  embed, the scanner would re-fire ingestion on the very source we just ingested → loop.
  Prevented two ways: in-place notes keep the embed but carry the `<!-- ingest:… -->` marker
  (scanner skips marked embeds); standalone v2 bodies pass through
  `publish.demote_resource_embeds()`, which turns any A/V/PDF `![[…]]` into a plain `[[…]]`
  link. **Images are intentionally exempt** — `![[frame.jpg]]` must stay an embed so the
  reused `ImageScanService` captions it. Post-approval the note is otherwise a normal vault
  note: chunked+embedded by `NoteEmbeddingWorker` (re-embedded on edit via content-hash),
  images captioned by `ImageProcessingWorker` — no v2-specific indexing.
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
| v1 vs v2 synthesis branch | `jobs._run()` (`pipeline_v2.v2_enabled()` ⇒ `_*_v2`); `INGEST_V2` env |
| v2 bundle→IR bridge at the seam | `jobs._ir_from_bundle()` (native A/V, bridge pdf/text) |
| v2 per-Unit WRITE draft | `synthesize.write_unit_body()` |
| v2 in-place / standalone assembly | `synthesize.build_inplace_body_v2()` / `assemble()` via `_span_to_segs()` |
| v2 SEQUENTIAL chrono links (prev/next) | `synthesize.chronology_block()`, injected in `jobs._synthesize_and_publish_v2` (order_index order) |
| v2 SEMANTIC links (ANN → `## Related`) | `linking.related_links()` (reuses `mcp_server._vector_candidates` + `embed_texts`); `INGEST_SEMANTIC_FLOOR/MAX_PER_NOTE/CHUNK_CHARS` |
| Proposed-note chunk/embed (searchable) | REUSED — existing Java `NoteEmbeddingWorker` embeds `_inbox/` notes; nothing v2-specific |
| Proposed-note image captioning | REUSED — existing `ImageScanService`/`ImageProcessingWorker` (`![[frame.jpg]]`) |
| Ingest infinite-loop guard (A/V/PDF) | `publish.demote_resource_embeds()` — `![[clip.mp4]]`→`[[clip.mp4]]` in v2 note bodies (images kept) |
| In-place capture snapshot + link | `jobs._synthesize_and_inject()` → `publish.create_capture()` + `stamp_capture()` |
| Capture source folder | Java `InternalAgentController.createCapture` (`_inbox/_sources`) |
| In-place review/acknowledge | Java `inbox/FLOWS.md` (`InboxController`) |
| v2 SourceIR / Block / Locator shape | `ir.py` `[v2 scaffold]` |
| v2 segmentation strategy | `segment.py` (Stage A/B) `[v2 scaffold]` |
| v2 Unit size band | `INGEST_UNIT_WORDS_MIN/MAX/CEIL/FLOOR`, `INGEST_TOPICSHIFT_DELTA` env `[v2]` |
| v2 heading boundary level | `INGEST_SEGMENT_HEADING_LEVEL` env `[v2]` |
| v2 retention keep/drop policy | `retention.py` (`compute_retention`) `[v2 scaffold]` |
| v2 extraction flags (IR-computable) | `flagging.py` (`flag_source`) `[v2 scaffold]` |
| v2 native text/html IR extraction | `extract_ir.py` (`from_markdown`) `[v2 scaffold]` |
| v2 native pdf/epub IR extraction | `extract_pdf_ir.py` (`build_ir` / `from_pdf`) `[v2 scaffold]` |
| v2 pdf heading / column thresholds | `FONT_HEADING_RATIO` / `INGEST_COLUMN_GAP_PTS` env `[v2]` |
| v2 native av IR extraction | `extract_av_ir.py` (`build_ir` / `from_bundle` / `from_av`) `[v2 scaffold]` |
| v2 av ASR / non-speech thresholds | `INGEST_ASR_LOGPROB_MIN` / `INGEST_NO_SPEECH_MAX` env `[v2]` |
| v2 splice resolution (consume) | `locator.py` (`resolve_splice`) `[v2 scaffold]` |
| v2 orchestrator chain (flagged) | `pipeline_v2.py` (`run`); `INGEST_V2` env, `guard()` `[v2 scaffold]` |
| (note, embed) job de-dup | `jobs.submit()` |
| Embed → file resolution | `main._resolve_embed()` (basename rglob fallback) |
| MCP: agent-authored text → notes | `mcp_server.create_note(text, title)` → `jobs.submit(text=…)` (text route → segment → _inbox) |
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
| YouTube captions / download | `download/downloader.py` (`fetch_subs` / `download_sync`); see `download/FLOWS.md` |
