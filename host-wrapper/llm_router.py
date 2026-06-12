"""LLM router — one gateway for every model provider the app talks to.

Free-tier providers are tried in priority order; Claude (API key or CLI
subscription credits) is the LAST resort so coding/subscription quota is
never burned on bulk image or text work while a free provider still has
quota left.

Concurrency model — queue sharding, not racing: each in-flight request
leases a different provider (in_flight cap of 1), so when the Java worker
sends several images concurrently they fan out across free quotas
(image A → Gemini while image B → Groq). A provider is also rate-spaced
(min_interval between request starts, derived from its free-tier RPM) and
benched on 429/5xx with an escalating cooldown that honors Retry-After.

All keys come from the repo-root .env (single source of truth) — see
.env.example. A provider with no key is simply skipped.
"""

import base64
import json
import os
import shutil
import subprocess
import threading
import time

import requests

REQUEST_TIMEOUT_S = int(os.environ.get("LLM_REQUEST_TIMEOUT_S", "120"))
ACQUIRE_DEADLINE_S = int(os.environ.get("LLM_ACQUIRE_DEADLINE_S", "150"))
TEXT_MAX_TOKENS = int(os.environ.get("LLM_TEXT_MAX_TOKENS", "4096"))
VISION_MAX_TOKENS = int(os.environ.get("LLM_VISION_MAX_TOKENS", "1024"))
CLI_TIMEOUT_S = int(os.environ.get("CLI_TIMEOUT_S", "180"))


class RouterError(Exception):
    """All candidate providers failed or none are configured."""


class _RateLimited(Exception):
    def __init__(self, retry_after):
        self.retry_after = retry_after


class Provider:
    """One upstream. kind: 'openai' (OpenAI-compatible HTTP), 'anthropic', 'cli'."""

    def __init__(self, name, kind, key, url=None,
                 text_model=None, vision_model=None, min_interval=1.0):
        self.name = name
        self.kind = kind
        self.key = (key or "").strip()
        self.url = url
        self.text_model = text_model
        self.vision_model = vision_model
        self.min_interval = min_interval
        # mutable state, guarded by Router._lock
        self.in_flight = 0
        self.next_start = 0.0
        self.cooldown_until = 0.0
        self.consecutive_failures = 0
        self.ok_count = 0
        self.fail_count = 0

    @property
    def configured(self):
        return self.kind == "cli" or bool(self.key)

    def supports(self, capability):
        model = self.vision_model if capability == "vision" else self.text_model
        return model is not None

    def bench(self, seconds=None):
        self.consecutive_failures += 1
        if seconds is None:
            seconds = min(30 * (2 ** (self.consecutive_failures - 1)), 3600)
        self.cooldown_until = time.time() + seconds
        self.fail_count += 1

    def succeed(self):
        self.consecutive_failures = 0
        self.ok_count += 1


def _env(name, default=""):
    return os.environ.get(name, default).strip()


def _build_providers():
    """Free tiers first. min_interval ≈ 60 / free-tier RPM."""
    return {p.name: p for p in [
        Provider("gemini", "openai", _env("GEMINI_API_KEY"),
                 url="https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                 text_model=_env("GEMINI_MODEL", "gemini-2.5-flash"),
                 vision_model=_env("GEMINI_MODEL", "gemini-2.5-flash"),
                 min_interval=4.0),    # 15 RPM free tier
        Provider("github", "openai",
                 _env("GITHUB_MODELS_TOKEN") or _env("GITHUB_TOKEN"),
                 url="https://models.github.ai/inference/chat/completions",
                 text_model=_env("GITHUB_MODELS_MODEL", "openai/gpt-4o-mini"),
                 vision_model=_env("GITHUB_MODELS_MODEL", "openai/gpt-4o-mini"),
                 min_interval=4.0),    # ~15 RPM free tier
        Provider("mistral", "openai", _env("MISTRAL_API_KEY"),
                 url="https://api.mistral.ai/v1/chat/completions",
                 text_model=_env("MISTRAL_MODEL", "mistral-small-latest"),
                 vision_model=_env("MISTRAL_MODEL", "mistral-small-latest"),
                 min_interval=1.5),    # 1 RPS free tier
        Provider("groq", "openai", _env("GROQ_API_KEY"),
                 url="https://api.groq.com/openai/v1/chat/completions",
                 text_model=_env("GROQ_TEXT_MODEL", "llama-3.3-70b-versatile"),
                 vision_model=_env("GROQ_VISION_MODEL",
                                   "meta-llama/llama-4-scout-17b-16e-instruct"),
                 min_interval=2.0),    # 30 RPM free tier
        Provider("deepseek", "openai", _env("DEEPSEEK_API_KEY"),
                 url="https://api.deepseek.com/chat/completions",
                 text_model=_env("DEEPSEEK_MODEL", "deepseek-chat"),
                 vision_model=None,    # no vision endpoint
                 min_interval=1.0),    # paid (cheap) — no hard free limit
        Provider("anthropic", "anthropic", _env("ANTHROPIC_API_KEY"),
                 text_model=_env("ANTHROPIC_MODEL", "claude-haiku-4-5-20251001"),
                 vision_model=_env("ANTHROPIC_MODEL", "claude-haiku-4-5-20251001"),
                 min_interval=1.5),
        Provider("claude-cli", "cli", None,
                 text_model=_env("SYNTH_MODEL", "haiku"),
                 vision_model=None,
                 min_interval=1.0),
    ]}


