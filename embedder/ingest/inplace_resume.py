"""Durable (note_path, embed_ref) → saved-bundle index for DEFERRED in-place ingests.

Why this exists (the efficiency win, INGEST_DURABILITY_PRIORITY §4). When an in-place
synthesis is DEFERRED (all LLM providers cooling), the extracted bundle is already on
disk — but unlike a standalone capture there is NO capture row to park it on:
`_synthesize_and_inject` only calls `publish.create_capture` AFTER synthesis, so a job
that defers at the LLM `outline()` step has `capture_id == None` and the backend failure
poller can't see it. The note simply stays `ingest_pending`, so `ResourceScanService`
re-fires the SAME `/ingest {ref, note_path}` every 5 min — which today re-extracts from
scratch (re-download + re-whisper + re-keyframe), throwing the saved bundle away.

This index closes that: on DEFER we record the saved bundle keyed by (note, embed); on the
next re-fire `jobs.submit` looks it up and RESUMES from the bundle (synthesis only) instead
of re-extracting. The entry is dropped once synthesis finally succeeds (or terminally fails)
so a genuine later re-ingest of a changed source still extracts fresh.

Restart-durable: this index file AND the bundles both live under `jobs.BUNDLE_DIR` on the
MODEL_CACHE volume, so an embedder restart mid-cooldown still resumes (the in-memory DEFERRED
job is gone, but the re-fire consults this file). It is the embedder-side mirror of the
standalone capture's DB `bundle_ref`, kept here because in-place has no capture row to hang it on.
"""
import json
import logging
import os
import threading
import time

log = logging.getLogger("embedder.ingest.inplace_resume")

# One writer at a time. The index is touched from the FastAPI request thread (lookup on
# submit) and the single ingest worker thread (record/drop around synthesis) — distinct
# threads, so the file needs its own lock.
_lock = threading.Lock()


def _index_path():
    # Derived from jobs.BUNDLE_DIR (lazy import avoids a circular import at module load,
    # and lets tests redirect the whole bundle dir by monkeypatching jobs.BUNDLE_DIR).
    from ingest import jobs
    return jobs.BUNDLE_DIR / "inplace_deferred.json"


def _key(note_path: str, embed_ref: str) -> str:
    return f"{note_path}\x00{embed_ref}"


def _load() -> dict:
    try:
        return json.loads(_index_path().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}   # missing or corrupt → treat as empty (self-heals on next write)


def _save(idx: dict) -> None:
    path = _index_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(idx, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(path)   # atomic swap — never leave a half-written index


def record(note_path, embed_ref, bundle_path,
           ref=None, source_type=None, title=None) -> None:
    """A DEFERRED in-place job's bundle is resume-ready — remember it for this (note, embed).
    Idempotent: re-recording the same key (repeated cooldown re-defers) just refreshes it."""
    if not (note_path and embed_ref and bundle_path):
        return
    with _lock:
        idx = _load()
        idx[_key(note_path, embed_ref)] = {
            "bundle_path": bundle_path, "ref": ref,
            "source_type": source_type, "title": title, "ts": time.time()}
        _save(idx)


def lookup(note_path, embed_ref):
    """The saved bundle entry for (note, embed) if one exists AND its bundle file is still
    present, else None. A dangling entry (bundle swept away) is pruned so the caller falls
    back to a fresh extract rather than resuming a ghost."""
    if not (note_path and embed_ref):
        return None
    with _lock:
        idx = _load()
        key = _key(note_path, embed_ref)
        entry = idx.get(key)
        if not entry:
            return None
        bundle_path = entry.get("bundle_path")
        if not bundle_path or not os.path.isfile(bundle_path):
            idx.pop(key, None)
            _save(idx)
            return None
        return entry


def drop(note_path, embed_ref) -> None:
    """Forget (note, embed) — called when its synthesis finally settles (DONE or terminal
    FAIL), so a genuine future re-ingest of a changed source extracts fresh. No-op if absent."""
    if not (note_path and embed_ref):
        return
    with _lock:
        idx = _load()
        if idx.pop(_key(note_path, embed_ref), None) is not None:
            _save(idx)
