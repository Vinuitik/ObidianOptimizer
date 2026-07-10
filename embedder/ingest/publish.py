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
# Standalone ingest notes land here as a staging area (the Learn "Inbox"): the user
# reviews each generated note, edits it, and files it to a real folder. find_home()
# is demoted to a SUGGESTED destination stamped into the frontmatter, not acted on.
INBOX_FOLDER = os.environ.get("INGEST_INBOX_FOLDER", "_inbox")

_EMBED_RE = re.compile(r"!\[\[([^\]]+)\]\]")

# A/V/PDF resource embeds (same extensions Java ResourceScanService.RESOURCE_EMBED matches).
# An ingest-produced note that carries one of these would make the scanner re-fire ingestion
# on it → infinite loop. We demote them to plain links so the reference survives but the
# `![[…]]` trigger doesn't. Image embeds are deliberately left ALONE — they still need the
# image-caption pipeline (ImageScanService picks up `![[frame.jpg]]`).
_RESOURCE_EMBED_RE = re.compile(
    r"!(\[\[[^\]|]+\.(?:mp4|mkv|webm|mov|avi|mp3|m4a|wav|ogg|flac|pdf)(?:\|[^\]]*)?\]\])",
    re.IGNORECASE)


class PublishError(Exception):
    pass


def demote_resource_embeds(content: str) -> str:
    """Neutralize any A/V/PDF embed in an ingest-produced note body: `![[clip.mp4]]` →
    `[[clip.mp4]]` (a link, not an embed). This is the explicit infinite-loop guard —
    once approved and filed, the note is re-scanned by ResourceScanService, and a live
    resource embed would re-trigger ingestion of the source we just ingested. Images are
    untouched so keyframes/figures still flow through the captioner. Standalone v2 notes
    don't emit these today (source is a link footer), so this is belt-and-suspenders that
    keeps the invariant true even if drafting/media rendering changes."""
    return _RESOURCE_EMBED_RE.sub(r"\1", content)


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


def find_home(text: str) -> str:
    """Folder for a new note from its OWN content (title + body) via the hierarchical
    centroid classifier (`placement`). Returns "" ("unsorted") when nothing is confident
    enough — so the triage UI shows no pre-pick instead of a confidently-wrong folder.
    `text` should be the note's content, NOT just a short title (short titles were the old
    kNN-vote's core failure)."""
    try:
        from ingest import placement
        return placement.suggest_folder(text) or ""
    except Exception as e:
        log.warning("find_home failed: %s", e)
        return ""


def _insert_frontmatter(content: str, extra: str) -> str:
    """Insert `extra` lines into a note's leading `---` block (or create one)."""
    if content.startswith("---\n"):
        end = content.index("\n---\n", 4)          # close of the frontmatter block
        return content[:end + 1] + extra + content[end + 1:]
    return f"---\n{extra}---\n\n{content}"


def stamp_inbox(content: str, source: str, suggested_folder: str) -> str:
    """Inject the inbox-triage frontmatter into a standalone note's `---` block so
    the Learn Inbox can list it, show where it came from, and pre-pick a destination.
    Stripped again by the backend when the note is filed (POST /inbox/file)."""
    extra = (f"ingest-inbox: true\n"
             f"ingest-source: {source}\n"
             f"ingest-suggested-folder: {suggested_folder}\n")
    return _insert_frontmatter(content, extra)


def stamp_capture(content: str, capture_id: str, seq: int) -> str:
    """Link a proposed note to the Capture that produced it (see CAPTURE_ARCH.md).
    Frontmatter is the DURABLE source of truth — the Java NoteIndexRepository mirrors
    capture-id/capture-seq into DB columns so the link survives a forceResync. `seq`
    preserves source order so the Learn queue never shuffles chapters. Unlike the
    ingest-* keys, these are NOT stripped when the note is filed."""
    extra = (f"capture-id: {capture_id}\n"
             f"capture-seq: {seq}\n")
    return _insert_frontmatter(content, extra)


def ensure_folder(folder: str) -> None:
    """Make sure a vault-relative folder exists before create_note targets it."""
    res = httpx.post(f"{BACKEND_URL}/api/internal/folders", headers=_headers(),
                     json={"path": folder}, timeout=30)
    if res.status_code != 200:
        raise PublishError(f"ensure_folder {res.status_code}: {res.text[:300]}")


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


def create_capture(capture_id: str, source_ref: str, content: str) -> str:
    """Stash a pre-rewrite snapshot of an in-place note as its Capture source —
    a note can hold multiple embeds, so the note itself (not any one embed) is
    always the single source. Returns the vault-relative snapshot path."""
    res = httpx.post(f"{BACKEND_URL}/api/internal/capture", headers=_headers(),
                     json={"captureId": capture_id, "sourceRef": source_ref, "content": content},
                     timeout=30)
    if res.status_code != 200:
        raise PublishError(f"create_capture {res.status_code}: {res.text[:300]}")
    return res.json()["sourcePath"]
