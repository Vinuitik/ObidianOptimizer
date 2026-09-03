# Common Queue/Concurrency Infra FLOWS
Files: WorkQueue.java, RetryPolicy.java, PollingQueueWorker.java, WorkerLane.java, OutboxRepository.java, OutboxRelay.java, RabbitBackedQueue.java, RabbitQueueConfig.java, ContentHashing.java, IngestClient.java

Shared abstractions used by Group A's background workers (`ml/NoteEmbeddingWorker`,
`ml/ChunkEmbeddingReconciler`, `ml/ResourceScanService`) — see
QUEUE_UNIFICATION_PLAN.md for the full inventory/decision matrix this implements.

---

## Phase 2 shape: WorkQueue<T> + PollingQueueWorker (poll-driven)

```
@Scheduled tick → worker.tick() → lane.trigger(drain) → drain():
    queue.claimBatch(limit) → for each item: processor.process(item)
        success → queue.markDone(item)
        false   → queue.markDeferred(item)   (left for next poll — no-op today)
        threw   → queue.markFailed(item, e)  (no-op today)
    continuousDrain=true → loop while batch full AND progress made
```

`WorkQueue<T>` is implemented two ways today:
- An **anonymous class per worker** (NoteEmbeddingWorker, ChunkEmbeddingReconciler,
  ResourceScanService) — `claimBatch` runs a DB predicate query; mark* are no-ops
  because the actual write happens inside the item processor (it needs data the
  claim step doesn't have, e.g. a computed embedding).
- `RabbitBackedQueue<T>` (Phase 3, below) — proves the same `PollingQueueWorker` can
  drive a message broker instead of a DB predicate, unchanged. Not wired into any
  worker yet; a later phase can swap it in for a Group B queue (`capture`,
  `sync_queue`, `pending_image_jobs`) with no change to `PollingQueueWorker` itself.

To change retry/backoff policy shape: `RetryPolicy.java` (today only `unbounded()` is
used; `capped(n)` exists for a later phase to extend with `.backoff(...)`).

## Phase 3 shape: the outbox — instant delivery, same self-healing

Group A's problem with polling alone: up to a full tick's delay before new work is
even attempted. The outbox pattern gets instant delivery WITHOUT losing the
self-healing guarantee polling gives for free (a missed publish just waits for the
next poll instead of being lost forever):

```
producer's write (e.g. ImageScanService.registerImages, @Transactional)
    │
    ├─ real DB write (content_hash, chunk text, ...)
    └─ OutboxRepository.enqueue(queueName, payload)   ── SAME transaction
             │                                            │
             │                                 events.publishEvent(OutboxRowWritten)
             │                                            │
        row committed                      OutboxRelay.onRowWritten
      (durable "needs sending")            (@TransactionalEventListener,
                                             phase = AFTER_COMMIT)
                                                       │
                                          rabbitTemplate.convertAndSend(queueName, json)
                                                       │
                                          success → outboxRepo.markPublished(id)
                                          failure → row stays unpublished
                                                       │
                            OutboxRelay.sweepUnpublished (@Scheduled, every
                            outbox.relay.delay-ms, default 5s) retries it —
                            this is the ONLY path that ever does real work when
                            the immediate attempt above failed (crash, broker blip)
```

Consumer side: a plain `@RabbitListener(queues = "embed" | "embed-chunk")` method on
the SAME worker class as the polling fallback, both calling one shared private method
(`NoteEmbeddingWorker.embedOne`, `ChunkEmbeddingReconciler.embedChunk`) so the actual
embedding logic exists exactly once. The listener re-reads the current DB state
(content_hash / chunk text) rather than trusting the message payload — this is the
idempotency check: a stale or redelivered message becomes a safe no-op if the target
already changed or was already embedded (`markEmbedded`'s `WHERE content_hash = ?`
guard; `getChunkText`'s `WHERE embedding IS NULL` guard).

The polling `@Scheduled` methods on both workers are now the SAFETY NET only (default
1h, was 60s/15s) — they still exist, unchanged in shape, just much less frequent.

**Why `@Transactional` doesn't appear everywhere you'd expect:** Spring's proxy-based
AOP does not intercept self-invocation (`this.method()` from within the same class).
`ImageScanService.registerImages` is always called externally (FileRepository, sync,
chrono) so `@Transactional` works there. `ImageProcessingWorker.handleResult` is only
ever called via `this.handleResult(...)` from `processJobBatch` in the same class —
an `@Transactional` annotation on it would silently never apply. It uses a
`TransactionTemplate` instead (built from the injected `PlatformTransactionManager`),
scoped narrowly to just the chunk-write + outbox-enqueue pair
(`persistPendingChunkAndEnqueueEmbed`) — programmatic transactions aren't proxy-based,
so self-invocation is a non-issue for them.

## RabbitBackedQueue<T> — manual-ack, poll-shaped

Uses `Channel.basicGet`/`basicAck`/`basicNack` (NOT a push `@RabbitListener`) so it can
satisfy `WorkQueue<T>`'s claim-then-resolve contract: `claimBatch` pulls up to `limit`
messages with manual ack (invisible to other consumers until resolved, same as a DB
row's claim step); `markDone` acks; `markFailed` nacks without requeue (dead-letters
once a later phase adds a DLX — for now, just gone); `markDeferred` nacks WITH requeue
(immediately redeliverable, no backoff yet). One channel opened lazily, reused, and
transparently reopened if found closed.

---

## Technology Notes

- **RabbitMQ has NO host port published** (see root `docker-compose.yml`) — reachable
  only from `backend` by service name (`rabbitmq:5672`) over the internal compose
  network. Credentials are the RabbitMQ default `guest`/`guest`; safe only because
  nothing outside the compose network can reach it.
- **`@RabbitListener` auto-startup is OFF by default**
  (`spring.rabbitmq.listener.simple.auto-startup`, default `false` in
  application.properties). A listener container that fails to start is FATAL to
  Spring context refresh — every `@SpringBootTest` IT in this repo boots the full
  `ObsidianApplication`, and most don't spin up their own RabbitMQ container, so a
  default of `true` would break every one of them (confirmed: it did, 78 test errors,
  before this was found and fixed). docker-compose sets
  `RABBITMQ_LISTENERS_AUTO_START=true` for the real app; `OutboxEmbedFlowIT` turns it
  on for itself via `@DynamicPropertySource` since it brings its own broker.
- **The outbox table has no retention/cleanup job yet.** `outbox_events` rows are
  never deleted once published — fine at Group A's volume (one row per note edit /
  per captioned chunk), but revisit before a high-volume Group B queue (Phase 4+)
  reuses this same table/relay.
- **No DLX or backoff configured on the `embed`/`embed-chunk` queues yet.** A
  consumer that fails just drops the message (RabbitListener default ack-on-return,
  no exception thrown on a logged failure) and relies entirely on the safety-net poll
  (now hourly) to catch it eventually. This is deliberate Phase 3 scope — the
  DLX+TTL retry ladder is Phase 4's job (`RetryPolicy`), for Group B's pilot queue.
- **`RabbitBackedQueue` is unused in production today.** It exists to prove
  `PollingQueueWorker` genuinely doesn't care which `WorkQueue` implementation it
  drives — see `RabbitBackedQueueIT` for its ack/nack contract proven against a real
  broker (Testcontainers). Group B's Phase 4 pilot (`capture`, below) did NOT end up
  using it — a push `@RabbitListener` (Group A's Phase 3 shape) fit better than the
  poll-shaped `claimBatch` contract once a DLX+TTL retry ladder was in the picture.

## Phase 4: Group B pilot (`capture`) — DLX+TTL retry ladder, see capture/FLOWS.md

`capture`'s transport moved to the same outbox→RabbitMQ→`@RabbitListener` shape as Group A,
PLUS a retry ladder (backoff rungs = plain RabbitMQ queues with `x-message-ttl` +
`x-dead-letter-exchange` pointing back at `capture` — no Java timer involved) and a
`capture.deadletter` terminal queue. `PipelineFailureRepository` (new) is the Java-side
writer for the same `pipeline_failures` table `embedder/failures.py` owns — see
`capture/FLOWS.md`'s "Retry ladder" section for the full mechanism and rung durations.
- **pgvector dimension is fixed at 768** (`note_chunks.embedding vector(768)`) —
  any embed call that returns a differently-sized vector fails the SQL write, not
  silently truncates. Caught this exact mismatch in `OutboxEmbedFlowIT` during
  development (a 2-element mock vector).

## Change Index
| Want to change | Where |
|---|---|
| Retry/backoff policy shape | `RetryPolicy.java` |
| Poll-drain loop behavior | `PollingQueueWorker.java` |
| Per-worker thread isolation | `WorkerLane.java` |
| Outbox table schema / enqueue | `OutboxRepository.java` (`initSchema`, `enqueue`) |
| Immediate-publish vs. sweep cadence | `OutboxRelay.java` · `.env`/property `outbox.relay.delay-ms` (default 5000) |
| RabbitMQ connection (host/port/creds) | `application.properties` `spring.rabbitmq.*` · `.env` `RABBITMQ_HOST/PORT/USERNAME/PASSWORD` |
| Listener auto-startup (broker required to boot) | `.env` `RABBITMQ_LISTENERS_AUTO_START` (compose default `true`; app default `false`) |
| Declared queue names | `RabbitQueueConfig.java` (`EMBED_QUEUE`, `EMBED_CHUNK_QUEUE`, `CAPTURE_QUEUE`, `CAPTURE_RUNG_QUEUES`, `CAPTURE_DEADLETTER_QUEUE`) |
| RabbitMQ-backed WorkQueue (manual ack) | `RabbitBackedQueue.java` |
| Retry ladder rung TTLs (Phase 4, `capture` pilot) | `RabbitQueueConfig.java` (`capture.retry.rung{1,2,3}-ttl-ms`, default 1h/6h/24h) |
| Shared pipeline failure ledger (Java-side writer) | `PipelineFailureRepository.java` (table `pipeline_failures`, same schema `embedder/failures.py` owns) |
| Broker itself (image, volume, healthcheck) | root `docker-compose.yml` `rabbitmq` service |
