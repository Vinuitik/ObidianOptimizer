# Capture — resource intake → durable ingest queue → notes

Files: CaptureController.java, CaptureRepository.java, CaptureIngestWorker.java, ../common/IngestClient.java

The user "sends resources from time to time" (shared links, pasted text, PWA share-target).
Each becomes a durable `capture` row and is drained into the embedder ingest pipeline by a
continuous background worker. Nothing is lost if the embedder is down or the app restarts —
the embedder's own job queue is in-memory; this table is authored, backed-up state.

## Intake → queue → drain → notes

```
POST /api/capture {url|text, title?}                         CaptureController.capture()
  text → storeTextResource() writes resources/files/{id}.md  (kept for Learn side-by-side)
  captureRepo.enqueue(id, type, ref, sourcePath, title)      → row status = 'queued'
  ingestWorker.nudge()                                        → drain now (no tick wait)
  → 200 {status:"queued", captureId}

CaptureIngestWorker (continuous)                             capture/CaptureIngestWorker.java
  @Scheduled tick (15s) ─┐
  nudge() (on capture)  ─┼─→ WorkerLane("capture-ingest").trigger(drain)   (1 drain at a time)
  onAppReady() ─────────┘
  drain():
    captureRepo.findQueued(batchLimit)          FIFO, oldest first
    per row: captureRepo.claim(id)              atomic 'queued'→'processing' (no double-submit)
             IngestClient.submit*(…)            text → submitText (reads the .md back)
                                                url  → submitStandalone
             ok        → stays 'processing'     (embedder now owns it)
             4xx       → 'failed'               (bad ref/route — retry won't help)
             5xx/down  → 'queued'               (released; next tick retries → survives outage)
```

To change drain cadence: `ingest.capture.delay-ms` (tick) / `ingest.capture.batch-limit`.
To disable: `ingest.enabled=false` (shared master switch with ResourceScanService) — queued
rows sit untouched until re-enabled. The `appReady` gate holds submission until Tomcat is
bound (the embedder publishes notes BACK to :8084; firing mid-boot would get connection-refused).

## The one gate — common/IngestClient

Every Java→embedder ingest submission funnels through `IngestClient` (embedder side is already
single-gated at `jobs.submit`). It owns the HTTP/1.1 transport (uvicorn drops bodies on h2c —
see ResourceScanService history) + `embedder.url`, and exposes typed helpers:
`submitInPlace(ref, notePath)` · `submitStandalone(captureId, ref, type)` · `submitText(captureId,
text, title)`. Callers: `CaptureIngestWorker` (standalone, from the queue) and
`ResourceScanService` (in-place, from note embeds — see ml/FLOWS.md). Returns
`Result{ok, status, jobId}` so the caller decides retry (5xx/down) vs fail (4xx).

## Lifecycle (capture.status)

```
queued ──drain/submit──> processing ──embedder publishes notes to _inbox──> (Inbox shows it)
   │                     │  │                                                      │
   4xx→failed  5xx→queued   synthesis 503 (LLM cooling) → deferred      user files/acks each note
   (retry)     (retry)      (+bundle_ref)     │                                    │
                              retryDeferred: resume from bundle      all filed → 'filed' + source trashed
                              (no re-extract) → processing
```
`queued` is NEW (this change): resources wait here until submitted. `processing`/`ready` are what
the Learn Inbox lists (`InboxController`); `filed` is set when every child note is triaged (see
`inbox/FLOWS.md`). In-place note snapshots (`InternalAgentController.createCapture`) skip the queue
— their ingest already ran — and are inserted straight at `processing`.

**`deferred` = synthesis waiting on LLM providers.** When the embedder DEFERS a job (all
providers cooling), `pollFailures` parks the capture `deferred` with the `bundle_ref` instead of
failing it; `retryDeferred` (@Scheduled `ingest.capture.retry-deferred-ms`, default 3min) resumes
synthesis from the saved bundle via `IngestClient.resume` → embedder `POST /ingest/resume` (no
re-download/re-whisper). Restart-safe: the state is this DB row, the bundle a file on the embedder
`/models` volume. Idempotency: resume is skipped if the capture already produced notes
(`noteIndex.findNotesByCapture` non-empty → settle to `processing`), so a status race can't
duplicate. `deferred` blocks the dedup guard (live) and is excluded from orphan cleanup.

## Technology Notes

- **Durable queue vs in-memory job queue.** The `capture` table survives restart; the embedder's
  `jobs.py` dict does not. That's the whole reason the drainer exists — capture-while-offline is
  submitted on recovery. Cost: a resource waits one drain (≤ tick, or instant via `nudge()`).
- **Atomic claim, not just the lane guard.** `WorkerLane` already prevents two overlapping drains,
  but `claim()` (`UPDATE … WHERE status='queued'`) is the real correctness guarantee — a `nudge()`
  racing a `tick()` can't double-submit the same row (which for standalone = duplicate notes, since
  the embedder only de-dups in-place (note,embed) jobs).
- **Indefinite retry on transport failure is intended.** A 5xx/unreachable row returns to `queued`
  forever until the embedder accepts it — matches ImageProcessingWorker's "leave PENDING". Only a
  4xx (the embedder rejected the request shape) is terminal (`failed`).
- **`embedder.url` default drift fixed.** Pre-centralization, ResourceScanService defaulted to
  `localhost:8000` and CaptureController to `embedder:8000`. `IngestClient` has one default
  (`localhost:8000`); the real value is `EMBEDDER_URL` in application.properties.

## Change Index

| Thing to change | Where |
|---|---|
| Capture intake endpoint | `CaptureController.capture()` (`POST /api/capture`) |
| Resource → queue insert | `CaptureRepository.enqueue()` (status `queued`) |
| Continuous drain cadence / batch | `ingest.capture.delay-ms` / `ingest.capture.batch-limit` env |
| Claim / queued query | `CaptureRepository.claim()` / `findQueued()` |
| Payload rebuilt from row | `CaptureIngestWorker.submit()` (text reads .md back) |
| The single embedder /ingest gate | `common/IngestClient` (`submitInPlace`/`submitStandalone`/`submitText`) |
| Embedder URL / submit timeout | `embedder.url` / `ingest.submit.timeout-ms` env |
| Master ingest on/off | `ingest.enabled` (shared with ResourceScanService) |
| Capture lifecycle transitions | `queued`→`processing` here; `filed` in `inbox/InboxController` |
| **Failure visibility** (job failed after submit) | `CaptureIngestWorker.pollFailures()` polls `IngestClient.listJobs()` → `CaptureRepository.markFailed()` (stranded `processing`→`failed`); DEFERRED → `markDeferred()` |
| **Synthesis durability retry** | `CaptureIngestWorker.retryDeferred()`/`drainDeferred()` → `IngestClient.resume(bundle_ref,…)`; cadence `ingest.capture.retry-deferred-ms` (3min) |
| **Deferred state + bundle** | `CaptureRepository.markDeferred/findDeferred/claimDeferred`; `bundle_ref` column; status `deferred` |
| **Orphan-source cleanup** ("no children → trash source") | `CaptureIngestWorker.cleanupOrphanSources()` (age + no-active-job + `countLiveReferencesToFile` guards) → `FileRepository.softDeleteFile`; env `ingest.cleanup.min-age-ms` |
| **Duplicate-capture guard** (409) | `CaptureController.capture()` → `CaptureRepository.existsLiveForSource()`; extension shows ⚠️ |
| Per-note retention (last note deleted → trash media) | `inbox/InboxController.discard()` (`local:` + `trashLocalMedia`; LOCAL_MEDIA_RETENTION §4) |
