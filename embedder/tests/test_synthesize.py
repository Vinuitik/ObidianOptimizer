"""Synthesis stack tests — windowing, outline schema/retry, assembly,
validation, splitter. All LLM calls stubbed (_complete)."""
import json

import pytest

from ingest import bundle as bundle_util
from ingest import publish, split_note, synthesize


def make_bundle(n_segs=3, source_type="video", media=None):
    return {
        "source": {"type": source_type, "ref": "https://youtu.be/x",
                   "title": "Test Lecture", "duration_s": 600, "chapters": []},
        "segments": [{"loc": {"t_start": i * 60.0, "t_end": i * 60 + 50.0},
                      "text": f"segment {i} content " * 10} for i in range(n_segs)],
        "media": media or [],
    }


# ── bundle windowing ─────────────────────────────────────────────────────

def test_windows_respect_segment_boundaries(monkeypatch):
    monkeypatch.setattr(bundle_util, "WINDOW_TOKENS", 100)  # 400 chars
    segs = bundle_util.number_segments(make_bundle(5))
    wins = bundle_util.windows(segs)
    assert len(wins) > 1
    assert sum(len(w) for w in wins) == 5                  # nothing lost
    assert [s["id"] for w in wins for s in w] == [0, 1, 2, 3, 4]  # order kept


def test_render_segments_tags_locations():
    segs = bundle_util.number_segments(make_bundle(1))
    out = bundle_util.render_segments(segs)
    assert out.startswith("[0 @ 0:00-0:50]")
    pdf_seg = [{"id": 7, "loc": {"page": 3}, "text": "x"}]
    assert bundle_util.render_segments(pdf_seg).startswith("[7 @ p.3]")


# ── outline pass ─────────────────────────────────────────────────────────

def test_outline_parses_valid_plan(monkeypatch):
    plan = {"notes": [{"title": "A", "segment_ids": [0, 1], "tags": ["t"],
                       "summary_hint": "h"},
                      {"title": "B", "segment_ids": [2]}]}
    monkeypatch.setattr(synthesize, "_complete", lambda p, s: json.dumps(plan))
    notes = synthesize.outline(make_bundle(3))
    assert [n["title"] for n in notes] == ["A", "B"]
    assert notes[1]["tags"] == []                          # defaulted


def test_outline_retries_then_succeeds(monkeypatch):
    replies = iter(["not json at all",
                    json.dumps({"notes": [{"title": "A", "segment_ids": [0]}]})])
    monkeypatch.setattr(synthesize, "_complete", lambda p, s: next(replies))
    notes = synthesize.outline(make_bundle(1))
    assert notes[0]["title"] == "A"


def test_outline_fails_after_retry_budget(monkeypatch):
    monkeypatch.setattr(synthesize, "_complete", lambda p, s: "garbage")
    with pytest.raises(synthesize.SynthesisError, match="outline failed"):
        synthesize.outline(make_bundle(1))


def test_outline_drops_invalid_segment_ids(monkeypatch):
    plan = {"notes": [{"title": "A", "segment_ids": [0, 99]}]}
    monkeypatch.setattr(synthesize, "_complete", lambda p, s: json.dumps(plan))
    notes = synthesize.outline(make_bundle(1))
    assert notes[0]["segment_ids"] == [0]


# ── write + deterministic assembly ───────────────────────────────────────

def test_assembled_note_shape(monkeypatch):
    monkeypatch.setattr(synthesize, "_complete",
                        lambda p, s: "## Key idea\n\nBody text here.")
    bundle = make_bundle(2, media=[
        {"path": "lec-60.jpg", "loc": {"t": 65.0}, "trigger": "cue",
         "cue_text": "look at this diagram"}])
    plan = {"title": "Key Concept", "segment_ids": [0, 1], "tags": ["ml"],
            "summary_hint": ""}
    segs = bundle_util.number_segments(bundle)
    note = synthesize.write_note(bundle, plan, segs)

    assert note.startswith("---\n")
    assert "source: https://youtu.be/x" in note
    assert "# Key Concept" in note
    assert "Body text here." in note
    assert "![[lec-60.jpg]]" in note          # media interleaved by loc, t=65 ∈ seg 1
    assert "*look at this diagram*" in note
    assert "?t=0s" in note                    # timestamp backlink
    assert note.rstrip().endswith("#review")


def test_media_outside_segments_not_embedded(monkeypatch):
    monkeypatch.setattr(synthesize, "_complete", lambda p, s: "body")
    bundle = make_bundle(1, media=[{"path": "far.jpg", "loc": {"t": 599.0}}])
    plan = {"title": "T", "segment_ids": [0], "tags": []}
    note = synthesize.write_note(bundle, plan, bundle_util.number_segments(bundle))
    assert "far.jpg" not in note


# ── validation ───────────────────────────────────────────────────────────

def test_validate_note_catches_problems():
    bad = "no frontmatter " * 20 + "![[ghost.jpg]]"
    problems = publish.validate_note(bad, set())
    assert any("frontmatter" in p for p in problems)
    assert any("ghost.jpg" in p for p in problems)
    good = "---\nsource: x\n---\n" + ("content " * 40) + "![[ok.jpg]]\n"
    assert publish.validate_note(good, {"ok.jpg"}) == []


# ── splitter ─────────────────────────────────────────────────────────────

def test_split_refuses_small_notes():
    with pytest.raises(ValueError, match="noise"):
        split_note.split("a/b.md", "---\n---\n# small\nshort")


def test_split_creates_children_and_hub(monkeypatch):
    sections = "".join(
        f"## Topic {i}\n" + (f"content {i} " * 220) + "\n\n" for i in range(3))
    content = "---\nsr-due: 2026-01-01\n---\n# Big Note\n\n" + sections

    plan = {"notes": [{"title": "Topic One", "segment_ids": [0, 1],
                       "summary_hint": "first"},
                      {"title": "Topic Two", "segment_ids": [2, 3],
                       "summary_hint": "second"}]}
    calls = {"complete": 0}

    def fake_complete(prompt, system):
        calls["complete"] += 1
        return json.dumps(plan) if system == synthesize.OUTLINE_SYSTEM else "body text"

    created, updated = [], {}
    monkeypatch.setattr(synthesize, "_complete", fake_complete)
    monkeypatch.setattr(publish, "create_note",
                        lambda folder, title, content: created.append(
                            (folder, title)) or f"/vault/{folder}/{title}.md")
    monkeypatch.setattr(publish, "update_note",
                        lambda path, content: updated.update({path: content}))

    res = split_note.split("study/Big Note.md", content)

    assert len(res["children"]) == 2
    assert [t for _, t in created] == ["Topic One", "Topic Two"]
    assert all(f == "study" for f, _ in created)
    hub = updated["study/Big Note.md"]
    assert "[[Topic One]]" in hub and "[[Topic Two]]" in hub
    assert hub.startswith("---\nsr-due: 2026-01-01\n---\n")  # frontmatter preserved
