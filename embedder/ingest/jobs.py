"""Ingest job manager — async because jobs are minutes-long (whisper).

In-memory registry + single worker thread (MAX_CONCURRENT_JOBS=1: one model in
VRAM at a time, INGEST_AGENT_ARCH resource policy). Container restart kills
in-flight jobs — accepted in v1, jobs are idempotent and re-triggerable.

Finished bundles are persisted to {MODEL_CACHE}/ingest_bundles/{job_id}.json so
stage 2 synthesis can re-run without re-extracting.
"""
import json
import logging
import os
import queue
import re
import threading
import time
import uuid
from pathlib import Path

log = logging.getLogger("embedder.ingest.jobs")

BUNDLE_DIR = Path(os.environ.get("MODEL_CACHE", "/models")) / "ingest_bundles"

_jobs: dict[str, dict] = {}
_queue: "queue.Queue[str]" = queue.Queue()
_lock = threading.Lock()
_worker_started = False


def submit(ref: str, resolved_path, force_whisper: bool = False,
           extract_only: bool = False) -> dict:
    job_id = uuid.uuid4().hex[:12]
    job = {
        "id": job_id, "ref": ref, "status": "QUEUED", "stage": None,
        "created_at": time.time(), "error": None, "bundle_path": None,
        "force_whisper": force_whisper, "extract_only": extract_only,
        "_resolved_path": str(resolved_path) if resolved_path else None,
    }
    with _lock:
        _jobs[job_id] = job
    _ensure_worker()
    _queue.put(job_id)
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


def _ensure_worker():
    global _worker_started
    with _lock:
        if _worker_started:
            return
        _worker_started = True
    threading.Thread(target=_worker_loop, daemon=True,
                     name="ingest-worker").start()


def _worker_loop():
    while True:
        job_id = _queue.get()
        with _lock:
            job = _jobs.get(job_id)
        if job is None:
            continue
        try:
            _run(job)
        except Exception as e:  # job errors must never kill the worker thread
            log.exception("ingest job %s failed", job_id)
            job["status"] = "FAILED"
            job["error"] = str(e)[:500]


def _run(job: dict):
    from ingest import extract_av, extract_pdf, extract_web, router

    job["status"] = "RUNNING"
    kind = router.route(job["ref"])
    job["stage"] = f"extract:{kind}"
    resolved = Path(job["_resolved_path"]) if job["_resolved_path"] else None

    if kind in ("av", "youtube"):
        bundle = extract_av.extract(job["ref"], resolved, job["force_whisper"])
        if kind == "av" and resolved and not router.is_audio(job["ref"]):
            job["stage"] = "keyframes"
            _attach_keyframes(job, bundle, resolved)
    elif kind == "pdf":
        bundle = extract_pdf.extract(job["ref"], resolved)
    elif kind == "web":
        bundle = extract_web.extract(job["ref"])
    else:
        raise NotImplementedError(
            f"route '{kind}': single images go through the existing "
            f"pending_image_jobs pipeline, not ingest")

    BUNDLE_DIR.mkdir(parents=True, exist_ok=True)
    out = BUNDLE_DIR / f"{job['id']}.json"
    out.write_text(json.dumps(bundle, ensure_ascii=False, indent=1),
                   encoding="utf-8")
    job["bundle_path"] = str(out)
    job["segments"] = len(bundle["segments"])
    job["duration_s"] = bundle["source"]["duration_s"]
    job["stage"] = "extracted"

    if job.get("extract_only"):
        job["status"] = "DONE"
        return

    job["stage"] = "synthesize"
    _synthesize_and_publish(job, bundle)
    job["status"] = "DONE"
    log.info("ingest job %s: %d segments, %d note(s) from %s",
             job["id"], len(bundle["segments"]),
             len(job.get("notes_created", [])), job["ref"])


def _attach_keyframes(job: dict, bundle: dict, video_path: Path):
    """Keyframes are best-effort — a CLIP/scenedetect failure must not kill
    the transcript-based notes."""
    try:
        from ingest import keyframes
        slug = re.sub(r"[^\w-]", "-", video_path.stem)[:60]
        bundle["media"] = keyframes.extract_keyframes(
            video_path, bundle["segments"], slug)
    except Exception as e:
        log.warning("keyframes failed for %s (continuing without): %s",
                    job["ref"], e)


def _synthesize_and_publish(job: dict, bundle: dict):
    from ingest import publish, synthesize
    from ingest import bundle as bundle_util

    # store media first so embeds resolve at validation time
    stored_names = set()
    for m in bundle.get("media", []):
        if "data_b64" in m:
            publish.store_media(m["path"], m.pop("data_b64"))
        stored_names.add(m["path"].rsplit("/", 1)[-1])

    plans = synthesize.outline(bundle)
    job["planned_notes"] = [p["title"] for p in plans]
    numbered = bundle_util.number_segments(bundle)

    folder = publish.find_home(bundle["source"].get("title", ""))
    created, failures = [], []
    for plan in plans:
        try:
            note_md = synthesize.write_note(bundle, plan, numbered)
            problems = publish.validate_note(note_md, stored_names)
            if problems:
                raise publish.PublishError("; ".join(problems))
            path = publish.create_note(folder, synthesize.slugify(plan["title"]),
                                       note_md)
            created.append(path)
        except Exception as e:  # one bad note must not sink its siblings
            log.warning("note %r failed: %s", plan["title"], e)
            failures.append({"title": plan["title"], "error": str(e)[:300]})

    job["notes_created"] = created
    if failures:
        job["note_failures"] = failures
    if not created:
        raise RuntimeError(f"all {len(plans)} planned notes failed: {failures}")
