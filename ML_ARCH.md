# ML + MCP Layer — Architecture Plan

Files: [NOT IMPLEMENTED] McpController.java, EmbeddingService.java, ImagePipelineService.java, NoteChunkRepository.java, MarkdownPreprocessor.java

---

## New Containers (docker-compose additions)

| Container | Image | Purpose |
|---|---|---|
| `postgres` | `pgvector/pgvector:pg16` | Vector + full-text search |
| `ollama` | `ollama/ollama` | Hosts `nomic-embed-text` (embeddings only) |
| host-wrapper | Python/Flask on host | VLM image processing via Anthropic API — see `host-wrapper/FLOWS.md` |

---

## Full Embedding Pipeline

Trigger: note created or updated → `EmbeddingService.indexNote(path)`

### 1. Preprocessing — `MarkdownPreprocessor.process(rawMarkdown)`

```
raw markdown
  → strip YAML frontmatter (lines between opening and closing ---)
  → strip HTML comments (<!-- ... -->)
  → strip obsidian metadata tags (lines starting with #tag at end of file)
  → normalize headers: keep ## / ### text, strip # chars (used as chunk boundaries)
  → normalize bold/italic: **text** → text, *text* → text, ==highlight== → text
  → extract image refs: collect all ![[name.ext]] and ![alt](path) → replace with placeholder IMAGE[name.ext]
  → extract tables: convert markdown table → "ColA: val, ColB: val" prose lines
  → collapse 3+ blank lines → single blank line
→ returns: PreprocessedNote { cleanText, List<imageRef> }
```

To change stripping rules: `MarkdownPreprocessor.process()`

---

### 2. Chunking — `EmbeddingService.splitIntoChunks(cleanText)`

```
cleanText
  → split on ## and ### header lines → semantic sections
  → for each section > 512 tokens:
      → split further on double-newline (paragraph boundary)
  → for each paragraph > 512 tokens:
      → sliding window: 512-token chunks, 50-token overlap
  → drop chunks < 50 tokens
→ returns: List<String> chunks
```

Chunk size unit: approximate word count (1 token ≈ 0.75 words) — exact tokenization not needed  
To change sizes: `EmbeddingService.CHUNK_MAX_TOKENS`, `EmbeddingService.CHUNK_OVERLAP_TOKENS`

---

### 3. Image Processing — `ImagePipelineService.toText(imageRef)` [ASYNC]

```
imageRef (e.g. "diagram.png")
  → resolve to /vault/... absolute path
  → check pending_image_jobs table — if already queued/done, skip
  → insert into pending_image_jobs(note_path, image_path, status=PENDING)
  → background thread drains queue:
      → POST http://host.docker.internal:5001/process-image {image_path}
      → if 200: extracted text → insert as extra chunk for note_path
      → if wrapper unreachable: mark status=SKIPPED, continue (graceful degradation)
      → update pending_image_jobs status
```

Image chunk is appended after text chunks for the same note  
To change wrapper URL: `application.properties → wrapper.url`

---

### 4. Embedding — per chunk

```
chunk text
  → hash(chunk text) → compare against note_chunks.content_hash
  → if unchanged: skip (cache hit)
  → POST http://ollama:11434/api/embeddings {model: nomic-embed-text, prompt: chunk}
  → float[768] vector
  → upsert note_chunks(note_path, chunk_index, text, embedding, content_hash, fts_vector)
  → fts_vector = to_tsvector('english', chunk text)  ← postgres full-text index
```

Ollama model pull on first run: `ollama pull nomic-embed-text`  
To change model: `application.properties → ollama.embed.model`

---

### 5. Stale Chunk Cleanup

After re-indexing a note: delete `note_chunks WHERE note_path = ? AND chunk_index > newChunkCount`  
Handles note shrinking (fewer chunks than before)

---

## Image Pipeline (CNN gatekeeper + VLM distillation)

`ImagePipelineService.toText(imagePath)`:

```
image → CnnGatekeeperClient.classify(image)
           → confidence > threshold?
               YES "text_screenshot" → Tesseract OCR → plain text
               YES "diagram"         → OllamaClient.vlmCaption(image, prompt) → description text
               NO  (uncertain)       → OllamaClient.vlmCaption(image, prompt) → text
                                        + log (imagePath, vlmOutput) to pseudo_labels table
                                          (feeds CNN retraining pipeline)
```

