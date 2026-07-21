# ML Domain Flows

Files: SearchController.java, SearchService.java, EmbeddingService.java, MarkdownPreprocessor.java, NoteChunkRepository.java, PendingImageJobRepository.java, ImageScanService.java, ImageProcessingWorker.java, NoteEmbeddingWorker.java, ResourceScanService.java

Ingest agent (resource → in-place notes): embedder/ingest/FLOWS.md

Python embedder: embedder/main.py, embedder/model_runtime.py, embedder/mcp_server.py, embedder/Dockerfile, embedder/requirements.txt

---

## GET /search?q=&limit=10

`SearchController.search(q, limit)` — semantic search endpoint for the UI (session-auth, not MCP).

### Cancellation safeguard

```
DeferredResult(timeout=5000ms, timeoutValue=List.of())
  onTimeout → cancelled.set(true) + log WARN
CompletableFuture.supplyAsync(() → searchService.search(q, limit, cancelled))
  .thenAccept(results → deferred.setResult(results))
  .exceptionally(ex → deferred.setErrorResult(ex))
```

**Why DeferredResult**: frees the Tomcat servlet thread while the background work runs. The timeout ensures a slow embedder doesn't hold the thread (or a DeferredResult slot) forever.

**What the AtomicBoolean catches**: if the 5s timeout fires mid-search, `cancelled` is flipped. The next checkpoint in `SearchService` sees it and skips remaining work:

```
SearchService.search(q, limit, cancelled):
  getVectorRankedMatches(q, limit)   ← embed HTTP + vector DB (expensive)
  if cancelled.get() → return List.of()  ← CHECKPOINT: skip BM25 if client timed out
  getTextRankedMatches(q, limit)     ← BM25 DB query
  RRF merge → return results
```

**What it does NOT cancel**: the embed HTTP call or vector DB query already in progress — those run to completion. The checkpoint prevents starting the *next* step.

To change timeout: `SearchController.TIMEOUT_MS`  
To add more checkpoints: add `if (cancelled.get()) return List.of()` between steps in `SearchService`

---

## MCP Server (moved to Python — embedder/mcp_server.py)

Real Model Context Protocol: JSON-RPC over streamable HTTP at `http://localhost:8000/mcp`
(`initialize` → `tools/list` → `tools/call`), built on the official `mcp` Python SDK (FastMCP),
stateless mode with JSON responses. The former Java `McpController` (custom REST RPC no MCP
client could speak) is deleted; `/api/mcp/**` no longer exists in Spring Security.

Auth: `X-API-Key` header, constant-time compare (`hmac.compare_digest`) against `MCP_API_TOKEN`
env var in `mcp_server.ApiKeyMiddleware`. Unset token fails closed. DNS-rebinding protection is
enabled — only `localhost` / `127.0.0.1` Host headers are accepted.

```
tools:
  search_notes(query, limit=10)        → hybrid RRF search, direct Postgres (note_chunks)
  get_note_content(note_path)          → read from read-only /vault mount, path-validated
  find_home_for_note(proposed_title)   → embed title → pgvector similarity → folder suggestions
```

No write tools — note creation must go through the Java backend so the notes index and
sync queue stay consistent. `[NOT IMPLEMENTED]` create_note via MCP.

Claude Code: `claude mcp add --transport http obsidian http://localhost:8000/mcp --header "X-API-Key: <token>"`

To add a tool: `@mcp.tool()` function in `embedder/mcp_server.py`  
To change DB access: `mcp_server._query_db` / `DATABASE_URL` env var  
To change vault mount: `VAULT_DIR` env var + compose volume `${HOST_VAULT_PATH}:/vault:ro`

---

## Embedding Pipeline (note text)

No call-site hooks: `NoteEmbeddingWorker` (`@Scheduled` every 30s, batch 20) diffs
`notes.content_hash ↔ notes.embedded_hash` — the diff IS the work list (same
philosophy as the cards worker). Covers note creation, app edits, sync downloads,
chrono rewrites, external Obsidian edits (hash loop), and first-boot backfill
(embedded_hash starts NULL for every note).

**Readiness gate**: the worklist also requires `notes.ingest_pending = false`, so a
note with an un-ingested A/V/PDF embed is NOT embedded against its pre-transcript
content (it would only be redone after the ingest agent injects the synthesized
text). `ResourceScanService.scan` maintains the flag at the `registerImages`
chokepoint. Same flag gates the card worklist — see Resource Ingest + Cards FLOWS.

