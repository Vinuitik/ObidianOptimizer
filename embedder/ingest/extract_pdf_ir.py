"""Native PDF/EPUB extraction → SourceIR (INGESTION_V2_FLOWS §3a).  [NEW / v2]

The block-level replacement for `extract_pdf.py`'s page mode. v1 emitted one segment per
**page** (`{loc:{page}, text:<whole page>}`); this emits one Block per layout block with a
`PageBox{page_no, bbox}`, so a Unit anchors to a sub-page region and can span pages
(§1 "the unit of anchoring is the block"). Page number is used *only* for retention
(which screenshot to keep), never for anchoring.

**Split by testability** (mirrors `extract_ir.from_markdown` vs `from_html`):
  - `build_ir(pages, toc, anchors, title, source_id)` — **pure**: PageParse[] → SourceIR,
    heading classification by font ratio, and the layout HARD flags (§3c). Unit-tested with
    synthetic PageParse; no `fitz`, no ML.
  - `from_pdf(ref, path)` — I/O: drives PyMuPDF to build PageParse (spans→runs, tables, OCR
    fallback) + lazy page-shot anchors, then calls `build_ir`. Not runnable in the sandbox
    (no fitz); the pure core is what the tests cover.

HARD flags here (correctness likely wrong → Unit REVIEWING, §3c): READING_ORDER (multi-column
scramble), TABLE (block over a table region), OCR (scanned page), LAYOUT (y runs backward in a
column). The IR-computable SOFT flags (NO_STRUCTURE, OVERSIZE_BLOCK) are added by
`flagging.flag_source` at the end — this module only owns the fitz-derived HARD ones.

*To tune heading detection:* `FONT_HEADING_RATIO`. *To tune columns:* `COLUMN_GAP_PTS`.
"""
from __future__ import annotations

import logging
import os
from collections import Counter
from dataclasses import dataclass, field
from typing import Optional

from ingest.flagging import flag_source
from ingest.ir import (Anchor, Block, BlockType, Flag, Medium, PageBox,
                       Severity, SourceIR, TocEntry)

log = logging.getLogger("embedder.ingest.pdf_ir")

# A run whose font is this many times the body size (or bold + short) is a heading.
FONT_HEADING_RATIO = float(os.environ.get("FONT_HEADING_RATIO", "1.2"))
# Two x0 cluster starts further apart than this (PDF points) are distinct columns.
COLUMN_GAP_PTS = float(os.environ.get("INGEST_COLUMN_GAP_PTS", "50"))
# A heading candidate must be at most this many words (a paragraph in bold isn't a heading).
HEADING_MAX_WORDS = int(os.environ.get("INGEST_HEADING_MAX_WORDS", "20"))
# y0 dropping back up by more than this within a column is a backward run → LAYOUT.
Y_BACKTRACK_TOL = float(os.environ.get("INGEST_Y_BACKTRACK_TOL", "4"))


# ── Fitz-independent input contract (the I/O layer fills these) ───────────────

@dataclass
class SpanRun:
    """One merged layout block on a page, before classification. `bbox` = [x0,y0,x1,y1]
    in PDF points (origin top-left, y grows downward — fitz convention)."""
    text: str
    size: float
    bold: bool
    bbox: list[float]


@dataclass
class PageParse:
    page_no: int
    runs: list[SpanRun]
    ocr: bool = False                                   # page fell back to Tesseract
    table_rects: list[list[float]] = field(default_factory=list)  # fitz find_tables bboxes


# ── Pure core: PageParse[] → SourceIR ─────────────────────────────────────────

