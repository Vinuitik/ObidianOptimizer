"""Shared in-memory job registry: dict + lock + status + created_at ordering, with a
pluggable DispatchStrategy for how a submitted job's work actually runs.

Extracted from download/jobs.py and tracks/minicourse_jobs.py, which each hand-wrote
this exact dict+lock+thread plumbing independently. `ingest/jobs.py` keeps its own
GPU-aware single-shared-worker-thread logic untouched (see
architecture_plans/QUEUE_UNIFICATION_PLAN.md Phase 1) — a SharedWorkerThread strategy
could plug in here later without changing this class, since dispatch is a separate
injected object rather than something JobRegistry hardcodes.
"""
import threading
import time
import uuid


class DispatchStrategy:
    """Runs a submitted job's work function. Swappable per registry instance."""

    def start(self, run_fn, *args, name: str | None = None) -> None:
        raise NotImplementedError


class PerCallThread(DispatchStrategy):
    """Spawns one daemon thread per submitted job — unbounded concurrency, no shared
    worker, no retry. What download/jobs.py and minicourse_jobs.py both did by hand."""

    def start(self, run_fn, *args, name: str | None = None) -> None:
        threading.Thread(target=run_fn, args=args, daemon=True, name=name).start()


def public_view(job: dict) -> dict:
    return {k: v for k, v in job.items() if not k.startswith("_")}


class JobRegistry:
    """dict + lock + status, keyed by an auto-generated job id. Individual job-field
    mutations (job["status"] = ...) happen lock-free on the dict returned by create()/
    get_raw(), same as the hand-rolled originals — the lock only guards the registry's
    own dict operations (insert/lookup/iterate), not a job's in-flight field writes."""

    def __init__(self, dispatch: DispatchStrategy | None = None):
        self._jobs: dict[str, dict] = {}
        self._lock = threading.Lock()
        self._dispatch = dispatch or PerCallThread()

    @property
    def lock(self) -> threading.Lock:
        return self._lock

    def new_id(self) -> str:
        return uuid.uuid4().hex[:12]

    def create(self, job_id: str, job: dict) -> dict:
        job.setdefault("created_at", time.time())
        with self._lock:
            self._jobs[job_id] = job
        return job

    def get_raw(self, job_id: str) -> dict | None:
        with self._lock:
            return self._jobs.get(job_id)

    @property
    def jobs(self) -> dict:
        """Raw job dict for check-and-mutate call sites that already hold `.lock`
        (e.g. minicourse_jobs.approve()'s status check + transition). Callers MUST
        hold `.lock` before touching this — it does no locking itself, unlike
        get_raw()/get(), so it can be used from inside an existing `with .lock:`
        block without the self-deadlock a nested get_raw() call would cause
        (threading.Lock is not reentrant)."""
        return self._jobs

    def get(self, job_id: str) -> dict | None:
        job = self.get_raw(job_id)
        return public_view(job) if job else None

    def list_jobs(self) -> list[dict]:
        with self._lock:
            jobs = list(self._jobs.values())
        return [public_view(j) for j in
                sorted(jobs, key=lambda j: j["created_at"], reverse=True)]

    def dispatch(self, run_fn, *args, name: str | None = None) -> None:
        self._dispatch.start(run_fn, *args, name=name)