```
NoteEmbeddingWorker.embedPendingNotes():
  NoteIndexRepository.findNotesNeedingEmbedding(20)   — (path, content_hash) pairs
                                                        (WHERE … AND ingest_pending = false)
  → per note: EmbeddingService.indexNote(path):
      rawContent
        → MarkdownPreprocessor.chunkNote(path, rawContent) → List<NoteChunk>
        → pass 1: keep only chunks whose SHA-256 differs from note_chunks.content_hash
                  (source='text') — the changed set
        → pass 2: embed the changed set in slices of EMBED_BATCH (64) —
            POST http://embedder:8000/embed {"texts": [...slice...]}  (ONE call per slice)
            → embeddings[] parsed in order → float[768] each
            → NoteChunkRepository.upsertChunk(path, index, 'text', text, embedding, hash) per chunk
        → deleteStaleChunks(path, 'text', newCount)
        → returns false on any slice failure (note stays in the diff, retried)
  → success: markEmbedded(path, hash) — guarded UPDATE (path AND content_hash=hash)
    so a note edited mid-index stays in the work list
  → failure (embedder down): stays in diff, retried next cycle
```

**source column**: text chunks (`source='text'`) and image chunks (`source='image'`)
have independent chunk_index ranges — unique key is (note_path, source, chunk_index).
Pre-migration rows default to 'image' (they were all written by the image worker).

Orphan cleanup: `NoteEmbeddingWorker.purgeOrphanChunks()` (daily) deletes chunks
whose note_path no longer exists in `notes` (deleted/renamed notes).

To change model: `EMBED_MODEL` env var in docker-compose, rebuild embedder container  
To change embedder URL: `EMBEDDER_URL` env var / `application.properties → embedder.url`  
To change chunk size/overlap: `MarkdownPreprocessor` constants  
To change batch/schedule: `NoteEmbeddingWorker.BATCH_SIZE / @Scheduled`

---

## Embedder Service (Python FastAPI + ONNX)

`GET /health` → `{"status":"ok","model":"...","dim":768,"device":"GPU"|"CPU"}`  
`POST /embed {"texts":[...]}` → `{"embeddings":[[...float[768]...]],"model":"...","dim":768}`

Startup flow:
```
_detect_provider()
  → ort.get_available_providers()
  → CUDAExecutionProvider found → INFO log, GPU inference
  → not found → WARN log (multiple lines, not silent), CPU fallback
_load_model(provider)
  → ORTModelForFeatureExtraction.from_pretrained(EMBED_MODEL, export=True, provider=...)
  → first run: downloads PyTorch model → converts to ONNX → caches to /models volume
  → subsequent runs: loads from volume, no network needed
```

Mean pooling + L2 normalisation applied to `last_hidden_state` before returning.

To check GPU status without reading logs: `GET http://localhost:8000/health` → `"device"` field  
To fix GPU passthrough: install `nvidia-container-toolkit`, confirm Docker Desktop WSL2 GPU support

---

## Hybrid Search (RRF)

`SearchService.search(query, limit)`:

```
EmbeddingService.embedQuery(query)
  → POST http://embedder:8000/embed {"texts": [query]} → float[768]
NoteChunkRepository.findByVectorSimilarity(vec, 60)
  → ORDER BY embedding <=> ?::vector LIMIT 60
NoteChunkRepository.findByTextSearch(query, 60)
  → real BM25 via ParadeDB pg_search: WHERE id @@@ paradedb.match('text', ?)
    ORDER BY paradedb.score(id) LIMIT 60   (idx_note_chunks_bm25, key_field=id)
  → NOTE: use paradedb.match(), NOT raw `@@@ 'string'` — raw form throws a
    Tantivy parse error on punctuation ("C++", "a/b", ":"). match() tokenizes safely.
RRF merge: score = 1/(60 + vectorRank) + 1/(60 + bm25Rank)
deduplicate: MAX(score) per note_path → top-limit SearchResult(path, snippet, score)
```

To bias vector: multiply vector term in RRF formula (`SearchService`)  
To change result limit: `SearchService.SEARCH_LIMIT`

---

## MarkdownPreprocessor.chunkNote(path, rawContent)

