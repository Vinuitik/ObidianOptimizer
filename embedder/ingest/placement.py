"""Hierarchical nearest-centroid folder placement (INGEST folder suggestion).

Replaces the old kNN-title-vote (`mcp_server.find_home_for_note`) that was "incredibly random":
it embedded only the SHORT note/source title, voted over the 10 nearest notes, and — because
770 unfiled `_inbox` chunks live in the index — kept recommending the staging folder back to
itself. Every note from one source also got the SAME folder (voted once on the source title).

This instead models each folder as the CENTROID of its notes' embeddings and walks the tree:
start at the main folders, descend into whichever child centroid is closest to THIS note's
content, and keep descending while going deeper stays closer (user's rule). Constraints:
  • min depth 2 — never suggest the root or a bare main folder (notes live in `Main/Sub[/…]`);
  • staging/media folders (`_inbox`, `ingest`, `resources`, `_workspace`, `_reports`, dotfolders)
    are excluded as both candidates AND from the centroids, killing the self-pollution loop;
  • a confidence floor (`PLACEMENT_MIN_SIM`) → suggest nothing ("unsorted") rather than a
    confidently-wrong folder;
  • tiny folders (`PLACEMENT_MIN_FOLDER_NOTES`) are skipped so a 1-note folder can't capture.

Pure/testable: `build_model(rows)` and `suggest(model, vec)` take plain data; only `_folder_rows`
and `suggest_folder` touch the DB / embedder. Centroids are cached in-process (`PLACEMENT_CACHE_TTL_S`)
so an ingest burst embeds once, not per note.
"""
from __future__ import annotations

import logging
import os
import threading
import time
from dataclasses import dataclass, field
from typing import Optional

import numpy as np

log = logging.getLogger("embedder.ingest.placement")

VAULT_ROOT = "/vault"
_EXCLUDE_SEG = {"_inbox", "ingest", "resources", "_workspace", "_reports"}

MIN_DEPTH = int(os.environ.get("PLACEMENT_MIN_DEPTH", "2"))            # folder segments under /vault
MIN_SIM = float(os.environ.get("PLACEMENT_MIN_SIM", "0.35"))          # else → unsorted
MIN_FOLDER_NOTES = int(os.environ.get("PLACEMENT_MIN_FOLDER_NOTES", "3"))
CACHE_TTL_S = int(os.environ.get("PLACEMENT_CACHE_TTL_S", "600"))


@dataclass
class _Node:
    path: str
    direct_c: Optional[np.ndarray] = None    # centroid of notes DIRECTLY in this folder
    direct_n: int = 0
    children: dict = field(default_factory=dict)
    sub_c: Optional[np.ndarray] = None       # centroid of this folder's whole SUBTREE
    sub_n: int = 0


def _excluded(folder: str) -> bool:
    parts = folder.split("/")
    return any(p in _EXCLUDE_SEG or p.startswith(".") for p in parts)


def _depth(path: str) -> int:
    # segments under /vault: '/vault/AI/Agents' → 2
    return max(0, len([p for p in path.split("/") if p]) - 1)


def build_model(rows: list[tuple]) -> dict[str, _Node]:
    """`rows` = (folder_path, centroid ndarray, note_count) for each DIRECT folder → a node
    tree with count-weighted subtree centroids. Pure."""
    nodes: dict[str, _Node] = {}

    def get(path: str) -> _Node:
        n = nodes.get(path)
        if n is None:
            n = nodes[path] = _Node(path=path)
        return n

    get(VAULT_ROOT)
    for folder, cen, n in rows:
        if not folder.startswith(VAULT_ROOT + "/") or _excluded(folder):
            continue
        node = get(folder)
        node.direct_c, node.direct_n = np.asarray(cen, dtype=np.float32), int(n)
        parts = folder.split("/")                 # ['', 'vault', 'AI', 'Agents']
        for i in range(2, len(parts)):            # link every ancestor edge down from /vault
            get("/".join(parts[:i])).children["/".join(parts[:i + 1])] = get("/".join(parts[:i + 1]))

    def compute(node: _Node) -> None:
        acc, total = None, 0
        if node.direct_c is not None:
            acc, total = node.direct_c * node.direct_n, node.direct_n
        for ch in node.children.values():
            compute(ch)
            if ch.sub_c is not None:
                acc = ch.sub_c * ch.sub_n if acc is None else acc + ch.sub_c * ch.sub_n
                total += ch.sub_n
        if total:
            node.sub_c, node.sub_n = acc / total, total

    compute(nodes[VAULT_ROOT])
    return nodes


