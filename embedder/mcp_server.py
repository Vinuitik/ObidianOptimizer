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
# Tools
# ---------------------------------------------------------------------------

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


@mcp.tool()
def find_home_for_note(proposed_title: str, content: str = "") -> dict:
    """Suggest where a new note belongs. `suggested_folder` is the primary answer, from a
    hierarchical nearest-centroid classifier (`ingest.placement`): each folder is modeled as
    the centroid of its notes and the tree is walked to the closest branch (min depth 2; staging
    folders excluded; null when nothing is confident). PASS `content` (the note body) as well as
    the title — the classifier is far better on real content than on a short title alone.

    `similar_notes`/`name_examples` are extra context (nearest existing notes + their naming),
    NOT a placement vote. Still verify with get_vault_tree() / list_folder() before filing."""
    text = (proposed_title + "\n\n" + content).strip() if content else proposed_title
    try:
        from ingest import placement
        suggested = placement.suggest_folder(text)
    except Exception as e:
        log.warning("placement failed in find_home_for_note: %s", e)
        suggested = None

    # Context only: nearest existing notes (staging folders filtered so the loop that made the
    # old tool suggest _inbox back to itself can't reappear here either).
    query_vec = embed_texts([text[:4000]], kind="query")[0]
    similar_notes: list[str] = []
    for row in _vector_candidates(query_vec):
        note_path = row[0]
        if "/_inbox/" in note_path or note_path.startswith(("ingest/", "/vault/ingest/")):
            continue
        if note_path not in similar_notes:
            similar_notes.append(note_path)
        if len(similar_notes) >= 10:
            break

    return {
        "suggested_folder": suggested,          # primary — may be null ("unsorted")
        "similar_notes": similar_notes,
        "name_examples": [PurePosixPath(p).stem for p in similar_notes[:5]],
    }


# Folders that hold media/build artifacts, not knowledge notes — excluded
# from traversal output so the tree stays signal, not noise.
TREE_SKIP_DIRS = {"resources", "_workspace", "_reports"}
LIST_NOTES_CAP = 200


@mcp.tool()
def get_vault_tree(max_depth: int = 3) -> list[dict]:
    """Read-only map of the vault's folder structure: every folder (down to
    max_depth) with its direct note count, e.g.
    [{"folder": "/vault/Programming/Cloud", "noteCount": 12}, ...].

    Embedding-based suggestions (search_notes, find_home_for_note) are
    imperfect — when deciding where a note belongs, start here to see what
    actually exists, then list_folder(candidate) to check its contents and
    naming conventions. Skips media/system folders (resources, _workspace,
    _reports, dotfolders)."""
    max_depth = max(1, min(max_depth, 8))
    root = VAULT_DIR.resolve()
    out: list[dict] = []

    def walk(d: Path, depth: int):
        try:
            entries = sorted(d.iterdir(), key=lambda p: p.name.lower())
        except (PermissionError, OSError):
            return
        note_count = sum(1 for e in entries if e.is_file() and e.suffix == ".md")
        rel = d.relative_to(root).as_posix()
        out.append({
            "folder": "/vault" if rel == "." else f"/vault/{rel}",
            "noteCount": note_count,
        })
        if depth >= max_depth:
            return
        for e in entries:
            if e.is_dir() and not e.name.startswith(".") and e.name not in TREE_SKIP_DIRS:
                walk(e, depth + 1)

    walk(root, 1)
    return out


@mcp.tool()
def list_folder(folder_path: str = "/vault", include_notes: bool = False) -> dict:
    """List ONE vault folder read-only. By default returns subfolder names and
    a note COUNT only (folders can hold hundreds of notes — keep the signal).
    Pass include_notes=true to also get the note names (capped at 200,
    notesTruncated flags it) — do this when you need a folder's scope and
    naming conventions before filing a note there. Accepts '/vault/...' or a
    vault-relative path. Use after get_vault_tree."""
    resolved = _resolve_in_vault(folder_path)
    if not resolved.is_dir():
        raise ValueError(f"Not a folder in the vault: {folder_path}")
    subfolders: list[str] = []
    notes: list[str] = []
    for e in sorted(resolved.iterdir(), key=lambda p: p.name.lower()):
        if e.name.startswith("."):
            continue
        if e.is_dir():
            subfolders.append(e.name)
        elif e.is_file() and e.suffix == ".md":
            notes.append(e.stem)
    rel = resolved.relative_to(VAULT_DIR.resolve()).as_posix()
    out = {
        "folder": "/vault" if rel == "." else f"/vault/{rel}",
        "subfolders": subfolders,
        "noteCount": len(notes),
    }
    if include_notes:
        out["notes"] = notes[:LIST_NOTES_CAP]
        out["notesTruncated"] = len(notes) > LIST_NOTES_CAP
    return out


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


