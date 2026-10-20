"""Which vault a piece of embedder work belongs to, and where that vault lives on disk.

Multi-vault (architecture_plans/VAULTS_PLAN.md, M5). The embedder holds NO "current vault"
state of its own for backend-driven work: the Java backend sends `vault_id` + `root` (the
vault's container path) with every request, the endpoint builds a `Vault` from them and passes
it down explicitly (same rule as the Java side: no hidden/ThreadLocal vault).

Two entry points that have no request context resolve the ACTIVE vault from the DB instead:
the MCP server (tools called by an external agent) and the browser-facing GETs (/pdf-page,
/media-rendition). That lookup is cached ~5s (`active()`).

Vault 1 is the legacy vault: its dirs stay on the old compose mounts (`/reports`, `/workspace`,
`/resources` via AGENT_REPORTS_DIR / DOWNLOAD_DIR / RESOURCE_DIR). Every other vault uses
`<root>/_reports`, `<root>/_workspace`, `<root>/resources` (the M6 mounts just have to make those
writable; the paths are computed here today).
"""
from __future__ import annotations

import logging
import os
import threading
import time
from pathlib import Path
from typing import NamedTuple, Optional

log = logging.getLogger("embedder.vault_paths")

DEFAULT_VAULT_ID = 1
DEFAULT_ROOT = os.environ.get("VAULT_DIR", "/vault")
ACTIVE_TTL_S = float(os.environ.get("ACTIVE_VAULT_TTL_S", "5"))


class Vault(NamedTuple):
    id: int
    root: Path

    # ── construction ────────────────────────────────────────────────────────────

    @staticmethod
    def default() -> "Vault":
        return Vault(DEFAULT_VAULT_ID, Path(DEFAULT_ROOT))

    @staticmethod
    def of(vault_id: Optional[int], root: Optional[str]) -> "Vault":
        """From the `vault_id` + `root` a backend request carries. Both absent = a caller that
        predates M5 = vault 1 at the legacy root. An id without a root is only valid for vault 1
        (we refuse to guess another vault's location)."""
        vid = DEFAULT_VAULT_ID if vault_id is None else int(vault_id)
        if root:
            return Vault(vid, Path(root))
        if vid != DEFAULT_VAULT_ID:
            raise ValueError(f"vault {vid}: request carried no root")
        return Vault.default()

    # ── file I/O roots ──────────────────────────────────────────────────────────

    @property
    def legacy(self) -> bool:
        return self.id == DEFAULT_VAULT_ID

    def reports_dir(self) -> str:
        return os.environ.get("AGENT_REPORTS_DIR", "/reports") if self.legacy \
            else str(self.root / "_reports")

    def workspace_dir(self) -> str:
        return os.environ.get("DOWNLOAD_DIR", "/workspace") if self.legacy \
            else str(self.root / "_workspace")

    def resources_dir(self) -> str:
        return os.environ.get("RESOURCE_DIR", "/resources") if self.legacy \
            else str(self.root / "resources")

    # ── path helpers ────────────────────────────────────────────────────────────

    def resolve(self, note_path: str) -> Path:
        """Vault-relative or absolute path -> absolute, refusing anything outside this vault."""
        candidate = Path(note_path)
        if not candidate.is_absolute():
            candidate = self.root / candidate
        resolved = candidate.resolve()
        if not resolved.is_relative_to(self.root.resolve()):
            raise ValueError(f"Path escapes the vault: {note_path}")
        return resolved

    def rel(self, p) -> str:
        """Container path -> vault-relative path used in note bodies (mediaUrl resolves these).
        Anything under the vault root is just stripped; vault 1 also maps its legacy rw mounts:
        /resources/... -> resources/..., /workspace/... -> _workspace/..."""
        s = str(p)
        prefixes = [(str(self.root).rstrip("/") + "/", "")]
        if self.legacy:
            prefixes = [("/resources/", "resources/"), ("/workspace/", "_workspace/"),
                        ("/vault/", "")] + prefixes
        for prefix, rel in prefixes:
            if s.startswith(prefix):
                return rel + s[len(prefix):]
        return s

    def folder_label(self, rel: str) -> str:
        """Absolute container folder shown to agents: '<root>' or '<root>/<rel>'."""
        root = str(self.root).rstrip("/") or "/"
        return root if rel in ("", ".") else f"{root}/{rel}"


# ── active vault (MCP + browser-facing GETs) ───────────────────────────────────

_lock = threading.Lock()
_cache: dict = {"ts": 0.0, "vault": None}


def _fetch_active() -> Optional[Vault]:
    """SELECT the active vault row. None = no registry yet (pre-registry install -> vault 1)."""
    import psycopg
    url = os.environ.get("DATABASE_URL", "postgresql://obsidian:obsidian@postgres:5432/obsidian")
    try:
        with psycopg.connect(url) as conn:
            row = conn.execute(
                "SELECT id, container_path FROM vaults WHERE active LIMIT 1").fetchone()
    except psycopg.errors.UndefinedTable:
        return None
    return Vault(int(row[0]), Path(row[1])) if row else None


def active() -> Vault:
    """The active vault, cached ~ACTIVE_TTL_S. Vault 1 when the registry doesn't exist yet. On a
    DB error the last known value is served (stale beats wrong); with none cached it raises -
    we never silently fall back to vault 1 while another vault may be active."""
    now = time.monotonic()
    with _lock:
        v, ts = _cache["vault"], _cache["ts"]
        if v is not None and now - ts < ACTIVE_TTL_S:
            return v
    try:
        fresh = _fetch_active() or Vault.default()
    except Exception as e:
        with _lock:
            if _cache["vault"] is not None:
                log.warning("active vault lookup failed (%s) - serving cached vault %s",
                            e, _cache["vault"].id)
                return _cache["vault"]
        raise
    with _lock:
        _cache["vault"], _cache["ts"] = fresh, now
    return fresh


def invalidate_active() -> None:
    with _lock:
        _cache["vault"], _cache["ts"] = None, 0.0
