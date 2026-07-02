"""
model_runtime embedding-path tests: output selection (sentence_embedding vs
last_hidden_state pooling) and query/document prompt asymmetry.
"""
import numpy as np
import pytest

import gpu_slot
import model_runtime
from model_runtime import state


class _Named:
    def __init__(self, name):
        self.name = name


class _CaptureTokenizer:
    """Records exactly what text reaches the tokenizer (prefix assertions)."""
    def __init__(self):
        self.seen: list[list[str]] = []

    def __call__(self, texts, padding=True, truncation=True, max_length=512,
                 return_tensors="np"):
        self.seen.append(list(texts))
        b = len(texts)
        return {
            "input_ids": np.ones((b, 4), dtype=np.int64),
            "attention_mask": np.ones((b, 4), dtype=np.int64),
        }


class _DualOutputSession:
    """Graph exposing BOTH last_hidden_state and sentence_embedding, like the
    onnx-community embeddinggemma export. The token stream is all zeros, so if
    the code wrongly mean-pools it, the result is a zero vector — while the
    baked-in sentence_embedding is a recognisable ramp."""
    def get_inputs(self):
        return [_Named("input_ids"), _Named("attention_mask")]

    def get_outputs(self):
        return [_Named("last_hidden_state"), _Named("sentence_embedding")]

    def get_providers(self):
        return ["CPUExecutionProvider"]

    def run(self, output_names, feeds):
        b = feeds["input_ids"].shape[0]
        tokens = np.zeros((b, 4, 8), dtype=np.float32)
        sentence = np.tile(np.arange(1, 9, dtype=np.float32), (b, 1))
        return [tokens, sentence]


@pytest.fixture(autouse=True)
def runtime_state():
    gpu_slot.set_gpu_available(False)
    state.clear()
    state.update({
        "tokenizer": _CaptureTokenizer(),
        "cpu_session": _DualOutputSession(),
        "gpu_session": None,
        "dim": 8,
        "provider": "CPUExecutionProvider",
        "model_name": "mock",
    })
    yield
    state.clear()


def test_sentence_embedding_output_preferred_over_pooling():
    [vec] = model_runtime.embed_texts(["anything"])
    ramp = np.arange(1, 9, dtype=np.float32)
    expected = ramp / np.linalg.norm(ramp)
    assert np.allclose(vec, expected, atol=1e-6)  # zero vector = pooled the wrong output


def test_query_and_document_prefixes_differ(monkeypatch):
    monkeypatch.setattr(model_runtime, "EMBED_QUERY_PREFIX", "task: search result | query: ")
    monkeypatch.setattr(model_runtime, "EMBED_DOC_PREFIX", "title: none | text: ")
    tok = state["tokenizer"]

    model_runtime.embed_texts(["how to negotiate"], kind="query")
    assert tok.seen[-1] == ["task: search result | query: how to negotiate"]

    model_runtime.embed_texts(["chunk body"])  # document is the default
    assert tok.seen[-1] == ["title: none | text: chunk body"]


def test_empty_prefixes_leave_text_untouched(monkeypatch):
    monkeypatch.setattr(model_runtime, "EMBED_QUERY_PREFIX", "")
    monkeypatch.setattr(model_runtime, "EMBED_DOC_PREFIX", "")
    tok = state["tokenizer"]
    model_runtime.embed_texts(["plain"], kind="query")
    assert tok.seen[-1] == ["plain"]
