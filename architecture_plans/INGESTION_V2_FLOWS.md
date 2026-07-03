# Ingestion v2 — Source → anchored Units → Notes (DESIGN / PLAN)

Files (v1, evolves): embedder/ingest/router.py, extract_pdf.py, extract_text.py, extract_web.py, extract_av.py, keyframes.py, synthesize.py, publish.py, bundle.py, split_note.py, jobs.py
Files (v2 NEW): ir.py, extract_ir.py, segment.py, flagging.py, retention.py, locator.py, pipeline_v2.py (orchestrator, flagged) — all built + unit-tested, none wired live
Supersedes aspects of: architecture_plans/INGEST_AGENT_ARCH.md, embedder/ingest/FLOWS.md (v1)
Related: architecture_plans/SYNC_RETENTION_PLAN.md, ML_ARCH.md (pgvector search)

> **Status: design + first scaffolding landed.** The contract and segmentation are
> built and unit-tested; extraction still emits v1 shapes and `jobs.py` still runs the
> v1 path — the cutover is not done. What exists today (see `embedder/ingest/FLOWS.md`
> "v2 scaffolding"):
> - `ir.py` — SourceIR / Block / Locator / Flag schema (§3), JSON round-trip. ✅
> - `ir.ir_from_v1_bundle()` — transitional bridge: live v1 bundle → SourceIR. ✅
> - `segment.py` — deterministic Stage A/B segmentation → Units (§4), GPU-free fallback. ✅
> - `tests/test_segment.py` — round-trip, bridge, ceiling/merge/topic-shift. ✅
> Not built: native IR extractors (§3a/b, §8a), `jobs.py` cutover to `segment()`,
> extraction flagging population (§3c), `retention.py` (§6), `locator.py`/consume (§7).
>
> Every section is tagged with what it reuses from v1 vs. what is `[NEW]`. Read v1 first
> (`embedder/ingest/FLOWS.md`) — v2 keeps the job runner, host-wrapper LLM routing,
> publish/write-through, and keyframe/CLIP machinery; it replaces **extraction
> granularity** and **who decides note boundaries**.

---

## 0. What changes from v1 (read this first)

| Concern | v1 (today) | v2 (this doc) |
|---|---|---|
| PDF granularity | one segment per **page** (`loc:{page}`) | **block-level** with bbox → sub-page + cross-page anchoring |
| Text locator | `{heading}` only | `{char_offset}` span (edit-stable id) |
| Who picks note boundaries | `synthesize.outline()` — **the LLM** | **structure + embedding topic-shift** (deterministic); LLM only *writes* |
| Source retention | out of scope (v1 keeps embed in note) | **delete-by-default; referenced regions + KeptFragments survive** |
| Anchor precision | page / heading | `locator_span` = two endpoints, sub-unit precise |
| Reprocessing | re-run extractor | source deleted → re-ingest (accepted cost) |

Everything else in v1 (job runner, GPU arbiter, host-wrapper `/complete`, CLIP
keep/drop, publish to Java internal API, `_inbox` staging) is **reused unchanged**.

---

## 1. Medium taxonomy — the one axis that matters

Two families, split by **whether text is present or inferred**:

- **text-native** (`.txt .md .html .pdf .epub`) — text exists in the file; structure
  is extractable; a locator is a *position in the document*. **Sections 3–4.**
- **inferred-text** (`audio → video`) — text comes from a model (Whisper); a locator
  is *time*. Video = audio + image track. **Sections 8–9.**

Locator is the only thing extraction varies on:

| medium | `locator` shape | kept-representation (survives deletion) |
|---|---|---|
| pdf / epub | `{page, bbox:[x0,y0,x1,y1]}` | cropped page screenshot (referenced pages only) |
| txt / md / html | `{start_char, end_char}` | the substring itself (text is cheap → keep) |
| audio | `{start_ms, end_ms}` | **full transcript** (§8c) + KeptFragment clip |
| video | `{start_ms, end_ms}` | **full transcript** + kept keyframes + KeptFragment clip |

**Rule that resolves sub-page anchoring:** the unit of anchoring is the **block**,
never the page/heading. A block carries a `locator`; a Unit is a contiguous block
range; the Unit's span is `first_block.start → last_block.end`. Page number is used
*only* for retention (which screenshot to keep), never for anchoring.

---

## 2. Stage pipeline

