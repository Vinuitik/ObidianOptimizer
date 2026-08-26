"""Excel/CSV import — maps an arbitrary personal-spreadsheet export into this app's
{tracks, items} shape via one LLM call, schema-validated with a retry budget.

Reuses ingest/synthesize.py's JSON-extract + validate + retry-with-errors-appended
discipline (same one tracks/minicourse.py already reuses for course outlines) rather
than flashcards/generate.py's own copy — the target here is a single JSON object, which
is exactly what synthesize._json_object is built to pull out of raw LLM text, and the
whole point of `import_agent.py` living in `tracks/` is not re-deriving that plumbing
a third time.

Pure function: no HTTP, no DB. A later step wires this into an endpoint.
"""
import logging

import httpx

from ingest import synthesize
from ingest.synthesize import MAX_RETRIES, _json_object

log = logging.getLogger("embedder.tracks.import_agent")

IMPORT_SYSTEM = (
    "You convert an arbitrary personal spreadsheet export into a structured "
    "learning-tracks JSON schema. You output ONLY valid JSON."
)

IMPORT_PROMPT = """The CSV below is a learner's personal record of study material — books,
courses, videos, whatever they were tracking. Columns might be named Book/Course,
Chapter, Status, Done — or something completely different. Infer intent from the
data, don't assume any fixed column layout.

Group rows into tracks (e.g. one track per book/course) and, within each track,
list its items in the SAME ORDER the rows appeared in the CSV — this is a
sequential learning list, not an unordered set.

Return ONLY this JSON shape:
{{"tracks": [{{"title": "...", "type": "..."}}],
  "items": [{{"trackIndex": 0, "title": "...", "status": "pending"}}]}}

Rules: trackIndex is a 0-based index into "tracks". status is exactly "pending"
or "done" (map anything that reads as finished/checked-off to "done", everything
else to "pending"). Every track needs a non-empty title. Every item needs a
non-empty title.

CSV:
{csv_text}"""


def _complete(prompt: str) -> str:
    # priority=medium: same tier as flashcards — an on-demand user action, behind
    # live ingest (high) but ahead of image-captions (low). See host-wrapper
    # llm_router.PRIORITY; mirrors minicourse._complete's reasoning.
    resp = httpx.post(f"{synthesize.WRAPPER_URL}/complete",
                      json={"prompt": prompt, "system": IMPORT_SYSTEM,
                            "model": synthesize.SYNTH_MODEL, "priority": "medium"},
                      timeout=synthesize.LLM_TIMEOUT_S)
    if resp.status_code != 200:
        raise synthesize.SynthesisError(
            f"wrapper /complete {resp.status_code}: {resp.text[:300]}", status=resp.status_code)
    return resp.json()["text"]


def _validate(data) -> list[str]:
    """Whole-object validation: unlike flashcards' independently-droppable card list,
    this is one coherent mapping (an item's trackIndex references a sibling track), so
    any error invalidates the whole attempt rather than filtering item-by-item."""
    errors: list[str] = []
    if not isinstance(data, dict):
        return ["top-level output must be a JSON object"]

    tracks = data.get("tracks")
    if not isinstance(tracks, list) or not tracks:
        errors.append("tracks must be a non-empty list")
        tracks = []
    for i, t in enumerate(tracks):
        if not isinstance(t, dict) or not isinstance(t.get("title"), str) or not t["title"].strip():
            errors.append(f"tracks[{i}]: non-empty title required")
        if not isinstance(t.get("type"), str) or not t["type"].strip():
            errors.append(f"tracks[{i}]: non-empty type required")

    items = data.get("items")
    if not isinstance(items, list) or not items:
        errors.append("items must be a non-empty list")
        items = []
    for i, it in enumerate(items):
        if not isinstance(it, dict):
            errors.append(f"items[{i}]: must be an object")
            continue
        idx = it.get("trackIndex")
        if not isinstance(idx, int) or isinstance(idx, bool) or not 0 <= idx < len(tracks):
            errors.append(f"items[{i}]: trackIndex must be a valid index into tracks")
        if not isinstance(it.get("title"), str) or not it["title"].strip():
            errors.append(f"items[{i}]: non-empty title required")
        if it.get("status") not in ("pending", "done"):
            errors.append(f"items[{i}]: status must be 'pending' or 'done'")

    return errors


def map_csv_to_tracks(csv_text: str) -> dict:
    """Maps arbitrary CSV rows into {"tracks": [...], "items": [...]}. See module
    docstring. Raises ValueError if the LLM never produces a valid mapping within the
    retry budget (caller turns this into an HTTP 422)."""
    if not csv_text or not csv_text.strip():
        raise ValueError("csv_text is empty")

    prompt = IMPORT_PROMPT.format(csv_text=csv_text)
    last_err = None
    for attempt in range(1 + MAX_RETRIES):
        try:
            raw = _complete(prompt if attempt == 0
                            else f"{prompt}\n\nYour previous reply was invalid: {last_err}. "
                                 f"Return ONLY the corrected JSON.")
            data = _json_object(raw)
        except (httpx.HTTPError, synthesize.SynthesisError, ValueError) as e:
            last_err = str(e)[:200]
            log.warning("map_csv_to_tracks attempt %d: %s", attempt, last_err)
            continue

        errors = _validate(data)
        if not errors:
            return {"tracks": data["tracks"], "items": data["items"]}
        last_err = "; ".join(errors)[:500]
        log.warning("map_csv_to_tracks attempt %d invalid: %s", attempt, last_err)

    raise ValueError(f"map_csv_to_tracks failed after {1 + MAX_RETRIES} attempts: {last_err}")