def _priority(env_name, default):
    raw = _env(env_name) or default
    return [n.strip() for n in raw.split(",") if n.strip()]


# Vision: Gemini first (1500 req/day, native vision). Claude API dead last.
VISION_PRIORITY = _priority("LLM_VISION_PRIORITY",
                            "gemini,github,mistral,groq,anthropic")
# Text: Gemini deliberately LATE — its daily quota is reserved for the image
# backlog. Claude CLI (subscription credits) dead last.
TEXT_PRIORITY = _priority("LLM_TEXT_PRIORITY",
                          "groq,github,mistral,deepseek,gemini,claude-cli")


class Router:
    def __init__(self):
        self.providers = _build_providers()
        self._lock = threading.Lock()
        self._cv = threading.Condition(self._lock)

    # ── leasing ──────────────────────────────────────────────────────────

    def _chain(self, capability):
        order = VISION_PRIORITY if capability == "vision" else TEXT_PRIORITY
        return [self.providers[n] for n in order
                if n in self.providers
                and self.providers[n].configured
                and self.providers[n].supports(capability)]

    def _acquire(self, capability, skip):
        """Lease the highest-priority free provider; block until one frees up."""
        deadline = time.time() + ACQUIRE_DEADLINE_S
        with self._cv:
            while True:
                chain = [p for p in self._chain(capability) if p.name not in skip]
                if not chain:
                    raise RouterError(
                        f"no providers left for '{capability}' "
                        f"(configured: {[p.name for p in self._chain(capability)]}, "
                        f"failed this request: {sorted(skip)})")
                now = time.time()
                for p in chain:
                    if p.cooldown_until > now or p.in_flight >= 1 or p.next_start > now:
                        continue
                    p.in_flight += 1
                    p.next_start = now + p.min_interval
                    return p
                if now >= deadline:
                    raise RouterError(
                        f"all '{capability}' providers busy/cooling for "
                        f"{ACQUIRE_DEADLINE_S}s: "
                        + ", ".join(f"{p.name}(cooldown {max(0, int(p.cooldown_until - now))}s,"
                                    f" in_flight {p.in_flight})" for p in chain))
                self._cv.wait(timeout=0.25)

    def _release(self, provider, ok, retry_after=None):
        with self._cv:
            provider.in_flight -= 1
            if ok:
                provider.succeed()
            else:
                provider.bench(retry_after)
            self._cv.notify_all()

    # ── public API ───────────────────────────────────────────────────────

    def complete_text(self, prompt, system=None, cli_model=None):
        """Returns (text, provider_name). Raises RouterError when exhausted."""
        return self._run("text", lambda p: self._call_text(p, prompt, system, cli_model))

    def complete_vision(self, prompt, image_bytes, media_type):
        """Returns (text, provider_name). Raises RouterError when exhausted."""
        b64 = base64.standard_b64encode(image_bytes).decode()
        return self._run("vision", lambda p: self._call_vision(p, prompt, b64, media_type))

    def _run(self, capability, call):
        skip = set()
        errors = []
        while True:
            try:
                provider = self._acquire(capability, skip)
            except RouterError as e:
                if errors:
                    raise RouterError(f"{e} — failures: {'; '.join(errors)}") from None
                raise
            try:
                text = call(provider)
            except _RateLimited as e:
                self._release(provider, ok=False, retry_after=e.retry_after)
                skip.add(provider.name)
                errors.append(f"{provider.name}: 429 (cooling "
                              f"{e.retry_after or 'default'}s)")
                continue
            except Exception as e:
                self._release(provider, ok=False)
                skip.add(provider.name)
                errors.append(f"{provider.name}: {e}")
                continue
            self._release(provider, ok=True)
            return text, provider.name

    # ── per-kind calls ───────────────────────────────────────────────────

    def _call_text(self, p, prompt, system, cli_model):
        if p.kind == "cli":
            return _claude_cli(prompt, system, cli_model or p.text_model)
        if p.kind == "anthropic":
            return _anthropic_messages(p, [{"role": "user", "content": prompt}],
                                       system=system, max_tokens=TEXT_MAX_TOKENS)
        messages = ([{"role": "system", "content": system}] if system else []) + \
                   [{"role": "user", "content": prompt}]
        return _openai_chat(p, p.text_model, messages, TEXT_MAX_TOKENS)

    def _call_vision(self, p, prompt, b64, media_type):
        if p.kind == "anthropic":
            content = [
                {"type": "image", "source": {"type": "base64",
                                             "media_type": media_type, "data": b64}},
                {"type": "text", "text": prompt},
            ]
            return _anthropic_messages(p, [{"role": "user", "content": content}],
                                       max_tokens=VISION_MAX_TOKENS)
        messages = [{"role": "user", "content": [
            {"type": "text", "text": prompt},
            {"type": "image_url",
             "image_url": {"url": f"data:{media_type};base64,{b64}"}},
        ]}]
        return _openai_chat(p, p.vision_model, messages, VISION_MAX_TOKENS)

    # ── introspection (dashboard / debugging) ────────────────────────────

    def status(self):
        now = time.time()
        with self._lock:
            return {p.name: {
                "configured": p.configured,
                "in_flight": p.in_flight,
                "cooldown_s": max(0, int(p.cooldown_until - now)),
                "ok": p.ok_count,
                "failed": p.fail_count,
            } for p in self.providers.values()}


