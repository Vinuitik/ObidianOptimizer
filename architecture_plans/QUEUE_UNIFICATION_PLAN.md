# Queue Unification Plan — one abstraction instead of 15 hand-rolled pollers, RabbitMQ pilot for the ones that need it

Status: planned, not started. Written 2026-09-01.

Origin: a blocked Instagram-reel embed in the Learn view traced back to the embedder's
best-effort yt-dlp download failing silently (login-wall/rate-limit) with no retry and no
visible error. Chasing that bug surfaced the real issue — retry/backoff/dead-letter logic
has been reinvented independently, in two languages, every time a new pipeline needed one.
Full inventory below.

Builds on [PIPELINE_HARDENING_PLAN](PIPELINE_HARDENING_PLAN.md)'s "five-pipeline hash-diff
model" ("every pipeline is an independent hash-diff poller over Postgres. No message bus,
no orchestrator.") — this plan is the infra-level answer to that observation, but ONLY for
the subset of pipelines that actually behave like a queue (see Non-goals — most of them don't
need a broker, they need a shared abstraction).

---

## Inventory — 15 queue-like structures, 4 recurring shapes

**Group A — DB diff-predicate pollers** (Java, `@Scheduled`, no dedicated queue table — the
work-list IS a `WHERE` clause on an existing entity table):
- `NoteEmbeddingWorker` — `notes.content_hash <> notes.embedded_hash`
- `ChunkEmbeddingReconciler` — `note_chunks.embedding IS NULL`
- `ResourceScanService.retryPendingIngests` — `notes.ingest_pending = true`

Shape is identical across all three: find-candidates query + batch limit + `WorkerLane` +
unbounded retry, no backoff, no dead-letter. Copy-pasted three times with a different SQL
predicate each time.

**Group B — Explicit status-column queue tables** (Java, each with its OWN hand-rolled
claim/retry/backoff/dead-letter shape, all different from each other):
- `capture` (`queued/processing/failed/deferred/filed`) — richest lifecycle: atomic claim,
  session-capped retry for `failed`, unbounded retry for `deferred`, orphan sweep.
- `sync_queue` (`PENDING/DONE/FAILED/DELETE_PENDING`) — `retry_count` cap → dead-letter,
  exponential backoff+jitter, fixed thread-pool concurrency (3), tombstones, weekly janitor.
- `pending_image_jobs` (`PENDING/DONE/SKIPPED`) — unbounded retry, `SKIPPED` as a quasi-dead
  letter that self-heals via a daily requeue sweep.
- `card_flags` / `card_gen_attempts` — a ledger (one row per note+hash), not a full state
  machine; caps retries by de-dup key rather than a counter.

**Group C — producer-only, not a queue itself:** `SubscriptionPollWorker` discovers new
items and enqueues them into `capture` (Group B). No separate durable state of its own.

**Group D — Python in-memory job-dict** (`dict` + `Lock` + status string + thread(s),
independently written three times):
- `ingest/jobs.py` — ONE shared worker thread (GPU-aware, evicts/reclaims the single GPU slot)
- `download/jobs.py` — one OS thread PER download call, no retry
- `tracks/minicourse_jobs.py` — one thread per call, two-stage human-approval gate

**Group E — true local FIFO queue:** `ingest/escalation.py` — a real `queue.Queue` with a
singleton consumer thread. Flag-gated OFF by default (`AGENT_ESCALATION_ENABLED`).

**Group F — external-medium queue:** `MailboxConsumeService` — the "queue" is encrypted event
files the PWA writes to Google Drive; Postgres holds only an idempotency ledger
(`consumed_events`), not the work itself.

**Confirmed NOT a queue** (stateless sweeps, no retry/status column): Chrono's daily jobs
(FileMover/Bankruptcy/SpreadCheck), capture-time track fan-out, `TodayPlanService`.

---

## Decision matrix — action per group

