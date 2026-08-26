"""Mini-course generation — a track's accumulated notes → course syllabus + lesson bodies.

Sibling of `ingest/synthesize.py`'s OUTLINE/WRITE passes, retargeted from "raw source →
notes" to "a track's existing notes → ordered lessons". Reuses the same bundle dict shape
(`ingest/bundle.py` helpers work unchanged) and the same schema-validated, retry-with-
errors-appended discipline as `synthesize.outline()`.

This module is intentionally job-registry-agnostic: build_course_bundle/outline_course/
expand_lesson are pure functions over data the caller supplies. A later step wraps them
in a job + HTTP endpoint.
"""
import json
import logging
import re

import httpx

from agent_reports import AgentReport
from ingest import bundle as bundle_util
from ingest import synthesize
from ingest.synthesize import MAX_RETRIES, SynthesisError, _json_object, _strip_fences

log = logging.getLogger("embedder.tracks.minicourse")

_HEADING = re.compile(r"^## (.+)$", re.MULTILINE)
_FM = re.compile(r"^---\n.*?\n---\n", re.DOTALL)

COURSE_OUTLINE_SYSTEM = (
    "You are a course designer. You turn a learner's accumulated notes into a "
    "structured mini-course made of ordered lessons. You output ONLY valid JSON."
)

COURSE_OUTLINE_PROMPT = """Track: {track_title}
Segments below are numbered [id @ location] and come from the learner's own notes.

Design a mini-course covering this material as an ordered sequence of lessons.
Group segments by learning objective — each lesson must stand alone and build on
the ones before it. Prefer FEWER, richer lessons; split only on real topic
boundaries.

Return ONLY this JSON shape:
{{"course_title": "...", "lessons": [{{"title": "...", "objective": "...",
  "segment_ids": [0,1], "summary_hint": "one line on what this lesson covers"}}]}}

Rules: every segment id appears in at least one lesson; titles are short noun
phrases unique within this plan.

SEGMENTS:
{segments}"""

COURSE_WRITE_SYSTEM = (
    "You write dense, well-structured Obsidian lesson notes in markdown. Headings with "
    "##, code in fenced blocks, math in $...$. No frontmatter, no top-level title — the "
    "caller adds those. Never invent facts absent from the source."
)

COURSE_WRITE_PROMPT = """Write the body of the lesson titled "{title}".
Objective: {objective}
Hint: {summary_hint}

Use ONLY the source segments below. Structure freely (##-sections, lists,
tables) but stay faithful to the source. Start directly with content.

SOURCE SEGMENTS:
{segments}"""


def _complete(prompt: str, system: str) -> str:
    # priority=medium: minicourse generation, like flashcards, yields scarce LLM tokens
    # to live ingest (high) but goes ahead of image-captions (low). Same host-wrapper
    # /complete contract as synthesize._complete — reuses its URL/model/timeout config.
    resp = httpx.post(f"{synthesize.WRAPPER_URL}/complete",
                      json={"prompt": prompt, "system": system,
                            "model": synthesize.SYNTH_MODEL, "priority": "medium"},
                      timeout=synthesize.LLM_TIMEOUT_S)
    if resp.status_code != 200:
        raise SynthesisError(f"wrapper /complete {resp.status_code}: {resp.text[:300]}",
                             status=resp.status_code)
    return resp.json()["text"]


def build_course_bundle(track_id: str, track_title: str, items: list[dict]) -> dict:
    """Reads each item's note off the vault mount and splits it on '## ' headings into
    segments, shaped into the same bundle dict `ingest/bundle.py`'s helpers expect.
    Items with no notePath yet (nothing to read) are skipped."""
    from mcp_server import _resolve_in_vault

    segments = []
    for item in items:
        note_path = item.get("notePath")
        if not note_path:
            continue
        try:
            content = _resolve_in_vault(note_path).read_text(encoding="utf-8")
        except (OSError, ValueError) as e:
            log.warning("minicourse: skipping unreadable note %s: %s", note_path, e)
            continue
        segments.extend(_segment_note(item.get("title") or note_path, content))

    return {
        "source": {"type": "track", "ref": track_id, "title": track_title,
                   "duration_s": 0, "chapters": []},
        "segments": segments,
        "media": [],
    }


