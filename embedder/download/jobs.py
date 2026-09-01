"""Download job manager — in-memory registry + one worker thread per download.

Salvaged from VideoManager's poll-download flow, minus the agent-escalation half
(the heavy Ollama/Chroma/Playwright loop we deliberately did not bring over). A
yt-dlp failure here is just an error — for sites that need login/JS-scraping, that
escalation never shipped to this app.

Container restart drops in-flight jobs (in-memory, like ingest/jobs.py). Accepted:
downloads are idempotent and re-triggerable. Status: queued|downloading|done|error.
"""
import logging
import time

from download import downloader
from job_registry import JobRegistry, PerCallThread, public_view

log = logging.getLogger("embedder.download.jobs")

_registry = JobRegistry(PerCallThread())


def submit(url: str) -> dict:
    job_id = _registry.new_id()
    job = {
        "id": job_id, "url": url, "status": "queued",
        "progress": 0.0, "speed": "", "eta": "", "filename": "",
        "error": None, "created_at": time.time(),
    }
    _registry.create(job_id, job)
    _registry.dispatch(_run, job, name=f"download-{job_id}")
    return public_view(job)


def get(job_id: str) -> dict | None:
    return _registry.get(job_id)


def list_jobs() -> list[dict]:
    return _registry.list_jobs()


def _run(job: dict):
    def hook(d: dict) -> None:
        p = downloader.parse_progress(d)
        status = d.get("status")
        if status == "downloading":
            job["status"] = "downloading"
            job["progress"] = p["progress"]
            job["speed"] = p["speed"]
            job["eta"] = p["eta"]
        elif status == "finished":
            # post-download merge still pending; report the produced file
            job["filename"] = p["filename"] or job["filename"]

    try:
        filename = downloader.download_sync(job["url"], hook)
        job["filename"] = filename
        job["progress"] = 100.0
        job["status"] = "done"
        log.info("download job %s done: %s", job["id"], filename)
    except Exception as e:  # never raise out of the worker thread
        job["status"] = "error"
        job["error"] = str(e)[:500]
        log.warning("download job %s failed: %s", job["id"], e)
