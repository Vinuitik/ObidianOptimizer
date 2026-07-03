"""Retention — what survives commit (INGESTION_V2_FLOWS §6/§8c/§9h).  [NEW / v2]

**Policy: delete-by-default.** Anything not referenced by a committed Unit is dropped;
the raw source blob is always dropped. Regeneration = re-ingest (accepted cost). The
reason is storage, not copyright — referenced regions are fine to keep.

This module is **pure planning**: `compute_retention(...)` returns a `RetentionPlan`
(paths to keep / drop, whether to keep the transcript). The actual filesystem sweep is
a separate executor (the Java internal API / `publish`), so this stays I/O-free and
unit-testable. Nothing here deletes anything.

Per-medium survivors (INGESTION_V2 §1 table):
  pdf/epub   → cropped page screenshots for REFERENCED pages only
  txt/md/html→ nothing extra (the note quote IS the substring — already inline)
  audio      → full transcript + KeptFragment clips (re-Whisper is expensive, §8c)
  video      → full transcript + kept keyframes + KeptFragment clips (§9h)
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

from ingest.ir import Medium, SourceIR
from ingest.segment import Unit


@dataclass
class KeptFragment:
    """A source region the user promoted during review to survive + inline into a note.
    Media-typed: video/audio clip [start_ms,end_ms], or a PDF figure crop path. For text
    the 'keep' is trivial (the quote is already in the note), so text never needs one."""
    kind: str                       # "clip" | "figure"
    media_path: Optional[str] = None
    start_ms: Optional[int] = None
    end_ms: Optional[int] = None
    page_no: Optional[int] = None
    note_path: Optional[str] = None


@dataclass
class RetentionPlan:
    keep_paths: set[str] = field(default_factory=set)   # anchor shots / keyframes / crops
    drop_paths: set[str] = field(default_factory=set)   # unreferenced media + raw blob
    keep_transcript: bool = False                        # A/V only (§8c)
    referenced_pages: set[int] = field(default_factory=set)

    def as_dict(self) -> dict:
        return {
            "keep_paths": sorted(self.keep_paths),
            "drop_paths": sorted(self.drop_paths),
            "keep_transcript": self.keep_transcript,
            "referenced_pages": sorted(self.referenced_pages),
        }


def referenced_pages(units: list[Unit]) -> set[int]:
    """Pages any committed Unit's locator_span touches (pdf/epub retention key)."""
    pages: set[int] = set()
    for u in units:
        span = u.locator_span
        if span.get("kind") == "page":
            pages.update(span.get("pages", []))
    return pages


def referenced_media(units: list[Unit], ir: SourceIR) -> set[str]:
    """media_ref of every block owned by a committed Unit (kept keyframes / figure crops)."""
    owned_ids = {bid for u in units for bid in u.block_ids}
    return {b.media_ref for b in ir.blocks
            if b.id in owned_ids and b.media_ref}


def compute_retention(
    ir: SourceIR,
    units: list[Unit],
    kept_fragments: Optional[list[KeptFragment]] = None,
    source_blob_path: Optional[str] = None,
) -> RetentionPlan:
    """Decide keep/drop from the *committed* Units. Everything an anchor/media could be
    is partitioned into keep vs drop; the raw source blob is always dropped."""
    kept_fragments = kept_fragments or []
    plan = RetentionPlan()

    pages = referenced_pages(units)
    plan.referenced_pages = pages

    # 1. page screenshots: keep only anchors for referenced pages, drop the rest.
    for a in ir.anchors:
        if not a.image_path:
            continue
        try:
            page = int(a.key)
        except (TypeError, ValueError):
            page = None
        if page is not None and page in pages:
            plan.keep_paths.add(a.image_path)
        else:
            plan.drop_paths.add(a.image_path)

    # 2. block media (keyframes / figure crops): keep if owned by a committed Unit.
    kept_media = referenced_media(units, ir)
    plan.keep_paths.update(kept_media)
    for b in ir.blocks:
        if b.media_ref and b.media_ref not in kept_media:
            plan.drop_paths.add(b.media_ref)

    # 3. user-promoted fragments always survive.
    for kf in kept_fragments:
        if kf.media_path:
            plan.keep_paths.add(kf.media_path)

    # 4. A/V: the transcript is the cheap durable substitute for the deleted media.
    plan.keep_transcript = ir.medium in (Medium.AUDIO, Medium.VIDEO)

    # 5. the raw source blob is always dropped (re-ingest to regenerate).
    if source_blob_path:
        plan.drop_paths.add(source_blob_path)

    # keep wins over drop if something is both referenced and unreferenced-elsewhere.
    plan.drop_paths -= plan.keep_paths
    return plan
