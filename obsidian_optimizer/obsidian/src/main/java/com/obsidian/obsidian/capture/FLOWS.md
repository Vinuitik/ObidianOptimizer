# Capture — resource intake → durable ingest queue → notes

Files: CaptureController.java, CaptureRepository.java, CaptureIngestWorker.java, ../common/IngestClient.java, ../common/RabbitQueueConfig.java, ../common/OutboxRepository.java, ../common/PipelineFailureRepository.java, ../internalapi/InternalAgentController.java (track fan-out), ../tracks/TrackRepository.java

The user "sends resources from time to time" (shared links, pasted text, PWA share-target).
Each becomes a durable `capture` row; the `capture` table is the status/UI source of truth
(unchanged schema). Since QUEUE_UNIFICATION_PLAN.md Phase 4, SUBMISSION transport is
RabbitMQ (outbox chokepoint → `capture` queue → `@RabbitListener`), not a fast poll — see
"Retry ladder" below. `CaptureIngestWorker`'s own poll (`tick()`, now 5min) is the SAFETY
NET, same shape Phase 3 gave Group A. Nothing is lost if the embedder or the broker is down
or the app restarts — the embedder's own job queue is in-memory; this table + the outbox +
RabbitMQ's own durability are what survive a restart.

## Intake → outbox → RabbitMQ → submit → notes

```
POST /api/capture {url|text, title?, trackId?|newTrackTitle?+newTrackType?}
                                                              CaptureController.capture()
  resolveTrackId(): newTrackTitle set → trackRepo.create() first; else the given trackId;
    neither → null (untagged — today's exact behavior). See tracks/FLOWS.md Phase 1b.
  text → storeTextResource() writes resources/files/{id}.md  (kept for Learn side-by-side)
  enqueueAndPublish(id, type, ref, sourcePath, title)   TransactionTemplate, ONE transaction:
    captureRepo.enqueue(...)                              → row status = 'queued'
    outboxRepo.enqueue(CAPTURE_QUEUE, {id})                → outbox row, published on commit
  trackId resolved → captureRepo.setTrackId(id, trackId)     → capture.track_id set
  ingestWorker.nudge()                             → ALSO trigger the poll safety net now
    (deliberately redundant with the outbox publish above — claim() is atomic so whichever
    path (Rabbit listener or this poll) reaches the row first wins; the other is a no-op)
  → 200 {status:"queued", captureId}

POST /api/capture/file (multipart)                           CaptureController.captureFile()
  PWA share-sheet shares a PDF/video/audio FILE (public/sw.js handleShareFile)
  classifyFile(ext) → pdf|video|audio (else 415); empty → 400
  storeBinaryResource() writes resources/files/{id}.{ext}    → BOTH source_ref and local copy
  captureRepo.enqueue(id, type, path, path, filename)        → 'queued' → same drain → standalone

POST /api/capture {url: playlist page}                       CaptureController.capturePlaylist()
  isPlaylistUrl(url): video host + /playlist path + list= param (NOT any watch?...&list=
    link — that's a single video the user meant to capture, so it's excluded on purpose)
  → embedder POST /playlist/expand {url}                      list ONLY, no download
    (embedder: download/downloader.list_playlist_entries — extract_flat, skip_download)
  per entry: existsLiveForSource dedup → captureRepo.enqueuePlaylistItem(…, playlistId, i)
    → N rows, status='queued', SAME playlist_id, playlist_position = original order
  ingestWorker.nudge() → same drain below picks them up FIFO (already serial: batchLimit
    per tick + the embedder's own ingest queue is a single worker thread) — videos
    download and get noted ONE AT A TIME, each under its own capture-id folder, and the
    caller gets an immediate response instead of waiting for the whole playlist
  → 200 {status:"queued", playlistId, count, skipped}         (skipped = already in pipeline)

CaptureIngestWorker — TWO paths into the same processCapture() body:

  INSTANT (primary): @RabbitListener(CAPTURE_QUEUE) onCaptureMessage(message)
    attempt = message header x-attempt-count (absent → 0, i.e. fresh from the outbox)
    attempt==0 → captureRepo.claim(id)          atomic 'queued'→'processing'
    captureRepo.get(id), guard status=='processing' (idempotency: stale/duplicate redelivery)
    → processCapture(c, attempt)

  SAFETY NET (fallback): @Scheduled tick (5min, was 15s) / nudge()
    WorkerLane("capture-ingest").trigger(drain)         (1 drain at a time)
    drain(): captureRepo.findQueued(batchLimit)         FIFO, oldest first — only rows never
             claimed by the instant path, or whose outbox publish was lost
      per row: captureRepo.claim(id) → processCapture(c, 0)

  processCapture(Capture c, int attempt):               shared by BOTH paths above
    IngestClient.submit*(…)            text → submitText (reads the .md back); url → submitStandalone
    ok        → stays 'processing'     (embedder now owns it; pollFailures reconciles the outcome)
    4xx       → deadLetter(id, err)    straight to capture.deadletter — NO ladder (retry won't help)
    5xx/down  → next rung, or deadLetter(id, err) if the ladder (3 rungs) is exhausted

Per-note track fan-out (Phase 1b, capture.track_id set)          jobs.py + InternalAgentController
  embedder synthesis, per note produced:
    publish.create_note(folder, title, content, capture_id=capture_id)
      → POST /api/internal/notes {folder, name, content, captureId}
        InternalAgentController.createNote(): write note as always, then linkToTrack():
          captureRepo.get(captureId).trackId() != null → trackRepo.addItem(trackId, name, path)
          (best-effort — a track-tagging hiccup never fails note delivery)
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
queued ──claim/submit──> processing ──embedder publishes notes to _inbox──> (Inbox shows it)
   │                     │  │                                                      │
   │          4xx │  5xx/down │ synthesis 503 (LLM cooling) → deferred   user files/acks each note
   │        (dead- │ (retry   │  (+bundle_ref)     │                                │
   │         letter│  ladder) │      retryDeferred: resume from bundle  all filed → 'filed' + trashed
   │        skip   ▼          │       (no re-extract) → processing
   │        ladder) capture.deadletter (after 3 rungs exhausted, or an
   │                 immediate 4xx) → markFailed + pipeline_failures row
   │                          │
   └───── manual POST .../retry ──────────────────────┘  (re-enters at rung 1, bypasses everything)
   failed (GET /api/capture/failed lists it; POST .../retry|dismiss)
```
`queued` waits here until claimed (by the instant Rabbit listener or the poll safety net).
`processing`/`ready` are what the Learn Inbox lists (`InboxController`); `filed` is set when
every child note is triaged (see `inbox/FLOWS.md`). In-place note snapshots
(`InternalAgentController.createCapture`) skip the queue — their ingest already ran — and are
inserted straight at `processing`. **A row riding the retry ladder stays `processing`**
(never flips back to `queued`) — see Retry Ladder below for why.

