"""
MCP server tests — exercise the real JSON-RPC protocol over streamable HTTP:
initialize → tools/list → tools/call, plus the X-API-Key auth gate.

The DB is monkeypatched at the _query_db seam; embeddings come from the
mock model installed by test_main's autouse fixture pattern (re-declared
here so this module is self-contained).
"""
import numpy as np
import pytest
from fastapi.testclient import TestClient

import main as embedder_main
import mcp_server
import model_runtime
from model_runtime import state

TOKEN = "test-token-123"
FAKE_DIM = 768

MCP_HEADERS = {
    "X-API-Key": TOKEN,
    "Content-Type": "application/json",
    "Accept": "application/json, text/event-stream",
}


class _MockTokenizer:
    def __call__(self, texts, padding=True, truncation=True, max_length=512, return_tensors="np"):
        batch = len(texts)
        return {
            "input_ids": np.ones((batch, 8), dtype=np.int64),
            "attention_mask": np.ones((batch, 8), dtype=np.int64),
        }


class _Info:
    def __init__(self, name):
        self.name = name


class _MockSession:
    """Stands in for an onnxruntime.InferenceSession (see test_main)."""
    def get_inputs(self):
        return [_Info("input_ids"), _Info("attention_mask")]

    def get_outputs(self):
        return [_Info("last_hidden_state")]

    def get_providers(self):
        return ["CPUExecutionProvider"]

    def run(self, output_names, feeds):
        rng = np.random.default_rng(42)
        batch, seq = feeds["input_ids"].shape
        return [rng.standard_normal((batch, seq, FAKE_DIM)).astype(np.float32)]


FAKE_ROWS = [
    ("/vault/ml/FlexMatch.md", 0, "FlexMatch is a semi-supervised learning method " + "x" * 200),
    ("/vault/ml/TypiClust.md", 1, "TypiClust selects typical examples for active learning."),
]


@pytest.fixture(autouse=True)
def mcp_env(monkeypatch):
    """Mock model + mock DB before every test — no Docker needed."""
    import gpu_slot
    gpu_slot.set_gpu_available(False)  # CPU floor only — no CUDA in tests
    state.clear()
    state.update({
        "tokenizer": _MockTokenizer(),
        "cpu_session": _MockSession(),
        "gpu_session": None,
        "dim": FAKE_DIM,
        "provider": "CPUExecutionProvider",
        "model_name": "mock-embed-model",
    })
    monkeypatch.setattr(mcp_server, "_query_db", lambda sql, params=(): list(FAKE_ROWS))
    yield
    state.clear()


@pytest.fixture(scope="module")
def client():
    # Module-scoped: the MCP StreamableHTTPSessionManager can only .run() once
    # per instance, so the lifespan must be entered exactly once for this module.
    mp = pytest.MonkeyPatch()
    mp.setattr(model_runtime, "init", lambda: None)  # lifespan must not load the real model
    # ApiKeyMiddleware reads MCP_API_TOKEN at mount time, so patch its attribute.
    for route in embedder_main.app.routes:
        mounted = getattr(route, "app", None)
        if isinstance(mounted, mcp_server.ApiKeyMiddleware):
            mounted.token = TOKEN
    # base_url=localhost: the MCP SDK's DNS-rebinding protection 421s any other Host
    with TestClient(embedder_main.app, base_url="http://localhost") as c:
        yield c
    mp.undo()


def _rpc(client, method, params=None, id_=1):
    body = {"jsonrpc": "2.0", "id": id_, "method": method, "params": params or {}}
    return client.post("/mcp", json=body, headers=MCP_HEADERS)


def _initialize(client):
    return _rpc(client, "initialize", {
        "protocolVersion": "2025-03-26",
        "capabilities": {},
        "clientInfo": {"name": "pytest", "version": "0"},
    })


# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------

def test_mcp_without_api_key_is_401(client):
    resp = client.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "initialize"},
                       headers={"Accept": "application/json, text/event-stream"})
    assert resp.status_code == 401


def test_mcp_with_wrong_api_key_is_401(client):
    headers = dict(MCP_HEADERS, **{"X-API-Key": "wrong"})
    resp = client.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "initialize"},
                       headers=headers)
    assert resp.status_code == 401


def test_embed_endpoint_does_not_require_api_key(client):
    resp = client.post("/embed", json={"texts": ["hello"]})
    assert resp.status_code == 200


# ---------------------------------------------------------------------------
# Protocol: initialize → tools/list → tools/call
# ---------------------------------------------------------------------------

def test_initialize_returns_server_info(client):
    resp = _initialize(client)
    assert resp.status_code == 200
    result = resp.json()["result"]
    assert result["serverInfo"]["name"] == "obsidian-vault"
    assert "protocolVersion" in result


def test_tools_list_exposes_the_tools(client):
    _initialize(client)
    resp = _rpc(client, "tools/list", id_=2)
    assert resp.status_code == 200
    tools = {t["name"] for t in resp.json()["result"]["tools"]}
    assert tools == {"search_notes", "get_note_content", "find_home_for_note",
                     "ingest_resource", "split_note", "get_vault_tree", "list_folder"}