```
raw markdown
  → strip YAML frontmatter (--- ... ---)
  → strip HTML comments
  → strip #tags
  → extract image refs: ![[name.ext]] and ![alt](path)
  → split on ## / ### headings → semantic sections
  → sections > 1000 chars: sliding window (size ~1000, overlap ~200)
  → drop chunks < 50 chars
→ returns List<NoteChunk> (path, sectionTitle, text, imageLinks[])
```

To change chunk size/overlap: `MarkdownPreprocessor` constants

---

## Image Pipeline

Queue table: `pending_image_jobs` (PENDING / DONE / SKIPPED).

**Populated by:**
1. `ImageScanService.scanAll()` — on startup after disk sync
2. `ImageScanService.registerImages(path, content)` — after every note write in `FileRepository`
3. `ChronoService` hash loop — SHA-256 detects external Obsidian edits, calls `registerImages`

**Drained by** `ImageProcessingWorker` (`@Scheduled` every 30s):
```
batch of PENDING rows, PRIORITISED by the embedding note's deadline —
  findPending JOINs notes ON note_path, ORDER BY sr_due ASC NULLS LAST, created_at.
  Images block flashcard generation (cards wait for captioning), so soonest-due
  notes drain first, mirroring how flashcards prioritise by deadline. The INNER
  JOIN also excludes ORPHAN jobs (note gone — "images stored by no one"): they are
  never embedded and pruneOrphans() DELETES them daily (deleted, not SKIPPED, so
  requeueSkipped can't revive them; re-created by registerImages if the note returns).
→ grouped by note_path → groups run in PARALLEL
(fixed pool, image.worker.parallelism=4) so the wrapper's LLM router shards
images across providers (image A → Gemini while image B → Groq).
Same-note images stay sequential — getNextChunkIndex() would collide otherwise.

per job: POST http://host.docker.internal:5001/process-image {image_path}
  → 200: {"text", "provider"} → chunk if > 1000 chars → EmbeddingService.embed() → upsert note_chunks → DONE
  → 404 (image file gone): SKIPPED
  → 503 / network error (LLM providers exhausted): stays PENDING — retried next 30s cycle
SKIPPED rows get one retry per day: ImageProcessingWorker.requeueSkipped()
  (which first calls jobRepo.pruneOrphans() to drop note-gone jobs)
```

To change image prompt: `host-wrapper/main.py → IMAGE_PROMPT`  
To change schedule: `ImageProcessingWorker @Scheduled(fixedDelay = ...)`  
To change parallelism: `.env → IMAGE_WORKER_PARALLELISM` (≈ number of configured vision providers)  
To change provider order/keys: root `.env` → see `host-wrapper/FLOWS.md`  
To change chunking threshold: `ImageProcessingWorker.IMAGE_CHUNK_THRESHOLD`

---

## Resource Ingest Trigger

`ResourceScanService.scan(absPath, content)` runs at the same chokepoint as
`registerImages` (called at its tail), so every note write is covered. For each
`![[*.mp4|mkv|webm|mov|avi|mp3|m4a|wav|ogg|flac|pdf]]` lacking a `<!-- ingest:… -->`
marker it submits `{ref, note_path}` off-thread via `common/IngestClient.submitInPlace()`
(best-effort). The embedder synthesizes a note and injects it below the embed; the next
write-back carries the marker, so re-scans skip it. Pipeline detail: embedder/ingest/FLOWS.md.

**Centralized gate:** all Java→embedder ingest submissions now go through the one
`common/IngestClient` (in-place here; standalone from the capture queue) — see
`capture/FLOWS.md`. It owns the HTTP/1.1 transport + `embedder.url`; callers pass a typed
request. Previously each caller hand-rolled its own client (drifting defaults, duplicated
h2c workaround).

`scan` also sets `notes.ingest_pending` (synchronously, before the off-thread POSTs):
true while any resource embed still lacks its marker, cleared once all do. This is the
readiness gate read by the embedding AND card worklists — downstream processing waits
until the note's content is finalized (avoids rework + double LLM card spend).

To change which embeds trigger ingest: `ResourceScanService.RESOURCE_EMBED`
To change the embedder endpoint: `embedder.url` (shared with EmbeddingService)

---

## NOT IMPLEMENTED

| Feature | Status |
|---|---|
| `create_note` MCP tool | Deliberately not exposed — writes must go through the Java backend (index + sync queue) |

---

## Technology Notes

