# Pipeline Hardening Plan — readiness gates, embed batching, launch testing

Status: **Phase 1 (gates) + Phase 2a (per-note batching) DONE** on branch
`fix/http1-and-cudnn9` (commits 7e31d8c, 8b93938) with ReadinessGateIT +
EmbeddingServiceBatchTest green; full unit suite 246 pass. Remaining: Phase 3 docs
(ml/FLOWS.md updated; root integration-map doc still TODO), Phase 4 launch testing
(canary + staged launch), Phase 1b (merge_pending gate — blocked on sync merge being
built), Phase 2b (cross-note batching — only if backfill is slow). Originally written
2026-06-13 to survive session loss.
Prereq: the in-flight fix branch `fix/http1-and-cudnn9` (HTTP/1.1, cuDNN9, regex,
requests dep, host-wrapper in start.ps1, embedder healthcheck start_period=600s)
must be rebuilt + verified working FIRST. Do not build on a broken base.

## Product intent — why the ingest agent exists (primary use)

The ingest agent's PRIMARY purpose is **background, ahead-of-time conversion of any
planned resource (a yt-dlp'd video, a book chapter, etc.) into a draft note** — so
learning is "guilt-free": the note is pre-generated locally before the user even
watches/reads. Target end-to-end flow: laptop pre-generates the note in the
background → user consumes the resource (often on phone, possibly laptop-off) and
jots only the few most-important notes into the mobile app → those persist on
phone → sync to cloud → on laptop boot, sync loads, retrieves, and **cross-references
+ conflict-resolves the phone notes against the pre-generated draft, git-style with
the user resolving**. The A/V-embed transcription path is a SECONDARY use that reused
this agent; the core value is reducing procrastination by decoupling note creation
from consumption (must run in background, before or after). Conflict-resolution /
mobile-sync merge is [NOT IMPLEMENTED] — design target, noted here for continuity.

## Context — the five-pipeline model (why this plan exists)

Every pipeline is an **independent hash-diff poller** over Postgres. No message bus,
no orchestrator. A note write fans out to:

1. **Ingest** — `ResourceScanService.scan` detects `![[*.mp4|mkv|webm|mov|avi|mp3|m4a|wav|ogg|flac|pdf]]`
   without a `<!-- ingest:base -->` marker → embedder `/ingest` → whisper/extract →
   synthesize (LLM) → inject text below the embed + marker. **Mutates note content.**
2. **Image→text** — `ImageScanService.registerImages` → `pending_image_jobs` queue →
   `ImageProcessingWorker` (30s) → host-wrapper `/process-image` (VLM) → text stored
   in `note_chunks` (source='image', text+embedding). Independent chunk_index range.
3. **Text embedding** — `NoteEmbeddingWorker` (30s, batch 20) diffs
   `notes.content_hash ↔ notes.embedded_hash` → `EmbeddingService.indexNote` → chunk →
   embed → `note_chunks` (source='text').
4. **Flashcards** — `CardJobWorker` (30min) diffs notes with `sr_due` set whose
   `content_hash ≠ cards.source_hash` → embedder `/flashcards/generate` (2-pass LLM +
   validate). **Expensive (LLM credits).**
5. **Review (FSRS+bandit)** — user-driven, not a processing pipeline.

**The gap:** nobody waits for anybody. A note with `![[lecture.mp4]]` gets embedded AND
carded from its pre-transcript text, then ingest mutates `content_hash`, then both
re-run. Eventually consistent, but: (a) wasted embedding work, (b) **double LLM spend
on cards**, (c) a search window where the note lacks its video content.

Progress instrumentation already exists: `GET /api/stats` → embedding %, image queue,
flashcard coverage, ingest job counts, wrapper health (polled by DashboardPage).

---

## Phase 1 — Readiness gates (there are TWO, sharing one signal)

**Goal:** downstream pipelines skip a note while its ingest preprocessing is incomplete.
Cards is the priority (credit cost); embedding second (wasted work).

**Signal:** a note is "ingest-pending" when `ResourceScanService.embedsNeedingIngest(content)`
is non-empty (resource embed present without its marker). This predicate already exists
and is unit-tested.

**Design decision: persist it as a column, gate in SQL** (not per-cycle file reads).
- Add `notes.ingest_pending BOOLEAN NOT NULL DEFAULT false`.
- Set it at the existing write chokepoint: `ResourceScanService.scan(absNotePath, content)`
  computes `boolean pending = !embedsNeedingIngest(content).isEmpty()` and persists via a
  new `NoteIndexRepository.setIngestPending(absNotePath, pending)`. (scan() already runs at
  the tail of `ImageScanService.registerImages` on every create/update/patch/rename/chrono/
  sync write, so the flag stays current; when ingest injects text + marker, the resulting
  write re-runs scan → flips it back to false.)
- **Image→text does NOT need gating** — it has its own dedup queue and doesn't mutate note
  text; ingest-injected keyframes get registered on the injection write naturally.

**Gate 1 — text embedding** (`NoteIndexRepository.findNotesNeedingEmbedding`):
add `AND ingest_pending = false` to the worklist WHERE clause. Effect: a note with a pending
video isn't embedded until ingest finishes; once the marker lands, flag flips, it embeds once.

**Gate 2 — flashcards** (`CardRepository.findNotesNeedingCards`):
add `AND ingest_pending = false`. Effect: **no card generation (no LLM spend) until the
note's content is final.** This is the main credit protector.

**Touchpoints:**
- Schema: `notes.ingest_pending` — add to BOTH `CardRepository.initSchema`/`NoteIndexRepository`
  schema init (wherever `notes` DDL lives) AND a migration `ALTER TABLE notes ADD COLUMN IF
  NOT EXISTS ingest_pending BOOLEAN NOT NULL DEFAULT false;` for the existing DB.
- `ResourceScanService.scan` — set the flag (inject `NoteIndexRepository`).
- `NoteIndexRepository.setIngestPending(path, bool)` — new method.
- `NoteIndexRepository.findNotesNeedingEmbedding` — `+ AND ingest_pending = false`.
- `CardRepository.findNotesNeedingCards` — `+ AND ingest_pending = false`.

**Path-key gotcha to verify:** `notes.path` is the ABSOLUTE `/vault/...` path (EmbeddingService
reads it via `Files.readString`). `ResourceScanService.scan` receives `absNotePath`. Set the
flag by absolute path — confirm both sides agree before wiring.

**Backfill race (minor, acceptable):** on first boot, `NoteEmbeddingWorker` (initialDelay 20s)
may fire before `ImageScanService.scanAll()` finishes flagging every note. Worst case a few
notes embed once prematurely; the next write/scan corrects via the hash diff. Optionally raise
NoteEmbeddingWorker `initialDelay` to run after scanAll, but not required.

**Acceptance:** a note with an un-ingested resource embed appears in neither embedding nor card
worklists; after the ingest marker lands, it appears in both exactly once.

---

## Phase 1b — Generalize the gate to ALL content mutators (cross-pipeline)

The readiness gate isn't ingest-specific — it's "is this note's content STABLE yet?"
Every upstream content mutator feeds the SAME two consumers (embedding + cards) via the
hash-diff, so each is a re-trigger:

- **Ingest injection** (Phase 1) — `ingest_pending`.
- **Sync / mobile-merge** (product-intent flow): phone-jotted notes sync down and
  conflict-resolve git-style against the pre-generated draft → the merge is another
  content mutation → re-embed + **re-card**. A note mid-conflict (user hasn't resolved)
  must NOT be embedded/carded yet, or it's processed against the un-merged version then
  redone after resolution. → future `merge_pending` / `conflict_pending` flag, set by the
  sync resolver, read by the SAME worklists. (`SyncWorker`/`SyncService`; merge + conflict
  resolution itself is [NOT IMPLEMENTED].)
- **Chrono rewrites + external Obsidian edits** — already hash-diff-covered; cheap for
  embedding, but they DO re-card. Mostly frontmatter, so likely fine to leave; revisit if
  card credit churn shows up.

**Design:** keep it additive — one boolean column per "not-stable-yet" reason, gates are
`AND ingest_pending = false AND merge_pending = false ...`. Same WHERE-clause pattern as
Phase 1, no new machinery. Conceptually this is the "content-stable" precondition for the
expensive consumers; the user explicitly does NOT want a full per-note lifecycle state
machine, so stay with additive boolean flags, not a status enum.

**End-to-end chain it enables:** ingest pre-generates draft → user consumes + jots on phone
→ sync down → conflict-resolve (gated) → on resolve, flags clear → embedding + cards run
ONCE on the final merged note. The gate is what keeps that whole chain single-pass and
credit-safe.

## Phase 2 — Embed batching (efficiency, pipeline #3)

**Problem:** `EmbeddingService.indexNote` calls `embed(chunk.text)` per chunk = one HTTP POST +
one batch-size-1 GPU forward per chunk. A 30-chunk note = 30 round-trips. The `/embed` endpoint
already accepts `{"texts":[...]}` and `embed_texts` runs the whole list in ONE GPU pass.

**Phase 2a (do this first — per-note batching, biggest win / smallest change):**
- New `EmbeddingService.embedBatch(List<String> texts) -> List<float[]>` — one POST with the
  full list; parse `embeddings[]` in order (the endpoint preserves order).
- Rewrite `indexNote`: first pass collects the changed chunks (hash != stored) into a list,
  call `embedBatch` once (sub-batch at a cap, e.g. `EMBED_BATCH=64`, to bound payload/RAM),
  then upsert each by index. Preserve: hash-skip of unchanged chunks; `deleteStaleChunks`;
  return false on batch failure so the note stays in the diff (retry contract intact).
- Edge: empty changed-set → no call.

**Phase 2b (optional, later — cross-note batching):**
- In `NoteEmbeddingWorker`, gather changed chunks across all 20 notes, embed in batches of 64,
  then upsert + `markEmbedded` per note. Bigger throughput on backfill but complicates the
  per-note success contract (a batch failure spans notes). Only do if 2a backfill still feels
  slow. Keep per-note `markEmbedded` semantics (only mark a note when ALL its chunks succeeded).

**Touchpoints:** `EmbeddingService` (new `embedBatch`, rewrite `indexNote`), optional
`NoteEmbeddingWorker`. Add `EMBED_BATCH` (env/constant). No embedder-side change needed.

**Acceptance:** indexing a 30-chunk note issues 1 `/embed` call (or ⌈30/64⌉), vectors land on
the correct chunk_index, unchanged chunks still skipped, embeddings byte-identical to per-chunk.

---

## Phase 3 — Documentation (the missing connective tissue)

Per-subsystem FLOWS.md files are good and stay as-is. What's missing is the CROSS-pipeline
orchestration doc — how a note moves through all five, and the ordering/gate contract.

**Create `FLOWS.md` at repo root (or `obsidian_optimizer/FLOWS.md`) as the integration map:**
- The "five independent hash-diff pollers over Postgres" model (state lives in `notes` /
  `note_chunks` / `pending_image_jobs` / `cards`; each worker = a WHERE-clause worklist).
- ONE diagram of a note's journey: write → ingest (mutates content) → [gate] → embedding +
  cards; image jobs in parallel.
- The **readiness-gate contract**: `notes.ingest_pending` — who sets it (ResourceScanService),
  who reads it (embedding + card worklists), why (credit protection + avoid rework).
- Keep it navigation-first per the FLOWS convention; link to per-subsystem FLOWS for detail,
  don't restate them.
- Update Change Index rows: `ingest_pending` flag (set: ResourceScanService; gates:
  findNotesNeedingEmbedding / findNotesNeedingCards), `EMBED_BATCH`.

---

## Phase 4 — Pre-launch testing (before the 3000-note run; avoid expensive prod testing)

**The risk:** unleashing on 3000 notes burns LLM credits (cards + image VLM) and GPU time. The
readiness gate (Phase 1) is the #1 protector against double-spend; this phase proves correctness
on a small corpus BEFORE the real run.

**4.1 Unit / repository tests (fast, no LLM):**
- Gate: `findNotesNeedingEmbedding` and `findNotesNeedingCards` exclude `ingest_pending=true`
  rows. (`embedsNeedingIngest` is already tested.)
- Batching: `EmbeddingService` sends one `/embed` with N texts (mock embedder), maps vectors by
  index, still skips unchanged chunks. Extend `embedder/tests` if any Python side changes.

**4.2 Integration (Testcontainers — pattern already exists: ChronoServiceIT, NoteLifecycleIT,
SyncServiceIT):**
- New IT: write a note with `![[clip.mp4]]` (no marker) → assert NOT in embedding/card worklists
  → simulate the ingest write-back (inject text + `<!-- ingest:clip.mp4 -->`) → assert now in
  both worklists, processed exactly once. End-to-end gate proof.

**4.3 Cost-bounded canary on REAL data (the "don't test in prod" mitigation):**
- Point `HOST_VAULT_PATH` at a COPY containing ~15–20 representative real notes: plain text,
  note+images, note+short video, note+audio, note+pdf, a card-eligible note (`sr_due` set), a
  note with an un-ingested resource (gate), a huge note, a tiny note.
- Bring the stack up; watch `GET /api/stats`. Verify: embeddings populate, a couple images
  caption, ONE video ingests end-to-end, a few cards generate — and crucially **no note is
  carded/embedded twice** (gate works) and credit usage is sane.
- Only after the canary passes, swap `HOST_VAULT_PATH` to the full 3000-note vault.

**4.4 Launch sequencing + guardrails:**
- **Stage the launch:** start with `CARDS_ENABLED=false`. Let ingest + image + embedding drain
  first (local GPU/whisper + free-tier VLM — cheap-ish). Once `/api/stats` shows ingest jobs DONE
  and embedding caught up, set `CARDS_ENABLED=true` to begin the expensive LLM card pass. The
  readiness gate makes this safe even if you don't stage, but staging gives a clean cost ramp.
- Existing throttles to confirm before launch: `cards.batch-limit` (default 10/pass, 30min cycle
  → cards spread over time, never 3000 at once); image worker free-provider sharding (the FLOWS
  call this a deliberately multi-day rate-limited operation — expected).
- Have a kill switch: `CARDS_ENABLED=false` + (optionally) stop the embedder to pause spend.

**Acceptance for "ready to launch":** 4.1 + 4.2 green; 4.3 canary shows correct single-pass
processing of each note type with bounded credit use.

---

## Execution order (when a session picks this up)

0. Rebuild + verify `fix/http1-and-cudnn9` (GPU provider, ingest 200/DONE, embedding >0/N).
1. Phase 1 (gates) — schema + flag set + two WHERE clauses + 4.1/4.2 gate tests. Commit.
2. Phase 2a (per-note batching) + tests. Commit.
3. Phase 3 (integration FLOWS doc). Commit.
4. Phase 4.3 canary on a 15–20 note copy. Fix anything. Then full 3000-note launch (4.4 staging).
5. Phase 2b (cross-note batching) only if backfill is still slow.

Branch per the git-discipline rule (don't work on master). Commit after each phase.