```
Acquire ─→ Extract ─→ SourceIR ─→ Segment ─→ Units ─→ Draft ─→ Place ─→ Commit ─→ Retain
 (v1 dl)   (§3 NEW)   (§3 schema) (§4 NEW)           (v1 synth) (v1 find_home) (v1 publish) (§6 NEW)
                                                                                          │
consume layer (§7): learn-view splice · RSVP reader        ────────────────────────────┘
```

- **Acquire** — reuse v1 `download/downloader.py` (yt-dlp) + direct download; extension
  feeds logged-in sources. Unchanged.
- **Extract → SourceIR** — `[NEW]` the contract between extraction and segmentation.
- **Segment → Units** — `[NEW]` deterministic boundaries.
- **Draft** — reuse `synthesize._write_body()` (WRITE pass) per Unit. **Drop the
  outline/boundary role** of `synthesize.outline()`.
- **Place** — reuse v1 `_inbox` staging + `find_home_for_note` suggestion.
- **Commit** — reuse `publish.*`; add chunk/embed/link + flashcards + retention sweep.
- **Retain** — `[NEW]` §6.

---

## 3. SourceIR — the extraction contract `[NEW]` (`ir.py`)

Extraction's *only* job: `raw bytes → ordered positioned blocks`. It assigns no
concepts and makes no LLM calls.

```
SourceIR {
  source_id, medium          # "pdf" | "text" | "html" | "epub" | "audio" | "video"
  title
  anchors: [...]             # pdf: [{page_no, image_path, w, h}]   av: timeline markers
  blocks: [
    { id, order_index,
      locator,               # medium-typed, see §1 table
      type,                  # heading|paragraph|list|figure|caption|table (text-native)
      text,                  # "" for figure/keyframe
      level?,                # heading depth if type==heading
      media_ref? }           # figure crop / keyframe path
  ]
  toc: [{ title, level, page_no|char }]   # embedded outline; may be empty
}
```

`order_index` is the spine: it becomes chronology **and** `SEQUENTIAL` links for free
(§5). *To change the IR shape:* `ir.py`.

### 3a. Extraction — PDF/EPUB `[NEW, replaces extract_pdf.py page mode]`

PyMuPDF (`fitz`), no ML in the happy path:

```
fitz.open(path)
  doc.get_toc()                          → toc[]  (best structural signal when present)
  per page:
    page.get_text("dict")                → spans with font size/weight + bbox
      cluster font size/weight           → type + heading level  (To tune: FONT_HEADING_RATIO)
      merge spans into blocks by line/gap→ block.text + block.locator{page,bbox}
    page.get_images(full=True)           → figure blocks (bbox), media_ref crop
    page.get_pixmap()                    → anchors[page].image_path   (retained lazily, §6)
  scanned page (get_text < MIN_OCR_WORDS)→ Tesseract fallback (reuse extract_pdf._ocr_page)
```

*v1 delta:* v1 emitted `{loc:{page}, text:<whole page>}`. v2 emits per-block bbox.
Keep v1's `_keep_diagrams()` CLIP mask for figure blocks (logos/headshots dropped).
*To change diagram keep/drop:* `keyframes.KEEP_PROMPTS / DROP_PROMPTS`.

### 3b. Extraction — txt / md / html `[NEW, evolves extract_text.py / extract_web.py]`

No pages, no bbox. Locator = character offsets into the normalized text.

```
normalize to text (md: as-is; html: reuse extract_web trafilatura → markdown)
scan structure:
  markdown/atx headings (^#{1,6})        → heading blocks + level
  blank-line paragraph breaks            → paragraph blocks
  each block.locator = {start_char, end_char}   (offsets into the normalized string)
```

*v1 delta:* v1 `extract_text._split_by_heading` yields `{loc:{heading}}` per section.
v2 keeps the section split but records `{start_char,end_char}` so the splice-view can
scroll-and-highlight and the Unit span is precise. Store the normalized string with
the source until commit (offsets are meaningless without it).

---

## 3c. Extraction confidence & flagging `[NEW]` — **ON by default**

Extraction is a best-effort layout parse; it *will* misgroup some PDFs. A flag is a
confidence signal on a block (inherited by its Unit) that **gates auto-commit** —
clean PDFs (real TOC, single column) flow straight through untouched; only messy ones
divert into the review queue with a specific reason. Two severities:

