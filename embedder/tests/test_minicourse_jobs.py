"""Mini-course job registry tests — the outline/approve/lessons state machine wrapping
tracks/minicourse.py's pure functions. All LLM-touching calls (build_course_bundle,
outline_course, expand_lesson) are mocked; no real HTTP/LLM calls, same convention as
test_minicourse.py."""
import time

import pytest

from ingest.synthesize import SynthesisError
from tracks import minicourse, minicourse_jobs


def _poll(job_id, terminal_or_awaiting=("AWAITING_APPROVAL", "DONE", "FAILED"), timeout=5.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = minicourse_jobs.get(job_id)
        if job["status"] in terminal_or_awaiting:
            return job
        time.sleep(0.02)
    raise AssertionError(f"job {job_id} did not reach {terminal_or_awaiting} in {timeout}s")


def make_bundle():
    return {
        "source": {"type": "track", "ref": "track-1", "title": "My Track",
                   "duration_s": 0, "chapters": []},
        "segments": [{"loc": {"heading": f"Section {i}"}, "text": f"content {i}"}
                     for i in range(3)],
        "media": [],
    }


def make_plan(n_lessons=3):
    return {"course_title": "My Course",
            "lessons": [{"title": f"Lesson {i}", "objective": f"obj {i}",
                        "segment_ids": [i], "summary_hint": ""}
                       for i in range(n_lessons)]}


# ── submit_outline ───────────────────────────────────────────────────────

def test_submit_outline_reaches_awaiting_approval(monkeypatch):
    plan = make_plan()
    monkeypatch.setattr(minicourse, "build_course_bundle", lambda *a: make_bundle())
    monkeypatch.setattr(minicourse, "outline_course", lambda b: plan)

    job = minicourse_jobs.submit_outline("track-1", "My Track",
                                         [{"title": "A", "notePath": "a.md"}])
    job = _poll(job["id"])

    assert job["status"] == "AWAITING_APPROVAL"
    assert job["course_title"] == "My Course"
    assert job["plan"] == plan
    assert job["error"] is None


def test_submit_outline_empty_bundle_fails_without_calling_outline(monkeypatch):
    empty_bundle = {"source": {"type": "track", "ref": "track-1", "title": "My Track",
                               "duration_s": 0, "chapters": []},
                    "segments": [], "media": []}
    monkeypatch.setattr(minicourse, "build_course_bundle", lambda *a: empty_bundle)
    called = []
    monkeypatch.setattr(minicourse, "outline_course",
                        lambda b: called.append(1) or make_plan())

    job = minicourse_jobs.submit_outline("track-1", "My Track",
                                         [{"title": "A", "notePath": None}])
    job = _poll(job["id"])

    assert job["status"] == "FAILED"
    assert job["error"]
    assert called == []


def test_submit_outline_synthesis_error_fails_with_message(monkeypatch):
    monkeypatch.setattr(minicourse, "build_course_bundle", lambda *a: make_bundle())
    def raise_err(b):
        raise SynthesisError("outline_course failed after 3 attempts: bad json")
    monkeypatch.setattr(minicourse, "outline_course", raise_err)

    job = minicourse_jobs.submit_outline("track-1", "My Track",
                                         [{"title": "A", "notePath": "a.md"}])
    job = _poll(job["id"])

    assert job["status"] == "FAILED"
    assert "outline_course failed" in job["error"]


# ── approve ───────────────────────────────────────────────────────────────

def _awaiting_job(monkeypatch, n_lessons=3):
    plan = make_plan(n_lessons)
    monkeypatch.setattr(minicourse, "build_course_bundle", lambda *a: make_bundle())
    monkeypatch.setattr(minicourse, "outline_course", lambda b: plan)
    job = minicourse_jobs.submit_outline("track-1", "My Track",
                                         [{"title": "A", "notePath": "a.md"}])
    return _poll(job["id"])


def test_approve_none_expands_all_lessons(monkeypatch):
    job = _awaiting_job(monkeypatch, 3)
    calls = []
    def fake_expand(bundle, lesson, by_id):
        calls.append(lesson["title"])
        return f"body for {lesson['title']}"
    monkeypatch.setattr(minicourse, "expand_lesson", fake_expand)

    minicourse_jobs.approve(job["id"], None)
    result = _poll(job["id"], terminal_or_awaiting=("DONE", "FAILED"))

    assert result["status"] == "DONE"
    assert len(calls) == 3
    assert len(result["results"]) == 3
    assert result["results"][0] == {"title": "Lesson 0", "body": "body for Lesson 0"}


def test_approve_subset_expands_only_selected_indexes(monkeypatch):
    job = _awaiting_job(monkeypatch, 3)
    calls = []
    monkeypatch.setattr(minicourse, "expand_lesson",
                        lambda bundle, lesson, by_id: calls.append(lesson["title"])
                        or f"body {lesson['title']}")

    minicourse_jobs.approve(job["id"], [0, 2])
    result = _poll(job["id"], terminal_or_awaiting=("DONE", "FAILED"))

    assert result["status"] == "DONE"
    assert calls == ["Lesson 0", "Lesson 2"]
    assert len(result["results"]) == 2


def test_approve_one_bad_lesson_does_not_sink_others(monkeypatch):
    job = _awaiting_job(monkeypatch, 3)
    def fake_expand(bundle, lesson, by_id):
        if lesson["title"] == "Lesson 1":
            raise SynthesisError("boom")
        return f"body {lesson['title']}"
    monkeypatch.setattr(minicourse, "expand_lesson", fake_expand)

    minicourse_jobs.approve(job["id"], None)
    result = _poll(job["id"], terminal_or_awaiting=("DONE", "FAILED"))

    assert result["status"] == "DONE"
    result_titles = [r["title"] for r in result["results"]]
    assert "Lesson 0" in result_titles and "Lesson 2" in result_titles
    assert "Lesson 1" not in result_titles
    assert result["lesson_failures"] == [{"title": "Lesson 1", "error": "boom"}]


def test_approve_while_still_running_returns_error_without_starting_lessons(monkeypatch):
    def slow_outline(b):
        time.sleep(0.3)
        return make_plan()
    monkeypatch.setattr(minicourse, "build_course_bundle", lambda *a: make_bundle())
    monkeypatch.setattr(minicourse, "outline_course", slow_outline)
    calls = []
    monkeypatch.setattr(minicourse, "expand_lesson",
                        lambda bundle, lesson, by_id: calls.append(1) or "body")

    job = minicourse_jobs.submit_outline("track-1", "My Track",
                                         [{"title": "A", "notePath": "a.md"}])
    assert minicourse_jobs.get(job["id"])["status"] in ("QUEUED", "RUNNING")

    result = minicourse_jobs.approve(job["id"], None)

    assert result["error"]
    assert calls == []
    _poll(job["id"])  # drain the outline thread so it doesn't leak into other tests


def test_approve_unknown_job_id_returns_none():
    assert minicourse_jobs.approve("no-such-job", None) is None


def test_get_unknown_job_id_returns_none():
    assert minicourse_jobs.get("no-such-job") is None
