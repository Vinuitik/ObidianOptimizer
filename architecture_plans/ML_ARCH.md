# ML + MCP Layer — Architecture Plan

Files: McpController.java, EmbeddingService.java, ImageProcessingWorker.java, ImageScanService.java, NoteChunkRepository.java, PendingImageJob.java, PendingImageJobRepository.java, MarkdownPreprocessor.java, SearchService.java
Python embedder: embedder/main.py, embedder/Dockerfile, embedder/requirements.txt, embedder/tests/test_main.py

---

## New Containers (docker-compose additions)

| Container | Image / Build | Purpose |
|---|---|---|
| `postgres` | `build: ./db` (paradedb/paradedb base) | pgvector cosine search + pg_search BM25 full-text |
| `embedder` | `build: ./embedder` (nvidia/cuda:12.2.2-cudnn8-runtime-ubuntu22.04 base) | Python FastAPI + ONNX Runtime; serves mxbai-embed-large-v1 (1024-dim) embeddings via `POST /embed`. GPU passthrough (GTX 1650 / NVIDIA). Loud CPU fallback warning if no GPU detected. |
| `host-wrapper` | Python/Flask on Windows host | VLM image processing via Anthropic Claude Vision — see `host-wrapper/FLOWS.md` |

---

## Database Schema

### `notes` table (extended)
Existing table gains one column:
```sql
ALTER TABLE notes ADD COLUMN content_hash TEXT;
```
Used by ChronoService to detect externally-edited files (via Obsidian, not the app).

### `note_chunks` table
```sql
CREATE TABLE note_chunks (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  note_path     TEXT NOT NULL,
  chunk_index   INT  NOT NULL,
  text          TEXT NOT NULL,
  embedding     vector(1024),
  content_hash  TEXT NOT NULL,
  fts_vector    TSVECTOR,
  UNIQUE (note_path, chunk_index)
);
CREATE INDEX ON note_chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX ON note_chunks USING GIN (fts_vector);
```

### `pending_image_jobs` table
```sql
CREATE TABLE pending_image_jobs (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  note_path     TEXT NOT NULL,
  image_path    TEXT NOT NULL,
  status        TEXT NOT NULL CHECK (status IN ('PENDING','DONE','SKIPPED')),
  content_hash  TEXT,
  created_at    TIMESTAMP NOT NULL DEFAULT now(),
  processed_at  TIMESTAMP,
  UNIQUE (note_path, image_path)
);
CREATE INDEX ON pending_image_jobs (status) WHERE status = 'PENDING';
```

---

## Full Embedding Pipeline

Trigger: note created or updated → `EmbeddingService.indexNote(path)`

### 1. Preprocessing — `MarkdownPreprocessor.chunk(path, rawContent)`

```
raw markdown
  → strip YAML frontmatter (--- ... ---)
  → strip HTML comments (<!-- ... -->)
  → strip #tags at end-of-file
  → extract image refs: ![[name.ext]] and ![alt](path) → returns alongside clean text
  → split on ## / ### headings → semantic sections
  → sections > 1000 chars: sliding window (size ~1000, overlap ~200)
  → drop chunks < 50 chars
→ returns: List<NoteChunk> (path, sectionTitle, text, imageLinks[])
```

To change chunk size / overlap: `MarkdownPreprocessor` constants

---

### 2. Embedding — per chunk

```
chunk text
  → SHA-256(text) → compare against note_chunks.content_hash
  → if unchanged: skip (cache hit)
  → POST http://embedder:8000/embed {"texts": [chunk]}
  → parse embeddings[0] → float[1024] vector
  → upsert note_chunks(note_path, chunk_index, text, embedding, content_hash)
  → UPDATE fts_vector = to_tsvector('english', chunk text)
```

To change model: `EMBED_MODEL` env var in docker-compose → rebuild embedder container (Settings UI field is display-only)  
To change embedder URL: `application.properties → embedder.url` / `EMBEDDER_URL` env var

---

### 3. Stale Chunk Cleanup

After re-indexing a note: `DELETE FROM note_chunks WHERE note_path = ? AND chunk_index > newChunkCount`  
Handles note shrinking (fewer chunks than before).

---