def test_tools_call_search_notes_returns_rrf_results(client):
    _initialize(client)
    resp = _rpc(client, "tools/call",
                {"name": "search_notes", "arguments": {"query": "semi-supervised", "limit": 5}},
                id_=3)
    assert resp.status_code == 200
    result = resp.json()["result"]
    assert result.get("isError") is not True
    # structuredContent carries the typed return value
    results = result["structuredContent"]["result"]
    assert len(results) == 2  # two distinct note paths in FAKE_ROWS
    assert results[0]["notePath"].endswith(".md")
    assert "snippet" in results[0]
    # both mocked rankers return the same rows → matched by both sources;
    # FAKE_ROWS are 3-tuples (no similarity column) → similarity is None
    assert results[0]["matchedBy"] == "keyword+semantic"
    assert results[0]["similarity"] is None
    # snippet truncated at 150 chars + ellipsis
    long_one = next(r for r in results if r["notePath"].endswith("FlexMatch.md"))
    assert long_one["snippet"].endswith("...")
    assert len(long_one["snippet"]) == 153


def test_rrf_merge_reports_similarity_and_sources():
    # semantic rows carry a 4th similarity column; keyword rows don't.
    semantic = [("/vault/a.md", 0, "alpha text", 0.71)]
    keyword = [("/vault/a.md", 0, "alpha text"), ("/vault/b.md", 0, "beta text")]
    results = mcp_server._rrf_merge([("semantic", semantic), ("keyword", keyword)], 10)
    by_path = {r["notePath"]: r for r in results}
    assert by_path["/vault/a.md"]["matchedBy"] == "keyword+semantic"
    assert by_path["/vault/a.md"]["similarity"] == 0.71
    assert by_path["/vault/b.md"]["matchedBy"] == "keyword"
    assert by_path["/vault/b.md"]["similarity"] is None
    # the both-ranker hit outranks the keyword-only hit
    assert results[0]["notePath"] == "/vault/a.md"


def test_get_vault_tree_and_list_folder(tmp_path, monkeypatch):
    (tmp_path / "AI" / "ML").mkdir(parents=True)
    (tmp_path / "resources").mkdir()          # skipped: media folder
    (tmp_path / ".obsidian").mkdir()          # skipped: dotfolder
    (tmp_path / "AI" / "intro.md").write_text("x")
    (tmp_path / "AI" / "ML" / "sgd.md").write_text("x")
    (tmp_path / "AI" / "ML" / "adam.md").write_text("x")
    (tmp_path / "root.md").write_text("x")
    monkeypatch.setattr(mcp_server, "VAULT_DIR", tmp_path)

    tree = {t["folder"]: t["noteCount"] for t in mcp_server.get_vault_tree()}
    assert tree["/vault"] == 1
    assert tree["/vault/AI"] == 1
    assert tree["/vault/AI/ML"] == 2
    assert not any("resources" in f or ".obsidian" in f for f in tree)

    # default: folders + count only — note names are opt-in
    listing = mcp_server.list_folder("AI/ML")
    assert listing["folder"] == "/vault/AI/ML"
    assert listing["noteCount"] == 2
    assert "notes" not in listing
    assert listing["subfolders"] == []

    with_notes = mcp_server.list_folder("AI/ML", include_notes=True)
    assert with_notes["notes"] == ["adam", "sgd"]
    assert with_notes["notesTruncated"] is False

    # "." == vault root (the '/vault' spelling only resolves when VAULT_DIR is /vault)
    root_listing = mcp_server.list_folder(".")
    assert "AI" in root_listing["subfolders"]

    with pytest.raises(ValueError):
        mcp_server.list_folder("../outside")


def test_tools_call_find_home_returns_folder_suggestions(client):
    _initialize(client)
    resp = _rpc(client, "tools/call",
                {"name": "find_home_for_note", "arguments": {"proposed_title": "Active learning"}},
                id_=4)
    assert resp.status_code == 200
    # dict-returning tools are serialized as JSON text content by the SDK
    import json as _json
    out = _json.loads(resp.json()["result"]["content"][0]["text"])
    assert out["suggested_folders"][0] == "/vault/ml"
    assert "FlexMatch" in out["name_examples"]


def test_get_note_content_rejects_path_escape(client):
    _initialize(client)
    resp = _rpc(client, "tools/call",
                {"name": "get_note_content", "arguments": {"note_path": "../../etc/passwd"}},
                id_=5)
    assert resp.status_code == 200
    assert resp.json()["result"]["isError"] is True


# ---------------------------------------------------------------------------
# Path validation unit tests (no protocol round-trip)
# ---------------------------------------------------------------------------

def test_resolve_in_vault_accepts_relative(tmp_path, monkeypatch):
    monkeypatch.setattr(mcp_server, "VAULT_DIR", tmp_path)
    (tmp_path / "note.md").write_text("hi")
    assert mcp_server._resolve_in_vault("note.md").name == "note.md"


def test_resolve_in_vault_rejects_traversal(tmp_path, monkeypatch):
    monkeypatch.setattr(mcp_server, "VAULT_DIR", tmp_path)
    with pytest.raises(ValueError):
        mcp_server._resolve_in_vault("../outside.md")


def test_resolve_in_vault_rejects_absolute_outside(tmp_path, monkeypatch):
    # NB: a "C:/..." path is only absolute on Windows; on POSIX it's a weird
    # relative subpath, so use a genuinely absolute outside path instead.
    monkeypatch.setattr(mcp_server, "VAULT_DIR", tmp_path)
    with pytest.raises(ValueError):
        mcp_server._resolve_in_vault(str(tmp_path.parent / "outside.md"))
