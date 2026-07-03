"""Ingest v2 retention tests (INGESTION_V2_FLOWS §6/§8c/§9h).

Pure planning — `compute_retention` never touches the filesystem. Covers the
delete-by-default partition: referenced page shots + owned media + KeptFragments
survive; unreferenced shots, unowned media, and the raw blob drop; A/V keeps the
transcript.
"""
from ingest import ir, segment
from ingest.ir import Anchor, Block, BlockType, PageBox, SourceIR, TimeSpan
from ingest.retention import KeptFragment, compute_retention


def _pdf_ir():
    blocks = [
        Block(order_index=0, type=BlockType.PARAGRAPH, text="a " * 10, locator=PageBox(1, [])),
        Block(order_index=1, type=BlockType.FIGURE, text="", media_ref="fig_p1.png",
              locator=PageBox(1, [])),
        Block(order_index=2, type=BlockType.PARAGRAPH, text="b " * 10, locator=PageBox(3, [])),
    ]
    anchors = [Anchor(key=str(p), image_path=f"p{p}.png") for p in (1, 2, 3)]
    return SourceIR(medium=ir.Medium.PDF, title="Book", blocks=blocks, anchors=anchors)


def test_pdf_keeps_referenced_pages_and_owned_media():
    s = _pdf_ir()
    units = segment.segment(s)
    plan = compute_retention(
        s, units,
        kept_fragments=[KeptFragment(kind="figure", media_path="crop.png")],
        source_blob_path="book.pdf")
    assert plan.referenced_pages == {1, 3}
    assert {"p1.png", "p3.png", "fig_p1.png", "crop.png"} <= plan.keep_paths
    assert "p2.png" in plan.drop_paths      # unreferenced page shot
    assert "book.pdf" in plan.drop_paths    # raw blob always dropped
    assert not plan.keep_transcript


def test_keep_wins_over_drop():
    s = _pdf_ir()
    units = segment.segment(s)
    plan = compute_retention(s, units, source_blob_path="book.pdf")
    assert plan.keep_paths.isdisjoint(plan.drop_paths)


def test_av_keeps_transcript_drops_blob():
    s = SourceIR(medium=ir.Medium.AUDIO, title="Pod", blocks=[
        Block(order_index=0, type=BlockType.SPEECH, text="hi " * 5, locator=TimeSpan(0, 1000))])
    units = segment.segment(s)
    plan = compute_retention(s, units, source_blob_path="pod.mp3")
    assert plan.keep_transcript
    assert "pod.mp3" in plan.drop_paths
