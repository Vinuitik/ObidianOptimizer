"""v2 cutover tests (INGESTION_V2_FLOWS §2) — the jobs.py seam.

Two layers, all LLM/embedder/publish calls stubbed:
  - synthesize v2 assembly (`write_unit_body`, `_span_to_segs`, `build_inplace_body_v2`) — pure
  - `jobs._synthesize_and_publish_v2` — the flagged branch: bundle → IR → segment → draft each
    Unit → assemble+publish, one note per Unit.
"""
from ingest import synthesize


# ── span → v1-segs shim ─────────────────────────────────────────────────────

def test_span_to_segs_by_kind():
    assert synthesize._span_to_segs({"kind": "char", "start_char": 0, "end_char": 5}) == []
    assert synthesize._span_to_segs({"kind": "page", "pages": [2, 4]}) == \
        [{"loc": {"page": 2}}, {"loc": {"page": 4}}]
    time = synthesize._span_to_segs({"kind": "time", "start_ms": 2000, "end_ms": 5000})
    assert time == [{"loc": {"t_start": 2.0, "t_end": 5.0}}]


def test_write_unit_body_uses_write_pass(monkeypatch):
    seen = {}
    monkeypatch.setattr(synthesize, "_complete",
                        lambda p, s: seen.update(prompt=p, system=s) or "## H\n\nbody")
    out = synthesize.write_unit_body("Cells", "Cells are units of life.")
    assert out == "## H\n\nbody"
    assert "Cells" in seen["prompt"] and seen["system"] == synthesize.WRITE_SYSTEM


def test_build_inplace_body_v2_assembles_sections_and_media():
    source = {"type": "video", "ref": "https://youtu.be/x", "title": "Lec"}
    drafted = [
        {"title": "Part One", "body": "First body.",
         "span": {"kind": "time", "start_ms": 0, "end_ms": 60000}},
        {"title": "Part Two", "body": "Second body.",
         "span": {"kind": "time", "start_ms": 60000, "end_ms": 120000}},
    ]
    media = [{"path": "lec-65.jpg", "loc": {"t": 65.0}, "cue_text": "diagram"}]
    block = synthesize.build_inplace_body_v2(source, drafted, media)
    assert not block.startswith("---")               # parent note owns frontmatter
    assert "## Part One" in block and "## Part Two" in block
    assert "![[lec-65.jpg]]" in block                # t=65 ∈ Part Two [60,120]s
    assert block.count("## Source") == 1             # one footer over all spans


# ── jobs.py v2 branch ───────────────────────────────────────────────────────

def _text_bundle():
    return {
        "source": {"type": "text", "ref": "", "title": "T", "chapters": []},
        "segments": [
            {"loc": {"heading": "Alpha"}, "text": "alpha body " * 30},   # 60 words
            {"loc": {"heading": "Beta"}, "text": "beta body " * 30},
        ],
        "media": [],
    }


def test_v2_publish_branch_one_note_per_unit(monkeypatch):
    from ingest import jobs, publish
    monkeypatch.setattr(synthesize, "_complete", lambda p, s: "Drafted body text.")
    # Small Units (< 600-word ceiling) never trigger Stage B, so the embedder is never
    # called; patch it if importable, else jobs falls back to the deterministic split.
    try:
        import model_runtime
        monkeypatch.setattr(model_runtime, "embed_texts",
                            lambda texts, kind="document": [[1.0, 0.0] for _ in texts],
                            raising=False)
    except Exception:
        pass
    created = []
    monkeypatch.setattr(publish, "find_home", lambda t: "Study")
    monkeypatch.setattr(publish, "ensure_folder", lambda f: None)
    monkeypatch.setattr(publish, "validate_note", lambda md, names: [])
    monkeypatch.setattr(publish, "stamp_inbox", lambda md, s, f: md)
    monkeypatch.setattr(publish, "create_note",
                        lambda folder, title, md: created.append((folder, title, md))
                        or f"/vault/{folder}/{title}.md")

    job = {}
    jobs._synthesize_and_publish_v2(job, _text_bundle())

    assert len(created) == 2                          # boundaries from segment.py (2 headings)
    by_title = {t: md for _, t, md in created}
    assert set(by_title) == {"Alpha", "Beta"}
    assert len(job["notes_created"]) == 2
    assert "retention" in job and "needs_review" in job   # v2 records the plan + gate
    # SEQUENTIAL links: Beta (2nd) points back to Alpha; Alpha (1st) is the start.
    assert "[[Alpha]]" in by_title["Beta"]
    assert "start of source" in by_title["Alpha"]
    assert "[[Beta]]" in by_title["Alpha"]            # forward link for walking


def test_v2_flag_default_off_selects_v1(monkeypatch):
    from ingest import pipeline_v2
    monkeypatch.delenv("INGEST_V2", raising=False)
    assert not pipeline_v2.v2_enabled()               # jobs._run picks the v1 path


# ── chronological (SEQUENTIAL) linking helper ────────────────────────────────

def test_chronology_block_first_middle_last():
    first = synthesize.chronology_block(None, "Two")
    assert "start of source" in first and "[[Two]]" in first
    mid = synthesize.chronology_block("One", "Three")
    assert "[[One]]" in mid and "[[Three]]" in mid
    last = synthesize.chronology_block("Two", None)
    assert "[[Two]]" in last and "Next:" not in last
    assert synthesize.chronology_block(None, None) == ""   # lone note → no sequence


def test_pipeline_preserves_contiguous_source_order():
    # two headings → two Units; order_index must stay 0,1 (never later-before-earlier)
    from ingest import pipeline_v2
    from ingest.extract_ir import from_text
    # paras > the 60-word merge floor so the two headings stay two Units
    md = "# First\n\n" + ("alpha " * 80) + "\n\n# Second\n\n" + ("beta " * 80)
    ir = from_text(md, "Src")
    res = pipeline_v2.run(ir)
    assert [n.unit.order_index for n in res.notes] == [0, 1]
    assert res.notes[0].title == "First" and res.notes[1].title == "Second"
