"""Mini-course generation tests — bundle building, course-level outline schema/retry,
lesson expansion. All LLM calls stubbed (`minicourse._complete`), same convention as
test_synthesize.py."""
import json

import pytest

import mcp_server
from ingest import bundle as bundle_util
from ingest import synthesize
from tracks import minicourse


NOTE_A = "---\ntags: []\n---\n# Note A\n\nIntro to A.\n\n## Section One\n\nA content one.\n\n## Section Two\n\nA content two.\n"
NOTE_B = "---\ntags: []\n---\n# Note B\n\n## Only Section\n\nB content.\n"


def _write_note(tmp_path, rel_path, content):
    p = tmp_path / rel_path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")


# ── build_course_bundle ──────────────────────────────────────────────────

def test_build_course_bundle_spans_multiple_notes(tmp_path, monkeypatch):
    monkeypatch.setattr(mcp_server, "VAULT_DIR", tmp_path)
    _write_note(tmp_path, "a.md", NOTE_A)
    _write_note(tmp_path, "b.md", NOTE_B)

    items = [{"title": "Note A", "notePath": "a.md", "status": "done"},
             {"title": "Note B", "notePath": "b.md", "status": "done"}]
    bundle = minicourse.build_course_bundle("track-1", "My Track", items)

    assert bundle["source"] == {"type": "track", "ref": "track-1", "title": "My Track",
                                "duration_s": 0, "chapters": []}
    assert bundle["media"] == []
    texts = " ".join(s["text"] for s in bundle["segments"])
    assert "A content one" in texts and "A content two" in texts
    assert "B content" in texts
    headings = [s["loc"]["heading"] for s in bundle["segments"]]
    assert any(h.startswith("Note A") for h in headings)
    assert any(h.startswith("Note B") for h in headings)


def test_build_course_bundle_skips_items_without_note_path(tmp_path, monkeypatch):
    monkeypatch.setattr(mcp_server, "VAULT_DIR", tmp_path)
    _write_note(tmp_path, "a.md", NOTE_A)

    items = [{"title": "Note A", "notePath": "a.md", "status": "done"},
             {"title": "Pending", "notePath": None, "status": "pending"}]
    bundle = minicourse.build_course_bundle("track-1", "My Track", items)

    texts = " ".join(s["text"] for s in bundle["segments"])
    assert "A content one" in texts
    assert "Pending" not in texts


# ── outline_course ───────────────────────────────────────────────────────

def make_course_bundle():
    return {
        "source": {"type": "track", "ref": "track-1", "title": "My Track",
                   "duration_s": 0, "chapters": []},
        "segments": [{"loc": {"heading": f"Note A — Section {i}"},
                      "text": f"content {i} " * 10} for i in range(3)],
        "media": [],
    }


def test_outline_course_parses_valid_plan(monkeypatch):
    plan = {"course_title": "Intro Course",
            "lessons": [{"title": "Lesson 1", "objective": "learn X",
                        "segment_ids": [0, 1], "summary_hint": "h"},
                       {"title": "Lesson 2", "objective": "learn Y",
                        "segment_ids": [2]}]}
    monkeypatch.setattr(minicourse, "_complete", lambda p, s: json.dumps(plan))
    result = minicourse.outline_course(make_course_bundle())
    assert result["course_title"] == "Intro Course"
    assert [l["title"] for l in result["lessons"]] == ["Lesson 1", "Lesson 2"]
    assert result["lessons"][1]["summary_hint"] == ""  # defaulted


def test_outline_course_retries_then_succeeds(monkeypatch):
    valid = json.dumps({"course_title": "C",
                        "lessons": [{"title": "L", "objective": "o", "segment_ids": [0]}]})
    replies = iter(["not json at all", valid])
    monkeypatch.setattr(minicourse, "_complete", lambda p, s: next(replies))
    result = minicourse.outline_course(make_course_bundle())
    assert result["course_title"] == "C"


def test_outline_course_fails_after_retry_budget(monkeypatch):
    monkeypatch.setattr(minicourse, "_complete", lambda p, s: "garbage")
    with pytest.raises(synthesize.SynthesisError, match="outline_course failed"):
        minicourse.outline_course(make_course_bundle())


def test_outline_course_bad_segment_reference_triggers_retry(monkeypatch):
    # segment_ids references id 99, which does not exist in the bundle's 3 segments —
    # after filtering, the lesson keeps no valid ids, so the (single-lesson) attempt is
    # invalid and must retry, mirroring synthesize.outline()'s own validation.
    bad = json.dumps({"course_title": "C",
                      "lessons": [{"title": "L", "objective": "o", "segment_ids": [99]}]})
    good = json.dumps({"course_title": "C",
                       "lessons": [{"title": "L", "objective": "o", "segment_ids": [0]}]})
    replies = iter([bad, good])
    monkeypatch.setattr(minicourse, "_complete", lambda p, s: next(replies))
    result = minicourse.outline_course(make_course_bundle())
    assert result["lessons"][0]["segment_ids"] == [0]


# ── expand_lesson ─────────────────────────────────────────────────────────

def test_expand_lesson_returns_body(monkeypatch):
    monkeypatch.setattr(minicourse, "_complete",
                        lambda p, s: "## Key idea\n\nLesson body text.")
    bundle = make_course_bundle()
    segs = bundle_util.number_segments(bundle)
    by_id = {s["id"]: s for s in segs}
    lesson = {"title": "Lesson 1", "objective": "learn X", "segment_ids": [0, 1]}

    body = minicourse.expand_lesson(bundle, lesson, by_id)

    assert "Lesson body text." in body
