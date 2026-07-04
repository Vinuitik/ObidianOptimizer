"""Agent escalation — a STATEFUL LLM that debugs an extraction failure (AGENT_ESCALATION.md).

When a job fails on the long tail (JS-viewer wrappers, download-behind-a-button, odd formats),
this hands the failure to an agent that reasons over tools and tries to recover the real
resource, then feeds it back into ingest. Design decisions (locked in the plan):
  - Lives in the embedder; the LLM is claude-cli via host-wrapper (multi-turn kept here as a
    running transcript, since host-wrapper /complete is single-shot).
  - SINGLETON + QUEUE — expensive, so at most one active session; others wait.
  - AUTO-fires on failure (jobs._worker_loop), bounded by MAX_ITERS + a per-signature guard so
    we never re-burn tokens on the same permanently-broken thing.
  - Browser tools (DOM / network / fetch-with-your-session) run in the extension over a
    WebSocket; that bridge is registered at runtime via `register_browser_tools()` — until it
    connects, only SERVER-SIDE tools are available (plain fetch / head).
  - On SUCCESS, appends a fix recipe to the fix-log (AGENT_FIXES_DIR) → we review these and
    codify recurring ones as deterministic code (like the Drive special-case), shrinking future
    agent use.

Flagged OFF by default: set AGENT_ESCALATION_ENABLED=1 to arm it.
"""
from __future__ import annotations

import json
import logging
import os
import queue
import threading
import time
from pathlib import Path
from typing import Callable, Optional
from urllib.parse import urlparse

log = logging.getLogger("embedder.ingest.escalation")

ENABLED = os.environ.get("AGENT_ESCALATION_ENABLED", "0") == "1"
MAX_ITERS = int(os.environ.get("AGENT_ESCALATION_MAX_ITERS", "8"))
FIXES_DIR = Path(os.environ.get("AGENT_FIXES_DIR", "/reports")) / "agent-fixes"

# Failure signatures the agent already tried and failed → don't re-escalate (token guard).
_dead_signatures: set[str] = set()
_lock = threading.Lock()
_queue: "queue.Queue[dict]" = queue.Queue()
_worker_started = False

# Browser-tool executor, injected when the extension WebSocket connects. Signature:
#   (tool_name: str, args: dict) -> dict            (raises/returns {"error": ...} if it can't)
BrowserToolFn = Callable[[str, dict], dict]
_browser_tools: Optional[BrowserToolFn] = None


def register_browser_tools(fn: Optional[BrowserToolFn]) -> None:
    """Called by the WebSocket bridge when the extension connects/disconnects (fn or None)."""
    global _browser_tools
    _browser_tools = fn


def signature(failure: dict) -> str:
    """Stable key for a failure: host + coarse error — so retries of the SAME broken thing are
    suppressed, but a genuinely new failure still escalates."""
    ref = failure.get("ref") or ""
    host = ""
    try:
        host = urlparse(ref).netloc
    except Exception:
        pass
    err = (failure.get("error") or "")[:60]
    return f"{host}|{err}"


def escalatable(failure: dict) -> bool:
    """Skip failures no agent can fix (empty/text errors) — save the tokens. Recoverable ones
    have a URL to chase."""
    if not ENABLED:
        return False
    ref = failure.get("ref") or ""
    if not ref.startswith(("http://", "https://")):
        return False
    return signature(failure) not in _dead_signatures


def escalate(failure: dict) -> None:
    """Queue a failure for the singleton agent (no-op if disabled / not escalatable / dup)."""
    if not escalatable(failure):
        return
    with _lock:
        if signature(failure) in _dead_signatures:
            return
    _ensure_worker()
    _queue.put(failure)
    log.info("escalation queued: %s", failure.get("ref"))


def _ensure_worker() -> None:
    global _worker_started
    with _lock:
        if _worker_started:
            return
        _worker_started = True
    threading.Thread(target=_worker_loop, daemon=True, name="agent-escalation").start()


def _worker_loop() -> None:
    while True:
        failure = _queue.get()   # SINGLETON: one session at a time; the rest wait here
        try:
            run_session(failure)
        except Exception as e:
            log.exception("escalation session crashed: %s", e)


# ── the agent session ─────────────────────────────────────────────────────────

