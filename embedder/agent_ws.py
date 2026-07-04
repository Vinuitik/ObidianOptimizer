"""Agent-escalation WebSocket bridge (AGENT_ESCALATION.md stage 3).

The extension holds an OUTBOUND WebSocket to /agent-ws (proxied by nginx, session-gated). The
escalation agent runs on a worker THREAD and calls browser tools synchronously; this bridge
marshals each call onto the asyncio event loop, sends it down the socket, and blocks the thread
until the extension replies. While a client is connected its browser tools are registered with
`escalation`; on disconnect they're removed (agent falls back to server-side tools only).

Only ONE client at a time is needed (the agent is a singleton). A new connection replaces the
previous bridge. Auth is handled at the edge: nginx `auth_request` gates /agent-ws on a valid
session before the upgrade, so this endpoint trusts whoever reaches it (same trust model as /mcp).
"""
from __future__ import annotations

import asyncio
import json
import logging
import uuid

from fastapi import WebSocket, WebSocketDisconnect

from ingest import escalation

log = logging.getLogger("embedder.agent_ws")
RPC_TIMEOUT_S = 60.0


class _Bridge:
    def __init__(self, ws: WebSocket, loop: asyncio.AbstractEventLoop):
        self.ws = ws
        self.loop = loop
        self.pending: dict[str, asyncio.Future] = {}

    async def _rpc(self, tool: str, args: dict) -> dict:
        rid = uuid.uuid4().hex[:8]
        fut = self.loop.create_future()
        self.pending[rid] = fut
        await self.ws.send_text(json.dumps({"id": rid, "tool": tool, "args": args}))
        try:
            return await asyncio.wait_for(fut, RPC_TIMEOUT_S)
        finally:
            self.pending.pop(rid, None)

    def call_sync(self, tool: str, args: dict) -> dict:
        """Invoked from the escalation worker THREAD → marshal onto the loop, block for the reply."""
        try:
            fut = asyncio.run_coroutine_threadsafe(self._rpc(tool, args), self.loop)
            return fut.result(RPC_TIMEOUT_S + 5)
        except Exception as e:
            return {"error": f"browser tool '{tool}' failed: {e}"}

    def emit_sync(self, event: dict) -> None:
        """Fire-and-forget status event (start/tool/done) → extension, from the agent thread."""
        try:
            asyncio.run_coroutine_threadsafe(
                self.ws.send_text(json.dumps(event)), self.loop)
        except Exception:
            pass

    def resolve(self, rid: str, result: dict) -> None:
        fut = self.pending.get(rid)
        if fut is not None and not fut.done():
            fut.set_result(result)


def register(app) -> None:
    @app.websocket("/agent-ws")
    async def agent_ws(ws: WebSocket):     # noqa: unused — registered as a route
        await ws.accept()
        bridge = _Bridge(ws, asyncio.get_running_loop())
        escalation.register_browser_tools(bridge.call_sync)
        escalation.register_status_emitter(bridge.emit_sync)
        log.info("agent-ws: extension connected")
        try:
            while True:
                msg = json.loads(await ws.receive_text())
                if "id" in msg:                       # a tool reply
                    bridge.resolve(msg["id"], msg.get("result", {}))
        except WebSocketDisconnect:
            pass
        except Exception as e:
            log.warning("agent-ws receive error: %s", e)
        finally:
            escalation.register_browser_tools(None)
            escalation.register_status_emitter(None)
            log.info("agent-ws: extension disconnected")