| Group | Members | Action |
|---|---|---|
| A — diff pollers | NoteEmbeddingWorker, ChunkEmbeddingReconciler, ResourceScanService.retryPendingIngests | **Move to RabbitMQ**, via a transactional outbox (keeps self-healing — see below). Hash columns stay, but shrink from "trigger" to "idempotency check + rare safety-net sweep." |
| B — status-table queues | capture, sync_queue, pending_image_jobs, card_flags | **Move to RabbitMQ.** These already ARE state machines (claim/retry/dead-letter); a broker replaces the hand-rolled version of exactly that. Existing UUID PKs double as correlation ids — no new columns needed for the common case. |
| C — producer-only | SubscriptionPollWorker | **Skip.** Not a queue — it just calls `captureRepo.enqueue()`. Nothing to migrate on its own; it rides along once `capture` (Group B) moves. |
| D — Python job-dicts | `download/jobs.py`, `minicourse_jobs.py` | **Skip.** No real problem today — restart-loses-status is an explicit, already-accepted tradeoff ("idempotent, re-triggerable"), and moving to Rabbit would require these to gain a durable status store for zero benefit. |
| D — Python job-dicts | `ingest/jobs.py` | **Centralize via a different pattern (Strategy), not RabbitMQ.** Its single-worker-thread model exists for GPU arbitration — a LOCAL concurrency concern, not a distributed one. What it DOES need: stop losing failure detail on restart. Give it durable failure recording via the new `pipeline_failures` table (below) instead of growing more bespoke sidecar files like `inplace_deferred.json`. |
| E — local FIFO | `ingest/escalation.py` | **Skip moving it. NOT activated.** It's already the "elevate to fixes" mechanism, just flag-gated off (`AGENT_ESCALATION_ENABLED`). Deliberately left OFF: it arms a stateful, unsupervised agent with real tool access (browser control, LLM spend) on every ingest failure — a decision for the user to make explicitly, not to flip silently. Phase 4's capture dead-letter does NOT hand off to it (see Phase 4 notes). Flipping the flag + wiring the hand-off is a small, independent follow-up whenever that's wanted. |
| F — external medium | MailboxConsumeService (Drive files) | **Skip.** A broker can't replace Drive as the offline transport; nothing to gain. |
| PWA outbox/mailbox | IndexedDB, client-side | **Skip — confirmed out of scope.** Must stay client-side; it queues while the device itself has no connectivity. |

---

## Failure recording — `pipeline_failures` (built, see Phase 0)

Dead-lettering something isn't useful without the exact input that broke it. One shared table,
written by every pipeline (not a bespoke `last_error` string per queue):

```
pipeline_failures(
  id, occurred_at,
  source,          -- 'ingest' / 'sync' / 'cards' / ...
  stage,           -- 'video_download' / 'whisper_extract' / 'synthesize' / ...
  input_payload,   -- the EXACT thing that was submitted (JSON)
  error_type, error_message,
  bundle_ref,      -- pointer to saved intermediate state, if any
  resolved_at
)
```

Debugging becomes: `SELECT * FROM pipeline_failures WHERE resolved_at IS NULL` → see the exact
input + exact error → fix the root cause → replay by resubmitting `input_payload` verbatim to
the same endpoint → mark `resolved_at`. This is also the eventual write target for every Group
B dead-letter (Phase 5+): whatever queue a message dies in, its failure lands in the same
table, in the same shape.

`embedder/failures.py` (`record_failure()`) is the first, standalone use of it — see Phase 0.

---

## The actual problem

It's not "queues are messy" in the abstract — it's that **the same four retry shapes get
reinvented from scratch every time a pipeline needs "try this, retry later on failure, give up
and surface it after N tries"**: unbounded-no-backoff (Group A, `pending_image_jobs`), capped
with exponential backoff + dead-letter (`sync_queue`), unbounded-with-daily-self-heal
(`pending_image_jobs`'s `SKIPPED`), and hash-keyed dedup-as-retry-cap (`card_gen_attempts`).
Nine independent implementations of that idea, in two languages. Maintaining any one of them
means re-learning its particular bespoke shape from scratch.

---

## Target design — two unifications, independently valuable

### 1. Code-level unification (no infra change — do this regardless of the RabbitMQ decision)

**Java:** a `WorkQueue<T>` interface (`claimBatch`, `markDone`, `markFailed`, `markDeferred`)
parameterized by a `RetryPolicy` (unbounded / capped+backoff / capped+dead-letter — a small
builder, not a new enum per worker). `PollingTableQueue<T>` implements it today via a
Repository + a predicate + the existing `WorkerLane`. Group A's three pollers become three
*configurations* of one generic scheduled-worker class instead of three hand-copied classes.
Group B's workers get refactored to depend on `WorkQueue<T>` instead of calling their
Repository directly — this indirection is what turns a later RabbitMQ swap into a bean
substitution instead of a rewrite.

**Python:** a `JobRegistry` class (dict + lock + status) with a pluggable `DispatchStrategy`
(`SharedWorkerThread` vs `PerCallThread`). `download/jobs.py` and `minicourse_jobs.py` become
two configurations of one class. `ingest/jobs.py`'s GPU-aware shared-worker logic becomes the
`SharedWorkerThread` strategy — refactored LAST, since it's the highest-risk one (touches the
live ingest pipeline and GPU arbitration).