def _openai_chat(p, model, messages, max_tokens):
    resp = requests.post(
        p.url,
        headers={"Authorization": f"Bearer {p.key}",
                 "Content-Type": "application/json"},
        json={"model": model, "messages": messages, "max_tokens": max_tokens},
        timeout=REQUEST_TIMEOUT_S,
    )
    if resp.status_code == 429:
        raise _RateLimited(_retry_after(resp))
    if resp.status_code >= 400:
        raise RuntimeError(f"HTTP {resp.status_code}: {resp.text[:300]}")
    return resp.json()["choices"][0]["message"]["content"]


def _retry_after(resp):
    try:
        return max(1, int(float(resp.headers.get("Retry-After"))))
    except (TypeError, ValueError):
        return None


def _anthropic_messages(p, messages, system=None, max_tokens=1024):
    import anthropic
    client = anthropic.Anthropic(api_key=p.key)
    kwargs = {"model": p.text_model, "max_tokens": max_tokens, "messages": messages}
    if system:
        kwargs["system"] = system
    try:
        message = client.messages.create(**kwargs)
    except anthropic.RateLimitError:
        raise _RateLimited(None)
    return message.content[0].text


def _claude_cli(prompt, system, model):
    # npm shim is claude.cmd on Windows — which() resolves it, no shell=True
    claude_bin = shutil.which("claude") or "claude"
    cmd = [claude_bin, "-p", "--output-format", "json", "--model", model]
    if system:
        cmd += ["--append-system-prompt", system]
    result = subprocess.run(cmd, input=prompt, capture_output=True, text=True,
                            encoding="utf-8", timeout=CLI_TIMEOUT_S)
    if result.returncode != 0:
        raise RuntimeError(f"claude CLI exit {result.returncode}: {result.stderr[:500]}")
    try:
        return json.loads(result.stdout).get("result", "")
    except json.JSONDecodeError:
        return result.stdout.strip()
