"""MCP tools: vault navigation + placement suggestions.

Split out of mcp_server.py for readability — the FastMCP instance and the
DB/vault-path helpers these tools depend on still live there; this module
just registers @mcp.tool() functions onto it.
"""
import logging
from pathlib import Path, PurePosixPath

import mcp_server
from mcp_server import mcp, _resolve_in_vault, _vector_candidates
from model_runtime import embed_texts

# NOTE: VAULT_DIR is read as `mcp_server.VAULT_DIR` (module-qualified), not
# imported as a bare name, on purpose — tests monkeypatch the attribute on
# the mcp_server module object (`monkeypatch.setattr(mcp_server, "VAULT_DIR", ...)`),
# which a bare `from mcp_server import VAULT_DIR` would snapshot at import
# time and never see updated.

log = logging.getLogger("embedder.mcp")

# Folders that hold media/build artifacts, not knowledge notes — excluded
# from traversal output so the tree stays signal, not noise.
TREE_SKIP_DIRS = {"resources", "_workspace", "_reports"}
LIST_NOTES_CAP = 200


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
    root = mcp_server.VAULT_DIR.resolve()
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
    rel = resolved.relative_to(mcp_server.VAULT_DIR.resolve()).as_posix()
    out = {
        "folder": "/vault" if rel == "." else f"/vault/{rel}",
        "subfolders": subfolders,
        "noteCount": len(notes),
    }
    if include_notes:
        out["notes"] = notes[:LIST_NOTES_CAP]
        out["notesTruncated"] = len(notes) > LIST_NOTES_CAP
    return out
