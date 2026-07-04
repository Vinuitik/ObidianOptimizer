# Agent Escalation — a stateful browser-agent that debugs extraction failures

**Status: FOUNDATION BUILT (flagged off), browser-tools bridge remaining.**
Built + unit-tested (`embedder/ingest/escalation.py`, flag `AGENT_ESCALATION_ENABLED`, default off):
- ✅ SINGLETON session + queue; **auto-trigger** on job failure (`jobs._maybe_escalate`).
- ✅ Bounded tool-loop (`run_session`, MAX_ITERS) with a running transcript as the LLM's memory
  (LLM = claude-cli via host-wrapper `_complete`); per-signature **give-up guard** (no token re-burn).
- ✅ Server-side tools (`http_head`/`http_fetch`) + `submit`/`give_up`; recovered resource → `jobs.submit`.
- ✅ **Fix-log** (`_write_fix` → `AGENT_FIXES_DIR/agent-fixes/fixes.jsonl`) — every success recorded
  as `{signature, ref, steps, resource}` → codify recurring ones into deterministic handlers.

✅ **Stage 3 — browser tools over WebSocket (built, browser-unverified):**
- Embedder `/agent-ws` (`agent_ws.py`, registered in `main.py`) — thread↔asyncio bridge marshals
  the agent's sync tool calls onto the loop, over the socket, and blocks for the reply.
- nginx `/agent-ws?token=<AGENT_WS_TOKEN>` — SAME token-in-URL auth as `/mcp` (env
  `AGENT_WS_TOKEN`, filter extended); WS upgrade proxied to the embedder.
- Extension WS client (`background.js`) — connects when a token is set (⚙ Settings), runs
  `get_dom`/`get_network`/`browser_fetch` **scoped to the failed tab's origin**, replies over the
  socket; reconnects (MV3 SW-wake caveat).

✅ **Activity UI + armed (deployed):** the embedder emits `start`/`tool`/`done` events over the
socket (`escalation._emit` → `bridge.emit_sync`); the extension logs them (`recordAgentEvent` →
`agentLog`) and the popup shows a live feed + a working/fixed/failed badge (`renderAgentFeed`).
Deployed: `AGENT_ESCALATION_ENABLED=1` (embedder) + `AGENT_WS_TOKEN` (nginx) set in `.env`;
`/agent-ws` verified through nginx (valid token connects, wrong 401s). **User step:** reload the
Firefox extension + paste the token in ⚙ Settings.

Remaining: **host-wrapper multi-turn** refinement (transcript-as-prompt works but is crude); a
richer **failed-captures** view; a real end-to-end run (agent recovering a live failure).

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

## Decisions (LOCKED)
1. **Brain location → embedder, with the other agents.** The escalation session (conversation
   state, singleton lock, queue) lives in the **embedder**; it calls **host-wrapper** for the
   LLM so it gets **claude-cli** (multi-turn). Same home as the existing ingest/synthesis agents.
2. **Tool channel → extension-initiated WebSocket (on-demand request/response).** A browser
   extension **cannot accept inbound connections**, so it opens a **persistent outbound
   WebSocket** to the backend on startup; the embedder session sends `{tool,args}` down that
   socket and the extension replies `{result}`. This gives on-demand DOM/network reads (NOT a
   periodically-pushed stale cache). Constraints to handle: **MV3 service-worker can be killed**
   (keep-alive / reconnect while a session is active); **WS must survive nginx + cloudflared**
   (both proxy WebSockets — needs the `Upgrade` headers in `nginx.conf.template`). Target the
   right client+tab by id in each command.
3. **Network visibility → start cheap.** `performance.getEntries()` (resource URLs, no bodies) +
   DOM inspection first; add a `webRequest` recorder only if URLs+DOM prove insufficient.
4. **LLM runtime → host-wrapper → claude-cli**, multi-turn. The session accumulates the message
   list server-side (embedder) and re-sends it each call; host-wrapper needs a chat/multi-turn
   mode (today `/complete` is single-shot — extend it or add `/chat`).
5. **Security → active-tab origin only.** Tools may read DOM / inspect network / `fetch` ONLY on
   the **origin of the failed tab**. No roaming to other sites with the user's cookies.
6. **Trigger → AUTO on failure**, but bounded: MAX_ITERS, wall-clock timeout, cost cap, and
   **don't re-escalate a failure signature the agent already failed** (avoid token burn on the
   permanently-broken). Singleton: one active session; others queue.

## Agent fix-log → codify recurring fixes (user requirement)
Every time the agent succeeds, it **appends a record of the fix** — `{failure signature (url
pattern / error / site), the recipe that worked (the network call it found, the button, the
rewrite)}` — to a dedicated **fix-log folder** (`AGENT_FIXES_DIR`, e.g. `_reports/agent-fixes/`
or a repo folder). Purpose: we review these and **implement the recurring ones as deterministic
code** (exactly like the Drive `captureDriveFile` special-case), so that failure type stops
needing the (expensive) agent. The fix-log is the pipeline from "agent figured it out once" →
"hardcoded, free, forever." Over time the agent handles only genuinely novel failures.

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
