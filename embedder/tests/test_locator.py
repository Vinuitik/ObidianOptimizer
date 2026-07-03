"""Splice-resolution tests (INGESTION_V2_FLOWS §7) — Unit.locator_span → SpliceView."""
from ingest import extract_ir, segment
from ingest.ir import Anchor, Block, BlockType, PageBox, SourceIR, TimeSpan
from ingest.locator import resolve_all, resolve_splice


def test_text_splice_returns_quote():
    md = "# H\n" + ("word " * 300)
    ir = extract_ir.from_text(md, "Doc")
    units = segment.segment(ir)
    view = resolve_splice(units[0], ir)
    assert view.kind == "text"
    # the quote is exactly the source region the Unit spans
    assert view.quote == ir.normalized_text[view.start_char:view.end_char]


def test_pdf_splice_maps_referenced_pages_to_images():
    blocks = [
        Block(order_index=0, type=BlockType.PARAGRAPH, text="a " * 10, locator=PageBox(1, [0, 0, 1, 1])),
        Block(order_index=1, type=BlockType.PARAGRAPH, text="b " * 10, locator=PageBox(2, [0, 0, 2, 2])),
    ]
    anchors = [Anchor(key=str(p), image_path=f"p{p}.png") for p in (1, 2, 3)]
    ir = SourceIR(medium=__import__("ingest.ir", fromlist=["Medium"]).Medium.PDF,
                  title="Book", blocks=blocks, anchors=anchors)
    units = segment.segment(ir)
    view = resolve_splice(units[0], ir)
    assert view.kind == "pdf"
    assert view.pages == [1, 2]
    assert view.page_images == ["p1.png", "p2.png"]   # p3 not referenced → not included
    assert view.bbox_start == [0, 0, 1, 1] and view.bbox_end == [0, 0, 2, 2]


def test_av_splice_returns_time_range():
    ir = SourceIR(medium=__import__("ingest.ir", fromlist=["Medium"]).Medium.AUDIO,
                  title="Pod", blocks=[
                      Block(order_index=0, type=BlockType.SPEECH, text="hi " * 5,
                            locator=TimeSpan(1000, 4000))])
    units = segment.segment(ir)
    view = resolve_splice(units[0], ir)
    assert view.kind == "av" and view.start_ms == 1000 and view.end_ms == 4000


def test_resolve_all_is_ordered():
    md = "# A\n" + ("word " * 400) + "\n\n## B\n" + ("term " * 400)
    ir = extract_ir.from_text(md, "Doc")
    units = segment.segment(ir)
    views = resolve_all(list(reversed(units)), ir)   # pass shuffled → must come back ordered
    assert all(v["kind"] == "text" for v in views)
    # first view's span starts at/before the second's (source order preserved)
    assert views[0]["start_char"] <= views[-1]["start_char"]
