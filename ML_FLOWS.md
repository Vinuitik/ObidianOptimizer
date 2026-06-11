# ML & MCP Service Implementation

## Implemented So Far (Phase 1 & 2)

*   **Package Structure**: Created `com.obsidian.obsidian.ml`.
*   **DTOs**: 
    *   `NoteChunk.java` (Represents a chunk and its metadata/image links).
    *   `SearchResult.java` (Represents a RRF search match and its string snippet).
*   **Chunking Logic**: 
    *   `MarkdownPreprocessor.java`: Strips frontmatter, HTML comments, wiki-links, handles images and tags. It splits notes physically into structural sections based on `Markdown` heading levels (`##`, `###`), falling back to a paragraph sliding-window (size ~1000 chars, 200 char overlap) when sections are too large.
*   **Search Service (RRF Stub)**: 
    *   `SearchService.java`: Scaffolds the `search(query, limit)` endpoint. Fetches Vector Matches and Text (BM25) Matches via stub functions, applies Reciprocal Rank Fusion ($Score = 1/(60+rank)$ with defined weights 0.7 vs 0.3), deduplicates results to return only the best chunk per note, and returns a formatted snippet.
*   **MCP Controller**: 
    *   `McpController.java`: A `/api/mcp/execute` gateway for Model Context Protocol interactions. Setup stubs for `search_notes`, `get_note_content`, `create_note`, and `find_home_for_note`. 

## Next Steps (Missing / Left to be implemented)

1.  **Docker & Schema Wiring**
    *   Update `docker-compose.yml` with `pgvector` container definition.
    *   Update `docker-compose.yml` with `ollama` and models.
    *   Schema creation (`note_chunks` Table with `VECTOR(768)`).
2.  **Database Integration**
    *   Create Spring Data JPA Repositories (e.g. `NoteChunkRepository`).
    *   Connect `SearchService` to actual native Postgres SQL `to_tsvector` and `<=>` exact neighbor math using `@Query`.
3.  **Embedding Generation**
    *   Implement `EmbeddingService.java` to make actual REST POST calls to the local Ollama `nomic-embed-text` endpoint for chunk embedding retrieval. 
    *   Wire `EmbeddingService` inside an async thread whenever a Note is created or updated in the main application.
4.  **Integration**
    *   Connect `McpController` endpoints to `FileRepository` properly.
    *   Extract auth tokens (API key) into Spring properties securely.
    *   Setup Python wrapper API communication for images logic.