Threshold: `application.properties → cnn.confidence.threshold` (default 0.80)  
VLM prompt: "Extract all visible text. If this is a diagram, describe its structure and meaning concisely."  
Pseudo-label table: `image_pseudo_labels(image_hash, cnn_logits, vlm_output, used_in_training)`

**CNN training (offline, not in Docker):**  
TypiClust → selects maximally diverse samples from unlabeled pool for manual labeling  
FlexMatch → semi-supervised training on labeled + pseudo-labeled data  
Export → ONNX → loaded by CNN sidecar  
Distillation loop: VLM uncertain outputs → reviewed batch → retrain CNN → redeploy sidecar  
To retrain: [NOT IMPLEMENTED] `scripts/train_cnn.py`

---

## MCP Server Flow (note creation)

Transport: HTTP/SSE on `/mcp/**`  
Auth: `X-API-Key` header or `?token=` query param → compare against `MCP_API_TOKEN` env var → 401 if mismatch  
Lives in: existing Spring Boot container, new `McpController.java`

**Tool: `find_home_for_note(proposed_title)`**  
`McpController` → embed `proposed_title` via Ollama → pgvector similarity search on `note_chunks`  
→ top-N chunks → deduplicate to note level → extract parent folders  
→ also: `SELECT name FROM notes WHERE folder IN (top_folders) LIMIT 10` (naming style examples)  
→ return: `{ similar_notes: [...], suggested_folders: [...], name_examples: [...] }`

**Tool: `get_folder_children(folder_path)`**  
→ `FileRepository.listChildren(folder)` → direct children only (files + subfolders), no recursion  
→ return names + types

**Tool: `create_note(folder_path, title, content)`**  
→ `FileRepository.createNote(folder, title)` → write content via PATCH hunks  
→ trigger `EmbeddingService.indexNote(newPath)` async  
→ return `{ path, status }`

---

## Hybrid Search Query (pgvector + postgres FTS)

```sql
SELECT note_path, chunk_text,
       (1 - (embedding <=> query_vec)) * 0.7
       + ts_rank(fts_vector, plainto_tsquery('english', query_text)) * 0.3 AS score
FROM note_chunks
ORDER BY score DESC
LIMIT 20;
```

Weights (0.7 / 0.3) tunable in `EmbeddingService.hybridSearch()`

---

## Technology Notes

- **pgvector**: `<=>` is cosine distance. Index type `ivfflat` for speed at scale; needs `VACUUM ANALYZE` after bulk insert. `hnsw` is faster for query but slower to build — use `ivfflat` for initial build, migrate to `hnsw` once stable.
- **nomic-embed-text**: 768-dim embeddings, MIT license, no GPU needed. Pull: `ollama pull nomic-embed-text`.
- **llava-phi3**: ~3GB, Ollama-hosted, CPU-viable but slow (~5-15s/image). Used only for uncertain/diagram cases — CNN keeps this rare.
- **CNN sidecar**: ONNX Runtime Java binding (`com.microsoft.onnxruntime`) can load the model in-process in Spring Boot — no separate container needed if you prefer. Separate container gives independent redeployment.
- **Pseudo-label loop**: VLM outputs are noisy ground truth. Review batch before using in training. `used_in_training = false` rows are the review queue.
- **Chunking + averaging**: search returns chunks, not notes. Deduplicate by `note_path` and take `MAX(score)` per note for ranking — don't average chunk scores.

---

## Change Index

| Thing to change | Where |
|---|---|
| Embedding model | `application.properties → ollama.embed.model` |
| VLM model | `application.properties → ollama.vlm.model` |
| CNN confidence threshold | `application.properties → cnn.confidence.threshold` |
| Chunk split strategy | `EmbeddingService.splitIntoChunks()` |
| Hybrid search weights | `EmbeddingService.hybridSearch()` |
| MCP auth token | `.env → MCP_API_TOKEN` |
| MCP exposed tools | `McpController.java` |
| Pseudo-label review queue | `image_pseudo_labels` table, `used_in_training = false` |
| CNN retrain script | `scripts/train_cnn.py` [NOT IMPLEMENTED] |
