"""Native text-native IR extraction tests (INGESTION_V2_FLOWS §3b).

The load-bearing property: every block's CharSpan exactly reproduces its text out of the
frozen normalized string. That exactness is what the consume-layer splice-view relies on.
"""
import pytest

from ingest import extract_ir, segment
from ingest.ir import BlockType, Medium


def _assert_spans_exact(ir):
    for b in ir.blocks:
        assert ir.normalized_text[b.locator.start_char:b.locator.end_char] == b.text


def test_char_spans_are_exact():
    md = "# Title\nPara one here.\n\nPara two, longer, with words.\n\n## Section\nMore text."
    ir = extract_ir.from_text(md, "Doc")
    _assert_spans_exact(ir)
    assert ir.medium == Medium.TEXT
    assert ir.normalized_text == md  # frozen verbatim


def test_headings_get_levels_and_structure():
    ir = extract_ir.from_text("# H1\ntext\n\n### H3\nmore", "Doc")
    heads = [b for b in ir.blocks if b.type == BlockType.HEADING]
    assert [h.level for h in heads] == [1, 3]
    assert ir.has_structure()


def test_multiline_paragraph_is_one_block():
    md = "First line of a para\ncontinues on line two\nand three.\n\nNext para."
    ir = extract_ir.from_text(md, "Doc")
    paras = [b for b in ir.blocks if b.type == BlockType.PARAGRAPH]
    assert len(paras) == 2
    assert "\n" in paras[0].text
    _assert_spans_exact(ir)


def test_no_structure_flag_on_plain_text():
    ir = extract_ir.from_text("just one paragraph, no headings at all here.", "Doc")
    assert "NO_STRUCTURE" in {f.code for b in ir.blocks for f in b.flags}


def test_empty_raises():
    with pytest.raises(RuntimeError):
        extract_ir.from_text("   \n\n  ", "Doc")


def test_flows_into_segment():
    md = "# A\n" + ("word " * 400) + "\n\n## B\n" + ("term " * 400)
    ir = extract_ir.from_text(md, "Doc")
    units = segment.segment(ir)
    assert units
    assert all(u.word_count <= segment.UNIT_WORDS_CEIL for u in units)
    assert all(u.locator_span["kind"] == "char" for u in units)
