"""Ingest v2 tests — SourceIR schema, v1 bridge, deterministic segmentation.

No heavy deps: ir.py/segment.py are pure. Covers the invariants that matter most
(INGESTION_V2_FLOWS §3/§4): JSON round-trip, CharSpan correctness through the v1
bridge, the 600-word ceiling, tiny-section merge, and locator-span typing.
"""
from ingest import ir, segment
from ingest.ir import Block, BlockType, CharSpan, Flag, PageBox, Severity, TimeSpan


# ── ir.py round-trip ──────────────────────────────────────────────────────

def test_sourceir_json_round_trip():
    blocks = [
        Block(order_index=0, type=BlockType.HEADING, level=2, text="H",
              locator=CharSpan(0, 1)),
        Block(order_index=1, type=BlockType.PARAGRAPH, text="body",
              locator=CharSpan(3, 7),
              flags=[Flag(code="NO_STRUCTURE", severity=Severity.SOFT)]),
    ]
    s = ir.SourceIR(medium=ir.Medium.TEXT, title="T", blocks=blocks,
                    normalized_text="H\n\nbody")
    d = s.to_dict()
    assert ir.SourceIR.from_dict(d).to_dict() == d


def test_has_structure_and_hard_flags():
    plain = ir.SourceIR(medium=ir.Medium.TEXT, title="T", blocks=[
        Block(order_index=0, type=BlockType.PARAGRAPH, text="x", locator=CharSpan(0, 1))])
    assert not plain.has_structure()

    flagged = ir.SourceIR(medium=ir.Medium.PDF, title="T", blocks=[
        Block(order_index=0, type=BlockType.PARAGRAPH, text="x", locator=PageBox(1, []),
              flags=[Flag(code="OCR", severity=Severity.HARD)])])
    assert len(flagged.hard_flags()) == 1


# ── v1 → IR bridge ─────────────────────────────────────────────────────────

def test_bridge_text_charspans_index_into_normalized():
    bundle = {
        "source": {"type": "text", "title": "Bio",
                   "chapters": [{"title": "Cells"}]},
        "segments": [
            {"loc": {"heading": "Cells"}, "text": "Cells are the unit of life."},
            {"loc": {"heading": ""}, "text": "More prose without a heading."},
        ],
        "media": [],
    }
    s = ir.ir_from_v1_bundle(bundle)
    assert s.medium == ir.Medium.TEXT
    assert s.has_structure()  # heading block emitted
    for b in s.blocks:
        assert s.normalized_text[b.locator.start_char:b.locator.end_char] == b.text


def test_bridge_av_seconds_to_ms():
    bundle = {
        "source": {"type": "youtube", "title": "Talk"},
        "segments": [{"loc": {"t_start": 1.5, "t_end": 3.0}, "text": "hello"}],
        "media": [],
    }
    s = ir.ir_from_v1_bundle(bundle)
    assert s.medium == ir.Medium.VIDEO
    loc = s.blocks[0].locator
    assert isinstance(loc, TimeSpan) and loc.start_ms == 1500 and loc.end_ms == 3000


# ── segmentation invariants ────────────────────────────────────────────────

def _para(i, n_words):
    text = " ".join(["w"] * n_words)
    return Block(order_index=i, type=BlockType.PARAGRAPH, text=text,
                 locator=CharSpan(i * 1000, i * 1000 + len(text)))


def test_ceiling_is_enforced_without_embedder():
    # one giant headingless blob → token windows + Stage B split, all ≤ ceiling
    blocks = [_para(i, 300) for i in range(6)]  # 1800 words total
    s = ir.SourceIR(medium=ir.Medium.TEXT, title="T", blocks=blocks)
    units = segment.segment(s)  # embed_fn=None → deterministic fallback
    assert units
    assert all(u.word_count <= segment.UNIT_WORDS_CEIL for u in units)


def test_tiny_sections_merge():
    blocks = [_para(0, 5), _para(1, 5), _para(2, 5)]
    s = ir.SourceIR(medium=ir.Medium.TEXT, title="T", blocks=blocks)
    units = segment.segment(s)
    assert len(units) == 1
    assert units[0].word_count == 15


def test_embed_fn_drives_topic_shift_split():
    # One heading keeps Stage A from pre-splitting (headingless text token-windows at
    # 600); the whole 800-word group then goes to Stage B, where the fake embedder's
    # orthogonal vectors make the A/B seam the sharpest cosine drop.
    heading = Block(order_index=0, type=BlockType.HEADING, level=2, text="topic",
                    locator=CharSpan(0, 5))
    a = [_para(i + 1, 200) for i in range(2)]  # topic A ("w")
    b = [_para(i + 3, 200) for i in range(2)]  # topic B ("z")
    for blk in b:
        blk.text = blk.text.replace("w", "z")
    s = ir.SourceIR(medium=ir.Medium.TEXT, title="T", blocks=[heading] + a + b)

    def fake_embed(texts):
        return [[0.0, 1.0] if "z" in t else [1.0, 0.0] for t in texts]

    units = segment.segment(s, embed_fn=fake_embed)
    assert len(units) == 2
    assert "z" not in units[0].raw_text and "w" not in units[1].raw_text


def test_locator_span_typing():
    txt = ir.SourceIR(medium=ir.Medium.TEXT, title="T",
                      blocks=[_para(0, 10), _para(1, 10)])
    assert segment.segment(txt)[0].locator_span["kind"] == "char"

    pdf = ir.SourceIR(medium=ir.Medium.PDF, title="T", blocks=[
        Block(order_index=0, type=BlockType.PARAGRAPH, text="a " * 10, locator=PageBox(1, [])),
        Block(order_index=1, type=BlockType.PARAGRAPH, text="b " * 10, locator=PageBox(2, [])),
    ])
    span = segment.segment(pdf)[0].locator_span
    assert span["kind"] == "page" and span["pages"] == [1, 2]
