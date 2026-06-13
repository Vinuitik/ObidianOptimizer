"""
MCP server — real Model Context Protocol (JSON-RPC over streamable HTTP).

Replaces the former Java McpController, which was a custom REST RPC that no
MCP client could actually speak. This module uses the official `mcp` Python
SDK (FastMCP) and is mounted into the FastAPI app in main.py at /mcp.

Protocol: initialize → tools/list → tools/call, stateless JSON responses.
Auth: X-API-Key header compared in constant time against MCP_API_TOKEN.

Tools query Postgres directly (same note_chunks table the Java backend
maintains) and read note files from the read-only /vault mount. No write
tools are exposed — writes must go through the Java backend so the notes
index and sync queue stay consistent.
"""
import hmac
import json
import logging
import os
from collections import Counter
from pathlib import Path, PurePosixPath

import psycopg
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings

from model_runtime import embed_texts

log = logging.getLogger("embedder.mcp")

DATABASE_URL = os.environ.get(
    "DATABASE_URL", "postgresql://obsidian:obsidian@postgres:5432/obsidian")
VAULT_DIR = Path(os.environ.get("VAULT_DIR", "/vault"))

RRF_K = 60          # reciprocal-rank-fusion constant (matches Java SearchService)
FETCH_LIMIT = 60    # candidates fetched per ranking before the RRF merge
SNIPPET_LEN = 150

# DNS-rebinding protection stays ON. Local MCP clients (Claude Code / Claude
# Desktop on this machine) send Host: localhost:8000 and pass the default list.
# To allow additional hosts (e.g. a reverse-proxied domain), set
# MCP_ALLOWED_HOSTS to a comma-separated list — origins are derived from it.
_extra_hosts = [h.strip() for h in os.environ.get("MCP_ALLOWED_HOSTS", "").split(",") if h.strip()]
_allowed_hosts = ["localhost", "localhost:*", "127.0.0.1", "127.0.0.1:*", *_extra_hosts]
_security = TransportSecuritySettings(
    enable_dns_rebinding_protection=True,
    allowed_hosts=_allowed_hosts,
    allowed_origins=[f"{scheme}://{h}" for h in _allowed_hosts for scheme in ("http", "https")],
)

mcp = FastMCP("obsidian-vault", stateless_http=True, json_response=True,
              transport_security=_security)


# ---------------------------------------------------------------------------
# DB + helpers (kept small so tests can monkeypatch _query_db)
# ---------------------------------------------------------------------------

def _query_db(sql: str, params: tuple = ()) -> list[tuple]:
    with psycopg.connect(DATABASE_URL) as conn:
        with conn.cursor() as cur:
            cur.execute(sql, params)
            return cur.fetchall()


def _vec_literal(vec: list[float]) -> str:
    return "[" + ",".join(f"{x:.8f}" for x in vec) + "]"


def _vector_candidates(query_vec: list[float]) -> list[tuple]:
    return _query_db(
        """
        SELECT note_path, chunk_index, text
        FROM note_chunks
        WHERE embedding IS NOT NULL
        ORDER BY embedding <=> %s::vector
        LIMIT %s
        """,
        (_vec_literal(query_vec), FETCH_LIMIT),
    )


def _text_candidates(query: str) -> list[tuple]:
    return _query_db(
        """
        SELECT note_path, chunk_index, text
        FROM note_chunks
        WHERE fts_vector @@ plainto_tsquery('english', %s)
        ORDER BY ts_rank_cd(fts_vector, plainto_tsquery('english', %s)) DESC
        LIMIT %s
        """,
        (query, query, FETCH_LIMIT),
    )


def _rrf_merge(rankings: list[list[tuple]], limit: int) -> list[dict]:
    """RRF over (note_path, chunk_index, text) rankings, deduped per note."""
    scores: dict[str, float] = {}
    chunks: dict[str, tuple] = {}
    for ranking in rankings:
        for rank, row in enumerate(ranking):
            key = f"{row[0]}::{row[1]}"
            scores[key] = scores.get(key, 0.0) + 1.0 / (RRF_K + rank + 1)
            chunks.setdefault(key, row)

    results: list[dict] = []
    seen_notes: set[str] = set()
    for key, score in sorted(scores.items(), key=lambda kv: kv[1], reverse=True):
        note_path, _, text = chunks[key]
        if note_path in seen_notes:
            continue
        seen_notes.add(note_path)
        snippet = text[:SNIPPET_LEN] + "..." if len(text) > SNIPPET_LEN else text
        results.append({"notePath": note_path, "snippet": snippet, "score": score})
        if len(results) >= limit:
            break
    return results