SYSTEM = (
    "You are a debugging agent that recovers a web resource an automated extractor failed to get. "
    "Tools (browser tools need the page open; server tools always work):\n"
    "  get_dom{url} -> page text, links, buttons, meta (find a download link/button)\n"
    "  get_network{url} -> resource URLs the page fetched (spot the real file URL)\n"
    "  browser_fetch{url} -> fetch WITH the user's session; on a file returns {uploaded:<vault path>}\n"
    "  http_head{url} / http_fetch{url} -> server-side probe (content-type, redirects)\n"
    "Strategy: inspect DOM+network → find the real file URL → browser_fetch it → it returns "
    "'uploaded' → reply {submit:<that path>}. Reply EXACTLY ONE json object per turn, nothing else: "
    '{\"tool\":\"<name>\",\"args\":{...}} | {\"submit\":\"<vault path>\"} | {\"give_up\":\"<reason>\"}. '
    "Stay on the failed URL's origin only."
)


def run_session(failure: dict, complete_fn=None, tools: Optional[dict] = None) -> dict:
    """Run the bounded tool-loop for one failure. `complete_fn`/`tools` injectable for tests.
    Returns {status: recovered|gave_up|exhausted, recipe?}."""
    complete_fn = complete_fn or _default_complete
    tools = tools or _default_tools()

    transcript = [f"FAILURE: {json.dumps({k: failure.get(k) for k in ('ref', 'error', 'stage')})}",
                  f"TOOLS: {', '.join(sorted(tools))}"]
    used: list[dict] = []
    for _ in range(MAX_ITERS):
        try:
            step = _parse_step(complete_fn("\n".join(transcript), SYSTEM))
        except Exception as e:
            transcript.append(f"(could not parse your reply: {e}) reply with ONE json object")
            continue
        if "give_up" in step:
            _dead_signatures.add(signature(failure))
            return {"status": "gave_up", "reason": step["give_up"]}
        if "submit" in step:
            recipe = {"signature": signature(failure), "ref": failure.get("ref"),
                      "steps": used, "resource": step["submit"], "at": time.time()}
            _write_fix(recipe)
            _resubmit(step["submit"], failure)
            return {"status": "recovered", "recipe": recipe}
        name, args = step.get("tool"), step.get("args", {})
        result = _run_tool(tools, name, args)
        used.append({"tool": name, "args": args})
        transcript.append(f"TOOL {name}({json.dumps(args)[:200]}) -> {json.dumps(result)[:600]}")
    _dead_signatures.add(signature(failure))
    return {"status": "exhausted"}


def _parse_step(reply: str) -> dict:
    t = reply.strip()
    lo, hi = t.find("{"), t.rfind("}")
    if not (0 <= lo < hi):
        raise ValueError("no json object")
    return json.loads(t[lo:hi + 1])


def _run_tool(tools: dict, name: str, args: dict) -> dict:
    fn = tools.get(name)
    if fn is None:
        return {"error": f"unknown tool '{name}'"}
    try:
        return fn(args)
    except Exception as e:
        return {"error": str(e)[:300]}


# ── tools ─────────────────────────────────────────────────────────────────────

def _default_tools() -> dict:
    """Server-side tools always available; browser tools proxied to the extension when connected."""
    tools = {
        "http_head": lambda a: _http("HEAD", a),
        "http_fetch": lambda a: _http("GET", a),
    }
    if _browser_tools is not None:
        for bt in ("get_dom", "get_network", "browser_fetch"):
            tools[bt] = (lambda name: (lambda a: _browser_tools(name, a)))(bt)
    return tools


def _http(method: str, args: dict) -> dict:
    import httpx
    url = args.get("url")
    if not url:
        return {"error": "url required"}
    r = httpx.request(method, url, follow_redirects=True, timeout=20.0,
                      headers=args.get("headers") or {})
    body = "" if method == "HEAD" else r.text[:1500]
    return {"status": r.status_code, "content_type": r.headers.get("content-type", ""),
            "final_url": str(r.url), "body_preview": body}


def _resubmit(vault_path: str, failure: dict) -> None:
    """Hand the recovered resource back to ingest as a local file."""
    from ingest import jobs
    from mcp_server import _resolve_embed
    resolved = _resolve_embed(vault_path)
    jobs.submit(vault_path, resolved, capture_id=failure.get("capture_id"))


def _default_complete(prompt: str, system: str) -> str:
    from ingest.synthesize import _complete
    return _complete(prompt, system)


# ── fix-log (recurring fixes → codify) ─────────────────────────────────────────

def _write_fix(recipe: dict) -> None:
    """Append a successful fix recipe as one JSON line — the pipeline from 'agent solved it once'
    to 'hardcode it, free forever' (reviewed by a human, turned into deterministic code)."""
    try:
        FIXES_DIR.mkdir(parents=True, exist_ok=True)
        with open(FIXES_DIR / "fixes.jsonl", "a", encoding="utf-8") as f:
            f.write(json.dumps(recipe, ensure_ascii=False) + "\n")
        log.info("fix-log: recorded recipe for %s", recipe.get("signature"))
    except Exception as e:
        log.warning("fix-log write failed: %s", e)
