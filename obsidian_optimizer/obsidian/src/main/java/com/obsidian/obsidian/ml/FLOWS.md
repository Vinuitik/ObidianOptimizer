# ML Domain Flows

Files: SearchController.java, SearchService.java, EmbeddingService.java, MarkdownPreprocessor.java, NoteChunkRepository.java, PendingImageJobRepository.java, ImageScanService.java, ImageProcessingWorker.java

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

## Embedding Pipeline

Trigger: `EmbeddingService.indexNote(path)` — called after note create/update.

```
rawContent
  → MarkdownPreprocessor.chunkNote(path, rawContent) → List<NoteChunk>
  → for each chunk:
      SHA-256(chunk.text) vs note_chunks.content_hash → skip if unchanged
      POST http://embedder:8000/embed {"texts": [chunk.text]}
      → parse embeddings[0] → float[1024]
      → NoteChunkRepository.upsertChunk(path, index, text, embedding, hash)
      → fts_vector updated via upsert trigger
  → NoteChunkRepository.deleteStaleChunks(path, newCount)
```

To change model: `EMBED_MODEL` env var in docker-compose, rebuild embedder container  
To change embedder URL: `EMBEDDER_URL` env var / `application.properties → embedder.url`  
To change chunk size/overlap: `MarkdownPreprocessor` constants

---

## Embedder Service (Python FastAPI + ONNX)

`GET /health` → `{"status":"ok","model":"...","dim":1024,"device":"GPU"|"CPU"}`  
`POST /embed {"texts":[...]}` → `{"embeddings":[[...float[1024]...]],"model":"...","dim":1024}`

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
  → POST http://embedder:8000/embed {"texts": [query]} → float[1024]
NoteChunkRepository.findByVectorSimilarity(vec, 60)
  → ORDER BY embedding <=> ?::vector LIMIT 60
NoteChunkRepository.findByTextSearch(query, 60)
  → WHERE fts_vector @@ plainto_tsquery('english', ?) ORDER BY ts_rank_cd LIMIT 60
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
batch of PENDING rows → grouped by note_path → groups run in PARALLEL
(fixed pool, image.worker.parallelism=4) so the wrapper's LLM router shards
images across providers (image A → Gemini while image B → Groq).
Same-note images stay sequential — getNextChunkIndex() would collide otherwise.

per job: POST http://host.docker.internal:5001/process-image {image_path}
  → 200: {"text", "provider"} → chunk if > 1000 chars → EmbeddingService.embed() → upsert note_chunks → DONE
  → 404 (image file gone): SKIPPED
  → 503 / network error (LLM providers exhausted): stays PENDING — retried next 30s cycle
SKIPPED rows get one retry per day: ImageProcessingWorker.requeueSkipped()
```

To change image prompt: `host-wrapper/main.py → IMAGE_PROMPT`  
To change schedule: `ImageProcessingWorker @Scheduled(fixedDelay = ...)`  
To change parallelism: `.env → IMAGE_WORKER_PARALLELISM` (≈ number of configured vision providers)  
To change provider order/keys: root `.env` → see `host-wrapper/FLOWS.md`  
To change chunking threshold: `ImageProcessingWorker.IMAGE_CHUNK_THRESHOLD`

---

## NOT IMPLEMENTED

| Feature | Status |
|---|---|
| `create_note` MCP tool | Deliberately not exposed — writes must go through the Java backend (index + sync queue) |

---

## Technology Notes

- **ONNX vs GGUF**: GGUF targets autoregressive decoder LLMs. ONNX is the correct format for encoder-only embedding models and supports the fine-tuning export path.
- **optimum + export=True**: downloads PyTorch model, converts to ONNX on first startup, caches to mounted volume. Container restart without volume re-runs conversion.
- **paradedb**: `<=>` cosine operator from pgvector + `@@@` / `pg_search` BM25 from Tantivy. Both required for hybrid search.
- **RRF**: order-invariant rank fusion — stable rankings regardless of raw score scales from different retrievers.
- **CPU fallback dimension**: always 1024 — mxbai-embed-large-v1 used in both GPU and CPU paths. No schema migration if GPU unavailable.
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
| RRF weights | `SearchService` — edit RRF formula |
| Search result limit | `SearchService.SEARCH_LIMIT` |
| Chunk size / overlap | `MarkdownPreprocessor` constants |
| Image processing prompt | `host-wrapper/main.py → IMAGE_PROMPT` |
| Image worker schedule | `ImageProcessingWorker @Scheduled` |
