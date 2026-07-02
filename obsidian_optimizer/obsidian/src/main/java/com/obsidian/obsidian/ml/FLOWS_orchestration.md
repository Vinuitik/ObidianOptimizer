# Background Orchestration FLOWS
Files: WorkerLane.java (common), SchedulingConfig.java (config), NoteEmbeddingWorker.java, ImageProcessingWorker.java, CardJobWorker.java, ResourceScanService.java, chrono/ChronoService.java, sync/SyncWorker.java · engine side: embedder/gpu_slot.py, embedder/model_runtime.py, embedder/ingest/extract_av.py, embedder/ingest/clip_onnx.py, host-wrapper/llm_router.py

How every background job is scheduled, isolated, and mapped onto the one scarce
resource (the 4 GB GPU). Two dimensions: **software concurrency** (who gets a
thread) and **hardware arbitration** (who gets VRAM). They are independent.

---

## Dimension 1 — software: per-worker lanes

`@EnableScheduling` (ObsidianApplication) + a multi-thread `TaskScheduler`
(`SchedulingConfig`, pool 3) fire the ticks. Each heavy worker then hands its work
to **its own single-thread `WorkerLane`** so a blocking worker never starves the rest.

```
sched pool (3) ──tick──► worker.@Scheduled  ──lane.trigger(drain)──►  lane-<name> thread ──► drain()
   (cheap triggers only)        (returns instantly)                      (all heavy work here)
```

`WorkerLane.trigger(Runnable)` = submit-if-not-already-running (an `AtomicBoolean`
guard, so a tick mid-drain is a no-op, never stacks). To change the pattern:
`common/WorkerLane.java`. To change scheduler pool size: `config/SchedulingConfig.java`.

### The workers

| Worker · method | Tick | Finds work by | Lane | Drain style |
|---|---|---|---|---|
| `NoteEmbeddingWorker.embedPendingNotes` | 60s | `content_hash` diff (`findNotesNeedingEmbedding`) | `embed` | **continuous** — loops while a batch is full AND progress is made (fast backlog drain); stops on partial batch or zero progress |
| `ImageProcessingWorker.processPendingImages` | 30s | `pending_image_jobs` table | `image` | **CAPTION stage** — one batch/tick (30s pacing avoids hammering cooled vision providers); inner `pool` parallelises across notes. Writes chunk text with a **NULL vector** and marks DONE = "captioned" — never re-captions, never loses a caption to an embed hiccup |
| `ChunkEmbeddingReconciler.reconcilePendingChunks` | 15s | `note_chunks WHERE embedding IS NULL` (the **embed queue**) | `embed-reconcile` | continuous drain; embeds any NULL-vector chunk (image OR text), sets the vector only on success (fail → stays NULL → retried next tick) |
| `CardJobWorker.scanAndGenerate` | 30min | `body_hash` diff | `cards` | one batch/tick (credit-capped) |
| `ChronoService.scheduledRun` | 2am cron | scans all md | `chrono` | full nightly pass (`runAllJobs`) |
| `SyncWorker.scheduledUpload` | 6h cron | sync queue | `sync` | Drive upload |
| `ResourceScanService.retryPendingIngests` | 5min | `ingest_pending` flag | **none — already async** (`trigger → pool.submit`, its own `resource-ingest-trigger` executor) | fires HTTP ingests off-thread |

### Caption ↔ embed decoupling ("each table a queue")
The image stage used to fuse caption+embed and mark the job DONE unconditionally — a
cheap embed failure discarded the expensive VLM caption forever (ack-before-success
bug). Now they are two independent queues feeding each other:
- **caption queue** = `pending_image_jobs`: image → VLM → `chunkRepo.upsertChunkTextOnly` (text, NULL vector) → DONE = captioned.
- **embed queue** = `note_chunks WHERE embedding IS NULL`: `ChunkEmbeddingReconciler` fills the vector (idempotent `setChunkEmbedding` — only writes if still NULL).

Result: caption is never lost, and image+text embedding share ONE self-healing consumer.
The other stages (text-embed, cards, ingest-via-marker, sync) were already correct
self-healing reconciliation — this brought the one fire-once stage in line with them.

`NoteEmbeddingWorker.purgeOrphanChunks` (daily) + `ImageProcessingWorker.requeueSkipped`
(daily) stay as direct light `@Scheduled` janitors — no lane needed.

