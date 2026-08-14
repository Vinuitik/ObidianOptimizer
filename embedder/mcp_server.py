"""
MCP server — real Model Context Protocol (JSON-RPC over streamable HTTP).

Replaces the former Java McpController, which was a custom REST RPC that no
MCP client could actually speak. This module uses the official `mcp` Python
SDK (FastMCP) and is mounted into the FastAPI app in main.py at /mcp.

Protocol: initialize → tools/list → tools/call, stateless JSON responses.
Auth: X-API-Key header compared in constant time against MCP_API_TOKEN.

This file owns the shared machinery every tool depends on: the FastMCP
instance, DB access, vault-path resolution, RRF merging, and the auth
middleware. The tools themselves (query Postgres directly — same
note_chunks table the Java backend maintains — and read note files from
the read-only /vault mount) live in mcp_tools/, grouped by concern:
  mcp_tools/search.py — search_notes, get_note_content
  mcp_tools/vault.py  — find_home_for_note, get_vault_tree, list_folder
  mcp_tools/write.py  — ingest_resource, split_note, create_note
Importing them below (see bottom of file) registers each function as an
MCP tool (@mcp.tool() runs at import time) and re-exports it here too, so
`from mcp_server import X` / `mcp_server.X` keep working exactly as before
the split — this module is still the one stable public interface (main.py,
ingest/placement.py, ingest/linking.py, and the tests all import from here,
not from mcp_tools directly).

No write tools bypass the backend — writes must go through the Java backend
so the notes index and sync queue stay consistent.
"""
import hmac
import json
import logging
import os
from pathlib import Path

import psycopg
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings

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

# Remote MCP connectors (Claude.ai, ChatGPT, Gemini) run browser-side and
# send an Origin header that will never match _allowed_hosts (that list is
# obsidianoptimizer.uk, not claude.ai). Host-header validation above is
# what actually stops DNS rebinding here; auth is a bearer token, not
# cookies, so same-origin Origin checks add little beyond that. So Origin
# is allowed for a fixed, named set of AI-assistant origins instead of
# being wildcarded open — add more via MCP_EXTRA_ORIGINS (comma-separated)
# rather than relaxing this to "*".
_known_client_origins = [
    "https://claude.ai",
    "https://chatgpt.com",
    "https://chat.openai.com",
    "https://gemini.google.com",
]
_extra_origins = [o.strip() for o in os.environ.get("MCP_EXTRA_ORIGINS", "").split(",") if o.strip()]
_security = TransportSecuritySettings(
    enable_dns_rebinding_protection=True,
    allowed_hosts=_allowed_hosts,
    allowed_origins=[f"{scheme}://{h}" for h in _allowed_hosts for scheme in ("http", "https")]
        + _known_client_origins + _extra_origins,
)

mcp = FastMCP("obsidian-vault", stateless_http=True, json_response=True,
              transport_security=_security)


# ---------------------------------------------------------------------------
# DB + vault-path helpers (kept small so tests can monkeypatch _query_db;
# also imported directly by ingest/placement.py and ingest/linking.py — keep
# these names+signatures stable, don't move them without updating those).
# ---------------------------------------------------------------------------

def _query_db(sql: str, params: tuple = ()) -> list[tuple]:
    with psycopg.connect(DATABASE_URL) as conn:
        with conn.cursor() as cur:
            cur.execute(sql, params)
            return cur.fetchall()


def _vec_literal(vec: list[float]) -> str:
    return "[" + ",".join(f"{x:.8f}" for x in vec) + "]"


def _vector_candidates(query_vec: list[float]) -> list[tuple]:
    # 4th column: cosine similarity (1 - distance) — the interpretable score
    # surfaced to callers. Tests monkeypatch _query_db with 3-tuples, so all
    # consumers must tolerate a missing similarity column.
    return _query_db(
        """
        SELECT note_path, chunk_index, text, 1 - (embedding <=> %s::vector) AS similarity
        FROM note_chunks
        WHERE embedding IS NOT NULL
        ORDER BY embedding <=> %s::vector
        LIMIT %s
        """,
        (_vec_literal(query_vec), _vec_literal(query_vec), FETCH_LIMIT),
    )


def _text_candidates(query: str) -> list[tuple]:
    # Real BM25 keyword ranking via ParadeDB pg_search (Tantivy), NOT stock
    # Postgres ts_rank_cd. paradedb.match() tokenizes the query safely — the raw
    # `@@@ 'string'` form throws a Tantivy parse error on punctuation ("C++",
    # "a/b", ":"), match() treats the input as plain terms. Ranked by BM25 score.
    if not query or not query.strip():
        return []
    return _query_db(
        """
        SELECT note_path, chunk_index, text
        FROM note_chunks
        WHERE id @@@ paradedb.match('text', %s)
        ORDER BY paradedb.score(id) DESC
        LIMIT %s
        """,
        (query, FETCH_LIMIT),
    )


def _rrf_merge(rankings: list[tuple[str, list[tuple]]], limit: int) -> list[dict]:
    """RRF over labeled (source, rows) rankings, deduped per note.

    RRF decides only the ORDER. The fused score is a rank artifact (~1/60
    for everything), so it is not returned; instead each result carries the
    interpretable signals: cosine `similarity` (when the semantic ranker saw
    it) and `matchedBy` (which rankers found it — both = strongest)."""
    scores: dict[str, float] = {}
    chunks: dict[str, tuple] = {}
    sources: dict[str, set] = {}
    sims: dict[str, float] = {}
    for source, ranking in rankings:
        for rank, row in enumerate(ranking):
            key = f"{row[0]}::{row[1]}"
            scores[key] = scores.get(key, 0.0) + 1.0 / (RRF_K + rank + 1)
            chunks.setdefault(key, row)
            sources.setdefault(key, set()).add(source)
            if len(row) > 3 and row[3] is not None:
                sims[key] = max(sims.get(key, 0.0), float(row[3]))

    results: list[dict] = []
    seen_notes: set[str] = set()
    for key, _score in sorted(scores.items(), key=lambda kv: kv[1], reverse=True):
        note_path, _, text = chunks[key][:3]
        if note_path in seen_notes:
            continue
        seen_notes.add(note_path)
        snippet = text[:SNIPPET_LEN] + "..." if len(text) > SNIPPET_LEN else text
        results.append({
            "notePath": note_path,
            "snippet": snippet,
            "similarity": round(sims[key], 3) if key in sims else None,
            "matchedBy": "+".join(sorted(sources[key])),
        })
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


# ---------------------------------------------------------------------------
# Tools — importing these registers each function as an MCP tool (@mcp.tool()
# decorators run at import time) and binds it here too, e.g.
# `mcp_server.search_notes`. Must come after everything above: each submodule
# does `from mcp_server import mcp, ...`, which needs those names to already
# exist in this module's namespace. Order between the three doesn't matter.
# ---------------------------------------------------------------------------
from mcp_tools.search import search_notes, get_note_content
from mcp_tools.vault import find_home_for_note, get_vault_tree, list_folder
from mcp_tools.write import ingest_resource, split_note, create_note
