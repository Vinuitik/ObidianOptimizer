"""
Embedder service tests.

The real model is NOT loaded — all tests use a lightweight mock injected via
the `mock_model_state` fixture. This keeps the suite fast and CI-friendly.
"""
import numpy as np
import pytest
from fastapi.testclient import TestClient

import main as embedder_main


FAKE_DIM = 768


class _MockTokenizer:
    def __call__(self, texts, padding=True, truncation=True, max_length=512, return_tensors="np"):
        batch = len(texts)
        seq = 8
        return {
            "input_ids": np.ones((batch, seq), dtype=np.int64),
            "attention_mask": np.ones((batch, seq), dtype=np.int64),
        }


class _Info:
    def __init__(self, name):
        self.name = name


class _MockSession:
    """Stands in for an onnxruntime.InferenceSession — returns a random
    last_hidden_state so embed_texts can mean-pool and normalise it."""
    def get_inputs(self):
        return [_Info("input_ids"), _Info("attention_mask")]

    def get_outputs(self):
        return [_Info("last_hidden_state")]

    def get_providers(self):
        return ["CPUExecutionProvider"]

    def run(self, output_names, feeds):
        batch, seq = feeds["input_ids"].shape
        return [np.random.randn(batch, seq, FAKE_DIM).astype(np.float32)]


@pytest.fixture(autouse=True)
def mock_model_state():
    """Inject a fake CPU session into the shared state before every test. GPU is
    disabled so embed_texts takes the CPU floor (no gpu_slot/CUDA in tests)."""
    import gpu_slot
    gpu_slot.set_gpu_available(False)
    embedder_main.state.clear()
    embedder_main.state.update(
        {
            "tokenizer": _MockTokenizer(),
            "cpu_session": _MockSession(),
            "gpu_session": None,
            "dim": FAKE_DIM,
            "provider": "CPUExecutionProvider",
            "model_name": "mock-embed-model",
        }
    )
    yield
    embedder_main.state.clear()


@pytest.fixture
def client():
    # lifespan is NOT invoked when using TestClient without context manager,
    # so state must be pre-populated by mock_model_state above.
    return TestClient(embedder_main.app, raise_server_exceptions=True)


# ---------------------------------------------------------------------------
# /health
# ---------------------------------------------------------------------------

def test_health_returns_ok(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["dim"] == FAKE_DIM
    assert body["model"] == "mock-embed-model"
    assert body["device"] in ("GPU", "CPU")


def test_health_reports_cpu_when_no_cuda(client):
    resp = client.get("/health")
    assert resp.json()["device"] == "CPU"


# ---------------------------------------------------------------------------
# /embed — happy paths
# ---------------------------------------------------------------------------

def test_embed_single_text_returns_correct_shape(client):
    resp = client.post("/embed", json={"texts": ["Hello world"]})
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["embeddings"]) == 1
    assert len(body["embeddings"][0]) == FAKE_DIM


def test_embed_batch_returns_one_vector_per_text(client):
    texts = ["First sentence.", "Second sentence.", "Third."]
    resp = client.post("/embed", json={"texts": texts})
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["embeddings"]) == len(texts)
    for vec in body["embeddings"]:
        assert len(vec) == FAKE_DIM


def test_embed_vectors_are_l2_normalised(client):
    resp = client.post("/embed", json={"texts": ["normalise me"]})
    assert resp.status_code == 200
    vec = np.array(resp.json()["embeddings"][0])
    norm = float(np.linalg.norm(vec))
    assert abs(norm - 1.0) < 1e-4, f"Expected unit norm, got {norm}"


def test_embed_response_includes_model_and_dim(client):
    resp = client.post("/embed", json={"texts": ["check metadata"]})
    assert resp.status_code == 200
    body = resp.json()
    assert body["model"] == "mock-embed-model"
    assert body["dim"] == FAKE_DIM


# ---------------------------------------------------------------------------
# /embed — edge cases & validation
# ---------------------------------------------------------------------------

def test_embed_empty_list_returns_422(client):
    resp = client.post("/embed", json={"texts": []})
    assert resp.status_code == 422


def test_embed_missing_texts_field_returns_422(client):
    resp = client.post("/embed", json={"wrong_field": "oops"})
    assert resp.status_code == 422


def test_embed_single_character_text(client):
    resp = client.post("/embed", json={"texts": ["a"]})
    assert resp.status_code == 200
    assert len(resp.json()["embeddings"][0]) == FAKE_DIM


def test_embed_large_batch(client):
    texts = [f"Document number {i}" for i in range(32)]
    resp = client.post("/embed", json={"texts": texts})
    assert resp.status_code == 200
    assert len(resp.json()["embeddings"]) == 32
