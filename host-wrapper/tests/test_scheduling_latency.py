"""Scheduling-latency tests — the router must never sit idle when work could run.

These assert TIMING, not reply correctness: a regression that adds dead air
(waits quantized to the poll loop, a benched provider blocking the chain, rate
spacing that delays instead of falling through to an idle provider) fails here
even though every reply would still be right.

Context: `Router._acquire` waits on a Condition with a 0.25s poll timeout.
A release notifies waiters immediately; cooldown/rate-spacing expiry has no
notifier, so those paths may pay up to one 0.25s poll tick — the bounds below
allow exactly that and no more.

All provider HTTP is monkeypatched; nothing here talks to a real API.
"""
import threading
import time

import pytest

import llm_router
from llm_router import Router, RouterError, _RateLimited

FAR = 3600  # "not this test" cooldown


def _only(router, *names):
    """Cool every vision provider except `names` far into the future."""
    for p in router._chain("vision"):
        if p.name not in names:
            p.cooldown_until = time.time() + FAR


def _run_concurrent(router, n, timeout=10):
    """Fire n complete_vision calls in parallel; returns list of (ok, wall_s)."""
    results = [None] * n

    def one(i):
        t0 = time.time()
        try:
            router.complete_vision("p", b"x", "image/png")
            results[i] = (True, time.time() - t0)
        except RouterError:
            results[i] = (False, time.time() - t0)

    threads = [threading.Thread(target=one, args=(i,)) for i in range(n)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=timeout)
    assert all(r is not None for r in results), "a request never finished"
    return results


def _fake_call(latency=0.0, record=None):
    """Monkeypatch target for _openai_chat: sleeps `latency`, logs enter times."""
    def call(p, model, messages, max_tokens):
        if record is not None:
            record.append((p.name, time.time()))
        if latency:
            time.sleep(latency)
        return "ok"
    return call


# ── wake-up promptness ───────────────────────────────────────────────────

def test_release_wakes_waiter_promptly(router, no_rate_spacing, monkeypatch):
    """A queued request must start within ~one scheduler beat of the lease
    freeing — not a full poll tick later, and never seconds later."""
    r = no_rate_spacing(router)
    _only(r, "gemini")
    enters = []
    monkeypatch.setattr(llm_router, "_openai_chat", _fake_call(0.4, enters))

    _run_concurrent(r, 2)
    assert len(enters) == 2
    (_, t1), (_, t2) = sorted(enters, key=lambda e: e[1])
    # first call holds the lease 0.4s; the waiter must enter right after release
    gap_after_release = t2 - (t1 + 0.4)
    assert gap_after_release < 0.2, f"waiter idled {gap_after_release:.3f}s after release"


def test_burst_shards_in_parallel_makespan(router, no_rate_spacing, monkeypatch):
    """K=N concurrent requests over N providers must take ~1 latency, not K."""
    r = no_rate_spacing(router)
    n = len(r._chain("vision"))  # gemini/github/mistral/groq = 4 in the test env
    assert n >= 3
    monkeypatch.setattr(llm_router, "_openai_chat", _fake_call(0.4))

    t0 = time.time()
    results = _run_concurrent(r, n)
    wall = time.time() - t0
    assert all(ok for ok, _ in results)
    assert wall < 0.4 * 2, f"burst of {n} took {wall:.2f}s — sharding degenerated to a queue"


# ── benching / spacing must not create false waits ───────────────────────

def test_bench_never_delays_the_chain(router, no_rate_spacing, monkeypatch):
    """Top-priority provider benched → the request lands on the next one NOW."""
    r = no_rate_spacing(router)
    r.providers["gemini"].cooldown_until = time.time() + 60
    monkeypatch.setattr(llm_router, "_openai_chat", _fake_call())

    t0 = time.time()
    _, provider = r.complete_vision("p", b"x", "image/png")
    assert provider == "github"
    assert time.time() - t0 < 0.3, "waited toward gemini's cooldown instead of falling through"


def test_rate_spacing_falls_through_to_idle_provider(router, monkeypatch):
    """gemini rate-spaced (next_start in the future) while github is idle:
    the request must go to github immediately, not wait out the spacing."""
    r = router
    r.providers["gemini"].min_interval = 5.0
    for name in ("github", "mistral", "groq"):
        r.providers[name].min_interval = 0.0
    monkeypatch.setattr(llm_router, "_openai_chat", _fake_call())

    _, first = r.complete_vision("p", b"x", "image/png")
    assert first == "gemini"  # leased fresh, spacing starts now

    t0 = time.time()
    _, second = r.complete_vision("p", b"x", "image/png")
    assert second == "github", "should shard to the idle provider"
    assert time.time() - t0 < 0.3, "waited out gemini's min_interval with github idle"