def build_ir(
    pages: list[PageParse],
    toc: Optional[list[TocEntry]] = None,
    anchors: Optional[list[Anchor]] = None,
    title: str = "Untitled",
    source_id: Optional[str] = None,
    medium: Medium = Medium.PDF,
) -> SourceIR:
    """Assemble positioned Blocks with font-ratio heading levels + layout HARD flags. Pure."""
    body = _body_size(pages)
    levels = _heading_levels(pages, body)

    blocks: list[Block] = []
    order = 0
    for pg in pages:
        page_flags = _page_hard_flags(pg)
        for run in pg.runs:
            text = run.text.strip()
            if not text:
                continue
            btype, level = _classify(run, body, levels)
            flags = _run_flags(run, pg, page_flags)
            blocks.append(Block(order_index=order, type=btype, level=level, text=text,
                                locator=PageBox(page_no=pg.page_no, bbox=list(run.bbox)),
                                flags=flags))
            order += 1

    ir = SourceIR(medium=medium, title=title, blocks=blocks,
                  anchors=anchors or [], toc=toc or [])
    if source_id:
        ir.source_id = source_id
    flag_source(ir)                                     # add IR-computable SOFT flags
    return ir


def _body_size(pages: list[PageParse]) -> float:
    """Dominant font size = the size covering the most characters (the running text)."""
    weight: Counter = Counter()
    for pg in pages:
        for r in pg.runs:
            weight[round(r.size, 1)] += len(r.text.strip())
    return weight.most_common(1)[0][0] if weight else 12.0


def _heading_levels(pages: list[PageParse], body: float) -> dict[float, int]:
    """Distinct sizes above the heading threshold → levels 1..6 by descending size."""
    thresh = body * FONT_HEADING_RATIO
    sizes = sorted({round(r.size, 1) for pg in pages for r in pg.runs
                    if round(r.size, 1) >= thresh}, reverse=True)
    return {s: min(i + 1, 6) for i, s in enumerate(sizes)}


def _classify(run: SpanRun, body: float, levels: dict[float, int]) -> tuple[BlockType, Optional[int]]:
    size = round(run.size, 1)
    n_words = len(run.text.split())
    if size in levels and n_words <= HEADING_MAX_WORDS:
        return BlockType.HEADING, levels[size]
    # bold + short with no larger font (fake headings) → treat as a level-tier heading
    if run.bold and n_words <= HEADING_MAX_WORDS and size >= body:
        return BlockType.HEADING, min((max(levels.values()) + 1) if levels else 3, 6)
    return BlockType.PARAGRAPH, None


# ── Layout HARD flags (§3c) — the fitz-derived confidence signals ─────────────

def _page_hard_flags(pg: PageParse) -> list[Flag]:
    """Whole-page HARD flags that taint every block on the page (OCR, multi-column
    scramble, backward-y layout). Table is per-block (below)."""
    flags: list[Flag] = []
    if pg.ocr:
        flags.append(Flag("OCR", Severity.HARD, f"page {pg.page_no} scanned → OCR"))

    cols = _column_index(pg.runs)
    n_cols = len(set(cols)) if cols else 1
    if n_cols >= 2 and not _non_decreasing(cols):
        # reading order zig-zags across columns → the span sequence is scrambled
        flags.append(Flag("READING_ORDER", Severity.HARD,
                          f"{n_cols} columns, blocks cross bands"))
    if _y_backtracks(pg.runs, cols):
        flags.append(Flag("LAYOUT", Severity.HARD, "y runs backward within a column"))
    return flags


def _run_flags(run: SpanRun, pg: PageParse, page_flags: list[Flag]) -> list[Flag]:
    flags = list(page_flags)
    if any(_intersects(run.bbox, rect) for rect in pg.table_rects):
        flags.append(Flag("TABLE", Severity.HARD, "block overlaps a table region"))
    return flags


def _column_index(runs: list[SpanRun]) -> list[int]:
    """Assign each run a column index by clustering x0 with a fixed gap. Empty → []."""
    if not runs:
        return []
    xs = sorted({round(r.bbox[0]) for r in runs})
    starts = [xs[0]]
    for x in xs[1:]:
        if x - starts[-1] > COLUMN_GAP_PTS:
            starts.append(x)
    # nearest cluster start at or below the run's x0
    return [max(range(len(starts)),
                key=lambda i: starts[i] if starts[i] <= round(r.bbox[0]) else -1e9)
            for r in runs]


def _non_decreasing(seq: list[int]) -> bool:
    return all(seq[i] <= seq[i + 1] for i in range(len(seq) - 1))


