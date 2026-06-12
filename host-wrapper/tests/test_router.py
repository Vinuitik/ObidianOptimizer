"""Router unit tests — chain building, leasing/sharding, failover, benching.

All provider HTTP is monkeypatched; nothing here talks to a real API.
"""
import threading
import time

import pytest

import llm_router
from llm_router import Router, RouterError, _RateLimited


# ── chain building ───────────────────────────────────────────────────────

def test_chain_respects_priority_and_skips_unconfigured(router):
    names = [p.name for p in router._chain("text")]
    # deepseek has no key in the test env → dropped; order follows TEXT_PRIORITY
    assert names == ["groq", "github", "mistral", "gemini", "claude-cli"]


def test_vision_chain_excludes_text_only_providers(router):
    names = [p.name for p in router._chain("vision")]
    # claude-cli and deepseek have no vision model; anthropic has no key
    assert names == ["gemini", "github", "mistral", "groq"]


def test_cli_configured_without_key(router):
    assert router.providers["claude-cli"].configured


# ── leasing / sharding ───────────────────────────────────────────────────

def test_acquire_leases_highest_priority(router, no_rate_spacing):
    r = no_rate_spacing(router)
    p = r._acquire("vision", skip=set())
    assert p.name == "gemini"
    assert p.in_flight == 1


def test_concurrent_acquires_shard_across_providers(router, no_rate_spacing):
    """Second concurrent lease must go to the NEXT provider, not queue on the first."""
    r = no_rate_spacing(router)
    first = r._acquire("vision", skip=set())
    second = r._acquire("vision", skip=set())
    assert first.name == "gemini"
    assert second.name == "github"


def test_release_frees_provider_for_reuse(router, no_rate_spacing):
    r = no_rate_spacing(router)
    p = r._acquire("vision", skip=set())
    r._release(p, ok=True)
    assert p.in_flight == 0
    assert p.ok_count == 1
    assert r._acquire("vision", skip=set()).name == "gemini"


def test_acquire_skips_cooling_provider(router, no_rate_spacing):
    r = no_rate_spacing(router)
    r.providers["gemini"].cooldown_until = time.time() + 60
    assert r._acquire("vision", skip=set()).name == "github"


def test_acquire_raises_when_no_provider_left(router):
    with pytest.raises(RouterError, match="no providers left"):
        router._acquire("vision", skip={"gemini", "github", "mistral", "groq"})


def test_acquire_deadline_when_all_busy(router, no_rate_spacing):
    r = no_rate_spacing(router)
    for p in r._chain("vision"):
        p.in_flight = 1
    start = time.time()
    with pytest.raises(RouterError, match="busy/cooling"):
        r._acquire("vision", skip=set())
    assert time.time() - start >= 1.5  # waited for the (test-shortened) deadline


def test_rate_spacing_blocks_back_to_back_starts(router):
    """min_interval pushes next_start forward — provider not immediately reusable."""
    p = router.providers["gemini"]
    p.min_interval = 30.0
    leased = router._acquire("vision", skip=set())
    router._release(leased, ok=True)
    # gemini's next_start is now in the future → next lease goes to github
    assert router._acquire("vision", skip=set()).name == "github"


# ── benching / backoff ───────────────────────────────────────────────────

def test_bench_exponential_backoff_and_cap():
    p = llm_router.Provider("x", "openai", "k", url="http://x", text_model="m")
    p.bench()
    first = p.cooldown_until - time.time()
    assert 25 <= first <= 31  # 30 * 2^0
    p.bench()
    second = p.cooldown_until - time.time()
    assert 55 <= second <= 61  # 30 * 2^1
    p.consecutive_failures = 20
    p.bench()
    assert p.cooldown_until - time.time() <= 3601  # capped at 1h


def test_bench_honors_explicit_retry_after():
    p = llm_router.Provider("x", "openai", "k", url="http://x", text_model="m")
    p.bench(seconds=120)
    assert 115 <= p.cooldown_until - time.time() <= 121


def test_success_resets_failure_streak():
    p = llm_router.Provider("x", "openai", "k", url="http://x", text_model="m")
    p.bench()
    p.succeed()
    assert p.consecutive_failures == 0


# ── failover (_run) ──────────────────────────────────────────────────────

def test_run_fails_over_to_next_provider(router, no_rate_spacing, monkeypatch):
    r = no_rate_spacing(router)
    calls = []

    def fake_openai(p, model, messages, max_tokens):
        calls.append(p.name)
        if p.name == "gemini":
            raise RuntimeError("HTTP 500: boom")
        return "extracted text"

    monkeypatch.setattr(llm_router, "_openai_chat", fake_openai)
    text, provider = r.complete_vision("prompt", b"png-bytes", "image/png")
    assert text == "extracted text"
    assert provider == "github"
    assert calls == ["gemini", "github"]
    assert r.providers["gemini"].cooldown_until > time.time()  # benched