**`deferred` = synthesis waiting on LLM providers.** When the embedder DEFERS a job (all
providers cooling), `pollFailures` parks the capture `deferred` with the `bundle_ref` instead of
failing it; `retryDeferred` (@Scheduled `ingest.capture.retry-deferred-ms`, default 3min) resumes
synthesis from the saved bundle via `IngestClient.resume` → embedder `POST /ingest/resume` (no
re-download/re-whisper). Restart-safe: the state is this DB row, the bundle a file on the embedder
`/models` volume. Idempotency: resume is skipped if the capture already produced notes
(`noteIndex.findNotesByCapture` non-empty → settle to `processing`), so a status race can't
duplicate. `deferred` blocks the dedup guard (live) and is excluded from orphan cleanup.

## Retry ladder (Phase 4 — replaces the old hand-rolled `retryFailed`/`retryDeferred` pollers)

A submit failure is NOT immediately `failed` any more. `processCapture()` classifies the
`IngestClient.Result`:

- **4xx (embedder rejected the request itself — bad ref/route)**: retrying won't help.
  Straight to `capture.deadletter`, skipping the ladder entirely.
- **5xx/transport (embedder down or busy)**: rides the ladder — a small, fixed, NOT
  per-message-configurable sequence of RabbitMQ queues, each a pure backoff timer:

  ```
  capture (attempt N fails, 5xx) ──publish──> capture.wait.1h  (x-attempt-count=1)
                                                    │ TTL expires (x-message-ttl)
                                                    ▼ DLX (dead-letter-exchange="", routing-key="capture")
                                               capture (redelivered, attempt=1)
                                                    │ fails again
                                                    ▼
                                               capture.wait.6h  (x-attempt-count=2)
                                                    │ … same TTL→DLX→redeliver …
                                                    ▼
                                               capture.wait.24h (x-attempt-count=3)
                                                    │ fails again → ladder exhausted (attempt 4 > 3 rungs)
                                                    ▼
                                               capture.deadletter
  ```
  Rungs: 1h → 6h → 24h (`capture.retry.rung{1,2,3}-ttl-ms`, `RabbitQueueConfig`). No consumer
  ever attaches to a wait queue — a message just sits until its TTL expires, then the broker
  itself moves it back onto `capture` via the default exchange. This IS the entire backoff
  mechanism; no Java timer/scheduler is involved. `x-attempt-count` is a message header,
  carried forward by RabbitMQ's DLX (headers survive dead-lettering), read by
  `CaptureIngestWorker.onCaptureMessage` to decide `processCapture`'s next move.