### Why this fixes the old bug
Before: no custom `TaskScheduler` → Spring's **single** scheduler thread ran every
`@Scheduled`. `ImageProcessingWorker` blocked it (`pool.invokeAll`, waiting on
rate-limited vision APIs), freezing note embedding for minutes (head-of-line
blocking). Now each drain is on its own lane; the tick just submits and returns.
Regression guard: `SchedulerIsolationTest` (embedding completes while the cards
worker's drain is held blocked).

### How to SEE it working (the thread-name tell)
The log's `[thread-name]` column is the proof. Run
`docker compose logs backend -f | grep -E 'NoteEmbeddingWorker|ImageProcessingWorker'`
and look at the bracketed thread:

```
[     lane-embed] NoteEmbeddingWorker : embedding 32 note(s)
[pool-1-thread-1] ImageProcessingWorker: processed ... via mistral -> 1 chunk(s)   ← same seconds!
[     lane-embed] NoteEmbeddingWorker : embedded 32/32 note(s)
[     lane-embed] NoteEmbeddingWorker : embedding 32 note(s)                        ← back-to-back drain
```

- `lane-embed` running **interleaved** with `pool-1-thread-1` in the same timestamps =
  true concurrency, blocking bug gone. On the old code both would share one
  `scheduling-1` thread and the embed line would stall behind the image lines.
- `embedded 32/32` immediately followed by `embedding 32` = the continuous drain loop.
- Every worker's lane thread is named `lane-<name>` (`WorkerLane` constructor), so the
  thread column tells you exactly which lane is doing what at a glance.

---

## Dimension 2 — hardware: the single-occupant GPU slot

The 4 GB card holds **one** heavy model at a time. `embedder/gpu_slot.py` arbitrates.

```
              ┌──────────── 4 GB GPU: ONE SLOT ────────────┐
              │      occupant = whisper | clip | embedder   │
              └─────────────────────────────────────────────┘
   exclusive(name) ▲ blocks+evicts        embedder_session() ▲ never evicts, yields
   (whisper, CLIP — ingest, win)          (text embedder — degrades to CPU floor)
```

- **Ingest models (whisper/CLIP)** → `gpu_slot.exclusive()`: block for the slot,
  evict the current occupant, stay resident until the ingest queue drains
  (`jobs.py` `_queue.empty() → _evict_models() → release_ingest()`).
- **Text embedder** → `gpu_slot.embedder_session()`: never blocks, never evicts.
  GPU if free, else the always-present **CPU floor** session (`model_runtime.py`).
- Priority = **whisper/CLIP > embedder** (whisper on CPU is painfully slow;
  embeddings on CPU are fine). Lock held only for load/evict, never across the
  minutes-long transcription — so the embedder keeps serving on CPU meanwhile.
- Kill switch: `GPU_SLOT=off`. VRAM cap: `EMBED_GPU_MEM_LIMIT_MB` (default 1800).

The two dimensions meet cleanly: the backend fires embed + ingest **concurrently**
(separate lanes) and `gpu_slot` absorbs it (embedder → CPU while whisper runs). No
GPU thrash because whisper is cached and evicted only on queue-drain.

---

## Dimension 3 — remote "models": the LLM router

Image captions + card text don't touch the GPU — they route through the host-wrapper
(`:5500`, `llm_router.py`) across free-tier **API** providers by priority
(`LLM_VISION_PRIORITY`, `LLM_TEXT_PRIORITY`), free-tiers first, Claude last, each
**cooling** on a 429. This is the load-balancer behind the `/status` provider table.

---

## Technology Notes
- **`WorkerLane` threads are daemons**, created per worker instance (not Spring beans).
  Each owning worker shuts its lane in `@PreDestroy` (`stopLane`); the daemon flag means
  a missed shutdown can't hang JVM exit.
- **Continuous embed drain can tight-loop only on progress.** The `ok == 0` guard stops
  the loop if the embedder is down/all-failing, so a persistent failure waits for the
  next tick instead of hammering. Change: `NoteEmbeddingWorker.drain()`.
- **Lanes give isolation, not priority.** Cross-worker priority is intentionally absent:
  the only truly contended resource (GPU) is prioritised downstream in `gpu_slot`, and
  the backend workers are I/O-bound, so concurrent lanes don't fight for CPU.
- **[NOT IMPLEMENTED] GPU locality heuristic.** A "drain all work for the resident model
  before evicting" scheduler would shave the ~2-swaps-per-burst cost further. Deliberately
  deferred — `gpu_slot`'s cache + batch-drain already make swaps rare; add only if thrash
  is actually measured.

## Change Index
| Want to change | Where |
|---|---|
| The lane mechanism (guard, thread) | `common/WorkerLane.java` |
| Scheduler thread-pool size | `config/SchedulingConfig.java` → `setPoolSize` |
| Embed cadence / batch | `.env` `EMBEDDING_SCAN_DELAY_MS` / `EMBEDDING_BATCH_LIMIT` |
| Image pacing / parallelism | `ImageProcessingWorker` `fixedDelay` · `.env` `IMAGE_WORKER_PARALLELISM` |
| Enable/disable a stage | `.env` `EMBEDDING_ENABLED` / `IMAGES_ENABLED` / `CARDS_ENABLED` / `INGEST_ENABLED` / `CHRONO_ENABLED` |
| GPU arbitration policy | `embedder/gpu_slot.py` |
| GPU VRAM cap / kill switch | `.env` `EMBED_GPU_MEM_LIMIT_MB` / `GPU_SLOT` |
| LLM provider priority / cooldown | `host-wrapper/llm_router.py` · `.env` `LLM_*_PRIORITY` |
| Isolation regression test | `ml/SchedulerIsolationTest.java` |