Zero schema changes. Zero new containers. Each worker refactored, tested, and committed
independently — fits a weekly-maintenance cadence, not a rewrite.

### 2. Transport unification (RabbitMQ — Group B only)

Group B's tables are already explicit state machines with retry/dead-letter concepts — exactly
what a broker's ack/nack + dead-letter-exchange (DLX) + per-message TTL replaces natively.
Group A stays on polling (see Non-goals) — it's self-healing by design and doesn't need
push-latency.

**DB impact: additive only, and possibly zero.** `capture` / `sync_queue` /
`pending_image_jobs` keep their exact current columns as the status/UI source of truth
(transactional-outbox pattern: write the row, publish the message in the same call). Their
existing primary key (`capture.id` is already a UUID) doubles as the message correlation id —
no new column needed for the common case.

RabbitMQ replaces: the `@Scheduled` poll-tick delay (today up to 15–30s of latency before a
retry is even attempted), the hand-rolled `retry_count`/backoff-per-table code, and the three
different ad hoc dead-letter shapes (`SKIPPED` / session-capped / `retry_count`-capped) — with
ONE DLX+TTL retry ladder and ONE real, inspectable dead-letter queue, configured once and
reused by every migrated queue via the same `RetryPolicy` object from part 1.

---

## Group A revised: outbox pattern keeps self-healing on RabbitMQ

Originally this plan left Group A on plain polling, reasoning that a naive "publish on write"
swap loses self-healing (a missed publish = never retried, silently). That's true for a NAIVE
swap — but a **transactional outbox** avoids the trade-off entirely:

- The write that changes `content_hash` also writes one row, in the SAME transaction, to a
  shared `outbox_events` table. That row is the durable record that "a message needs to go
  out" — it can't be lost independently of the DB write.
- The producer tries to publish to RabbitMQ immediately (so the common case is instant, not a
  60s wait) and marks the outbox row published on success.
- ONE small generic relay job (reused for every table, not one poller per pipeline) sweeps
  `outbox_events WHERE published=false` every few seconds — this only does anything on the rare
  case a direct publish failed (e.g. a crash between the DB write and the publish call).