def test_single_provider_spacing_wait_is_bounded(router, monkeypatch):
    """Only one provider, min_interval=0.5: the second request should wait
    ~0.5s (+ at most one 0.25s poll tick) — a regression to multi-second
    quantized waits fails here."""
    r = router
    _only(r, "gemini")
    r.providers["gemini"].min_interval = 0.5
    monkeypatch.setattr(llm_router, "_openai_chat", _fake_call())

    r.complete_vision("p", b"x", "image/png")
    t0 = time.time()
    r.complete_vision("p", b"x", "image/png")
    waited = time.time() - t0
    assert waited >= 0.3, "spacing not applied at all"
    assert waited < 1.0, f"waited {waited:.2f}s for a 0.5s spacing window"


# ── recovery timing ──────────────────────────────────────────────────────

def test_retry_after_reopens_provider_exactly(router, no_rate_spacing, monkeypatch):
    """Retry-After: 1 must reopen the provider at ~+1s — not the default
    exponential bench, and not the acquire deadline."""
    r = no_rate_spacing(router)
    _only(r, "gemini")
    calls = {"n": 0}

    def flaky(p, model, messages, max_tokens):
        calls["n"] += 1
        if calls["n"] == 1:
            raise _RateLimited(retry_after=1)
        return "ok"

    monkeypatch.setattr(llm_router, "_openai_chat", flaky)

    t0 = time.time()
    with pytest.raises(RouterError):     # only provider 429'd → this request fails
        r.complete_vision("p", b"x", "image/png")
    assert time.time() - t0 < 0.5, "the failing request itself should fail fast"

    t0 = time.time()
    _, provider = r.complete_vision("p", b"x", "image/png")  # blocks until reopen
    waited = time.time() - t0
    assert provider == "gemini"
    assert waited < 1.5, f"reopened after {waited:.2f}s — Retry-After 1s not honored"
    assert waited >= 0.7, "leased before the provider's Retry-After elapsed"


# ── priority: scarce tokens go to the important request ──────────────────

def test_high_priority_waiter_wins_freed_provider(router, no_rate_spacing):
    """One provider, occupied. A LOW and a HIGH request both block waiting for it.
    When it frees, HIGH must get it first (ingest beats image-captions)."""
    r = no_rate_spacing(router)
    _only(r, "gemini")
    g = r.providers["gemini"]
    with r._cv:              # occupy the only provider
        g.in_flight = 1

    got = []
    start = threading.Barrier(3)

    def waiter(prio):
        start.wait()
        p = r._acquire("vision", set(), prio)
        got.append(prio)
        r._release(p, ok=True)

    low = threading.Thread(target=waiter, args=("low",))
    high = threading.Thread(target=waiter, args=("high",))
    low.start(); high.start()
    start.wait()
    time.sleep(0.4)          # let both register as blocked waiters
    with r._cv:              # free the provider
        g.in_flight = 0
        r._cv.notify_all()
    low.join(3); high.join(3)

    assert got == ["high", "low"], f"priority ignored, got {got}"


def test_equal_priority_both_served_no_starvation(router, no_rate_spacing):
    """Two equal-priority waiters on one provider: both get served (no deadlock,
    equal priority never yields to itself)."""
    r = no_rate_spacing(router)
    _only(r, "gemini")
    g = r.providers["gemini"]
    with r._cv:
        g.in_flight = 1

    served = []
    start = threading.Barrier(3)

    def waiter(tag):
        start.wait()
        p = r._acquire("vision", set(), "medium")
        served.append(tag)
        r._release(p, ok=True)

    a = threading.Thread(target=waiter, args=("a",))
    b = threading.Thread(target=waiter, args=("b",))
    a.start(); b.start()
    start.wait()
    time.sleep(0.3)
    with r._cv:
        g.in_flight = 0
        r._cv.notify_all()
    a.join(3); b.join(3)

    assert sorted(served) == ["a", "b"]


def test_priority_does_not_break_doomed_fast_fail(router, no_rate_spacing):
    """A HIGH request when every provider is benched past the deadline still fails
    fast (priority must not defeat the doomed-acquire guard)."""
    r = no_rate_spacing(router)
    now = time.time()
    for p in r._chain("vision"):
        p.cooldown_until = now + 9999
    t0 = time.time()
    with pytest.raises(RouterError):
        r._acquire("vision", set(), "high")
    assert time.time() - t0 < 1.0


def test_all_benched_recovery_at_cooldown_not_deadline(router, no_rate_spacing, monkeypatch):
    """Every provider cooling 1s: the request must be served as the first
    cooldown expires (~1s + <=1 poll tick), not at the acquire deadline
    (2s in the test env) and not fail."""
    r = no_rate_spacing(router)
    now = time.time()
    for p in r._chain("vision"):
        p.cooldown_until = now + 1.0
    monkeypatch.setattr(llm_router, "_openai_chat", _fake_call())

    t0 = time.time()
    _, provider = r.complete_vision("p", b"x", "image/png")
    waited = time.time() - t0
    assert provider == "gemini"  # priority order preserved on reopen
    assert waited >= 0.8, "leased a provider that was still cooling"
    assert waited < 1.6, f"served after {waited:.2f}s — waited toward the deadline, not the cooldown"