def _resolve_in_vault(note_path: str) -> Path:
    """Resolve a vault-relative or absolute path, refusing anything outside /vault."""
    candidate = Path(note_path)
    if not candidate.is_absolute():
        candidate = VAULT_DIR / candidate
    resolved = candidate.resolve()
    vault_root = VAULT_DIR.resolve()
    if not resolved.is_relative_to(vault_root):
        raise ValueError(f"Path escapes the vault: {note_path}")
    return resolved


# ---------------------------------------------------------------------------
# Tools
# ---------------------------------------------------------------------------

@mcp.tool()
def search_notes(query: str, limit: int = 10) -> list[dict]:
    """Hybrid search over the Obsidian vault: semantic (pgvector cosine) +
    full-text (Postgres FTS), merged with reciprocal rank fusion.
    Returns [{notePath, snippet, score}] sorted by relevance."""
    limit = max(1, min(limit, 50))
    query_vec = embed_texts([query])[0]
    vector_rows = _vector_candidates(query_vec)
    text_rows = _text_candidates(query)
    return _rrf_merge([vector_rows, text_rows], limit)


@mcp.tool()
def get_note_content(note_path: str) -> str:
    """Read the full markdown content of a note. Accepts the notePath values
    returned by search_notes, or a vault-relative path like 'folder/note.md'."""
    resolved = _resolve_in_vault(note_path)
    if not resolved.is_file():
        raise ValueError(f"Note not found: {note_path}")
    return resolved.read_text(encoding="utf-8")


@mcp.tool()
def find_home_for_note(proposed_title: str) -> dict:
    """Given a proposed note title, suggest where it belongs in the vault:
    semantically similar notes, the folders they live in (ranked), and
    example note names from those folders."""
    query_vec = embed_texts([proposed_title])[0]
    rows = _vector_candidates(query_vec)

    # note_path values are written by the backend container, so always POSIX
    similar_notes: list[str] = []
    folder_counts: Counter = Counter()
    for note_path, _, _ in rows:
        if note_path not in similar_notes:
            similar_notes.append(note_path)
            folder_counts[str(PurePosixPath(note_path).parent)] += 1
        if len(similar_notes) >= 10:
            break

    return {
        "similar_notes": similar_notes,
        "suggested_folders": [f for f, _ in folder_counts.most_common(5)],
        "name_examples": [PurePosixPath(p).stem for p in similar_notes[:5]],
    }


@mcp.tool()
def ingest_resource(ref: str, force_whisper: bool = False) -> dict:
    """Turn a resource into Obsidian notes (async job). ref is a vault-relative
    path (resources/videos/x.mp4, folder/slides.pdf) or a URL (article,
    YouTube). Returns the job descriptor — poll status via job id at
    GET /ingest/{id}. Pipeline: deterministic extraction (whisper/PyMuPDF/
    trafilatura/keyframes) → constrained LLM synthesis → notes via backend."""
    from ingest import jobs as ingest_jobs
    from ingest import router as ingest_router

    ingest_router.route(ref)  # raises ValueError on unroutable input
    resolved = None
    if not ref.startswith(("http://", "https://")):
        resolved = _resolve_in_vault(ref)
        if not resolved.exists():
            raise ValueError(f"not in vault: {ref}")
    return ingest_jobs.submit(ref, resolved, force_whisper)


@mcp.tool()
def split_note(note_path: str) -> dict:
    """Split an oversized note (>6000 chars) into focused concept notes and
    rewrite the original as a hub of [[links]]. Synchronous — a few LLM calls."""
    from ingest import split_note as splitter

    content = _resolve_in_vault(note_path).read_text(encoding="utf-8")
    return splitter.split(note_path, content)


# ---------------------------------------------------------------------------
# Auth — constant-time X-API-Key check wrapped around the MCP ASGI app
# ---------------------------------------------------------------------------

class ApiKeyMiddleware:
    """401s any request whose X-API-Key doesn't match the configured token.
    An empty/unset token fails closed (everything 401s) rather than open."""

    def __init__(self, app, token: str):
        self.app = app
        self.token = token

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        provided = ""
        for name, value in scope.get("headers", []):
            if name == b"x-api-key":
                provided = value.decode("latin-1")
                break

        if not self.token or not hmac.compare_digest(provided, self.token):
            body = json.dumps({"error": "Unauthorized"}).encode()
            await send({
                "type": "http.response.start",
                "status": 401,
                "headers": [(b"content-type", b"application/json"),
                            (b"content-length", str(len(body)).encode())],
            })
            await send({"type": "http.response.body", "body": body})
            return

        await self.app(scope, receive, send)
