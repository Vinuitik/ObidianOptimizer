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


def submit(ref: str, resolved_path, force_whisper: bool = False) -> dict:
    job_id = uuid.uuid4().hex[:12]
    job = {
        "id": job_id, "ref": ref, "status": "QUEUED", "stage": None,
        "created_at": time.time(), "error": None, "bundle_path": None,
        "force_whisper": force_whisper,
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
    from ingest import extract_av, router

    job["status"] = "RUNNING"
    kind = router.route(job["ref"])
    job["stage"] = f"extract:{kind}"

    if kind in ("av", "youtube"):
        resolved = Path(job["_resolved_path"]) if job["_resolved_path"] else None
        bundle = extract_av.extract(job["ref"], resolved, job["force_whisper"])
    else:
        raise NotImplementedError(
            f"route '{kind}' lands in a later stage (pdf/web: stage 3, image: existing pipeline)")

    BUNDLE_DIR.mkdir(parents=True, exist_ok=True)
    out = BUNDLE_DIR / f"{job['id']}.json"
    out.write_text(json.dumps(bundle, ensure_ascii=False, indent=1),
                   encoding="utf-8")
    job["bundle_path"] = str(out)
    job["segments"] = len(bundle["segments"])
    job["duration_s"] = bundle["source"]["duration_s"]
    job["stage"] = "extracted"          # stage 2 will continue: synthesize
    job["status"] = "DONE"
    log.info("ingest job %s: %d segments from %s",
             job["id"], len(bundle["segments"]), job["ref"])
