# ML + MCP Layer — Architecture Plan

Files: [NOT IMPLEMENTED] McpController.java, EmbeddingService.java, ImagePipelineService.java, NoteChunkRepository.java, CnnGatekeeperClient.java

---

## New Containers (docker-compose additions)

| Container | Image | Purpose |
|---|---|---|
| `postgres` | `pgvector/pgvector:pg16` | Vector + full-text search |
| `ollama` | `ollama/ollama` | Hosts `nomic-embed-text` + `llava-phi3` |
| CNN sidecar | TBD (Python/Flask or TorchServe) | Serves fine-tuned image classifier |

---

## Embedding Flow (note indexing)

`NoteWriteEvent` (create/update) → `EmbeddingService.indexNote(path)`  
→ read markdown → split on `##` headers + paragraph breaks → `List<Chunk>`  
→ for each `![[image.*]]` in chunk → `ImagePipelineService.toText(imagePath)` → replace tag with extracted text  
→ for each chunk → `OllamaClient.embed(text)` → `float[768]`  
→ upsert into `note_chunks(id, note_path, chunk_index, text, embedding, content_hash)`  
→ if `content_hash` unchanged → skip (cache hit)

Invalidation: mirrors `FileRepository.invalidateCache()` — any write triggers re-index of that note only  
Bulk import: one-time `EmbeddingService.reindexAll()` — background thread, low priority  
To change chunk size: `EmbeddingService.splitIntoChunks()`  
To change embedding model: `application.properties → ollama.embed.model`

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
