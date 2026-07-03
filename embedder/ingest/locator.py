"""Splice resolution — a Unit's locator_span → a renderable source region (§7).  [NEW / v2]

The learn-view splice (INGESTION_V2_FLOWS §7): a note renders its source region directly
instead of making you scroll a whole document —
  txt/md/html → scroll + highlight the `char` span (the quote)
  pdf/epub    → the kept page-shot(s) cropped to the `bbox` span (no scrolling)
  audio/video → play the kept clip from `start_ms`

This module is **pure**: `resolve_splice(unit, ir)` returns a `SpliceView` descriptor the
frontend consumes; it reads `ir.normalized_text` / `ir.anchors` but touches no files and
renders nothing. Post-commit only what retention kept survives (§6) — for text the quote
is inline in the note already, so a text splice needs nothing beyond the char range.

*To change what the consume layer receives:* this file (mirror any change in the frontend
learn-view + INGESTION_V2 §7).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

from ingest.ir import SourceIR
from ingest.segment import Unit


@dataclass
class SpliceView:
    kind: str                                   # "text" | "pdf" | "av" | "unknown"
    # text
    start_char: Optional[int] = None
    end_char: Optional[int] = None
    quote: Optional[str] = None
    # pdf / epub
    pages: list[int] = field(default_factory=list)
    page_images: list[str] = field(default_factory=list)   # anchor image paths, page order
    bbox_start: Optional[list[float]] = None
    bbox_end: Optional[list[float]] = None
    # audio / video
    start_ms: Optional[int] = None
    end_ms: Optional[int] = None

    def as_dict(self) -> dict:
        d = {"kind": self.kind}
        if self.kind == "text":
            d.update(start_char=self.start_char, end_char=self.end_char, quote=self.quote)
        elif self.kind == "pdf":
            d.update(pages=self.pages, page_images=self.page_images,
                     bbox_start=self.bbox_start, bbox_end=self.bbox_end)
        elif self.kind == "av":
            d.update(start_ms=self.start_ms, end_ms=self.end_ms)
        return d


def resolve_splice(unit: Unit, ir: SourceIR) -> SpliceView:
    """Turn a committed Unit's locator_span into a renderable source region."""
    span = unit.locator_span
    kind = span.get("kind")

    if kind == "char":
        start, end = span.get("start_char"), span.get("end_char")
        quote = None
        if ir.normalized_text is not None and start is not None and end is not None:
            quote = ir.normalized_text[start:end]
        return SpliceView(kind="text", start_char=start, end_char=end, quote=quote)

    if kind == "page":
        pages = span.get("pages", [])
        by_key = {a.key: a.image_path for a in ir.anchors if a.image_path}
        images = [by_key[str(p)] for p in pages if str(p) in by_key]
        return SpliceView(kind="pdf", pages=pages, page_images=images,
                          bbox_start=span.get("bbox_start"), bbox_end=span.get("bbox_end"))

    if kind == "time":
        return SpliceView(kind="av", start_ms=span.get("start_ms"), end_ms=span.get("end_ms"))

    return SpliceView(kind="unknown")


def resolve_all(units: list[Unit], ir: SourceIR) -> list[dict]:
    """Splice descriptors for every Unit in source order (learn-view walks a source
    one-to-many, ordered by `order_index`, without jumping — §7)."""
    ordered = sorted(units, key=lambda u: u.order_index)
    return [resolve_splice(u, ir).as_dict() for u in ordered]