def test_run_429_benches_with_retry_after(router, no_rate_spacing, monkeypatch):
    r = no_rate_spacing(router)

    def fake_openai(p, model, messages, max_tokens):
        if p.name == "gemini":
            raise _RateLimited(retry_after=300)
        return "ok"

    monkeypatch.setattr(llm_router, "_openai_chat", fake_openai)
    text, provider = r.complete_vision("prompt", b"x", "image/png")
    assert provider == "github"
    cooldown = r.providers["gemini"].cooldown_until - time.time()
    assert 295 <= cooldown <= 301


def test_run_exhausts_all_providers(router, no_rate_spacing, monkeypatch):
    r = no_rate_spacing(router)
    monkeypatch.setattr(
        llm_router, "_openai_chat",
        lambda p, model, messages, max_tokens: (_ for _ in ()).throw(RuntimeError("down")))
    with pytest.raises(RouterError, match="failures:"):
        r.complete_vision("prompt", b"x", "image/png")
    for name in ("gemini", "github", "mistral", "groq"):
        assert r.providers[name].fail_count == 1


def test_run_releases_in_flight_on_failure(router, no_rate_spacing, monkeypatch):
    r = no_rate_spacing(router)
    monkeypatch.setattr(
        llm_router, "_openai_chat",
        lambda p, model, messages, max_tokens: (_ for _ in ()).throw(RuntimeError("down")))
    with pytest.raises(RouterError):
        r.complete_vision("prompt", b"x", "image/png")
    assert all(p.in_flight == 0 for p in r.providers.values())


def test_concurrent_runs_shard_not_queue(router, no_rate_spacing, monkeypatch):
    """Two parallel vision calls must land on two different providers."""
    r = no_rate_spacing(router)
    seen, release = [], threading.Event()

    def slow_openai(p, model, messages, max_tokens):
        seen.append(p.name)
        release.wait(timeout=5)
        return "ok"

    monkeypatch.setattr(llm_router, "_openai_chat", slow_openai)
    threads = [threading.Thread(target=r.complete_vision, args=("p", b"x", "image/png"))
               for _ in range(2)]
    for t in threads:
        t.start()
    deadline = time.time() + 5
    while len(seen) < 2 and time.time() < deadline:
        time.sleep(0.01)
    release.set()
    for t in threads:
        t.join(timeout=5)
    assert sorted(seen) == ["gemini", "github"]


# ── HTTP helpers ─────────────────────────────────────────────────────────

class _Resp:
    def __init__(self, status_code, body=None, headers=None, text=""):
        self.status_code = status_code
        self._body = body or {}
        self.headers = headers or {}
        self.text = text

    def json(self):
        return self._body


def test_openai_chat_parses_response(monkeypatch):
    p = llm_router.Provider("x", "openai", "key", url="http://x", text_model="m")
    monkeypatch.setattr(
        llm_router.requests, "post",
        lambda *a, **k: _Resp(200, {"choices": [{"message": {"content": "hello"}}]}))
    assert llm_router._openai_chat(p, "m", [], 100) == "hello"


def test_openai_chat_429_raises_ratelimited_with_retry_after(monkeypatch):
    p = llm_router.Provider("x", "openai", "key", url="http://x", text_model="m")
    monkeypatch.setattr(
        llm_router.requests, "post",
        lambda *a, **k: _Resp(429, headers={"Retry-After": "17"}))
    with pytest.raises(_RateLimited) as exc:
        llm_router._openai_chat(p, "m", [], 100)
    assert exc.value.retry_after == 17


def test_openai_chat_429_without_header(monkeypatch):
    p = llm_router.Provider("x", "openai", "key", url="http://x", text_model="m")
    monkeypatch.setattr(llm_router.requests, "post", lambda *a, **k: _Resp(429))
    with pytest.raises(_RateLimited) as exc:
        llm_router._openai_chat(p, "m", [], 100)
    assert exc.value.retry_after is None


def test_openai_chat_4xx_raises_runtime_error(monkeypatch):
    p = llm_router.Provider("x", "openai", "key", url="http://x", text_model="m")
    monkeypatch.setattr(
        llm_router.requests, "post", lambda *a, **k: _Resp(400, text="bad param"))
    with pytest.raises(RuntimeError, match="HTTP 400"):
        llm_router._openai_chat(p, "m", [], 100)


def test_vision_message_shape_openai_compat(router, no_rate_spacing, monkeypatch):
    """Image must travel as a data: URI image_url part next to the text part."""
    r = no_rate_spacing(router)
    captured = {}

    def fake_openai(p, model, messages, max_tokens):
        captured["messages"] = messages
        return "ok"

    monkeypatch.setattr(llm_router, "_openai_chat", fake_openai)
    r.complete_vision("describe", b"\x89PNG", "image/png")
    parts = captured["messages"][0]["content"]
    kinds = {part["type"] for part in parts}
    assert kinds == {"text", "image_url"}
    url = next(p for p in parts if p["type"] == "image_url")["image_url"]["url"]
    assert url.startswith("data:image/png;base64,")


# ── status introspection ─────────────────────────────────────────────────

def test_status_reports_all_providers(router):
    status = router.status()
    assert set(status) == set(router.providers)
    assert status["deepseek"]["configured"] is False
    assert status["gemini"]["configured"] is True
    for entry in status.values():
        assert {"configured", "in_flight", "cooldown_s", "ok", "failed"} <= set(entry)