def _cos(a: np.ndarray, b: np.ndarray) -> float:
    na, nb = np.linalg.norm(a), np.linalg.norm(b)
    if na == 0 or nb == 0:
        return -1.0
    return float(np.dot(a, b) / (na * nb))


def suggest(model: dict[str, _Node], vec: np.ndarray) -> Optional[str]:
    """Descend the centroid tree for note embedding `vec` → a folder path (depth ≥ MIN_DEPTH),
    or None ("unsorted") when nothing is confident enough. Pure."""
    root = model.get(VAULT_ROOT)
    if root is None or not root.children:
        return None

    def best_child(node: _Node) -> Optional[_Node]:
        kids = [c for c in node.children.values() if c.sub_c is not None and c.sub_n >= MIN_FOLDER_NOTES]
        return max(kids, key=lambda c: _cos(vec, c.sub_c), default=None)

    top = best_child(root)
    if top is None or _cos(vec, top.sub_c) < MIN_SIM:
        return None                                # no confident main folder → unsorted

    node = root
    while True:
        child = best_child(node)
        if child is None:
            break
        node_sim = _cos(vec, node.sub_c) if node.sub_c is not None else -1.0
        # Descend while it gets MORE specific-and-closer, OR we're still shallower than allowed.
        if _cos(vec, child.sub_c) >= node_sim or _depth(node.path) < MIN_DEPTH:
            node = child
        else:
            break

    return node.path if _depth(node.path) >= MIN_DEPTH else None


# ── DB / embedder-backed entry point (cached) ─────────────────────────────────

_lock = threading.Lock()
_cache: dict = {"ts": 0.0, "model": None}


def _folder_rows() -> list[tuple]:
    """Per-DIRECT-folder centroid + note count from the live vector index. `AVG(embedding)`
    (pgvector) → parsed to an ndarray. Staging/media folders are filtered in `build_model`."""
    from mcp_server import _query_db
    rows = _query_db(
        """
        SELECT regexp_replace(note_path, '/[^/]*$', '') AS folder,
               AVG(embedding)::text AS centroid,
               COUNT(*) AS n
        FROM note_chunks
        WHERE embedding IS NOT NULL AND note_path LIKE '/vault/%%'
        GROUP BY 1
        """)
    out = []
    for folder, cen_txt, n in rows:
        try:
            out.append((folder, np.fromstring(cen_txt.strip()[1:-1], sep=",", dtype=np.float32), n))
        except Exception:
            continue
    return out


def _model() -> dict[str, _Node]:
    now = time.time()
    with _lock:
        if _cache["model"] is None or now - _cache["ts"] > CACHE_TTL_S:
            _cache["model"] = build_model(_folder_rows())
            _cache["ts"] = now
        return _cache["model"]


def invalidate_cache() -> None:
    with _lock:
        _cache["model"], _cache["ts"] = None, 0.0


def suggest_folder(text: str) -> Optional[str]:
    """Best folder for a note's own content (title + body), or None ("unsorted"). One embed
    call; centroids are cached. Never raises — a failure just yields None so ingest continues."""
    try:
        from model_runtime import embed_texts
        vec = np.asarray(embed_texts([text[:4000]], "document")[0], dtype=np.float32)
        return suggest(_model(), vec)
    except Exception as e:
        log.warning("placement.suggest_folder failed: %s", e)
        return None