@mcp.tool()
def create_note(text: str, title: str = "", already_processed: bool = True, folder: str = "") -> dict:
    """Create a note from text YOU authored. Don't hand-write a note file — pass the
    whole body here.

    `folder` (optional, default ""): leave empty to stage the note in the Learn Inbox
    for the user to review and file — the normal, safe default. Pass a vault-relative
    folder (e.g. "Reference/Mobile & PWA") to skip the inbox and file the note DIRECTLY
    there instead (created if missing) — use this ONLY when you're actually confident of
    placement (check get_vault_tree()/list_folder() first, or use
    find_home_for_note()'s suggestion), since it bypasses the user's review-before-filing
    step. Good for: grouping several notes from one conversation into a folder you name
    yourself instead of a random/date-based one. The note is stamped
    `ingest-auto-filed: true` (not stripped) so the user can audit what got placed
    without review. `folder` only applies to the synchronous as-is path below — the
    async re-process path always still goes to the inbox.

    `already_processed` (default True): the text was authored by an LLM (you), so it is
    already good — stage it AS-IS with NO further LLM synthesis and NO async job. The
    write is SYNCHRONOUS: the note exists on disk when this returns, so it is durable
    without needing the async ingest queue, and it never re-spends tokens re-processing
    content tokens already produced. This is the normal path for agent-authored notes.

    Pass `already_processed=False` ONLY to deliberately treat the text as a RAW source to
    be re-processed by the ingest pipeline (deterministically segmented into linked concept
    notes when long). That spends tokens and runs async — use it for pasted third-party
    material, not for your own output. Short inputs stage as-is either way (splitting a
    short note is meaningless). Returns notes_created (as-is) or a job descriptor to poll
    GET /ingest/{id} (re-process). `title` is an optional display hint."""
    from ingest import jobs as ingest_jobs

    if not text or not text.strip():
        raise ValueError("text is empty")

    # Default: LLM-authored content is already good — stage it as-is (no tokens, no queue,
    # durable synchronous write). See [[mcp-ingest-durability-gap]] for the reasoning.
    if already_processed:
        return _stage_note_as_is(text.strip(), title, folder.strip())

    split_min = int(os.environ.get("INGEST_SPLIT_MIN_WORDS", "700"))
    if len(text.split()) < split_min:
        return _stage_note_as_is(text.strip(), title, folder.strip())

    return ingest_jobs.submit("", None, text=text, source_type="text",
                              title=(title or None))


def _stage_note_as_is(text: str, title: str = "", folder: str = "") -> dict:
    """Short-note path: stage the text with NO LLM (no split, no re-synthesis) — the note
    is its own source. Reuses the ingest publish helpers so it lands in the vault exactly
    like a synthesized note, minus the token spend. `folder` set → file DIRECTLY there
    (agent-chosen placement, marked `ingest-auto-filed`); empty → the usual Inbox staging
    (`ingest-inbox` + classifier-suggested folder, awaiting user review)."""
    from ingest import publish, synthesize
    t = (title or "").strip() or _title_from_text(text)
    bundle = {"source": {"ref": "", "title": t, "type": "text"}, "media": []}
    note_md = synthesize.assemble(bundle, {"title": t, "tags": []}, [], text)
    target = folder.strip()
    if target:
        note_md = publish.stamp_auto_filed(note_md)
    else:
        target = publish.INBOX_FOLDER
        note_md = publish.stamp_inbox(note_md, "", publish.find_home(t))
    publish.ensure_folder(target)
    path = publish.create_note(target, synthesize.slugify(t), note_md)
    return {"status": "DONE", "ingested": False, "words": len(text.split()),
            "notes_created": [path]}


def _title_from_text(text: str) -> str:
    first = next((ln.strip().lstrip("# ").strip() for ln in text.splitlines() if ln.strip()), "")
    return (first[:80] or "Note")


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