- **HARD** — correctness is likely *wrong* (bad anchor/text). Unit → `REVIEWING`,
  cannot be permitted until the user accepts it.
- **SOFT** — result is *coarse*, not wrong. Auto-flows, just shows a badge.

Checks are deterministic, cheap, no ML:

| Check | How (fitz) | Flag | Sev |
|---|---|---|---|
| Multi-column scramble | `x0` histogram → 2+ clusters, `order_index` crosses bands | `READING_ORDER` | HARD |
| Table region | `page.find_tables()`; block bbox intersects a table | `TABLE` | HARD |
| Scanned page | `get_text` words < `MIN_OCR_WORDS` → OCR ran | `OCR` | HARD |
| Sidebar/callout/footnote interleave | `y` runs backward within a column band | `LAYOUT` | HARD |
| No structure | `toc` empty **and** one dominant font size (no heading tier) | `NO_STRUCTURE` | SOFT |
| Oversized block | one block > ~60% page text or > 70% page height | `OVERSIZE_BLOCK` | SOFT |
| Slides / low text ratio | page mostly image area, little text | `LOW_TEXT` | SOFT |

Process:
```
extract → flags on blocks → segment → Unit inherits flags
  HARD  → REVIEWING, not permit-eligible
  SOFT  → auto-flows with a badge
  none  → hands-off straight through
```

**Review affordance:** we already render `page.get_pixmap()` per page, so the review
UI draws the numbered block bboxes over the page image — verifying reading order is a
glance. Actions on a flagged Unit: accept · re-order blocks · adjust boundary ·
exclude a block · drop the source.

Examples: two-column arXiv paper → `READING_ORDER` (boxes zigzag; "sort columns"
fixes). Photographed textbook → `OCR` per page (proofread beside the image). Slides
exported to PDF → `LOW_TEXT`+`NO_STRUCTURE` (auto "one Unit per slide", confirm or
merge). Callout box mid-page → `LAYOUT` (exclude or promote to its own Unit). Clean
TOC'd single-column PDF → no flags, commits hands-off.

*To disable / tune:* `INGEST_FLAG_MODE` (default `on`); per-check thresholds in
`extract_pdf.py`. Flag-by-default is a starting stance — if it's noisy in practice,
demote HARD checks to SOFT rather than turning it off.

---

## 4. Segmentation — IR → Units `[NEW]` (`segment.py`)

