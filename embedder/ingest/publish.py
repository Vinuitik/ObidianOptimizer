"""Validation + placement + write-through (INGEST_AGENT_ARCH stage 5).

All vault writes go through the Java backend's internal API — never direct
file writes — so the notes index, embedding diff, and sync queue stay
consistent. Auth: X-Internal-Token = MCP_API_TOKEN (shared service secret).
"""
import logging
import os
import re

import httpx

log = logging.getLogger("embedder.ingest.publish")

BACKEND_URL = os.environ.get("BACKEND_URL", "http://backend:8084")
INTERNAL_TOKEN = os.environ.get("MCP_API_TOKEN", "")
DEFAULT_FOLDER = os.environ.get("INGEST_DEFAULT_FOLDER", "ingest")

_EMBED_RE = re.compile(r"!\[\[([^\]]+)\]\]")


class PublishError(Exception):
    pass


def validate_embeds(content: str, stored_media_names: set[str]) -> list[str]:
    """Every ![[…]] embed the LLM body references must resolve to a file we
    actually stored. Ignores embeds whose target already exists in the vault
    (e.g. the source video itself) — only media WE produced is gated here."""
    problems = []
    for name in _EMBED_RE.findall(content):
        base = name.split("|")[0].strip().rsplit("/", 1)[-1]
        if base not in stored_media_names:
            problems.append(f"embed does not resolve: {base}")
    return problems


def validate_note(content: str, stored_media_names: set[str]) -> list[str]:
    """Standalone-note checks; returns list of problems (empty = valid)."""
    problems = []
    if not content.startswith("---\n") or "\n---\n" not in content[4:]:
        problems.append("frontmatter missing or unterminated")
    problems.extend(validate_embeds(content, stored_media_names))
    if len(content) < 200:
        problems.append("note suspiciously short (<200 chars)")
    return problems


def inject_block(content: str, embed_ref: str, body: str, sha: str) -> str:
    """Insert (or replace) the synthesized block directly below the resource
    embed in `content`. Idempotent: a second run with the same embed replaces
    the existing block rather than stacking. The marker is an HTML comment so
    it renders invisibly and the chunker strips it (MarkdownPreprocessor)."""
    base = embed_ref.rsplit("/", 1)[-1]
    block = (f"<!-- ingest:{base} sha={sha} -->\n"
             f"{body.strip()}\n<!-- /ingest:{base} -->")

    existing = re.compile(
        rf"<!-- ingest:{re.escape(base)} [^\n>]*-->.*?<!-- /ingest:{re.escape(base)} -->",
        re.DOTALL)
    if existing.search(content):
        return existing.sub(lambda _m: block, content, count=1)

    embed_line = re.compile(
        rf"^.*!\[\[[^\]]*{re.escape(base)}[^\]]*\]\].*$", re.MULTILINE)
    m = embed_line.search(content)
    if not m:
        raise PublishError(f"embed {base!r} not found in note — cannot inject")
    return content[:m.end()] + "\n\n" + block + content[m.end():]


def find_home(title: str) -> str:
    """Vault-relative folder for a new note: pgvector neighborhood vote via the
    MCP tool's internals; falls back to INGEST_DEFAULT_FOLDER."""
    try:
        import mcp_server
        res = mcp_server.find_home_for_note(title)
        for folder in res.get("suggested_folders", []):
            if folder and folder != ".":
                return folder
    except Exception as e:
        log.warning("find_home failed for %r: %s", title, e)
    return DEFAULT_FOLDER


def _headers():
    if not INTERNAL_TOKEN:
        raise PublishError("MCP_API_TOKEN not set — internal API is fail-closed")
    return {"X-Internal-Token": INTERNAL_TOKEN}


def store_media(filename: str, data_b64: str) -> str:
    res = httpx.post(f"{BACKEND_URL}/api/internal/media", headers=_headers(),
                     json={"filename": filename, "dataB64": data_b64}, timeout=120)
    if res.status_code != 200:
        raise PublishError(f"media store {res.status_code}: {res.text[:300]}")
    return res.json()["relPath"]


def create_note(folder: str, title: str, content: str) -> str:
    """Create via backend; on name collision retry with ' (2)', ' (3)'..."""
    for attempt in range(1, 6):
        name = title if attempt == 1 else f"{title} ({attempt})"
        res = httpx.post(f"{BACKEND_URL}/api/internal/notes", headers=_headers(),
                         json={"folder": folder, "name": name, "content": content},
                         timeout=60)
        if res.status_code == 200:
            return res.json()["path"]
        if "already exists" not in res.text:
            raise PublishError(f"create_note {res.status_code}: {res.text[:300]}")
    raise PublishError(f"create_note: 5 name collisions for {title!r} in {folder!r}")


def update_note(vault_rel_path: str, content: str) -> None:
    res = httpx.put(f"{BACKEND_URL}/api/internal/notes", headers=_headers(),
                    json={"path": vault_rel_path, "content": content}, timeout=60)
    if res.status_code != 200:
        raise PublishError(f"update_note {res.status_code}: {res.text[:300]}")
