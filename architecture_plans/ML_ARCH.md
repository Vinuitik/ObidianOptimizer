# ML + MCP Layer — Architecture Plan

Files: McpController.java, EmbeddingService.java, ImageProcessingWorker.java, ImageScanService.java, NoteChunkEntity.java, NoteChunkRepository.java, PendingImageJob.java, PendingImageJobRepository.java, MarkdownPreprocessor.java, SearchService.java

---

## New Containers (docker-compose additions)

| Container | Image / Build | Purpose |
|---|---|---|
| `postgres` | `build: ./db` (paradedb/paradedb base) | pgvector cosine search + pg_search BM25 full-text |
| `ollama` | `ollama/ollama` | Hosts `mxbai-embed-large` (1024-dim embeddings). GPU passthrough (GTX 1650 / NVIDIA). Falls back to CPU with log warning if no GPU detected. |
| host-wrapper | Python/Flask on Windows host | VLM image processing via Anthropic Claude Vision — see `host-wrapper/FLOWS.md` |

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
  → POST http://ollama:11434/api/embeddings {model: mxbai-embed-large, prompt: chunk}
  → float[1024] vector
  → upsert note_chunks(note_path, chunk_index, text, embedding, content_hash)
  → UPDATE fts_vector = to_tsvector('english', chunk text)
```

To change model: Settings UI → embed model field → stored in `app_settings.ollama_embed_model`  
To change Ollama URL: `application.properties → ollama.base.url`

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

## Hybrid Search (RRF)

```
query
  → embed via Ollama → float[1024]
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

## Ollama GPU / CPU Fallback

Docker compose passes NVIDIA device to Ollama container. On startup, an init container (or entrypoint script) attempts to detect GPU via `nvidia-smi`:
- GPU found → pull `mxbai-embed-large` (1024-dim), log `INFO: Ollama using GPU`
- GPU not found → log `WARN: No GPU detected — falling back to CPU with nomic-embed-text (768-dim)`, pull `nomic-embed-text`

**Important:** if fallback activates, embedding dimension drops to 768. The `note_chunks.embedding` column must match. Either recreate the table or keep the column as `vector(1024)` and accept the dimension mismatch error as a prompt to fix GPU setup. The warning in logs is the signal.

To fix GPU passthrough: install `nvidia-container-toolkit` on the host, ensure Docker Desktop WSL2 backend is active.

---

## Technology Notes

- **paradedb/paradedb**: ships pgvector + pg_search (BM25 via Tantivy). Use `paradedb.bm25()` or `@@@` operator for BM25 queries.
- **mxbai-embed-large**: 1024-dim, 335M params, ~670MB VRAM. MTEB English 64.68 — best quality/size ratio for GTX 1650 (4GB).
- **nomic-embed-text** (CPU fallback): 768-dim, 274MB, lower quality but CPU-viable.
- **ivfflat index**: requires `VACUUM ANALYZE` after bulk insert to improve clustering. Switch to `hnsw` once the chunk count stabilises.
- **SHA-256 for content hashing**: JDK built-in, no dependency. Throughput ~500MB/s — a 10K-note vault takes ~1s in chrono. Zero false negatives.
- **Image chunking threshold**: 1000 chars (~200 words). Large VLM outputs (detailed diagram descriptions) are split before embedding to stay within Ollama context.
- **Graceful degradation**: if host wrapper is down, images are marked SKIPPED and notes are still searchable on text content. Worker retries on next scheduled run.

---

## Change Index

| Thing to change | Where |
|---|---|
| Embedding model | Settings UI → ML section → embed model field → `app_settings.ollama_embed_model` |
| Ollama URL | `application.properties → ollama.base.url` |
| Chunk size / overlap | `MarkdownPreprocessor` constants |
| Hybrid search limit | `SearchService.SEARCH_LIMIT` |
| MCP auth token | `.env → MCP_API_TOKEN` (docker-compose backend env) |
| MCP tools | `McpController.java` switch |
| Image processing prompt | `host-wrapper/main.py → IMAGE_PROMPT` |
| Image chunk threshold | `ImageProcessingWorker.IMAGE_CHUNK_THRESHOLD` |
| Worker schedule | `ImageProcessingWorker` `@Scheduled(fixedDelay = ...)` |
| GPU fallback model | Ollama entrypoint script |
