"""Ingest v2 flagging tests (INGESTION_V2_FLOWS §3c/§8d) — IR-computable checks only."""
from ingest import ir
from ingest.flagging import flag_source
from ingest.ir import Block, BlockType, CharSpan, SourceIR, TimeSpan


def _codes(s):
    return {f.code for b in s.blocks for f in b.flags}


def test_no_structure_flag():
    s = SourceIR(medium=ir.Medium.TEXT, title="T", blocks=[
        Block(order_index=0, type=BlockType.PARAGRAPH, text="a b c", locator=CharSpan(0, 5))])
    flag_source(s)
    assert "NO_STRUCTURE" in _codes(s)


def test_structure_present_no_flag():
    s = SourceIR(medium=ir.Medium.TEXT, title="T", blocks=[
        Block(order_index=0, type=BlockType.HEADING, level=1, text="H", locator=CharSpan(0, 1)),
        Block(order_index=1, type=BlockType.PARAGRAPH, text="a b c", locator=CharSpan(3, 8))])
    flag_source(s)
    assert "NO_STRUCTURE" not in _codes(s)


def test_oversize_block_flag():
    small = Block(order_index=0, type=BlockType.PARAGRAPH, text="a b", locator=CharSpan(0, 3))
    big = Block(order_index=1, type=BlockType.PARAGRAPH, text="w " * 100, locator=CharSpan(5, 5))
    # give a heading so NO_STRUCTURE doesn't also fire and muddy the assertion
    head = Block(order_index=2, type=BlockType.HEADING, level=1, text="H", locator=CharSpan(9, 10))
    s = SourceIR(medium=ir.Medium.TEXT, title="T", blocks=[head, small, big])
    flag_source(s)
    assert any(f.code == "OVERSIZE_BLOCK" for f in big.flags)
    assert not any(f.code == "OVERSIZE_BLOCK" for f in small.flags)


def test_long_unbroken_av_flag():
    s = SourceIR(medium=ir.Medium.VIDEO, title="Talk", blocks=[
        Block(order_index=0, type=BlockType.SPEECH, text="w " * 600, locator=TimeSpan(0, 60000))])
    flag_source(s)
    assert "LONG_UNBROKEN" in _codes(s)


def test_idempotent():
    s = SourceIR(medium=ir.Medium.TEXT, title="T", blocks=[
        Block(order_index=0, type=BlockType.PARAGRAPH, text="a b c", locator=CharSpan(0, 5))])
    flag_source(s)
    flag_source(s)
    assert len(s.blocks[0].flags) == 1  # NO_STRUCTURE not duplicated
