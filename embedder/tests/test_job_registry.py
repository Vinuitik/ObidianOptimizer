"""JobRegistry: dict+lock+status plumbing shared by download/jobs.py and
tracks/minicourse_jobs.py. Pure stdlib (threading/uuid/time) — no ML deps."""
import threading
import time

import pytest

from job_registry import JobRegistry, PerCallThread, public_view


def test_public_view_strips_underscore_prefixed_keys():
    # Internal bookkeeping fields (leading "_") must never leak to the API —
    # that's the whole reason public_view exists instead of returning job dicts raw.
    job = {"status": "DONE", "result": "ok", "_internal_lock_token": "secret", "_retry_fn": lambda: None}
    assert public_view(job) == {"status": "DONE", "result": "ok"}


def test_create_assigns_created_at_when_missing():
    reg = JobRegistry()
    before = time.time()
    job = reg.create("j1", {"status": "QUEUED"})
    after = time.time()
    assert before <= job["created_at"] <= after


def test_create_preserves_existing_created_at_instead_of_overwriting():
    # A caller resubmitting/upserting a job with a known creation time must keep its
    # original ordering — setdefault, not unconditional assignment.
    reg = JobRegistry()
    job = reg.create("j1", {"status": "QUEUED", "created_at": 12345.0})
    assert job["created_at"] == 12345.0


def test_get_raw_returns_none_for_unknown_id():
    reg = JobRegistry()
    assert reg.get_raw("nonexistent") is None


def test_get_returns_public_view_not_the_raw_dict():
    reg = JobRegistry()
    reg.create("j1", {"status": "QUEUED", "_secret": "x"})
    view = reg.get("j1")
    assert "_secret" not in view
    assert view["status"] == "QUEUED"


def test_get_unknown_id_returns_none_not_empty_dict():
    reg = JobRegistry()
    assert reg.get("missing") is None


def test_list_jobs_sorted_newest_first():
    reg = JobRegistry()
    reg.create("old", {"status": "DONE", "created_at": 1.0})
    reg.create("new", {"status": "DONE", "created_at": 3.0})
    reg.create("mid", {"status": "DONE", "created_at": 2.0})

    ids_in_order = [j["created_at"] for j in reg.list_jobs()]
    assert ids_in_order == [3.0, 2.0, 1.0]


def test_list_jobs_strips_underscore_fields_from_every_entry():
    reg = JobRegistry()
    reg.create("j1", {"status": "DONE", "created_at": 1.0, "_private": "x"})
    jobs = reg.list_jobs()
    assert all("_private" not in j for j in jobs)


def test_new_id_produces_12_char_hex_and_is_unique_per_call():
    reg = JobRegistry()
    a, b = reg.new_id(), reg.new_id()
    assert len(a) == 12 and len(b) == 12
    assert all(c in "0123456789abcdef" for c in a)
    assert a != b


def test_dispatch_delegates_to_injected_strategy_with_args_and_name():
    calls = []

    class RecordingStrategy:
        def start(self, run_fn, *args, name=None):
            calls.append((run_fn, args, name))

    reg = JobRegistry(dispatch=RecordingStrategy())
    fn = lambda x: x
    reg.dispatch(fn, "arg1", "arg2", name="job-name")

    assert calls == [(fn, ("arg1", "arg2"), "job-name")]


def test_per_call_thread_actually_runs_the_function_off_the_calling_thread():
    strategy = PerCallThread()
    done = threading.Event()
    caller_thread_id = threading.get_ident()
    seen_thread_id = []

    def work():
        seen_thread_id.append(threading.get_ident())
        done.set()

    strategy.start(work, name="test-job")
    assert done.wait(timeout=2.0), "PerCallThread never ran the work function"
    assert seen_thread_id[0] != caller_thread_id


def test_jobs_property_gives_direct_dict_access_for_lock_held_callers():
    # minicourse_jobs.approve() reads/mutates via .jobs while already holding .lock —
    # it must be the SAME dict create() wrote into, not a copy.
    reg = JobRegistry()
    reg.create("j1", {"status": "QUEUED"})
    with reg.lock:
        reg.jobs["j1"]["status"] = "APPROVED"
    assert reg.get_raw("j1")["status"] == "APPROVED"
