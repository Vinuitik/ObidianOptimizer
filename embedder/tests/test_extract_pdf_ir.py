"""Native PDF IR extraction tests (INGESTION_V2_FLOWS §3a).

Exercises the **pure** core `build_ir(PageParse[]) → SourceIR` (no fitz, no ML): block
positioning (PageBox bbox), font-ratio heading levels, and the layout HARD flags
(READING_ORDER / TABLE / OCR / LAYOUT) that gate auto-commit. The `from_pdf` I/O wrapper
needs PyMuPDF and isn't covered here.
"""
from ingest import segment
from ingest.extract_pdf_ir import PageParse, SpanRun, build_ir
from ingest.ir import BlockType, Medium, PageBox, Severity, TocEntry


def _run(text, size=12.0, bold=False, x0=50, y0=100, x1=500, y1=120):
    return SpanRun(text=text, size=size, bold=bold, bbox=[x0, y0, x1, y1])


def _body(n_words, **kw):
    return _run(" ".join(["word"] * n_words), **kw)


# ── block positioning + heading levels ──────────────────────────────────────

def test_font_ratio_headings_and_pagebox():
    pages = [PageParse(page_no=1, runs=[
        _run("Chapter One", size=24.0, y0=50, y1=80),        # big → H1
        _body(40, size=12.0, y0=100),                        # body
        _run("A Subsection", size=16.0, y0=300, y1=320),     # medium → H2
        _body(40, size=12.0, y0=340),
    ])]
    ir = build_ir(pages, medium=Medium.PDF, title="Book")
    headings = [b for b in ir.blocks if b.type == BlockType.HEADING]
    assert [h.text for h in headings] == ["Chapter One", "A Subsection"]
    assert headings[0].level == 1 and headings[1].level == 2
    # every block carries a real PageBox bbox
    assert all(isinstance(b.locator, PageBox) for b in ir.blocks)
    assert ir.blocks[0].locator.bbox == [50, 50, 500, 80]
    assert ir.has_structure()


def test_clean_single_column_toc_pdf_has_no_hard_flags():
    pages = [
        PageParse(page_no=1, runs=[_run("Intro", size=20.0), _body(80)]),
        PageParse(page_no=2, runs=[_run("Methods", size=20.0), _body(80)]),
    ]
    toc = [TocEntry(title="Intro", level=1, page_no=1),
           TocEntry(title="Methods", level=1, page_no=2)]
    ir = build_ir(pages, toc=toc, medium=Medium.PDF)
    assert not ir.hard_flags()          # commits hands-off


# ── layout HARD flags ───────────────────────────────────────────────────────

def test_reading_order_flag_on_column_scramble():
    # blocks interleave two columns (x0 50 / 320) in the wrong order → HARD
    pages = [PageParse(page_no=1, runs=[
        _body(30, x0=50), _body(30, x0=320), _body(30, x0=50), _body(30, x0=320),
    ])]
    ir = build_ir(pages)
    assert any(f.code == "READING_ORDER" and f.severity == Severity.HARD
               for f in ir.hard_flags())


def test_two_column_read_in_order_is_clean():
    # column-by-column (all of col0 then col1) is the correct order → no READING_ORDER
    pages = [PageParse(page_no=1, runs=[
        _body(30, x0=50, y0=100), _body(30, x0=50, y0=200),
        _body(30, x0=320, y0=100), _body(30, x0=320, y0=200),
    ])]
    ir = build_ir(pages)
    assert not any(f.code == "READING_ORDER" for f in ir.hard_flags())


def test_table_flag_on_block_over_table_rect():
    pages = [PageParse(page_no=1, runs=[_body(20, x0=50, y0=400, x1=500, y1=460)],
                       table_rects=[[40, 390, 520, 480]])]
    ir = build_ir(pages)
    assert any(f.code == "TABLE" and f.severity == Severity.HARD for f in ir.hard_flags())


def test_ocr_flag_on_scanned_page():
    pages = [PageParse(page_no=1, runs=[_body(30)], ocr=True)]
    ir = build_ir(pages)
    codes = {f.code for f in ir.hard_flags()}
    assert "OCR" in codes


def test_layout_flag_on_y_backtrack():
    # same column, second block starts far above the first → interleaved callout
    pages = [PageParse(page_no=1, runs=[
        _body(20, x0=50, y0=300, y1=340), _body(20, x0=50, y0=100, y1=140),
    ])]
    ir = build_ir(pages)
    assert any(f.code == "LAYOUT" and f.severity == Severity.HARD for f in ir.hard_flags())


# ── integration: build_ir → segment ────────────────────────────────────────

def test_build_ir_feeds_segment_page_spans():
    pages = [
        PageParse(page_no=1, runs=[_run("Ch1", size=20.0), _body(120)]),
        PageParse(page_no=2, runs=[_run("Ch2", size=20.0), _body(120)]),
    ]
    ir = build_ir(pages, medium=Medium.PDF)
    units = segment.segment(ir)
    assert units and units[0].locator_span["kind"] == "page"
    # HARD flags absent → both Units are clean; pages tracked for retention
    assert not ir.hard_flags()
    all_pages = sorted({p for u in units for p in u.locator_span["pages"]})
    assert all_pages == [1, 2]
