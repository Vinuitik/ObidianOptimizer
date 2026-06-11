# ML Domain Flows

Files: McpController.java, SearchService.java, EmbeddingService.java, MarkdownPreprocessor.java, NoteChunkRepository.java, PendingImageJobRepository.java, ImageScanService.java, ImageProcessingWorker.java

Python embedder: embedder/main.py, embedder/Dockerfile, embedder/requirements.txt

---

## POST /api/mcp/execute

`McpController.executeTool(McpRequest, X-API-Key header)` — MCP tool gateway.

Auth: `X-API-Key` header validated against `mcp.api.token` (`MCP_API_TOKEN` env var) → 401 if missing or wrong.

```
request.tool:
  "search_notes"       → SearchService.search(query, limit) → List<SearchResult>
  "get_note_content"   → [NOT IMPLEMENTED] stub returns placeholder string
  "create_note"        → [NOT IMPLEMENTED] stub
  "find_home_for_note" → [NOT IMPLEMENTED] stub
```

Security: `/api/mcp/**` is `permitAll()` in Spring Security — auth is handled inside the controller, not by session.  
To wire `get_note_content`: inject `FileRepository` into `McpController`, call `FileRepository.getText(notePath)`  
To add a tool: add case to `McpController.executeTool()` switch

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
for each PENDING row:
  POST http://host.docker.internal:5001/process-image {image_path}
  → 200: extracted text → chunk if > 1000 chars → EmbeddingService.embed() → upsert note_chunks → DONE
  → error: SKIPPED (logged WARN with image_path; retried next run)
```

To change image prompt: `host-wrapper/main.py → IMAGE_PROMPT`  
To change schedule: `ImageProcessingWorker @Scheduled(fixedDelay = ...)`  
To change chunking threshold: `ImageProcessingWorker.IMAGE_CHUNK_THRESHOLD`

---

## NOT IMPLEMENTED

| Feature | Status |
|---|---|
| `get_note_content` MCP tool | Stub — FileRepository not injected into McpController |
| `create_note` MCP tool | Stub |
| `find_home_for_note` MCP tool | Stub |

---

## Technology Notes

- **ONNX vs GGUF**: GGUF targets autoregressive decoder LLMs. ONNX is the correct format for encoder-only embedding models and supports the fine-tuning export path.
- **optimum + export=True**: downloads PyTorch model, converts to ONNX on first startup, caches to mounted volume. Container restart without volume re-runs conversion.
- **paradedb**: `<=>` cosine operator from pgvector + `@@@` / `pg_search` BM25 from Tantivy. Both required for hybrid search.
- **RRF**: order-invariant rank fusion — stable rankings regardless of raw score scales from different retrievers.
- **CPU fallback dimension**: always 1024 — mxbai-embed-large-v1 used in both GPU and CPU paths. No schema migration if GPU unavailable.
- **MCP permitAll in Spring Security**: session auth is bypassed for `/api/mcp/**`. Auth is X-API-Key checked in controller. No CSRF risk (JSON API, no browser session).

---

## Change Index

| Thing to change | Where |
|---|---|
| MCP tool dispatch | `McpController.executeTool()` switch |
| MCP auth token | `.env → MCP_API_TOKEN` |
| Embedding model | `EMBED_MODEL` docker-compose env → rebuild embedder |
| Embedder URL | `EMBEDDER_URL` env / `application.properties → embedder.url` |
| GPU provider detection | `embedder/main.py → _detect_provider()` |
| RRF weights | `SearchService` — edit RRF formula |
| Search result limit | `SearchService.SEARCH_LIMIT` |
| Chunk size / overlap | `MarkdownPreprocessor` constants |
| Image processing prompt | `host-wrapper/main.py → IMAGE_PROMPT` |
| Image worker schedule | `ImageProcessingWorker @Scheduled` |