**Invariant: the LLM never chooses boundaries.** Boundaries come from structure +
embedding topic-shift only → reproducible, no hallucinated splits. (This is the
deliberate departure from v1's `synthesize.outline()`.)

**Stage A — structural grouping (deterministic):**
```
if toc non-empty      → group blocks by TOC leaf section
elif has headings     → boundary at each heading with level ≤ SEGMENT_HEADING_LEVEL
else                  → sliding window over paragraphs by token budget
```

**Stage B — semantic refinement (bounded, uses the existing embedder):**
```
SPLIT oversized section (> UNIT_WORDS_CEIL, = 600):
    embed adjacent paragraph windows → cut where cosine drops (topic-shift / TextTiling)
MERGE undersized adjacent sections (< UNIT_WORDS_FLOOR)
target each Unit into [UNIT_WORDS_MIN, UNIT_WORDS_MAX]   (see size band below)
```

Output:
```
Unit {
  id, source_id
  block_ids: [...]       # provenance into IR
  locator_span           # {page_range+bbox endpoints}  |  {char start→end}  |  {ms}
  order_index            # → chronology + SEQUENTIAL edges
  raw_text               # concat of block text → drafting input
}
```

### Size band — **DECIDED: 600 words is the hard ceiling**
| param | default | env |
|---|---|---|
| `UNIT_WORDS_MIN` / `MAX` | 150 / **600** words | `INGEST_UNIT_WORDS_MIN/MAX` |
| `UNIT_WORDS_CEIL` (force split) | 600 (== MAX; a Unit never exceeds this) | `INGEST_UNIT_WORDS_CEIL` |
| `UNIT_WORDS_FLOOR` (force merge) | 60 words | `INGEST_UNIT_WORDS_FLOOR` |
| topic-shift cosine drop | 0.25 below local mean | `INGEST_TOPICSHIFT_DELTA` |

A Unit is capped at **600 words** — Stage B always splits anything larger at the best
topic-shift boundary. (~800 tokens; band expressed in words because that's the mental
model for "one note.")

*To change segmentation strategy:* `segment.py`. *To change size:* the env vars above.

### Worked example (the reference case)
Tiny 2-page PDF, "Intro to Cells", Ch.2 starts at bottom of p1 → spills to p2.
- Extraction → 8 blocks (b0..b7), each `{page, bbox, type, text}`.
- Stage A groups by TOC: **Unit A** = b0–b2 (Ch.1, `page1 y72→200`), **Unit B** = b3–b7
  (Ch.2, `{page1,y700} → {page2,y410}` — the cross-page/sub-page case).
- Stage B: both within band → no split/merge.
- Downstream free: `order_index` → A→B SEQUENTIAL; splice crops p1/p2 to the bbox
  span; retention keeps p1.png (both units) + p2.png (Unit B); PDF dropped.

---

## 5. Linking (at commit) `[NEW linking, reuses pgvector]`

Two **distinct** edge types:
- **SEQUENTIAL** — from `order_index` within a source. Deterministic, no embeddings.
- **SEMANTIC** — **ANN top-k, never pairwise.** For each new note *chunk*, query the
  vector index (pgvector, see `ML_ARCH.md`) for top-k nearest neighbors above a
  similarity floor; cap top-N per note. Compute at **chunk grain**, store at **note
  grain** with the chunk as evidence anchor: `noteA/chunk3 ↔ noteB/chunk1, 0.82`.

*To change link thresholds:* `INGEST_SEMANTIC_TOPK`, `INGEST_SEMANTIC_FLOOR`,
`INGEST_SEMANTIC_MAX_PER_NOTE`. Chunking window reuses `bundle.WINDOW_TOKENS`.

---

## 6. Retention & KeptFragments `[NEW]` (`retention.py`)

**Policy: delete-by-default. Anything not referenced at commit is deleted; the source
blob is dropped. Regeneration = re-ingest (accepted cost). Reason is storage, not
copyright** — so referenced regions are legally fine to keep.

At commit, compute the reference set:
```
referenced_pages   = { p for unit in notes for p in unit.locator_span.pages }   # pdf
KeptFragments      = user-marked regions during review (§7)
KEEP:  anchors[p].image_path for p in referenced_pages   +   KeptFragments
       (txt/md: the substring is the note's quote — already inline, nothing extra)
DROP:  raw source blob, unreferenced page screenshots, unreferenced media
```

**KeptFragment** = a source region the user promotes to survive + inline into a note.
Media-typed: video clip `[ms,ms]`, audio snippet `[ms,ms]`, PDF figure crop. For text
the "keep" is trivial (the quote). Created via the review UI "keep this" action.

**Splice-view lifetime:** full-source scrubbing exists **only during review** (source
still on disk). **Post-commit only screenshots/clips survive** — learn-view plays kept
clips and shows kept page-shots; it does **not** scrub the original. Consistent with
"no references means gg."

*To change staging/retention:* `retention.py`; `INGEST_INBOX_FOLDER` (v1) for staging.

---

## 7. Consume layer `[NOT IMPLEMENTED]`

- **Learn-view splice** — a note renders its source region directly: PDF → the kept
  page-shot cropped to `locator_span` bbox (no scrolling); txt → scroll+highlight
  `char` span; A/V → play the kept clip from `start_ms`. One-to-many source→notes,
  ordered by `order_index`, so learning walks a source without jumping.
- **Review UI — 3 panels** — `queue │ source-viewer (spliced to region) │ note editor`.
  Queue = notes in non-committed lifecycle states. "Keep this" → KeptFragment.
- **Note lifecycle state machine:** `DRAFT → REVIEWING → COMMITTED`. "Permit" is the
  `REVIEWING→COMMITTED` transition → triggers chunk · embed · link · flashcards ·
  retention sweep. v1 `_inbox` staging is the DRAFT/REVIEWING store.
- **RSVP "watch text" reader** — reads committed `note.md`, tokenizes to words. Per
  word: highlight the **ORP** (a letter left-of-center, not middle). Dwell =
  `base_ms(WPM)` × length factor × punctuation multiplier (longer pause on `.,;`).
  Adjustable WPM; tick/click sound on each word change. Pure consume-side, zero
  coupling to extraction.

---

## 8. Audio ingestion `[DESIGNED]` (evolves `extract_av.py`)

Video = audio + images, so audio is the base case. The **entire v2 skeleton is
reused** — Block → Unit → `locator_span` → Draft → Place → Commit → Retain, plus
flagging. Blocks come from the transcript instead of layout; `locator = {start_ms,
end_ms}`; `type = speech`. Only three things are audio-specific: the structural prior
(chapters), the retention rule (keep transcript), and the flag set.

**Reframe — the taxonomy split is "authored boundaries or not," not text vs audio.**
TOC (pdf) ≈ **chapters** (podcast / YouTube via yt-dlp `chapters`) ≈ chapter track
(audiobook) — all authored boundaries feeding the same Stage A. Audio only *feels*
structureless when chapters are absent.

### 8a. Extraction — transcript by trust ladder `[evolves extract_av.py]`
```
acquire audio (yt-dlp / file)
  chapters metadata?  (yt-dlp --dump-json .chapters / chapter track) → structural prior
  transcript, best available first:
     uploaded captions (VTT)   → best, cheap, no GPU          ┐ v1 fetch_subs()
     auto-captions (VTT)       → ok; flag AUTO_CAPTIONS SOFT  │  (reuse)
     else faster-whisper       → word_timestamps=True         ┘  (v1, now word-level)
  → blocks: one per cue/utterance, locator {start_ms,end_ms}, type=speech
  → VAD long-pauses recorded as boundary hints for Stage A
```
**Word-level timestamps ON** (`word_timestamps=True`) — required for tight KeptFragment
clips and future RSVP-synced-to-audio. v1 is segment-level only.
**Diarization OFF** in the baseline (pyannote = heavy, more failure surface — the exact
"models don't make a working product" risk). Leave a hook; enable per-source later.

### 8b. Segmentation — boundary precedence `[NEW ladder]`
```
1. authored chapters present        → group blocks by chapter   (trusted, automatic)
2. user manual marks (in review)    → override; the reliable BACKUP when no chapters
3. auto heuristic                   → topic-shift (TextTiling) ∪ VAD long-pause
                                       = the DRAFT you then hand-correct
Stage B: same 600-word cap split/merge as text.
```
Manual marking is always available as an override; auto just gives a non-empty draft so
you edit rather than build from scratch. `NO_STRUCTURE` flags sources on tier 3.

### 8c. Retention — **keep the transcript** `[NEW, departs from text policy]`
Text deletes the source because re-reading is free. A/V re-inference = re-download +
re-Whisper (GPU, minutes) — expensive. So the cheap durable substitute for the deleted
media is the transcript itself.
```
DROP:  raw audio blob
KEEP:  full transcript (tiny)  +  KeptFragment clips  (user-promoted [start,end])
```
This also gives the learn-view something to show for a region that isn't a clip: the
transcript quote. **No screenshots** — pure audio has no image track. An audio Unit's
`media_ref` is empty; its splice-view = transcript span + play-from-`start_ms` if a
clip was kept.

### 8d. Flagging (parallel to §3c, ON by default)
| Check | Signal | Flag | Sev |
|---|---|---|---|
| Low ASR confidence | whisper `avg_logprob` low / `no_speech_prob` high | `ASR_LOW` | HARD |
| Auto-captions used | VTT was auto-generated, not uploaded | `AUTO_CAPTIONS` | SOFT |
| No chapters | fell to topic-shift/VAD (tier 3) | `NO_STRUCTURE` | SOFT |
| Music / non-speech run | sustained high `no_speech_prob` | `NON_SPEECH` | SOFT |
| Long unbroken span | no pauses/shifts in a long stretch → weak segmentation | `LONG_UNBROKEN` | SOFT |

*To change:* Whisper model/device `WHISPER_MODEL` / `extract_av._pick_device()`;
captions vs whisper in `extract_av` + `download/downloader.fetch_subs()`; word
timestamps `INGEST_WORD_TIMESTAMPS` `[NEW]`; diarization hook `INGEST_DIARIZE` (default
off) `[NEW]`.

---

## 9. Video ingestion `[DESIGNED]` (evolves `keyframes.py`)

Video = the §8 audio pipeline **+ an image track**. The audio half (transcript,
chapters, Units, word timestamps, retention) is unchanged; **all new work is
keyframes**. Screenshots return here (audio had none).

### 9a. Keyframe candidate generation `[reuse keyframes.py]`
Over-generate, then prune. Three candidate sources, unioned:
```
scene-change   ffmpeg select='gt(scene,SCENE_THRESHOLD)'  → candidate @ cut t_ms   (slide/cut signal)
transcript cue CUE_PATTERNS match in transcript           → candidate @ word t_ms  (word-level, §8)
periodic       1 frame / PERIODIC_SECS                    → coverage fallback (whiteboard, slow build)
→ candidates: [{ t_ms, png }]
```
The keyword cue that underperformed in v1 is now *one of three* sources, not the trigger.

### 9b. Keep/drop — CLIP prefilter → dedupe → VLM confirm `[DECIDED]`
Order matters — dedupe **before** the VLM so vision only sees unique survivors (bounds cost):
```
CLIP zero-shot (clip_onnx.py, KEEP/DROP prompts) → drop obvious junk (faces, blur, logos, title cards)
  → dedupe (§9c) collapse near-duplicates
    → VLM confirm (Claude vision via host-wrapper /complete-image) on the handful that remain:
         prompt: "Information-bearing (slide/diagram/chart/code/whiteboard)? If yes, one-line caption."
         no  → drop
         yes → keep + store caption
```
The **caption is reused**: written as the image's alt/caption and fed to the existing
`pending_image_jobs` captioner (skip the lazy re-caption). VLM cost = a few calls per
video (post-CLIP, post-dedupe), not per frame. *Toggle:* `KEYFRAME_VLM` (default on).

### 9c. Dedupe — keep the *most complete* frame `[NEW behavior]`
```
cluster survivors by CLIP/pHash similarity within temporal windows
keep LAST-in-cluster (settled slide / full animation build), drop earlier partials
cap ≤ MAX_FRAMES
```
"Keep-last" is the fix for animated slides (last frame has all bullets) and held slides.

### 9d. Alignment & in-note placement `[NEW]`
```
each kept frame t_ms → owning Unit = the Unit whose locator_span [start,end) contains t_ms
in-note position    → find the transcript word at t_ms (word timestamps, §8) →
                       insert the image right after THAT sentence, not dumped at the end
frame → Unit.media_ref  (inline figure; user may promote to KeptFragment)
```

### 9e. Two survivor types (both outlive the deleted video)
- **Keyframe** — auto-extracted *still*, information-bearing, inlined as an image, ≤`MAX_FRAMES`.
- **KeptFragment clip** — user-promoted *time range*, playable, for when **motion** matters
  (a demonstration / physical process). Rare, user-driven, larger.

### 9f. Content-source modes — graceful degradation `[DECIDED: degrade now]`
Video info can live in speech, frames, or both:
```
narrated (default)     transcript = content, frames illustrate       → normal §8 + §9
LOW_VISUAL_YIELD       keep/drop dropped ~everything                 → treat as pure audio (transcript only)
LOW_SPEECH             sparse transcript (silent screencast/slideshow)→ feed drafter the VLM keyframe
                                                                        captions as content so notes aren't empty
frame-primary          scene-cuts as the boundary spine             → [NOT IMPLEMENTED] known mode, later
```

### 9g. Flags (extend §8d, ON by default)
| Check | Signal | Flag | Sev |
|---|---|---|---|
| No usable frames | keep/drop kept ~0 | `LOW_VISUAL_YIELD` | SOFT |
| Sparse speech | transcript words/min below floor | `LOW_SPEECH` | SOFT |

### 9h. Retention
Drop the raw video; **keep the transcript** (§8c) + keyframes owned by a committed Unit
+ user KeptFragment clips. Unreferenced frames dropped.

---

## Technology Notes (failure modes)

- **Block clustering breaks on multi-column / complex layout.** `get_text("dict")`
  gives spans in reading order *usually*; two-column papers, sidebars, and tables can
  interleave. Font-ratio heading detection also fails on PDFs that fake headings with
  bold body text. When it misgroups, Units span the wrong region → wrong splice crop.
  Mitigation: prefer TOC when present; treat font heuristics as best-effort. **No
  layout-ML in v1 of this design** — flag misgrouped sources for manual review rather
  than silently mis-anchoring.
- **Char offsets drift if the note is edited before commit.** `{start_char,end_char}`
  index into the *normalized source string*, which must be stored verbatim until
  commit. If the source text is re-normalized (whitespace, unicode) the offsets rot.
  Freeze the normalized string at extraction; never re-normalize mid-flow.
- **Deletion is irreversible by design.** Once the source blob and unreferenced pages
  are dropped, re-segmenting with a better model requires re-downloading. This is the
  accepted trade for storage. The risk: a model upgrade (better Whisper/embedder) can
  no longer improve already-committed sources. Acceptable now; revisit if models churn.
- **Screenshots are the retention cost for PDF.** A 500-page book with references on
  every page keeps 500 PNGs. Referenced-only keeps that bounded, but a note per page
  defeats it. Watch total PNG footprint per source.
- **SEMANTIC links can hairball.** Without `FLOOR` + `MAX_PER_NOTE`, top-k over a large
  vault links everything to everything. Caps are load-bearing, not optional.
- **LLM-boundary removal shifts failure mode.** v1 could produce sensible notes from
  messy structure because the LLM regrouped. v2 trusts structure; a PDF with *no* TOC
  and *fake* headings degrades to token-window splitting, which can cut mid-concept.
  Topic-shift refinement (Stage B) is the safety net — if it underperforms, that's the
  first knob to revisit, not re-adding LLM boundary control.
- **Inherited v1 constraints:** jobs are in-memory (restart loses status, bundles
  survive); single GPU slot arbitrated via `GPU_MEMORY.md`; faster-whisper pins
  onnxruntime-gpu last; all `/complete` via host-wrapper.

---

## Change Index

| Thing to change | Where |
|---|---|
| SourceIR shape | `ir.py` `[NEW]` |
| PDF block/bbox extraction | `extract_pdf.py` → block mode `[NEW]` |
| PDF heading detection threshold | `FONT_HEADING_RATIO` env `[NEW]` |
| txt/md/html char-offset extraction | `extract_text.py` / `extract_web.py` `[NEW]` |
| Segmentation strategy | `segment.py` `[NEW]` |
| Unit size band (600-word cap) | `INGEST_UNIT_WORDS_MIN/MAX/CEIL/FLOOR` env `[NEW]` |
| Extraction flagging on/off + thresholds | `INGEST_FLAG_MODE` env, `extract_pdf.py` `[NEW]` |
| Word-level timestamps (audio) | `INGEST_WORD_TIMESTAMPS` env `[NEW]` |
| Diarization hook (audio, default off) | `INGEST_DIARIZE` env `[NEW]` |
| Audio boundary precedence (chapters→manual→auto) | `segment.py` `[NEW]` |
| A/V transcript retention | `retention.py` `[NEW]` (keep transcript, drop media) |
| Keyframe candidates (scene/cue/periodic) | `keyframes.py → SCENE_THRESHOLD / CUE_PATTERNS / PERIODIC_SECS` |
| Keyframe CLIP keep/drop prompts | `keyframes.KEEP_PROMPTS / DROP_PROMPTS` |
| VLM keep/drop confirm on/off | `KEYFRAME_VLM` env `[NEW]`; host-wrapper vision |
| Dedupe representative (keep-last) | `keyframes.py` `[NEW behavior]` |
| Max keyframes per video | `MAX_FRAMES` |
| Keyframe → Unit placement (by t_ms) | `segment.py` / `publish.py` `[NEW]` |
| Topic-shift sensitivity | `INGEST_TOPICSHIFT_DELTA` env `[NEW]` |
| Structural heading boundary level | `SEGMENT_HEADING_LEVEL` env `[NEW]` |
| Note drafting (WRITE only, no outline) | `synthesize._write_body()` (reuse); retire outline-boundary role |
| SEMANTIC link caps | `INGEST_SEMANTIC_TOPK/FLOOR/MAX_PER_NOTE` env `[NEW]` |
| Chunk window | `bundle.WINDOW_TOKENS` (`INGEST_WINDOW_TOKENS`) |
| Retention / deletion sweep | `retention.py` `[NEW]` |
| v2 orchestrator chain (flagged, not live) | `pipeline_v2.py` `run()`; `INGEST_V2` env, `guard()` `[NEW]` |
| Staging folder | `INGEST_INBOX_FOLDER` env (v1) |
| Diagram keep/drop | `keyframes.KEEP_PROMPTS / DROP_PROMPTS` |
| Routing rules | `router.py → ROUTE_TABLE` |
| RSVP reader / learn-view / 3-panel | frontend `[NOT IMPLEMENTED]` |
| Silent/visual-primary video (frame-primary) | §9f `[NOT IMPLEMENTED]` — degrades via captions for now |