**`capture.deadletter`** — the graveyard once the ladder (or an immediate 4xx) gives up.
`CaptureIngestWorker.onCaptureDeadLetter` does exactly two things (`recordDeadLetter`):
(a) `captureRepo.markFailed(id, error)` — the SAME method the pre-Rabbit code already used,
so `GET /api/capture/failed`, `SyncPage.loadFailed()`, and manual retry/dismiss are all
UNCHANGED from the frontend's perspective; (b) `PipelineFailureRepository.record("capture",
"ingest_submit", {captureId, sourceRef, sourceType}, null, error, null)` — a plain JDBC
insert into the SAME `pipeline_failures` table `embedder/failures.py` writes (see Change
Index) — chosen over a Python-side write because the whole point of that table is to be a
single shared ledger regardless of which pipeline (Java or Python) produced the failure;
debugging a capture dead-letter now looks identical to debugging an ingest failure.

**Dead-lettering does NOT poison a capture forever.** It only stops the AUTOMATIC ladder —
`POST /api/capture/{id}/retry` → `requeueFailed()` (`failed`→`queued`) → `ingestWorker.nudge()`
re-enters the exact same flow from rung 1 (attempt 0), same as a brand new capture. There is
still no lifetime cap on MANUAL retries — the old "forever, but session-capped" behavior's
spirit is preserved: automatic retries are now bounded by the ladder (3 rungs, not an
in-memory session counter), but a human can always keep trying.

**`ridingLadder` (in-memory, `CaptureIngestWorker`) — the one deliberate touch to
`cleanupOrphanSources()`.** A capture riding the 6h/24h rung stays `processing` for far
longer than `cleanupMinAgeMs` (30min default) with no notes and no active embedder job — by
the OLD orphan-sweep predicate that looks exactly like an abandoned row, and it would trash
the kept source file out from under a capture that's still legitimately auto-retrying. Fixed
with the smallest possible guard: `sweepOrphan()` also skips any id in `ridingLadder` (added
on `publishToRung`, removed on success/dead-letter). Lost on restart, like the old
`sessionRetryAttempts` was — a restart during a rung wait re-exposes the row to the sweep for
at most one cleanup cycle (~2min default). Accepted, rare edge case; flagged here because
item 4 of the Phase 4 brief asked for orphan-cleanup to stay untouched, and this is the one
line that isn't.

## Technology Notes

- **Durable queue vs in-memory job queue.** The `capture` table survives restart; the embedder's
  `jobs.py` dict does not. That's the whole reason the drainer exists — capture-while-offline is
  submitted on recovery. Cost: a resource waits at most one safety-net tick (5min) in the rare
  case the instant Rabbit path is somehow lost; the common case is sub-second.
- **Atomic claim, not just the lane guard.** `WorkerLane` already prevents two overlapping poll
  drains, but `claim()` (`UPDATE … WHERE status='queued'`) is the real correctness guarantee — the
  instant Rabbit listener and the poll safety net (or two overlapping drains) racing for the same
  row can't double-submit it (which for standalone = duplicate notes, since the embedder only
  de-dups in-place (note,embed) jobs). Deliberately redundant: `nudge()` still triggers an
  immediate poll drain on every enqueue on TOP of the outbox publish, exactly like before Phase 4
  — the atomic claim is what makes that safe rather than a race condition.
- **A 5xx/transport failure now backs off instead of hammering immediately.** Before Phase 4 a
  5xx row went straight back to `queued` and could be re-tried on the very next tick (15s) —
  effectively no backoff at all. Now it rides the retry ladder (1h → 6h → 24h) — see Retry Ladder
  above. Only an immediate 4xx (the embedder rejected the request shape) skips the ladder.
- **Outbox reuses Phase 3's infra as-is.** `CaptureController.enqueueAndPublish` /
  `enqueuePlaylistItemAndPublish` are the Group B equivalent of `ImageScanService.registerImages`'s
  chokepoint — same `OutboxRepository`/`OutboxRelay` (`@TransactionalEventListener(AFTER_COMMIT)`
  + 5s sweep fallback), same `outbox_events` table, just a different `queue_name` (`capture`
  instead of `embed`/`embed-chunk`). Uses `TransactionTemplate` rather than `@Transactional`
  because `capturePlaylist()` calls the per-item insert via self-invocation from `capture()` —
  a proxy-based annotation would silently not apply there (same landmine Phase 3 hit in
  `ImageProcessingWorker.handleResult`).
- **`CaptureIngestWorker`/`PollingQueueWorker` — a deliberate non-migration.** Unlike Group A,
  `CaptureIngestWorker`'s poll loop was NOT refactored onto `WorkQueue<T>`/`PollingQueueWorker`
  (Phase 2's abstraction) — `RetryPolicy` doesn't yet model DLX/TTL ladders (only
  `unbounded()`/`capped(n)`, see its own doc comment: "future phases add .backoff(...),
  .deadLetter(...)"), so forcing it through that interface today would add indirection with
  nothing yet to plug into it. Same call Phase 3 made for `ResourceScanService.retryPendingIngests`.
- **`embedder.url` default drift fixed.** Pre-centralization, ResourceScanService defaulted to
  `localhost:8000` and CaptureController to `embedder:8000`. `IngestClient` has one default
  (`localhost:8000`); the real value is `EMBEDDER_URL` in application.properties.
- **Playlist rows reuse `capture`, not a separate table.** No new queue was needed: `drain()`
  already claims/submits rows independently and in FIFO order regardless of how many were
  inserted in one request, so N playlist rows behave exactly like N separate captures — the
  "one at a time" and "own folder per video" requirements were already properties of the
  existing pipeline (folder = `publish.inbox_folder(capture_id)`, one worker thread on the
  embedder side). `playlist_id`/`playlist_position` are metadata for future grouping/progress
  UI only — nothing currently reads them back.
- **Playlist expand call is unbounded/untimed on the Java side beyond a 30s HTTP timeout.** A
  very large playlist (hundreds of videos) means one slow `/playlist/expand` round-trip before
  any row is queued; there's no pagination. If this becomes a problem, cap entries or stream them.
- **Partial-failure handling is best-effort.** If `enqueuePlaylistItem` throws partway through the
  loop (e.g. a DB hiccup), rows already inserted stay `queued` (durable, will still drain) but the
  HTTP response falls through to the outer `catch` and reports a generic 500 — the user may see an
  error even though most of the playlist was queued successfully. Re-submitting the same playlist
  URL is safe (the dedup check skips already-live videos).

## Change Index

| Thing to change | Where |
|---|---|
| Capture intake endpoint | `CaptureController.capture()` (`POST /api/capture`, url/text) |
| Shared-file intake (PDF/av) | `CaptureController.captureFile()` (`POST /api/capture/file`, multipart); `classifyFile`/`storeBinaryResource` |
| Resource → queue insert + outbox publish | `CaptureController.enqueueAndPublish()` / `enqueuePlaylistItemAndPublish()` (one TransactionTemplate) → `CaptureRepository.enqueue()` (status `queued`) + `OutboxRepository.enqueue(CAPTURE_QUEUE, {id})` |
| Instant submit path | `CaptureIngestWorker.onCaptureMessage()` (`@RabbitListener(CAPTURE_QUEUE)`) |
| Safety-net poll cadence / batch | `ingest.capture.delay-ms` (default 5min) / `ingest.capture.batch-limit` env |
| Claim / queued query | `CaptureRepository.claim()` / `findQueued()` |
| Shared submit-and-route logic (both paths) | `CaptureIngestWorker.processCapture()` |
| Payload rebuilt from row | `CaptureIngestWorker.submit()` (text reads .md back) |
| The single embedder /ingest gate | `common/IngestClient` (`submitInPlace`/`submitStandalone`/`submitText`) |
| Embedder URL / submit timeout | `embedder.url` / `ingest.submit.timeout-ms` env |
| Master ingest on/off | `ingest.enabled` (shared with ResourceScanService) |
| Capture lifecycle transitions | `queued`→`processing` here; `filed` in `inbox/InboxController` |
| **Failure visibility** (job failed after submit) | `CaptureIngestWorker.pollFailures()` polls `IngestClient.listJobs()` → `CaptureRepository.markFailed(id, error)` (stranded `processing`→`failed`, records `last_error`); DEFERRED → `markDeferred()` |
| **Retry ladder** (5xx/transport failures) | `RabbitQueueConfig` (`CAPTURE_QUEUE`/`CAPTURE_RUNG_QUEUES`/`CAPTURE_DEADLETTER_QUEUE`, `capture.retry.rung{1,2,3}-ttl-ms` = 1h/6h/24h) → `CaptureIngestWorker.publishToRung()`; header `x-attempt-count` |
| **4xx / ladder-exhausted → dead-letter** | `CaptureIngestWorker.deadLetter()` → `capture.deadletter` queue → `onCaptureDeadLetter()`/`recordDeadLetter()` → `CaptureRepository.markFailed()` + `common/PipelineFailureRepository.record("capture", "ingest_submit", …)` |
| **cleanupOrphanSources() ladder-wait guard** (the one touch to an otherwise-untouched sweep) | `CaptureIngestWorker.ridingLadder` (in-memory `Set`), checked in `sweepOrphan()` |
| **Failed-capture list / manual retry / dismiss** (bypasses the ladder entirely, re-enters at rung 1) | `GET /api/capture/failed` · `POST /api/capture/{id}/retry` · `POST /api/capture/{id}/dismiss` → `CaptureController` + `CaptureRepository.listFailed/requeueFailed/dismissFailed` — UNCHANGED from Phase 4 |
| **Synthesis durability retry** | `CaptureIngestWorker.retryDeferred()`/`drainDeferred()` → `IngestClient.resume(bundle_ref,…)`; cadence `ingest.capture.retry-deferred-ms` (3min) |
| **Deferred state + bundle** | `CaptureRepository.markDeferred/findDeferred/claimDeferred`; `bundle_ref` column; status `deferred` |
| **Orphan-source cleanup** ("no children → trash source"), `processing` only — `failed` is excluded, see failed-capture rows above | `CaptureIngestWorker.cleanupOrphanSources()` (age + no-active-job + `countLiveReferencesToFile` guards) → `FileRepository.softDeleteFile`; env `ingest.cleanup.min-age-ms` |
| **Duplicate-capture guard** (409) | `CaptureController.capture()` → `CaptureRepository.existsLiveForSource()`; extension shows ⚠️ |
| Per-note retention (last note deleted → trash media) | `inbox/InboxController.discard()` (`local:` + `trashLocalMedia`; LOCAL_MEDIA_RETENTION §4) |
| **Playlist URL detection** | `CaptureController.isPlaylistUrl()` (`PLAYLIST_PATH`/`LIST_PARAM` regexes, video-host gated) |
| **Playlist expansion (list-only, no download)** | `CaptureController.capturePlaylist()` → embedder `POST /playlist/expand` → `download/downloader.list_playlist_entries` (embedder `FLOWS.md`) |
| **Playlist row insert** | `CaptureRepository.enqueuePlaylistItem(id, type, ref, path, title, playlistId, position)`; columns `playlist_id`/`playlist_position` |
| **Capture-time track tag** (Phase 1b) | `capture.track_id` column; `CaptureController.resolveTrackId()`; `CaptureRepository.setTrackId()`; fan-out on note creation via `InternalAgentController.linkToTrack()` — see tracks/FLOWS.md |
