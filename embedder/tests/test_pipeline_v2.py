"""Ingest v2 orchestrator tests (INGESTION_V2_FLOWS §2).

Covers the chain end to end (extract_ir → segment → draft → retention → locator) with
pure stubs: the injected `draft_fn`/`embed_fn` keep it LLM/GPU-free. Asserts the seams
that matter — deterministic boundaries, injected draft is the only writer, HARD flag →
REVIEWING gate, retention runs over auto-committable Units only, splice mirrors locator.
"""
import os

import pytest

from ingest import pipeline_v2
from ingest.ir import (Block, BlockType, CharSpan, Flag, PageBox, Severity,
                       SourceIR, Medium)
from ingest.pipeline_v2 import Lifecycle


# ── feature flag ────────────────────────────────────────────────────────────

def test_flag_default_off_and_guard_raises(monkeypatch):
    monkeypatch.delenv("INGEST_V2", raising=False)
    assert not pipeline_v2.v2_enabled()
    try:
        pipeline_v2.guard()
        assert False, "guard must raise when INGEST_V2 is off"
    except RuntimeError:
        pass


def test_flag_on_enables(monkeypatch):
    monkeypatch.setenv("INGEST_V2", "on")
    assert pipeline_v2.v2_enabled()
    pipeline_v2.guard()  # no raise


# ── the chain runs pure (no LLM, no GPU) ────────────────────────────────────

def test_run_text_chains_all_stages():
    md = "# Cells\n\nCells are the unit of life.\n\n## Energy\n\nMitochondria make ATP."
    res = pipeline_v2.run_text(md, "Bio")
    assert res.units and len(res.notes) == len(res.units)
    # every note carries a splice descriptor mirroring its Unit's locator kind
    for n in res.notes:
        assert n.splice["kind"] == "text"
        assert n.splice["quote"] is not None
    # default echo drafter → body is the Unit's raw text (LLM-free)
    assert res.notes[0].body == res.units[0].raw_text
    assert not res.needs_review  # clean structured text → all DRAFT


def test_injected_draft_fn_is_the_only_writer():
    md = "# A\n\n" + " ".join(["w"] * 40)
    seen = []

    def draft_fn(unit, title):
        seen.append(title)
        return f"DRAFTED::{title}"

    res = pipeline_v2.run_text(md, "T", draft_fn=draft_fn)
    assert seen and all(n.body.startswith("DRAFTED::") for n in res.notes)
    # title came from the Unit's heading block, not the drafter
    assert res.notes[0].title == "A"


def test_title_fn_names_unstructured_units():
    # Headingless source (the A/V-transcript case: no chapters) → no structural name →
    # title_fn (the LLM stand-in) is asked and its name is used.
    called = []

    def title_fn(unit):
        called.append(unit.order_index)
        return "LLM Picked Name"

    res = pipeline_v2.run_text(" ".join(["w"] * 40), "Src", title_fn=title_fn)
    assert called and res.notes[0].title == "LLM Picked Name"

    # A structural (heading) name always wins — title_fn is never consulted for it.
    called.clear()
    res2 = pipeline_v2.run_text("# Real Heading\n\n" + " ".join(["w"] * 40), "Src", title_fn=title_fn)
    assert res2.notes[0].title == "Real Heading" and called == []

    # No title_fn → the generic "<source> (n)" fallback, never a crash.
    res3 = pipeline_v2.run_text(" ".join(["w"] * 40), "Src")
    assert res3.notes[0].title.startswith("Src (")


def test_title_fn_503_propagates_to_defer():
    # Provider exhaustion during naming must DEFER the job (bubble up), not silently fall back.
    class Cooling(Exception):
        status = 503

    def title_fn(unit):
        raise Cooling("all providers cooling")

    with pytest.raises(Cooling):
        pipeline_v2.run_text(" ".join(["w"] * 40), "Src", title_fn=title_fn)


def test_embed_fn_drives_boundaries_not_draft():
    # one heading, two topics; the fake embedder makes the A/B seam the sharpest drop
    heading = Block(order_index=0, type=BlockType.HEADING, level=2, text="topic",
                    locator=CharSpan(0, 5))

    def para(i, tok):
        text = " ".join([tok] * 200)
        return Block(order_index=i, type=BlockType.PARAGRAPH, text=text,
                     locator=CharSpan(i * 2000, i * 2000 + len(text)))

    blocks = [heading, para(1, "w"), para(2, "w"), para(3, "z"), para(4, "z")]
    ir = SourceIR(medium=Medium.TEXT, title="T", blocks=blocks,
                  normalized_text="topic")

    def embed_fn(texts):
        return [[0.0, 1.0] if "z" in t else [1.0, 0.0] for t in texts]

    res = pipeline_v2.run(ir, embed_fn=embed_fn)
    assert len(res.notes) == 2  # embedder split, not the drafter


# ── HARD flag gates auto-commit (§3c) ───────────────────────────────────────

def test_hard_flag_unit_goes_reviewing_and_gates():
    blocks = [
        Block(order_index=0, type=BlockType.PARAGRAPH, text="a " * 20, locator=PageBox(1, []),
              flags=[Flag(code="OCR", severity=Severity.HARD)]),
    ]
    ir = SourceIR(medium=Medium.PDF, title="Scan", blocks=blocks)
    res = pipeline_v2.run(ir)
    assert res.notes[0].lifecycle == Lifecycle.REVIEWING
    assert res.notes[0].needs_review and res.needs_review


def test_soft_flag_auto_flows():
    # NO_STRUCTURE is SOFT (flagging.flag_source adds it) → still DRAFT
    res = pipeline_v2.run_text(" ".join(["w"] * 40), "Plain")
    assert any(f.code == "NO_STRUCTURE" for n in res.notes for f in n.flags)
    assert all(n.lifecycle == Lifecycle.DRAFT for n in res.notes)
    assert not res.needs_review


# ── retention runs over auto-committable Units only ─────────────────────────

def test_retention_excludes_reviewing_units():
    # two pages: p1's Unit is HARD-flagged (REVIEWING), p2's is clean (DRAFT).
    # paras > the 60-word merge floor so the two pages stay distinct Units
    blocks = [
        Block(order_index=0, type=BlockType.HEADING, level=2, text="Bad", locator=PageBox(1, [])),
        Block(order_index=1, type=BlockType.PARAGRAPH, text="a " * 80, locator=PageBox(1, []),
              flags=[Flag(code="OCR", severity=Severity.HARD)]),
        Block(order_index=2, type=BlockType.HEADING, level=2, text="Good", locator=PageBox(2, [])),
        Block(order_index=3, type=BlockType.PARAGRAPH, text="b " * 80, locator=PageBox(2, [])),
    ]
    from ingest.ir import Anchor
    ir = SourceIR(medium=Medium.PDF, title="Book",
                  blocks=blocks, anchors=[Anchor(key="1", image_path="p1.png"),
                                          Anchor(key="2", image_path="p2.png")])
    res = pipeline_v2.run(ir, source_blob_path="book.pdf")
    # p2 kept (its Unit auto-commits); p1 dropped (Unit still REVIEWING); blob dropped
    assert "p2.png" in res.retention.keep_paths
    assert "p1.png" in res.retention.drop_paths
    assert "book.pdf" in res.retention.drop_paths


def test_result_as_dict_round_trips_shape():
    res = pipeline_v2.run_text("# H\n\nbody here", "T")
    d = res.as_dict()
    assert d["medium"] == "text" and "notes" in d and "retention" in d
    assert d["notes"][0]["lifecycle"] in (Lifecycle.DRAFT, Lifecycle.REVIEWING)
