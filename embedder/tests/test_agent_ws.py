"""_Bridge: marshals escalation-agent tool calls (worker THREAD) onto the asyncio
event loop that owns the extension's WebSocket (register()'s FastAPI thread), and
blocks the worker thread until the extension replies. Pure asyncio/threading logic —
no FastAPI TestClient needed to exercise the marshaling contract itself."""
import asyncio
import json
import threading
import time

import pytest

from agent_ws import _Bridge
import agent_ws


@pytest.fixture
def running_loop():
    """A real asyncio loop running on its own thread — mirrors the FastAPI event-loop
    thread that owns the real WebSocket, and is required by run_coroutine_threadsafe
    (it raises RuntimeError against a loop that isn't actually running)."""
    loop = asyncio.new_event_loop()
    t = threading.Thread(target=loop.run_forever, daemon=True)
    t.start()
    yield loop
    loop.call_soon_threadsafe(loop.stop)
    t.join(timeout=2.0)
    loop.close()


class FakeWebSocket:
    def __init__(self):
        self.sent: list[dict] = []
        self.on_send = None  # optional callback(msg_dict) run after recording

    async def send_text(self, text: str) -> None:
        msg = json.loads(text)
        self.sent.append(msg)
        if self.on_send:
            self.on_send(msg)


def test_call_sync_blocksUntilReplyThenReturnsResult(running_loop):
    ws = FakeWebSocket()
    bridge = _Bridge(ws, running_loop)
    # Extension "replies" the instant it sees the request — exercises the real
    # send -> resolve -> unblock path end to end.
    ws.on_send = lambda msg: bridge.resolve(msg["id"], {"ok": True, "echo": msg["tool"]})

    result = bridge.call_sync("click", {"selector": "#go"})

    assert result == {"ok": True, "echo": "click"}
    assert ws.sent[0]["tool"] == "click"
    assert ws.sent[0]["args"] == {"selector": "#go"}


def test_call_sync_eachCallGetsAUniqueRequestId(running_loop):
    ws = FakeWebSocket()
    bridge = _Bridge(ws, running_loop)
    seen_ids = []
    ws.on_send = lambda msg: (seen_ids.append(msg["id"]), bridge.resolve(msg["id"], {}))

    bridge.call_sync("a", {})
    bridge.call_sync("b", {})

    assert len(seen_ids) == 2
    assert seen_ids[0] != seen_ids[1]


def test_call_sync_sendFailure_returnsErrorDict_insteadOfRaising(running_loop):
    ws = FakeWebSocket()

    async def broken_send_text(text):
        raise ConnectionError("socket closed")

    ws.send_text = broken_send_text
    bridge = _Bridge(ws, running_loop)

    result = bridge.call_sync("click", {})

    assert "error" in result
    assert "click" in result["error"]
    assert "socket closed" in result["error"]


def test_call_sync_timesOutIfNeverResolved(running_loop, monkeypatch):
    monkeypatch.setattr(agent_ws, "RPC_TIMEOUT_S", 0.05)
    ws = FakeWebSocket()  # no on_send — extension never replies
    bridge = _Bridge(ws, running_loop)

    start = time.monotonic()
    result = bridge.call_sync("click", {})
    elapsed = time.monotonic() - start

    assert "error" in result
    assert "click" in result["error"]
    assert elapsed < 5.0  # bounded by the patched short timeout, not the real 60s+5s


def test_resolve_unknownRequestId_isNoop():
    loop = asyncio.new_event_loop()
    bridge = _Bridge(FakeWebSocket(), loop)
    bridge.resolve("never-requested", {"x": 1})  # must not raise
    loop.close()


def test_resolve_calledTwiceForSameId_secondCallIsNoop():
    # asyncio.Future.set_result() raises InvalidStateError if called on an already-done
    # future — a duplicate/late "reply" from the extension must not crash the bridge.
    loop = asyncio.new_event_loop()
    bridge = _Bridge(FakeWebSocket(), loop)
    fut = loop.create_future()
    bridge.pending["rid-1"] = fut

    bridge.resolve("rid-1", {"first": True})
    bridge.resolve("rid-1", {"second": True})  # must not raise

    assert fut.result() == {"first": True}
    loop.close()


def test_emitSync_deliversEventOverTheSocket(running_loop):
    ws = FakeWebSocket()
    bridge = _Bridge(ws, running_loop)

    bridge.emit_sync({"type": "tool", "name": "click"})

    deadline = time.monotonic() + 2.0
    while not ws.sent and time.monotonic() < deadline:
        time.sleep(0.01)
    assert ws.sent == [{"type": "tool", "name": "click"}]


@pytest.mark.filterwarnings("ignore:coroutine .* was never awaited:RuntimeWarning")
def test_emitSync_closedLoop_swallowsErrorInsteadOfRaising():
    # run_coroutine_threadsafe rejects a closed loop before the coroutine it was given
    # ever runs, so Python separately warns about that coroutine being un-awaited —
    # a side effect of the closed-loop edge case itself, not something emit_sync controls.
    loop = asyncio.new_event_loop()
    loop.close()  # scheduling onto a closed loop raises RuntimeError
    bridge = _Bridge(FakeWebSocket(), loop)

    bridge.emit_sync({"type": "start"})  # must not raise
