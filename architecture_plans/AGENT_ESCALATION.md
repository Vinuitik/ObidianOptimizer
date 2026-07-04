# Agent Escalation — a stateful browser-agent that debugs extraction failures

**Status: PLAN / [NOT IMPLEMENTED].** Design + open decisions below. Nothing built yet.

## Why
Extraction fails on the long tail: JS-viewer wrappers (Google Drive), sites where the media
is behind a Download button's XHR, odd formats, imperfect video extraction. Today's
`escalate()` is a **misnomer** — it re-sends the same URL to the same pipeline, no reasoning,
no DOM/network access, so it fails identically. Drive is now special-cased deterministically
([extension `captureDriveFile`]), but special-casing every site doesn't scale. The general
answer is an agent that can *look at the page like a human* and figure out how to get the bytes.

## The vision (user's words, distilled)
On failure → hand the agent the **logs + the tab**. It can read the **DOM** and the **network**
(“is there a link in the network? a button? what does the button call? can we call it
directly?”), keeps the **chat stateful** (no fresh memory each iteration), gets **new logs fed**
after each step, and **terminates** when done. Expensive → **at most 1 active session; every
other escalation task queues.**

## Architecture (proposed)
```
ingest FAILED ──▶ Java marks capture 'failed' + records error         ← prerequisite (failure visibility)
             ──▶ escalation queue (singleton). If a session is active, QUEUE; else START.
Session (server owns state + singleton + queue):
   seed  = { source_url, tab_id, failure_logs }
   loop (bounded: MAX_ITERS, timeout, cost cap):
      LLM (multi-turn, stateful) picks a TOOL ▼
      tool runs in the EXTENSION (that's where DOM/network/cookies live)
      result + newest logs appended to the conversation
   until  LLM emits submit_resource(path|bytes)  → ingest    OR  gives up
   terminate → free the singleton → dequeue next
```

### Tools (executed in the extension content/background context)
| Tool | Does | Powered by |
|---|---|---|
| `get_dom(selector?)` | distilled DOM / a button's attributes + handlers | content script |
| `get_network()` | URLs the tab fetched (spot the real media/download call) | `webRequest`/`performance.getEntries()` |
| `fetch(url, opts)` | call a URL **with the user's session** → bytes | `background` fetch `credentials:'include'` |
| `click(selector)` / observe | trigger a button, watch the resulting request | content script + webRequest |
| `submit_resource(path\|bytes)` | upload found bytes → `/workspace/upload` → `/capture` | reuses existing ingest |
| `get_logs()` | latest `GET /ingest/{id}` status/error | backend |

## Open decisions (need your call before building)
1. **Brain location.** *Recommended:* **backend owns the session** (conversation state, the
   singleton lock, the queue, the LLM router) and sends tool commands to the extension, which
   executes them. Rationale: singleton/queue/cost are server concerns; the LLM router already
   lives server-side. Alternative: brain in the extension (simpler wiring, but the queue/expense
   controls end up client-side and are easy to bypass).
2. **Network visibility depth.** `performance.getEntries()` (URLs only, cheap, no bodies) vs a
   `webRequest` recorder (headers too) vs devtools-protocol attach (bodies, heavy). Start cheap
   (URLs + DOM) and escalate only if needed?
3. **LLM runtime.** Needs **multi-turn tool-use**, which `host-wrapper /complete` (single-shot)
   doesn't do. Options: claude-cli in agent mode, or a new stateful `/agent` chat endpoint.
   Which model? (cost — this is the expensive path.)
4. **Security scope.** The agent can fetch arbitrary URLs with the user's cookies and act in
   their browser. Scope it: only the **active tab's origin**? an allowlist? a confirmation
   step before it fetches/clicks? This is the biggest risk surface — decide the guardrails.
5. **Trigger.** Auto on every failure, or only on user click ("try harder")? Auto is seamless
   but spends tokens on permanent failures (e.g. "content too thin" that no agent can fix).
   *Recommended:* mark failures visible + a per-capture **“Escalate to agent”** button (manual,
   cost-controlled), auto only for a curated set of recoverable failure types.
6. **Limits.** MAX_ITERS, wall-clock timeout, cost cap per session; what happens on give-up
   (leave capture 'failed' with the agent's notes attached).

## Prerequisite (do first, small): failure visibility
The agent is triggered by failures, but right now a job that fails **during processing** dies
silently (see the retry table in this session's notes): submit succeeded → Java marked the
capture `processing` → the embedder job `FAILED` in memory → nobody re-checks. **First step,
independent of the agent:** Java capture worker polls `GET /ingest/{id}`; on `FAILED` → mark the
capture `failed` + surface it (inbox/dashboard) with the error + a re-run/escalate action. This
turns silent drops into something you can see — and is the hook the agent hangs off.

## Relation to what exists
- Drive is handled deterministically now (`captureDriveFile`) — the agent is for the rest.
- Reuses the ingest submit path (`/workspace/upload` + `/capture`) for whatever the agent finds.
- The GPU single-slot / download-ahead concerns are orthogonal (that's throughput, not extraction).
