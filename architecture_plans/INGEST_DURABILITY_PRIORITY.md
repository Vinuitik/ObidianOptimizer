# Ingest Durability + Resource Priority — Design & Execution Plan

Status: PLANNED (2026-07-09). Three coordinated changes, each shippable + tested on its own.

## The three problems (in plain terms)

1. **No priority on scarce resources.** Image-captions, flashcards, and ingest-synthesis
   all draw LLM tokens from the *same* free-tier providers (the vision and text chains
   overlap: gemini/github/mistral/groq are in both). Today it's first-come-first-served.
   We want **ingest > flashcards > image-captions** when tokens are scarce. The GPU is
   already arbitrated (`gpu_slot`: whisper/CLIP > embedder); only the LLM router lacks priority.

2. **Synthesis has no durability.** An ingest job is two steps: (1) extract the transcript
   [cheap, saved to disk] and (2) write notes with an LLM [needs a token]. When step 2 hits
   `503 all providers cooling`, the job is marked FAILED and thrown away — transcript and all.
   The **standalone-capture** path (extension/PWA/MCP "I sent a link") has *zero* retry: it goes
   `processing → failed` and the work is silently lost. (In-place note-embed ingests already
   retry every 5 min via `ResourceScanService` + the `ingest_pending` flag — they're durable,
   just wasteful; out of scope here, see §4.)

3. **No caption fallback.** `extract_av._youtube_captions` raises "no usable captions —
   re-run with force_whisper" when a video has no subtitles. The whisper path (download audio
   → faster-whisper) EXISTS but is never triggered automatically. So a captionless video just fails.

## Change 1 — Router priority (host-wrapper)

**Files:** `host-wrapper/llm_router.py`, `host-wrapper/main.py`, embedder call sites.

- Add `PRIORITY = {"high": 0, "medium": 1, "low": 2}` (lower = more important).
- `Router._acquire(capability, skip, priority)`: register the caller's priority in a
  `_waiters` multiset while it waits. A provider that frees up goes to the **highest-priority
  waiter first**: a thread only *takes* an available provider if no strictly-higher-priority
  waiter is currently blocked; otherwise it yields (waits one more beat) so the important
  request wins. Deregister on exit (finally).
- `complete_text` / `complete_vision` / `complete_vision_batch` gain a `priority="medium"` arg,
  threaded into `_run` → `_acquire`.
- `main.py` `/complete` + `/process-image[s]` read `priority` from the request JSON (default
  "medium"), pass it through.
- **Call sites set priority:**
  - ingest synthesis (`synthesize._complete`) → `priority="high"`
  - flashcard generation (`flashcards/generate.py`) → `priority="medium"`
  - image captioning (`host-wrapper /process-image`, called by Java `ImageProcessingWorker`) → `priority="low"`
- Starvation note: no aging (finite ingest bursts; the user explicitly wants ingest to win).
  Add aging later if low-priority work is seen to stall.
- **Tests:** extend `test_scheduling_latency.py` — a HIGH waiter gets a freed provider before a
  LOW waiter that was already waiting; equal priority keeps FIFO-ish fairness; priority never
  deadlocks (all-benched still fails fast per the existing doomed-acquire guard).

## Change 2 — Standalone synthesis durability (capture-side, Option A)

The DB (Postgres `capture` table) holds the "come back and finish this later" note, so it
survives an embedder OR backend restart. The on-disk bundle holds the completed extraction.

### Embedder (`embedder/ingest/jobs.py`, `main.py`, `synthesize.py`)
- **Re-save the bundle right before `synthesize`** (after `_ensure_local_copy`), so the on-disk
  bundle is the *complete pre-synthesis state* incl. keyframes + local media (today it's saved
  at extract time, before those). This is what makes resume skip the expensive half.
- **`SynthesisError` carries `.status`** (set from the wrapper's HTTP code). `_complete` sets it.
- **`jobs._run` catches provider-exhaustion** (`SynthesisError.status == 503`) at the synthesize
  stage and sets `job["status"] = "DEFERRED"` (not FAILED), keeping `bundle_path` + the resume
  metadata (`note_path`, `capture_id`, `source_type`, `title`, `ref`). Other errors still FAIL.
- **`POST /ingest/resume {bundle_path, capture_id, source_type, title, ref, note_path?}`** →
  `jobs.submit(..., resume_bundle=bundle_path)`; `_run` with `resume_bundle` set **loads the
  bundle from disk and skips straight to synthesize+publish** (no re-extract, no re-download).
  On another 503 → DEFERRED again (idempotent).

### Backend (`capture/CaptureRepository.java`, `CaptureIngestWorker.java`)
- `capture` gains status value **`deferred`** and column **`bundle_ref`** (the bundle path).
- **`pollFailures`** (renamed intent): a DEFERRED embedder job → set capture `deferred` +
  store `bundle_ref` (instead of `failed`). A genuinely FAILED job still → `failed`.
- **New drain `retryDeferred()`** (`@Scheduled`, slow cadence e.g. 2–5 min so we don't hammer
  cooling providers): for each `deferred` capture → `IngestClient.resume(bundle_ref, …)`.
  Success → embedder publishes notes → capture flows to `processing`/inbox as normal.
  Still 503 → embedder returns DEFERRED → capture stays `deferred`, retried next tick.
- **Restart safety:** the `deferred` capture + `bundle_ref` live in Postgres; the bundle FILE
  lives on the `MODEL_CACHE` docker volume. Either process can restart and the retry still
  finds both. This is the whole point of choosing the DB over the embedder's RAM.
- **Tests:** `CaptureIngestWorkerTest` — a DEFERRED job → capture `deferred` (not `failed`) +
  `bundle_ref` set; `retryDeferred` calls `resume`; success clears it; repeated 503 keeps it
  `deferred`. Embedder `test_ingest` — resume-from-bundle skips extraction; 503 at synthesis →
  DEFERRED (not FAILED) + bundle retained.

## Change 3 — Auto-whisper fallback (embedder)

**File:** `embedder/ingest/extract_av.py`.
- `extract()` youtube branch: try `_youtube_captions`; on its "no usable captions" /
  fetch-failure `RuntimeError`, **fall through to `_youtube_whisper`** (download bestaudio →
  faster-whisper) instead of raising. Log the downgrade loudly.
- Cost note: whisper on a 90-min lecture is minutes on GPU, longer on CPU — acceptable as a
  fallback (captions are the fast path; whisper is the safety net).
- **Test:** `test_extract_av` (or a new case) — captions raise → whisper path invoked (mock
  `_youtube_whisper` + `_youtube_captions`); captions present → whisper NOT called.

## §4 Out of scope (flagged for follow-up)

- **In-place note-embed durability optimization.** Already restart-durable via
  `ResourceScanService.retryPendingIngests` (5-min, `ingest_pending` flag), but it *re-extracts*
  each retry — re-downloading the video + re-running whisper/keyframes while providers are down.
  A resume-from-bundle for in-place (keyed by note+embed) would remove that waste. Deferred:
  it's an efficiency win, not a correctness gap. `[NOT IMPLEMENTED]`
- **Router priority aging** (anti-starvation for low-priority work). `[NOT IMPLEMENTED]`

## Execution order (each a commit, tests green before moving on)

1. Change 1 (router priority) — self-contained, host-wrapper only.
2. Change 3 (whisper fallback) — self-contained, embedder only.
3. Change 2 (durability) — embedder DEFERRED + resume, then backend capture `deferred` + retry.
