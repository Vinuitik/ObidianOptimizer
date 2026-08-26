"""In-memory job registry wrapping tracks/minicourse.py's pure functions in a two-pass
async workflow with a human approval gate in between:

  QUEUED -> RUNNING(stage=outline) -> AWAITING_APPROVAL -> RUNNING(stage=lessons) -> DONE|FAILED

One job id spans both passes. Unlike ingest/jobs.py (single shared worker thread, because
whisper/CLIP need exclusive GPU access), each submit_outline()/approve() call spawns its
own daemon thread — mini-course generation makes zero GPU calls, only host-wrapper
/complete HTTP calls, and host-wrapper already arbitrates provider concurrency/priority
itself. Concurrent mini-course generations are intentional, not a bug.

Same in-memory, restart-loses-status tradeoff ingest/jobs.py accepts — no persistence.
This module does not write notes to the vault or call Java; the HTTP wiring is a later step.
"""
import logging
import threading
import time
import uuid

from ingest import bundle as bundle_util
from ingest.synthesize import SynthesisError
from tracks import minicourse

log = logging.getLogger("embedder.tracks.minicourse_jobs")

_jobs: dict[str, dict] = {}
_lock = threading.Lock()


def submit_outline(track_id: str, track_title: str, items: list[dict]) -> dict:
    job_id = uuid.uuid4().hex[:12]
    job = {
        "id": job_id, "status": "QUEUED", "stage": None,
        "track_id": track_id, "course_title": None, "error": None,
        "created_at": time.time(),
        "plan": None, "results": None, "lesson_failures": None,
    }
    with _lock:
        _jobs[job_id] = job
    threading.Thread(target=_run_outline, args=(job, track_id, track_title, items),
                     daemon=True, name=f"minicourse-outline-{job_id}").start()
    return public_view(job)


def approve(job_id: str, approved_indexes: list[int] | None) -> dict | None:
    with _lock:
        job = _jobs.get(job_id)
        if job is None:
            return None
        if job["status"] != "AWAITING_APPROVAL":
            view = public_view(job)
            view["error"] = f"job {job_id} is {job['status']}, not AWAITING_APPROVAL"
            return view

        lessons = job["_plan"]["lessons"]
        if approved_indexes is None:
            selected = list(enumerate(lessons))
        else:
            selected = [(i, lessons[i]) for i in approved_indexes if 0 <= i < len(lessons)]

        job["status"] = "RUNNING"
        job["stage"] = "lessons"

    threading.Thread(target=_run_lessons, args=(job, selected),
                     daemon=True, name=f"minicourse-lessons-{job_id}").start()
    return public_view(job)


def get(job_id: str) -> dict | None:
    with _lock:
        job = _jobs.get(job_id)
        return public_view(job) if job else None


def list_jobs() -> list[dict]:
    with _lock:
        return [public_view(j) for j in
                sorted(_jobs.values(), key=lambda j: j["created_at"], reverse=True)]


def public_view(job: dict) -> dict:
    return {k: v for k, v in job.items() if not k.startswith("_")}


def _run_outline(job: dict, track_id: str, track_title: str, items: list[dict]) -> None:
    job["status"] = "RUNNING"
    job["stage"] = "outline"
    try:
        bundle = minicourse.build_course_bundle(track_id, track_title, items)
        if not bundle["segments"]:
            job["status"] = "FAILED"
            job["error"] = "no items had a readable note — nothing to build a course from"
            return
        plan = minicourse.outline_course(bundle)
    except SynthesisError as e:
        job["status"] = "FAILED"
        job["error"] = str(e)[:500]
        return
    except Exception as e:  # job errors must never leave the job stuck in RUNNING
        log.exception("minicourse outline job %s failed", job["id"])
        job["status"] = "FAILED"
        job["error"] = str(e)[:500]
        return

    job["_bundle"] = bundle
    job["_plan"] = plan
    job["course_title"] = plan["course_title"]
    job["plan"] = plan
    job["stage"] = None
    job["status"] = "AWAITING_APPROVAL"


def _run_lessons(job: dict, selected: list[tuple[int, dict]]) -> None:
    bundle = job["_bundle"]
    segs = bundle_util.number_segments(bundle)
    by_id = {s["id"]: s for s in segs}

    results, failures = [], []
    for _idx, lesson in selected:
        try:
            body = minicourse.expand_lesson(bundle, lesson, by_id)
            results.append({"title": lesson["title"], "body": body})
        except Exception as e:  # one bad lesson must not sink the others
            log.warning("minicourse lesson %r failed (job %s): %s",
                       lesson.get("title"), job["id"], e)
            failures.append({"title": lesson.get("title"), "error": str(e)[:300]})

    job["results"] = results
    job["lesson_failures"] = failures or None
    job["stage"] = None
    job["status"] = "DONE"
