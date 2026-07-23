"""MCP tools: hybrid search + raw note reads.

Split out of mcp_server.py for readability — the FastMCP instance and the
DB/vault-path helpers these tools depend on still live there; this module
just registers @mcp.tool() functions onto it.
"""
from mcp_server import mcp, _resolve_in_vault, _vector_candidates, _text_candidates, _rrf_merge
from model_runtime import embed_texts


@mcp.tool()
def search_notes(query: str, limit: int = 10) -> list[dict]:
    """Hybrid search over the Obsidian vault: semantic (pgvector cosine) +
    keyword (real BM25 via ParadeDB pg_search), order fused with reciprocal
    rank fusion.

    Returns [{notePath, snippet, similarity, matchedBy}] sorted by relevance.
    - similarity: cosine similarity of the best semantically-matched chunk
      (null when only the keyword ranker found it). CALIBRATION for the
      EmbeddingGemma space (asymmetric query/doc prompts, multilingual —
      cross-language matches work), measured against this vault: ≥0.5 strong
      match, 0.4–0.5 related, ≤0.35 likely noise (nonsense queries still
      surface ~0.30–0.34 hits from a ~22k-chunk corpus — do not trust
      bottom-of-band results just because they exist).
    - matchedBy: 'semantic', 'keyword', or 'keyword+semantic' — found by
      both rankers is the strongest relevance signal.
    Results are candidates, not ground truth: for filing/placement decisions
    verify against the real structure with get_vault_tree / list_folder."""
    limit = max(1, min(limit, 50))
    query_vec = embed_texts([query], kind="query")[0]
    vector_rows = _vector_candidates(query_vec)
    text_rows = _text_candidates(query)
    return _rrf_merge([("semantic", vector_rows), ("keyword", text_rows)], limit)


@mcp.tool()
def get_note_content(note_path: str) -> str:
    """Read the full markdown content of a note. Accepts the notePath values
    returned by search_notes, or a vault-relative path like 'folder/note.md'."""
    resolved = _resolve_in_vault(note_path)
    if not resolved.is_file():
        raise ValueError(f"Note not found: {note_path}")
    return resolved.read_text(encoding="utf-8")