- The hash column doesn't disappear — its job shrinks from "the thing polled to detect work"
  to (a) an idempotency check in the consumer ("is my target hash still current, or did a
  newer edit already supersede me — skip if so") and (b) a much-lower-frequency safety-net
  query (e.g. hourly, not every-60s) that catches a bug in the outbox-writing code itself.

Net: Group A gets the SAME self-healing guarantee it has today, PLUS instant delivery instead
of poll-interval latency, PLUS unified retry/dead-letter via the same RabbitMQ mechanism as
Group B. See Phase 3.

## Non-goals (explicitly out of scope)

- **Mailbox stays as-is.** A broker doesn't replace Drive as the offline-transport medium.
- **`escalation.py` stays as-is.** Flag-gated off by default, low value, and its shape (local
  FIFO drained by a singleton) isn't the distributed-delivery problem a broker solves.
- **Frontend PWA outbox/mailbox (IndexedDB) is out of scope** — confirmed explicitly with the
  user: it must stay client-side, since its entire job is queuing while the device itself has
  no connectivity, before any request reaches a server.

---

## Phased plan

### Phase 0 — the original bug + the failure ledger — ✅ DONE
- `SourceSplicePanel.jsx` (+ `.module.css`): no local copy AND source is Instagram/TikTok →
  show "Couldn't fetch this source for offline viewing — Open original ↗" instead of a blank
  `<iframe>` (`isEmbedBlockedHost()`).
- `embedder/failures.py` (new): `pipeline_failures` table + `record_failure()` — the shared
  failure ledger described above.
- `jobs.py::_ensure_local_copy`'s except-block now calls `failures.record_failure(source=
  "ingest", stage="video_download", input_payload={ref, note_path, capture_id}, error=e,
  bundle_ref=job.get("bundle_path"))` instead of only logging a warning.
- No dependency on anything below.

### Phase 1 — Python code unification (no infra) — not started
- Extract `JobRegistry` + `DispatchStrategy` (`SharedWorkerThread` vs `PerCallThread`).
- Refactor `download/jobs.py` and `minicourse_jobs.py` onto it (Group D "skip-Rabbit" members —
  this is pure cleanup, no behavior change, no infra).
- Give `ingest/jobs.py` durable failure recording via `failures.record_failure()` at its other
  swallow/DEFER points too (it already calls `escalation.escalate()` on full-job failure —
  add a `record_failure` call alongside it so escalation AND the ledger both see it).

### Phase 2 — Java code unification (no infra) — not started
- Extract `WorkQueue<T>` + `RetryPolicy` (unbounded / capped+backoff / capped+dead-letter).
- Refactor `NoteEmbeddingWorker`, `ChunkEmbeddingReconciler`,
  `ResourceScanService.retryPendingIngests` onto ONE generic scheduled-worker (Group A), still
  polling at this point — no infra change yet, just collapsing 3 bespoke classes into 1 + 3
  configs.
- **Acceptance:** identical behavior (same predicates, same cadence), existing tests +
  one canary run.

### Phase 3 — outbox + RabbitMQ stood up, Group A migrated — ✅ DONE
- `docker-compose.yml`: `rabbitmq` service (3.13-management-alpine), internal-network-only
  (no host ports — avoids colliding with an unrelated RabbitMQ container already on this
  host), named volume, healthcheck, `backend` depends on it.
- `spring-boot-starter-amqp` + `outbox_events` table (`OutboxRepository`, same inline
  `CREATE TABLE IF NOT EXISTS` convention as the rest of this codebase) + `OutboxRelay`
  (`@TransactionalEventListener(AFTER_COMMIT)` for the instant path, a 5s scheduled sweep as
  the only-matters-on-failure fallback).
- `RabbitBackedQueue<T>` implements `WorkQueue<T>` via manual ack/nack — `PollingQueueWorker`
  (Phase 2) can drive it with zero changes, proving the interface decouples backend choice.
  Not wired into a real worker yet (that's Phases 4-6); proven against a real broker in
  `RabbitBackedQueueIT`.
- **Group A migrated:** `NoteEmbeddingWorker` and `ChunkEmbeddingReconciler` now publish
  instantly via the outbox at their confirmed producer chokepoints
  (`ImageScanService.registerImages()` and `ImageProcessingWorker.handleResult()`
  respectively) with a `@RabbitListener` doing the real work; their polling loops became
  1-hour safety nets (down from 60s/15s). `ResourceScanService.retryPendingIngests()`
  deliberately left as pure Phase-2 polling — it already had its own eager-trigger-plus-
  safety-net shape via a different, pre-existing mechanism (eager off-thread HTTP submit
  inside `scan()`), so migrating it added complexity for no gain.
- **Two non-obvious bugs caught during implementation:** (1) unconditional `@RabbitListener`
  beans broke every other `@SpringBootTest` IT (a failed listener-container startup is fatal
  to context refresh) — fixed via `spring.rabbitmq.listener.simple.auto-startup` defaulting
  off, turned on explicitly in docker-compose and in the ITs that need it. (2)
  `ImageProcessingWorker.handleResult()` is only ever called via self-invocation, which
  bypasses Spring's AOP proxy — `@Transactional` there would have silently never applied;
  used `TransactionTemplate` instead.
- Correction from the original text above: no Python (`pika`) dependency was needed — every
  Group A/B member turned out to be Java-side.
- Validated: `docker compose config` clean; `java-unit` + `java-it` both pass (49 test
  classes, 0 failures/0 errors, independently re-run and confirmed via `surefire-reports/`
  before merge — not just taken on the implementing agent's word).

### Phase 4 — pilot: `capture` / `CaptureIngestWorker` → RabbitMQ — ✅ DONE
- Transport: outbox+`@RabbitListener` (Group A's Phase 3 shape, not `RabbitBackedQueue` — a
  push listener fit better than the poll-shaped `claimBatch` contract once a DLX+TTL ladder
  was in the picture). `capture` table keeps its exact schema, unchanged — it's the
  outbox/status record, not the poll target. `CaptureIngestWorker`'s own poll (`tick()`,
  15s→5min) is now the safety net, same as Group A's.
- DLX+TTL retry ladder (3 rungs: 1h → 6h → 24h, `RabbitQueueConfig` — each a plain queue with
  `x-message-ttl` + `x-dead-letter-exchange` pointing back at `capture`) replaces
  `retryFailed`'s hand-rolled 6h/session-capped poller. `retryDeferred`/`drainDeferred`
  (bundle-resume synthesis retry) deliberately left untouched — functionally a different
  operation (`IngestClient.resume`, not resubmit) on a much slower natural cadence
  (LLM-provider cooldowns), not worth folding into the same ladder.
- **Deviation from this doc's original ladder order:** no `ingest/escalation.py` hand-off
  before dead-lettering. The Phase 4 implementation brief (separate from this doc) specified
  the dead-letter handler as exactly two actions (`markFailed` + `pipeline_failures` write)
  with no escalation step, and `escalation.py` is Python/Group E — a separate flag-gated
  subsystem outside this phase's Java-only scope. Flagged, not silently dropped; wiring it in
  is a small later addition (publish to escalation before the final dead-letter hop) if wanted.
- **One deliberate touch to the otherwise-untouched orphan-cleanup sweep:** a capture riding
  the 6h/24h rung stays `processing` well past `cleanupMinAgeMs` (30min) with no notes and no
  active embedder job — indistinguishable from an abandoned row to the old predicate. Fixed
  with an in-memory `ridingLadder` set in `CaptureIngestWorker`, checked in `sweepOrphan()`.
  See `capture/FLOWS.md`'s Retry Ladder section for the full reasoning.
- **Acceptance:** `/api/capture/failed` + manual retry/dismiss behave identically from the
  UI's perspective (verified: `CaptureControllerTest` unchanged assertions on those
  endpoints); a forced 5xx rides the ladder and surfaces as a notified, debuggable
  `pipeline_failures` row (proven end-to-end against a real broker in `CaptureRabbitFlowIT`,
  with rung TTLs shrunk via property override so the full ladder runs in seconds).

### Phase 5 — `pending_image_jobs` → outbox + RabbitMQ — ✅ DONE
- Built on the Phase 3 outbox mechanism (`OutboxRepository`/`OutboxRelay`), NOT
  `RabbitBackedQueue`'s poll-shaped manual-ack — a push `@RabbitListener` on
  `ImageProcessingWorker` was the actual ask, matching this table's existing lifecycle
  (PENDING/DONE/SKIPPED) instead of the claim/mark abstraction. `upsertPending`
  (`ImageScanService.registerImages`) and the daily `requeueSkipped` revive both
  publish to the new `image-caption` outbox queue.
- `image.worker.parallelism` now sizes BOTH the poll fallback's thread pool and the
  `@RabbitListener` concurrency — one property, two consumers of the same weight.
- **Deliberately NOT "same shape as Phase 4"**: no capped ladder, no escalation.py
  hookup, no hard dead-letter. A transient failure rejects into a short-TTL wait queue
  (`ImageCaptionQueueConfig`, default 5min) and comes back to the main queue — matching
  this table's existing "retry forever, no ladder" behavior, just with backoff instead
  of an instant tight loop. A `not_found` still goes straight to `SKIPPED`, self-healed
  by the existing daily sweep — never a hard DLQ. This preserves a self-heal design this
  table already had, rather than replacing it with capture's harsher shape.
- Poll fallback (`processPendingImages`) demoted from 30s to 1h (`image.scan.delay-ms`),
  same pattern as Phase 3's Group A pollers.
- **Open judgment call, not decided here:** whether `pending_image_jobs` should EVER
  get a genuine dead-letter (e.g. for a caption that fails transiently forever, never
  hitting `not_found`) is left open — today it just cycles the wait queue indefinitely,
  identical to the pre-Phase-5 "stays PENDING forever" behavior. Revisit if that proves
  to actually happen in production.
- Tests: `ImageCaptionFlowIT` (Testcontainers Postgres+RabbitMQ, stubbed host-wrapper) —
  proves the listener path (not poll) captions a fresh job, a forced transient failure
  retries after the wait TTL and succeeds, and `not_found` still lands on SKIPPED.

### Phase 6 — `sync_queue` → `RabbitBackedQueue` (LAST) — upload direction done, narrowed scope
- Attempted in parallel with Phases 4–5 (explicit direction), not after the "couple of weeks
  in production" validation window this doc originally called for — flagged as a deviation,
  not a silent call.
- **Scope narrowed to the upload direction only.** `SyncQueueRepository.markPending` (the
  single funnel for every producer) now also enqueues to a "sync-upload" outbox/RabbitMQ
  queue; `SyncService.onSyncUploadMessage` (`@RabbitListener`, concurrency =
  `SYNC_UPLOAD_CONCURRENCY`) reuses the exact existing `uploadOne` per-row logic. Retry ladder:
  a fixed-delay `sync-upload` ⇄ `sync-upload-wait` DLX/TTL pair (not the 1h/6h/24h multi-step
  ladder used for Phase 4/5 — Drive's transient errors clear in seconds/minutes, not hours, and
  `DriveService.withRetry`'s own exponential backoff already runs before a message ever lands
  here), capped by the existing `retry_count`/`SYNC_UPLOAD_MAX_RETRIES`, then dead-lettered via
  the existing `markFailed` — same `/api/sync/status` contract as before.
- **Did NOT use `RabbitBackedQueue<T>`** (the poll-shaped, manual-ack `WorkQueue` impl from
  Phase 3) — sync's per-item logic already lives in a plain method (`uploadOne`) called from a
  push-style `@RabbitListener`, matching the `NoteEmbeddingWorker` precedent more closely than
  the `RabbitBackedQueue` shape.
- **Explicitly NOT migrated**, left exactly as before: tombstone processing (`DELETE_PENDING`),
  the weekly janitor, DB backup/restore internals, and the whole download direction — none of
  these are "local file changed, upload it" events that flow through `markPending`. See
  `sync/FLOWS.md` "Upload fast path" for the full design, including the new
  `SyncService.dbBackupLock` added specifically to keep the Rabbit-driven upload path from
  racing a live `pg_restore` (a risk that couldn't exist in the old poll-only model, since
  uploads and DB backup/restore always shared one `WorkerLane`).

---

## Execution order

`0 (done) → 1 (done) → 2 (done) → 3 (done) → 4 (done) → (validate ~2 weeks) → 5 → (validate) → 6`

Each phase is its own branch/commit per the git-discipline rule.

## Change Index

| Thing to change | Where |
|---|---|
| Blocked-embed fallback (IG/TikTok, no local copy) | `frontend/.../SourceSplicePanel.jsx` `isEmbedBlockedHost()` |
| Which hosts trigger the blocked-fallback UI | `SourceSplicePanel.jsx` `isEmbedBlockedHost()` regex |
| Failure ledger (shared, all pipelines) | `embedder/failures.py` (`record_failure`, table `pipeline_failures`) |
| yt-dlp failure visibility | `embedder/ingest/jobs.py` `_ensure_local_copy` except-block → `failures.record_failure()` |
| Java queue abstraction | `WorkQueue<T>` / `RetryPolicy` (new) |
| Group A pollers, unified | new generic scheduled-worker class |
| Python job-dict abstraction | `JobRegistry` / `DispatchStrategy` (new) |
| RabbitMQ Java client wiring | Spring AMQP config (new) |
| RabbitMQ Python client wiring | `pika` config (new) |
| Pilot queue | `capture` / `CaptureIngestWorker` → `RabbitBackedQueue<T>` |
| Retry ladder | DLX + per-message TTL config on the RabbitMQ queue definition |