## Image Pipeline (VLM-only, queue-driven)

All images go directly to the host wrapper (Claude Vision). No CNN classifier.

### Queue table: `pending_image_jobs`

**Populated by three paths:**

1. **Startup scan** — `ImageScanService.scanAll()` called after `FileRepository.init()`:
   - Walks all notes, extracts `![[img.ext]]` and `![alt](path)` refs
   - Inserts PENDING rows for any `(note_path, image_path)` not already DONE

2. **App-side writes** — `FileRepository.createNote()` / `updateNote()` / `patchNote()`:
   - After each write, calls `ImageScanService.registerImages(path, content)`
   - Upserts PENDING rows for any new image refs

3. **Chrono hash check** — `ChronoService.runAllJobs()` step:
   - For each `.md` file: SHA-256(fileContent) vs `notes.content_hash`
   - If different (external Obsidian edit): `ImageScanService.registerImages(path, content)`, update `content_hash`

### Worker: `ImageProcessingWorker`

Background `@Scheduled` thread drains PENDING rows:

```
for each PENDING row:
  → POST http://host.docker.internal:5001/process-image {image_path}
  → if 200 OK:
      extracted text
      → if text > 1000 chars: sliding window chunk (same as MarkdownPreprocessor)
      → each chunk → EmbeddingService.embed(chunk) → upsert note_chunks
      → mark row DONE, set processed_at
  → if wrapper unreachable / 4xx / 5xx:
      → mark row SKIPPED
      → log WARN with image_path (visible in Docker logs)
```

Host wrapper prompt: "If this is a wall of text or screenshot, transcribe it verbatim. If it is a diagram or chart, describe its structure and meaning concisely."  
To change prompt: `host-wrapper/main.py → IMAGE_PROMPT`

---

## MCP Server

Transport: HTTP POST on `/api/mcp/execute`  
Auth: `X-API-Key` header → compare against `MCP_API_TOKEN` env var → 401 if missing/wrong  
Lives in: existing Spring Boot container (`McpController.java`)

### Tools

| Tool | Status | Implementation |
|---|---|---|
| `search_notes` | Stub → wire to SearchService | RRF hybrid search |
| `get_note_content` | Stub | `FileRepository.getText(path)` |
| `create_note` | Stub | `FileRepository.createNote()` + `EmbeddingService.indexNote()` async |
| `find_home_for_note` | Stub | embed title → pgvector similarity → extract folders |

---

## Connecting Claude (or any MCP client) to the MCP server

### 1. Generate the API token
```powershell
# On Windows — generates a 32-byte hex secret
-join ((1..32) | ForEach-Object { '{0:x2}' -f (Get-Random -Max 256) })
```
Or on any system: `openssl rand -hex 32`

Add it to your `.env` file (never commit this file):
```
MCP_API_TOKEN=<your-generated-secret>
```
The backend reads it via `MCP_API_TOKEN` env var (set in `docker-compose.yml → backend.environment`).

### 2. Claude Desktop (`claude_desktop_config.json`)
Location: `%APPDATA%\Claude\claude_desktop_config.json` on Windows.

```json
{
  "mcpServers": {
    "obsidian": {
      "type": "http",
      "url": "http://localhost:8084/api/mcp/execute",
      "headers": {
        "X-API-Key": "<your-generated-secret>"
      }
    }
  }
}
```
Restart Claude Desktop after saving. The `obsidian` server will appear in the tools list.

### 3. Test the connection
```powershell
curl -X POST http://localhost:8084/api/mcp/execute `
  -H "Content-Type: application/json" `
  -H "X-API-Key: <your-secret>" `
  -d '{"tool":"search_notes","parameters":{"query":"test","limit":3}}'