- **ONNX vs GGUF**: GGUF targets autoregressive decoder LLMs. ONNX is the correct format for encoder-only embedding models and supports the fine-tuning export path.
- **optimum + export=True**: downloads PyTorch model, converts to ONNX on first startup, caches to mounted volume. Container restart without volume re-runs conversion.
- **paradedb**: `<=>` cosine operator from pgvector (vector arm) + `@@@` / `pg_search` BM25 from Tantivy (keyword arm). Both are the live hybrid arms. Query the BM25 arm through `paradedb.match('text', ?)` — the raw `@@@ 'string'` operator runs Tantivy's query parser, which throws on punctuation (`C++`, `a/b`, `:`); match() treats input as plain tokens. BM25 index: `idx_note_chunks_bm25 USING bm25 (id, text) WITH (key_field='id')`, created in `NoteChunkRepository.initSchema`.
- **LEGACY fts_vector**: the `fts_vector` TSVECTOR column + `idx_note_chunks_fts` GIN index + `ts_rank_cd` are the OLD keyword ranker (stock Postgres FTS). Superseded by BM25 above; column still populated by the upsert methods but no longer read by any search path. Safe to drop in a follow-up migration (column + GIN index + the `to_tsvector` calls in `upsertChunk`/`upsertChunkTextOnly`).
- **RRF**: order-invariant rank fusion — stable rankings regardless of raw score scales from different retrievers.
- **CPU fallback dimension**: always 768 — gte-base (`Xenova/gte-base`, mean-pooled) used in both GPU and CPU paths. No schema migration if GPU unavailable.
- **MCP session manager**: `mcp.session_manager.run()` must be entered in the FastAPI lifespan or `/mcp` 500s. It can only be started once per process — relevant for tests (module-scoped client).
- **MCP stateless mode**: no session persistence; every request is self-contained. Fine for tool calls; would need stateful mode for subscriptions/sampling.
- **Embedder DB access**: the MCP tools read Postgres directly with psycopg (connection per call, no pool). Low traffic by design; add a pool if MCP usage grows.

---

## Change Index

| Thing to change | Where |
|---|---|
| MCP tools | `embedder/mcp_server.py` — `@mcp.tool()` functions |
| MCP auth | `embedder/mcp_server.py ApiKeyMiddleware` / `.env → MCP_API_TOKEN` |
| Search request timeout | `SearchController.TIMEOUT_MS` |
| Search cancellation checkpoints | `SearchService.search(q, limit, cancelled)` — add `if (cancelled.get())` between steps |
| MCP auth token | `.env → MCP_API_TOKEN` |
| Embedding model | `EMBED_MODEL` docker-compose env → rebuild embedder |
| Embedder URL | `EMBEDDER_URL` env / `application.properties → embedder.url` |
| GPU provider detection | `embedder/main.py → _detect_provider()` |
| Keyword ranker (BM25) | `NoteChunkRepository.findByTextSearch` (Java) + `mcp_server._text_candidates` (Python) — both `id @@@ paradedb.match('text', ?)` |
| BM25 index definition | `NoteChunkRepository.initSchema` — `idx_note_chunks_bm25 USING bm25 (id, text)` |
| RRF weights | `SearchService` — edit RRF formula |
| Search result limit | `SearchService.SEARCH_LIMIT` |
| Chunk size / overlap | `MarkdownPreprocessor` constants |
| Image processing prompt | `host-wrapper/main.py → IMAGE_PROMPT` |
| Image worker schedule | `ImageProcessingWorker @Scheduled` |
| Image job priority (deadline) | `PendingImageJobRepository.findPending()` — JOIN notes, ORDER BY sr_due |
| Orphan image job cleanup | `PendingImageJobRepository.pruneOrphans()` — called daily in `ImageProcessingWorker.requeueSkipped()` |
| Note embedding batch/schedule | `NoteEmbeddingWorker.BATCH_SIZE / @Scheduled` |
| Chunks per embed request | `EmbeddingService.EMBED_BATCH` (64) |
| Embedding work list rule | `NoteIndexRepository.findNotesNeedingEmbedding()` |
| Readiness gate flag | set: `ResourceScanService.scan` → `NoteIndexRepository.setIngestPending`; gates: `findNotesNeedingEmbedding` + `CardRepository.findNotesNeedingCards` |
| Orphan chunk cleanup | `NoteEmbeddingWorker.purgeOrphanChunks()` (daily) |