def _y_backtracks(runs: list[SpanRun], cols: list[int]) -> bool:
    """Within a single column, reading order should march down the page. A run whose top
    jumps back above the previous run in the same column (beyond tol) is interleaved
    sidebar/footnote/callout content."""
    last_y: dict[int, float] = {}
    for run, c in zip(runs, cols):
        y0 = run.bbox[1]
        if c in last_y and y0 < last_y[c] - Y_BACKTRACK_TOL:
            return True
        last_y[c] = y0
    return False


def _intersects(a: list[float], b: list[float]) -> bool:
    return not (a[2] < b[0] or b[2] < a[0] or a[3] < b[1] or b[3] < a[1])


# ── I/O layer: PyMuPDF → PageParse[] → build_ir (not sandbox-testable) ─────────

MIN_OCR_WORDS = 20
_BOLD_BIT = 1 << 4          # fitz span flags: bit 4 = bold


def from_pdf(ref: str, resolved_path) -> SourceIR:
    """Drive PyMuPDF into PageParse[], render lazy page-shot anchors, then `build_ir`.
    Anchor `image_path` is a filename placeholder (rendered/stored lazily at retention,
    §6 — only referenced pages survive). Requires `fitz`; the pure `build_ir` is what the
    unit tests exercise."""
    import fitz  # PyMuPDF
    from pathlib import Path

    path = Path(resolved_path)
    doc = fitz.open(path)
    pages: list[PageParse] = []
    anchors: list[Anchor] = []
    for page_no, page in enumerate(doc, start=1):
        runs, ocr = _runs_from_page(page)
        try:
            rects = [list(t.bbox) for t in page.find_tables().tables]
        except Exception:
            rects = []
        pages.append(PageParse(page_no=page_no, runs=runs, ocr=ocr, table_rects=rects))
        rect = page.rect
        anchors.append(Anchor(key=str(page_no), image_path=f"{path.stem}-p{page_no}.png",
                              width=int(rect.width), height=int(rect.height)))

    toc = [TocEntry(title=t[1], level=t[0], page_no=t[2])
           for t in (doc.get_toc() or []) if t and t[1]]
    title = (doc.metadata or {}).get("title") or path.stem
    medium = Medium.EPUB if path.suffix.lower() == ".epub" else Medium.PDF
    doc.close()
    return build_ir(pages, toc=toc, anchors=anchors, title=title, medium=medium)


def _runs_from_page(page) -> tuple[list[SpanRun], bool]:
    """One SpanRun per fitz text block (spans merged). OCR fallback for scanned pages
    yields a single page-sized run flagged via the `ocr` bool."""
    data = page.get_text("dict")
    words = sum(len(s.get("text", "").split())
                for b in data.get("blocks", []) for l in b.get("lines", [])
                for s in l.get("spans", []))
    if words < MIN_OCR_WORDS:
        text = _ocr_page(page)
        if text:
            r = page.rect
            return [SpanRun(text=text, size=12.0, bold=False,
                            bbox=[r.x0, r.y0, r.x1, r.y1])], True
    runs: list[SpanRun] = []
    for b in data.get("blocks", []):
        spans = [s for l in b.get("lines", []) for s in l.get("spans", [])]
        text = " ".join(s.get("text", "") for s in spans).strip()
        if not text:
            continue
        size = max((s.get("size", 12.0) for s in spans), default=12.0)
        bold = any(int(s.get("flags", 0)) & _BOLD_BIT for s in spans)
        runs.append(SpanRun(text=text, size=size, bold=bold, bbox=list(b.get("bbox", [0, 0, 0, 0]))))
    return runs, False


def _ocr_page(page) -> str:
    try:
        import io
        import pytesseract
        from PIL import Image
        pix = page.get_pixmap(dpi=150)
        return pytesseract.image_to_string(Image.open(io.BytesIO(pix.tobytes("png")))).strip()
    except Exception as e:
        log.warning("OCR failed p%s: %s", getattr(page, "number", "?"), e)
        return ""
