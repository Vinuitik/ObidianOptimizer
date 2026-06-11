# ML Domain Flows

Files: McpController.java, SearchService.java, MarkdownPreprocessor.java, NoteChunk.java, SearchResult.java

**Status: Phase 1 & 2 scaffolding only — no real DB/embeddings yet. See `## NOT IMPLEMENTED` sections.**

---

## POST /api/mcp/execute

`McpController.executeTool(McpRequest, X-API-Key header)` — MCP tool gateway.

```
request.tool:
  "search_notes"       → SearchService.search(query, limit) → List<SearchResult>
  "get_note_content"   → [NOT IMPLEMENTED] stub returns placeholder string
  "create_note"        → [NOT IMPLEMENTED] stub, no FileRepository wired
  "find_home_for_note" → [NOT IMPLEMENTED] stub, returns hardcoded folders
```

`X-API-Key` validation: [NOT IMPLEMENTED] — header accepted but not checked.

To wire `get_note_content`: inject `FileRepository` into `McpController`, call `FileRepository.getText(notePath)`  
To wire `create_note`: same injection, call `FileRepository.createNote(folderPath, title)`

---

## SearchService.search(query, limit)

```
getVectorMatches(query, limit)    → [NOT IMPLEMENTED] stub returns empty list
getTextMatches(query, limit)      → [NOT IMPLEMENTED] stub returns empty list
RRF merge: score = 0.7 * (1/(60+vectorRank)) + 0.3 * (1/(60+textRank))
deduplicate: best chunk per note only
return top-limit SearchResult (path, snippet, score)
```

To implement vector search: add `NoteChunkRepository` (JPA, `@Query` with `<=>` pgvector operator)  
To implement text search: add `@Query` with `to_tsvector` / `plainto_tsquery`  
To change RRF weights: `SearchService` `VECTOR_WEIGHT` / `TEXT_WEIGHT` constants

---

## MarkdownPreprocessor

`MarkdownPreprocessor.chunk(path, rawContent)` → `List<NoteChunk>`

1. Strip frontmatter, HTML comments, wiki-links, `#tags`
2. Split on `##`/`###` headings → structural sections
3. If section > 1000 chars: sliding window (size ~1000, overlap ~200)
4. Each `NoteChunk`: `path`, `sectionTitle`, `text`, `imageLinks[]`

To change chunk size / overlap: `MarkdownPreprocessor` constants

---

## NOT IMPLEMENTED — full pipeline

| Feature | Status |
|---|---|
| `note_chunks` table with `VECTOR(768)` | Schema not created |
| `NoteChunkRepository` JPA | Not created |
| `EmbeddingService` (Ollama `nomic-embed-text`) | Not created |
| Auto-embed on note create/update | Not wired |
| pgvector container in docker-compose | Not added |
| Ollama container in docker-compose | Not added |
| `X-API-Key` validation | Not implemented |
| Python wrapper for image logic | Not implemented |

Full architecture plan: see `memory/project_ml_mcp_plan.md`

---

## Technology Notes

- **pgvector**: `pgvector/pgvector:pg16` image required (not stock `postgres:16`). Adds the `vector` type and `<=>` cosine-distance operator.
- **RRF**: Reciprocal Rank Fusion is order-invariant — result ranking is stable regardless of raw score magnitudes from different retrieval systems.
- **Ollama**: local inference; `nomic-embed-text` produces 768-dim embeddings. HTTP POST to `http://localhost:11434/api/embeddings`.

---

## Change Index

| Thing to change | Where |
|---|---|
| MCP tool dispatch | `McpController.executeTool()` switch |
| RRF weights | `SearchService.VECTOR_WEIGHT / TEXT_WEIGHT` |
| Chunk size / overlap | `MarkdownPreprocessor` constants |
| Auth key validation | `McpController.executeTool()` — add key check at top |
| Add a new MCP tool | `McpController` switch + new service method |