```
Expect `{"results":[]}` until notes are indexed. A `401` means the token is wrong.

### 4. Auth token security notes
- The token is a shared secret — anyone who has it can call all MCP tools
- Do not expose port 8084 to the internet; keep it on localhost
- To rotate: change `MCP_API_TOKEN` in `.env` and restart the backend container
- The token is set at the controller level (`McpController.java`), not in Spring Security,
  so session auth is completely bypassed for `/api/mcp/**`

---

## Hybrid Search (RRF)

```
query
  → POST http://embedder:8000/embed {"texts": [query]} → float[1024]
  → vector search: SELECT note_path, chunk_index, 1-(embedding <=> queryVec) AS score
                   FROM note_chunks ORDER BY embedding <=> queryVec LIMIT 60
  → BM25 search:   pg_search / paradedb BM25 index on text column LIMIT 60
  → RRF merge: score = (1/(60 + vectorRank)) + (1/(60 + bm25Rank))
  → deduplicate: MAX(score) per note_path
  → return top-limit SearchResult(path, snippet, score)
```

Weights implicit in RRF formula (equal). To bias vector: multiply vector term.  
To change limit: `SearchService.SEARCH_LIMIT`

---

## Embedder GPU / CPU Fallback

On startup, `embedder/main.py` calls `ort.get_available_providers()`:
- `CUDAExecutionProvider` present → log `INFO: GPU detected — using GPU inference`
- Not present → multiple `WARN` lines logged, falls back to `CPUExecutionProvider`

The WARN output:
```
WARN: No GPU / CUDAExecutionProvider detected.
WARN: Falling back to CPU inference — embeddings will be slow.
```

**Dimension is always 1024** regardless of provider — mxbai-embed-large-v1 is used in both paths. No schema mismatch on CPU fallback.

To fix GPU passthrough: install `nvidia-container-toolkit` on the host, ensure Docker Desktop WSL2 backend uses GPU acceleration. The `health` endpoint (`GET http://localhost:8000/health`) reports `"device": "GPU"` or `"CPU"` so you can confirm without reading logs.

---

## Technology Notes

- **paradedb/paradedb**: ships pgvector + pg_search (BM25 via Tantivy). Use `paradedb.bm25()` or `@@@` operator for BM25 queries.
- **Python embedder (ONNX)**: FastAPI + `onnxruntime-gpu`. On first run, `optimum` downloads the PyTorch model from HuggingFace and converts to ONNX, caching to `/models` volume. Subsequent starts load from cache — no network required.
- **mxbai-embed-large-v1** (`mixedbread-ai/mxbai-embed-large-v1`): 1024-dim encoder-only, 335M params, ~670MB VRAM. MTEB English 64.68. Fits GTX 1650 (4GB). Same dimension on CPU fallback — no schema migration needed.
- **ONNX vs GGUF**: GGUF quantization targets autoregressive decoder LLMs. ONNX is the correct inference format for encoder-only embedding models and supports the fine-tuning export path we'll use later.
- **ivfflat index**: requires `VACUUM ANALYZE` after bulk insert to improve clustering. Switch to `hnsw` once the chunk count stabilises.
- **SHA-256 for content hashing**: JDK built-in, no dependency. Throughput ~500MB/s — a 10K-note vault takes ~1s in chrono. Zero false negatives.
- **Image chunking threshold**: 1000 chars (~200 words). Large VLM outputs (detailed diagram descriptions) are split before embedding.
- **Graceful degradation**: if host wrapper is down, images are marked SKIPPED and notes are still searchable on text content. Worker retries on next scheduled run.
- **Embedder healthcheck**: `start_period: 120s` in docker-compose — first run downloads and converts the model, which takes time. Backend `depends_on: embedder: condition: service_healthy`.

---

## Change Index

| Thing to change | Where |
|---|---|
| Embedding model | `EMBED_MODEL` env var in docker-compose → rebuild embedder container |
| Embedder URL | `EMBEDDER_URL` env var / `application.properties → embedder.url` |
| Chunk size / overlap | `MarkdownPreprocessor` constants |
| Hybrid search limit | `SearchService.SEARCH_LIMIT` |
| MCP auth token | `.env → MCP_API_TOKEN` (docker-compose backend env) |
| MCP tools | `McpController.java` switch |
| Image processing prompt | `host-wrapper/main.py → IMAGE_PROMPT` |
| Image chunk threshold | `ImageProcessingWorker.IMAGE_CHUNK_THRESHOLD` |
| Worker schedule | `ImageProcessingWorker` `@Scheduled(fixedDelay = ...)` |
| GPU provider detection | `embedder/main.py → _detect_provider()` |