def _segment_note(note_title: str, content: str) -> list[dict]:
    """One segment per '## ' section (preamble first); headingless notes become a
    single segment. Heading is prefixed with the note's own title so segments from
    different notes stay distinguishable once merged into one course bundle."""
    body = _FM.sub("", content, count=1)
    matches = list(_HEADING.finditer(body))
    if not matches:
        text = body.strip()
        return [{"loc": {"heading": note_title}, "text": text}] if text else []
    segments = []
    pre = body[:matches[0].start()].strip()
    if pre:
        segments.append({"loc": {"heading": note_title}, "text": pre})
    for i, m in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(body)
        text = body[m.start():end].strip()
        if text:
            segments.append({"loc": {"heading": f"{note_title} — {m.group(1).strip()}"},
                             "text": text})
    return segments


def outline_course(bundle: dict) -> dict:
    segs = bundle_util.number_segments(bundle)
    subject = bundle["source"].get("title") or bundle["source"].get("ref", "untitled")
    rep = AgentReport("minicourse", subject)
    rep.input("source", {k: bundle["source"].get(k) for k in ("type", "title", "ref")})
    rep.input("segments", bundle_util.render_segments(segs))
    try:
        result = _outline_course_pass(bundle, segs, rep)
        rep.output("course plan", result)
        rep.save(status="ok")
        return result
    except SynthesisError as e:
        rep.output("error", str(e))
        rep.save(status="error")
        raise


def _outline_course_pass(bundle: dict, segs: list[dict], rep: AgentReport) -> dict:
    prompt = COURSE_OUTLINE_PROMPT.format(
        track_title=bundle["source"].get("title", "untitled"),
        segments=bundle_util.render_segments(segs))
    valid_ids = {s["id"] for s in segs}
    last_err = None
    for attempt in range(MAX_RETRIES + 1):
        raw = _complete(prompt if attempt == 0
                        else f"{prompt}\n\nYour previous reply was invalid: {last_err}. "
                             f"Return ONLY the corrected JSON.", COURSE_OUTLINE_SYSTEM)
        rep.said(f"raw outline (attempt {attempt})", raw)
        try:
            plan = _json_object(raw)
            course_title = plan["course_title"]
            assert isinstance(course_title, str) and course_title, "course_title required"
            lessons = plan["lessons"]
            assert isinstance(lessons, list) and lessons, "lessons must be a non-empty list"
            for lesson in lessons:
                assert lesson.get("title"), "every lesson needs a title"
                assert lesson.get("objective"), "every lesson needs an objective"
                lesson["segment_ids"] = [i for i in lesson.get("segment_ids", []) if i in valid_ids]
                lesson.setdefault("summary_hint", "")
            lessons = [l for l in lessons if l["segment_ids"]]
            assert lessons, "no lesson kept any valid segment id"
            return {"course_title": course_title, "lessons": lessons}
        except (json.JSONDecodeError, AssertionError, KeyError, TypeError) as e:
            last_err = str(e)[:200]
            log.warning("outline_course attempt %d invalid: %s", attempt + 1, last_err)
    raise SynthesisError(f"outline_course failed after {MAX_RETRIES + 1} attempts: {last_err}")


def expand_lesson(bundle: dict, lesson: dict, by_id: dict) -> str:
    """One COURSE_WRITE call → markdown body for a single planned lesson."""
    segs = [by_id[i] for i in lesson["segment_ids"] if i in by_id]
    body = _complete(COURSE_WRITE_PROMPT.format(
        title=lesson["title"], objective=lesson.get("objective", ""),
        summary_hint=lesson.get("summary_hint", ""),
        segments=bundle_util.render_segments(segs)), COURSE_WRITE_SYSTEM)
    return _strip_fences(body)
